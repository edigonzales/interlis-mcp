package ch.so.agi.mcp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@SuppressWarnings("unchecked")
class ConstraintSchemaDiagnosticsTest {
  @Autowired @Qualifier("toolSpecs") List<SyncToolSpecification> specifications;
  private final ObjectMapper mapper = new ObjectMapper();
  private final DefaultJsonSchemaValidator validator = new DefaultJsonSchemaValidator();

  private Map<String,Object> schema(String tool) {
    return specifications.stream().filter(s -> s.tool().name().equals(tool)).findFirst().orElseThrow().tool().inputSchema();
  }
  private Map<String,Object> cloneMap(Map<String,Object> map) {
    return mapper.readValue(mapper.writeValueAsString(map), Map.class);
  }
  private Map<String,Object> expressionSchema(boolean previous) {
    Map<String,Object> result=cloneMap(schema("authorIliMandatoryConstraint"));
    Map<String,Object> defs=(Map<String,Object>)result.get("$defs");
    Map<String,Object> expression=(Map<String,Object>)defs.get("ExpressionSpec");
    if (previous) {
      // Reconstruct the previous recursive oneOf, using the unchanged branch shapes.
      List<Object> alternatives=((List<Map<String,Object>>)expression.get("allOf")).stream()
          .map(b -> b.get("then")).toList();
      expression.clear(); expression.put("oneOf",alternatives);
    }
    return new LinkedHashMap<>(Map.of("$defs",defs,"$ref","#/$defs/ExpressionSpec"));
  }
  private List<Map<String,Object>> examples() {
    return mapper.readValue("""
        [{"kind":"ATTRIBUTE","name":"value"},
         {"kind":"PATH","name":"parent->value"},
         {"kind":"NUMERIC","value":7},
         {"kind":"BOOLEAN","value":true},
         {"kind":"ENUM","value":"#Group.Item"},
         {"kind":"TEXT","value":"text"},
         {"kind":"MTEXT","value":"text"},
         {"kind":"FUNCTION","name":"NUMERIC_ABS","functionOrigin":"STANDARD","children":[{"kind":"NUMERIC","value":7}]},
         {"kind":"DEFINED","children":[{"kind":"ATTRIBUTE","name":"value"}]},
         {"kind":"NOT","children":[{"kind":"BOOLEAN","value":true}]},
         {"kind":"AND","children":[{"kind":"BOOLEAN","value":true},{"kind":"BOOLEAN","value":false}]},
         {"kind":"OR","children":[{"kind":"BOOLEAN","value":true},{"kind":"BOOLEAN","value":false}]},
         {"kind":"IMPLIES","children":[{"kind":"BOOLEAN","value":true},{"kind":"BOOLEAN","value":false}]},
         {"kind":"COMPARE","operator":"==","children":[{"kind":"ATTRIBUTE","name":"value"},{"kind":"NUMERIC","value":7}]},
         {"kind":"OBJECT_COUNT","objects":{"kind":"ALL"}}]
        """, List.class);
  }

  @Test void allFifteenShapesPreserveAcceptanceIncludingInvalidAndNestedNodes() {
    var previous=expressionSchema(true);var current=expressionSchema(false);
    var examples=examples();assertThat(examples).hasSize(15);
    for(var example:examples) {
      assertThat(validator.validate(previous,example).valid()).as(example.toString()).isTrue();
      assertThat(validator.validate(current,example).valid()).as(example.toString()).isTrue();
      List<Object> invalid=new ArrayList<>();
      var extra=cloneMap(example);extra.put("surprise",true);invalid.add(extra);
      var unknown=cloneMap(example);unknown.put("kind","NUMBER");invalid.add(unknown);
      for(String field:example.keySet()) {
        if(field.equals("children") && example.get("kind").equals("FUNCTION")) continue;
        var missing=cloneMap(example);missing.remove(field);invalid.add(missing);
      }
      for(Object node:invalid) {
        assertThat(validator.validate(previous,node).valid()).as(node.toString()).isFalse();
        assertThat(validator.validate(current,node).valid()).as(node.toString()).isFalse();
      }
      // Both unions remain recursive; invalid children cannot be hidden in a valid parent.
      for(Object leaf:List.of(example,extra)) {
        Object nested=Map.of("kind","NOT","children",List.of(leaf));
        assertThat(validator.validate(current,nested).valid())
            .isEqualTo(validator.validate(previous,nested).valid());
      }
    }
    for(Object node:List.of("not an object",List.of(),Map.of(),Map.of("kind",7),
        Map.of("kind","NOT","children",List.of()),
        Map.of("kind","ATTRIBUTE","name","value","children",List.of(Map.of("kind","BOOLEAN","value",true))),
        Map.of("kind","COMPARE","operator","=","children",List.of(Map.of("kind","NUMERIC","value",7),Map.of("kind","NUMERIC","value",7))),
        Map.of("kind","BOOLEAN","value","true"),Map.of("kind","ENUM","value",7))) {
      assertThat(validator.validate(previous,node).valid()).isFalse();
      assertThat(validator.validate(current,node).valid()).isFalse();
    }
  }

  @Test void nestedErrorsStayFocusedAndSmallAcrossSingleAndBatchSchemas() throws Exception {
    Map<String,Object> measurements=new LinkedHashMap<>();
    Object invalidLeaf=Map.of("kind","NUMBER","value",7);
    Object nested=Map.of("kind","COMPARE","operator",">=","children",List.of(Map.of("kind","ATTRIBUTE","name","value"),invalidLeaf));
    for(int depth:List.of(0,4,16,32)) {
      Object condition=nested;
      for(int i=0;i<depth;i++)condition=Map.of("kind","NOT","children",List.of(condition));
      var spec=Map.of("kind","MANDATORY","name","Rule","condition",condition);
      var single=Map.of("modelText","INTERLIS 2.4;","contextFqn","Example.Data.Item","spec",spec);
      var batch=Map.of("modelText","INTERLIS 2.4;","request",Map.of("changes",List.of(Map.of("operation","ADD_CONSTRAINT","addConstraint",Map.of("containerFqn","Example.Data.Item","constraint",spec)))));
      for(var entry:Map.of("authorIliMandatoryConstraint",single,"applyIliModelChanges",batch).entrySet()) {
        var result=validator.validate(schema(entry.getKey()),entry.getValue());
        assertThat(result.valid()).isFalse();
        int bytes=result.errorMessage().getBytes(StandardCharsets.UTF_8).length;
        assertThat(bytes).as(entry.getKey()+" depth="+depth).isLessThan(8192);
        assertThat(result.errorMessage()).contains("/kind", "NUMERIC").doesNotContain("must be the constant value 'ATTRIBUTE'");
        measurements.put(entry.getKey()+" depth="+depth,bytes);
      }
    }
    var path=Path.of("build/diagnostics/constraint-schema-errors.json");Files.createDirectories(path.getParent());
    Files.writeString(path,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(measurements));
  }
}
