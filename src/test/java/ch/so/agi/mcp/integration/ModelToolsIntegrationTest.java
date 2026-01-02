package ch.so.agi.mcp.integration;

import ch.so.agi.mcp.tools.ModelTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {ModelTools.class, ModelToolsIntegrationTest.FixedClockTestConfig.class})
class ModelToolsIntegrationTest {

    @TestConfiguration
    static class FixedClockTestConfig {
        @Bean(name = "testClock")
        @Primary
        Clock clock() {
            return Clock.fixed(Instant.parse("2024-04-01T00:00:00Z"), ZoneId.of("UTC"));
        }
    }

    @Autowired
    private ModelTools modelTools;

    @Test
    void createModelSnippet_usesDefaultsFromSpringContext() {
        Map<String, Object> result = modelTools.createModelSnippet("TestModel", null, null, null, null, null, null);

        String expectedSnippet = "INTERLIS 2.4;\n\n" +
                "MODEL TestModel (de) AT \"https://example.org/testmodel\" VERSION \"2024-04-01\" =\n" +
                "  IMPORTS UNQUALIFIED INTERLIS;\n\n" +
                "END TestModel.\n";
        assertEquals(expectedSnippet, result.get("iliSnippet"));
        assertEquals(Map.of("line", 4, "col", 0), result.get("cursorHint"));
    }

    @Test
    void createModelSnippet_trimsParametersAndJoinsImports() {
        Map<String, Object> result = modelTools.createModelSnippet(
                "DemoModel",
                " en ",
                " https://example.com/demo ",
                "2023-12-31",
                "2.3",
                List.of("GeometryCHLV95_V1", "Units"),
                null
        );

        String expectedSnippet = "INTERLIS 2.3;\n\n" +
                "MODEL DemoModel (en) AT \"https://example.com/demo\" VERSION \"2023-12-31\" =\n" +
                "  IMPORTS UNQUALIFIED GeometryCHLV95_V1, Units;\n\n" +
                "END DemoModel.\n";
        assertEquals(expectedSnippet, result.get("iliSnippet"));
    }
}
