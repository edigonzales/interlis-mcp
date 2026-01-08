package ch.so.agi.mcp.tools;

import ch.ehi.basics.logging.EhiLogger;
import ch.ehi.basics.logging.LogEvent;
import ch.ehi.basics.logging.LogListener;
import ch.ehi.basics.logging.StdListener;
import ch.interlis.ili2c.Ili2cSettings;
import ch.interlis.ili2c.CompilerLogEvent;
import ch.interlis.ili2c.config.Configuration;
import ch.interlis.ili2c.config.FileEntry;
import ch.interlis.ili2c.config.FileEntryKind;
import ch.interlis.ili2c.generator.Interlis2Generator;
import ch.interlis.ili2c.metamodel.Model;
import ch.interlis.ili2c.metamodel.TransferDescription;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.StringWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class FormattingTools {

  private static final ReentrantLock ILI2C_LOCK = new ReentrantLock();

  @McpTool(
      name = "formatIliModel",
      description = "Formatiert (pretty print) ein INTERLIS-2 Modell mit dem offiziellen ili2c-Formatter. Rückgabe: vollständig formatiertes Modell als Text."
  )
  public String formatIliModel(
      @McpToolParam(description = "INTERLIS-2 Modelltext", required = true) String modelText,
      @McpToolParam(description = "Optionale MODELREPOS-/ilidirs-Definition, z. B. 'https://models.interlis.ch;https://geo.so.ch/models'", required = false)
      @Nullable String modelRepositories
  ) {
    if (modelText == null || modelText.isBlank()) {
      throw new IllegalArgumentException("Model text is required.");
    }

    Path tempFile;
    try {
      tempFile = Files.createTempFile("ili2c_model_", ".ili");
      Files.writeString(tempFile, modelText, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to persist INTERLIS source for formatting.", e);
    }

    Ili2cSettings settings = new Ili2cSettings();
    ch.interlis.ili2c.Main.setDefaultIli2cPathMap(settings);
    if (modelRepositories != null && !modelRepositories.isBlank()) {
      settings.setIlidirs(modelRepositories);
    } else {
      settings.setIlidirs(Ili2cSettings.DEFAULT_ILIDIRS);
    }

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
        throw new IllegalStateException("ili2c failed: " + details);
      }

      TransferDescription pretty = new TransferDescription();
      for (Model model : td.getModelsFromLastFile()) {
        pretty.add(model);
      }

      Interlis2Generator gen = new Interlis2Generator();
      try (StringWriter writer = new StringWriter()) {
        gen.generate(writer, pretty, false);
        return writer.toString();
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to format INTERLIS source.", e);
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
        String file = compilerEvent.getFilename();
        int line = compilerEvent.getLine();
        String raw = compilerEvent.getRawEventMsg();
        if (raw != null && !raw.isBlank()) {
          message = raw;
        }
        if (file != null && !file.isBlank()) {
          message = file + (line > 0 ? ":" + line : "") + ": " + message;
        }
      }

      if (message != null && !message.isBlank()) {
        sink.add(message);
      }
    }
  }
}
