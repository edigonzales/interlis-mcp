package ch.so.agi.mcp.tools;

import ch.interlis.ili2c.metamodel.AbstractCoordType;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.AttributeRef;
import ch.interlis.ili2c.metamodel.Cardinality;
import ch.interlis.ili2c.metamodel.CompositionType;
import ch.interlis.ili2c.metamodel.EnumTreeValueType;
import ch.interlis.ili2c.metamodel.EnumerationType;
import ch.interlis.ili2c.metamodel.ExistenceConstraint;
import ch.interlis.ili2c.metamodel.LineType;
import ch.interlis.ili2c.metamodel.NumericType;
import ch.interlis.ili2c.metamodel.ObjectPath;
import ch.interlis.ili2c.metamodel.ReferenceType;
import ch.interlis.ili2c.metamodel.Table;
import ch.interlis.ili2c.metamodel.TextType;
import ch.interlis.ili2c.metamodel.Type;
import ch.so.agi.mcp.constraint.CompiledConstraintContext;
import ch.so.agi.mcp.constraint.SemanticConstraint;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** B8 proof planning for direct non-scalar EXISTENCE attributes and explicit safety gates. */
final class ExistenceConstraintSpecialCasePlanner {

  private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

  private ExistenceConstraintSpecialCasePlanner() {
  }

  /**
   * Returns {@code null} for scalar B7 constraints so the existing finite-domain planner keeps
   * ownership. Non-scalar direct attributes are handled or rejected with a precise reason code.
   */
  static ExistenceConstraintCasePlanner.@Nullable Plan planIfSpecial(
      CompiledConstraintContext context,
      SemanticConstraint.Existence semantics) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(semantics, "semantics");
    if (!(context.constraint() instanceof ExistenceConstraint raw)) {
      return null;
    }

    AttributeDef restricted = directAttribute(raw.getRestrictedAttribute());
    if (restricted == null) {
      if (isScalarKind(semantics.restrictedAttribute())) {
        return null;
      }
      return unsolvedOnly(
          "EXISTENCE_SPECIAL_PATH_NAVIGATION_UNSUPPORTED",
          "B8 special-type proof currently requires a direct attribute path; navigated non-scalar EXISTENCE paths are not approximated.",
          semantics.restrictedAttribute().rootFqn() + ":" + semantics.restrictedAttribute().path());
    }

    Type restrictedType = Type.findReal(restricted.getDomainOrDerivedDomain());
    if (restrictedType instanceof CompositionType composition) {
      return planComposition(context, semantics, raw, restricted, composition);
    }
    if (restrictedType instanceof ReferenceType) {
      return unsolvedOnly(
          "EXISTENCE_REFERENCE_VALUE_PROOF_UNSAFE",
          "REFERENCE-valued EXISTENCE is not automatically claimed as proven because the active validator comparison path is not value-discriminating enough for a safe equality counterexample.",
          semantics.restrictedAttribute().rootFqn() + ":" + semantics.restrictedAttribute().path());
    }
    if (restrictedType instanceof AbstractCoordType) {
      return unsolvedOnly(
          "EXISTENCE_COORD_FIXTURE_NOT_VALUE_AWARE",
          "COORD-valued EXISTENCE has dedicated validator equality semantics, but the automatic fixture layer cannot yet inject arbitrary coordinate values without relying on model-specific defaults.",
          semantics.restrictedAttribute().rootFqn() + ":" + semantics.restrictedAttribute().path());
    }
    if (restrictedType instanceof LineType) {
      return unsolvedOnly(
          "EXISTENCE_COMPLEX_GEOMETRY_FIXTURE_UNAVAILABLE",
          "POLYLINE/SURFACE/AREA EXISTENCE equality is validator-specific and the automatic fixture layer does not yet synthesize geometry values for a proof.",
          semantics.restrictedAttribute().rootFqn() + ":" + semantics.restrictedAttribute().path());
    }
    if (isScalarKind(semantics.restrictedAttribute())) {
      return null;
    }
    return unsolvedOnly(
        "EXISTENCE_SPECIAL_TYPE_UNSUPPORTED",
        "Automatic EXISTENCE proof does not support restricted attribute type "
            + restrictedType.getClass().getSimpleName() + ".",
        semantics.restrictedAttribute().rootFqn() + ":" + semantics.restrictedAttribute().path());
  }

  private static ExistenceConstraintCasePlanner.Plan planComposition(
      CompiledConstraintContext context,
      SemanticConstraint.Existence semantics,
      ExistenceConstraint raw,
      AttributeDef restricted,
      CompositionType restrictedType) {
    List<ConstraintTestTools.TestCase> cases = new ArrayList<>();
    List<Map<String, Object>> summaries = new ArrayList<>();
    List<Map<String, Object>> unsolved = new ArrayList<>();

    Table sourceClass = identifiableRoot(raw.getRestrictedAttribute());
    if (sourceClass == null) {
      return unsolvedOnly(
          "EXISTENCE_STRUCTURE_CONTEXT_NOT_IDENTIFIABLE",
          "Automatic STRUCTURE EXISTENCE proof requires an identifiable class as restricted-path root.",
          semantics.restrictedAttribute().rootFqn());
    }

    StructureTemplate template = structureTemplate(restrictedType.getComponentType());
    if (template == null) {
      return unsolvedOnly(
          "EXISTENCE_STRUCTURE_TEMPLATE_UNAVAILABLE",
          "The restricted STRUCTURE contains mandatory members that the minimal fixture generator cannot safely materialize, or no comparable scalar member exists.",
          restrictedType.getComponentType().getScopedName(null));
    }

    int sourceCount = definedOccurrenceCount(restricted.getDomainOrDerivedDomain());
    if (sourceCount < 1) {
      return unsolvedOnly(
          "EXISTENCE_STRUCTURE_CARDINALITY_UNSUPPORTED",
          "The restricted STRUCTURE cardinality cannot be represented by a small defined proof fixture.",
          restricted.getScopedName(null));
    }

    Object sourceValue = occurrences(template.sameValues(), sourceCount);
    ConstraintTestTools.TestObject sourceOnly = object(
        sourceClass.getScopedName(null),
        "existence_struct_source_only",
        Map.of(restricted.getName(), sourceValue));
    addCase(
        cases,
        summaries,
        "defined structure missing from all REQUIRED IN targets",
        false,
        "COUNTEREXAMPLE",
        "A defined STRUCTURE value without an equal REQUIRED IN value violates EXISTENCE.",
        Map.of("structure", template.sameValues(), "occurrenceCount", sourceCount),
        List.of(sourceOnly));

    List<ObjectPath> rawTargets = requiredIn(raw);
    for (int i = 0; i < rawTargets.size(); i++) {
      ObjectPath rawTarget = rawTargets.get(i);
      SemanticConstraint.ConstraintPath semanticTarget = semantics.requiredIn().get(i);
      AttributeDef targetAttribute = directAttribute(rawTarget);
      if (targetAttribute == null) {
        unsolved.add(unsolved(
            "EXISTENCE_STRUCTURE_TARGET_NAVIGATION_UNSUPPORTED",
            "STRUCTURE REQUIRED IN target must be a direct attribute path in B8.",
            semanticTarget.rootFqn() + ":" + semanticTarget.path()));
        continue;
      }
      Type targetReal = Type.findReal(targetAttribute.getDomainOrDerivedDomain());
      if (!(targetReal instanceof CompositionType targetComposition)) {
        unsolved.add(unsolved(
            "EXISTENCE_STRUCTURE_TARGET_TYPE_MISMATCH",
            "STRUCTURE restricted value resolved to a non-STRUCTURE REQUIRED IN target.",
            semanticTarget.rootFqn() + ":" + semanticTarget.path()));
        continue;
      }
      if (!restricted.getName().equals(targetAttribute.getName())) {
        unsolved.add(unsolved(
            "EXISTENCE_STRUCTURE_TARGET_ATTRIBUTE_NAME_MISMATCH",
            "The active validator structure comparison reads the restricted transfer attribute name from the REQUIRED IN object; automatic proof therefore requires identical source/target attribute names.",
            restricted.getName() + " vs " + targetAttribute.getName()));
        continue;
      }
      if (!sameStructureType(restrictedType.getComponentType(), targetComposition.getComponentType())) {
        unsolved.add(unsolved(
            "EXISTENCE_STRUCTURE_COMPONENT_TYPE_UNSUPPORTED",
            "Automatic equality proof currently requires the restricted and REQUIRED IN STRUCTURE attributes to use the same component type.",
            restrictedType.getComponentType().getScopedName(null) + " vs "
                + targetComposition.getComponentType().getScopedName(null)));
        continue;
      }
      Table targetClass = identifiableRoot(rawTarget);
      if (targetClass == null) {
        unsolved.add(unsolved(
            "EXISTENCE_STRUCTURE_TARGET_NOT_IDENTIFIABLE",
            "Automatic STRUCTURE EXISTENCE proof requires an identifiable class as REQUIRED IN root.",
            semanticTarget.rootFqn()));
        continue;
      }
      int count = commonOccurrenceCount(
          restricted.getDomainOrDerivedDomain(),
          targetAttribute.getDomainOrDerivedDomain());
      if (count < 1) {
        unsolved.add(unsolved(
            "EXISTENCE_STRUCTURE_CARDINALITY_INTERSECTION_EMPTY",
            "No small positive occurrence count is valid for both STRUCTURE attributes.",
            semanticTarget.rootFqn() + ":" + semanticTarget.path()));
        continue;
      }

      Object equalValue = occurrences(template.sameValues(), count);
      ConstraintTestTools.TestObject source = object(
          sourceClass.getScopedName(null),
          "existence_struct_source_" + (i + 1),
          Map.of(restricted.getName(), equalValue));
      ConstraintTestTools.TestObject target = object(
          targetClass.getScopedName(null),
          "existence_struct_target_" + (i + 1),
          Map.of(targetAttribute.getName(), equalValue));
      addCase(
          cases,
          summaries,
          "equal structure exists in REQUIRED IN target " + (i + 1),
          true,
          "WITNESS",
          "The restricted STRUCTURE equals the REQUIRED IN STRUCTURE value member-by-member.",
          Map.of(
              "requiredIn", semanticTarget.rootFqn() + ":" + semanticTarget.path(),
              "structure", template.sameValues(),
              "occurrenceCount", count),
          List.of(source, target));

      if (template.differentValues() != null) {
        Object differentValue = occurrences(template.differentValues(), count);
        ConstraintTestTools.TestObject differentSource = object(
            sourceClass.getScopedName(null),
            "existence_struct_diff_source_" + (i + 1),
            Map.of(restricted.getName(), equalValue));
        ConstraintTestTools.TestObject differentTarget = object(
            targetClass.getScopedName(null),
            "existence_struct_diff_target_" + (i + 1),
            Map.of(targetAttribute.getName(), differentValue));
        addCase(
            cases,
            summaries,
            "structure target differs in comparable member " + template.probeAttribute(),
            false,
            "COUNTEREXAMPLE",
            "A REQUIRED IN object is insufficient when its STRUCTURE value differs from the restricted value.",
            Map.of(
                "probeAttribute", template.probeAttribute(),
                "sourceStructure", template.sameValues(),
                "targetStructure", template.differentValues()),
            List.of(differentSource, differentTarget));
      } else {
        unsolved.add(unsolved(
            "EXISTENCE_STRUCTURE_DIFFERENCE_NOT_SYNTHESIZED",
            "The structure can be materialized equally, but no second valid scalar value was available to synthesize a member-level inequality.",
            restrictedType.getComponentType().getScopedName(null)));
      }
    }

    if (minimum(restricted.getDomainOrDerivedDomain()) == 0) {
      ConstraintTestTools.TestObject undefinedSource = object(
          sourceClass.getScopedName(null),
          "existence_struct_undefined",
          Map.of());
      addCase(
          cases,
          summaries,
          "undefined optional structure",
          true,
          "WITNESS",
          "An omitted optional restricted STRUCTURE does not require a matching target value.",
          Map.of("sourceValue", "UNDEFINED"),
          List.of(undefinedSource));
    }

    return new ExistenceConstraintCasePlanner.Plan(cases, summaries, unsolved);
  }

  private static @Nullable StructureTemplate structureTemplate(Table component) {
    Map<String, Object> same = new LinkedHashMap<>();
    String probe = null;
    Object probeAlternative = null;

    Iterator<?> iterator = component.getAttributes();
    while (iterator.hasNext()) {
      Object next = iterator.next();
      if (!(next instanceof AttributeDef attribute)) {
        continue;
      }
      Type declared = attribute.getDomainOrDerivedDomain();
      Type real = Type.findReal(declared);
      ScalarPair pair = scalarPair(real);
      boolean required = minimum(declared) > 0;
      if (pair != null) {
        if (required || probe == null) {
          same.put(attribute.getName(), pair.same());
        }
        if (probe == null && pair.different() != null) {
          probe = attribute.getName();
          probeAlternative = pair.different();
          same.put(attribute.getName(), pair.same());
        }
        continue;
      }
      if (required && !(real instanceof AbstractCoordType)) {
        return null;
      }
    }

    if (same.isEmpty()) {
      return null;
    }
    Map<String, Object> different = null;
    if (probe != null && probeAlternative != null) {
      different = new LinkedHashMap<>(same);
      different.put(probe, probeAlternative);
    }
    return new StructureTemplate(Map.copyOf(same), different == null ? null : Map.copyOf(different), probe);
  }

  private static @Nullable ScalarPair scalarPair(Type real) {
    if (real instanceof TextType text) {
      int maxLength = text.getMaxLength();
      if (maxLength == 0) {
        return null;
      }
      String same = maxLength > 0 && maxLength < 1 ? "" : "a";
      String different = maxLength > 0 && maxLength < 1 ? null : "b";
      return new ScalarPair(same, different);
    }
    if (real instanceof NumericType numeric) {
      BigDecimal min = numeric.getMinimum() != null
          ? new BigDecimal(numeric.getMinimum().toString())
          : null;
      BigDecimal max = numeric.getMaximum() != null
          ? new BigDecimal(numeric.getMaximum().toString())
          : null;
      BigDecimal same = min != null ? min : max != null ? max : BigDecimal.ZERO;
      BigDecimal different = null;
      if (max != null && max.compareTo(same) != 0) {
        different = max;
      } else if (min != null && min.compareTo(same) != 0) {
        different = min;
      } else if (min == null && max == null) {
        different = BigDecimal.ONE;
      }
      return new ScalarPair(same, different);
    }
    if (real.isBoolean()) {
      return new ScalarPair(Boolean.TRUE, Boolean.FALSE);
    }
    if (real instanceof EnumerationType enumeration) {
      if (enumeration.getValues().isEmpty()) {
        return null;
      }
      Object same = enumeration.getValues().getFirst();
      Object different = enumeration.getValues().size() > 1 ? enumeration.getValues().get(1) : null;
      return new ScalarPair(same, different);
    }
    if (real instanceof EnumTreeValueType enumeration) {
      if (enumeration.getValues().isEmpty()) {
        return null;
      }
      Object same = enumeration.getValues().getFirst();
      Object different = enumeration.getValues().size() > 1 ? enumeration.getValues().get(1) : null;
      return new ScalarPair(same, different);
    }
    return null;
  }

  private static int commonOccurrenceCount(Type source, Type target) {
    int count = Math.max(1, Math.max(minimum(source), minimum(target)));
    if (count > 4 || !allows(source, count) || !allows(target, count)) {
      return -1;
    }
    return count;
  }

  private static int definedOccurrenceCount(Type type) {
    int count = Math.max(1, minimum(type));
    return count <= 4 && allows(type, count) ? count : -1;
  }

  private static int minimum(Type type) {
    int minimum = type.isMandatoryConsideringAliases() ? 1 : 0;
    Cardinality cardinality = type.getCardinality();
    if (cardinality != null && cardinality.getMinimum() > minimum) {
      long value = cardinality.getMinimum();
      minimum = value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
    return minimum;
  }

  private static boolean allows(Type type, int count) {
    if (count < minimum(type)) {
      return false;
    }
    Cardinality cardinality = type.getCardinality();
    return cardinality == null
        || cardinality.getMaximum() == Cardinality.UNBOUND
        || count <= cardinality.getMaximum();
  }

  private static Object occurrences(Map<String, Object> values, int count) {
    if (count == 1) {
      return values;
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      result.add(values);
    }
    return List.copyOf(result);
  }

  private static boolean sameStructureType(Table left, Table right) {
    return left == right || left.getScopedName(null).equals(right.getScopedName(null));
  }

  private static @Nullable AttributeDef directAttribute(ObjectPath path) {
    if (path == null || !SIMPLE_IDENTIFIER.matcher(path.toString()).matches()) {
      return null;
    }
    return path.getLastPathEl() instanceof AttributeRef attributeRef
        ? attributeRef.getAttr()
        : null;
  }

  private static @Nullable Table identifiableRoot(ObjectPath path) {
    return path != null && path.getRoot() instanceof Table table && table.isIdentifiable()
        ? table
        : null;
  }

  private static List<ObjectPath> requiredIn(ExistenceConstraint constraint) {
    List<ObjectPath> result = new ArrayList<>();
    Iterator<ObjectPath> iterator = constraint.iteratorRequiredIn();
    while (iterator.hasNext()) {
      result.add(iterator.next());
    }
    return List.copyOf(result);
  }

  private static boolean isScalarKind(SemanticConstraint.ConstraintPath path) {
    return switch (path.endpointType().scalarKind()) {
      case NUMERIC, BOOLEAN, ENUM, TEXT, MTEXT -> true;
      default -> false;
    };
  }

  private static ConstraintTestTools.TestObject object(
      String classFqn,
      String oid,
      Map<String, Object> values) {
    ConstraintTestTools.TestObject result = new ConstraintTestTools.TestObject();
    result.classFqn = classFqn;
    result.oid = oid;
    result.values = values;
    return result;
  }

  private static void addCase(
      List<ConstraintTestTools.TestCase> cases,
      List<Map<String, Object>> summaries,
      String name,
      boolean expected,
      String purpose,
      String reason,
      Object values,
      List<ConstraintTestTools.TestObject> objects) {
    ConstraintTestTools.TestCase testCase = new ConstraintTestTools.TestCase();
    testCase.name = name;
    testCase.expectedConstraintValid = expected;
    testCase.objects = List.copyOf(objects);
    testCase.links = List.of();
    cases.add(testCase);

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("purpose", purpose);
    summary.put("name", name);
    summary.put("reason", reason);
    summary.put("expectedConstraintValid", expected);
    summary.put("values", values);
    summary.put("objectCount", objects.size());
    summaries.add(Map.copyOf(summary));
  }

  private static ExistenceConstraintCasePlanner.Plan unsolvedOnly(
      String reasonCode,
      String reason,
      String goal) {
    return new ExistenceConstraintCasePlanner.Plan(
        List.of(),
        List.of(),
        List.of(unsolved(reasonCode, reason, goal)));
  }

  private static Map<String, Object> unsolved(String reasonCode, String reason, String goal) {
    return Map.of(
        "reasonCode", reasonCode,
        "reason", reason,
        "goal", goal);
  }

  private record StructureTemplate(
      Map<String, Object> sameValues,
      @Nullable Map<String, Object> differentValues,
      @Nullable String probeAttribute) {
  }

  private record ScalarPair(Object same, @Nullable Object different) {
  }
}
