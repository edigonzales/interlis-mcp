package ch.so.agi.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Identifies exact UTF-8 model text, not compilation success or imported dependencies. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelHashes(@Nullable String model, @Nullable String before,
    @Nullable String after, @Nullable String candidate) {
  public static @Nullable String sha256(@Nullable String text) {
    if (text == null) return null;
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Java runtime must support SHA-256", impossible);
    }
  }
  public static ModelHashes model(@Nullable String text) { return new ModelHashes(sha256(text), null, null, null); }
  public static ModelHashes change(@Nullable String before, @Nullable String after) {
    return new ModelHashes(null, sha256(before), sha256(after), null);
  }
  public static Map<String, Object> attach(Map<String, Object> result, ModelHashes hashes) {
    var response = new LinkedHashMap<>(result);
    response.put("modelHashes", hashes);
    return response;
  }
  public static IliAuthoringResult attach(IliAuthoringResult result, @Nullable String before) {
    result.modelHashes = new ModelHashes(null, sha256(before), sha256(result.updatedModelText), sha256(result.candidateModelText));
    return result;
  }
}
