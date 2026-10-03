package ch.so.agi.mcp.tools;

import static org.assertj.core.api.Assertions.*;

import ch.so.agi.mcp.analysis.ModelAnalysisTools;
import ch.so.agi.mcp.analysis.ModelChangeReviewService;
import ch.so.agi.mcp.constraint.*;
import ch.so.agi.mcp.knowledge.KnowledgeRuleLoader;
import ch.so.agi.mcp.knowledge.ModelingRuleTools;
import ch.so.agi.mcp.model.*;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class ConstraintPathSearchTest {
  private final CountingCompiler compiler = new CountingCompiler();
  private final ConstraintKnowledgeTools tools = new ConstraintKnowledgeTools(compiler);
  private static final String MODEL = """
      INTERLIS 2.4;
      MODEL Paths (en) AT "https://example.org" VERSION "1" =
        DOMAIN RequiredNumber = MANDATORY 0..100;
        TOPIC T =
          CLASS Base =
            amount : MANDATORY RequiredNumber;
            label : TEXT*20;
            flag : BOOLEAN;
          END Base;
          CLASS Child EXTENDS Base = extra : 0..10; END Child;
          STRUCTURE Entry =
            ref : REFERENCE TO Base;
            quantity : MANDATORY 0..100;
          END Entry;
          STRUCTURE Detail = entry : MANDATORY Entry; END Detail;
          CLASS Root =
            own : MANDATORY 0..10;
            detail : Detail;
            entries : BAG {0..3} OF Entry;
          END Root;
          CLASS Isolated = value : 0..10; END Isolated;
          ASSOCIATION ToChildren = parents -- {0..*} Root; children -- {0..3} Child; END ToChildren;
          ASSOCIATION ToPreferred = callers -- {0..*} Root; preferred -- {0..1} Base; END ToPreferred;
        END T;
      END Paths.
      """;

  @ParameterizedTest @ValueSource(strings = {"2.3", "2.4"})
  void findsAndResolvesTheSameFactsInStableBreadthFirstOrder(String version) {
    String model = MODEL.replace("INTERLIS 2.4", "INTERLIS " + version);
    var result = tools.findConstraintPaths(model, "Paths.T.Root", "Paths.T.Base.amount", null, null);
    assertThat(result).containsEntry("status", "AVAILABLE");
    assertThat(compiler.calls).isEqualTo(1);
    assertThat(((ModelHashes) result.get("modelHashes")).model()).isEqualTo(ModelHashes.sha256(model));
    assertThat(names(result)).containsExactly("children->amount", "preferred->amount", "entries->ref->amount");
    assertThat(map(result.get("search"))).containsEntry("maxDepth", 3).containsEntry("limit", 10)
        .containsEntry("completeWithinBounds", true);
    for (var path : paths(result)) {
      assertThat(path).isEqualTo(tools.resolveConstraintPath(model, "Paths.T.Root", (String) path.get("path")));
    }
    var first = paths(result).getFirst();
    assertThat(first).containsEntry("collection", true).containsEntry("mayBeUndefined", true).containsEntry("proofStatus", "NOT_RUN");
    assertThat(steps(first).getFirst()).containsEntry("minimum", 0L).containsEntry("maximum", 3L).containsEntry("optional", true);
    assertThat(steps(first).getLast()).containsEntry("elementFqn", "Paths.T.Base.amount").containsEntry("optional", false);
    assertThat(operations(first)).containsExactly("SUM");
    assertThat(operations(paths(result).get(1))).containsExactly("SINGLE_VALUE");
  }

  @Test void followsOptionalStructuresReferencesAndDirectStructureTargets() {
    var result = find("Paths.T.Base.amount", 4, 50);
    assertThat(names(result)).contains("detail->entry->ref->amount");
    var nested = path(result, "detail->entry->ref->amount");
    assertThat(steps(nested)).extracting(s -> s.get("kind"))
        .containsExactly("STRUCTURE_ATTRIBUTE", "STRUCTURE_ATTRIBUTE", "REFERENCE_ATTRIBUTE", "ATTRIBUTE");
    assertThat(steps(nested)).extracting(s -> s.get("optional")).containsExactly(true, false, true, false);
    assertThat(operations(nested)).containsExactly("SINGLE_VALUE");
    var structure = path(find("Paths.T.Entry", 3, 50), "entries");
    assertThat(operations(structure)).doesNotContain("OBJECT_COUNT", "SUM", "SINGLE_VALUE");
    assertThat(map(structure.get("result"))).containsEntry("kind", "COMPOSITION");
    var reference = path(find("Paths.T.Base", 3, 50), "entries->ref");
    assertThat(operations(reference)).containsExactly("OBJECT_COUNT");
  }

  @ParameterizedTest @ValueSource(strings = {"2.3", "2.4"})
  void aliasOptionalityFollowsTheCompiledLanguageVersion(String version) {
    String model = MODEL.replace("INTERLIS 2.4", "INTERLIS " + version).replace("amount : MANDATORY RequiredNumber", "amount : RequiredNumber");
    var td = compiler.compile(model, null, "alias_facts_").transferDescription();
    var attribute = (ch.interlis.ili2c.metamodel.AttributeDef) td.getElement("Paths.T.Base.amount");
    boolean mandatory = attribute.getDomainOrDerivedDomain().isMandatoryConsideringAliases();
    var result = tools.resolveConstraintPath(model, "Paths.T.Base", "amount");
    assertThat(steps(result).getFirst()).containsEntry("optional", !mandatory).containsEntry("minimum", mandatory ? 1L : 0L);
    assertThat(result).containsEntry("mayBeUndefined", !mandatory);
  }

  @Test void searchesInverseAndInheritedRolesAndAttributes() {
    var result = tools.findConstraintPaths(MODEL, "Paths.T.Child", "Paths.T.Root.own", 2, 50);
    assertThat(names(result)).contains("parents->own", "callers->own");
    assertThat(names(tools.findConstraintPaths(MODEL, "Paths.T.ToChildren", "Paths.T.Root.own", 2, 50)))
        .contains("parents->own");
    assertThat(names(find("Paths.T.Child.amount", 3, 50))).containsExactly("children->amount");
    assertThat(names(find("Paths.T.Child.extra", 3, 50))).containsExactly("children->extra");
    assertThat(names(tools.findConstraintPaths(MODEL, "Paths.T.Child", "Paths.T.Base.amount", 1, 50))).containsExactly("amount");
    var invalid = tools.resolveConstraintPath(MODEL, "Paths.T.Child", "missing");
    assertThat(list(invalid.get("candidates"))).extracting(m -> m.get("name")).contains("amount", "parents", "callers");
  }

  @Test void typeHintsDoNotTurnTextOrBooleansIntoSumsOrStructuresIntoObjects() {
    assertThat(operations(path(find("Paths.T.Base.label", 2, 50), "children->label"))).isEmpty();
    assertThat(operations(path(find("Paths.T.Base.label", 2, 50), "preferred->label"))).containsExactly("SINGLE_VALUE");
    assertThat(operations(path(find("Paths.T.Base.flag", 2, 50), "children->flag"))).isEmpty();
    assertThat(operations(path(find("Paths.T.Base.flag", 2, 50), "preferred->flag"))).containsExactly("SINGLE_VALUE");
    var own = path(find("Paths.T.Root.own", 1, 50), "own");
    assertThat(own).containsEntry("mayBeUndefined", false).containsEntry("collection", false);
    assertThat(map(list(own.get("usageHints")).getFirst().get("expression")))
        .containsEntry("kind", "ATTRIBUTE").containsEntry("name", "own");
  }

  @Test void inheritedAttributeOverrideUsesTheEffectiveDefinition() {
    String model = MODEL.replace("CLASS Child EXTENDS Base = extra : 0..10;", "CLASS Child EXTENDS Base = amount (EXTENDED) : MANDATORY 10..20; extra : 0..10;");
    var result = tools.findConstraintPaths(model, "Paths.T.Root", "Paths.T.Child.amount", 2, 50);
    var candidate = path(result, "children->amount");
    assertThat(steps(candidate).getLast()).containsEntry("elementFqn", "Paths.T.Child.amount");
    assertThat(map(candidate.get("result"))).containsEntry("typeText", "10..20");
  }

  @Test void invalidInputsAndUnknownTargetsAreNotReportedAsSuccessfulEmptySearches() {
    assertThat(find("Paths.T.Missing", 3, 10)).containsEntry("status", "UNAVAILABLE");
    assertThat(find("Paths.T", 3, 10)).containsEntry("reasonCode", "TARGET_NOT_FOUND_OR_UNSUPPORTED");
    assertThat(tools.findConstraintPaths(MODEL, "Paths.T", "Paths.T.Root", null, null))
        .containsEntry("reasonCode", "CONTEXT_NOT_VIEWABLE");
    assertThat(tools.findConstraintPaths("INVALID", "Paths.T.Root", "Paths.T.Base", null, null))
        .containsEntry("reasonCode", "MODEL_COMPILATION_FAILED");
    assertThatThrownBy(() -> find("Paths.T.Base", 9, 10)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> find("Paths.T.Base", 0, 10)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> find("Paths.T.Base", 3, 51)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> find("Paths.T.Base", 3, 0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void preservesAlternativeAndCyclicPathsAndReportsDepthAndResultBounds() {
    assertThat(names(find("Paths.T.Base.amount", 1, 50))).isEmpty();
    assertThat(reasons(find("Paths.T.Base.amount", 1, 50))).contains("DEPTH_LIMIT_REACHED");
    var result = find("Paths.T.Base.amount", 4, 50);
    assertThat(names(result)).contains("preferred->callers->preferred->amount", "children->parents->preferred->amount");
    var limited = find("Paths.T.Base.amount", 4, 1);
    assertThat(names(limited)).containsExactly("children->amount");
    assertThat(reasons(limited)).contains("RESULT_LIMIT_REACHED");
    assertThat(map(limited.get("search"))).containsEntry("completeWithinBounds", false);
    var exact = find("Paths.T.Root.own", 1, 1);
    assertThat(reasons(exact)).doesNotContain("RESULT_LIMIT_REACHED");
    assertThat(names(find("Paths.T.Isolated.value", 3, 50))).isEmpty();
  }

  @Test void cyclicSearchStopsAtTheHardWorkBudgetDeterministically() {
    StringBuilder associations = new StringBuilder();
    for (int i = 0; i < 5; i++) associations.append("ASSOCIATION Cycle" + i + " = a" + i + " -- {0..*} Root; b" + i + " -- {0..*} Root; END Cycle" + i + ";\n");
    String model = MODEL.replace("END T;", associations + "END T;");
    var first = tools.findConstraintPaths(model, "Paths.T.Root", "Paths.T.Isolated.value", 8, 50);
    assertThat(names(first)).isEmpty();
    assertThat(map(first.get("search"))).containsEntry("examinedPrefixes", 2000).containsEntry("completeWithinBounds", false);
    assertThat(reasons(first)).contains("SEARCH_BUDGET_EXCEEDED");
    assertThat(tools.findConstraintPaths(model, "Paths.T.Root", "Paths.T.Isolated.value", 8, 50)).isEqualTo(first);
  }

  @Test void doesNotInventSubclassNavigation() {
    String model = MODEL.replace("children -- {0..3} Child", "children -- {0..3} Base");
    var result = tools.findConstraintPaths(model, "Paths.T.Root", "Paths.T.Child.extra", 3, 50);
    assertThat(names(result)).isEmpty();
    assertThat(result.get("limitations").toString()).contains("no downcasts", "No paths is not a proof");
  }

  @Test void legacySpecialPathsRetainResolutionWithoutInventedUsageHints() {
    var result = tools.resolveConstraintPath(MODEL, "Paths.T.Root", "THIS");
    assertThat(result).containsEntry("valid", true).containsEntry("mayBeUndefined", null);
    assertThat(result.get("limitations").toString()).contains("PATH_ELEMENT_UNSUPPORTED");
    assertThat(operations(result)).isEmpty();
  }

  @Test void multipleRoleDestinationsRemainAnExplicitSearchGap() {
    String model = MODEL.replace("children -- {0..3} Child", "children -- {0..3} Child OR Isolated");
    var result = tools.findConstraintPaths(model, "Paths.T.Root", "Paths.T.Child", 2, 50);
    assertThat(result).as(result.toString()).containsEntry("status", "AVAILABLE");
    assertThat(names(result)).isEmpty();
    assertThat(reasons(result)).contains("MULTIPLE_ROLE_TARGETS_UNSUPPORTED");
    assertThat(map(result.get("search"))).containsEntry("completeWithinBounds", false);
  }

  @Test void projectionAttributesAreSearchableButBaseAliasNavigationIsExplicitlyLimited() {
    String model = MODEL.replace("END Paths.", """
        VIEW TOPIC V =
          DEPENDS ON T;
          VIEW Selected PROJECTION OF B ~ Paths.T.Base;
            = ALL OF B;
          END Selected;
        END V;
        END Paths.
        """);
    var result = tools.findConstraintPaths(model, "Paths.V.Selected", "Paths.V.Selected.amount", 2, 50);
    assertThat(names(result)).containsExactly("amount");
    assertThat(reasons(result)).contains("VIEW_BASE_ALIASES_NOT_SEARCHED");
    assertThat(map(result.get("search"))).containsEntry("completeWithinBounds", false);
  }

  @ParameterizedTest @ValueSource(strings = {"2.3", "2.4"})
  void discoveredExpressionsPassExistingAuthoringAndRealValidator(String version) throws Exception {
    String model = MODEL.replace("INTERLIS 2.4", "INTERLIS " + version);
    var analysis = new ModelAnalysisTools(compiler);
    var engine = new ConstraintAuthoringEngine(new ConstraintAuthoringWorkflow(compiler),
        new IliSpecRenderer(new AttributeTools(), new DomainTools()),
        new ConstraintCaseGenerationTools(new ConstraintContextService(compiler), new ConstraintTestTools(compiler)),
        new ModelChangeReviewService(analysis, new ModelingRuleTools(new KnowledgeRuleLoader(), analysis, compiler)));
    var mapper = new ObjectMapper();
    for (var test : List.of(
        List.of("Paths.T.Root.own", "own", "SINGLE_VALUE"),
        List.of("Paths.T.Base.amount", "children->amount", "SUM"),
        List.of("Paths.T.Child", "children", "OBJECT_COUNT"))) {
      var candidate = path(tools.findConstraintPaths(model, "Paths.T.Root", test.get(0), 2, 50), test.get(1));
      var expression = list(candidate.get("usageHints")).stream().filter(h -> test.get(2).equals(h.get("operation"))).findFirst().orElseThrow().get("expression");
      var spec = mapper.convertValue(Map.of("kind", "MANDATORY", "name", "DiscoveredRule", "condition",
          Map.of("kind", "COMPARE", "operator", ">=", "children", List.of(expression, Map.of("kind", "NUMERIC", "value", 2)))), IliConstraintSpec.Mandatory.class);
      var result = engine.author(model, "Paths.T.Root", spec, null, null);
      assertThat(result.status).as(test + ": " + mapper.writeValueAsString(result)).isEqualTo(IliAuthoringResult.Status.GENERATED);
      assertThat(result.proofVerified).isTrue();
    }
  }

  private Map<String, Object> find(String target, int depth, int limit) {
    return tools.findConstraintPaths(MODEL, "Paths.T.Root", target, depth, limit);
  }
  private static List<String> names(Map<String, Object> result) { return paths(result).stream().map(p -> (String) p.get("path")).toList(); }
  private static List<Map<String, Object>> paths(Map<String, Object> result) { return list(result.get("paths")); }
  private static Map<String, Object> path(Map<String, Object> result, String path) {
    return paths(result).stream().filter(p -> path.equals(p.get("path"))).findFirst().orElseThrow(() -> new AssertionError(result.toString()));
  }
  private static List<Map<String, Object>> steps(Map<String, Object> path) { return list(path.get("steps")); }
  private static List<String> operations(Map<String, Object> path) { return list(path.get("usageHints")).stream().map(h -> (String) h.get("operation")).toList(); }
  @SuppressWarnings("unchecked") private static List<String> reasons(Map<String, Object> result) { return (List<String>) map(result.get("search")).get("reasonCodes"); }
  @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
  @SuppressWarnings("unchecked") private static List<Map<String, Object>> list(Object value) { return (List<Map<String, Object>>) value; }
  private static class CountingCompiler extends IliCompilerService {
    int calls;
    @Override public CompilationResult compile(String text, String repositories, String prefix) {
      calls++; return super.compile(text, repositories, prefix);
    }
  }
}
