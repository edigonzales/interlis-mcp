package ch.so.agi.mcp.service;

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
import ch.interlis.ili2c.metamodel.Model;
import ch.interlis.ili2c.metamodel.TransferDescription;
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
import org.springframework.stereotype.Service;

@Service
public class IliCompilerService {

  private static final ReentrantLock ILI2C_LOCK = new ReentrantLock();

  public CompilationResult compile(String modelText, @Nullable String modelRepositories) {
    return compile(modelText, modelRepositories, "ili2c_model_");
  }

  public CompilationResult compile(String modelText, @Nullable String modelRepositories, String tempPrefix) {
    if (modelText == null || modelText.isBlank()) {
      throw new IllegalArgumentException("Model text is required.");
    }

    Path tempFile;
    try {
      tempFile = Files.createTempFile(tempPrefix, ".ili");
      Files.writeString(tempFile, modelText, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to persist INTERLIS source for ili2c.", e);
    }

    List<Map<String, Object>> messages = new ArrayList<>();
    LogListener collector = new Ili2cLogCollector(messages);

    Ili2cSettings settings = new Ili2cSettings();
    ch.interlis.ili2c.Main.setDefaultIli2cPathMap(settings);
    settings.setIlidirs(modelRepositories != null && !modelRepositories.isBlank()
        ? modelRepositories
        : Ili2cSettings.DEFAULT_ILIDIRS);

    Configuration cfg = new Configuration();
    cfg.addFileEntry(new FileEntry(tempFile.toString(), FileEntryKind.ILIMODELFILE));
    cfg.setAutoCompleteModelList(true);
    cfg.setGenerateWarnings(true);

    TransferDescription transferDescription = null;
    ILI2C_LOCK.lock();
    StdListener stdListener = StdListener.getInstance();
    stdListener.skipInfo(true);
    EhiLogger.getInstance().addListener(collector);
    EhiLogger.getInstance().removeListener(stdListener);
    try {
      transferDescription = ch.interlis.ili2c.Main.runCompiler(cfg, settings, null);
    } catch (Exception e) {
      Map<String, Object> error = new LinkedHashMap<>();
      error.put("severity", "ERROR");
      error.put("message", "ili2c failed: " + e.getMessage());
      messages.add(error);
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

    boolean valid = transferDescription != null
        && messages.stream().noneMatch(message -> "ERROR".equals(message.get("severity")));
    return new CompilationResult(valid, messages, transferDescription);
  }

  public TransferDescription compileOrThrow(String modelText, @Nullable String modelRepositories, String phase) {
    CompilationResult result = compile(modelText, modelRepositories, "ili2c_" + sanitizePrefix(phase) + "_");
    if (!result.valid()) {
      String details = result.messages().isEmpty()
          ? "unknown compiler failure"
          : result.messages().stream()
              .map(message -> String.valueOf(message.get("message")))
              .reduce((left, right) -> left + " | " + right)
              .orElse("unknown compiler failure");
      throw new IllegalStateException("ili2c failed during " + phase + ": " + details);
    }
    return result.transferDescription();
  }

  public String generateModelsFromLastFile(TransferDescription td) {
    TransferDescription pretty = new TransferDescription();
    for (Model model : td.getModelsFromLastFile()) {
      pretty.add(model);
    }

    Interlis2Generator generator = new Interlis2Generator();
    try (StringWriter writer = new StringWriter()) {
      generator.generate(writer, pretty, false);
      return writer.toString();
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to generate INTERLIS source.", e);
    }
  }

  private String sanitizePrefix(String phase) {
    String sanitized = phase == null ? "compile" : phase.replaceAll("[^A-Za-z0-9_]", "_");
    return sanitized.isBlank() ? "compile" : sanitized;
  }

  public record CompilationResult(
      boolean valid,
      List<Map<String, Object>> messages,
      @Nullable TransferDescription transferDescription) {
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
