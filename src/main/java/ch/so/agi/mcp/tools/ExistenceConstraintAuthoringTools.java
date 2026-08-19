package ch.so.agi.mcp.tools;

import ch.interlis.ili2c.metamodel.ObjectPath;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.ili2c.metamodel.Viewable;
import ch.interlis.ili2c.parser.Ili23Parser;
import ch.so.agi.mcp.constraint.CompiledConstraintContext;
import ch.so.agi.mcp.constraint.ConstraintContextService;
import ch.so.agi.mcp.constraint.ConstraintExpression;
import ch.so.agi.mcp.constraint.ConstraintSourceEditService;
import ch.so.agi.mcp.constraint.SemanticConstraint;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** High-level typed authoring for scalar INTERLIS EXISTENCE constraints. */
@Component
public class ExistenceConstraintAuthoringTools {

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

  public static class RequiredInTarget {
    public String viewableFqn;
    public String attributePath;
  }

  private final IliCompilerService compilerService;
  private final ConstraintCaseGenerationTools caseGenerationTools;
  private final ConstraintContextService contextService;
  private final ConstraintSourceEditService sourceEditService;

  public ExistenceConstraintAuthoringTools(
      IliCompilerService compilerService,
      ConstraintCaseGenerationTools caseGenerationTools,
      ConstraintContextService contextService,
      ConstraintSourceEditService sourceEditService) {
    this.compilerService = compilerService;
    this.caseGenerationTools = caseGenerationTools;
    this.contextService = contextService;
    this.sourceEditService = sourceEditService;
  }

  @McpTool(
      name = "authorIliExistenceConstraint",
      description = "Erzeugt einen skalaren INTERLIS EXISTENCE CONSTRAINT aus explizitem restrictedPath und einer Liste von REQUIRED-IN-Zielen mit jeweils viewableFqn plus attributePath. Loest Source- und Target-Pfade gegen den kompilierten Before-AST auf, fuegt den Constraint source-preserving ein, kompiliert Before/After je genau einmal, prueft den AST->constraint-level-IR Roundtrip und beweist Witness/Counterexample-Faelle mit echtem ilivalidator. Unterstuetzt in B7 skalare NUMERIC/BOOLEAN/ENUM/TEXT/MTEXT-Pfade; Structure-/Geometry-Sondersemantik folgt in B8."
  )
  public Map<String, Object> authorIliExistenceConstraint(
      @McpToolParam(description = "Vollstaendiger INTERLIS-2 Modelltext ohne den zu erzeugenden EXISTENCE Constraint", required = true) String modelText,
      @McpToolParam(description = "Vollqualifizierter Constraint-Kontext Model.Topic.Class/Association/View", required = true) String context,
      @McpToolParam(description = "Technischer Constraint-Name", required = true) String constraintName,
      @McpToolParam(description = "Attributpfad des eingeschraenkten Werts im Constraint-Kontext", required = true) String restrictedPath,
      @McpToolParam(description = "Mindestens ein REQUIRED-IN-Ziel. Jedes Element gibt viewableFqn und attributePath explizit an; Zielattribute werden nie geraten.", required = true) List<RequiredInTarget> requiredIn,
      @McpToolParam(description = "Optionale MODELREPOS-/ilidirs-Definition", required = false) @Nullable String modelRepositories) {
    String normalizedContext;
    String normalizedName;
    String normalizedRestricted;
    List<NormalizedTarget> targets;
    try {
      normalizedContext = requireFqn(context, "context");
      normalizedName = requireIdentifier(constraintName, "constraintName");
      normalizedRestricted = requirePath(restrictedPath, "restrictedPath");
      targets = normalizeTargets(requiredIn);
    } catch (IllegalArgumentException ex) {
      return unavailable("INVALID_AUTHORING_SPEC", ex.getMessage(), null, null, null);
    }

    IliCompilerService.CompilationResult beforeCompilation = compilerService.compile(
        modelText,
        modelRepositories,
        "ili2c_existence_authoring_before_");
    if (!beforeCompilation.valid() || beforeCompilation.transferDescription() == null) {
      Map<String, Object> result = unavailable(
          "BEFORE_MODEL_NOT_COMPILABLE",
          "The supplied model must compile before a source-preserving EXISTENCE constraint can be inserted.",
          null,
          null,
          null);
      result.put("compilerMessages", beforeCompilation.messages());
      return result;
    }

    try {
      validatePaths(
          beforeCompilation.transferDescription(),
          normalizedContext,
          normalizedRestricted,
          targets);
    } catch (IllegalArgumentException ex) {
      return unavailable("EXISTENCE_PATH_RESOLUTION_FAILED", ex.getMessage(), null, null, null);
    }

    String constraintBlock = renderConstraintBlock(
        normalizedContext,
        normalizedName,
        normalizedRestricted,
        targets);
    ConstraintSourceEditService.PreparedInsertion insertion;
    try {
      insertion = sourceEditService.insertConstraintBlock(
          modelText,
          beforeCompilation,
          normalizedContext,
          constraintBlock);
    } catch (IllegalArgumentException ex) {
      return unavailable(
          "CONSTRAINT_INSERTION_FAILED",
          ex.getMessage(),
          constraintBlock,
          null,
          null);
    }

    String expectedConstraintFqn = normalizedContext + "." + normalizedName;
    ConstraintContextService.Resolution afterResolution = contextService.compileAndResolve(
        insertion.updatedModelText(),
        expectedConstraintFqn,
        modelRepositories,
        "ili2c_existence_authoring_after_");
    if (!afterResolution.available()) {
      Map<String, Object> result = unavailable(
          afterResolution.compilation().valid()
              ? nonBlank(afterResolution.reasonCode(), "GENERATED_CONSTRAINT_NOT_RESOLVED")
              : "GENERATED_CONSTRAINT_NOT_COMPILABLE",
          afterResolution.compilation().valid()
              ? nonBlank(afterResolution.reason(), "The generated EXISTENCE constraint could not be resolved.")
              : "The structured EXISTENCE proposal could not be compiled in the supplied model.",
          constraintBlock,
          insertion.updatedModelText(),
          insertion.sourceEdit());
      result.put("compilerMessages", afterResolution.compilation().messages());
      return result;
    }

    CompiledConstraintContext compiled = afterResolution.context();
    if (!(compiled.semantics() instanceof SemanticConstraint.Existence existence)) {
      return unavailable(
          "CONSTRAINT_KIND_ROUND_TRIP_MISMATCH",
          "Compiled constraint is not an EXISTENCE constraint: " + compiled.semantics().kind(),
          constraintBlock,
          insertion.updatedModelText(),
          insertion.sourceEdit());
    }

    String mismatch = roundTripMismatch(
        existence,
        normalizedContext,
        normalizedRestricted,
        targets);
    if (mismatch != null) {
      return unavailable(
          "EXISTENCE_SEMANTIC_ROUND_TRIP_MISMATCH",
          mismatch,
          constraintBlock,
          insertion.updatedModelText(),
          insertion.sourceEdit());
    }

    String unsupported = scalarSupportProblem(existence);
    if (unsupported != null) {
      return unavailable(
          "UNSUPPORTED_EXISTENCE_TYPE",
          unsupported,
          constraintBlock,
          insertion.updatedModelText(),
          insertion.sourceEdit());
    }

    Map<String, Object> proof = caseGenerationTools.generateCompiledConstraintCases(compiled);
    boolean verified = Boolean.TRUE.equals(proof.get("generationVerified"));

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("generated", true);
    result.put("proofVerified", verified);
    result.put("constraintName", normalizedName);
    result.put("context", normalizedContext);
    result.put("restrictedPath", normalizedRestricted);
    result.put("requiredIn", targets.stream().map(NormalizedTarget::asMap).toList());
    result.put("constraintBlock", constraintBlock);
    result.put("updatedModelText", insertion.updatedModelText());
    result.put("sourceEdit", insertion.sourceEdit());
    result.put("semanticConstraint", semanticSummary(existence));
    result.put("proof", proof);
    copyIfPresent(proof, result, "coverageGoalCount");
    copyIfPresent(proof, result, "coverageSolvedCount");
    copyIfPresent(proof, result, "coverageComplete");
    copyIfPresent(proof, result, "coverageUnsolved");
    if (!verified) {
      result.put("reasonCode", proof.getOrDefault("reasonCode", "EXISTENCE_PROOF_NOT_VERIFIED"));
      result.put("reason", proof.getOrDefault(
          "reason",
          "The EXISTENCE constraint compiled and round-tripped to typed IR, but its generated proof was not fully verified."));
    }
    result.put("limitations", List.of(
        "B7 authoring/proof supports scalar NUMERIC, BOOLEAN, ENUM, TEXT and MTEXT EXISTENCE paths.",
        "Structure equality, geometry and special EXISTENCE comparison semantics are deferred to B8.",
        "A successful proofVerified=true means every generated proof fixture was confirmed by the real ilivalidator."));
    return result;
  }

  private void validatePaths(
      TransferDescription td,
      String contextFqn,
      String restrictedPath,
      List<NormalizedTarget> targets) {
    Viewable<?> source = requireViewable(td, contextFqn, "context");
    requireAttributePath(td, source, restrictedPath, "restrictedPath");
    for (NormalizedTarget target : targets) {
      Viewable<?> viewable = requireViewable(td, target.viewableFqn(), "REQUIRED IN viewable");
      requireAttributePath(td, viewable, target.attributePath(),
          "REQUIRED IN " + target.viewableFqn() + " attributePath");
    }
  }

  private Viewable<?> requireViewable(
      TransferDescription td,
      String fqn,
      String label) {
    Object element = td.getElement(fqn);
    if (!(element instanceof Viewable<?> viewable)) {
      throw new IllegalArgumentException(label + " is not a compiled CLASS/STRUCTURE/ASSOCIATION/VIEW: " + fqn);
    }
    return viewable;
  }

  private void requireAttributePath(
      TransferDescription td,
      Viewable<?> root,
      String path,
      String label) {
    try {
      ObjectPath parsed = Ili23Parser.parseObjectOrAttributePath(td, root, path);
      if (parsed == null || !parsed.isAttributePath()) {
        throw new IllegalArgumentException(label + " must resolve to an attribute path: " + path);
      }
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalArgumentException(label + " cannot be resolved: " + path + " (" + ex.getMessage() + ")", ex);
    }
  }

  private String renderConstraintBlock(
      String context,
      String constraintName,
      String restrictedPath,
      List<NormalizedTarget> targets) {
    StringBuilder result = new StringBuilder();
    result.append("CONSTRAINTS OF ").append(context).append(" =\n")
        .append("  !!@ name = \"").append(constraintName).append("\"\n")
        .append("  EXISTENCE CONSTRAINT\n")
        .append("    ").append(restrictedPath).append(" REQUIRED IN\n");
    for (int i = 0; i < targets.size(); i++) {
      NormalizedTarget target = targets.get(i);
      result.append(i == 0 ? "      " : "      OR ")
          .append(target.viewableFqn())
          .append(" : ")
          .append(target.attributePath());
      if (i + 1 == targets.size()) {
        result.append(';');
      }
      result.append('\n');
    }
    result.append("END;");
    return result.toString();
  }

  private @Nullable String roundTripMismatch(
      SemanticConstraint.Existence existence,
      String context,
      String restrictedPath,
      List<NormalizedTarget> targets) {
    if (!context.equals(existence.contextFqn())) {
      return "Compiled EXISTENCE context differs from the request: " + existence.contextFqn();
    }
    if (!normalizePath(existence.restrictedAttribute().path()).equals(restrictedPath)) {
      return "Compiled restricted path differs from the request: " + existence.restrictedAttribute().path();
    }
    if (!existence.restrictedAttribute().rootFqn().equals(context)) {
      return "Compiled restricted path root differs from the request: " + existence.restrictedAttribute().rootFqn();
    }
    if (existence.requiredIn().size() != targets.size()) {
      return "Compiled REQUIRED IN target count differs from the request.";
    }
    for (int i = 0; i < targets.size(); i++) {
      NormalizedTarget expected = targets.get(i);
      SemanticConstraint.ConstraintPath actual = existence.requiredIn().get(i);
      if (!expected.viewableFqn().equals(actual.rootFqn())
          || !expected.attributePath().equals(normalizePath(actual.path()))) {
        return "Compiled REQUIRED IN target " + (i + 1) + " differs from the request: "
            + actual.rootFqn() + ":" + actual.path();
      }
    }
    return null;
  }

  private @Nullable String scalarSupportProblem(SemanticConstraint.Existence existence) {
    List<SemanticConstraint.ConstraintPath> paths = new ArrayList<>();
    paths.add(existence.restrictedAttribute());
    paths.addAll(existence.requiredIn());
    for (SemanticConstraint.ConstraintPath path : paths) {
      ConstraintExpression.Type type = path.endpointType();
      if (type.collection()) {
        return "Collection-valued EXISTENCE path is outside B7 scalar scope: " + path.rootFqn() + ":" + path.path();
      }
      ConstraintExpression.ScalarKind kind = type.scalarKind();
      if (kind != ConstraintExpression.ScalarKind.NUMERIC
          && kind != ConstraintExpression.ScalarKind.BOOLEAN
          && kind != ConstraintExpression.ScalarKind.ENUM
          && kind != ConstraintExpression.ScalarKind.TEXT
          && kind != ConstraintExpression.ScalarKind.MTEXT) {
        return "EXISTENCE endpoint type " + kind + " is outside B7 scalar scope: "
            + path.rootFqn() + ":" + path.path();
      }
    }
    return null;
  }

  private Map<String, Object> semanticSummary(SemanticConstraint.Existence existence) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("kind", existence.kind().name());
    result.put("constraintScopedName", existence.constraintScopedName());
    result.put("contextFqn", existence.contextFqn());
    result.put("restrictedAttribute", pathSummary(existence.restrictedAttribute()));
    result.put("requiredIn", existence.requiredIn().stream().map(this::pathSummary).toList());
    return result;
  }

  private Map<String, Object> pathSummary(SemanticConstraint.ConstraintPath path) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("rootFqn", path.rootFqn());
    result.put("path", path.path());
    result.put("scalarKind", path.endpointType().scalarKind().name());
    result.put("nullable", path.endpointType().nullable());
    return Map.copyOf(result);
  }

  private List<NormalizedTarget> normalizeTargets(@Nullable List<RequiredInTarget> requiredIn) {
    if (requiredIn == null || requiredIn.isEmpty()) {
      throw new IllegalArgumentException("requiredIn must contain at least one target.");
    }
    List<NormalizedTarget> result = new ArrayList<>();
    for (int i = 0; i < requiredIn.size(); i++) {
      RequiredInTarget target = requiredIn.get(i);
      if (target == null) {
        throw new IllegalArgumentException("requiredIn[" + i + "] must not be null.");
      }
      result.add(new NormalizedTarget(
          requireFqn(target.viewableFqn, "requiredIn[" + i + "].viewableFqn"),
          requirePath(target.attributePath, "requiredIn[" + i + "].attributePath")));
    }
    return List.copyOf(result);
  }

  private String requireFqn(@Nullable String value, String label) {
    String normalized = requireText(value, label);
    String[] parts = normalized.split("\\.");
    if (parts.length < 2) {
      throw new IllegalArgumentException(label + " must be a qualified INTERLIS name.");
    }
    for (String part : parts) {
      requireIdentifier(part, label + " part");
    }
    return String.join(".", parts);
  }

  private String requirePath(@Nullable String value, String label) {
    String normalized = requireText(value, label);
    String[] parts = normalized.split("\\s*->\\s*", -1);
    for (String part : parts) {
      requireIdentifier(part, label + " segment");
    }
    return String.join("->", parts);
  }

  private String normalizePath(String path) {
    return String.join("->", path.trim().split("\\s*->\\s*", -1));
  }

  private String requireIdentifier(@Nullable String value, String label) {
    String normalized = requireText(value, label);
    if (!IDENTIFIER.matcher(normalized).matches()) {
      throw new IllegalArgumentException(label + " must be a simple INTERLIS identifier.");
    }
    return normalized;
  }

  private String requireText(@Nullable String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " is required.");
    }
    return value.trim();
  }

  private Map<String, Object> unavailable(
      String reasonCode,
      String reason,
      @Nullable String constraintBlock,
      @Nullable String candidateModelText,
      @Nullable Object sourceEdit) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("generated", false);
    result.put("proofVerified", false);
    result.put("reasonCode", reasonCode);
    result.put("reason", reason == null ? "" : reason);
    if (constraintBlock != null) {
      result.put("constraintBlock", constraintBlock);
    }
    if (candidateModelText != null) {
      result.put("candidateModelText", candidateModelText);
    }
    if (sourceEdit != null) {
      result.put("sourceEdit", sourceEdit);
    }
    return result;
  }

  private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
    if (source.containsKey(key)) {
      target.put(key, source.get(key));
    }
  }

  private String nonBlank(@Nullable String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private record NormalizedTarget(String viewableFqn, String attributePath) {
    private NormalizedTarget {
      Objects.requireNonNull(viewableFqn, "viewableFqn");
      Objects.requireNonNull(attributePath, "attributePath");
    }

    private Map<String, Object> asMap() {
      return Map.of(
          "viewableFqn", viewableFqn,
          "attributePath", attributePath);
    }
  }
}
