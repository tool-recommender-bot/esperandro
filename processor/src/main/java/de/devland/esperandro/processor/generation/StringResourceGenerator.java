package de.devland.esperandro.processor.generation;

import de.devland.esperandro.annotations.experimental.GenerateStringResources;
import de.devland.esperandro.processor.PreferenceInformation;
import de.devland.esperandro.processor.Warner;

import javax.lang.model.element.Element;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;

/**
 * @author David Kunzler on 18.07.2017.
 */
public class StringResourceGenerator {

    private static final String STRING_RESOURCES_HINT = "<!--generated by esperandro-->\n";

    private final String resDirLocation;
    private final Warner warner;

    public StringResourceGenerator(String resDirLocation, Warner warner) {
        this.resDirLocation = resDirLocation;
        this.warner = warner;
    }


    public void generateStringResources(Element interfaze, Collection<PreferenceInformation> allPreferences, GenerateStringResources generateAnnotation) throws IOException {
        if (resDirLocation != null && new File(resDirLocation, "values").exists()) {
            Path stringsPath = Paths.get(resDirLocation, "values",
                    generateAnnotation.filePrefix() + interfaze.getSimpleName() + ".xml");
            if (Files.exists(stringsPath)) {
                List<String> lines = Files.readAllLines(stringsPath, StandardCharsets.UTF_8);
                if (lines != null && !lines.isEmpty()) {
                    if (STRING_RESOURCES_HINT.startsWith(lines.get(0))) {
                        Files.deleteIfExists(stringsPath);
                    } else {
                        warner.emitError("File '" + stringsPath.toString() + "' was not generated by esperandro and " +
                                "will therefore not get deleted.", interfaze);
                    }
                } else {
                    Files.deleteIfExists(stringsPath);
                }
            }

            final StringBuilder resFile = new StringBuilder(STRING_RESOURCES_HINT);
            resFile.append("<resources>\n");
            for (PreferenceInformation info : allPreferences) {
                resFile.append("    <string translatable=\"false\" name=\"" + generateAnnotation.stringPrefix() + info.preferenceName + "\">"
                        + info.preferenceName + "</string>\n");
            }
            resFile.append("</resources>");

            Files.write(stringsPath, resFile.toString().getBytes(StandardCharsets.UTF_8));
        } else {
            warner.emitError("To generate String resources, the resDir option has to be present and " +
                    "the directory as well as the values subdirectory must exist. Given directory: " +
                    resDirLocation, interfaze);
        }
    }
}