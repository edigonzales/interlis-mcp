package ch.so.agi.mcp.analysis;

import ch.ehi.basics.settings.Settings;
import ch.interlis.ili2c.metamodel.AbstractCoordType;
import ch.interlis.ili2c.metamodel.AreaType;
import ch.interlis.ili2c.metamodel.AssociationDef;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.Container;
import ch.interlis.ili2c.metamodel.Domain;
import ch.interlis.ili2c.metamodel.Element;
import ch.interlis.ili2c.metamodel.LineType;
import ch.interlis.ili2c.metamodel.Model;
import ch.interlis.ili2c.metamodel.SurfaceType;
import ch.interlis.ili2c.metamodel.Table;
import ch.interlis.ili2c.metamodel.Topic;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.ili2c.metamodel.Type;
import ch.interlis.ili2c.metamodel.Unit;
import ch.so.agi.mcp.service.IliCompilerService;
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
      description = "Analysiert ein vollstaendiges INTERLIS-Modell strukturell mit ili2c. Rueckgabe: valid, messages, Modelle, Imports, Topics, Klassen, Strukturen, Domains, Units, Associations, Attribute, Metaattribute und summaryMarkdown."
  )
  public Map<String, Object> analyzeIliModel(
      @McpToolParam(description = "INTERLIS-2 Modelltext", required = true) String modelText,
      @McpToolParam(description = "Optionale MODELREPOS-/ilidirs-Definition", required = false) @Nullable String modelRepositories,
      @McpToolParam(description = "Modellzweck: CAPTURE, PUBLICATION, VALIDATION oder UNKNOWN", required = false) @Nullable ModelPurpose modelPurpose
  ) {
    IliCompilerService.CompilationResult compilation = compilerService.compile(modelText, modelRepositories, "ili2c_analysis_");
    AnalysisData data = analyzeCompiled(compilation.transferDescription(), modelText);
    return toResponse(compilation.valid(), compilation.messages(), data, ModelPurpose.normalize(modelPurpose));
  }

  public AnalysisData analyzeCompiled(@Nullable TransferDescription td, String modelText) {
    AnalysisData data = new AnalysisData();
    data.imports.addAll(parseImports(modelText));
    data.metaAttributes.addAll(parseMetaAttributes(modelText));
    data.iliVersion = parseIliVersion(modelText);

    if (td == null) {
      return data;
    }

    for (Model model : td.getModelsFromLastFile()) {
      data.models.add(elementMap(model, "MODEL"));
      if (model.getIliVersion() != null && !model.getIliVersion().isBlank()) {
        data.iliVersion = model.getIliVersion();
      }
      collect(model, data);
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
        Map.entry("domains", data.domains),
        Map.entry("units", data.units),
        Map.entry("associations", data.associations),
        Map.entry("attributes", data.attributes),
        Map.entry("metaAttributes", data.metaAttributes),
        Map.entry("summaryMarkdown", summaryMarkdown(valid, data, modelPurpose))
    );
  }

  private void collect(Container<?> container, AnalysisData data) {
    Iterator<?> iterator = container.iterator();
    while (iterator.hasNext()) {
      Object item = iterator.next();
      if (!(item instanceof Element element)) {
        continue;
      }
      if (element instanceof Topic topic) {
        data.topics.add(elementMap(topic, "TOPIC"));
        collect(topic, data);
      } else if (element instanceof AssociationDef association) {
        data.associations.add(elementMap(association, "ASSOCIATION"));
        collect(association, data);
      } else if (element instanceof Table table) {
        if (!table.isImplicit()) {
          if (table.isIdentifiable()) {
            data.classes.add(elementMap(table, "CLASS"));
          } else {
            data.structures.add(elementMap(table, "STRUCTURE"));
          }
        }
        collect(table, data);
      } else if (element instanceof Domain domain) {
        Map<String, Object> map = elementMap(domain, "DOMAIN");
        map.put("type", domain.getType() != null ? domain.getType().getClass().getSimpleName() : "");
        data.domains.add(map);
      } else if (element instanceof Unit unit) {
        Map<String, Object> map = elementMap(unit, "UNIT");
        data.units.add(map);
      } else if (element instanceof AttributeDef attribute) {
        data.attributes.add(attributeMap(attribute));
      } else if (element instanceof Container<?> child) {
        collect(child, data);
      }
    }
  }

  private Map<String, Object> attributeMap(AttributeDef attribute) {
    Map<String, Object> map = elementMap(attribute, "ATTRIBUTE");
    Type domain = attribute.getDomainResolvingAliases();
    map.put("type", domain != null ? domain.getClass().getSimpleName() : "");
    map.put("typeText", domain != null ? domain.toString() : "");
    map.put("mandatory", domain != null && domain.isMandatoryConsideringAliases());
    map.put("geometry", isGeometryType(domain));
    Element container = attribute.getContainer();
    if (container != null) {
      map.put("container", container.getScopedName());
    }
    return map;
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
        + "- domains: " + data.domains.size() + "\n"
        + "- units: " + data.units.size() + "\n"
        + "- associations: " + data.associations.size() + "\n"
        + "- attributes: " + data.attributes.size();
  }

  public Set<String> lexicalTerms(Map<String, Object> analysisResponse) {
    Set<String> terms = new LinkedHashSet<>();
    for (String key : List.of("models", "topics", "classes", "structures", "domains", "units", "associations", "attributes")) {
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
    public final List<Map<String, Object>> domains = new ArrayList<>();
    public final List<Map<String, Object>> associations = new ArrayList<>();
    public final List<Map<String, Object>> attributes = new ArrayList<>();
    public final List<Map<String, Object>> metaAttributes = new ArrayList<>();
    public final List<Map<String, Object>> units = new ArrayList<>();
  }
}
