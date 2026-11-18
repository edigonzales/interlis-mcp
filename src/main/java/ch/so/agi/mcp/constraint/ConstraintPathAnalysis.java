package ch.so.agi.mcp.constraint;

import ch.interlis.ili2c.Ili2cException;
import ch.interlis.ili2c.metamodel.AbstractClassDef;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.AttributeRef;
import ch.interlis.ili2c.metamodel.Cardinality;
import ch.interlis.ili2c.metamodel.CompositionType;
import ch.interlis.ili2c.metamodel.Element;
import ch.interlis.ili2c.metamodel.EnumerationType;
import ch.interlis.ili2c.metamodel.NumericType;
import ch.interlis.ili2c.metamodel.ObjectPath;
import ch.interlis.ili2c.metamodel.ObjectType;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** Shared compiler-derived path facts for explicit resolution and bounded discovery. */
public final class ConstraintPathAnalysis {
  public static final String ATTRIBUTE_PATH_SEMANTICS = "ILI23_OBJECT_OR_ATTRIBUTE_PATH";
  private ConstraintPathAnalysis() {}

  public static Map<String, Object> resolveCompiledPath(TransferDescription td, String context, String path) {
    Element contextElement = td.getElement(context.trim());
    if (!(contextElement instanceof Viewable<?> root)) {
      throw new IllegalArgumentException("Context is not a class, structure, association or other viewable: " + context);
    }

    String normalizedPath = normalizePath(path);
    try {
      return describe(parse(td, root, normalizedPath));
    } catch (Ili2cException | RuntimeException ex) {
      InvalidPathDiagnostic diagnostic = diagnoseInvalidPath(td, root, normalizedPath);
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("valid", false);
      response.put("context", root.getScopedName());
      response.put("path", normalizedPath);
      response.put("pathSemantics", ATTRIBUTE_PATH_SEMANTICS);
      response.put("message", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
      response.put("failedSegment", diagnostic.failedSegment());
      response.put("failedSegmentIndex", diagnostic.failedSegmentIndex());
      response.put("candidates", diagnostic.candidates());
      return response;
    }
  }

  public static ObjectPath parse(TransferDescription td, Viewable<?> root, String path) throws Ili2cException {
    ObjectPath parsed = Ili23Parser.parseObjectOrAttributePath(td, root, path);
    if (!matchesParsedPath(parsed, path)) {
      throw new IllegalArgumentException("Invalid object/attribute path: " + path);
    }
    return parsed;
  }

  public static Map<String, Object> describe(ObjectPath path) {
    var steps = describeSteps(path.getPathElements());
    boolean collection = steps.stream().anyMatch(s -> Boolean.TRUE.equals(s.get("collection")));
    boolean missing = steps.stream().anyMatch(s -> Boolean.TRUE.equals(s.get("optional")));
    var limitations = new java.util.LinkedHashSet<String>();
    for (var element : path.getPathElements()) limitations.addAll(unsupported(element));
    var response = new LinkedHashMap<String, Object>();
    response.put("valid", true);
    response.put("context", path.getRoot().getScopedName());
    response.put("path", path.toString());
    response.put("pathSemantics", ATTRIBUTE_PATH_SEMANTICS);
    response.put("attributePath", path.isAttributePath());
    response.put("collection", collection);
    response.put("steps", steps);
    response.put("result", describeType(path.getType()));
    Boolean mayBeUndefined = missing ? Boolean.TRUE
        : steps.stream().anyMatch(s -> s.get("optional") == null) ? null : Boolean.FALSE;
    response.put("mayBeUndefined", mayBeUndefined);
    response.put("missingnessBasis", "DECLARED_CARDINALITIES_AND_OPTIONALITY_ONLY");
    response.put("limitations", List.copyOf(limitations));
    response.put("usageHints", limitations.isEmpty() ? usageHints(path, collection) : List.of());
    response.put("proofStatus", "NOT_RUN");
    return response;
  }

  private static List<Map<String, Object>> usageHints(ObjectPath path, boolean collection) {
    Type type = path.getType().resolveAliases();
    boolean scalar = type instanceof NumericType || type instanceof TextType || type instanceof EnumerationType;
    var hints = new ArrayList<Map<String, Object>>();
    var pathExpression = Map.of("kind", "PATH", "name", path.toString());
    if (path.isAttributePath() && scalar && !collection) {
      hints.add(Map.of("operation", "SINGLE_VALUE", "expression", path.getPathElements().length == 1
          ? Map.of("kind", "ATTRIBUTE", "name", path.toString()) : pathExpression,
          "note", "Einzelwert; optionale Schritte und Endwerte fachlich behandeln. Keine DEFINED-Pflicht abgeleitet."));
    }
    if (path.isAttributePath() && type instanceof NumericType && collection) {
      hints.add(Map.of("operation", "SUM", "semanticId", "COLLECTION_SUM",
          "expression", Map.of("kind", "FUNCTION", "functionOrigin", "STANDARD", "name", "COLLECTION_SUM",
              "children", List.of(pathExpression)),
          "note", "Numerischer Sammelpfad. Leere Menge und undefinierte Werte separat pruefen; keine Nullsumme voraussetzen."));
    }
    if (!path.isAttributePath() && navigationTarget(path.getLastPathEl()) instanceof Table table && table.isIdentifiable()) {
      hints.add(Map.of("operation", "OBJECT_COUNT", "expression", Map.of("kind", "OBJECT_COUNT",
          "objects", Map.of("kind", "PATH", "path", path.toString())),
          "note", "Zaehlt Pfadvorkommen von Klassenobjekten; mehrere Wege zur selben OID koennen mehrfach zaehlen. Kein DISTINCT."));
    }
    return hints;
  }

  private static List<Map<String, Object>> describeSteps(PathEl[] pathElements) {
    List<Map<String, Object>> steps = new ArrayList<>();
    for (int i = 0; i < pathElements.length; i++) {
      PathEl pathElement = pathElements[i];
      Map<String, Object> step = new LinkedHashMap<>();
      step.put("index", i);
      step.put("name", pathElement.getName());
      if (pathElement instanceof PathElAssocRole associationRole) {
        describeRole(step, associationRole.getRole());
      } else if (pathElement instanceof PathElAbstractClassRole classRole) {
        describeRole(step, classRole.getRole());
      } else if (pathElement instanceof PathElRefAttr referenceAttribute) {
        AttributeDef attribute = referenceAttribute.getAttr();
        step.put("name", attribute.getName());
        step.put("kind", "REFERENCE_ATTRIBUTE");
        step.put("target", referenceAttribute.getViewable().getScopedName());
        describeAttribute(step, attribute);
      } else if (pathElement instanceof AttributeRef attributeRef) {
        AttributeDef attribute = attributeRef.getAttr();
        Type type = attribute.getDomainResolvingAliases();
        step.put("name", attribute.getName());
        describeAttribute(step, attribute);
        if (type instanceof CompositionType composition) {
          step.put("kind", "STRUCTURE_ATTRIBUTE");
          step.put("target", composition.getComponentType().getScopedName());
        } else {
          step.put("kind", "ATTRIBUTE");
          step.put("type", describeType(attribute.getDomainOrDerivedDomain()));
        }
      } else {
        step.put("kind", pathElement.getClass().getSimpleName());
        Viewable<?> reached = pathElement.getViewable();
        if (reached != null) {
          step.put("target", reached.getScopedName());
        }
        step.put("collection", false);
        step.put("optional", null);
      }
      steps.add(step);
    }
    return steps;
  }

  private static void describeAttribute(Map<String, Object> step, AttributeDef attribute) {
    step.put("elementFqn", attribute.getScopedName());
    step.put("declaringContext", attribute.getContainer().getScopedName());
    Cardinality cardinality = attribute.getCardinality();
    // ili2c's effective alias mandatory flag may be stronger than the local cardinality.
    if (cardinality != null && cardinality.getMinimum() == 0
        && attribute.getDomainOrDerivedDomain().isMandatoryConsideringAliases()) {
      cardinality = new Cardinality(1, cardinality.getMaximum());
    }
    addCardinality(step, cardinality);
  }

  private static void describeRole(Map<String, Object> step, RoleDef role) {
    step.put("name", role.getName());
    step.put("kind", "ROLE");
    step.put("elementFqn", role.getScopedName());
    step.put("declaringContext", role.getContainer().getScopedName());
    if (role.getDestination() != null) {
      step.put("target", role.getDestination().getScopedName());
    }
    addCardinality(step, role.getCardinality());
  }

  private static void addCardinality(Map<String, Object> step, @Nullable Cardinality cardinality) {
    if (cardinality == null) {
      step.put("collection", false);
      step.put("optional", null);
      return;
    }
    step.put("cardinality", cardinality.toString());
    step.put("minimum", cardinality.getMinimum());
    step.put("maximum", cardinality.getMaximum() == Cardinality.UNBOUND ? "*" : cardinality.getMaximum());
    step.put("collection", cardinality.getMaximum() > 1);
    step.put("optional", cardinality.getMinimum() == 0);
  }

  private static Map<String, Object> describeType(@Nullable Type type) {
    if (type == null) {
      return Map.of("kind", "UNKNOWN");
    }
    Type real = type.resolveAliases();
    Map<String, Object> result = new LinkedHashMap<>();
    if (real instanceof NumericType numeric) {
      result.put("kind", "NUMERIC");
      if (numeric.getMinimum() != null && numeric.getMaximum() != null) {
        result.put("typeText", numeric.getMinimum() + ".." + numeric.getMaximum());
      } else {
        result.put("typeText", "NUMERIC");
      }
    } else if (real instanceof TextType text) {
      result.put("kind", text.isNormalized() ? "TEXT" : "MTEXT");
      result.put("typeText", text.getMaxLength() < 0 ? result.get("kind") : result.get("kind") + "*" + text.getMaxLength());
    } else if (real instanceof CompositionType composition) {
      result.put("kind", "COMPOSITION");
      result.put("target", composition.getComponentType().getScopedName());
      result.put("cardinality", composition.getCardinality().toString());
    } else if (real instanceof ReferenceType reference) {
      result.put("kind", "REFERENCE");
      result.put("target", reference.getReferred().getScopedName());
    } else {
      result.put("kind", real.getClass().getSimpleName());
      if (real instanceof ObjectType object && object.getRef() != null) result.put("target", object.getRef().getScopedName());
      if (real instanceof EnumerationType enumeration) result.put("values", enumeration.getValues());
    }
    return result;
  }

  private static InvalidPathDiagnostic diagnoseInvalidPath(TransferDescription td, Viewable<?> root, String path) {
    String[] segments = path.split("->", -1);
    Viewable<?> current = root;
    for (int i = 0; i < segments.length; i++) {
      String segment = segments[i].trim();
      String prefix = String.join("->", java.util.Arrays.copyOfRange(segments, 0, i + 1));
      try {
        ObjectPath parsed = Ili23Parser.parseObjectOrAttributePath(td, root, prefix);
        if (!matchesParsedPath(parsed, prefix)) {
          return new InvalidPathDiagnostic(segment, i, candidates(current));
        }
        if (i < segments.length - 1) {
          Viewable<?> reached = navigationTarget(parsed.getLastPathEl());
          if (reached != null) {
            current = reached;
          }
        }
      } catch (Exception ex) {
        return new InvalidPathDiagnostic(segment, i, candidates(current));
      }
    }
    return new InvalidPathDiagnostic("", -1, candidates(current));
  }

  private static boolean matchesParsedPath(ObjectPath objectPath, String path) {
    if (objectPath == null || objectPath.isDirty()) {
      return false;
    }
    String[] segments = path.split("->", -1);
    PathEl[] parsedElements = objectPath.getPathElements();
    if (parsedElements == null || parsedElements.length != segments.length) {
      return false;
    }
    for (int i = 0; i < segments.length; i++) {
      PathEl parsedElement = parsedElements[i];
      if (parsedElement == null || !segments[i].trim().equals(parsedElement.getName())) {
        return false;
      }
    }
    return true;
  }

  public static List<Map<String, Object>> candidates(Viewable<?> viewable) {
    var candidates = new TreeMap<String, Map<String, Object>>();
    var attributes = viewable.getAttributes();
    while (attributes.hasNext()) if (attributes.next() instanceof AttributeDef attribute) {
      candidates.put(attribute.getName(), Map.of("name", attribute.getName(), "kind", "ATTRIBUTE"));
    }
    var elements = viewable.getAttributesAndRoles();
    while (elements.hasNext()) if (elements.next() instanceof RoleDef role) {
      candidates.putIfAbsent(role.getName(), Map.of("name", role.getName(), "kind", "ROLE"));
    }
    if (viewable instanceof AbstractClassDef<?> clazz) {
      var roles = clazz.getOpposideRoles();
      while (roles.hasNext()) {
        var role = roles.next();
        candidates.putIfAbsent(role.getName(), Map.of("name", role.getName(), "kind", "ROLE"));
      }
    }
    return List.copyOf(candidates.values());
  }

  /** Scalar AttributeRef.getViewable() throws; only call it for navigable types. */
  public static @Nullable Viewable<?> navigationTarget(PathEl element) {
    if (element instanceof AttributeRef attribute) {
      return attribute.getAttr().getDomainResolvingAliases() instanceof CompositionType composition
          ? composition.getComponentType() : null;
    }
    if (element instanceof PathElRefAttr || element instanceof PathElAssocRole || element instanceof PathElAbstractClassRole) {
      return element.getViewable();
    }
    return null;
  }

  public static @Nullable AttributeDef attribute(PathEl element) {
    if (element instanceof AttributeRef ref) return ref.getAttr();
    if (element instanceof PathElRefAttr ref) return ref.getAttr();
    return null;
  }

  public static List<String> unsupported(PathEl element) {
    RoleDef role = element instanceof PathElAssocRole r ? r.getRole()
        : element instanceof PathElAbstractClassRole r ? r.getRole() : null;
    if (role != null) {
      var references = role.iteratorReference();
      if (references.hasNext()) references.next();
      if (references.hasNext()) return List.of("MULTIPLE_ROLE_TARGETS_UNSUPPORTED");
    } else if (!(element instanceof AttributeRef || element instanceof PathElRefAttr)) {
      return List.of("PATH_ELEMENT_UNSUPPORTED");
    }
    return List.of();
  }

  private static String normalizePath(String path) {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("Path is required.");
    }
    String normalized = path.trim();
    if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
      normalized = normalized.substring(1, normalized.length() - 1);
    }
    return normalized;
  }

  private record InvalidPathDiagnostic(String failedSegment, int failedSegmentIndex, List<Map<String, Object>> candidates) {
  }
}
