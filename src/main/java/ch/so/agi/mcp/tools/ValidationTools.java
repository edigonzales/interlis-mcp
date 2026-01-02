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
import ch.interlis.ili2c.metamodel.TransferDescription;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class ValidationTools {

  private static final ReentrantLock ILI2C_LOCK = new ReentrantLock();

  @McpTool(
      name = "validateIliModel",
      description = "Validiert ein INTERLIS-2 Modell mit dem offiziellen ili2c-Compiler. Rückgabe: {valid:bool, messages:[{severity,file?,line?,message}]}"
  )
  public Map<String, Object> validateIliModel(
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
      throw new UncheckedIOException("Unable to persist INTERLIS source for validation.", e);
    }

    List<Map<String, Object>> messages = new ArrayList<>();
    LogListener collector = new Ili2cLogCollector(messages);
    boolean valid;

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

    ILI2C_LOCK.lock();
    StdListener stdListener = StdListener.getInstance();
    stdListener.skipInfo(true);
    EhiLogger.getInstance().addListener(collector);
    EhiLogger.getInstance().removeListener(stdListener);
    try {
      TransferDescription td = ch.interlis.ili2c.Main.runCompiler(cfg, settings, null);
      valid = td != null && messages.stream().noneMatch(m -> "ERROR".equals(m.get("severity")));
    } catch (Exception e) {
      Map<String, Object> error = new LinkedHashMap<>();
      error.put("severity", "ERROR");
      error.put("message", "ili2c failed: " + e.getMessage());
      messages.add(error);
      valid = false;
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

    return Map.of(
        "valid", valid,
        "messages", messages
    );
  }

  private static class Ili2cLogCollector implements LogListener {
    private final List<Map<String, Object>> sink;

    Ili2cLogCollector(List<Map<String, Object>> sink) {
      this.sink = sink;
    }

    @Override
    public void logEvent(LogEvent event) {
      int kind = event.getEventKind();
      if (kind != LogEvent.ERROR && kind != LogEvent.ADAPTION) {
        return;
      }

      String severity = kind == LogEvent.ERROR ? "ERROR" : "WARNING";
      String message = event.getEventMsg();
      String file = null;
      Integer line = null;

      if (event instanceof CompilerLogEvent compilerEvent) {
        file = compilerEvent.getFilename();
        line = compilerEvent.getLine() > 0 ? compilerEvent.getLine() : null;
        String raw = compilerEvent.getRawEventMsg();
        if (raw != null && !raw.isBlank()) {
          message = raw;
        }
      }

      Map<String, Object> msg = new LinkedHashMap<>();
      msg.put("severity", severity);
      if (file != null && !file.isBlank()) {
        msg.put("file", file);
      }
      if (line != null) {
        msg.put("line", line);
      }
      msg.put("message", message != null ? message : "");
      sink.add(msg);
    }
  }
}
