/*
 * Copyright 2013 David Kunzler Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may obtain a copy of the License
 * at http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law or agreed to in
 * writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package de.devland.esperandro.processor.generation;

import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import de.devland.esperandro.annotations.experimental.Cached;
import de.devland.esperandro.processor.PreferenceInformation;
import de.devland.esperandro.processor.Utils;

import javax.lang.model.element.Modifier;

public class PutterGenerator {

    void createPrivate(TypeSpec.Builder type, PreferenceInformation info, Cached cachedAnnotation) {
        createInternal(type, info, cachedAnnotation, false, true);
    }

    public void create(TypeSpec.Builder type, PreferenceInformation info, Cached cachedAnnotation, boolean isCommitSetter) {
        createInternal(type, info, cachedAnnotation, isCommitSetter, false);
    }

    private void createInternal(TypeSpec.Builder type, PreferenceInformation info, Cached cachedAnnotation,
                                boolean isCommitSetter, boolean internal) {
        MethodSpec.Builder putterBuilder = MethodSpec.methodBuilder(info.preferenceName)
                .addModifiers(internal ? Modifier.PRIVATE : Modifier.PUBLIC)
                .addParameter(info.preferenceType.getType(), info.preferenceName);
        if (!internal) {
            putterBuilder.addAnnotation(Override.class);
        }
        PreferenceEditorCommitStyle commitStyle = PreferenceEditorCommitStyle.APPLY;
        StringBuilder statementPattern = new StringBuilder("preferences.edit().put%s(\"%s\", %s)");

        if (isCommitSetter) {
            putterBuilder.returns(boolean.class);
            statementPattern.insert(0, "return ");
            commitStyle = PreferenceEditorCommitStyle.COMMIT;
        } else {
            putterBuilder.returns(void.class);
        }

        String methodSuffix = Utils.getMethodSuffix(info.preferenceType.getPreferenceType());
        String value = info.preferenceName;
        switch (info.preferenceType.getPreferenceType()) {
            case OBJECT:
                if (info.preferenceType.isGeneric()) {
                    String genericClassName = Utils.createClassNameForPreference(info.preferenceName);
                    putterBuilder.addStatement("$L __container = new $L()", genericClassName, genericClassName);
                    putterBuilder.addStatement("__container.value = $L", info.preferenceName);
                    value = "Esperandro.getSerializer().serialize(__container)";
                } else {
                    value = String.format("Esperandro.getSerializer().serialize(%s)", info.preferenceName);
                }
                break;
            case UNKNOWN:
                break;
        }

        if (cachedAnnotation != null) {
            if (cachedAnnotation.cacheOnPut()) {
                if (info.preferenceType.isPrimitive()) {
                    putterBuilder.addStatement("cache.put($S, $L)", info.preferenceName, info.preferenceName);
                } else {
                    putterBuilder.beginControlFlow("if ($L != null)", info.preferenceName)
                            .addStatement("cache.put($S, $L)", info.preferenceName, info.preferenceName)
                            .nextControlFlow("else")
                            .addStatement("cache.remove($S)", info.preferenceName)
                            .endControlFlow();
                }
            } else {
                putterBuilder.addStatement("cache.remove($S)", info.preferenceName);
            }
        }

        putterBuilder.addStatement(String.format(statementPattern.toString(),
                methodSuffix, info.preferenceName, value) + ".$L", commitStyle.getStatementPart());

        type.addMethod(putterBuilder.build());
    }
}
