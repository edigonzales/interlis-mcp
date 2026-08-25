package ch.so.agi.mcp.analysis;

import ch.ehi.basics.settings.Settings;
import ch.interlis.ili2c.generator.Interlis2Generator;
import ch.interlis.ili2c.metamodel.AbstractCoordType;
import ch.interlis.ili2c.metamodel.AreaType;
import ch.interlis.ili2c.metamodel.AssociationDef;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.BlackboxType;
import ch.interlis.ili2c.metamodel.CompositionType;
import ch.interlis.ili2c.metamodel.Constraint;
import ch.interlis.ili2c.metamodel.Container;
import ch.interlis.ili2c.metamodel.Domain;
import ch.interlis.ili2c.metamodel.Element;
import ch.interlis.ili2c.metamodel.EnumerationType;
import ch.interlis.ili2c.metamodel.LineForm;
import ch.interlis.ili2c.metamodel.LineType;
import ch.interlis.ili2c.metamodel.Model;
import ch.interlis.ili2c.metamodel.NumericType;
import ch.interlis.ili2c.metamodel.PolylineType;
import ch.interlis.ili2c.metamodel.RefSystemRef;
import ch.interlis.ili2c.metamodel.ReferenceType;
import ch.interlis.ili2c.metamodel.RoleDef;
import ch.interlis.ili2c.metamodel.SurfaceType;
import ch.interlis.ili2c.metamodel.Table;
import ch.interlis.ili2c.metamodel.TextType;
import ch.interlis.ili2c.metamodel.Topic;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.ili2c.metamodel.Type;
import ch.interlis.ili2c.metamodel.TypeAlias;
import ch.interlis.ili2c.metamodel.Unit;
import ch.interlis.ili2c.metamodel.View;
import ch.so.agi.mcp.service.IliCompilerService;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class ModelAnalysisTools {

  private static final Pattern IMPORT_PATTERN = Pattern.compile("\\bIMPORTS\\s+(QUALIFIED\\s+)?([^;]+);", Pattern.CASE_INSENSITIVE);
  private static final Pattern META_PATTERN = Pattern.compile("^\\s*!!@\\s*([^=\\s]+)\\s*=", Pattern.MULTILINE);
  private final IliCompilerService compilerService;

  public ModelAnalysisTools(IliCompilerService compilerService) {
    this.compilerService = compilerService;
  }

  @McpTool(
      name = "analyzeIliModel",
      description = "Low-Level-Tool fuer gezielte strukturelle und semantische Inspektion eines vollstaendigen INTERLIS-Modells, z. B. Vererbung, Topic-Abhaengigkeiten, Association-Rollen, Constraints, Units, Metaattribute oder Typdetails. Nicht als allgemeines Qualitaetsgate oder Standardreview verwenden; dafuer reviewIliModel.",
      annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true)
  )
  public Map<String, Object> analyzeIliModel(
      @McpToolParam(description = "INTERLIS-2 Modelltext", required = true) String modelText,
      @McpToolParam(description = "Modellzweck: CAPTURE, PUBLICATION, VALIDATION oder UNKNOWN", required = false) @Nullable ModelPurpose modelPurpose
  ) {
    IliCompilerService.CompilationResult compilation = compilerService.compile(modelText, null, "ili2c_analysis_");
    AnalysisData data = analyzeCompiled(compilation.transferDescription(), modelText);
    return toResponse(compilation.valid(), compilation.messages(), data, ModelPurpose.normalize(modelPurpose));
  }

  public AnalysisData analyzeCompiled(@Nullable TransferDescription td, String modelText) {
    AnalysisData data = new AnalysisData();
    data.imports.addAll(parseImports(modelText));
    data.iliVersion = parseIliVersion(modelText);

    if (td == null) {
      data.metaAttributes.addAll(parseMetaAttributes(modelText));
      return data;
    }

    for (Model model : td.getModelsFromLastFile()) {
      collectMetaAttributes(model, data);
      data.models.add(modelMap(model));
      if (model.getIliVersion() != null && !model.getIliVersion().isBlank()) {
        data.iliVersion = model.getIliVersion();
      }
      collect(model, data, td);
    }
    return data;
  }

  public Map<String, Object> toResponse(
      boolean valid,
      List<Map<String, Object>> messages,
      AnalysisData data,
      ModelPurpose modelPurpose) {
    return Map.ofEntries(
        Map.entry("valid", valid),
        Map.entry("messages", messages),
        Map.entry("iliVersion", data.iliVersion),
        Map.entry("models", data.models),
        Map.entry("imports", data.imports),
        Map.entry("topics", data.topics),
        Map.entry("classes", data.classes),
        Map.entry("structures", data.structures),
        Map.entry("views", data.views),
        Map.entry("domains", data.domains),
        Map.entry("units", data.units),
        Map.entry("associations", data.associations),
        Map.entry("attributes", data.attributes),
        Map.entry("constraints", data.constraints),
        Map.entry("metaAttributes", data.metaAttributes),
        Map.entry("summaryMarkdown", summaryMarkdown(valid, data, modelPurpose))
    );
  }

  private void collect(Container<?> container, AnalysisData data, TransferDescription td) {
    Iterator<?> iterator = container.iterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (!(item instanceof Element element)) {
        continue;
      }
      collectMetaAttributes(element, data);
      if (element instanceof Topic topic) {
        data.topics.add(topicMap(topic));
        collect(topic, data, td);
      } else if (element instanceof AssociationDef association) {
        data.associations.add(associationMap(association));
        collect(association, data, td);
      } else if (element instanceof View view) {
        data.views.add(viewMap(view, td));
        collect(view, data, td);
      } else if (element instanceof Table table) {
        if (!table.isImplicit()) {
          if (table.isIdentifiable()) {
            data.classes.add(classMap(table, "CLASS"));
          } else {
            data.structures.add(classMap(table, "STRUCTURE"));
          }
        }
        collect(table, data, td);
      } else if (element instanceof Domain domain) {
        data.domains.add(domainMap(domain, td));
        collect(domain, data, td);
      } else if (element instanceof Constraint constraint) {
        data.constraints.add(constraintMap(constraint, td));
      } else if (element instanceof Unit unit) {
        data.units.add(unitMap(unit, td));
      } else if (element instanceof AttributeDef attribute) {
        data.attributes.add(attributeMap(attribute, td));
      } else if (element instanceof Container<?> child) {
        collect(child, data, td);
      }
    }
  }

  private Map<String, Object> modelMap(Model model) {
    Map<String, Object> map = elementMap(model, "MODEL");
    if (model.getModelVersion() != null && !model.getModelVersion().isBlank()) {
      map.put("version", model.getModelVersion());
    }
    return map;
  }

  private Map<String, Object> topicMap(Topic topic) {
    Map<String, Object> map = elementMap(topic, "TOPIC");
    map.put("abstract", topic.isAbstract());
    map.put("final", topic.isFinal());
    putExtending(map, topic.getExtending());

    List<String> dependsOn = new ArrayList<>();
    Iterator<Topic> iterator = topic.getDependentOn();
    while (iterator.hasNext()) {
      dependsOn.add(iterator.next().getScopedName());
    }
    if (!dependsOn.isEmpty()) {
      dependsOn.sort(String::compareTo);
      map.put("dependsOn", dependsOn);
    }
    return map;
  }

  private Map<String, Object> classMap(Table table, String kind) {
    Map<String, Object> map = elementMap(table, kind);
    map.put("abstract", table.isAbstract());
    map.put("final", table.isFinal());
    putExtending(map, table.getExtending());
    Domain oid = table.getOid();
    if (oid != null) {
      map.put("oid", oid.getScopedName());
    }
    return map;
  }

  private Map<String, Object> viewMap(View view, TransferDescription td) {
    Map<String, Object> map = elementMap(view, "VIEW");
    map.put("viewType", view.getClass().getSimpleName());
    StringWriter writer = new StringWriter();
    Interlis2Generator generator = Interlis2Generator.generateElements(writer, td);
    generator.printView(view, true);
    map.put("definitionText", writer.toString().strip());
    return map;
  }

  private Map<String, Object> domainMap(Domain domain, TransferDescription td) {
    Type domainType = domain.getType();
    Map<String, Object> map = elementMap(domain, "DOMAIN");
    map.put("abstract", domain.isAbstract());
    map.put("final", domain.isFinal());
    putExtending(map, domain.getExtending());
    map.put("type", domainType != null ? domainType.getClass().getSimpleName() : "");
    map.put("typeText", typeText(domainType));
    map.put("declaredType", declaredTypeText(domain.getContainer(), domainType, td));
    return map;
  }

  private Map<String, Object> associationMap(AssociationDef association) {
    Map<String, Object> map = elementMap(association, "ASSOCIATION");
    map.put("abstract", association.isAbstract());
    map.put("final", association.isFinal());
    putExtending(map, association.getExtending());

    List<Map<String, Object>> roles = new ArrayList<>();
    Iterator<RoleDef> iterator = association.getDefinedRoles();
    while (iterator.hasNext()) {
      roles.add(roleMap(iterator.next()));
    }
    map.put("roles", roles);
    return map;
  }

  private Map<String, Object> roleMap(RoleDef role) {
    Map<String, Object> map = elementMap(role, "ROLE");
    map.put("abstract", role.isAbstract());
    map.put("final", role.isFinal());
    map.put("ordered", role.isOrdered());
    map.put("roleKind", roleKind(role.getKind()));
    map.put("cardinalityDefined", role.containsCardinality());
    if (role.getCardinality() != null) {
      map.put("cardinality", role.getCardinality().toString());
    }
    putExtending(map, role.getExtending());

    List<Map<String, Object>> targets = new ArrayList<>();
    Iterator<ReferenceType> references = role.iteratorReference();
    while (references.hasNext()) {
      ReferenceType reference = references.next();
      if (reference.getReferred() == null) {
        continue;
      }
      Map<String, Object> target = new LinkedHashMap<>();
      target.put("target", reference.getReferred().getScopedName());
      target.put("external", reference.isExternal());
      targets.add(target);
    }
    map.put("targets", targets);
    return map;
  }

  private Map<String, Object> constraintMap(Constraint constraint, TransferDescription td) {
    Map<String, Object> map = elementMap(constraint, constraintKind(constraint));
    StringWriter writer = new StringWriter();
    Interlis2Generator generator = Interlis2Generator.generateElements(writer, td);
    generator.printConstraint(constraint, true);
    map.put("definitionText", writer.toString().strip());
    return map;
  }

  private Map<String, Object> unitMap(Unit unit, TransferDescription td) {
    Map<String, Object> map = elementMap(unit, "UNIT");
    StringWriter writer = new StringWriter();
    Interlis2Generator generator = Interlis2Generator.generateElements(writer, td);
    generator.printUnit(unit.getContainer(), unit, true);
    map.put("definitionText", writer.toString().strip());
    return map;
  }

  private String constraintKind(Constraint constraint) {
    return constraint.getClass().getSimpleName()
        .replace("Constraint", "_CONSTRAINT")
        .toUpperCase(Locale.ROOT);
  }

  private String roleKind(int kind) {
    return switch (kind) {
      case RoleDef.Kind.eAGGREGATE -> "AGGREGATE";
      case RoleDef.Kind.eCOMPOSITE -> "COMPOSITE";
      default -> "ASSOCIATE";
    };
  }

  private void putExtending(Map<String, Object> map, @Nullable Element extending) {
    if (extending != null) {
      map.put("extends", extending.getScopedName());
    }
  }

  private Map<String, Object> attributeMap(AttributeDef attribute, TransferDescription td) {
    Map<String, Object> map = elementMap(attribute, "ATTRIBUTE");
    Type declaredDomain = attribute.getDomain();
    Type domain = attribute.getDomainResolvingAliases();
    map.put("type", domain != null ? domain.getClass().getSimpleName() : "");
    map.put("typeText", typeText(domain));
    map.put("declaredType", declaredTypeText(attribute.getContainer(), declaredDomain, td));
    if (declaredDomain instanceof TypeAlias alias && alias.getAliasing() != null) {
      map.put("declaredTypeFqn", alias.getAliasing().getScopedName());
    }
    map.put("mandatory", (declaredDomain != null && declaredDomain.isMandatory())
        || (domain != null && domain.isMandatoryConsideringAliases()));
    map.put("geometry", isGeometryType(domain));
    Element container = attribute.getContainer();
    if (container != null) {
      map.put("container", container.getScopedName());
    }
    return map;
  }

  private String declaredTypeText(
      @Nullable Container<?> container,
      @Nullable Type type,
      TransferDescription td) {
    if (container == null || type == null) {
      return "";
    }
    StringWriter writer = new StringWriter();
    Interlis2Generator generator = Interlis2Generator.generateElements(writer, td);
    generator.printType(container, type, true);
    return writer.toString().strip();
  }

  private String typeText(@Nullable Type type) {
    if (type == null) {
      return "";
    }
    Type real = type.resolveAliases();
    if (real instanceof TextType textType) {
      String kind = textType.isNormalized() ? "TEXT" : "MTEXT";
      return textType.getMaxLength() < 0 ? kind : kind + "*" + textType.getMaxLength();
    }
    if (real instanceof NumericType numericType) {
      StringBuilder text = new StringBuilder();
      if (numericType.getMinimum() == null || numericType.getMaximum() == null) {
        text.append("NUMERIC");
      } else {
        text.append(numericType.getMinimum()).append("..").append(numericType.getMaximum());
      }
      Unit unit = numericType.getUnit();
      if (unit != null) {
        text.append("|unit=").append(unit.getScopedName());
      }
      if (numericType.isCircular()) {
        text.append("|circular=true");
      }
      if (numericType.getRotation() != 0) {
        text.append("|rotation=").append(numericType.getRotation());
      }
      String referenceSystem = referenceSystemText(numericType.getReferenceSystem());
      if (!referenceSystem.isEmpty()) {
        text.append("|refSys=").append(referenceSystem);
      }
      return text.toString();
    }
    if (real instanceof AbstractCoordType coordType) {
      StringBuilder text = new StringBuilder(real.getClass().getSimpleName()).append("|dimensions=");
      for (int i = 0; i < coordType.getDimensions().length; i++) {
        if (i > 0) {
          text.append(",");
        }
        text.append(typeText(coordType.getDimensions()[i]));
      }
      if (coordType.getNullAxis() != 0 || coordType.getPiHalfAxis() != 0) {
        text.append("|rotation=").append(coordType.getNullAxis()).append("->").append(coordType.getPiHalfAxis());
      }
      if (coordType.isGeneric()) {
        text.append("|generic=true");
      }
      if (coordType.getCrs() != null && !coordType.getCrs().isBlank()) {
        text.append("|crs=").append(coordType.getCrs());
      }
      return text.toString();
    }
    if (real instanceof EnumerationType enumerationType) {
      StringBuilder text = new StringBuilder("ENUM|values=").append(String.join(",", enumerationType.getValues()));
      if (enumerationType.isOrdered()) {
        text.append("|ordered=true");
      }
      if (enumerationType.isCircular()) {
        text.append("|circular=true");
      }
      if (enumerationType.getConsolidatedEnumeration().isFinal()) {
        text.append("|final=true");
      }
      return text.toString();
    }
    if (real instanceof BlackboxType blackboxType) {
      return switch (blackboxType.getKind()) {
        case BlackboxType.eXML -> "BLACKBOX|kind=XML";
        case BlackboxType.eBINARY -> "BLACKBOX|kind=BINARY";
        default -> "BLACKBOX|kind=" + blackboxType.getKind();
      };
    }
    if (real instanceof CompositionType compositionType) {
      StringBuilder text = new StringBuilder("COMPOSITION");
      if (compositionType.getComponentType() != null) {
        text.append("|component=").append(compositionType.getComponentType().getScopedName());
      }
      if (compositionType.getCardinality() != null) {
        text.append("|cardinality=").append(compositionType.getCardinality());
      }
      if (compositionType.isOrdered()) {
        text.append("|ordered=true");
      }
      return text.toString();
    }
    if (real instanceof LineType lineType) {
      StringBuilder text = new StringBuilder(real.getClass().getSimpleName());
      if (real instanceof PolylineType polylineType && polylineType.isDirected()) {
        text.append("|directed=true");
      }
      if (lineType.getControlPointDomain() != null) {
        text.append("|controlPointDomain=").append(lineType.getControlPointDomain().getScopedName());
      }
      List<String> lineForms = new ArrayList<>();
      for (LineForm lineForm : lineType.getLineForms()) {
        lineForms.add(lineForm.getName());
      }
      if (!lineForms.isEmpty()) {
        lineForms.sort(String::compareTo);
        text.append("|lineForms=").append(String.join(",", lineForms));
      }
      if (lineType.getMaxOverlap() != null) {
        text.append("|maxOverlap=").append(lineType.getMaxOverlap());
      }
      return text.toString();
    }
    if (real instanceof ReferenceType referenceType) {
      StringBuilder text = new StringBuilder("REFERENCE");
      if (referenceType.getReferred() != null) {
        text.append("|target=").append(referenceType.getReferred().getScopedName());
      }
      if (referenceType.isExternal()) {
        text.append("|external=true");
      }
      List<String> restrictions = new ArrayList<>();
      Iterator<?> iterator = referenceType.iteratorRestrictedTo();
      while (iterator.hasNext()) {
        Object restriction = iterator.next();
        if (restriction instanceof Element element) {
          restrictions.add(element.getScopedName());
        }
      }
      if (!restrictions.isEmpty()) {
        text.append("|restrictedTo=").append(String.join(",", restrictions));
      }
      return text.toString();
    }
    return real.getClass().getSimpleName();
  }

  private String referenceSystemText(@Nullable RefSystemRef referenceSystem) {
    if (referenceSystem instanceof RefSystemRef.CoordDomain ref) {
      return "coordDomain:" + ref.getReferredDomain().getScopedName();
    }
    if (referenceSystem instanceof RefSystemRef.CoordDomainAxis ref) {
      return "coordDomain:" + ref.getReferredDomain().getScopedName() + ":axis=" + ref.getAxisNumber();
    }
    if (referenceSystem instanceof RefSystemRef.CoordSystem ref) {
      return "coordSystem:" + ref.getSystem().getTable().getScopedName() + "." + ref.getSystem().getName();
    }
    if (referenceSystem instanceof RefSystemRef.CoordSystemAxis ref) {
      return "coordSystem:" + ref.getSystem().getTable().getScopedName() + "." + ref.getSystem().getName()
          + ":axis=" + ref.getAxisNumber();
    }
    return referenceSystem == null ? "" : referenceSystem.getClass().getSimpleName();
  }

  private boolean isGeometryType(@Nullable Type type) {
    if (type == null) {
      return false;
    }
    Type real = type.resolveAliases();
    return real instanceof LineType
        || real instanceof SurfaceType
        || real instanceof AreaType
        || real instanceof AbstractCoordType;
  }

  private Map<String, Object> elementMap(Element element, String kind) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("kind", kind);
    map.put("name", element.getName());
    map.put("scopedName", element.getScopedName());
    if (element.getSourceLine() > 0) {
      map.put("line", element.getSourceLine());
    }
    if (element.getDocumentation() != null && !element.getDocumentation().isBlank()) {
      map.put("documentation", element.getDocumentation());
    }
    Settings metaValues = element.getMetaValues();
    if (metaValues != null && !metaValues.getValues().isEmpty()) {
      Map<String, String> values = new LinkedHashMap<>();
      for (String key : metaValues.getValues()) {
        values.put(key, metaValues.getValue(key));
      }
      map.put("metaAttributes", values);
    }
    return map;
  }

  private void collectMetaAttributes(Element element, AnalysisData data) {
    Settings metaValues = element.getMetaValues();
    if (metaValues == null || metaValues.getValues().isEmpty()) {
      return;
    }
    List<String> keys = new ArrayList<>(metaValues.getValues());
    keys.sort(String::compareTo);
    for (String key : keys) {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("kind", "META_ATTRIBUTE");
      map.put("name", key);
      map.put("owner", element.getScopedName());
      map.put("value", metaValues.getValue(key));
      data.metaAttributes.add(map);
    }
  }

  private List<Map<String, Object>> parseImports(String modelText) {
    List<Map<String, Object>> imports = new ArrayList<>();
    Matcher matcher = IMPORT_PATTERN.matcher(modelText);
    while (matcher.find()) {
      String qualified = matcher.group(1);
      String[] names = matcher.group(2).split(",");
      for (String rawName : names) {
        String name = rawName.trim();
        if (!name.isBlank()) {
          Map<String, Object> map = new LinkedHashMap<>();
          map.put("model", name);
          map.put("qualified", qualified != null && !qualified.isBlank());
          imports.add(map);
        }
      }
    }
    return imports;
  }

  private List<Map<String, Object>> parseMetaAttributes(String modelText) {
    List<Map<String, Object>> metaAttributes = new ArrayList<>();
    Matcher matcher = META_PATTERN.matcher(modelText);
    while (matcher.find()) {
      Map<String, Object> map = new LinkedHashMap<>();
      map.put("kind", "META_ATTRIBUTE");
      map.put("name", matcher.group(1));
      metaAttributes.add(map);
    }
    return metaAttributes;
  }

  private String parseIliVersion(String modelText) {
    Matcher matcher = Pattern.compile("\\bINTERLIS\\s+([0-9.]+)\\s*;", Pattern.CASE_INSENSITIVE).matcher(modelText);
    return matcher.find() ? matcher.group(1) : "";
  }

  private String summaryMarkdown(boolean valid, AnalysisData data, ModelPurpose purpose) {
    return "- valid: " + valid + "\n"
        + "- modelPurpose: " + purpose + "\n"
        + "- models: " + data.models.size() + "\n"
        + "- topics: " + data.topics.size() + "\n"
        + "- classes: " + data.classes.size() + "\n"
        + "- structures: " + data.structures.size() + "\n"
        + "- views: " + data.views.size() + "\n"
        + "- domains: " + data.domains.size() + "\n"
        + "- units: " + data.units.size() + "\n"
        + "- associations: " + data.associations.size() + "\n"
        + "- attributes: " + data.attributes.size() + "\n"
        + "- constraints: " + data.constraints.size() + "\n"
        + "- metaAttributes: " + data.metaAttributes.size();
  }

  public Set<String> lexicalTerms(Map<String, Object> analysisResponse) {
    Set<String> terms = new LinkedHashSet<>();
    for (String key : List.of("models", "topics", "classes", "structures", "views", "domains", "units", "associations", "attributes", "constraints", "metaAttributes")) {
      Object value = analysisResponse.get(key);
      if (value instanceof List<?> list) {
        for (Object item : list) {
          if (item instanceof Map<?, ?> map) {
            addTerm(terms, map.get("name"));
            addTerm(terms, map.get("scopedName"));
          }
        }
      }
    }
    return terms;
  }

  private void addTerm(Set<String> terms, @Nullable Object value) {
    if (value == null) {
      return;
    }
    for (String token : value.toString().split("[^A-Za-z0-9_]+")) {
      if (token.length() >= 3) {
        terms.add(token.toLowerCase(Locale.ROOT));
      }
    }
  }

  public static class AnalysisData {
    public String iliVersion = "";
    public final List<Map<String, Object>> models = new ArrayList<>();
    public final List<Map<String, Object>> imports = new ArrayList<>();
    public final List<Map<String, Object>> topics = new ArrayList<>();
    public final List<Map<String, Object>> classes = new ArrayList<>();
    public final List<Map<String, Object>> structures = new ArrayList<>();
    public final List<Map<String, Object>> views = new ArrayList<>();
    public final List<Map<String, Object>> domains = new ArrayList<>();
    public final List<Map<String, Object>> associations = new ArrayList<>();
    public final List<Map<String, Object>> attributes = new ArrayList<>();
    public final List<Map<String, Object>> constraints = new ArrayList<>();
    public final List<Map<String, Object>> metaAttributes = new ArrayList<>();
    public final List<Map<String, Object>> units = new ArrayList<>();
  }
}
