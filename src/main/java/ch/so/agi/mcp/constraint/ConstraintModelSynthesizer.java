package ch.so.agi.mcp.constraint;

import ch.interlis.ili2c.metamodel.AssociationDef;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.AttributeRef;
import ch.interlis.ili2c.metamodel.Cardinality;
import ch.interlis.ili2c.metamodel.Element;
import ch.interlis.ili2c.metamodel.EnumerationType;
import ch.interlis.ili2c.metamodel.Extendable;
import ch.interlis.ili2c.metamodel.NumericType;
import ch.interlis.ili2c.metamodel.ObjectPath;
import ch.interlis.ili2c.metamodel.PathEl;
import ch.interlis.ili2c.metamodel.PathElAbstractClassRole;
import ch.interlis.ili2c.metamodel.PathElAssocRole;
import ch.interlis.ili2c.metamodel.RoleDef;
import ch.interlis.ili2c.metamodel.TextType;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.ili2c.metamodel.Type;
import ch.interlis.ili2c.metamodel.Viewable;
import ch.interlis.ili2c.parser.Ili23Parser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Binds semantic constraint references to an ili2c model and materializes concrete object graphs.
 *
 * <p>This class deliberately does not solve test goals. A solver supplies an assignment for the IR
 * references; this class validates that assignment against INTERLIS domains/cardinalities and turns
 * it into root/target objects plus association links. That keeps scalar solving independent from
 * INTERLIS transfer topology.</p>
 */
public final class ConstraintModelSynthesizer {

  public record NumericDomain(
      @Nullable BigDecimal minimum,
      @Nullable BigDecimal maximum,
      BigDecimal step) {

    public NumericDomain {
      Objects.requireNonNull(step, "step");
      if (step.compareTo(BigDecimal.ZERO) <= 0) {
        throw new IllegalArgumentException("Numeric domain step must be positive.");
      }
    }

    public boolean contains(BigDecimal value) {
      Objects.requireNonNull(value, "value");
      if (minimum != null && value.compareTo(minimum) < 0) {
        return false;
      }
      if (maximum != null && value.compareTo(maximum) > 0) {
        return false;
      }
      try {
        value.setScale(step.scale(), RoundingMode.UNNECESSARY);
        return true;
      } catch (ArithmeticException ex) {
        return false;
      }
    }
  }

  public record ValueDomain(
      ConstraintExpression.ScalarKind kind,
      @Nullable NumericDomain numeric,
      List<String> values,
      boolean mandatory) {

    public ValueDomain {
      Objects.requireNonNull(kind, "kind");
      values = values == null ? List.of() : List.copyOf(values);
      if (kind == ConstraintExpression.ScalarKind.NUMERIC && numeric == null) {
        throw new IllegalArgumentException("NUMERIC domain requires numeric bounds/precision metadata.");
      }
    }
  }

  public record AssociationBinding(
      String associationFqn,
      String roleName,
      String oppositeRoleName,
      String targetClassFqn,
      long minimum,
      long maximum,
      boolean unbounded) {

    public AssociationBinding {
      requireName(associationFqn, "associationFqn");
      requireName(roleName, "roleName");
      requireName(oppositeRoleName, "oppositeRoleName");
      requireName(targetClassFqn, "targetClassFqn");
      if (minimum < 0 || (!unbounded && maximum < minimum)) {
        throw new IllegalArgumentException("Invalid association cardinality.");
      }
    }

    public int effectiveMaximum(int cap) {
      if (cap < 1) {
        throw new IllegalArgumentException("cap must be positive.");
      }
      if (unbounded || maximum > cap) {
        return cap;
      }
      return (int) maximum;
    }

    String groupKey() {
      return associationFqn + "|" + roleName + "|" + oppositeRoleName;
    }
  }

  public record ReferenceBinding(
      ConstraintExpression.Reference reference,
      ValueDomain domain,
      String attributeName,
      @Nullable AssociationBinding association) {

    public ReferenceBinding {
      Objects.requireNonNull(reference, "reference");
      Objects.requireNonNull(domain, "domain");
      requireName(attributeName, "attributeName");
    }

    public boolean associationPath() {
      return association != null;
    }
  }

  public record ModelBinding(
      String contextFqn,
      Map<String, ReferenceBinding> references) {

    public ModelBinding {
      requireName(contextFqn, "contextFqn");
      references = references == null
          ? Map.of()
          : Collections.unmodifiableMap(new LinkedHashMap<>(references));
    }

    public ReferenceBinding reference(String name) {
      ReferenceBinding binding = references.get(name);
      if (binding == null) {
        throw new IllegalArgumentException("Expression reference is not bound: " + name);
      }
      return binding;
    }
  }

  public record GraphObject(
      String classFqn,
      String oid,
      Map<String, Object> values) {

    public GraphObject {
      requireName(classFqn, "classFqn");
      requireName(oid, "oid");
      values = values == null
          ? Map.of()
          : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
  }

  public record GraphLink(
      String associationFqn,
      Map<String, String> roles) {

    public GraphLink {
      requireName(associationFqn, "associationFqn");
      roles = roles == null
          ? Map.of()
          : Collections.unmodifiableMap(new LinkedHashMap<>(roles));
      if (roles.size() < 2) {
        throw new IllegalArgumentException("Association graph link requires at least two roles.");
      }
    }
  }

  public record ObjectGraph(
      List<GraphObject> objects,
      List<GraphLink> links) {

    public ObjectGraph {
      objects = objects == null ? List.of() : List.copyOf(objects);
      links = links == null ? List.of() : List.copyOf(links);
      if (objects.isEmpty()) {
        throw new IllegalArgumentException("Object graph requires a root object.");
      }
    }
  }

  private record AssignedReference(ReferenceBinding binding, @Nullable List<Object> values) {
  }

  private static final class AssociationGroup {
    private final AssociationBinding association;
    private final boolean collection;
    private final List<AssignedReference> references = new ArrayList<>();

    private AssociationGroup(AssociationBinding association, boolean collection) {
      this.association = association;
      this.collection = collection;
    }
  }

  private ConstraintModelSynthesizer() {
  }

  /** Resolves all attribute/path references of an expression against the compiled model. */
  public static ModelBinding bind(
      TransferDescription td,
      String contextFqn,
      ConstraintExpression expression) {
    Objects.requireNonNull(td, "td");
    Objects.requireNonNull(expression, "expression");
    requireName(contextFqn, "contextFqn");

    Element contextElement = td.getElement(contextFqn);
    if (!(contextElement instanceof Viewable<?> root)) {
      throw new IllegalArgumentException("Constraint context is not a class/viewable: " + contextFqn);
    }

    Map<String, ReferenceBinding> bindings = new LinkedHashMap<>();
    for (ConstraintExpression.Reference reference : expression.references()) {
      ReferenceBinding binding = switch (reference.kind()) {
        case ATTRIBUTE -> bindDirectAttribute(root, reference);
        case PATH -> bindPath(td, root, reference);
      };
      ReferenceBinding previous = bindings.putIfAbsent(reference.name(), binding);
      if (previous != null && !previous.equals(binding)) {
        throw new IllegalArgumentException(
            "Expression uses incompatible references with the same name: " + reference.name());
      }
    }
    return new ModelBinding(contextFqn, bindings);
  }

  /**
   * Materializes a complete assignment into root/target objects and association links.
   *
   * <p>Every expression reference must occur in {@code assignment}; use
   * {@link ConstraintExpressionEngine.Undefined#INSTANCE} explicitly for an undefined value.</p>
   */
  public static ObjectGraph synthesize(
      ModelBinding binding,
      Map<String, Object> assignment,
      String oidPrefix) {
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(assignment, "assignment");
    requireName(oidPrefix, "oidPrefix");

    for (String name : assignment.keySet()) {
      if (!binding.references().containsKey(name)) {
        throw new IllegalArgumentException("Assignment contains unknown expression reference: " + name);
      }
    }
    for (String name : binding.references().keySet()) {
      if (!assignment.containsKey(name)) {
        throw new IllegalArgumentException("Assignment is missing expression reference: " + name);
      }
    }

    String rootOid = oidPrefix + "_root";
    Map<String, Object> rootValues = new LinkedHashMap<>();
    Map<String, AssociationGroup> groups = new LinkedHashMap<>();

    for (ReferenceBinding reference : binding.references().values()) {
      Object raw = assignment.get(reference.reference().name());
      if (!reference.associationPath()) {
        Object scalar = normalizeScalarAssignment(raw, reference.domain());
        if (scalar == ConstraintExpressionEngine.Undefined.INSTANCE) {
          if (reference.domain().mandatory()) {
            throw new IllegalArgumentException(
                "Mandatory attribute cannot be undefined: " + reference.reference().name());
          }
        } else {
          rootValues.put(reference.attributeName(), scalar);
        }
        continue;
      }

      AssociationBinding association = reference.association();
      AssociationGroup group = groups.computeIfAbsent(
          association.groupKey(),
          key -> new AssociationGroup(association, reference.reference().type().collection()));
      if (group.collection != reference.reference().type().collection()) {
        throw new IllegalArgumentException(
            "The same association role cannot be used as both scalar and collection path: "
                + reference.reference().name());
      }
      group.references.add(new AssignedReference(
          reference,
          normalizePathAssignment(raw, reference)));
    }

    List<GraphObject> objects = new ArrayList<>();
    objects.add(new GraphObject(binding.contextFqn(), rootOid, rootValues));
    List<GraphLink> links = new ArrayList<>();

    int groupIndex = 1;
    for (AssociationGroup group : groups.values()) {
      int count = targetCount(group);
      for (int targetIndex = 0; targetIndex < count; targetIndex++) {
        Map<String, Object> targetValues = new LinkedHashMap<>();
        for (AssignedReference assigned : group.references) {
          List<Object> values = assigned.values();
          if (values == null) {
            if (assigned.binding().domain().mandatory()) {
              throw new IllegalArgumentException(
                  "Target attribute cannot be undefined while its association target exists: "
                      + assigned.binding().reference().name());
            }
            continue;
          }
          if (targetIndex >= values.size()) {
            throw new IllegalArgumentException(
                "Association-path assignments sharing role '" + group.association.roleName()
                    + "' must have the same number of target values.");
          }
          targetValues.put(assigned.binding().attributeName(), values.get(targetIndex));
        }

        String targetOid = oidPrefix + "_p" + groupIndex + "_" + (targetIndex + 1);
        objects.add(new GraphObject(group.association.targetClassFqn(), targetOid, targetValues));
        Map<String, String> roles = new LinkedHashMap<>();
        roles.put(group.association.roleName(), targetOid);
        roles.put(group.association.oppositeRoleName(), rootOid);
        links.add(new GraphLink(group.association.associationFqn(), roles));
      }
      groupIndex++;
    }

    return new ObjectGraph(objects, links);
  }

  private static ReferenceBinding bindDirectAttribute(
      Viewable<?> root,
      ConstraintExpression.Reference reference) {
    if (reference.type().collection()) {
      throw new IllegalArgumentException(
          "Direct multi-valued attributes are not supported by object-graph synthesis: " + reference.name());
    }
    AttributeDef attribute = findAttribute(root, reference.name());
    if (attribute == null) {
      throw new IllegalArgumentException(
          "Direct attribute not found in constraint context: " + reference.name());
    }
    ValueDomain domain = valueDomain(
        attribute.getDomainOrDerivedDomain(), reference.type().scalarKind());
    return new ReferenceBinding(reference, domain, attribute.getName(), null);
  }

  private static ReferenceBinding bindPath(
      TransferDescription td,
      Viewable<?> root,
      ConstraintExpression.Reference reference) {
    try {
      ObjectPath objectPath = Ili23Parser.parseObjectOrAttributePath(td, root, reference.name());
      if (!matchesParsedPath(objectPath, reference.name())) {
        throw new IllegalArgumentException("Unable to resolve expression path: " + reference.name());
      }
      PathEl[] elements = objectPath.getPathElements();
      if (elements.length != 2 || !(elements[1] instanceof AttributeRef attributeRef)) {
        throw new IllegalArgumentException(
            "Object-graph synthesis currently supports one association step Role->Attribute: "
                + reference.name());
      }

      RoleDef role;
      if (elements[0] instanceof PathElAssocRole associationRole) {
        role = associationRole.getRole();
      } else if (elements[0] instanceof PathElAbstractClassRole classRole) {
        role = classRole.getRole();
      } else {
        throw new IllegalArgumentException(
            "Path does not start with an association role: " + reference.name());
      }
      if (!(role.getContainer() instanceof AssociationDef association)
          || role.getOppEnd() == null
          || role.getDestination() == null) {
        throw new IllegalArgumentException(
            "Association metadata is incomplete for path: " + reference.name());
      }

      Cardinality cardinality = role.getCardinality();
      long minimum = cardinality != null ? cardinality.getMinimum() : 1;
      boolean unbounded = cardinality != null && cardinality.getMaximum() == Cardinality.UNBOUND;
      long maximum = cardinality != null ? cardinality.getMaximum() : 1;
      if (reference.type().collection()) {
        if (!unbounded && maximum <= 1) {
          throw new IllegalArgumentException(
              "Collection IR path requires a multi-valued association role: " + reference.name());
        }
      } else if (unbounded || maximum > 1) {
        throw new IllegalArgumentException(
            "Scalar IR path cannot navigate a multi-valued association role: " + reference.name());
      }

      AttributeDef endpoint = attributeRef.getAttr();
      ValueDomain domain = valueDomain(
          endpoint.getDomainOrDerivedDomain(), reference.type().scalarKind());
      AssociationBinding associationBinding = new AssociationBinding(
          association.getScopedName(null),
          role.getName(),
          role.getOppEnd().getName(),
          role.getDestination().getScopedName(null),
          minimum,
          maximum,
          unbounded);
      return new ReferenceBinding(reference, domain, endpoint.getName(), associationBinding);
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalArgumentException(
          "Unable to resolve expression path '" + reference.name() + "': " + ex.getMessage(), ex);
    }
  }

  private static @Nullable AttributeDef findAttribute(Viewable<?> root, String name) {
    Iterator<Extendable> attributes = root.getAttributes();
    while (attributes.hasNext()) {
      Extendable element = attributes.next();
      if (element instanceof AttributeDef attribute && name.equals(attribute.getName())) {
        return attribute;
      }
    }
    return null;
  }

  private static ValueDomain valueDomain(
      Type declared,
      ConstraintExpression.ScalarKind expectedKind) {
    Type real = Type.findReal(declared);
    boolean mandatory = mandatory(declared);
    if (real instanceof NumericType numeric) {
      requireKind(expectedKind, ConstraintExpression.ScalarKind.NUMERIC);
      BigDecimal minimum = numeric.getMinimum() != null
          ? new BigDecimal(numeric.getMinimum().toString())
          : null;
      BigDecimal maximum = numeric.getMaximum() != null
          ? new BigDecimal(numeric.getMaximum().toString())
          : null;
      int scale = 0;
      if (minimum != null) {
        scale = Math.max(scale, Math.max(0, minimum.scale()));
      }
      if (maximum != null) {
        scale = Math.max(scale, Math.max(0, maximum.scale()));
      }
      return new ValueDomain(
          ConstraintExpression.ScalarKind.NUMERIC,
          new NumericDomain(minimum, maximum, BigDecimal.ONE.movePointLeft(scale)),
          List.of(),
          mandatory);
    }
    if (declared.isBoolean() || real.isBoolean()) {
      requireKind(expectedKind, ConstraintExpression.ScalarKind.BOOLEAN);
      return new ValueDomain(
          ConstraintExpression.ScalarKind.BOOLEAN, null, List.of("false", "true"), mandatory);
    }
    if (real instanceof EnumerationType enumeration) {
      requireKind(expectedKind, ConstraintExpression.ScalarKind.ENUM);
      return new ValueDomain(
          ConstraintExpression.ScalarKind.ENUM,
          null,
          new ArrayList<>(enumeration.getValues()),
          mandatory);
    }
    if (real instanceof TextType) {
      if (expectedKind != ConstraintExpression.ScalarKind.TEXT
          && expectedKind != ConstraintExpression.ScalarKind.MTEXT) {
        throw new IllegalArgumentException(
            "Model TEXT attribute is incompatible with IR type " + expectedKind + ".");
      }
      return new ValueDomain(expectedKind, null, List.of(), mandatory);
    }
    throw new IllegalArgumentException(
        "Object-graph synthesis does not support model type " + real.getClass().getSimpleName() + ".");
  }

  private static boolean mandatory(Type declared) {
    if (declared.isMandatoryConsideringAliases()) {
      return true;
    }
    Cardinality cardinality = declared.getCardinality();
    return cardinality != null && cardinality.getMinimum() > 0;
  }

  private static void requireKind(
      ConstraintExpression.ScalarKind actual,
      ConstraintExpression.ScalarKind expected) {
    if (actual != expected && actual != ConstraintExpression.ScalarKind.UNKNOWN) {
      throw new IllegalArgumentException(
          "Model value type " + expected + " is incompatible with IR type " + actual + ".");
    }
  }

  private static @Nullable List<Object> normalizePathAssignment(
      @Nullable Object raw,
      ReferenceBinding reference) {
    if (raw == null || raw == ConstraintExpressionEngine.Undefined.INSTANCE) {
      return null;
    }
    if (reference.reference().type().collection()) {
      if (!(raw instanceof Collection<?> collection)) {
        throw new IllegalArgumentException(
            "Collection path requires a collection assignment: " + reference.reference().name());
      }
      List<Object> result = new ArrayList<>();
      for (Object value : collection) {
        Object scalar = normalizeScalarAssignment(value, reference.domain());
        if (scalar == ConstraintExpressionEngine.Undefined.INSTANCE) {
          throw new IllegalArgumentException(
              "Collection path elements must be defined scalar values: " + reference.reference().name());
        }
        result.add(scalar);
      }
      return List.copyOf(result);
    }
    Object scalar = normalizeScalarAssignment(raw, reference.domain());
    return scalar == ConstraintExpressionEngine.Undefined.INSTANCE ? null : List.of(scalar);
  }

  private static Object normalizeScalarAssignment(@Nullable Object raw, ValueDomain domain) {
    if (raw == null || raw == ConstraintExpressionEngine.Undefined.INSTANCE) {
      return ConstraintExpressionEngine.Undefined.INSTANCE;
    }
    return switch (domain.kind()) {
      case NUMERIC -> normalizeNumeric(raw, domain.numeric());
      case BOOLEAN -> {
        if (!(raw instanceof Boolean)) {
          throw new IllegalArgumentException("BOOLEAN assignment requires a Boolean value: " + raw);
        }
        yield raw;
      }
      case ENUM -> {
        String value = String.valueOf(raw);
        if (value.startsWith("#")) {
          value = value.substring(1);
        }
        if (!domain.values().contains(value)) {
          throw new IllegalArgumentException("Enum assignment is outside the model domain: " + raw);
        }
        yield value;
      }
      case TEXT, MTEXT -> {
        if (!(raw instanceof String)) {
          throw new IllegalArgumentException("TEXT assignment requires a String value: " + raw);
        }
        yield raw;
      }
      default -> throw new IllegalArgumentException(
          "Object-graph synthesis does not support scalar kind " + domain.kind() + ".");
    };
  }

  private static BigDecimal normalizeNumeric(Object raw, NumericDomain domain) {
    BigDecimal value;
    try {
      value = raw instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(raw));
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("NUMERIC assignment is not numeric: " + raw, ex);
    }
    if (!domain.contains(value)) {
      throw new IllegalArgumentException("NUMERIC assignment is outside the model domain: " + raw);
    }
    return value;
  }

  private static int targetCount(AssociationGroup group) {
    int definedCount = -1;
    boolean hasUndefined = false;
    for (AssignedReference reference : group.references) {
      if (reference.values() == null) {
        hasUndefined = true;
        continue;
      }
      int count = reference.values().size();
      if (definedCount < 0) {
        definedCount = count;
      } else if (definedCount != count) {
        throw new IllegalArgumentException(
            "Association-path assignments sharing role '" + group.association.roleName()
                + "' must have the same number of target values.");
      }
    }

    int count;
    if (definedCount >= 0) {
      count = definedCount;
    } else if (group.association.minimum() > 0) {
      count = Math.toIntExact(group.association.minimum());
    } else {
      count = 0;
    }

    if (group.collection && hasUndefined && count > 0) {
      throw new IllegalArgumentException(
          "Undefined collection path cannot share existing association targets for role '"
              + group.association.roleName() + "'.");
    }
    if (count < group.association.minimum()) {
      throw new IllegalArgumentException(
          "Assignment creates " + count + " targets but association role '"
              + group.association.roleName() + "' requires at least " + group.association.minimum() + ".");
    }
    if (!group.association.unbounded() && count > group.association.maximum()) {
      throw new IllegalArgumentException(
          "Assignment creates " + count + " targets but association role '"
              + group.association.roleName() + "' allows at most " + group.association.maximum() + ".");
    }
    return count;
  }

  private static boolean matchesParsedPath(ObjectPath objectPath, String path) {
    if (objectPath == null || objectPath.isDirty()) {
      return false;
    }
    String[] segments = path.split("->", -1);
    PathEl[] elements = objectPath.getPathElements();
    if (elements == null || elements.length != segments.length) {
      return false;
    }
    for (int i = 0; i < segments.length; i++) {
      if (elements[i] == null || !segments[i].trim().equals(elements[i].getName())) {
        return false;
      }
    }
    return true;
  }

  private static void requireName(@Nullable String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank.");
    }
  }
}
