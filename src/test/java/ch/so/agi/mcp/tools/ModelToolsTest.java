package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.MetaAttributeSpec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ModelToolsTest {

    private final Clock fixedClock = Clock.fixed(Instant.parse("2024-05-01T00:00:00Z"), ZoneOffset.UTC);
    private final ModelTools modelTools = new ModelTools(fixedClock);

    @Test
    @DisplayName("createModelSnippet uses defaults when optional parameters are null or empty")
    void createModelSnippetDefaults() {
        Map<String, Object> result = modelTools.createModelSnippet(
                "TestModel",
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null
        );

        String expectedSnippet = "INTERLIS 2.4;\n\n"
                + "MODEL TestModel (de) AT \"https://example.org/testmodel\" VERSION \"2024-05-01\" =\n"
                + "\n"
                + "END TestModel.\n";

        assertEquals(expectedSnippet, result.get("iliSnippet"));
    }

    @Test
    @DisplayName("createModelSnippet trims provided values before building snippet")
    void createModelSnippetTrimsValues() {
        Map<String, Object> result = modelTools.createModelSnippet(
                " TrimModel ",
                " en ",
                " https://data.example/TrimModel ",
                " 2024-01-31 ",
                " 2.3 ",
                List.of("INTERLIS", "GeometryCHLV95_V1"),
                null,
                null,
                null
        );

        String expectedSnippet = "INTERLIS 2.3;\n\n"
                + "MODEL TrimModel (en) AT \"https://data.example/TrimModel\" VERSION \"2024-01-31\" =\n"
                + "  IMPORTS INTERLIS;\n"
                + "  IMPORTS GeometryCHLV95_V1;\n\n"
                + "END TrimModel.\n";

        assertEquals(expectedSnippet, result.get("iliSnippet"));
    }

    @Test
    @DisplayName("createModelSnippet validates model name")
    void createModelSnippetValidatesModelName() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                modelTools.createModelSnippet(
                        "Invalid-Model",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        );

        assertEquals(
                "Model name must match [A-Za-z][A-Za-z0-9_]* (starts with a letter, then letters/digits/underscore). Got: 'Invalid-Model'.",
                ex.getMessage()
        );
    }

    @Test
    @DisplayName("createModelSnippet validates import identifiers")
    void createModelSnippetValidatesImports() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                modelTools.createModelSnippet(
                        "InvalidImportModel",
                        "de",
                        "https://example.org/invalid",
                        "2024-05-01",
                        null,
                        List.of("ValidImport", "Invalid-Import"),
                        null,
                        null,
                        null
                )
        );

        assertEquals(
                "Import model name must match [A-Za-z][A-Za-z0-9_]* (starts with a letter, then letters/digits/underscore). Got: 'Invalid-Import'.",
                ex.getMessage()
        );
    }

    @Test
    @DisplayName("createModelSnippet builds Solothurn header with Zurich date")
    void createModelSnippetAddsSolothurnHeader() {
        Map<String, Object> result = modelTools.createModelSnippet(
                "HeaderModel",
                null,
                null,
                null,
                null,
                null,
                true,
                null,
                null
        );

        String expectedSnippet = """
                /** !!------------------------------------------------------------------------------
                 * !! Version    | wer | Änderung
                 * !!------------------------------------------------------------------------------
                 * !! 2024-05-01 | abr  | Initalversion
                 * !!==============================================================================
                 */
                INTERLIS 2.4;

                !!@ technicalContact=mailto:agi@bd.so.ch
                !!@ title="a title"
                !!@ shortDescription="a short description"
                !!@ tags="de:Gebäude,fr:Bâtiment,fubar"
                MODEL HeaderModel (de) AT "https://example.org/headermodel" VERSION "2024-05-01" =

                END HeaderModel.
                """.stripIndent();

        assertEquals(expectedSnippet, result.get("iliSnippet"));
    }

    @Test
    @DisplayName("createModelSnippet validates iliVersion")
    void createModelSnippetValidatesIliVersion() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                modelTools.createModelSnippet(
                        "InvalidIliVersion",
                        null,
                        null,
                        null,
                        "2.5",
                        null,
                        null,
                        null,
                        null
                )
        );

        assertEquals(
                "iliVersion must be either '2.3' or '2.4'. Got: '2.5'.",
                ex.getMessage()
        );
    }

    @Test
    @DisplayName("createImportLine defaults to qualified import")
    void createImportLineDefaultsToQualified() {
        String line = modelTools.createImportLine("ModelA", null);

        assertEquals("IMPORTS ModelA;", line);
    }

    @Test
    @DisplayName("createImportLine renders unqualified import when requested")
    void createImportLineUnqualified() {
        String line = modelTools.createImportLine("ModelA", false);

        assertEquals("IMPORTS UNQUALIFIED ModelA;", line);
    }

    @Test
    @DisplayName("createImportLine validates model name")
    void createImportLineValidatesModelName() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                modelTools.createImportLine("Invalid-Model", true)
        );

        assertEquals(
                "Import model name must match [A-Za-z][A-Za-z0-9_]* (starts with a letter, then letters/digits/underscore). Got: 'Invalid-Model'.",
                ex.getMessage()
        );
    }

    @Test
    @DisplayName("createModelSnippet merges Solothurn defaults with overriding meta attributes and iliDoc")
    void createModelSnippetOverridesSolothurnMetaAttributes() {
        MetaAttributeSpec title = meta("title", "override", null);
        MetaAttributeSpec custom = meta("ch.so.test", null, "TRUE");

        Map<String, Object> result = modelTools.createModelSnippet(
                "HeaderModel",
                null,
                null,
                null,
                null,
                null,
                true,
                "Modelldoku",
                List.of(title, custom)
        );

        String expectedSnippet = """
                /** !!------------------------------------------------------------------------------
                 * !! Version    | wer | Änderung
                 * !!------------------------------------------------------------------------------
                 * !! 2024-05-01 | abr  | Initalversion
                 * !!==============================================================================
                 */
                INTERLIS 2.4;

                /** Modelldoku */
                !!@ technicalContact=mailto:agi@bd.so.ch
                !!@ title="override"
                !!@ shortDescription="a short description"
                !!@ tags="de:Gebäude,fr:Bâtiment,fubar"
                !!@ ch.so.test=TRUE
                MODEL HeaderModel (de) AT "https://example.org/headermodel" VERSION "2024-05-01" =

                END HeaderModel.
                """.stripIndent();

        assertEquals(expectedSnippet, result.get("iliSnippet"));
    }

    private MetaAttributeSpec meta(String name, String value, String rawValue) {
        MetaAttributeSpec metaAttribute = new MetaAttributeSpec();
        metaAttribute.setName(name);
        metaAttribute.setValue(value);
        metaAttribute.setRawValue(rawValue);
        return metaAttribute;
    }
}
