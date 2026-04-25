package ch.so.agi.mcp.tools;

import ch.ehi.basics.logging.EhiLogger;
import ch.ehi.basics.logging.LogEvent;
import ch.ehi.basics.logging.LogListener;
import ch.ehi.basics.logging.StdListener;
import ch.interlis.ili2c.CompilerLogEvent;
import ch.interlis.ili2c.Ili2cSettings;
import ch.interlis.ili2c.config.Configuration;
import ch.interlis.ili2c.config.FileEntry;
import ch.interlis.ili2c.config.FileEntryKind;
import ch.interlis.ili2c.generator.Interlis2Generator;
import ch.interlis.ili2c.metamodel.AssociationDef;
import ch.interlis.ili2c.metamodel.AttributeDef;
import ch.interlis.ili2c.metamodel.Domain;
import ch.interlis.ili2c.metamodel.Element;
import ch.interlis.ili2c.metamodel.Model;
import ch.interlis.ili2c.metamodel.Table;
import ch.interlis.ili2c.metamodel.Topic;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.ili2c.metamodel.Unit;
import ch.so.agi.mcp.model.RenameElementKind;
import ch.so.agi.mcp.util.NameValidator;
import java.beans.PropertyVetoException;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class RenameTools {

  private static final ReentrantLock ILI2C_LOCK = new ReentrantLock();

  @McpTool(
      name = "renameModelElement",
      description = "Benennt ein INTERLIS-Modellelement robust über ili2c-Metamodell und vollständige Modell-Neugenerierung um. Nicht source-preserving."
  )
  public Map<String, Object> renameModelElement(
      @McpToolParam(description = "Vollständiger INTERLIS-2 Modelltext", required = true) String modelText,
      @McpToolParam(description = "Vollqualifizierter Name des umzubenennenden Elements", required = true) String elementFqn,
      @McpToolParam(description = "Optionale Guard-Elementart: MODEL, TOPIC, CLASS_OR_STRUCTURE, ASSOCIATION, DOMAIN, UNIT oder ATTRIBUTE", required = false) @Nullable RenameElementKind expectedKind,
      @McpToolParam(description = "Neuer einfacher Name des Elements", required = true) String newName,
      @McpToolParam(description = "Optionale MODELREPOS-/ilidirs-Definition", required = false) @Nullable String modelRepositories
  ) {
    if (modelText == null || modelText.isBlank()) {
      throw new IllegalArgumentException("Model text is required.");
    }
    if (elementFqn == null || elementFqn.isBlank()) {
      throw new IllegalArgumentException("elementFqn is required.");
    }
    if (newName == null || newName.isBlank()) {
      throw new IllegalArgumentException("newName is required.");
    }

    NameValidator.ascii().validateFqn(elementFqn.trim(), "Element FQN");
    NameValidator.ascii().validateIdent(newName.trim(), "New element name");

    CompilationResult compilation = compile(modelText, modelRepositories, "rename");
    TransferDescription td = compilation.transferDescription();

    Element element = td.getElement(elementFqn.trim());
    if (element == null) {
      throw new IllegalArgumentException("Element not found: " + elementFqn.trim());
    }
    RenameElementKind actualKind = detectKind(element);
    if (expectedKind != null && !matchesKind(element, expectedKind)) {
      throw new IllegalArgumentException(
          "Element '" + elementFqn.trim() + "' is not of expected kind " + expectedKind + ".");
    }

    String oldScopedName = element.getScopedName();
    renameElement(element, newName.trim());

    String regenerated = generateModelsFromLastFile(td);
    CompilationResult validation = compile(regenerated, modelRepositories, "validation after rename");

    return Map.of(
        "updatedModelText", regenerated,
        "oldElementFqn", oldScopedName,
        "newElementFqn", resolveRenamedScopedName(validation.transferDescription(), oldScopedName, newName.trim()),
        "expectedKind", (expectedKind != null ? expectedKind : actualKind).name(),
        "notes", List.of("Model was fully regenerated with ili2c. Layout and declaration order may differ from the input.")
    );
  }

  private RenameElementKind detectKind(Element element) {
    if (element instanceof Model) {
      return RenameElementKind.MODEL;
    }
    if (element instanceof Topic) {
      return RenameElementKind.TOPIC;
    }
    if (element instanceof AssociationDef) {
      return RenameElementKind.ASSOCIATION;
    }
    if (element instanceof Table) {
      return RenameElementKind.CLASS_OR_STRUCTURE;
    }
    if (element instanceof Domain) {
      return RenameElementKind.DOMAIN;
    }
    if (element instanceof Unit) {
      return RenameElementKind.UNIT;
    }
    if (element instanceof AttributeDef) {
      return RenameElementKind.ATTRIBUTE;
    }

    throw new IllegalArgumentException("Renaming is not supported for element type: " + element.getClass().getSimpleName());
  }

  private boolean matchesKind(Element element, RenameElementKind expectedKind) {
    return switch (expectedKind) {
      case MODEL -> element instanceof Model;
      case TOPIC -> element instanceof Topic;
      case CLASS_OR_STRUCTURE -> element instanceof Table && !(element instanceof AssociationDef);
      case ASSOCIATION -> element instanceof AssociationDef;
      case DOMAIN -> element instanceof Domain;
      case UNIT -> element instanceof Unit;
      case ATTRIBUTE -> element instanceof AttributeDef;
    };
  }

  private void renameElement(Element element, String newName) {
    try {
      if (element instanceof Model model) {
        model.setName(newName);
      } else if (element instanceof Topic topic) {
        topic.setName(newName);
      } else if (element instanceof AssociationDef association) {
        association.setName(newName);
      } else if (element instanceof Table table) {
        table.setName(newName);
      } else if (element instanceof Domain domain) {
        domain.setName(newName);
      } else if (element instanceof Unit unit) {
        unit.setName(newName);
      } else if (element instanceof AttributeDef attribute) {
        attribute.setName(newName);
      } else {
        throw new IllegalArgumentException("Renaming is not supported for element type: " + element.getClass().getSimpleName());
      }
    } catch (PropertyVetoException e) {
      throw new IllegalStateException("Unable to rename element: " + e.getMessage(), e);
    }
  }

  private String resolveRenamedScopedName(TransferDescription td, String oldScopedName, String newName) {
    int split = oldScopedName.lastIndexOf('.');
    if (split < 0) {
      return newName;
    }

    String newScopedName = oldScopedName.substring(0, split + 1) + newName;
    Element renamed = td.getElement(newScopedName);
    return renamed != null ? renamed.getScopedName() : newScopedName;
  }

  private String generateModelsFromLastFile(TransferDescription td) {
    TransferDescription pretty = new TransferDescription();
    for (Model model : td.getModelsFromLastFile()) {
      pretty.add(model);
    }

    Interlis2Generator generator = new Interlis2Generator();
    try (StringWriter writer = new StringWriter()) {
      generator.generate(writer, pretty, false);
      return writer.toString();
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to regenerate INTERLIS source.", e);
    }
  }

  private CompilationResult compile(String modelText, @Nullable String modelRepositories, String phase) {
    Path tempFile;
    try {
      tempFile = Files.createTempFile("ili2c_rename_", ".ili");
      Files.writeString(tempFile, modelText, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to persist INTERLIS source for " + phase + ".", e);
    }

    Ili2cSettings settings = new Ili2cSettings();
    ch.interlis.ili2c.Main.setDefaultIli2cPathMap(settings);
    settings.setIlidirs(modelRepositories != null && !modelRepositories.isBlank()
        ? modelRepositories
        : Ili2cSettings.DEFAULT_ILIDIRS);

    Configuration cfg = new Configuration();
    cfg.addFileEntry(new FileEntry(tempFile.toString(), FileEntryKind.ILIMODELFILE));
    cfg.setAutoCompleteModelList(true);
    cfg.setGenerateWarnings(true);

    List<String> errors = new ArrayList<>();
    LogListener collector = new Ili2cErrorCollector(errors);

    ILI2C_LOCK.lock();
    StdListener stdListener = StdListener.getInstance();
    stdListener.skipInfo(true);
    EhiLogger.getInstance().addListener(collector);
    EhiLogger.getInstance().removeListener(stdListener);
    try {
      TransferDescription td = ch.interlis.ili2c.Main.runCompiler(cfg, settings, null);
      if (td == null || !errors.isEmpty()) {
        String details = errors.isEmpty() ? "unknown compiler failure" : String.join(" | ", errors);
        throw new IllegalStateException("ili2c failed during " + phase + ": " + details);
      }
      return new CompilationResult(td);
    } finally {
      EhiLogger.getInstance().addListener(stdListener);
      EhiLogger.getInstance().removeListener(collector);
      stdListener.skipInfo(false);
      ILI2C_LOCK.unlock();
      try {
        Files.deleteIfExists(tempFile);
      } catch (Exception ignore) {
      }
    }
  }

  private record CompilationResult(TransferDescription transferDescription) {
  }

  private static class Ili2cErrorCollector implements LogListener {
    private final List<String> sink;

    Ili2cErrorCollector(List<String> sink) {
      this.sink = sink;
    }

    @Override
    public void logEvent(LogEvent event) {
      if (event.getEventKind() != LogEvent.ERROR) {
        return;
      }

      String message = event.getEventMsg();
      if (event instanceof CompilerLogEvent compilerEvent) {
        String raw = compilerEvent.getRawEventMsg();
        if (raw != null && !raw.isBlank()) {
          message = raw;
        }
      }
      if (message != null && !message.isBlank()) {
        sink.add(message);
      }
    }
  }
}
