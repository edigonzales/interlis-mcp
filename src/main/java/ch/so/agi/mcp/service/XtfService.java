package ch.so.agi.mcp.service;

import ch.ehi.basics.logging.EhiLogger;
import ch.ehi.basics.logging.LogEvent;
import ch.ehi.basics.logging.LogListener;
import ch.ehi.basics.logging.StdListener;
import ch.ehi.basics.settings.Settings;
import ch.interlis.ili2c.CompilerLogEvent;
import ch.interlis.ili2c.Ili2cSettings;
import ch.interlis.ili2c.metamodel.AbstractClassDef;
import ch.interlis.ili2c.metamodel.AbstractCoordType;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.Cardinality;
import ch.interlis.ili2c.metamodel.Element;
import ch.interlis.ili2c.metamodel.EnumerationType;
import ch.interlis.ili2c.metamodel.EnumTreeValueType;
import ch.interlis.ili2c.metamodel.Extendable;
import ch.interlis.ili2c.metamodel.Model;
import ch.interlis.ili2c.metamodel.NumericType;
import ch.interlis.ili2c.metamodel.ReferenceType;
import ch.interlis.ili2c.metamodel.Table;
import ch.interlis.ili2c.metamodel.TextType;
import ch.interlis.ili2c.metamodel.Topic;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.ili2c.metamodel.Type;
import ch.interlis.iom.IomObject;
import ch.interlis.iom_j.Iom_jObject;
import ch.interlis.iom_j.xtf.XtfWriter;
import ch.interlis.iox.IoxException;
import ch.interlis.iox.IoxLogEvent;
import ch.interlis.iox_j.EndBasketEvent;
import ch.interlis.iox_j.EndTransferEvent;
import ch.interlis.iox_j.ObjectEvent;
import ch.interlis.iox_j.StartBasketEvent;
import ch.interlis.iox_j.StartTransferEvent;
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
import java.util.Set;
import java.util.stream.Collectors;
import org.interlis2.validator.Validator;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
public class XtfService {

  private static final int MAX_SUPPORTED_REQUIRED_MULTIPLICITY = 5;

  private final IliCompilerService compilerService;

  public XtfService(IliCompilerService compilerService) {
    this.compilerService = compilerService;
  }

  public GenerateExampleResult generateExampleXtf(
      String modelText,
      @Nullable String modelRepositories,
      @Nullable Integer maxObjectsPerClass) {
    int objectLimit = normalizeObjectLimit(maxObjectsPerClass);
    IliCompilerService.CompilationResult compilation = compilerService.compile(modelText, modelRepositories, "xtf_generate_model_");

    List<Map<String, Object>> messages = copyMessages(compilation.messages());
    List<Map<String, Object>> objectsByClass = new ArrayList<>();
    List<Map<String, Object>> skippedClasses = new ArrayList<>();

    if (!compilation.valid() || compilation.transferDescription() == null) {
      return new GenerateExampleResult(false, null, messages, 0, 0, objectsByClass, skippedClasses);
    }

    TransferDescription transferDescription = compilation.transferDescription();
    List<Table> eligibleTables = collectEligibleTables(transferDescription);
    if (eligibleTables.isEmpty()) {
      messages.add(message("WARNING", "No eligible identifiable classes were found in the last compiled model file."));
      return new GenerateExampleResult(false, null, messages, 0, 0, objectsByClass, skippedClasses);
    }

    Map<Table, String> skippedReasons = determineSkippedReasons(eligibleTables);
    for (Table table : eligibleTables) {
      if (skippedReasons.containsKey(table)) {
        String reason = skippedReasons.get(table);
        skippedClasses.add(Map.of(
            "classFqn", table.getScopedName(null),
            "reason", reason));
        messages.add(message("WARNING", table.getScopedName(null) + ": " + reason));
      }
    }

    List<Table> activeTables = eligibleTables.stream()
        .filter(table -> !skippedReasons.containsKey(table))
        .toList();

    if (activeTables.isEmpty()) {
      messages.add(message("WARNING", "No class could be generated due to unsupported mandatory attributes."));
      return new GenerateExampleResult(false, null, messages, 0, 0, objectsByClass, skippedClasses);
    }

    Map<Table, List<String>> objectIdsByClass = allocateObjectIds(activeTables, objectLimit);
    Map<Topic, String> basketIds = allocateBasketIds(activeTables);

    Path xtfFile = null;
    try {
      xtfFile = Files.createTempFile("interlis-mcp-example-", ".xtf");
      writeExampleXtf(transferDescription, activeTables, objectIdsByClass, basketIds, xtfFile);
      String xtfText = Files.readString(xtfFile, StandardCharsets.UTF_8);

      int objectCount = objectIdsByClass.values().stream().mapToInt(List::size).sum();
      int basketCount = basketIds.size();
      for (Table table : activeTables) {
        objectsByClass.add(Map.of(
            "classFqn", table.getScopedName(null),
            "objectCount", objectIdsByClass.get(table).size()));
      }

      return new GenerateExampleResult(true, xtfText, messages, basketCount, objectCount, objectsByClass, skippedClasses);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to generate example XTF.", e);
    } finally {
      if (xtfFile != null) {
        try {
          Files.deleteIfExists(xtfFile);
        } catch (Exception ignore) {
        }
      }
    }
  }

  public ValidationResult validateXtf(
      String modelText,
      String xtfText,
      @Nullable String modelRepositories) {
    if (xtfText == null || xtfText.isBlank()) {
      throw new IllegalArgumentException("XTF text is required.");
    }

    IliCompilerService.CompilationResult compilation = compilerService.compile(modelText, modelRepositories, "xtf_validate_model_");
    List<Map<String, Object>> messages = copyMessages(compilation.messages());

    if (!compilation.valid() || compilation.transferDescription() == null) {
      Counts counts = countSeverities(messages);
      return new ValidationResult(false, messages, counts.errors(), counts.warnings());
    }

    TransferDescription td = compilation.transferDescription();
    Path tempDir = null;
    try {
      tempDir = Files.createTempDirectory("interlis-mcp-validate-");
      Path modelFile = tempDir.resolve("model.ili");
      Path xtfFile = tempDir.resolve("data.xtf");
      Files.writeString(modelFile, modelText, StandardCharsets.UTF_8);
      Files.writeString(xtfFile, xtfText, StandardCharsets.UTF_8);

      String modelNames = modelNamesFromLastFile(td);
      Settings settings = new Settings();
      settings.setValue(Validator.SETTING_DISABLE_STD_LOGGER, Validator.TRUE);
      settings.setValue(Validator.SETTING_ILIDIRS, buildValidationIliDirs(tempDir, modelRepositories));
      if (!modelNames.isBlank()) {
        settings.setValue(Validator.SETTING_MODELNAMES, modelNames);
      }

      boolean validatorResult = runValidator(xtfFile, settings, messages);
      Counts counts = countSeverities(messages);
      boolean valid = validatorResult && counts.errors() == 0;
      return new ValidationResult(valid, messages, counts.errors(), counts.warnings());
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to validate XTF.", e);
    } finally {
      if (tempDir != null) {
        deleteRecursively(tempDir);
      }
    }
  }

  private boolean runValidator(Path xtfFile, Settings settings, List<Map<String, Object>> messages) {
    boolean validationResult = false;
    LogListener collector = new ValidatorLogCollector(messages);

    EhiLogger logger = EhiLogger.getInstance();
    synchronized (logger) {
      StdListener stdListener = StdListener.getInstance();
      stdListener.skipInfo(true);
      logger.addListener(collector);
      logger.removeListener(stdListener);
      try {
        validationResult = new Validator().validate(new String[] {xtfFile.toString()}, settings);
      } catch (Exception e) {
        messages.add(message("ERROR", "ilivalidator failed: " + e.getMessage()));
      } finally {
        logger.addListener(stdListener);
        logger.removeListener(collector);
        stdListener.skipInfo(false);
      }
    }
    return validationResult;
  }

  private void writeExampleXtf(
      TransferDescription td,
      List<Table> activeTables,
      Map<Table, List<String>> objectIdsByClass,
      Map<Topic, String> basketIds,
      Path xtfFile) throws IOException {
    Map<Topic, List<Table>> tablesByTopic = new LinkedHashMap<>();
    for (Table table : activeTables) {
      Topic topic = (Topic) table.getContainer(Topic.class);
      tablesByTopic.computeIfAbsent(topic, key -> new ArrayList<>()).add(table);
    }
    tablesByTopic.values().forEach(list -> list.sort(Comparator.comparing(table -> table.getScopedName(null))));

    try (OutputStream outputStream = Files.newOutputStream(xtfFile)) {
      XtfWriter writer = new XtfWriter(outputStream, td);
      try {
        StartTransferEvent startTransferEvent = new StartTransferEvent();
        startTransferEvent.setVersion("2.4");
        startTransferEvent.setSender("interlis-mcp");
        startTransferEvent.setComment("generateExampleXtf");
        writer.write(startTransferEvent);
        for (Map.Entry<Topic, List<Table>> entry : tablesByTopic.entrySet()) {
          Topic topic = entry.getKey();
          String bid = basketIds.get(topic);
          writer.write(new StartBasketEvent(topic.getScopedName(null), bid));
          for (Table table : entry.getValue()) {
            List<String> objectIds = objectIdsByClass.getOrDefault(table, List.of());
            for (int i = 0; i < objectIds.size(); i++) {
              int objectIndex = i + 1;
              Iom_jObject object = new Iom_jObject(table.getScopedName(null), objectIds.get(i));
              fillMandatoryAttributes(object, table, objectIndex, objectIdsByClass, basketIds);
              writer.write(new ObjectEvent(object));
            }
          }
          writer.write(new EndBasketEvent());
        }
        writer.write(new EndTransferEvent());
        writer.flush();
      } catch (IoxException e) {
        throw new IOException("Unable to write XTF content.", e);
      } finally {
        try {
          writer.close();
        } catch (Exception ignore) {
        }
      }
    } catch (IoxException e) {
      throw new IOException("Unable to create XTF writer.", e);
    }
  }

  private void fillMandatoryAttributes(
      Iom_jObject object,
      Table table,
      int objectIndex,
      Map<Table, List<String>> objectIdsByClass,
      Map<Topic, String> basketIds) {
    Iterator<Extendable> attributes = table.getAttributes();
    while (attributes.hasNext()) {
      Extendable extendable = attributes.next();
      if (!(extendable instanceof AttributeDef attributeDef)) {
        continue;
      }
      Type type = Type.findReal(attributeDef.getDomainResolvingAll());
      int min = requiredMultiplicity(type);
      for (int occurrence = 0; occurrence < min; occurrence++) {
        applyMandatoryValue(object, table, attributeDef, type, objectIndex, occurrence, objectIdsByClass, basketIds);
      }
    }
  }

  private void applyMandatoryValue(
      Iom_jObject object,
      Table table,
      AttributeDef attributeDef,
      Type type,
      int objectIndex,
      int occurrence,
      Map<Table, List<String>> objectIdsByClass,
      Map<Topic, String> basketIds) {
    String attrName = attributeDef.getName();

    if (type instanceof TextType textType) {
      String value = "txt_" + objectIndex;
      int maxLength = textType.getMaxLength();
      if (maxLength > 0 && value.length() > maxLength) {
        value = value.substring(0, maxLength);
      }
      object.addattrvalue(attrName, value);
      return;
    }

    if (type instanceof NumericType numericType) {
      String value = numericType.getMinimum() != null ? numericType.getMinimum().toString() : "1";
      object.addattrvalue(attrName, value);
      return;
    }

    if (type.isBoolean()) {
      object.addattrvalue(attrName, Iom_jObject.TRUE);
      return;
    }

    if (type instanceof EnumerationType enumerationType) {
      List<String> values = enumerationType.getValues();
      if (values.isEmpty()) {
        throw new IllegalStateException("Enumeration has no values for mandatory attribute '" + attrName + "'.");
      }
      object.addattrvalue(attrName, values.getFirst());
      return;
    }

    if (type instanceof EnumTreeValueType enumTreeValueType) {
      List<String> values = enumTreeValueType.getValues();
      if (values.isEmpty()) {
        throw new IllegalStateException("Enum tree has no values for mandatory attribute '" + attrName + "'.");
      }
      object.addattrvalue(attrName, values.getFirst());
      return;
    }

    if (type instanceof AbstractCoordType coordType) {
      IomObject coord = object.addattrobj(attrName, Iom_jObject.COORD);
      coord.setattrvalue(Iom_jObject.COORD_C1, "2600000.0");
      if (coordType.getDimensions().length >= 2) {
        coord.setattrvalue(Iom_jObject.COORD_C2, "1200000.0");
      }
      if (coordType.getDimensions().length >= 3) {
        coord.setattrvalue(Iom_jObject.COORD_C3, "500.0");
      }
      return;
    }

    if (type instanceof ReferenceType referenceType) {
      AbstractClassDef<?> referred = referenceType.getReferred();
      if (!(referred instanceof Table targetTable)) {
        throw new IllegalStateException("Mandatory reference target is not a table for attribute '" + attrName + "'.");
      }
      List<String> targetOids = objectIdsByClass.getOrDefault(targetTable, List.of());
      if (targetOids.isEmpty()) {
        throw new IllegalStateException("Mandatory reference target has no generated object for attribute '" + attrName + "'.");
      }
      IomObject ref = object.addattrobj(attrName, Iom_jObject.REF);
      String targetOid = targetOids.get((objectIndex - 1 + occurrence) % targetOids.size());
      ref.setobjectrefoid(targetOid);
      Topic sourceTopic = (Topic) table.getContainer(Topic.class);
      Topic targetTopic = (Topic) targetTable.getContainer(Topic.class);
      if (sourceTopic != null && targetTopic != null && sourceTopic != targetTopic) {
        String refBid = basketIds.get(targetTopic);
        if (refBid != null) {
          ref.setobjectrefbid(refBid);
        }
      }
      return;
    }

    throw new IllegalStateException(
        "Unsupported mandatory type for attribute '" + attrName + "': " + type.getClass().getSimpleName());
  }

  private Map<Table, String> determineSkippedReasons(List<Table> eligibleTables) {
    Set<Table> active = new LinkedHashSet<>(eligibleTables);
    Map<Table, String> skippedReasons = new LinkedHashMap<>();

    boolean changed;
    do {
      changed = false;
      for (Table table : new ArrayList<>(active)) {
        String reason = firstUnsupportedMandatoryReason(table, active);
        if (reason != null) {
          active.remove(table);
          skippedReasons.putIfAbsent(table, reason);
          changed = true;
        }
      }
    } while (changed);

    return skippedReasons;
  }

  private @Nullable String firstUnsupportedMandatoryReason(Table table, Set<Table> activeClasses) {
    Iterator<Extendable> attributes = table.getAttributes();
    while (attributes.hasNext()) {
      Extendable extendable = attributes.next();
      if (!(extendable instanceof AttributeDef attributeDef)) {
        continue;
      }
      Type type = Type.findReal(attributeDef.getDomainResolvingAll());
      int minimum = requiredMultiplicity(type);
      if (minimum <= 0) {
        continue;
      }
      if (minimum > MAX_SUPPORTED_REQUIRED_MULTIPLICITY) {
        return "mandatory attribute '" + attributeDef.getName() + "' has multiplicity minimum "
            + minimum + " which is above supported limit " + MAX_SUPPORTED_REQUIRED_MULTIPLICITY;
      }
      if (isSupportedMandatoryType(type, activeClasses)) {
        continue;
      }
      return "unsupported mandatory attribute '" + attributeDef.getName() + "' of type " + type.getClass().getSimpleName();
    }
    return null;
  }

  private boolean isSupportedMandatoryType(Type type, Set<Table> activeClasses) {
    if (type instanceof TextType
        || type instanceof NumericType
        || type instanceof EnumerationType
        || type instanceof EnumTreeValueType
        || type instanceof AbstractCoordType
        || type.isBoolean()) {
      return true;
    }
    if (type instanceof ReferenceType referenceType) {
      AbstractClassDef<?> referred = referenceType.getReferred();
      return referred instanceof Table targetTable && activeClasses.contains(targetTable);
    }
    return false;
  }

  private int requiredMultiplicity(Type type) {
    int minimum = type.isMandatoryConsideringAliases() ? 1 : 0;
    Cardinality cardinality = type.getCardinality();
    if (cardinality != null && cardinality.getMinimum() > minimum) {
      if (cardinality.getMinimum() > Integer.MAX_VALUE) {
        return Integer.MAX_VALUE;
      }
      minimum = (int) cardinality.getMinimum();
    }
    return minimum;
  }

  private Map<Topic, String> allocateBasketIds(List<Table> activeTables) {
    Map<Topic, String> basketIds = new LinkedHashMap<>();
    int basketIndex = 1;
    for (Table table : activeTables) {
      Topic topic = (Topic) table.getContainer(Topic.class);
      if (topic == null || basketIds.containsKey(topic)) {
        continue;
      }
      basketIds.put(topic, "b" + basketIndex++);
    }
    return basketIds;
  }

  private Map<Table, List<String>> allocateObjectIds(List<Table> activeTables, int objectLimit) {
    Map<Table, List<String>> objectIds = new LinkedHashMap<>();
    for (Table table : activeTables) {
      List<String> ids = new ArrayList<>();
      String slug = table.getScopedName(null).replaceAll("[^A-Za-z0-9_]", "_");
      for (int i = 1; i <= objectLimit; i++) {
        ids.add(slug + "_" + i);
      }
      objectIds.put(table, ids);
    }
    return objectIds;
  }

  private List<Table> collectEligibleTables(TransferDescription td) {
    List<Table> tables = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (Model model : td.getModelsFromLastFile()) {
      Iterator<?> modelElements = model.iterator();
      while (modelElements.hasNext()) {
        Object modelElementObj = modelElements.next();
        if (!(modelElementObj instanceof Element modelElement)) {
          continue;
        }
        if (!(modelElement instanceof Topic topic) || topic.isViewTopic()) {
          continue;
        }
        Iterator<?> topicElements = topic.iterator();
        while (topicElements.hasNext()) {
          Object topicElementObj = topicElements.next();
          if (!(topicElementObj instanceof Element topicElement)) {
            continue;
          }
          if (!(topicElement instanceof Table table)) {
            continue;
          }
          if (!table.isIdentifiable() || table.isAbstract() || table.isImplicit()) {
            continue;
          }
          String classFqn = table.getScopedName(null);
          if (seen.add(classFqn)) {
            tables.add(table);
          }
        }
      }
    }
    return tables;
  }

  private int normalizeObjectLimit(@Nullable Integer maxObjectsPerClass) {
    if (maxObjectsPerClass == null) {
      return 1;
    }
    if (maxObjectsPerClass < 1) {
      throw new IllegalArgumentException("maxObjectsPerClass must be greater than 0.");
    }
    return maxObjectsPerClass;
  }

  private String buildValidationIliDirs(Path modelDir, @Nullable String modelRepositories) {
    String base = normalizeModelRepositories(modelRepositories);
    return modelDir.toAbsolutePath() + ";" + base;
  }

  private String normalizeModelRepositories(@Nullable String modelRepositories) {
    if (modelRepositories == null || modelRepositories.isBlank()) {
      return Ili2cSettings.DEFAULT_ILIDIRS;
    }
    return modelRepositories.trim();
  }

  private String modelNamesFromLastFile(TransferDescription td) {
    return List.of(td.getModelsFromLastFile()).stream()
        .map(Model::getName)
        .filter(name -> name != null && !name.isBlank())
        .collect(Collectors.joining(";"));
  }

  private static Counts countSeverities(List<Map<String, Object>> messages) {
    int errors = 0;
    int warnings = 0;
    for (Map<String, Object> message : messages) {
      String severity = String.valueOf(message.getOrDefault("severity", "")).toUpperCase(Locale.ROOT);
      if ("ERROR".equals(severity)) {
        errors++;
      } else if ("WARNING".equals(severity)) {
        warnings++;
      }
    }
    return new Counts(errors, warnings);
  }

  private static List<Map<String, Object>> copyMessages(List<Map<String, Object>> messages) {
    if (messages == null || messages.isEmpty()) {
      return new ArrayList<>();
    }
    List<Map<String, Object>> copied = new ArrayList<>(messages.size());
    for (Map<String, Object> message : messages) {
      copied.add(new LinkedHashMap<>(message));
    }
    return copied;
  }

  private static Map<String, Object> message(String severity, String text) {
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("severity", severity);
    message.put("message", text);
    return message;
  }

  private static void deleteRecursively(Path root) {
    try (var stream = Files.walk(root)) {
      List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
      for (Path path : paths) {
        try {
          Files.deleteIfExists(path);
        } catch (Exception ignore) {
        }
      }
    } catch (Exception ignore) {
    }
  }

  public record GenerateExampleResult(
      boolean generated,
      @Nullable String xtfText,
      List<Map<String, Object>> messages,
      int basketCount,
      int objectCount,
      List<Map<String, Object>> objectsByClass,
      List<Map<String, Object>> skippedClasses) {
  }

  public record ValidationResult(
      boolean valid,
      List<Map<String, Object>> messages,
      int errorCount,
      int warningCount) {
  }

  private record Counts(int errors, int warnings) {
  }

  private static class ValidatorLogCollector implements LogListener {
    private final List<Map<String, Object>> sink;

    ValidatorLogCollector(List<Map<String, Object>> sink) {
      this.sink = sink;
    }

    @Override
    public void logEvent(LogEvent event) {
      String severity = null;
      String message = event.getEventMsg();
      String file = null;
      Integer line = null;

      if (event instanceof IoxLogEvent ioxEvent) {
        if (ioxEvent.getEventKind() == IoxLogEvent.ERROR) {
          severity = "ERROR";
        } else if (ioxEvent.getEventKind() == IoxLogEvent.WARNING) {
          severity = "WARNING";
        } else {
          return;
        }
        file = ioxEvent.getDataSource();
        line = ioxEvent.getSourceLineNr();
        String raw = ioxEvent.getRawEventMsg();
        if (raw != null && !raw.isBlank()) {
          message = raw;
        }
      } else {
        int kind = event.getEventKind();
        if (kind == LogEvent.ERROR) {
          severity = "ERROR";
        } else if (kind == LogEvent.ADAPTION) {
          severity = "WARNING";
        } else {
          return;
        }

        if (event instanceof CompilerLogEvent compilerEvent) {
          file = compilerEvent.getFilename();
          line = compilerEvent.getLine() > 0 ? compilerEvent.getLine() : null;
          String raw = compilerEvent.getRawEventMsg();
          if (raw != null && !raw.isBlank()) {
            message = raw;
          }
        }
      }

      Map<String, Object> msg = new LinkedHashMap<>();
      msg.put("severity", severity);
      if (file != null && !file.isBlank()) {
        msg.put("file", file);
      }
      if (line != null && line > 0) {
        msg.put("line", line);
      }
      msg.put("message", message != null ? message : "");
      sink.add(msg);
    }
  }
}
