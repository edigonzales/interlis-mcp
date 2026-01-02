package ch.so.agi.mcp.tools;

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
                null
        );

        String expectedSnippet = "INTERLIS 2.4;\n\n"
                + "MODEL TestModel (de) AT \"https://example.org/testmodel\" VERSION \"2024-05-01\" =\n"
                + "  IMPORTS UNQUALIFIED INTERLIS;\n\n"
                + "END TestModel.\n";

        assertEquals(expectedSnippet, result.get("iliSnippet"));

        @SuppressWarnings("unchecked")
        Map<String, Integer> cursorHint = (Map<String, Integer>) result.get("cursorHint");
        assertEquals(Map.of("line", 4, "col", 0), cursorHint);
    }

    @Test
    @DisplayName("createModelSnippet trims provided values before building snippet")
    void createModelSnippetTrimsValues() {
        Map<String, Object> result = modelTools.createModelSnippet(
                "TrimModel",
                " en ",
                " https://data.example/TrimModel ",
                " 2024-01-31 ",
                " 2.3 ",
                List.of("INTERLIS", "GeometryCHLV95_V1"),
                null
        );

        String expectedSnippet = "INTERLIS 2.3;\n\n"
                + "MODEL TrimModel (en) AT \"https://data.example/TrimModel\" VERSION \"2024-01-31\" =\n"
                + "  IMPORTS UNQUALIFIED INTERLIS, GeometryCHLV95_V1;\n\n"
                + "END TrimModel.\n";

        assertEquals(expectedSnippet, result.get("iliSnippet"));

        @SuppressWarnings("unchecked")
        Map<String, Integer> cursorHint = (Map<String, Integer>) result.get("cursorHint");
        assertEquals(Map.of("line", 4, "col", 0), cursorHint);
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
                        null
                )
        );

        assertEquals(
                "Import model name must match [A-Za-z][A-Za-z0-9_]* (starts with a letter, then letters/digits/underscore). Got: 'Invalid-Import'.",
                ex.getMessage()
        );
    }

    @Test
    @DisplayName("createModelSnippet builds Solothurn header with Zurich date and updates cursor hint")
    void createModelSnippetAddsSolothurnHeader() {
        Map<String, Object> result = modelTools.createModelSnippet(
                "HeaderModel",
                null,
                null,
                null,
                null,
                null,
                true
        );

        String expectedSnippet = """
                /** !!------------------------------------------------------------------------------
                 * !! Version    | wer | Änderung
                 * !!------------------------------------------------------------------------------
                 * !! 2024-05-01 | abr  | Initalversion
                 * !!==============================================================================
                 */
                !!@ technicalContact=mailto:agi@bd.so.ch
                !!@ title="a title"
                !!@ shortDescription="a short description"
                !!@ tags="foo,bar,fubar"
                INTERLIS 2.4;

                MODEL HeaderModel (de) AT "https://example.org/headermodel" VERSION "2024-05-01" =
                  IMPORTS UNQUALIFIED INTERLIS;

                END HeaderModel.
                """.stripIndent();

        assertEquals(expectedSnippet, result.get("iliSnippet"));

        @SuppressWarnings("unchecked")
        Map<String, Integer> cursorHint = (Map<String, Integer>) result.get("cursorHint");
        assertEquals(Map.of("line", 14, "col", 0), cursorHint);
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
                        null
                )
        );

        assertEquals(
                "iliVersion must be either '2.3' or '2.4'. Got: '2.5'.",
                ex.getMessage()
        );
    }
}
