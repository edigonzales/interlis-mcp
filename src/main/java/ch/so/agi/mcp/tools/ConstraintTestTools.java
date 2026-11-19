package ch.so.agi.mcp.tools;

import ch.so.agi.mcp.model.ModelHashes;
import ch.so.agi.mcp.model.TestXtfOutput;
import ch.ehi.basics.settings.Settings;
import ch.interlis.ili2c.metamodel.AbstractClassDef;
import ch.interlis.ili2c.metamodel.AbstractCoordType;
import ch.interlis.ili2c.metamodel.AssociationDef;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.Cardinality;
import ch.interlis.ili2c.metamodel.CompositionType;
import ch.interlis.ili2c.metamodel.Constraint;
import ch.interlis.ili2c.metamodel.Container;
import ch.interlis.ili2c.metamodel.Domain;
import ch.interlis.ili2c.metamodel.Element;
import ch.interlis.ili2c.metamodel.EnumTreeValueType;
import ch.interlis.ili2c.metamodel.EnumerationType;
import ch.interlis.ili2c.metamodel.ExistenceConstraint;
import ch.interlis.ili2c.metamodel.Extendable;
import ch.interlis.ili2c.metamodel.Model;
import ch.interlis.ili2c.metamodel.NumericType;
import ch.interlis.ili2c.metamodel.NumericalType;
import ch.interlis.ili2c.metamodel.PredefinedModel;
import ch.interlis.ili2c.metamodel.Projection;
import ch.interlis.ili2c.metamodel.ReferenceType;
import ch.interlis.ili2c.metamodel.RoleDef;
import ch.interlis.ili2c.metamodel.SetConstraint;
import ch.interlis.ili2c.metamodel.Table;
import ch.interlis.ili2c.metamodel.TextOIDType;
import ch.interlis.ili2c.metamodel.TextType;
import ch.interlis.ili2c.metamodel.Topic;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.ili2c.metamodel.Type;
import ch.interlis.iom.IomObject;
import ch.interlis.iom_j.Iom_jObject;
import ch.interlis.iom_j.xtf.Xtf23Reader0;
import ch.interlis.iom_j.xtf.Xtf24Reader;
import ch.interlis.iom_j.xtf.XtfWriter;
import ch.interlis.iox.IoxEvent;
import ch.interlis.iox.IoxException;
import ch.interlis.iox.IoxLogEvent;
import ch.interlis.iox.IoxLogging;
import ch.interlis.iox.IoxReader;
import ch.interlis.iox_j.EndBasketEvent;
import ch.interlis.iox_j.EndTransferEvent;
import ch.interlis.iox_j.IoxIliReader;
import ch.interlis.iox_j.ObjectEvent;
import ch.interlis.iox_j.PipelinePool;
import ch.interlis.iox_j.StartBasketEvent;
import ch.interlis.iox_j.StartTransferEvent;
import ch.interlis.iox_j.logging.LogEventFactory;
import ch.interlis.iox_j.validator.ValidationConfig;
import ch.interlis.iox_j.validator.Validator;
import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.constraint.CompiledConstraintContext;
import ch.so.agi.mcp.constraint.ViewProofScope;
import ch.so.agi.mcp.util.McpInputLimits;
import ch.so.agi.mcp.constraint.ConstraintModelSynthesizer.CountShape;
import ch.so.agi.mcp.constraint.ConstraintModelSynthesizer.GraphLink;
import ch.so.agi.mcp.constraint.ConstraintModelSynthesizer.GraphObject;
import ch.so.agi.mcp.constraint.ConstraintModelSynthesizer.ObjectGraph;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class ConstraintTestTools {

  private static final String TARGET_VIOLATION_MARKER = "INTERLIS_MCP_TARGET_CONSTRAINT_VIOLATED";
  private static final int MAX_AUTOMATIC_ASSOCIATION_ELEMENTS = 64;

  private final IliCompilerService compilerService;

  public ConstraintTestTools(IliCompilerService compilerService) {
    this.compilerService = compilerService;
  }

  public enum ExpectationSource { USER_PROVIDED, USER_CONFIRMED, AGENT_DERIVED, UNSPECIFIED }

  public static class TestCase {
    public @Nullable ExpectationSource expectationSource = ExpectationSource.UNSPECIFIED;
    public String name;
    public Boolean expectedConstraintValid;
    public List<TestObject> objects;
    @com.fasterxml.jackson.annotation.JsonIgnore
    Map<String, Map<String, Integer>> plannedObjectCounts = Map.of();
    @com.fasterxml.jackson.annotation.JsonIgnore
    Map<String, Map<String, CountShape>> plannedObjectShapes = Map.of();
    public @Nullable List<TestLink> links;

    static TestCase fromGraph(String name, boolean expected, ObjectGraph graph) {
      TestCase testCase = new TestCase();
      testCase.name = name;
      testCase.expectedConstraintValid = expected;
      testCase.objects = graph.objects().stream().map(TestObject::fromGraph).toList();
      testCase.links = graph.links().stream().map(TestLink::fromGraph).toList();
      return testCase;
    }
  }

  public static class TestObject {
    public String classFqn;
    public @Nullable String oid;
    public @Nullable String basketId;
    public @Nullable Map<String, Object> values;
    public @Nullable Map<String, String> references;

    static TestObject fromGraph(GraphObject object) {
      TestObject result = new TestObject();
      result.classFqn = object.classFqn();
      result.oid = object.oid();
      result.values = object.values();
      result.references = object.references();
      return result;
    }
  }

  public static class TestLink {
    public String associationFqn;
    public @Nullable String basketId;
    public @Nullable String oid;
    public Map<String, String> roles;

    static TestLink fromGraph(GraphLink link) {
      TestLink result = new TestLink();
      result.associationFqn = link.associationFqn();
      result.roles = link.roles();
      return result;
    }
  }

  @McpTool(
      name = "testIliConstraint",
      description = "Prüft einen bestehenden Constraint mit ilivalidator gegen unabhängig festgelegte erwartete Ergebnisse.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true)
  )
  public Map<String, Object> testIliConstraint(
      @McpToolParam(description = "Vollstaendiger INTERLIS-2 Modelltext", required = true) String modelText,
      @McpToolParam(description = "Constraint-Name oder vollqualifizierter Constraint-Name", required = true) String constraint,
      @McpToolParam(description = "Testfälle mit expectedConstraintValid, Objekten und optionalen Links. expectationSource gibt die Herkunft der Erwartung an.", required = true) List<TestCase> cases,
      @McpToolParam(description = TestXtfOutput.PARAMETER, required = false) @org.jspecify.annotations.Nullable Boolean includeSuccessfulTestXtf) {
    return ModelHashes.attach(testIliConstraintFull(modelText, constraint, cases,
        Boolean.TRUE.equals(includeSuccessfulTestXtf)), ModelHashes.model(modelText));
  }

  public Map<String, Object> testIliConstraint(String modelText, String constraint, List<TestCase> cases) {
    return testIliConstraint(modelText, constraint, cases, null);
  }

  private Map<String, Object> testIliConstraintFull(
      String modelText, String constraint, List<TestCase> cases, boolean includeSuccessfulTestXtf) {
    McpInputLimits.requireConstraintCases(cases);

    IliCompilerService.CompilationResult compilation =
        compilerService.compile(modelText, null, "ili2c_constraint_test_");
    if (!compilation.valid() || compilation.transferDescription() == null) {
      Map<String, Object> response = new LinkedHashMap<>(Map.of(
          "tested", false,
          "compilerValid", false,
          "messages", compilation.messages(),
          "caseCount", cases.size(),
          "passedCount", 0,
          "allPassed", false,
          "automaticCasesGenerated", false));
      response.put("cases", cases.stream().map(c -> {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", c == null ? null : c.name);
        item.put("expectationSource", c == null || c.expectationSource == null ? ExpectationSource.UNSPECIFIED : c.expectationSource);
        item.put("tested", false);
        item.put("passed", false);
        item.put("reasonCode", "MODEL_INVALID");
        return item;
      }).toList());
      return response;
    }

    TransferDescription td = compilation.transferDescription();
    List<Constraint> constraints = collectConstraints(td);
    Constraint target = findConstraint(td, constraints, constraint);
    if (target == null) {
      throw new IllegalArgumentException("Constraint not found: " + constraint);
    }

    Map<String, Object> response = testResolvedConstraint(td, target, constraints, cases, includeSuccessfulTestXtf);
    response.put("messages", compilation.messages());
    return response;
  }

  Map<String, Object> testCompiledConstraint(
      CompiledConstraintContext context,
      List<TestCase> cases) {
    Objects.requireNonNull(context, "context");
    McpInputLimits.requireConstraintCases(cases);
    var td = context.transferDescription();
    return testResolvedConstraint(td, context.constraint(), collectConstraints(td), cases, true);
  }

  private Map<String, Object> testResolvedConstraint(
      TransferDescription td,
      Constraint target,
      List<Constraint> constraints,
      List<TestCase> cases,
      boolean includeSuccessfulTestXtf) {
    String targetQName = constraintQName(target);
    String context = target.getContainer() != null ? target.getContainer().getScopedName(null) : "";
    ValidationConfig config = validationConfig(target, targetQName, constraints);
    List<Map<String, Object>> results = new ArrayList<>();
    int passedCount = 0;
    int omittedXtfCount = 0;

    for (int i = 0; i < cases.size(); i++) {
      TestCase testCase = requireCase(cases.get(i), i, target);
      Map<String, Object> result = runCase(td, target, targetQName, config, testCase, i + 1, includeSuccessfulTestXtf);
      result.put("expectationSource", testCase.expectationSource == null ? ExpectationSource.UNSPECIFIED : testCase.expectationSource);
      results.add(result);
      if (Boolean.TRUE.equals(result.get("passed"))) {
        passedCount++;
        if (!result.containsKey("xtfText")) omittedXtfCount++;
      }
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("tested", true);
    response.put("compilerValid", true);
    response.put("constraint", Map.of(
        "name", target.getName(),
        "scopedName", target.getScopedName(null),
        "validatorQName", targetQName,
        "context", context));
    response.put("caseCount", results.size());
    response.put("passedCount", passedCount);
    response.put("allPassed", passedCount == results.size());
    response.put("cases", results);
    if (omittedXtfCount > 0) response.put("omittedSuccessfulTestXtfCount", omittedXtfCount);
    response.put("automaticCasesGenerated", false);
    response.put("limitations", List.of(
        "Test cases are supplied explicitly by the agent; no witness or counterexample generation is performed.",
        "Scalar values, references, association links, nested STRUCTURE value maps and explicit multi-basket fixtures are supported; geometry remains outside the explicit-case support boundary.",
        "Cross-basket references are emitted with BID; the referenced model element must still permit the corresponding external reference semantics.",
        "Other INTERLIS constraints are disabled while the selected constraint is tested, but type, multiplicity and transfer checks remain active."));
    return response;
  }

  private Map<String, Object> runCase(
      TransferDescription td,
      Constraint target,
      String targetQName,
      ValidationConfig config,
      TestCase testCase,
      int caseIndex,
      boolean includeSuccessfulTestXtf) {
    PreparedCase prepared;
    Map<BasketKey, List<Iom_jObject>> iomObjects;
    int subjectCount;
    ViewProofScope viewScope;
    try {
      viewScope = ViewProofScope.resolve(td, target);
      prepared = prepareCase(td, testCase, caseIndex);
      iomObjects = buildCaseObjects(td, prepared, caseIndex);
      if (viewScope != null && target instanceof SetConstraint) {
        if (iomObjects.isEmpty()) {
          iomObjects.put(new BasketKey((Topic) viewScope.base().getContainer(), "view_base_" + caseIndex), List.of());
        }
        Topic scopeTopic = (Topic) viewScope.view().getContainer();
        String scopeBid = "view_scope_" + caseIndex;
        Set<String> usedBids = new LinkedHashSet<>();
        iomObjects.keySet().forEach(key -> usedBids.add(key.basketId()));
        while (usedBids.contains(scopeBid)) {
          scopeBid += "_";
        }
        iomObjects.put(new BasketKey(scopeTopic, scopeBid), List.of());
      }
      subjectCount = viewScope == null ? countSubjects(td, target, prepared, iomObjects) : 0;
    } catch (IllegalArgumentException ex) {
      String reasonCode = ex instanceof ConstraintFixtureException known
          ? known.reasonCode() : ex instanceof ViewProofScope.ScopeException scopeError ? scopeError.reasonCode() : "FIXTURE_MATERIALIZATION_FAILED";
      return fixtureFailure(testCase, 0, reasonCode, ex.getMessage());
    }
    Path xtfFile = null;
    try {
      xtfFile = Files.createTempFile("interlis-mcp-constraint-case-", ".xtf");
      var baskets = writeCaseXtf(td, iomObjects, xtfFile);
      ValidationOutcome validation = validateCase(td, target, targetQName, config, xtfFile, viewScope,
          iomObjects, testCase.plannedObjectCounts, testCase.plannedObjectShapes);

      int targetViolationCount = 0;
      var fixtureErrors = new ArrayList<Map<String, Object>>();
      for (var message : validation.messages()) {
        if (TARGET_VIOLATION_MARKER.equals(message.get("message"))) {
          targetViolationCount++;
        } else if ("ERROR".equals(message.get("severity"))) {
          fixtureErrors.add(message);
        }
      }

      boolean actualConstraintValid = targetViolationCount == 0;
      boolean fixtureValid = fixtureErrors.isEmpty();
      if (viewScope != null) subjectCount = ((Number)validation.viewDiagnostics().getOrDefault("subjectCount",0)).intValue();
      boolean setExecuted = target instanceof SetConstraint && validation.targetExecuted();
      boolean exercised = validation.targetExecuted() && (subjectCount > 0 || viewScope != null && setExecuted);
      boolean expected = testCase.expectedConstraintValid;
      boolean passed = exercised && fixtureValid && actualConstraintValid == expected;

      var result = caseOutcome(testCase, actualConstraintValid, passed, exercised, subjectCount);
      if (viewScope != null) {
        result.put("setExecuted", setExecuted);
      }
      result.putAll(validation.viewDiagnostics());
      result.put("basketCount", baskets.size());
      result.put("baskets", baskets);
      result.put("fixtureValid", fixtureValid);
      result.put("validatorValid", validation.errorCount() == 0);
      result.put("targetViolationCount", targetViolationCount);
      result.put("errorCount", validation.errorCount());
      result.put("warningCount", validation.warningCount());
      result.put("messages", validation.messages());
      result.put("fixtureErrors", fixtureErrors);
      if (includeSuccessfulTestXtf || !passed || validation.warningCount() > 0) {
        result.put("xtfText", Files.readString(xtfFile, StandardCharsets.UTF_8));
      }
      if (!fixtureValid) {
        result.put("reason", "The generated fixture has non-target validation errors.");
        for(var error : fixtureErrors) {
          String message = String.valueOf(error.getOrDefault("message", ""));
          for(String code : List.of("OBJECT_PATH_COUNT_MISMATCH", "OBJECT_PATH_TOPOLOGY_MISMATCH", "OBJECT_PATH_VERIFICATION_FAILED"))
            if(message.contains(code)) result.put("fixturePreparationReasonCode", code);
        }
      } else if (!exercised) {
        result.put("reason", "The validator did not exercise the selected constraint in this fixture.");
        if (viewScope != null) result.put("fixturePreparationReasonCode", target instanceof SetConstraint
            ? "VIEW_SET_NOT_EXECUTED" : "VIEW_SCOPE_NOT_EXERCISED");
      } else if (actualConstraintValid != expected) {
        result.put("reason", "Observed target constraint result differs from expectedConstraintValid.");
      }
      return result;
    } catch (IOException e) {
      String code = "FIXTURE_XTF_SERIALIZATION_FAILED";
      Throwable cause = e;
      while (cause.getCause() != null) cause = cause.getCause();
      String reason = cause.getMessage() == null ? e.toString() : cause.getMessage();
      if (reason.contains("max one reference") || reason.contains("cardinality")) code = "OBJECT_PATH_CARDINALITY_VIOLATION";
      return fixtureFailure(testCase, subjectCount, code, reason);
    } finally {
      if (xtfFile != null) {
        try {
          Files.deleteIfExists(xtfFile);
        } catch (IOException e) {
          System.err.println("Unable to delete a temporary constraint-test XTF file.");
        }
      }
    }
  }

  private Map<String, Object> caseOutcome(
      TestCase testCase, boolean actual, boolean passed, boolean exercised, int subjectCount) {
    var result = new LinkedHashMap<String, Object>();
    result.put("name", testCase.name.trim());
    result.put("expectedConstraintValid", testCase.expectedConstraintValid);
    result.put("actualConstraintValid", actual);
    result.put("passed", passed);
    result.put("constraintExercised", exercised);
    result.put("subjectCount", subjectCount);
    return result;
  }

  private Map<String, Object> fixtureFailure(
      TestCase testCase, int subjectCount, String code, String reason) {
    var result = caseOutcome(testCase, false, false, false, subjectCount);
    result.put("fixtureValid", false);
    result.put("fixturePreparationReasonCode", code);
    result.put("fixtureErrors", List.of(Map.of("severity", "ERROR", "code", code, "message", reason)));
    result.put("reason", reason);
    result.put("basketCount", 0);
    result.put("baskets", List.of());
    result.put("validatorValid", false);
    result.put("targetViolationCount", 0);
    result.put("errorCount", 1);
    result.put("warningCount", 0);
    result.put("messages", result.get("fixtureErrors"));
    return result;
  }

  private PreparedCase prepareCase(
      TransferDescription td,
      TestCase testCase,
      int caseIndex) {
    List<TestLink> explicitLinks = testCase.links == null ? List.of() : testCase.links;
    if (testCase.objects.size() + explicitLinks.size() > 64)
      throw new ConstraintFixtureException("OBJECT_PATH_FIXTURE_BUDGET_EXCEEDED", "A fixture permits at most 64 explicit class objects and relationships.");
    Map<String, PreparedObject> objectsByOid = new LinkedHashMap<>();

    for (int i = 0; i < testCase.objects.size(); i++) {
      TestObject spec = testCase.objects.get(i);
      if (spec == null || spec.classFqn == null || spec.classFqn.isBlank()) {
        throw new IllegalArgumentException("Case '" + testCase.name + "': object classFqn is required.");
      }
      Element element = td.getElement(spec.classFqn.trim());
      if (!(element instanceof Table table) || !table.isIdentifiable()) {
        throw new IllegalArgumentException("Case '" + testCase.name + "': classFqn is not an identifiable class: " + spec.classFqn);
      }
      String oid = spec.oid == null || spec.oid.isBlank()
          ? "case" + caseIndex + "_o" + (i + 1)
          : spec.oid.trim();
      if (objectsByOid.containsKey(oid)) {
        throw new IllegalArgumentException("Case '" + testCase.name + "': duplicate OID " + oid);
      }
      objectsByOid.put(oid, new PreparedObject(
          table,
          oid,
          optionalId(spec.basketId),
          fixtureValues(spec)));
    }

    List<PreparedLink> links = new ArrayList<>();
    for (TestLink spec : explicitLinks) {
      if (spec == null || spec.associationFqn == null || spec.associationFqn.isBlank()) {
        throw new IllegalArgumentException("Case '" + testCase.name + "': link associationFqn is required.");
      }
      Element element = td.getElement(spec.associationFqn.trim());
      if (!(element instanceof AssociationDef association)) {
        throw new IllegalArgumentException("Case '" + testCase.name + "': associationFqn is not an association: " + spec.associationFqn);
      }
      if (spec.roles == null || spec.roles.size() < 2) {
        throw new IllegalArgumentException("Case '" + testCase.name + "': association link needs at least two role references.");
      }
      var roles = new LinkedHashMap<String, String>();
      for (var role : spec.roles.entrySet()) {
        String name = optionalId(role.getKey());
        String oid = optionalId(role.getValue());
        if (name == null || oid == null) {
          throw new IllegalArgumentException("Case '" + testCase.name + "': link role names and OIDs must be non-empty.");
        }
        if (!objectsByOid.containsKey(oid)) {
          throw new IllegalArgumentException("Case '" + testCase.name + "': link role '" + name + "' references unknown OID " + oid);
        }
        if (association.findRole(name) == null || roles.putIfAbsent(name, oid) != null) {
          throw fixturePreparationFailure("FIXTURE_ASSOCIATION_ROLE_INVALID", testCase,
              "Unknown or repeated role '" + name + "' of " + association.getScopedName() + ".");
        }
      }
      for (var it = association.getAttributesAndRoles(); it.hasNext();) {
        if (it.next() instanceof RoleDef role && !roles.containsKey(role.getName())) {
          throw fixturePreparationFailure("FIXTURE_ASSOCIATION_ROLE_INVALID", testCase,
              "Missing role '" + role.getName() + "' of " + association.getScopedName() + ".");
        }
      }
      String requestedBasketId = optionalId(spec.basketId);
      RoleDef embeddedEnd = association.getRoleWhereEmbedded();
      if (embeddedEnd != null && requestedBasketId != null) {
        throw new IllegalArgumentException("Case '" + testCase.name
            + "': basketId is not applicable to lightweight association " + association.getScopedName(null)
            + "; its embedded reference belongs to the owner object's basket.");
      }
      links.add(new PreparedLink(association, embeddedEnd, requestedBasketId, optionalId(spec.oid), roles));
    }

    completeMandatoryAssociations(testCase, caseIndex, objectsByOid, links);
    Map<Topic, String> defaults = allocateDefaultBasketIds(testCase, objectsByOid.values(), links);
    objectsByOid.replaceAll((oid, object) -> object.basketId() != null ? object : new PreparedObject(
        object.table(), oid, defaults.get(topicOf(object.table())), object.values()));
    return new PreparedCase(links, objectsByOid, defaults);
  }

  private Map<String, Object> fixtureValues(TestObject spec) {
    Map<String, Object> values = spec.values == null ? Map.of() : spec.values;
    if (spec.references == null || spec.references.isEmpty()) return values;
    var merged = new LinkedHashMap<>(values);
    spec.references.forEach((name, oid) -> {
      if (name == null || name.isBlank() || oid == null || oid.isBlank()) {
        throw new IllegalArgumentException("Fixture reference names and target OIDs must be non-empty.");
      }
      String attribute = name.trim();
      if (merged.containsKey(attribute) && merged.get(attribute) != null) {
        throw new IllegalArgumentException("Duplicate fixture assignment for reference '" + attribute + "'.");
      }
      merged.put(attribute, new TypedValueFixtureFactory.ReferenceValue(oid.trim()));
    });
    return merged;
  }

  private void completeMandatoryAssociations(
      TestCase testCase,
      int caseIndex,
      Map<String, PreparedObject> objectsByOid,
      List<PreparedLink> links) {
    var pending = new ArrayDeque<>(objectsByOid.values());
    int generatedElements = 0;
    int generatedObjectIndex = 1;
    while (!pending.isEmpty()) {
      PreparedObject source = pending.removeFirst();
      var navigationRoles = new TreeMap<String, RoleDef>();
      source.table().getOpposideRoles().forEachRemaining(role ->
          navigationRoles.putIfAbsent(role.getScopedName(), role));
      for (RoleDef targetRole : navigationRoles.values()) {
        if (!(targetRole.getContainer() instanceof AssociationDef association)
            || targetRole.getOppEnd() == null
            || targetRole.getDestination() == null) {
          continue;
        }
        Cardinality cardinality = targetRole.getCardinality();
        long minimum = cardinality != null ? cardinality.getMinimum() : 1;
        if (minimum <= 0) {
          continue;
        }
        if (!targetRole.hasOneOppEnd()) {
          throw fixturePreparationFailure(
              "MANDATORY_ASSOCIATION_NARY_UNSUPPORTED",
              testCase,
              "Cannot complete non-binary mandatory association "
                  + association.getScopedName(null) + ".");
        }

        RoleDef sourceRole = targetRole.getOppEnd();
        for (long count = countLinksForSource(links, association, sourceRole, source.oid()); count < minimum; count++) {
          PreparedObject target = reusableTarget(objectsByOid.values(), links, targetRole, source);
          boolean createsObject = target == null;
          int requiredElements = createsObject ? 2 : 1;
          if (generatedElements + requiredElements > MAX_AUTOMATIC_ASSOCIATION_ELEMENTS) {
            throw fixturePreparationFailure(
                "MANDATORY_ASSOCIATION_FIXTURE_LIMIT",
                testCase,
                "Completing mandatory associations would exceed the limit of "
                    + MAX_AUTOMATIC_ASSOCIATION_ELEMENTS + " generated objects and links.");
          }
          if (createsObject) {
            Table targetTable = concreteTargetTable(targetRole, testCase);
            String oid;
            do {
              int objectSequence = generatedObjectIndex++;
              oid = generatedObjectOid(targetTable, caseIndex, objectSequence);
            } while (objectsByOid.containsKey(oid));
            String targetBasketId = topicOf(targetTable) == topicOf(source.table())
                    && !targetRole.isExternal()
                ? source.basketId()
                : null;
            target = new PreparedObject(targetTable, oid, targetBasketId, Map.of());
            pending.addLast(target);
            objectsByOid.put(oid, target);
            generatedElements++;
          }

          links.add(new PreparedLink(association, association.getRoleWhereEmbedded(), null, null,
              Map.of(sourceRole.getName(), source.oid(), targetRole.getName(), target.oid())));
          generatedElements++;
        }
      }
    }
  }

  private String generatedObjectOid(AbstractClassDef<?> type, int caseIndex, int objectIndex) {
    String seed = type.getScopedName(null) + ":" + caseIndex + ":" + objectIndex;
    Domain oidDomain = type.getOid();
    if (usesUuidOid(oidDomain)) {
      return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }
    if (oidDomain != null && oidDomain.getType() instanceof TextOIDType) {
      return String.format(Locale.ROOT, "mcp%013d", caseIndex * 1000L + objectIndex);
    }
    return "case" + caseIndex + "_mandatory_" + objectIndex;
  }

  private @Nullable String associationOid(
      TransferDescription td, PreparedLink link, Set<String> usedOids, int caseIndex, int linkIndex) {
    AssociationDef association = link.association();
    Domain domain = association.getOid();
    String explicit = link.oid();
    if (!association.isIdentifiable() && domain == null) {
      if (explicit == null) return null;
      throw new ConstraintFixtureException("ASSOCIATION_OID_NOT_ALLOWED",
          association.getScopedName() + " does not allow an association OID.");
    }
    if (link.embeddedEnd() != null) {
      throw new ConstraintFixtureException("ASSOCIATION_OID_TRANSFER_UNSUPPORTED",
          "The installed INTERLIS libraries classify " + association.getScopedName()
              + " as embedded despite its OID requirement.");
    }
    if (domain != null && domain != td.INTERLIS.ANYOID && domain != td.INTERLIS.I32OID
        && !(domain.getType() instanceof TextOIDType)) {
      throw new ConstraintFixtureException("ASSOCIATION_OID_DOMAIN_UNSUPPORTED",
          "Unsupported association OID domain " + domain.getScopedName() + ".");
    }
    if (explicit != null) {
      if (!validAssociationOid(td, domain, explicit)) {
        throw new ConstraintFixtureException("ASSOCIATION_OID_INVALID",
            "The supplied OID does not match the OID domain of " + association.getScopedName() + ".");
      }
      return explicit;
    }
    for (int sequence = linkIndex; sequence < linkIndex + usedOids.size() + 1; sequence++) {
      String candidate = domain == td.INTERLIS.I32OID
          ? Integer.toString(caseIndex * 1000 + sequence)
          : generatedObjectOid(association, caseIndex, sequence);
      if (!validAssociationOid(td, domain, candidate)) break;
      if (usedOids.add(candidate)) return candidate;
    }
    throw new ConstraintFixtureException("ASSOCIATION_OID_REQUIRED",
        "Supply an OID in the domain of " + association.getScopedName() + ".");
  }

  private boolean validAssociationOid(TransferDescription td, @Nullable Domain domain, String value) {
    if (usesUuidOid(domain)) {
      return value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    }
    if (domain == td.INTERLIS.I32OID) {
      return value.matches("[0-9]{1,10}") && Long.parseLong(value) <= Integer.MAX_VALUE;
    }
    if (domain == td.INTERLIS.STANDARDOID) {
      return value.length() == 16 && value.matches(Validator.REGEX_FOR_STANDARTOID_VALIDATION);
    }
    if (domain != null && domain.getType() instanceof TextOIDType textOid) {
      int maximum = ((TextType) textOid.getOIDType()).getMaxLength();
      return (maximum < 0 || value.length() <= maximum) && value.matches(Validator.REGEX_FOR_TEXTOID_VALIDATION);
    }
    return value.matches(Validator.REGEX_FOR_ID_VALIDATION);
  }

  private boolean usesUuidOid(@Nullable Domain oidDomain) {
    Set<Domain> visited = new LinkedHashSet<>();
    Domain current = oidDomain;
    while (current != null && visited.add(current)) {
      if (current == PredefinedModel.getInstance().UUIDOID) {
        return true;
      }
      current = current.getExtending();
    }
    return false;
  }

  private long countLinksForSource(
      List<PreparedLink> links,
      AssociationDef association,
      RoleDef sourceRole,
      String sourceOid) {
    return links.stream()
        .filter(link -> link.association() == association)
        .filter(link -> sourceOid.equals(link.roles().get(sourceRole.getName())))
        .count();
  }

  private @Nullable PreparedObject reusableTarget(
      Collection<PreparedObject> objects,
      List<PreparedLink> links,
      RoleDef targetRole,
      PreparedObject source) {
    if (!(targetRole.getDestination() instanceof Table declaredTarget)) {
      return null;
    }
    RoleDef sourceRole = targetRole.getOppEnd();
    long maximumBackReferences = sourceRole.getCardinality() != null
        ? sourceRole.getCardinality().getMaximum()
        : 1;
    return objects.stream()
        .filter(candidate -> candidate != source)
        .filter(candidate -> compatibleTable(candidate.table(), declaredTarget))
        .filter(candidate -> targetRole.isExternal()
            || topicOf(candidate.table()) != topicOf(source.table())
            || Objects.equals(candidate.basketId(), source.basketId()))
        .filter(candidate -> links.stream().noneMatch(link ->
            link.association() == targetRole.getContainer()
                && source.oid().equals(link.roles().get(sourceRole.getName()))
                && candidate.oid().equals(link.roles().get(targetRole.getName()))))
        .filter(candidate -> maximumBackReferences == Cardinality.UNBOUND
            || links.stream()
                .filter(link -> link.association() == targetRole.getContainer())
                .filter(link -> candidate.oid().equals(link.roles().get(targetRole.getName())))
                .count() < maximumBackReferences)
        .min(Comparator.comparing(PreparedObject::oid))
        .orElse(null);
  }

  private boolean compatibleTable(Table actual, Table declared) {
    return actual == declared || actual.isExtending(declared);
  }

  private Table concreteTargetTable(RoleDef targetRole, TestCase testCase) {
    if (!(targetRole.getDestination() instanceof Table declared) || !declared.isIdentifiable()) {
      throw fixturePreparationFailure(
          "MANDATORY_ASSOCIATION_TARGET_UNSUPPORTED",
          testCase,
          "Mandatory role " + targetRole.getScopedName(null)
              + " does not target an identifiable class.");
    }
    if (!declared.isAbstract()) {
      return declared;
    }

    var candidates = declared.getExtensions().stream()
        .filter(Table.class::isInstance).map(Table.class::cast)
        .filter(table -> !table.isAbstract() && table.isIdentifiable())
        .toList();
    if (candidates.size() != 1) {
      throw fixturePreparationFailure(
          "MANDATORY_ASSOCIATION_TARGET_AMBIGUOUS",
          testCase,
          "Mandatory role " + targetRole.getScopedName(null) + " targets abstract class "
              + declared.getScopedName(null) + " with " + candidates.size()
              + " concrete choices; the fixture cannot choose one without changing semantics.");
    }
    return candidates.getFirst();
  }

  private ConstraintFixtureException fixturePreparationFailure(
      String reasonCode,
      TestCase testCase,
      String reason) {
    return new ConstraintFixtureException(
        reasonCode,
        "Case '" + testCase.name + "': " + reason);
  }

  private Map<Topic, String> allocateDefaultBasketIds(
      TestCase testCase, Collection<PreparedObject> objects, List<PreparedLink> links) {
    Map<String, Topic> explicit = new LinkedHashMap<>();
    Set<Topic> implicit = new LinkedHashSet<>();
    for (PreparedObject object : objects) {
      registerBasket(testCase, explicit, implicit, topicOf(object.table()), object.basketId());
    }
    for (PreparedLink link : links) {
      if (link.embeddedEnd() == null) {
        registerBasket(testCase, explicit, implicit, topicOf(link.association()), link.basketId());
      }
    }

    var defaults = new LinkedHashMap<Topic, String>();
    int next = 1;
    for (Topic topic : implicit.stream().sorted(Comparator.comparing(Topic::getScopedName)).toList()) {
      String candidate;
      do {
        candidate = "b" + next++;
      } while (explicit.containsKey(candidate));
      defaults.put(topic, candidate);
    }
    return defaults;
  }

  private void registerBasket(
      TestCase testCase, Map<String, Topic> explicit, Set<Topic> implicit, Topic topic, @Nullable String bid) {
    if (bid == null) {
      implicit.add(topic);
    } else {
      Topic previous = explicit.putIfAbsent(bid, topic);
      if (previous != null && previous != topic) {
        throw new IllegalArgumentException("Case '" + testCase.name + "': basketId '" + bid
            + "' is used for more than one topic (" + previous.getScopedName() + " and "
            + topic.getScopedName() + "). Basket IDs must be unique across topics.");
      }
    }
  }

  private List<BasketKey> orderBaskets(Set<BasketKey> baskets) {
    List<BasketKey> remaining = new ArrayList<>(baskets);
    List<BasketKey> ordered = new ArrayList<>();
    Comparator<BasketKey> stableOrder = Comparator
        .comparing((BasketKey key) -> key.topic().getScopedName(null))
        .thenComparing(BasketKey::basketId);

    while (!remaining.isEmpty()) {
      Set<Topic> remainingTopics = new LinkedHashSet<>();
      remaining.forEach(key -> remainingTopics.add(key.topic()));
      List<BasketKey> ready = remaining.stream()
          .filter(key -> !hasRemainingTopicDependency(key.topic(), remainingTopics))
          .sorted(stableOrder)
          .toList();
      if (ready.isEmpty()) {
        remaining.sort(stableOrder);
        ordered.addAll(remaining);
        break;
      }
      ordered.addAll(ready);
      remaining.removeAll(ready);
    }
    return ordered;
  }

  private boolean hasRemainingTopicDependency(Topic topic, Set<Topic> remainingTopics) {
    Iterator<Topic> dependencies = topic.getDependentOn();
    while (dependencies.hasNext()) {
      if (remainingTopics.contains(dependencies.next())) {
        return true;
      }
    }
    return false;
  }

  private Map<BasketKey, List<Iom_jObject>> buildCaseObjects(
      TransferDescription td, PreparedCase prepared, int caseIndex) {
    FixtureBudget budget = new FixtureBudget();
    Map<BasketKey, List<Iom_jObject>> objectsByBasket = new LinkedHashMap<>();
    Map<String, Iom_jObject> iomObjectsByOid = new LinkedHashMap<>();
    int objectIndex = 1;
    for (PreparedObject preparedObject : prepared.objectsByOid().values()) {
      BasketKey basket = new BasketKey(topicOf(preparedObject.table()), preparedObject.basketId());
      Iom_jObject object = materializeObject(preparedObject.table(), preparedObject.oid(), preparedObject.values(),
          basket, objectIndex++, prepared.objectsByOid(), budget);
      objectsByBasket.computeIfAbsent(basket, key -> new ArrayList<>()).add(object);
      iomObjectsByOid.put(preparedObject.oid(), object);
    }

    Set<String> usedOids = new LinkedHashSet<>(prepared.objectsByOid().keySet());
    for (PreparedLink link : prepared.links()) {
      if (link.oid() != null && !usedOids.add(link.oid())) {
        throw new ConstraintFixtureException("ASSOCIATION_OID_DUPLICATE", "Duplicate transfer OID " + link.oid() + ".");
      }
    }
    int linkIndex = 0;
    for (PreparedLink link : prepared.links()) {
      String oid = associationOid(td, link, usedOids, caseIndex, ++linkIndex);
      RoleDef embeddedEnd = link.embeddedEnd();
      if (embeddedEnd != null) {
        var owner = prepared.objectsByOid().get(link.roles().get(embeddedEnd.getName()));
        String role = embeddedEnd.getOppEnd().getName();
        addReference(iomObjectsByOid.get(owner.oid()), role, new BasketKey(topicOf(owner.table()), owner.basketId()),
            prepared.objectsByOid().get(link.roles().get(role)));
        continue;
      }
      Topic topic = topicOf(link.association());
      BasketKey basket = new BasketKey(topic, link.basketId() != null ? link.basketId() : prepared.defaults().get(topic));
      Iom_jObject associationObject = new Iom_jObject(link.association().getScopedName(), oid);
      link.roles().forEach((role, targetOid) ->
          addReference(associationObject, role, basket, prepared.objectsByOid().get(targetOid)));
      objectsByBasket.computeIfAbsent(basket, key -> new ArrayList<>()).add(associationObject);
    }

    return objectsByBasket;
  }

  private List<Map<String, Object>> writeCaseXtf(
      TransferDescription td, Map<BasketKey, List<Iom_jObject>> objectsByBasket, Path xtfFile) throws IOException {
    var summaries = new ArrayList<Map<String, Object>>();
    try (OutputStream output = Files.newOutputStream(xtfFile)) {
      XtfWriter writer = new XtfWriter(output, td);
      try (java.io.Closeable closeWriter = () -> {
        try {
          writer.close();
        } catch (IoxException e) {
          throw new IOException("Unable to close the fixture writer.", e);
        }
      }) {
        StartTransferEvent start = new StartTransferEvent();
        start.setVersion(td.getLastModel().isIli23() ? "2.3" : "2.4");
        start.setSender("interlis-mcp");
        start.setComment("testIliConstraint");
        writer.write(start);
        for (BasketKey basket : orderBaskets(objectsByBasket.keySet())) {
          String topic = basket.topic().getScopedName();
          writer.write(new StartBasketEvent(topic, basket.basketId()));
          long classCount = 0;
          long associationCount = 0;
          for (Iom_jObject object : objectsByBasket.get(basket)) {
            switch (td.getElement(object.getobjecttag())) {
              case Table table when table.isIdentifiable() -> classCount++;
              case AssociationDef association -> associationCount++;
              case null, default -> throw new IllegalStateException("Unexpected top-level fixture type " + object.getobjecttag());
            }
            writer.write(new ObjectEvent(object));
          }
          writer.write(new EndBasketEvent());
          summaries.add(Map.of("topic", topic, "basketId", basket.basketId(),
              "objectCount", classCount, "associationObjectCount", associationCount));
        }
        writer.write(new EndTransferEvent());
        writer.flush();
      }
      return summaries;
    } catch (IoxException e) {
      throw new IOException("Unable to write explicit constraint test XTF.", e);
    }
  }

  private ValidationConfig validationConfig(Constraint target, String targetQName, List<Constraint> constraints) {
    ValidationConfig config = new ValidationConfig();
    config.setConfigValue(ValidationConfig.PARAMETER, ValidationConfig.ALL_OBJECTS_ACCESSIBLE, ValidationConfig.TRUE);
    if (target.getContainer() instanceof Projection projection && projection.getContainer(Model.class) instanceof Model model) {
      config.setConfigValue(ValidationConfig.PARAMETER, ValidationConfig.ADDITIONAL_MODELS, model.getName());
    }
    for (Constraint constraint : constraints) {
      String qName = constraintQName(constraint);
      if (targetQName.equals(qName)) {
        config.setConfigValue(qName, ValidationConfig.MSG, TARGET_VIOLATION_MARKER);
      } else {
        config.setConfigValue(qName, ValidationConfig.CHECK, ValidationConfig.OFF);
      }
    }
    return config;
  }

  private ValidationOutcome validateCase(
      TransferDescription td, Constraint target, String targetQName, ValidationConfig config,
      Path xtfFile, @Nullable ViewProofScope viewScope, Map<BasketKey, List<Iom_jObject>> objectsByBasket,
      Map<String, Map<String, Integer>> plannedCounts, Map<String, Map<String, CountShape>> plannedShapes) {
    CollectingIoxLogging logging = new CollectingIoxLogging(xtfFile, targetQName);
    Validator validator = new Validator(td, config, logging, new LogEventFactory(), new PipelinePool(), new Settings());
    try {
      boolean completed = validateTransfer(td, target, xtfFile, validator, logging);
      var diagnostics = viewDiagnostics(td, viewScope, objectsByBasket, validator, logging);
      diagnostics.put("objectCounts", ObjectCountVerification.verify(td, target, validator,
          objectsByBasket.values().stream().flatMap(List::stream), plannedCounts, plannedShapes, logging::addSyntheticError));
      // ilivalidator skips its EXISTENCE trace for an object with no attributes.
      // An omitted optional value is legal and needs no REQUIRED IN match.
      boolean executed = completed && !logging.unsupportedCheck
          && (logging.targetStarted || target instanceof ExistenceConstraint);
      return new ValidationOutcome(logging.messages, logging.errors, logging.warnings, diagnostics, executed);
    } finally {
      validator.close();
    }
  }

  private boolean validateTransfer(
      TransferDescription td, Constraint target, Path xtfFile, Validator validator, CollectingIoxLogging logging) {
    IoxReader reader = null;
    try {
      // The current default XTF 2.3 reader ignores the normative EXTREF attribute.
      // Its compatibility reader preserves cross-basket association references.
      reader = td.getLastModel().isIli23()
          ? new Xtf23Reader0(xtfFile.toFile())
          : Xtf24Reader.createReader(xtfFile.toFile());
      if (reader instanceof IoxIliReader iliReader) {
        iliReader.setModel(td);
      }
      IoxEvent event;
      while ((event = reader.read()) != null) {
        validator.validate(event);
        if (event instanceof EndTransferEvent) {
          return true;
        }
      }
      logging.addSyntheticError("Constraint test validation failed: no end of transfer.");
    } catch (IoxException e) {
      logging.addSyntheticError("Constraint test validation failed: " + e.getMessage());
    } catch (NullPointerException e) {
      // iox-ili 1.24.4 compares some complex EXISTENCE values through a null scalar accessor.
      StackTraceElement[] trace = e.getStackTrace();
      if (!(target instanceof ExistenceConstraint) || trace.length == 0
          || !trace[0].getClassName().equals(Validator.class.getName())
          || !trace[0].getMethodName().equals("validateExistenceConstraint")) {
        throw e;
      }
      logging.addSyntheticError("Constraint test validation failed: " + e.getMessage());
    } finally {
      if (reader != null) {
        try {
          reader.close();
        } catch (IoxException e) {
          logging.addSyntheticError("Constraint test reader failed to close: " + e.getMessage());
        }
      }
    }
    return false;
  }

  private Map<String, Object> viewDiagnostics(
      TransferDescription td, @Nullable ViewProofScope viewScope,
      Map<BasketKey, List<Iom_jObject>> objectsByBasket, Validator validator, CollectingIoxLogging logging) {
    Map<String,Object> viewDiagnostics = new LinkedHashMap<>();
    if (viewScope != null) {
      viewDiagnostics.putAll(viewScope.diagnostics());
      int baseCount=0, selected=0, skipped=0;
      for (var basket : objectsByBasket.values()) {
        for (var object : basket) {
          var type = td.getElement(object.getobjecttag());
          if (!(type instanceof Table table) || !(table == viewScope.base() || table.isExtending(viewScope.base()))) continue;
          baseCount++;
          boolean included=true;
          for (var iterator=viewScope.view().iterator();iterator.hasNext();) {
            var element=iterator.next();
            if (!(element instanceof ch.interlis.ili2c.metamodel.ExpressionSelection selection)) continue;
            var value=validator.evaluateExpression(null,null,viewScope.view().getScopedName(),object,selection.getCondition(),null);
            if (value.isNotYetImplemented()) {
              logging.addSyntheticError("VIEW_SCOPE_VERIFICATION_FAILED: View filter is not implemented by validator.");
              return viewDiagnostics;
            }
            if (value.skipEvaluation()) { skipped++; continue; }
            if (!value.isTrue()) { included=false; break; }
          }
          if (included) selected++;
        }
      }
      viewDiagnostics.put("baseSubjectCount",baseCount);
      viewDiagnostics.put("subjectCount",selected);
      viewDiagnostics.put("excludedSubjectCount",baseCount-selected);
      viewDiagnostics.put("skippedFilterCount",skipped);
    }
    return viewDiagnostics;
  }

  private Iom_jObject materializeObject(
      Table table, @Nullable String oid, Map<?, ?> values, BasketKey basket, int objectIndex,
      Map<String, PreparedObject> objectsByOid, FixtureBudget budget) {
    Iom_jObject object = new Iom_jObject(table.getScopedName(), oid);
    Iterator<Extendable> attributes = table.getAttributes();
    while (attributes.hasNext()) {
      if (attributes.next() instanceof AttributeDef attribute && !values.containsKey(attribute.getName())) {
        Type declared = attribute.getDomainOrDerivedDomain();
        int minimum = requiredMultiplicity(declared);
        for (int occurrence = 0; occurrence < minimum; occurrence++) {
          applyDefaultMandatoryValue(object, table, basket, attribute, Type.findReal(declared),
              objectIndex, occurrence, objectsByOid, budget);
        }
      }
    }
    applyValues(object, table, basket, values, objectIndex, objectsByOid, budget);
    return object;
  }

  private void applyDefaultMandatoryValue(
      Iom_jObject object,
      Table table,
      BasketKey basket,
      AttributeDef attribute,
      Type type,
      int objectIndex,
      int occurrence,
      Map<String, PreparedObject> objectsByOid, FixtureBudget budget) {
    String name = attribute.getName();
    if (type instanceof CompositionType composition) {
      applyCompositionValue(object, name, composition, basket, Map.of(), objectIndex, objectsByOid, budget);
      return;
    }
    if (type instanceof TextType textType) {
      String value = attribute.isDomainIli1Date() ? "20000101" : "txt_" + objectIndex;
      if (textType.getMaxLength() > 0 && value.length() > textType.getMaxLength()) {
        value = value.substring(0, textType.getMaxLength());
      }
      object.addattrvalue(name, value);
      return;
    }
    if (type instanceof NumericType numericType) {
      object.addattrvalue(name, numericType.getMinimum() != null ? numericType.getMinimum().toString() : "1");
      return;
    }
    if (type.isBoolean()) {
      object.addattrvalue(name, Iom_jObject.TRUE);
      return;
    }
    if (type instanceof EnumerationType enumerationType) {
      if (enumerationType.getValues().isEmpty()) {
        throw unsupportedMandatory(table, attribute, type);
      }
      object.addattrvalue(name, enumerationType.getValues().getFirst());
      return;
    }
    if (type instanceof EnumTreeValueType enumTreeValueType) {
      if (enumTreeValueType.getValues().isEmpty()) {
        throw unsupportedMandatory(table, attribute, type);
      }
      object.addattrvalue(name, enumTreeValueType.getValues().getFirst());
      return;
    }
    if (type instanceof AbstractCoordType coordType) {
      IomObject coord = object.addattrobj(name, Iom_jObject.COORD);
      NumericalType[] dimensions = coordType.getDimensions();
      if (dimensions.length < 1 || dimensions.length > 3) {
        throw unsupportedMandatory(table, attribute, type);
      }
      for (int index = 0; index < dimensions.length; index++) {
        if (!(dimensions[index] instanceof NumericType numeric) || numeric.getMinimum() == null) {
          throw unsupportedMandatory(table, attribute, type);
        }
        String value = numeric.getMinimum().toString();
        coord.setattrvalue(coordinateAxis(index), value);
      }
      return;
    }
    if (type instanceof ReferenceType referenceType) {
      AbstractClassDef<?> referred = referenceType.getReferred();
      if (!(referred instanceof Table targetTable)) {
        throw unsupportedMandatory(table, attribute, type);
      }
      var targets = objectsByOid.values().stream().filter(candidate -> candidate.table() == targetTable).toList();
      if (targets.isEmpty()) {
        throw new IllegalArgumentException("Explicit constraint case cannot auto-fill mandatory reference '"
            + table.getScopedName(null) + "." + name + "' because no target object was supplied.");
      }
      PreparedObject target = targets.get((objectIndex - 1 + occurrence) % targets.size());
      addReference(object, name, basket, target);
      return;
    }
    throw unsupportedMandatory(table, attribute, type);
  }

  private IllegalArgumentException unsupportedMandatory(Table table, AttributeDef attribute, Type type) {
    return new IllegalArgumentException("Explicit constraint case cannot auto-fill mandatory attribute '"
        + table.getScopedName(null) + "." + attribute.getName() + "' of type " + type.getClass().getSimpleName()
        + "; supply a simpler fixture or validate handcrafted XTF with validateXtf.");
  }

  private void applyValues(
      Iom_jObject object,
      Table table,
      BasketKey basket,
      Map<?, ?> values,
      int objectIndex,
      Map<String, PreparedObject> objectsByOid, FixtureBudget budget) {
    for (var entry : values.entrySet()) {
      if (!(entry.getKey() instanceof String key) || key.isBlank() || entry.getValue() == null) continue;
      String name = key.trim();
      AttributeDef attribute = findAttribute(table, name);
      Type type = attribute != null ? Type.findReal(attribute.getDomainOrDerivedDomain()) : null;
      if (entry.getValue() instanceof TypedValueFixtureFactory.GeometryValue geometry) {
        applyGeometryValue(object, name, geometry);
      } else if (type instanceof CompositionType composition) {
        applyCompositionValue(object, name, composition, basket, entry.getValue(), objectIndex, objectsByOid, budget);
      } else if (type instanceof ReferenceType) {
        applyReferenceValue(object, basket, name, entry.getValue(), objectsByOid);
      } else if (entry.getValue() instanceof List<?> list) {
        for (Object value : list) {
          if (value != null) {
            object.addattrvalue(name, scalarValue(value));
          }
        }
      } else {
        object.addattrvalue(name, scalarValue(entry.getValue()));
      }
    }
  }

  private void applyCompositionValue(
      Iom_jObject owner,
      String attributeName,
      CompositionType composition,
      BasketKey basket,
      Object raw,
      int objectIndex,
      Map<String, PreparedObject> objectsByOid, FixtureBudget budget) {
    List<?> occurrences = raw instanceof List<?> list ? list : List.of(raw);
    Table component = composition.getComponentType();
    if (!occurrences.isEmpty() && component.isAbstract()) {
      throw new ConstraintFixtureException("STRUCTURE_PATH_UNSUPPORTED",
          "No concrete structure type was specified for " + component.getScopedName(null) + ".");
    }
    for (Object occurrence : occurrences) {
      if (!(occurrence instanceof Map<?, ?> rawValues)) {
        throw new IllegalArgumentException(
            "STRUCTURE assignment for '" + attributeName + "' requires a map or list of maps.");
      }
      if (rawValues.keySet().stream().anyMatch(key -> !(key instanceof String name) || name.isBlank())) {
        throw new IllegalArgumentException(
            "STRUCTURE assignment for '" + attributeName + "' requires non-empty string attribute names.");
      }
      budget.enter();
      try {
        owner.addattrobj(attributeName, materializeObject(component, null, rawValues, basket,
            objectIndex, objectsByOid, budget));
      } finally {
        budget.exit();
      }
    }
  }

  private void applyReferenceValue(
      Iom_jObject owner,
      BasketKey basket,
      String attributeName,
      Object raw,
      Map<String, PreparedObject> objectsByOid) {
    if (raw instanceof List<?>) {
      throw new IllegalArgumentException(
          "REFERENCE assignment for '" + attributeName + "' requires one target OID.");
    }
    String oid = raw instanceof TypedValueFixtureFactory.ReferenceValue reference
        ? reference.targetOid() : String.valueOf(raw).trim();
    PreparedObject target = objectsByOid.get(oid);
    if (target == null) {
      throw new IllegalArgumentException(
          "REFERENCE assignment for '" + attributeName + "' points to unknown OID " + raw + ".");
    }
    addReference(owner, attributeName, basket, target);
  }

  private void applyGeometryValue(
      IomObject owner,
      String attributeName,
      TypedValueFixtureFactory.GeometryValue geometry) {
    switch (geometry) {
      case TypedValueFixtureFactory.CoordinateValue coordinate ->
          addCoordinate(owner, attributeName, coordinate);
      case TypedValueFixtureFactory.PolylineValue polyline -> addPolyline(owner, attributeName, polyline);
      case TypedValueFixtureFactory.SurfaceValue surface -> {
        IomObject target = owner.addattrobj(attributeName, Iom_jObject.MULTISURFACE);
        addSurface(target, surface);
      }
      case TypedValueFixtureFactory.MultiCoordinateValue multi -> {
        IomObject target = owner.addattrobj(attributeName, Iom_jObject.MULTICOORD);
        for (TypedValueFixtureFactory.CoordinateValue coordinate : multi.coordinates()) {
          addCoordinate(target, Iom_jObject.MULTICOORD_COORD, coordinate);
        }
      }
      case TypedValueFixtureFactory.MultiPolylineValue multi -> {
        IomObject target = owner.addattrobj(attributeName, Iom_jObject.MULTIPOLYLINE);
        for (TypedValueFixtureFactory.PolylineValue polyline : multi.polylines()) {
          addPolyline(target, Iom_jObject.MULTIPOLYLINE_POLYLINE, polyline);
        }
      }
      case TypedValueFixtureFactory.MultiSurfaceValue multi -> {
        IomObject target = owner.addattrobj(attributeName, Iom_jObject.MULTISURFACE);
        for (TypedValueFixtureFactory.SurfaceValue surface : multi.surfaces()) {
          addSurface(target, surface);
        }
      }
    }
  }

  private void addCoordinate(
      IomObject owner,
      String attributeName,
      TypedValueFixtureFactory.CoordinateValue coordinate) {
    IomObject target = owner.addattrobj(attributeName, Iom_jObject.COORD);
    for (int index = 0; index < coordinate.ordinates().size(); index++) {
      target.setattrvalue(coordinateAxis(index), coordinate.ordinates().get(index).stripTrailingZeros().toPlainString());
    }
  }

  private String coordinateAxis(int index) {
    return switch (index) {
      case 0 -> Iom_jObject.COORD_C1;
      case 1 -> Iom_jObject.COORD_C2;
      case 2 -> Iom_jObject.COORD_C3;
      default -> throw new IllegalArgumentException("Coordinate fixture has too many axes.");
    };
  }

  private void addPolyline(
      IomObject owner,
      String attributeName,
      TypedValueFixtureFactory.PolylineValue value) {
    IomObject polyline = owner.addattrobj(attributeName, Iom_jObject.POLYLINE);
    IomObject sequence = polyline.addattrobj(
        Iom_jObject.POLYLINE_SEQUENCE, Iom_jObject.SEGMENTS);
    for (TypedValueFixtureFactory.CoordinateValue coordinate : value.points()) {
      addCoordinate(sequence, Iom_jObject.SEGMENTS_SEGMENT, coordinate);
    }
  }

  private void addSurface(
      IomObject multiSurface,
      TypedValueFixtureFactory.SurfaceValue value) {
    IomObject surface = multiSurface.addattrobj(
        Iom_jObject.MULTISURFACE_SURFACE, Iom_jObject.SURFACE);
    for (TypedValueFixtureFactory.PolylineValue boundaryValue : value.boundaries()) {
      IomObject boundary = surface.addattrobj(
          Iom_jObject.SURFACE_BOUNDARY, Iom_jObject.BOUNDARY);
      addPolyline(boundary, Iom_jObject.BOUNDARY_POLYLINE, boundaryValue);
    }
  }

  private @Nullable AttributeDef findAttribute(Table table, String name) {
    Iterator<Extendable> attributes = table.getAttributes();
    while (attributes.hasNext()) {
      Extendable element = attributes.next();
      if (element instanceof AttributeDef attribute && name.equals(attribute.getName())) {
        return attribute;
      }
    }
    return null;
  }

  private String scalarValue(Object value) {
    if (value instanceof String || value instanceof Number || value instanceof Boolean) {
      return String.valueOf(value);
    }
    throw new IllegalArgumentException(
        "Explicit constraint test scalar values must be string, number or boolean; STRUCTURE values use nested maps.");
  }

  private void addReference(Iom_jObject owner, String name, BasketKey basket, PreparedObject target) {
    IomObject ref = owner.addattrobj(name, Iom_jObject.REF);
    ref.setobjectrefoid(target.oid());
    if (basket.topic() != topicOf(target.table()) || !basket.basketId().equals(target.basketId())) {
      ref.setobjectrefbid(target.basketId());
    }
  }

  private int requiredMultiplicity(Type type) {
    int minimum = type.isMandatoryConsideringAliases() ? 1 : 0;
    Cardinality cardinality = type.getCardinality();
    if (cardinality != null && cardinality.getMinimum() > minimum) {
      minimum = cardinality.getMinimum() > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) cardinality.getMinimum();
    }
    if (minimum > 5) {
      throw new ConstraintFixtureException("STRUCTURE_FIXTURE_BUDGET_EXCEEDED", "Mandatory multiplicity minimum " + minimum + " is too large for minimal constraint fixtures.");
    }
    return minimum;
  }

  private Topic topicOf(AbstractClassDef<?> type) {
    Topic topic = (Topic) type.getContainer(Topic.class);
    if (topic == null) {
      throw new IllegalArgumentException("Fixture type is not contained in a topic: " + type.getScopedName());
    }
    return topic;
  }

  private @Nullable String optionalId(@Nullable String basketId) {
    return basketId == null || basketId.isBlank() ? null : basketId.trim();
  }

  private int countSubjects(
      TransferDescription td, Constraint target, PreparedCase prepared,
      Map<BasketKey, List<Iom_jObject>> objectsByBasket) {
    Element context = target.getContainer();
    if (context instanceof AssociationDef association) {
      return (int) prepared.links().stream()
          .filter(link -> link.association() == association || link.association().isExtending(association))
          .count();
    }
    if (!(context instanceof Table table)) return 0;
    return objectsByBasket.values().stream().flatMap(List::stream)
        .mapToInt(object -> countInstances(td, table, object)).sum();
  }

  private int countInstances(TransferDescription td, Table context, IomObject object) {
    Element actual = td.getElement(object.getobjecttag());
    int count = actual instanceof Table table && (table == context || table.isExtending(context)) ? 1 : 0;
    for (int a = 0; a < object.getattrcount(); a++) {
      String name = object.getattrname(a);
      for (int i = 0; i < object.getattrvaluecount(name); i++) {
        IomObject child = object.getattrobj(name, i);
        if (child != null) count += countInstances(td, context, child);
      }
    }
    return count;
  }

  private static final class FixtureBudget {
    private int structures;
    private int depth;

    void enter() {
      if (++structures > 64 || ++depth > 8) {
        throw new ConstraintFixtureException("STRUCTURE_FIXTURE_BUDGET_EXCEEDED",
            "Structure fixture exceeds 64 instances or 8 composition levels.");
      }
    }

    void exit() { depth--; }
  }

  private TestCase requireCase(@Nullable TestCase testCase, int index, Constraint target) {
    if (testCase == null) {
      throw new IllegalArgumentException("Constraint test case at index " + index + " must not be null.");
    }
    if (testCase.name == null || testCase.name.isBlank()) {
      throw new IllegalArgumentException("Constraint test case at index " + index + " requires a name.");
    }
    if (testCase.expectedConstraintValid == null) {
      throw new IllegalArgumentException("Case '" + testCase.name + "' requires expectedConstraintValid.");
    }
    if (testCase.objects == null || testCase.objects.isEmpty()
        && !(target instanceof SetConstraint && target.getContainer() instanceof Projection)) {
      throw new IllegalArgumentException("Case '" + testCase.name + "' requires at least one object.");
    }
    return testCase;
  }

  private Constraint findConstraint(TransferDescription td, List<Constraint> constraints, String requestedName) {
    Set<Model> sourceModels = Set.of(td.getModelsFromLastFile());
    List<Constraint> matches = constraints.stream()
        .filter(constraint -> sourceModels.contains(constraint.getContainer(Model.class)))
        .filter(constraint -> requestedName.trim().equals(constraint.getName())
            || requestedName.trim().equals(constraint.getScopedName(null)))
        .toList();
    if (matches.size() > 1) {
      throw new IllegalArgumentException("Constraint name is ambiguous: " + requestedName + "; use a fully qualified name.");
    }
    return matches.isEmpty() ? null : matches.getFirst();
  }

  private List<Constraint> collectConstraints(TransferDescription td) {
    List<Constraint> result = new ArrayList<>();
    collectConstraints(td, result);
    return result;
  }

  private void collectConstraints(Container<?> container, List<Constraint> sink) {
    Iterator<?> iterator = container.iterator();
    while (iterator.hasNext()) {
      Object child = iterator.next();
      if (child instanceof Constraint constraint) {
        sink.add(constraint);
      } else if (child instanceof Container<?> nested) {
        collectConstraints(nested, sink);
      }
    }
  }

  private String constraintQName(Constraint constraint) {
    Element container = constraint.getContainer();
    return (container != null ? container.getScopedName(null) + "." : "") + constraint.getName();
  }

  private record PreparedObject(
      Table table,
      String oid,
      @Nullable String basketId,
      Map<String, Object> values) {
  }

  private record PreparedLink(
      AssociationDef association,
      @Nullable RoleDef embeddedEnd,
      @Nullable String basketId,
      @Nullable String oid,
      Map<String, String> roles) {
  }

  private record BasketKey(Topic topic, String basketId) {
    private BasketKey {
      Objects.requireNonNull(topic, "topic");
      if (basketId == null || basketId.isBlank()) {
        throw new IllegalArgumentException("basketId is required.");
      }
    }
  }

  private record PreparedCase(
      List<PreparedLink> links,
      Map<String, PreparedObject> objectsByOid,
      Map<Topic, String> defaults) {
  }

  private record ValidationOutcome(List<Map<String, Object>> messages, int errorCount, int warningCount,
      Map<String,Object> viewDiagnostics, boolean targetExecuted) {
  }

  private static final class CollectingIoxLogging implements IoxLogging {
    private final List<Map<String, Object>> messages = new ArrayList<>();
    private final String xtfPath;
    private final String validationTraceSuffix;
    private boolean targetStarted;
    private boolean unsupportedCheck;
    private int errors;
    private int warnings;

    private CollectingIoxLogging(Path xtfFile, String targetQName) {
      xtfPath = xtfFile.toString();
      validationTraceSuffix = " constraint " + targetQName + "...";
    }

    @Override
    public void addEvent(IoxLogEvent event) {
      String full = event.getEventMsg();
      if (full != null && full.startsWith("validate ") && full.endsWith(validationTraceSuffix)) {
        targetStarted = true;
      }
      String severity;
      if (event.getEventKind() == IoxLogEvent.ERROR) {
        severity = "ERROR";
        errors++;
      } else if (event.getEventKind() == IoxLogEvent.WARNING) {
        severity = "WARNING";
        warnings++;
      } else {
        return;
      }
      var message = new LinkedHashMap<String, Object>();
      message.put("severity", severity);
      String raw = event.getRawEventMsg();
      String text = raw != null && !raw.isBlank() ? raw : Objects.toString(full, "");
      // Only the target constraint is enabled in this validator run.
      if (event.getEventKind() == IoxLogEvent.WARNING && text.endsWith("is not yet implemented.")) {
        unsupportedCheck = true;
      }
      message.put("message", text.replace(xtfPath, "<generated-constraint-fixture>"));
      String source = event.getDataSource();
      if (source != null && !source.isBlank()) {
        message.put("file", source.equals(xtfPath) ? "<generated-constraint-fixture>" : source);
      }
      Integer sourceLine = event.getSourceLineNr();
      if (sourceLine != null && sourceLine > 0) {
        message.put("line", sourceLine);
      }
      messages.add(message);
    }

    private void addSyntheticError(String text) {
      errors++;
      messages.add(Map.of("severity", "ERROR", "message", text.replace(xtfPath, "<generated-constraint-fixture>")));
    }
  }
}
