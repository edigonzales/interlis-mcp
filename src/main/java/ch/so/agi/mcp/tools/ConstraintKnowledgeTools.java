package ch.so.agi.mcp.tools;

import static ch.so.agi.mcp.constraint.ConstraintExpression.ArgumentSemantics.ATTRIBUTE_PATH;

import ch.interlis.ili2c.Ili2cException;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.AttributeRef;
import ch.interlis.ili2c.metamodel.Cardinality;
import ch.interlis.ili2c.metamodel.CompositionType;
import ch.interlis.ili2c.metamodel.Element;
import ch.interlis.ili2c.metamodel.NumericType;
import ch.interlis.ili2c.metamodel.ObjectPath;
import ch.interlis.ili2c.metamodel.PathEl;
import ch.interlis.ili2c.metamodel.PathElAbstractClassRole;
import ch.interlis.ili2c.metamodel.PathElAssocRole;
import ch.interlis.ili2c.metamodel.PathElRefAttr;
import ch.interlis.ili2c.metamodel.ReferenceType;
import ch.interlis.ili2c.metamodel.RoleDef;
import ch.interlis.ili2c.metamodel.TextType;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.ili2c.metamodel.Type;
import ch.interlis.ili2c.metamodel.Viewable;
import ch.interlis.ili2c.parser.Ili23Parser;
import ch.so.agi.mcp.constraint.ConstraintExpression.IliVersion;
import ch.so.agi.mcp.constraint.StandardFunctionRegistry;
import ch.so.agi.mcp.constraint.StandardFunctionRegistry.Family;
import ch.so.agi.mcp.constraint.StandardFunctionRegistry.StandardFunction;
import ch.so.agi.mcp.service.IliCompilerService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class ConstraintKnowledgeTools {

  private static final String ATTRIBUTE_PATH_SEMANTICS = "ILI23_OBJECT_OR_ATTRIBUTE_PATH";

  private final IliCompilerService compilerService;

  public ConstraintKnowledgeTools(MathTools mathTools, TextTools textTools, IliCompilerService compilerService) {
    this.compilerService = compilerService;
  }

  @McpTool(
      name = "listConstraintFunctions",
      description = "Listet bekannte Constraint-Funktionen mit Herkunft, stabiler semantischer ID, Parametern und semantischen Parametertypen. Math/Text stammen aus INTERLIS-Funktionsmodellen; Validator-Extensions und Modellfunktionen werden als eigene Herkunftskategorien unterschieden."
  )
  public Map<String, Object> listConstraintFunctions(
      @McpToolParam(description = "INTERLIS Sprachversion (2.3 oder 2.4)", required = false) @Nullable String iliVersion) {
    IliVersion version = normalizeIliVersion(iliVersion);
    List<Map<String, Object>> functions = new ArrayList<>();
    appendStandardFunctions(functions, StandardFunctionRegistry.functions(Family.MATH), version);
    appendStandardFunctions(functions, StandardFunctionRegistry.functions(Family.TEXT), version);

    return Map.of(
        "iliVersion", version.text(),
        "functions", functions,
        "constraintLanguage", List.of(
            languageConstruct("DEFINED", "Prueft, ob ein Wert definiert ist."),
            languageConstruct("AND", "Logische Konjunktion."),
            languageConstruct("OR", "Logische Disjunktion."),
            languageConstruct("NOT", "Logische Negation."),
            languageConstruct("IMPLIES", "Logische Implikation.")),
        "originKinds", Map.of(
            "LANGUAGE", "Sprachmittel aus INTERLIS selbst.",
            "STANDARD_FUNCTION_MODEL", "Funktion aus einem standardisierten INTERLIS-Funktionsmodell wie Math_V2 oder Text_V2.",
            "VALIDATOR_EXTENSION", "Zusaetzliche Laufzeitfunktion, die der Validator z. B. als InterlisFunction-Plugin bereitstellt; nicht automatisch portabel.",
            "MODEL_FUNCTION", "Funktion, die ein konkretes Fach-/Projektmodell deklariert; Laufzeitimplementierung separat pruefen."));
  }

  @McpTool(
      name = "resolveConstraintPath",
      description = "Loest einen String-Objekt-/Attributpfad im Kontext einer Klasse oder Association exakt mit dem ili2c-Ili23Parser auf. Geeignet insbesondere fuer attributePath-Parameter wie Math_V2.sum(\"Rolle->Attribut\"). Gibt Pfadschritte, Kardinalitaeten, Zieltyp und bei Fehlern moegliche Elemente zurueck."
  )
  public Map<String, Object> resolveConstraintPath(
      @McpToolParam(description = "Vollstaendiger INTERLIS-2 Modelltext", required = true) String modelText,
      @McpToolParam(description = "Vollqualifizierter Kontext, z. B. Modell.Topic.Klasse", required = true) String context,
      @McpToolParam(description = "Objekt-/Attributpfad, z. B. Nebenauspraegung->Gewichtung; aeussere Anfuehrungszeichen sind optional", required = true) String path,
      @McpToolParam(description = "Optionale MODELREPOS-/ilidirs-Definition", required = false) @Nullable String modelRepositories) {
    TransferDescription td = compilerService.compileOrThrow(modelText, modelRepositories, "constraint_path");
    Element contextElement = td.getElement(context.trim());
    if (!(contextElement instanceof Viewable<?> root)) {
      throw new IllegalArgumentException("Context is not a class, structure, association or other viewable: " + context);
    }

    String normalizedPath = normalizePath(path);
    try {
      ObjectPath objectPath = Ili23Parser.parseObjectOrAttributePath(td, root, normalizedPath);
      if (!matchesParsedPath(objectPath, normalizedPath)) {
        throw new IllegalArgumentException("Invalid object/attribute path: " + normalizedPath);
      }
      List<Map<String, Object>> steps = describeSteps(objectPath.getPathElements());
      Type resultType = objectPath.getType();
      boolean collection = steps.stream().anyMatch(step -> Boolean.TRUE.equals(step.get("collection")));
      Map<String, Object> response = new LinkedHashMap<>();
      response.put("valid", true);
      response.put("context", root.getScopedName());
      response.put("path", objectPath.toString());
      response.put("pathSemantics", ATTRIBUTE_PATH_SEMANTICS);
      response.put("attributePath", objectPath.isAttributePath());
      response.put("collection", collection);
      response.put("steps", steps);
      response.put("result", describeType(resultType));
      return response;
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

  private void appendStandardFunctions(
      List<Map<String, Object>> target,
      List<StandardFunction> functions,
      IliVersion version) {
    for (StandardFunction function : functions) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("name", function.qualifiedName(version));
      item.put("signature", function.signature(version));
      item.put("family", function.family() == Family.MATH ? "Math" : "Text");
      item.put("origin", "STANDARD_FUNCTION_MODEL");
      item.put("sourceModel", function.modelName(version));
      item.put("semanticId", function.semanticId());
      item.put("returns", function.declaredReturnType());
      item.put("parameters", function.parameters().stream().map(parameter -> Map.<String, Object>of(
          "name", parameter.name(),
          "type", parameter.declaredType(),
          "semanticType", parameter.semantics().name())).toList());
      boolean attributePath = function.parameters().stream()
          .anyMatch(parameter -> parameter.semantics() == ATTRIBUTE_PATH);
      if (attributePath) {
        item.put("pathSemantics", ATTRIBUTE_PATH_SEMANTICS);
        item.put("description", "Der attributePath-String wird von iox-ili im aktuellen Objektkontext mit Ili23Parser.parseObjectOrAttributePath geparst.");
        item.put("edgeCases", List.of("leere Zielmenge", "optionale Navigation", "undefinierte Endwerte", "mehrere Zielobjekte"));
      }
      target.add(item);
    }
  }

  private Map<String, Object> languageConstruct(String name, String description) {
    return Map.of("name", name, "origin", "LANGUAGE", "description", description);
  }

  private List<Map<String, Object>> describeSteps(PathEl[] pathElements) {
    List<Map<String, Object>> steps = new ArrayList<>();
    for (int i = 0; i < pathElements.length; i++) {
      PathEl pathElement = pathElements[i];
      Map<String, Object> step = new LinkedHashMap<>();
      step.put("index", i);
      if (pathElement instanceof PathElAssocRole associationRole) {
        describeRole(step, associationRole.getRole());
      } else if (pathElement instanceof PathElAbstractClassRole classRole) {
        describeRole(step, classRole.getRole());
      } else if (pathElement instanceof PathElRefAttr referenceAttribute) {
        AttributeDef attribute = referenceAttribute.getAttr();
        step.put("name", attribute.getName());
        step.put("kind", "REFERENCE_ATTRIBUTE");
        step.put("target", referenceAttribute.getViewable().getScopedName());
        step.put("collection", false);
      } else if (pathElement instanceof AttributeRef attributeRef) {
        AttributeDef attribute = attributeRef.getAttr();
        Type type = attribute.getDomainResolvingAliases();
        step.put("name", attribute.getName());
        if (type instanceof CompositionType composition) {
          step.put("kind", "STRUCTURE_ATTRIBUTE");
          step.put("target", composition.getComponentType().getScopedName());
          addCardinality(step, composition.getCardinality());
        } else {
          step.put("kind", "ATTRIBUTE");
          step.put("collection", false);
          step.put("type", describeType(attribute.getDomainOrDerivedDomain()));
        }
      } else {
        step.put("kind", pathElement.getClass().getSimpleName());
        Viewable<?> reached = pathElement.getViewable();
        if (reached != null) {
          step.put("target", reached.getScopedName());
        }
        step.put("collection", false);
      }
      steps.add(step);
    }
    return steps;
  }

  private void describeRole(Map<String, Object> step, RoleDef role) {
    step.put("name", role.getName());
    step.put("kind", "ROLE");
    if (role.getDestination() != null) {
      step.put("target", role.getDestination().getScopedName());
    }
    addCardinality(step, role.getCardinality());
  }

  private void addCardinality(Map<String, Object> step, @Nullable Cardinality cardinality) {
    if (cardinality == null) {
      step.put("collection", false);
      return;
    }
    step.put("cardinality", cardinality.toString());
    step.put("minimum", cardinality.getMinimum());
    step.put("maximum", cardinality.getMaximum() == Cardinality.UNBOUND ? "*" : cardinality.getMaximum());
    step.put("collection", cardinality.getMaximum() > 1);
  }

  private Map<String, Object> describeType(@Nullable Type type) {
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
    }
    return result;
  }

  private InvalidPathDiagnostic diagnoseInvalidPath(TransferDescription td, Viewable<?> root, String path) {
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
          Viewable<?> reached = parsed.getViewable();
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

  private boolean matchesParsedPath(ObjectPath objectPath, String path) {
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

  private List<Map<String, Object>> candidates(Viewable<?> viewable) {
    List<Map<String, Object>> candidates = new ArrayList<>();
    Iterator<Element> iterator = viewable.getAttributesAndRoles();
    while (iterator.hasNext()) {
      Element element = iterator.next();
      if (element instanceof AttributeDef attribute) {
        candidates.add(Map.of("name", attribute.getName(), "kind", "ATTRIBUTE"));
      } else if (element instanceof RoleDef role) {
        candidates.add(Map.of("name", role.getName(), "kind", "ROLE"));
      }
    }
    candidates.sort(Comparator.comparing(candidate -> String.valueOf(candidate.get("name"))));
    return candidates;
  }

  private String normalizePath(String path) {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("Path is required.");
    }
    String normalized = path.trim();
    if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
      normalized = normalized.substring(1, normalized.length() - 1);
    }
    return normalized;
  }

  private IliVersion normalizeIliVersion(@Nullable String iliVersion) {
    String version = iliVersion == null || iliVersion.isBlank() ? "2.4" : iliVersion.trim();
    return switch (version) {
      case "2.3" -> IliVersion.ILI_23;
      case "2.4" -> IliVersion.ILI_24;
      default -> throw new IllegalArgumentException("iliVersion must be '2.3' oder '2.4'.");
    };
  }

  private record InvalidPathDiagnostic(String failedSegment, int failedSegmentIndex, List<Map<String, Object>> candidates) {
  }
}
