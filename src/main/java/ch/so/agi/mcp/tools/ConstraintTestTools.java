package ch.so.agi.mcp.tools;

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
import ch.interlis.ili2c.metamodel.Extendable;
import ch.interlis.ili2c.metamodel.Model;
import ch.interlis.ili2c.metamodel.NumericType;
import ch.interlis.ili2c.metamodel.NumericalType;
import ch.interlis.ili2c.metamodel.ReferenceType;
import ch.interlis.ili2c.metamodel.RoleDef;
import ch.interlis.ili2c.metamodel.Table;
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
import ch.so.agi.mcp.service.IliCompilerService;
import ch.so.agi.mcp.constraint.CompiledConstraintContext;
import ch.so.agi.mcp.util.McpInputLimits;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

  public static class TestCase {
    public String name;
    public Boolean expectedConstraintValid;
    public List<TestObject> objects;
    public @Nullable List<TestLink> links;
  }

  public static class TestObject {
    public String classFqn;
    public @Nullable String oid;
    public @Nullable String basketId;
    public @Nullable Map<String, Object> values;
    public @Nullable Map<String, String> references;
  }

  public static class TestLink {
    public String associationFqn;
    public @Nullable String basketId;
    public Map<String, String> roles;
  }

  @McpTool(
      name = "testIliConstraint",
      description = "Prueft einen bestehenden INTERLIS-Constraint mit explizit vom Agenten definierten Testfaellen. Erzeugt fuer jeden Fall ein minimales XTF, unterstuetzt mehrere Objekte und optional mehrere Baskets pro Topic ueber object.basketId bzw. bei heavyweight Associations link.basketId, deaktiviert andere Constraints, validiert mit iox-ili/ilivalidator und vergleicht das beobachtete Ergebnis mit expectedConstraintValid. Erzeugt selbst noch keine Witnesses oder Counterexamples.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true)
  )
  public Map<String, Object> testIliConstraint(
      @McpToolParam(description = "Vollstaendiger INTERLIS-2 Modelltext", required = true) String modelText,
      @McpToolParam(description = "Constraint-Name oder vollqualifizierter Constraint-Name", required = true) String constraint,
      @McpToolParam(description = "Explizite Testfaelle. Jeder Fall enthaelt name, expectedConstraintValid, objects und optional links. Object basketId ist optional; ohne Angabe wird wie bisher ein impliziter Basket pro Topic verwendet. Object values sind skalare Werte, Listen skalarer Werte oder verschachtelte Maps/Listen fuer STRUCTURE-Attribute; references und link roles enthalten Ziel-OIDs. Heavyweight Association links koennen optional basketId setzen.", required = true) List<TestCase> cases) {
    McpInputLimits.requireConstraintCases(cases);

    IliCompilerService.CompilationResult compilation =
        compilerService.compile(modelText, null, "ili2c_constraint_test_");
    if (!compilation.valid() || compilation.transferDescription() == null) {
      return Map.of(
          "tested", false,
          "compilerValid", false,
          "messages", compilation.messages(),
          "caseCount", cases.size(),
          "passedCount", 0,
          "allPassed", false,
          "automaticCasesGenerated", false);
    }

    TransferDescription td = compilation.transferDescription();
    Constraint target = findConstraint(td, constraint);
    if (target == null) {
      throw new IllegalArgumentException("Constraint not found: " + constraint);
    }

    return testResolvedConstraint(td, target, cases);
  }

  Map<String, Object> testCompiledConstraint(
      CompiledConstraintContext context,
      List<TestCase> cases) {
    Objects.requireNonNull(context, "context");
    McpInputLimits.requireConstraintCases(cases);
    return testResolvedConstraint(context.transferDescription(), context.constraint(), cases);
  }

  private Map<String, Object> testResolvedConstraint(
      TransferDescription td,
      Constraint target,
      List<TestCase> cases) {
    String targetQName = constraintQName(target);
    String context = target.getContainer() != null ? target.getContainer().getScopedName(null) : "";
    List<Constraint> allConstraints = collectConstraints(td);
    List<Map<String, Object>> results = new ArrayList<>();
    int passedCount = 0;

    for (int i = 0; i < cases.size(); i++) {
      TestCase testCase = requireCase(cases.get(i), i);
      Map<String, Object> result = runCase(td, target, targetQName, allConstraints, testCase, i + 1);
      results.add(result);
      if (Boolean.TRUE.equals(result.get("passed"))) {
        passedCount++;
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
      List<Constraint> allConstraints,
      TestCase testCase,
      int caseIndex) {
    PreparedCase prepared;
    try {
      prepared = prepareCase(td, target, testCase, caseIndex);
    } catch (FixturePreparationException ex) {
      Map<String, Object> fixtureError = Map.of(
          "severity", "ERROR",
          "code", ex.reasonCode(),
          "message", ex.getMessage());
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("name", testCase.name.trim());
      result.put("expectedConstraintValid", testCase.expectedConstraintValid);
      result.put("actualConstraintValid", false);
      result.put("passed", false);
      result.put("constraintExercised", false);
      result.put("subjectCount", 0);
      result.put("basketCount", 0);
      result.put("baskets", List.of());
      result.put("fixtureValid", false);
      result.put("validatorValid", false);
      result.put("targetViolationCount", 0);
      result.put("errorCount", 1);
      result.put("warningCount", 0);
      result.put("messages", List.of(fixtureError));
      result.put("fixtureErrors", List.of(fixtureError));
      result.put("fixturePreparationReasonCode", ex.reasonCode());
      result.put("reason", ex.getMessage());
      return result;
    }
    Path xtfFile = null;
    try {
      xtfFile = Files.createTempFile("interlis-mcp-constraint-case-", ".xtf");
      writeCaseXtf(td, prepared, xtfFile);
      String xtfText = Files.readString(xtfFile, StandardCharsets.UTF_8);
      ValidationOutcome validation = validateCase(td, targetQName, allConstraints, xtfFile);

      int targetViolationCount = (int) validation.messages().stream()
          .filter(message -> String.valueOf(message.getOrDefault("message", "")).contains(TARGET_VIOLATION_MARKER))
          .count();
      List<Map<String, Object>> fixtureErrors = validation.messages().stream()
          .filter(message -> "ERROR".equals(message.get("severity")))
          .filter(message -> !String.valueOf(message.getOrDefault("message", "")).contains(TARGET_VIOLATION_MARKER))
          .toList();

      boolean actualConstraintValid = targetViolationCount == 0;
      boolean fixtureValid = fixtureErrors.isEmpty();
      boolean exercised = prepared.subjectCount() > 0;
      boolean expected = testCase.expectedConstraintValid;
      boolean passed = exercised && fixtureValid && actualConstraintValid == expected;

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("name", testCase.name.trim());
      result.put("expectedConstraintValid", expected);
      result.put("actualConstraintValid", actualConstraintValid);
      result.put("passed", passed);
      result.put("constraintExercised", exercised);
      result.put("subjectCount", prepared.subjectCount());
      result.put("basketCount", prepared.baskets().size());
      result.put("baskets", basketSummaries(prepared));
      result.put("fixtureValid", fixtureValid);
      result.put("validatorValid", validation.errorCount() == 0);
      result.put("targetViolationCount", targetViolationCount);
      result.put("errorCount", validation.errorCount());
      result.put("warningCount", validation.warningCount());
      result.put("messages", validation.messages());
      result.put("fixtureErrors", fixtureErrors);
      result.put("xtfText", xtfText);
      if (!exercised) {
        result.put("reason", "The test case contains no instance of the constraint context.");
      } else if (!fixtureValid) {
        result.put("reason", "The generated fixture has non-target validation errors.");
      } else if (actualConstraintValid != expected) {
        result.put("reason", "Observed target constraint result differs from expectedConstraintValid.");
      }
      return result;
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to create or validate constraint test XTF.", e);
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

  private PreparedCase prepareCase(
      TransferDescription td,
      Constraint target,
      TestCase testCase,
      int caseIndex) {
    List<ObjectDraft> objectDrafts = new ArrayList<>();
    Map<String, ObjectDraft> draftsByOid = new LinkedHashMap<>();

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
      if (draftsByOid.containsKey(oid)) {
        throw new IllegalArgumentException("Case '" + testCase.name + "': duplicate OID " + oid);
      }
      ObjectDraft draft = new ObjectDraft(
          table,
          oid,
          normalizeBasketId(spec.basketId),
          spec.values != null ? spec.values : Map.of(),
          spec.references != null ? spec.references : Map.of());
      objectDrafts.add(draft);
      draftsByOid.put(oid, draft);
    }

    List<LinkDraft> linkDrafts = new ArrayList<>();
    if (testCase.links != null) {
      for (TestLink spec : testCase.links) {
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
        for (Map.Entry<String, String> role : spec.roles.entrySet()) {
          if (role.getKey() == null || role.getKey().isBlank() || role.getValue() == null || role.getValue().isBlank()) {
            throw new IllegalArgumentException("Case '" + testCase.name + "': link role names and OIDs must be non-empty.");
          }
          if (!draftsByOid.containsKey(role.getValue().trim())) {
            throw new IllegalArgumentException("Case '" + testCase.name + "': link role '" + role.getKey() + "' references unknown OID " + role.getValue());
          }
        }
        String requestedBasketId = normalizeBasketId(spec.basketId);
        if (association.isLightweight() && requestedBasketId != null) {
          throw new IllegalArgumentException("Case '" + testCase.name
              + "': basketId is not applicable to lightweight association " + association.getScopedName(null)
              + "; its embedded reference belongs to the owner object's basket.");
        }
        linkDrafts.add(new LinkDraft(association, requestedBasketId, Map.copyOf(spec.roles)));
      }
    }

    completeMandatoryAssociations(
        testCase, caseIndex, objectDrafts, draftsByOid, linkDrafts);

    validateReferenceTargets(testCase, objectDrafts, draftsByOid);
    validateExplicitBasketIds(testCase, objectDrafts, linkDrafts);
    Map<Topic, String> defaultBasketIds = allocateDefaultBasketIds(objectDrafts, linkDrafts);

    List<PreparedObject> objects = new ArrayList<>();
    Map<String, PreparedObject> objectsByOid = new LinkedHashMap<>();
    Map<Table, List<PreparedObject>> objectsByClass = new LinkedHashMap<>();
    for (ObjectDraft draft : objectDrafts) {
      Topic topic = topicOf(draft.table());
      String basketId = resolveBasketId(topic, draft.requestedBasketId(), defaultBasketIds);
      PreparedObject prepared = new PreparedObject(
          draft.table(),
          draft.oid(),
          basketId,
          draft.values(),
          draft.references());
      objects.add(prepared);
      objectsByOid.put(prepared.oid(), prepared);
      objectsByClass.computeIfAbsent(prepared.table(), key -> new ArrayList<>()).add(prepared);
    }

    List<PreparedLink> links = new ArrayList<>();
    for (LinkDraft draft : linkDrafts) {
      String basketId = null;
      if (!draft.association().isLightweight()) {
        basketId = resolveBasketId(
            topicOf(draft.association()),
            draft.requestedBasketId(),
            defaultBasketIds);
      }
      links.add(new PreparedLink(draft.association(), basketId, draft.roles()));
    }

    List<BasketKey> baskets = collectBaskets(objects, links);
    int subjectCount = countSubjects(target, objects, links);
    return new PreparedCase(objects, links, objectsByOid, objectsByClass, baskets, subjectCount);
  }

  private void completeMandatoryAssociations(
      TestCase testCase,
      int caseIndex,
      List<ObjectDraft> objects,
      Map<String, ObjectDraft> objectsByOid,
      List<LinkDraft> links) {
    int generatedElements = 0;
    int generatedObjectIndex = 1;
    Set<String> visitedRolePaths = new LinkedHashSet<>();

    for (int objectIndex = 0; objectIndex < objects.size(); objectIndex++) {
      ObjectDraft source = objects.get(objectIndex);
      List<RoleDef> navigationRoles = new ArrayList<>();
      Iterator<RoleDef> iterator = source.table().getOpposideRoles();
      while (iterator.hasNext()) {
        navigationRoles.add(iterator.next());
      }
      navigationRoles.sort(Comparator.comparing(role -> role.getScopedName(null)));

      for (RoleDef targetRole : navigationRoles) {
        String visitedRolePath = source.oid() + "\u0000" + targetRole.getScopedName(null);
        if (!visitedRolePaths.add(visitedRolePath)) {
          continue;
        }
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
        while (countLinksForSource(links, association, sourceRole, source.oid()) < minimum) {
          ObjectDraft target = reusableTarget(objects, links, targetRole, source);
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
                ? source.requestedBasketId()
                : null;
            target = new ObjectDraft(targetTable, oid, targetBasketId, Map.of(), Map.of());
            objects.add(target);
            objectsByOid.put(oid, target);
            generatedElements++;
          }

          Map<String, String> roles = new LinkedHashMap<>();
          roles.put(sourceRole.getName(), source.oid());
          roles.put(targetRole.getName(), Objects.requireNonNull(target).oid());
          links.add(new LinkDraft(association, null, Map.copyOf(roles)));
          generatedElements++;
        }
      }
    }
  }

  private String generatedObjectOid(Table table, int caseIndex, int objectIndex) {
    String seed = table.getScopedName(null) + ":" + caseIndex + ":" + objectIndex;
    Domain oidDomain = table.getOid();
    if (usesUuidOid(oidDomain)) {
      return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }
    return "case" + caseIndex + "_mandatory_" + objectIndex;
  }

  private boolean usesUuidOid(@Nullable Domain oidDomain) {
    Set<Domain> visited = new LinkedHashSet<>();
    Domain current = oidDomain;
    while (current != null && visited.add(current)) {
      if (current.getScopedName(null).endsWith(".UUIDOID")) {
        return true;
      }
      current = current.getExtending();
    }
    return false;
  }

  private long countLinksForSource(
      List<LinkDraft> links,
      AssociationDef association,
      RoleDef sourceRole,
      String sourceOid) {
    return links.stream()
        .filter(link -> link.association() == association)
        .filter(link -> sourceOid.equals(link.roles().get(sourceRole.getName())))
        .count();
  }

  private @Nullable ObjectDraft reusableTarget(
      List<ObjectDraft> objects,
      List<LinkDraft> links,
      RoleDef targetRole,
      ObjectDraft source) {
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
            || Objects.equals(candidate.requestedBasketId(), source.requestedBasketId()))
        .filter(candidate -> links.stream().noneMatch(link ->
            link.association() == targetRole.getContainer()
                && source.oid().equals(link.roles().get(sourceRole.getName()))
                && candidate.oid().equals(link.roles().get(targetRole.getName()))))
        .filter(candidate -> maximumBackReferences == Cardinality.UNBOUND
            || links.stream()
                .filter(link -> link.association() == targetRole.getContainer())
                .filter(link -> candidate.oid().equals(link.roles().get(targetRole.getName())))
                .count() < maximumBackReferences)
        .sorted(Comparator.comparing(ObjectDraft::oid))
        .findFirst()
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

    List<Table> candidates = new ArrayList<>();
    collectConcreteExtensions(declared, candidates);
    candidates.sort(Comparator.comparing(table -> table.getScopedName(null)));
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

  private void collectConcreteExtensions(Table parent, List<Table> result) {
    for (Object extension : parent.getExtensions()) {
      if (!(extension instanceof Table child) || child == parent) {
        continue;
      }
      if (!child.isAbstract() && child.isIdentifiable() && !result.contains(child)) {
        result.add(child);
      }
      collectConcreteExtensions(child, result);
    }
  }

  private FixturePreparationException fixturePreparationFailure(
      String reasonCode,
      TestCase testCase,
      String reason) {
    return new FixturePreparationException(
        reasonCode,
        "Case '" + testCase.name + "': " + reason);
  }

  private void validateReferenceTargets(
      TestCase testCase,
      List<ObjectDraft> objects,
      Map<String, ObjectDraft> objectsByOid) {
    for (ObjectDraft object : objects) {
      for (Map.Entry<String, String> reference : object.references().entrySet()) {
        if (reference.getValue() == null
            || reference.getValue().isBlank()
            || !objectsByOid.containsKey(reference.getValue().trim())) {
          throw new IllegalArgumentException("Case '" + testCase.name + "': reference '"
              + reference.getKey() + "' points to unknown OID " + reference.getValue());
        }
      }
    }
  }

  private void validateExplicitBasketIds(
      TestCase testCase,
      List<ObjectDraft> objects,
      List<LinkDraft> links) {
    Map<String, Topic> topicsByBasketId = new LinkedHashMap<>();
    for (ObjectDraft object : objects) {
      registerExplicitBasketId(
          testCase.name,
          topicsByBasketId,
          object.requestedBasketId(),
          topicOf(object.table()));
    }
    for (LinkDraft link : links) {
      if (!link.association().isLightweight()) {
        registerExplicitBasketId(
            testCase.name,
            topicsByBasketId,
            link.requestedBasketId(),
            topicOf(link.association()));
      }
    }
  }

  private void registerExplicitBasketId(
      String caseName,
      Map<String, Topic> topicsByBasketId,
      @Nullable String basketId,
      Topic topic) {
    if (basketId == null) {
      return;
    }
    Topic previous = topicsByBasketId.putIfAbsent(basketId, topic);
    if (previous != null && previous != topic) {
      throw new IllegalArgumentException("Case '" + caseName + "': basketId '" + basketId
          + "' is used for more than one topic (" + previous.getScopedName(null)
          + " and " + topic.getScopedName(null) + "). Basket IDs must be unique across topics.");
    }
  }

  private Map<Topic, String> allocateDefaultBasketIds(
      List<ObjectDraft> objects,
      List<LinkDraft> links) {
    Set<String> explicitIds = new LinkedHashSet<>();
    Set<Topic> topicsNeedingDefault = new LinkedHashSet<>();
    for (ObjectDraft object : objects) {
      if (object.requestedBasketId() == null) {
        topicsNeedingDefault.add(topicOf(object.table()));
      } else {
        explicitIds.add(object.requestedBasketId());
      }
    }
    for (LinkDraft link : links) {
      if (link.association().isLightweight()) {
        continue;
      }
      if (link.requestedBasketId() == null) {
        topicsNeedingDefault.add(topicOf(link.association()));
      } else {
        explicitIds.add(link.requestedBasketId());
      }
    }

    List<Topic> sortedTopics = topicsNeedingDefault.stream()
        .sorted(Comparator.comparing(topic -> topic.getScopedName(null)))
        .toList();
    Map<Topic, String> result = new LinkedHashMap<>();
    int candidateIndex = 1;
    for (Topic topic : sortedTopics) {
      String candidate;
      do {
        candidate = "b" + candidateIndex++;
      } while (explicitIds.contains(candidate));
      result.put(topic, candidate);
      explicitIds.add(candidate);
    }
    return result;
  }

  private String resolveBasketId(
      Topic topic,
      @Nullable String requestedBasketId,
      Map<Topic, String> defaultBasketIds) {
    if (requestedBasketId != null) {
      return requestedBasketId;
    }
    String basketId = defaultBasketIds.get(topic);
    if (basketId == null) {
      throw new IllegalStateException("No implicit basket allocated for topic " + topic.getScopedName(null) + ".");
    }
    return basketId;
  }

  private List<BasketKey> collectBaskets(
      List<PreparedObject> objects,
      List<PreparedLink> links) {
    Set<BasketKey> result = new LinkedHashSet<>();
    for (PreparedObject object : objects) {
      result.add(new BasketKey(topicOf(object.table()), object.basketId()));
    }
    for (PreparedLink link : links) {
      if (!link.association().isLightweight()) {
        result.add(new BasketKey(topicOf(link.association()), Objects.requireNonNull(link.basketId())));
      }
    }
    return orderBaskets(result);
  }

  private List<BasketKey> orderBaskets(Iterable<BasketKey> baskets) {
    List<BasketKey> remaining = new ArrayList<>();
    baskets.forEach(remaining::add);
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

  private List<Map<String, Object>> basketSummaries(PreparedCase prepared) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (BasketKey basket : prepared.baskets()) {
      long objectCount = prepared.objects().stream()
          .filter(object -> topicOf(object.table()) == basket.topic())
          .filter(object -> object.basketId().equals(basket.basketId()))
          .count();
      long associationObjectCount = prepared.links().stream()
          .filter(link -> !link.association().isLightweight())
          .filter(link -> topicOf(link.association()) == basket.topic())
          .filter(link -> basket.basketId().equals(link.basketId()))
          .count();
      result.add(Map.of(
          "topic", basket.topic().getScopedName(null),
          "basketId", basket.basketId(),
          "objectCount", objectCount,
          "associationObjectCount", associationObjectCount));
    }
    return result;
  }

  private void writeCaseXtf(TransferDescription td, PreparedCase prepared, Path xtfFile) throws IOException {
    Map<BasketKey, List<Iom_jObject>> objectsByBasket = new LinkedHashMap<>();
    Map<String, Iom_jObject> iomObjectsByOid = new LinkedHashMap<>();
    int objectIndex = 1;
    for (PreparedObject preparedObject : prepared.objects()) {
      Table table = preparedObject.table();
      Iom_jObject object = new Iom_jObject(table.getScopedName(null), preparedObject.oid());
      Set<String> explicitNames = new LinkedHashSet<>(preparedObject.values().keySet());
      explicitNames.addAll(preparedObject.references().keySet());
      int currentObjectIndex = objectIndex++;
      fillMandatoryAttributes(
          object,
          table,
          preparedObject.basketId(),
          currentObjectIndex,
          explicitNames,
          prepared.objectsByClass());
      applyValues(
          object,
          table,
          preparedObject.basketId(),
          preparedObject.values(),
          currentObjectIndex,
          prepared.objectsByClass(),
          prepared.objectsByOid());
      applyReferences(object, preparedObject, prepared.objectsByOid());
      Topic topic = topicOf(table);
      BasketKey basket = new BasketKey(topic, preparedObject.basketId());
      objectsByBasket.computeIfAbsent(basket, key -> new ArrayList<>()).add(object);
      iomObjectsByOid.put(preparedObject.oid(), object);
    }

    for (PreparedLink link : prepared.links()) {
      if (link.association().isLightweight()) {
        applyEmbeddedLink(link, prepared, iomObjectsByOid);
        continue;
      }
      String associationBasketId = Objects.requireNonNull(link.basketId());
      Iom_jObject associationObject = new Iom_jObject(link.association().getScopedName(null), null);
      Topic associationTopic = topicOf(link.association());
      for (Map.Entry<String, String> role : link.roles().entrySet()) {
        PreparedObject target = prepared.objectsByOid().get(role.getValue().trim());
        IomObject ref = associationObject.addattrobj(role.getKey().trim(), Iom_jObject.REF);
        ref.setobjectrefoid(target.oid());
        setReferenceBasketIfNeeded(ref, associationTopic, associationBasketId, target);
      }
      BasketKey basket = new BasketKey(associationTopic, associationBasketId);
      objectsByBasket.computeIfAbsent(basket, key -> new ArrayList<>()).add(associationObject);
    }

    List<BasketKey> baskets = orderBaskets(objectsByBasket.keySet());
    try (OutputStream outputStream = Files.newOutputStream(xtfFile)) {
      XtfWriter writer = new XtfWriter(outputStream, td);
      try {
        StartTransferEvent start = new StartTransferEvent();
        start.setVersion(td.getLastModel().isIli23() ? "2.3" : "2.4");
        start.setSender("interlis-mcp");
        start.setComment("testIliConstraint");
        writer.write(start);
        for (BasketKey basket : baskets) {
          writer.write(new StartBasketEvent(basket.topic().getScopedName(null), basket.basketId()));
          for (Iom_jObject object : objectsByBasket.get(basket)) {
            writer.write(new ObjectEvent(object));
          }
          writer.write(new EndBasketEvent());
        }
        writer.write(new EndTransferEvent());
        writer.flush();
      } catch (IoxException e) {
        throw new IOException("Unable to write explicit constraint test XTF.", e);
      } finally {
        try {
          writer.close();
        } catch (Exception ignore) {
        }
      }
    } catch (IoxException e) {
      throw new IOException("Unable to create XTF writer for explicit constraint test.", e);
    }
  }

  private void applyEmbeddedLink(
      PreparedLink link,
      PreparedCase prepared,
      Map<String, Iom_jObject> iomObjectsByOid) {
    RoleDef embeddedEnd = link.association().getRoleWhereEmbedded();
    if (embeddedEnd == null) {
      throw new IllegalStateException("Lightweight association has no embedded role: "
          + link.association().getScopedName(null));
    }
    RoleDef referenceRole = embeddedEnd.getOppEnd();
    PreparedObject owner = linkedObject(link, embeddedEnd, prepared);
    PreparedObject target = linkedObject(link, referenceRole, prepared);
    Iom_jObject ownerObject = iomObjectsByOid.get(owner.oid());
    IomObject ref = ownerObject.addattrobj(referenceRole.getName(), Iom_jObject.REF);
    ref.setobjectrefoid(target.oid());
    setReferenceBasketIfNeeded(ref, topicOf(owner.table()), owner.basketId(), target);
  }

  private PreparedObject linkedObject(PreparedLink link, RoleDef role, PreparedCase prepared) {
    String oid = link.roles().get(role.getName());
    if (oid == null || oid.isBlank()) {
      throw new IllegalArgumentException("Association link for '"
          + link.association().getScopedName(null) + "' requires role '" + role.getName() + "'.");
    }
    PreparedObject object = prepared.objectsByOid().get(oid.trim());
    if (object == null) {
      throw new IllegalArgumentException("Association link role '" + role.getName()
          + "' references unknown OID " + oid + ".");
    }
    return object;
  }

  private ValidationOutcome validateCase(
      TransferDescription td,
      String targetQName,
      List<Constraint> allConstraints,
      Path xtfFile) {
    ValidationConfig config = new ValidationConfig();
    config.setConfigValue(ValidationConfig.PARAMETER, ValidationConfig.ALL_OBJECTS_ACCESSIBLE, ValidationConfig.TRUE);
    for (Constraint constraint : allConstraints) {
      String qName = constraintQName(constraint);
      if (targetQName.equals(qName)) {
        config.setConfigValue(qName, ValidationConfig.MSG, TARGET_VIOLATION_MARKER);
      } else {
        config.setConfigValue(qName, ValidationConfig.CHECK, ValidationConfig.OFF);
      }
    }

    CollectingIoxLogging logging = new CollectingIoxLogging();
    ch.interlis.iox_j.validator.Validator validator = new ch.interlis.iox_j.validator.Validator(
        td,
        config,
        logging,
        new LogEventFactory(),
        new PipelinePool(),
        new Settings());

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
          break;
        }
      }
    } catch (Exception e) {
      logging.addSyntheticError("Constraint test validation failed: " + e.getMessage());
    } finally {
      if (reader != null) {
        try {
          reader.close();
        } catch (Exception ignore) {
        }
      }
    }

    List<Map<String, Object>> normalizedMessages = normalizeFixtureDiagnostics(logging.messages(), xtfFile);
    int errors = 0;
    int warnings = 0;
    for (Map<String, Object> message : normalizedMessages) {
      if ("ERROR".equals(message.get("severity"))) {
        errors++;
      } else if ("WARNING".equals(message.get("severity"))) {
        warnings++;
      }
    }
    return new ValidationOutcome(normalizedMessages, errors, warnings);
  }

  private List<Map<String, Object>> normalizeFixtureDiagnostics(
      List<Map<String, Object>> messages,
      Path xtfFile) {
    List<Map<String, Object>> normalized = new ArrayList<>(messages.size());
    for (Map<String, Object> original : messages) {
      Map<String, Object> message = new LinkedHashMap<>(original);
      Object file = message.get("file");
      if (file != null && samePath(file.toString(), xtfFile)) {
        message.put("file", "<generated-constraint-fixture>");
      }
      Object text = message.get("message");
      if (text != null) {
        message.put("message", text.toString().replace(
            xtfFile.toString(), "<generated-constraint-fixture>"));
      }
      normalized.add(message);
    }
    return normalized;
  }

  private boolean samePath(String value, Path expected) {
    try {
      return Path.of(value).toAbsolutePath().normalize().equals(expected.toAbsolutePath().normalize());
    } catch (RuntimeException e) {
      return value.equals(expected.toString());
    }
  }

  private void fillMandatoryAttributes(
      Iom_jObject object,
      Table table,
      String sourceBasketId,
      int objectIndex,
      Set<String> explicitNames,
      Map<Table, List<PreparedObject>> objectsByClass) {
    Iterator<Extendable> attributes = table.getAttributes();
    while (attributes.hasNext()) {
      Extendable extendable = attributes.next();
      if (!(extendable instanceof AttributeDef attribute) || explicitNames.contains(attribute.getName())) {
        continue;
      }
      Type declaredType = attribute.getDomainOrDerivedDomain();
      Type type = Type.findReal(declaredType);
      int minimum = requiredMultiplicity(declaredType);
      for (int occurrence = 0; occurrence < minimum; occurrence++) {
        applyDefaultMandatoryValue(
            object,
            table,
            sourceBasketId,
            attribute,
            type,
            objectIndex,
            occurrence,
            objectsByClass);
      }
    }
  }

  private void applyDefaultMandatoryValue(
      Iom_jObject object,
      Table table,
      String sourceBasketId,
      AttributeDef attribute,
      Type type,
      int objectIndex,
      int occurrence,
      Map<Table, List<PreparedObject>> objectsByClass) {
    String name = attribute.getName();
    if (type instanceof TextType textType) {
      String value = "txt_" + objectIndex;
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
        switch (index) {
          case 0 -> coord.setattrvalue(Iom_jObject.COORD_C1, value);
          case 1 -> coord.setattrvalue(Iom_jObject.COORD_C2, value);
          case 2 -> coord.setattrvalue(Iom_jObject.COORD_C3, value);
          default -> throw unsupportedMandatory(table, attribute, type);
        }
      }
      return;
    }
    if (type instanceof ReferenceType referenceType) {
      AbstractClassDef<?> referred = referenceType.getReferred();
      if (!(referred instanceof Table targetTable)) {
        throw unsupportedMandatory(table, attribute, type);
      }
      List<PreparedObject> targets = objectsByClass.getOrDefault(targetTable, List.of());
      if (targets.isEmpty()) {
        throw new IllegalArgumentException("Explicit constraint case cannot auto-fill mandatory reference '"
            + table.getScopedName(null) + "." + name + "' because no target object was supplied.");
      }
      PreparedObject target = targets.get((objectIndex - 1 + occurrence) % targets.size());
      IomObject ref = object.addattrobj(name, Iom_jObject.REF);
      ref.setobjectrefoid(target.oid());
      setReferenceBasketIfNeeded(ref, topicOf(table), sourceBasketId, target);
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
      String sourceBasketId,
      Map<String, Object> values,
      int objectIndex,
      Map<Table, List<PreparedObject>> objectsByClass,
      Map<String, PreparedObject> objectsByOid) {
    for (Map.Entry<String, Object> entry : values.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
        continue;
      }
      String name = entry.getKey().trim();
      AttributeDef attribute = findAttribute(table, name);
      Type type = attribute != null ? Type.findReal(attribute.getDomainOrDerivedDomain()) : null;
      if (entry.getValue() instanceof TypedValueFixtureFactory.GeometryValue geometry) {
        applyGeometryValue(object, name, geometry);
      } else if (type instanceof CompositionType composition) {
        applyCompositionValue(
            object,
            name,
            composition,
            sourceBasketId,
            entry.getValue(),
            objectIndex,
            objectsByClass,
            objectsByOid);
      } else if (type instanceof ReferenceType) {
        applyReferenceValue(object, table, sourceBasketId, name, entry.getValue(), objectsByOid);
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
      String sourceBasketId,
      Object raw,
      int objectIndex,
      Map<Table, List<PreparedObject>> objectsByClass,
      Map<String, PreparedObject> objectsByOid) {
    List<?> occurrences = raw instanceof List<?> list ? list : List.of(raw);
    Table component = composition.getComponentType();
    for (Object occurrence : occurrences) {
      if (!(occurrence instanceof Map<?, ?> rawValues)) {
        throw new IllegalArgumentException(
            "STRUCTURE assignment for '" + attributeName + "' requires a map or list of maps.");
      }
      Map<String, Object> nestedValues = stringKeyedMap(rawValues, attributeName);
      Iom_jObject nested = new Iom_jObject(component.getScopedName(null), null);
      fillMandatoryAttributes(
          nested,
          component,
          sourceBasketId,
          objectIndex,
          nestedValues.keySet(),
          objectsByClass);
      applyValues(
          nested,
          component,
          sourceBasketId,
          nestedValues,
          objectIndex,
          objectsByClass,
          objectsByOid);
      owner.addattrobj(attributeName, nested);
    }
  }

  private void applyReferenceValue(
      Iom_jObject owner,
      Table sourceTable,
      String sourceBasketId,
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
    IomObject ref = owner.addattrobj(attributeName, Iom_jObject.REF);
    ref.setobjectrefoid(target.oid());
    setReferenceBasketIfNeeded(ref, topicOf(sourceTable), sourceBasketId, target);
  }

  private void applyGeometryValue(
      IomObject owner,
      String attributeName,
      TypedValueFixtureFactory.GeometryValue geometry) {
    switch (geometry) {
      case TypedValueFixtureFactory.CoordinateValue coordinate ->
          addCoordinate(owner, attributeName, coordinate);
      case TypedValueFixtureFactory.PolylineValue polyline -> {
        IomObject target = owner.addattrobj(attributeName, Iom_jObject.POLYLINE);
        fillPolyline(target, polyline);
      }
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
          IomObject child = target.addattrobj(
              Iom_jObject.MULTIPOLYLINE_POLYLINE, Iom_jObject.POLYLINE);
          fillPolyline(child, polyline);
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
      String name = switch (index) {
        case 0 -> Iom_jObject.COORD_C1;
        case 1 -> Iom_jObject.COORD_C2;
        case 2 -> Iom_jObject.COORD_C3;
        default -> throw new IllegalArgumentException("Coordinate fixture has too many axes.");
      };
      target.setattrvalue(name, coordinate.ordinates().get(index).stripTrailingZeros().toPlainString());
    }
  }

  private void fillPolyline(
      IomObject polyline,
      TypedValueFixtureFactory.PolylineValue value) {
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
      IomObject polyline = boundary.addattrobj(
          Iom_jObject.BOUNDARY_POLYLINE, Iom_jObject.POLYLINE);
      fillPolyline(polyline, boundaryValue);
    }
  }

  private Map<String, Object> stringKeyedMap(Map<?, ?> values, String attributeName) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : values.entrySet()) {
      if (!(entry.getKey() instanceof String key) || key.isBlank()) {
        throw new IllegalArgumentException(
            "STRUCTURE assignment for '" + attributeName + "' requires non-empty string attribute names.");
      }
      result.put(key, entry.getValue());
    }
    return result;
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

  private void applyReferences(
      Iom_jObject object,
      PreparedObject source,
      Map<String, PreparedObject> objectsByOid) {
    for (Map.Entry<String, String> reference : source.references().entrySet()) {
      PreparedObject target = objectsByOid.get(reference.getValue().trim());
      IomObject ref = object.addattrobj(reference.getKey().trim(), Iom_jObject.REF);
      ref.setobjectrefoid(target.oid());
      setReferenceBasketIfNeeded(ref, topicOf(source.table()), source.basketId(), target);
    }
  }

  private void setReferenceBasketIfNeeded(
      IomObject ref,
      Topic sourceTopic,
      String sourceBasketId,
      PreparedObject target) {
    Topic targetTopic = topicOf(target.table());
    if (sourceTopic != targetTopic || !sourceBasketId.equals(target.basketId())) {
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
      throw new IllegalArgumentException("Mandatory multiplicity minimum " + minimum + " is too large for minimal constraint fixtures.");
    }
    return minimum;
  }

  private Topic topicOf(Table table) {
    Topic topic = (Topic) table.getContainer(Topic.class);
    if (topic == null) {
      throw new IllegalArgumentException("Fixture class is not contained in a topic: " + table.getScopedName(null));
    }
    return topic;
  }

  private Topic topicOf(AssociationDef association) {
    Topic topic = (Topic) association.getContainer(Topic.class);
    if (topic == null) {
      throw new IllegalArgumentException("Fixture association is not contained in a topic: " + association.getScopedName(null));
    }
    return topic;
  }

  private @Nullable String normalizeBasketId(@Nullable String basketId) {
    return basketId == null || basketId.isBlank() ? null : basketId.trim();
  }

  private int countSubjects(Constraint target, List<PreparedObject> objects, List<PreparedLink> links) {
    Element context = target.getContainer();
    if (context instanceof Table table) {
      return (int) objects.stream().filter(object -> object.table() == table).count();
    }
    if (context instanceof AssociationDef association) {
      return (int) links.stream().filter(link -> link.association() == association).count();
    }
    return 0;
  }

  private TestCase requireCase(@Nullable TestCase testCase, int index) {
    if (testCase == null) {
      throw new IllegalArgumentException("Constraint test case at index " + index + " must not be null.");
    }
    if (testCase.name == null || testCase.name.isBlank()) {
      throw new IllegalArgumentException("Constraint test case at index " + index + " requires a name.");
    }
    if (testCase.expectedConstraintValid == null) {
      throw new IllegalArgumentException("Case '" + testCase.name + "' requires expectedConstraintValid.");
    }
    if (testCase.objects == null || testCase.objects.isEmpty()) {
      throw new IllegalArgumentException("Case '" + testCase.name + "' requires at least one object.");
    }
    return testCase;
  }

  private Constraint findConstraint(TransferDescription td, String requestedName) {
    List<Constraint> matches = collectConstraints(td).stream()
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
    for (Model model : td.getModelsFromLastFile()) {
      collectConstraints(model, result);
    }
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

  private record ObjectDraft(
      Table table,
      String oid,
      @Nullable String requestedBasketId,
      Map<String, Object> values,
      Map<String, String> references) {
  }

  private record LinkDraft(
      AssociationDef association,
      @Nullable String requestedBasketId,
      Map<String, String> roles) {
  }

  private record PreparedObject(
      Table table,
      String oid,
      String basketId,
      Map<String, Object> values,
      Map<String, String> references) {
  }

  private record PreparedLink(
      AssociationDef association,
      @Nullable String basketId,
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
      List<PreparedObject> objects,
      List<PreparedLink> links,
      Map<String, PreparedObject> objectsByOid,
      Map<Table, List<PreparedObject>> objectsByClass,
      List<BasketKey> baskets,
      int subjectCount) {
  }

  private record ValidationOutcome(List<Map<String, Object>> messages, int errorCount, int warningCount) {
  }

  private static final class FixturePreparationException extends IllegalArgumentException {
    private final String reasonCode;

    private FixturePreparationException(String reasonCode, String message) {
      super(message);
      this.reasonCode = reasonCode;
    }

    private String reasonCode() {
      return reasonCode;
    }
  }

  private static final class CollectingIoxLogging implements IoxLogging {
    private final List<Map<String, Object>> messages = new ArrayList<>();

    @Override
    public void addEvent(IoxLogEvent event) {
      String severity;
      if (event.getEventKind() == IoxLogEvent.ERROR) {
        severity = "ERROR";
      } else if (event.getEventKind() == IoxLogEvent.WARNING) {
        severity = "WARNING";
      } else {
        return;
      }
      Map<String, Object> message = new LinkedHashMap<>();
      message.put("severity", severity);
      String raw = event.getRawEventMsg();
      message.put("message", raw != null && !raw.isBlank() ? raw : event.getEventMsg());
      if (event.getDataSource() != null && !event.getDataSource().isBlank()) {
        message.put("file", event.getDataSource());
      }
      Integer sourceLine = event.getSourceLineNr();
      if (sourceLine != null && sourceLine > 0) {
        message.put("line", sourceLine);
      }
      messages.add(message);
    }

    private void addSyntheticError(String text) {
      messages.add(Map.of("severity", "ERROR", "message", text != null ? text : "Constraint test validation failed."));
    }

    private List<Map<String, Object>> messages() {
      return messages;
    }
  }
}
