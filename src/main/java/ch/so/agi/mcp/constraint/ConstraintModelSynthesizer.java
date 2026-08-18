package ch.so.agi.mcp.constraint;

import ch.interlis.ili2c.metamodel.AbstractClassDef;
import ch.interlis.ili2c.metamodel.AssociationDef;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.AttributeRef;
import ch.interlis.ili2c.metamodel.Cardinality;
import ch.interlis.ili2c.metamodel.CompositionType;
import ch.interlis.ili2c.metamodel.Element;
import ch.interlis.ili2c.metamodel.EnumerationType;
import ch.interlis.ili2c.metamodel.Extendable;
import ch.interlis.ili2c.metamodel.NumericType;
import ch.interlis.ili2c.metamodel.ObjectPath;
import ch.interlis.ili2c.metamodel.PathEl;
import ch.interlis.ili2c.metamodel.PathElAbstractClassRole;
import ch.interlis.ili2c.metamodel.PathElAssocRole;
import ch.interlis.ili2c.metamodel.PathElRefAttr;
import ch.interlis.ili2c.metamodel.ReferenceType;
import ch.interlis.ili2c.metamodel.RoleDef;
import ch.interlis.ili2c.metamodel.Table;
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
import java.util.Comparator;
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
 * it into root/target objects, references, embedded structures and association links. Shared path
 * prefixes are materialized only once.</p>
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
      return unbounded || maximum > cap ? cap : (int) maximum;
    }
  }

  public enum NavigationKind {
    ASSOCIATION,
    REFERENCE,
    COMPOSITION
  }

  /** One navigational step before the scalar endpoint attribute. */
  public record NavigationBinding(
      NavigationKind kind,
      String name,
      String targetClassFqn,
      long minimum,
      long maximum,
      boolean unbounded,
      @Nullable AssociationBinding association) {

    public NavigationBinding {
      Objects.requireNonNull(kind, "kind");
      requireName(name, "name");
      requireName(targetClassFqn, "targetClassFqn");
      if (minimum < 0 || (!unbounded && maximum < minimum)) {
        throw new IllegalArgumentException("Invalid navigation cardinality.");
      }
      if (kind == NavigationKind.ASSOCIATION && association == null) {
        throw new IllegalArgumentException("Association navigation requires association metadata.");
      }
    }

    public boolean multiValued() {
      return unbounded || maximum > 1;
    }
  }

  public record ReferenceBinding(
      ConstraintExpression.Reference reference,
      ValueDomain domain,
      String attributeName,
      @Nullable AssociationBinding association,
      List<NavigationBinding> navigation) {

    public ReferenceBinding {
      Objects.requireNonNull(reference, "reference");
      Objects.requireNonNull(domain, "domain");
      requireName(attributeName, "attributeName");
      navigation = navigation == null ? List.of() : List.copyOf(navigation);
    }

    public ReferenceBinding(
        ConstraintExpression.Reference reference,
        ValueDomain domain,
        String attributeName,
        @Nullable AssociationBinding association) {
      this(
          reference,
          domain,
          attributeName,
          association,
          association == null
              ? List.of()
              : List.of(new NavigationBinding(
                  NavigationKind.ASSOCIATION,
                  association.roleName(),
                  association.targetClassFqn(),
                  association.minimum(),
                  association.maximum(),
                  association.unbounded(),
                  association)));
    }

    public boolean associationPath() {
      return navigation.stream().anyMatch(step -> step.kind() == NavigationKind.ASSOCIATION);
    }

    public boolean navigatedPath() {
      return !navigation.isEmpty();
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
      Map<String, Object> values,
      Map<String, String> references) {

    public GraphObject {
      requireName(classFqn, "classFqn");
      requireName(oid, "oid");
      values = values == null
          ? Map.of()
          : Collections.unmodifiableMap(new LinkedHashMap<>(values));
      references = references == null
          ? Map.of()
          : Collections.unmodifiableMap(new LinkedHashMap<>(references));
    }

    public GraphObject(String classFqn, String oid, Map<String, Object> values) {
      this(classFqn, oid, values, Map.of());
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

  private interface MutableCarrier {
    String key();
    Map<String, Object> values();
  }

  private static final class MutableObject implements MutableCarrier {
    private final String key;
    private final String classFqn;
    private final String oid;
    private final Map<String, Object> values = new LinkedHashMap<>();
    private final Map<String, String> references = new LinkedHashMap<>();

    private MutableObject(String key, String classFqn, String oid) {
      this.key = key;
      this.classFqn = classFqn;
      this.oid = oid;
    }

    @Override
    public String key() {
      return key;
    }

    @Override
    public Map<String, Object> values() {
      return values;
    }
  }

  private static final class MutableStructure implements MutableCarrier {
    private final String key;
    private final Map<String, Object> values = new LinkedHashMap<>();

    private MutableStructure(String key) {
      this.key = key;
    }

    @Override
    public String key() {
      return key;
    }

    @Override
    public Map<String, Object> values() {
      return values;
    }
  }

  private static final class GraphBuilder {
    private final String oidPrefix;
    private final MutableObject root;
    private final List<MutableObject> objects = new ArrayList<>();
    private final List<GraphLink> links = new ArrayList<>();
    private final Map<String, List<MutableCarrier>> children = new LinkedHashMap<>();
    private int objectIndex;

    private GraphBuilder(String contextFqn, String oidPrefix) {
      this.oidPrefix = oidPrefix;
      root = new MutableObject("root", contextFqn, oidPrefix + "_root");
      objects.add(root);
    }

    private List<MutableCarrier> children(
        MutableCarrier parent,
        NavigationBinding step,
        int count) {
      String key = parent.key() + "/" + step.kind() + ":" + step.name();
      List<MutableCarrier> existing = children.get(key);
      if (existing != null) {
        if (existing.size() != count) {
          throw new IllegalArgumentException(
              "Shared path prefix '" + step.name() + "' requires incompatible target counts "
                  + existing.size() + " and " + count + ".");
        }
        return existing;
      }
      validateCount(step, count);
      List<MutableCarrier> created = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        created.add(createChild(parent, step, key + "[" + i + "]"));
      }
      List<MutableCarrier> frozen = List.copyOf(created);
      children.put(key, frozen);
      attachStructures(parent, step, frozen);
      return frozen;
    }

    private @Nullable List<MutableCarrier> existingChildren(
        MutableCarrier parent,
        NavigationBinding step) {
      return children.get(parent.key() + "/" + step.kind() + ":" + step.name());
    }

    private MutableCarrier createChild(
        MutableCarrier parent,
        NavigationBinding step,
        String key) {
      return switch (step.kind()) {
        case ASSOCIATION -> createAssociationChild(parent, step, key);
        case REFERENCE -> createReferenceChild(parent, step, key);
        case COMPOSITION -> new MutableStructure(key);
      };
    }

    private MutableCarrier createAssociationChild(
        MutableCarrier parent,
        NavigationBinding step,
        String key) {
      if (!(parent instanceof MutableObject source)) {
        throw new IllegalArgumentException("Association navigation from an embedded structure is not supported.");
      }
      AssociationBinding association = step.association();
      String targetOid = nextOid();
      MutableObject target = new MutableObject(key, step.targetClassFqn(), targetOid);
      objects.add(target);
      Map<String, String> roles = new LinkedHashMap<>();
      roles.put(association.roleName(), targetOid);
      roles.put(association.oppositeRoleName(), source.oid);
      links.add(new GraphLink(association.associationFqn(), roles));
      return target;
    }

    private MutableCarrier createReferenceChild(
        MutableCarrier parent,
        NavigationBinding step,
        String key) {
      String targetOid = nextOid();
      MutableObject target = new MutableObject(key, step.targetClassFqn(), targetOid);
      objects.add(target);
      if (parent instanceof MutableObject source) {
        source.references.put(step.name(), targetOid);
      } else if (parent instanceof MutableStructure source) {
        source.values.put(step.name(), targetOid);
      } else {
        throw new IllegalArgumentException("Unsupported reference-attribute parent graph node.");
      }
      return target;
    }

    private void attachStructures(
        MutableCarrier parent,
        NavigationBinding step,
        List<MutableCarrier> created) {
      if (step.kind() != NavigationKind.COMPOSITION || created.isEmpty()) {
        return;
      }
      List<Map<String, Object>> values = created.stream()
          .map(MutableCarrier::values)
          .toList();
      if (!step.multiValued() && values.size() == 1) {
        parent.values().put(step.name(), values.getFirst());
      } else {
        parent.values().put(step.name(), values);
      }
    }

    private String nextOid() {
      objectIndex++;
      return oidPrefix + "_n" + objectIndex;
    }

    private ObjectGraph finish() {
      List<GraphObject> result = objects.stream()
          .map(object -> new GraphObject(
              object.classFqn,
              object.oid,
              object.values,
              object.references))
          .toList();
      return new ObjectGraph(result, links);
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

  /** Materializes a complete solver assignment into an INTERLIS-shaped object graph. */
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

    GraphBuilder graph = new GraphBuilder(binding.contextFqn(), oidPrefix);
    List<ReferenceBinding> references = new ArrayList<>(binding.references().values());
    references.sort(Comparator
        .comparingInt((ReferenceBinding reference) -> isUndefinedAssignment(
            assignment.get(reference.reference().name())) ? 1 : 0)
        .thenComparingInt(reference -> reference.navigation().size()));

    for (ReferenceBinding reference : references) {
      Object raw = assignment.get(reference.reference().name());
      if (!reference.navigatedPath()) {
        applyDirectAttribute(graph.root, reference, raw);
      } else {
        applyPath(graph, reference, raw);
      }
    }
    return graph.finish();
  }

  private static void applyDirectAttribute(
      MutableObject root,
      ReferenceBinding reference,
      @Nullable Object raw) {
    Object scalar = normalizeScalarAssignment(raw, reference.domain());
    if (scalar == ConstraintExpressionEngine.Undefined.INSTANCE) {
      if (reference.domain().mandatory()) {
        throw new IllegalArgumentException(
            "Mandatory attribute cannot be undefined: " + reference.reference().name());
      }
      return;
    }
    root.values.put(reference.attributeName(), scalar);
  }

  private static void applyPath(
      GraphBuilder graph,
      ReferenceBinding reference,
      @Nullable Object raw) {
    List<Object> values = normalizePathAssignment(raw, reference);
    List<MutableCarrier> carriers = List.of(graph.root);
    int multiIndex = multiValuedStepIndex(reference.navigation());

    if (values == null) {
      carriers = materializeUndefinedPath(graph, reference, carriers);
      if (!carriers.isEmpty() && reference.domain().mandatory()) {
        throw new IllegalArgumentException(
            "Mandatory endpoint attribute cannot be undefined while its path exists: "
                + reference.reference().name());
      }
      return;
    }

    for (int stepIndex = 0; stepIndex < reference.navigation().size(); stepIndex++) {
      NavigationBinding step = reference.navigation().get(stepIndex);
      int count = stepIndex == multiIndex ? values.size() : 1;
      List<MutableCarrier> next = new ArrayList<>();
      for (MutableCarrier carrier : carriers) {
        next.addAll(graph.children(carrier, step, count));
      }
      carriers = List.copyOf(next);
    }

    if (reference.reference().type().collection()) {
      if (carriers.size() != values.size()) {
        throw new IllegalArgumentException(
            "Collection path materialized " + carriers.size() + " endpoints for " + values.size()
                + " assigned values: " + reference.reference().name());
      }
      for (int i = 0; i < values.size(); i++) {
        carriers.get(i).values().put(reference.attributeName(), values.get(i));
      }
    } else {
      if (carriers.size() != 1 || values.size() != 1) {
        throw new IllegalArgumentException(
            "Scalar path must materialize exactly one endpoint: " + reference.reference().name());
      }
      carriers.getFirst().values().put(reference.attributeName(), values.getFirst());
    }
  }

  private static List<MutableCarrier> materializeUndefinedPath(
      GraphBuilder graph,
      ReferenceBinding reference,
      List<MutableCarrier> initial) {
    List<MutableCarrier> carriers = initial;
    for (NavigationBinding step : reference.navigation()) {
      List<MutableCarrier> next = new ArrayList<>();
      for (MutableCarrier carrier : carriers) {
        List<MutableCarrier> existing = graph.existingChildren(carrier, step);
        if (existing != null) {
          next.addAll(existing);
        } else if (step.minimum() > 0) {
          next.addAll(graph.children(carrier, step, Math.toIntExact(step.minimum())));
        }
      }
      carriers = List.copyOf(next);
      if (carriers.isEmpty()) {
        return carriers;
      }
    }
    return carriers;
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
    return new ReferenceBinding(reference, domain, attribute.getName(), null, List.of());
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
      if (elements.length < 2 || !(elements[elements.length - 1] instanceof AttributeRef endpointRef)) {
        throw new IllegalArgumentException(
            "Object-graph synthesis requires a navigated path ending in a scalar attribute: "
                + reference.name());
      }

      List<NavigationBinding> navigation = new ArrayList<>();
      for (int i = 0; i < elements.length - 1; i++) {
        navigation.add(navigation(elements[i], reference.name()));
      }
      long multiValuedSteps = navigation.stream().filter(NavigationBinding::multiValued).count();
      if (multiValuedSteps > 1) {
        throw new IllegalArgumentException(
            "Object-graph synthesis currently supports at most one multi-valued navigation step: "
                + reference.name());
      }
      boolean collection = multiValuedSteps == 1;
      if (reference.type().collection() != collection) {
        throw new IllegalArgumentException(
            "IR collection/scalar path shape does not match model navigation cardinality: "
                + reference.name());
      }

      AttributeDef endpoint = endpointRef.getAttr();
      if (Type.findReal(endpoint.getDomainOrDerivedDomain()) instanceof CompositionType
          || Type.findReal(endpoint.getDomainOrDerivedDomain()) instanceof ReferenceType) {
        throw new IllegalArgumentException(
            "Constraint path endpoint must be a scalar attribute: " + reference.name());
      }
      ValueDomain domain = valueDomain(
          endpoint.getDomainOrDerivedDomain(), reference.type().scalarKind());
      AssociationBinding solverAssociation = solverAssociation(reference, navigation);
      return new ReferenceBinding(
          reference,
          domain,
          endpoint.getName(),
          solverAssociation,
          navigation);
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalArgumentException(
          "Unable to resolve expression path '" + reference.name() + "': " + ex.getMessage(), ex);
    }
  }

  private static NavigationBinding navigation(PathEl element, String fullPath) {
    RoleDef role = role(element);
    if (role != null) {
      if (!(role.getContainer() instanceof AssociationDef association)
          || role.getOppEnd() == null
          || role.getDestination() == null) {
        throw new IllegalArgumentException(
            "Association metadata is incomplete for path: " + fullPath);
      }
      Cardinality cardinality = role.getCardinality();
      long minimum = cardinality != null ? cardinality.getMinimum() : 1;
      boolean unbounded = cardinality != null && cardinality.getMaximum() == Cardinality.UNBOUND;
      long maximum = cardinality != null ? cardinality.getMaximum() : 1;
      AssociationBinding binding = new AssociationBinding(
          association.getScopedName(null),
          role.getName(),
          role.getOppEnd().getName(),
          role.getDestination().getScopedName(null),
          minimum,
          maximum,
          unbounded);
      return new NavigationBinding(
          NavigationKind.ASSOCIATION,
          role.getName(),
          role.getDestination().getScopedName(null),
          minimum,
          maximum,
          unbounded,
          binding);
    }

    if (element instanceof PathElRefAttr referenceElement) {
      AttributeDef attribute = referenceElement.getAttr();
      Type declared = attribute.getDomainOrDerivedDomain();
      Type real = Type.findReal(declared);
      if (!(real instanceof ReferenceType referenceType)) {
        throw new IllegalArgumentException("Reference path element is not a REFERENCE attribute: " + fullPath);
      }
      AbstractClassDef target = referenceType.getReferred();
      if (!(target instanceof Table table) || !table.isIdentifiable()) {
        throw new IllegalArgumentException(
            "Reference path target is not an identifiable class: " + fullPath);
      }
      return new NavigationBinding(
          NavigationKind.REFERENCE,
          attribute.getName(),
          table.getScopedName(null),
          mandatory(declared) ? 1 : 0,
          1,
          false,
          null);
    }

    if (element instanceof AttributeRef attributeElement) {
      AttributeDef attribute = attributeElement.getAttr();
      Type declared = attribute.getDomainOrDerivedDomain();
      Type real = Type.findReal(declared);
      if (!(real instanceof CompositionType composition)) {
        throw new IllegalArgumentException(
            "Intermediate attribute path element is not a structure/composition: " + fullPath);
      }
      Cardinality cardinality = declared.getCardinality();
      long minimum = cardinality != null
          ? cardinality.getMinimum()
          : (declared.isMandatoryConsideringAliases() ? 1 : 0);
      boolean unbounded = cardinality != null && cardinality.getMaximum() == Cardinality.UNBOUND;
      long maximum = cardinality != null ? cardinality.getMaximum() : 1;
      Table component = composition.getComponentType();
      return new NavigationBinding(
          NavigationKind.COMPOSITION,
          attribute.getName(),
          component.getScopedName(null),
          minimum,
          maximum,
          unbounded,
          null);
    }

    throw new IllegalArgumentException(
        "Unsupported object-path navigation element " + element.getClass().getSimpleName()
            + " in " + fullPath + ".");
  }

  /**
   * Keeps the solver-facing legacy association cardinality without making synthesis depend on it.
   * For multi-step/reference/composition paths this may be a synthetic path-cardinality binding.
   */
  private static @Nullable AssociationBinding solverAssociation(
      ConstraintExpression.Reference reference,
      List<NavigationBinding> navigation) {
    if (navigation.size() == 1 && navigation.getFirst().association() != null) {
      return navigation.getFirst().association();
    }
    NavigationBinding multi = navigation.stream()
        .filter(NavigationBinding::multiValued)
        .findFirst()
        .orElse(null);
    boolean optional = navigation.stream().anyMatch(step -> step.minimum() == 0);
    AssociationBinding actual = navigation.stream()
        .map(NavigationBinding::association)
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
    if (multi == null && !optional) {
      return actual;
    }
    long minimum = optional ? 0 : multi != null ? multi.minimum() : 1;
    long maximum = multi != null ? multi.maximum() : 1;
    boolean unbounded = multi != null && multi.unbounded();
    String target = navigation.isEmpty()
        ? reference.name()
        : navigation.getLast().targetClassFqn();
    return new AssociationBinding(
        actual != null ? actual.associationFqn() : "PATH." + reference.name(),
        actual != null ? actual.roleName() : "pathTarget",
        actual != null ? actual.oppositeRoleName() : "pathRoot",
        target,
        minimum,
        maximum,
        unbounded);
  }

  private static int multiValuedStepIndex(List<NavigationBinding> navigation) {
    int result = -1;
    for (int i = 0; i < navigation.size(); i++) {
      if (navigation.get(i).multiValued()) {
        if (result >= 0) {
          throw new IllegalArgumentException("More than one multi-valued path step is not supported.");
        }
        result = i;
      }
    }
    return result;
  }

  private static void validateCount(NavigationBinding step, int count) {
    if (count < step.minimum()) {
      throw new IllegalArgumentException(
          "Path step '" + step.name() + "' requires at least " + step.minimum()
              + " targets but assignment creates " + count + ".");
    }
    if (!step.unbounded() && count > step.maximum()) {
      throw new IllegalArgumentException(
          "Path step '" + step.name() + "' allows at most " + step.maximum()
              + " targets but assignment creates " + count + ".");
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

  private static boolean isUndefinedAssignment(@Nullable Object raw) {
    return raw == null || raw == ConstraintExpressionEngine.Undefined.INSTANCE;
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

  private static @Nullable RoleDef role(PathEl element) {
    if (element instanceof PathElAssocRole associationRole) {
      return associationRole.getRole();
    }
    if (element instanceof PathElAbstractClassRole classRole) {
      return classRole.getRole();
    }
    return null;
  }

  private static void requireName(@Nullable String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank.");
    }
  }
}
