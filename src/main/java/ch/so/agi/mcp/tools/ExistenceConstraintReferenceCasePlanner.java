package ch.so.agi.mcp.tools;

import ch.interlis.ili2c.metamodel.AbstractClassDef;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.ExistenceConstraint;
import ch.interlis.ili2c.metamodel.ObjectPath;
import ch.interlis.ili2c.metamodel.PathElRefAttr;
import ch.interlis.ili2c.metamodel.ReferenceType;
import ch.interlis.ili2c.metamodel.Table;
import ch.interlis.ili2c.metamodel.Type;
import ch.so.agi.mcp.constraint.CompiledConstraintContext;
import ch.so.agi.mcp.constraint.SemanticConstraint;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Validator-backed REFERENCE/OID equality fixtures for EXISTENCE. */
final class ExistenceConstraintReferenceCasePlanner {

  private ExistenceConstraintReferenceCasePlanner() {
  }

  static ExistenceConstraintCasePlanner.@Nullable Plan planIfReference(
      CompiledConstraintContext context,
      SemanticConstraint.Existence semantics) {
    if (!(context.constraint() instanceof ExistenceConstraint raw)
        || raw.getRestrictedAttribute() == null
        || !(raw.getRestrictedAttribute().getLastPathEl() instanceof PathElRefAttr sourcePath)) {
      return null;
    }
    AttributeDef sourceAttribute = sourcePath.getAttr();
    Table sourceHolder = identifiableRoot(raw.getRestrictedAttribute());
    ReferenceType sourceType = referenceType(sourceAttribute);
    Table sourceTarget = sourceType == null ? null : concreteTarget(sourceType);
    if (sourceHolder == null || sourceTarget == null) {
      return unsolved("EXISTENCE_REFERENCE_TARGET_CONCRETE_TYPE_UNAVAILABLE",
          "REFERENCE EXISTENCE requires a direct reference attribute on an identifiable class and a concrete target class.");
    }

    List<ConstraintTestTools.TestCase> cases = new ArrayList<>();
    List<Map<String, Object>> summaries = new ArrayList<>();
    List<Map<String, Object>> unsolved = new ArrayList<>();
    ConstraintTestTools.TestObject sameTarget = object(
        sourceTarget.getScopedName(null), "existence_ref_same", Map.of());
    ConstraintTestTools.TestObject source = object(
        sourceHolder.getScopedName(null), "existence_ref_source",
        Map.of(sourceAttribute.getName(),
            new TypedValueFixtureFactory.ReferenceValue(sameTarget.oid)));
    addCase(cases, summaries,
        "referenced target OID missing from all REQUIRED IN values", false,
        "COUNTEREXAMPLE", List.of(sameTarget, source),
        Map.of("sourceReferenceOid", sameTarget.oid));

    Iterator<ObjectPath> targets = raw.iteratorRequiredIn();
    int index = 0;
    while (targets.hasNext()) {
      ObjectPath targetPath = targets.next();
      index++;
      if (!(targetPath.getLastPathEl() instanceof PathElRefAttr targetPathElement)) {
        unsolved.add(gap("EXISTENCE_REFERENCE_TARGET_TYPE_MISMATCH",
            "A REFERENCE restricted value requires a REFERENCE REQUIRED IN target.",
            semantics.requiredIn().get(index - 1).path()));
        continue;
      }
      AttributeDef targetAttribute = targetPathElement.getAttr();
      Table targetHolder = identifiableRoot(targetPath);
      ReferenceType targetType = referenceType(targetAttribute);
      Table otherTargetType = targetType == null ? null : concreteTarget(targetType);
      if (targetHolder == null || otherTargetType == null
          || !compatibleTargets(sourceTarget, otherTargetType)) {
        unsolved.add(gap("EXISTENCE_REFERENCE_TARGET_UNSUPPORTED",
            "REFERENCE REQUIRED IN requires a compatible concrete target type.",
            semantics.requiredIn().get(index - 1).path()));
        continue;
      }

      ConstraintTestTools.TestObject equalHolder = object(
          targetHolder.getScopedName(null), "existence_ref_holder_equal_" + index,
          Map.of(targetAttribute.getName(),
              new TypedValueFixtureFactory.ReferenceValue(sameTarget.oid)));
      addCase(cases, summaries,
          "same referenced target OID in REQUIRED IN branch " + index, true,
          "WITNESS", List.of(sameTarget, source, equalHolder),
          Map.of("requiredInReferenceOid", sameTarget.oid));

      ConstraintTestTools.TestObject otherTarget = object(
          otherTargetType.getScopedName(null), "existence_ref_other_" + index, Map.of());
      ConstraintTestTools.TestObject differentHolder = object(
          targetHolder.getScopedName(null), "existence_ref_holder_different_" + index,
          Map.of(targetAttribute.getName(),
              new TypedValueFixtureFactory.ReferenceValue(otherTarget.oid)));
      addCase(cases, summaries,
          "different referenced target OID in REQUIRED IN branch " + index, false,
          "COUNTEREXAMPLE", List.of(sameTarget, otherTarget, source, differentHolder),
          Map.of("sourceReferenceOid", sameTarget.oid,
              "requiredInReferenceOid", otherTarget.oid));
    }

    if (!sourceAttribute.getDomainOrDerivedDomain().isMandatoryConsideringAliases()) {
      addCase(cases, summaries, "undefined optional reference", true, "WITNESS",
          List.of(object(sourceHolder.getScopedName(null), "existence_ref_undefined", Map.of())),
          Map.of("sourceValue", "UNDEFINED"));
    }
    return new ExistenceConstraintCasePlanner.Plan(cases, summaries, unsolved);
  }

  private static @Nullable ReferenceType referenceType(AttributeDef attribute) {
    Type real = Type.findReal(attribute.getDomainOrDerivedDomain());
    return real instanceof ReferenceType reference ? reference : null;
  }

  private static @Nullable Table concreteTarget(ReferenceType type) {
    AbstractClassDef<?> referred = type.getReferred();
    return referred instanceof Table table && !table.isAbstract() ? table : null;
  }

  private static boolean compatibleTargets(Table left, Table right) {
    return left == right || left.isExtending(right) || right.isExtending(left);
  }

  private static @Nullable Table identifiableRoot(ObjectPath path) {
    return path != null && path.getRoot() instanceof Table table && table.isIdentifiable()
        ? table : null;
  }

  private static ConstraintTestTools.TestObject object(
      String classFqn, String oid, Map<String, Object> values) {
    ConstraintTestTools.TestObject result = new ConstraintTestTools.TestObject();
    result.classFqn = classFqn;
    result.oid = oid;
    result.values = values;
    result.references = Map.of();
    return result;
  }

  private static void addCase(
      List<ConstraintTestTools.TestCase> cases,
      List<Map<String, Object>> summaries,
      String name,
      boolean expected,
      String purpose,
      List<ConstraintTestTools.TestObject> objects,
      Map<String, Object> values) {
    ConstraintTestTools.TestCase testCase = new ConstraintTestTools.TestCase();
    testCase.name = name;
    testCase.expectedConstraintValid = expected;
    testCase.objects = List.copyOf(objects);
    testCase.links = List.of();
    cases.add(testCase);
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("name", name);
    summary.put("purpose", purpose);
    summary.put("expectedConstraintValid", expected);
    summary.put("objectCount", objects.size());
    summary.put("values", values);
    summaries.add(Map.copyOf(summary));
  }

  private static ExistenceConstraintCasePlanner.Plan unsolved(
      String reasonCode, String reason) {
    return new ExistenceConstraintCasePlanner.Plan(
        List.of(), List.of(), List.of(gap(reasonCode, reason, "REFERENCE")));
  }

  private static Map<String, Object> gap(String code, String reason, String goal) {
    return Map.of("reasonCode", code, "reason", reason, "goal", goal);
  }
}
