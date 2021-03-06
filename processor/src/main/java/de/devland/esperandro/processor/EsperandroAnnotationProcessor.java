/*
 * Copyright 2013 David Kunzler
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */

package de.devland.esperandro.processor;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

import de.devland.esperandro.CacheActions;
import de.devland.esperandro.SharedPreferenceActions;
import de.devland.esperandro.SharedPreferenceMode;
import de.devland.esperandro.UnsafeActions;
import de.devland.esperandro.annotations.SharedPreferences;
import de.devland.esperandro.annotations.experimental.Cached;
import de.devland.esperandro.annotations.experimental.GenerateStringResources;
import de.devland.esperandro.processor.generation.AdderGenerator;
import de.devland.esperandro.processor.generation.GenericActionsGenerator;
import de.devland.esperandro.processor.generation.GetterGenerator;
import de.devland.esperandro.processor.generation.PutterGenerator;
import de.devland.esperandro.processor.generation.RemoverGenerator;
import de.devland.esperandro.processor.generation.StringConstantsGenerator;
import de.devland.esperandro.processor.generation.StringResourceGenerator;
import de.devland.esperandro.processor.generation.UnsafeActionsGenerator;

@SupportedAnnotationTypes("de.devland.esperandro.annotations.SharedPreferences")
@SupportedOptions(EsperandroAnnotationProcessor.OPTION_VALUES_DIR)
public class EsperandroAnnotationProcessor extends AbstractProcessor {

    public static final String OPTION_VALUES_DIR = "esperandro_valuesDir";

    private static final Set<String> SUPER_INTERFACE_BLACKLIST;

    static {
        Set<String> blacklist = new HashSet<>();
        blacklist.add(SharedPreferenceActions.class.getName());
        blacklist.add(CacheActions.class.getName());
        blacklist.add(UnsafeActions.class.getName());
        SUPER_INTERFACE_BLACKLIST = Collections.unmodifiableSet(blacklist);
    }

    private Warner warner;
    private Map<TypeMirror, Element> rootElements;
    private String valuesLocation;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        valuesLocation = processingEnv.getOptions().get(OPTION_VALUES_DIR);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        warner = new Warner(processingEnv);
        rootElements = new HashMap<>();

        preProcessEnvironment(roundEnv);


        for (TypeElement typeElement : annotations) {
            if (typeElement.getQualifiedName().toString().equals(Constants.SHARED_PREFERENCES_ANNOTATION_NAME)) {
                Set<? extends Element> interfaces = roundEnv.getElementsAnnotatedWith(SharedPreferences.class);

                for (Element interfaze : interfaces) {
                    // fix some weird behaviour where getElementsAnnotatedWith returns more elements than expected
                    if (interfaze.getKind() == ElementKind.INTERFACE && interfaze.getAnnotation(SharedPreferences
                            .class) != null) {
                        try {
                            // collect all PreferenceInformation
                            Collection<PreferenceInformation> allPreferences = analyze(interfaze, interfaze);
                            postProcess(interfaze, allPreferences);

                            if (hasErrors(interfaze, allPreferences)) {
                                return false; // do not generate anything if errors are there
                            }
                            // handle errors and warning, check type for adder/remover
                            generateWarnings(interfaze, allPreferences);
                            Cached cacheAnnotation = interfaze.getAnnotation(Cached.class);
                            boolean caching = cacheAnnotation != null;
                            TypeSpec.Builder type = initImplementation(interfaze, cacheAnnotation);

                            createMethods(type, allPreferences, cacheAnnotation);
                            GenericActionsGenerator.createGenericActions(type, allPreferences, caching);
                            UnsafeActionsGenerator.createUnsafeActions(type, allPreferences, cacheAnnotation);
                            createGenericClassImplementations(type, allPreferences);
                            createDefaultConstructor(type, allPreferences, cacheAnnotation);
                            finish(interfaze, allPreferences, type);
                            StringConstantsGenerator.createConstantsClass(processingEnv.getFiler(), interfaze, allPreferences);
                        } catch (Exception e) {
                            StringWriter sw = new StringWriter();
                            PrintWriter pw = new PrintWriter(sw);
                            e.printStackTrace(pw);
                            String stacktrace = sw.toString().replace("\n", " - ");
                            warner.emitError("Esperandro Processor Error: " + stacktrace, interfaze);
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        }


        return false;
    }

    private void postProcess(Element interfaze, Collection<PreferenceInformation> allPreferences) {
        for (PreferenceInformation info : allPreferences) {
            // add interfaze as element for every method without explicit element for warnings
            if (info.setter != null && info.setter.element == null) info.setter.element = interfaze;
            if (info.getter != null && info.getter.element == null) info.getter.element = interfaze;
            if (info.runtimeDefaultGetter != null && info.runtimeDefaultGetter.element == null)
                info.runtimeDefaultGetter.element = interfaze;
            if (info.commitSetter != null && info.commitSetter.element == null)
                info.commitSetter.element = interfaze;
            if (info.adder != null && info.adder.element == null) info.adder.element = interfaze;
            if (info.remover != null && info.remover.element == null)
                info.remover.element = interfaze;

            // find common preferenceType
            info.preferenceType = commonPreferenceType(
                    info.setter != null ? info.setter.parameterType : null,
                    info.getter != null ? info.getter.returnType : null,
                    info.runtimeDefaultGetter != null ? info.runtimeDefaultGetter.returnType : null,
                    info.runtimeDefaultGetter != null ? info.runtimeDefaultGetter.parameterType : null,
                    info.commitSetter != null ? info.commitSetter.parameterType : null
            );
        }
    }

    private PreferenceTypeInformation commonPreferenceType(PreferenceTypeInformation... types) {
        boolean same = true;
        PreferenceTypeInformation candidate = null;
        for (PreferenceTypeInformation type : types) {
            if (type != null) {
                if (candidate == null) {
                    candidate = type;
                } else {
                    if (!candidate.getTypeName().equals(type.getTypeName())) {
                        same = false;
                    }
                }
            }
        }

        return same ? candidate : null;
    }

    private boolean hasErrors(Element interfaze, Collection<PreferenceInformation> allPreferences) {
        boolean hasErrors = false;
        for (PreferenceInformation info : allPreferences) {
            if (info.preferenceType == null) {
                warner.emitError("Found different types for the same preference (" + info.preferenceName + "). Aborting.", interfaze);
                hasErrors = true;
            }
            if (info.adder != null && (info.setter == null && info.commitSetter == null || info.getter == null)) {
                warner.emitError("Missing getter or setter for " + info.preferenceName + ", add will not work" +
                        " without one of them.", info.adder.element);
                hasErrors = true;
            }
            if (info.remover != null && (info.setter == null && info.commitSetter == null || info.getter == null)) {
                warner.emitError("Missing getter or setter for " + info.preferenceName + ", remove will not work" +
                        " without one of them.", info.remover.element);
                hasErrors = true;
            }
            Types types = processingEnv.getTypeUtils();
            if (info.adder != null || info.remover != null) {
                if (info.getter != null) { // check with getter, if it is not there there will already be an error
                    boolean isCollection = false;
                    if (info.getter.method != null) {
                        isCollection = Collection.class.isAssignableFrom(info.getter.method.getReturnType());
                    } else if (info.getter.element != null) {
                        TypeMirror collection = processingEnv.getElementUtils().getTypeElement("java.util.Collection").asType();
                        TypeMirror returnType = ((ExecutableElement) info.getter.element).getReturnType();
                        isCollection = types.isAssignable(
                                types.erasure(returnType), types.erasure(collection));
                    }
                    if (!isCollection) {
                        warner.emitError("Type of " + info.preferenceName + " is not a Collection. " +
                                "No collection operations possible.", interfaze);
                    }
                }
            }
        }

        return hasErrors;
    }

    private void generateWarnings(Element interfaze, Collection<PreferenceInformation> allPreferences) {
        for (PreferenceInformation info : allPreferences) {
            if (info.getter == null) {
                warner.emitWarning("Missing getter or setter for '" + info.preferenceName + "', initDefaults" +
                        " will possibly not work as expected.", interfaze);
            }
        }
    }

    private void createMethods(TypeSpec.Builder type, Collection<PreferenceInformation> allPreferences, Cached cachedAnnotation) {
        // reinitialize getterGenerator and putter to start fresh for each interface
        GetterGenerator getterGenerator = new GetterGenerator(warner, processingEnv);
        PutterGenerator putterGenerator = new PutterGenerator();

        for (PreferenceInformation info : allPreferences) {
            if (info.setter != null || info.commitSetter != null) {
                putterGenerator.create(type, info, cachedAnnotation, info.commitSetter != null);
            }
            if (info.getter != null) {
                getterGenerator.create(type, info, cachedAnnotation, false);
            }
            if (info.runtimeDefaultGetter != null) {
                getterGenerator.create(type, info, cachedAnnotation, true);
            }
            if (info.adder != null) {
                AdderGenerator.generate(type, info);
            }
            if (info.remover != null) {
                RemoverGenerator.generate(type, info);
            }
        }
    }

    private void createGenericClassImplementations(TypeSpec.Builder type, Collection<PreferenceInformation> allPreferences) {
        for (PreferenceInformation info : allPreferences) {
            if (info.preferenceType.isGeneric()) {
                FieldSpec serialVersionUid = FieldSpec.builder(TypeName.LONG, "serialVersionUID", Modifier.PRIVATE)
                        .initializer("1L")
                        .build();
                TypeSpec innerGenericType = TypeSpec.classBuilder(Utils.createClassNameForPreference(info.preferenceName))
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .addSuperinterface(Serializable.class)
                        .addField(serialVersionUid)
                        .addField(info.preferenceType.getType(), "value", Modifier.PUBLIC)
                        .build();

                type.addType(innerGenericType);
            }
        }
    }

    private void preProcessEnvironment(RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getRootElements()) {
            rootElements.put(element.asType(), element);
        }
    }

    private Collection<PreferenceInformation> analyze(Element topLevelInterface, Element currentInterface) {
        Map<String, PreferenceInformation> informationByType = new HashMap<>();
        analyze(topLevelInterface, currentInterface, informationByType);
        return informationByType.values();
    }

    private void analyze(Element topLevelInterface, Element currentInterface, Map<String, PreferenceInformation> informationByType) {
        List<? extends Element> potentialMethods = currentInterface.getEnclosedElements();
        for (Element element : potentialMethods) {
            if (element.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement) element;
                String preferenceName = PreferenceClassifier.preferenceName(method);
                PreferenceInformation info = informationByType.get(preferenceName);
                if (info == null) {
                    info = new PreferenceInformation();
                    info.preferenceName = preferenceName;
                    informationByType.put(preferenceName, info);
                }
                PreferenceClassifier.analyze(method, info);
            }
        }

        List<? extends TypeMirror> interfaces = ((TypeElement) currentInterface).getInterfaces();
        for (TypeMirror subInterfaceType : interfaces) {
            Element subInterface = rootElements.get(subInterfaceType);
            String subInterfaceTypeName = subInterfaceType.toString();
            if (!SUPER_INTERFACE_BLACKLIST.contains(subInterfaceTypeName)) {
                if (subInterface != null) {
                    analyze(topLevelInterface, subInterface, informationByType);
                } else {
                    try {
                        Class<?> subInterfaceClass = Class.forName(subInterfaceTypeName);
                        analyze(subInterfaceClass, informationByType);
                    } catch (ClassNotFoundException e) {
                        warner.emitError("Could not load Interface '" + subInterfaceTypeName + "' for generation.",
                                topLevelInterface);
                    }
                }
            }
        }
    }

    private void analyze(Class<?> interfaceClass, Map<String, PreferenceInformation> informationByType) {
        for (Method method : interfaceClass.getDeclaredMethods()) {
            String preferenceName = PreferenceClassifier.preferenceName(method);
            PreferenceInformation info = informationByType.get(preferenceName);
            if (info == null) {
                info = new PreferenceInformation();
                info.preferenceName = preferenceName;
                informationByType.put(preferenceName, info);
            }
            PreferenceClassifier.analyze(method, info);
        }

        for (Class<?> subInterfaceClass : interfaceClass.getInterfaces()) {
            if (subInterfaceClass.getName() != null && !subInterfaceClass.getName().equals(SharedPreferenceActions
                    .class.getName())) {
                analyze(subInterfaceClass, informationByType);
            }
        }
    }

    private TypeSpec.Builder initImplementation(Element interfaze, Cached cacheAnnotation) {
        TypeSpec.Builder result;
        SharedPreferences prefAnnotation = interfaze.getAnnotation(SharedPreferences.class);
        String preferencesName = prefAnnotation.name();
        SharedPreferenceMode mode = prefAnnotation.mode();
        if (cacheAnnotation != null && preferencesName.equals("")) {
            warner.emitWarning("Caching should not be used on default SharedPreferences. This is not officially supported.", interfaze);
        }

        try {
            boolean preferenceNamePresent = !preferencesName.equals("");
            String typeName = Utils.classNameFromInterface(interfaze) + Constants.IMPLEMENTATION_SUFFIX;
            result = TypeSpec.classBuilder(typeName)
                    .addSuperinterface(TypeName.get(interfaze.asType()))
                    .addField(ClassName.get("android.content", "SharedPreferences"), "preferences", Modifier.PRIVATE, Modifier.FINAL);
            if (Utils.isPublic(interfaze)) {
                result.addModifiers(Modifier.PUBLIC);
            }
            if (cacheAnnotation != null) {
                result.addSuperinterface(CacheActions.class);
            }
            MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(ClassName.get("android.content", "Context"), "context");
            if (preferenceNamePresent) {
                constructor.addStatement("this.preferences = context.getSharedPreferences($S, $L)", preferencesName,
                        mode.getSharedPreferenceModeStatement());
            } else {
                constructor.addStatement("this.preferences = $T.getDefaultSharedPreferences(context)", ClassName.get("android.preference", "PreferenceManager"));
            }

            if (cacheAnnotation != null) {
                constructor.addParameter(TypeName.INT, "cacheSize");
                ClassName cacheClass;
                if (cacheAnnotation.support()) {
                    cacheClass = ClassName.get("android.support.v4.util", "LruCache");
                } else {
                    cacheClass = ClassName.get("android.util", "LruCache");
                }
                ParameterizedTypeName lruCache = ParameterizedTypeName.get(
                        cacheClass,
                        ClassName.get(String.class),
                        ClassName.get(Object.class));
                result.addField(lruCache, "cache", Modifier.PRIVATE, Modifier.FINAL);

                constructor.addStatement("cache = new LruCache<$T, $T>(cacheSize)", String.class, Object.class);
            }

            result.addMethod(constructor.build());

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    private void createDefaultConstructor(TypeSpec.Builder type, Collection<PreferenceInformation> allPreferences, Cached cacheAnnotation) {
        if (cacheAnnotation != null) {
            MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(ClassName.get("android.content", "Context"), "context");
            int cacheSize = cacheAnnotation.cacheSize();
            if (cacheAnnotation.autoSize()) {
                cacheSize = allPreferences.size();
            }
            constructor.addStatement("this(context, $L)", cacheSize);
            type.addMethod(constructor.build());
        }
    }

    private void finish(Element interfaze, Collection<PreferenceInformation> allPreferences, TypeSpec.Builder type) throws IOException {
        GenerateStringResources generateAnnotation = interfaze.getAnnotation(GenerateStringResources.class);
        String packageName = Utils.packageNameFromInterface(interfaze);

        JavaFile javaFile = JavaFile.builder(packageName, type.build())
                .build();
        Filer filer = processingEnv.getFiler();
        javaFile.writeTo(filer);

        if (generateAnnotation != null) {
            StringResourceGenerator resourceGenerator = new StringResourceGenerator(valuesLocation, processingEnv, warner);
            resourceGenerator.generateStringResources(interfaze, allPreferences, generateAnnotation);
        }
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }
}
