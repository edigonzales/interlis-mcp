package ch.so.agi.mcp.service;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Captures the immutable benchmark artifact and dependency identity once per server process. */
public final class RuntimeIdentity {
  private static final Map<String, Object> IDENTITY = capture();
  private RuntimeIdentity() {}
  public static Map<String, Object> current() { return IDENTITY; }

  private static Map<String, Object> capture() {
    return capture(System.getenv(), System.getProperty("sun.java.command", ""));
  }

  static Map<String, Object> capture(Map<String, String> environment, String command) {
    var result = new LinkedHashMap<String, Object>();
    result.put("schemaVersion", 1);
    result.put("compilerVersion", ch.interlis.ili2c.Ili2c.getVersion());
    result.put("verified", false);
    String artifact = environment.get("INTERLIS_MCP_IMMUTABLE_JAR");
    if (artifact == null) {
      result.put("mode", "UNPINNED");
      return Collections.unmodifiableMap(result);
    }
    try {
      Path jar = Path.of(artifact).toRealPath();
      if (!command.equals(jar.toString())) throw new IllegalStateException("Launched JAR differs from declared immutable artifact");
      String jarHash = sha256(jar);
      if (!jar.getParent().getFileName().toString().equals(jarHash)) throw new IllegalStateException("JAR content address mismatch");
      Path provenance = jar.resolveSibling("build.json");
      var build = new ObjectMapper().readTree(Files.readString(provenance));
      if (!jarHash.equals(build.path("jarSha256").asText())) throw new IllegalStateException("Build manifest mismatch");
      Path manifest = Path.of(environment.get("INTERLIS_MCP_DEPENDENCY_MANIFEST")).toRealPath();
      var deps = new ObjectMapper().readTree(Files.readString(manifest));
      var expectedFiles = new HashSet<String>();
      for (var file : deps.path("files")) {
        expectedFiles.add(file.path("path").asText());
        Path path = manifest.getParent().resolve(file.path("path").asText()).toRealPath();
        if (!path.startsWith(manifest.getParent()) || !sha256(path).equals(file.path("sha256").asText()))
          throw new IllegalStateException("Dependency snapshot mismatch: " + path.getFileName());
      }
      try (var files = Files.walk(manifest.getParent())) {
        var actualFiles = files.filter(Files::isRegularFile).filter(p -> !p.equals(manifest)
            && !p.getFileName().toString().equals(".DS_Store"))
            .map(p -> manifest.getParent().relativize(p).toString()).collect(java.util.stream.Collectors.toSet());
        if (!actualFiles.equals(expectedFiles)) throw new IllegalStateException("Unlisted dependency snapshot file");
      }
      String repositories = environment.get("INTERLIS_MCP_MODEL_REPOSITORIES");
      if (!manifest.getParent().toString().equals(repositories)) throw new IllegalStateException("Benchmark requires only its local dependency snapshot");
      result.put("mode", "IMMUTABLE_BENCHMARK");
      result.put("compilerVersion", libraryVersion(jar, "ili2c-core"));
      result.put("validatorVersion", libraryVersion(jar, "ilivalidator"));
      result.put("ioxIliVersion", libraryVersion(jar, "iox-ili"));
      result.put("jarSha256", jarHash);
      result.put("buildId", build.path("buildId").asText());
      result.put("dependencySnapshotSha256", sha256(manifest));
      result.put("verified", true);
      return Collections.unmodifiableMap(result);
    } catch (Exception e) {
      // A declared benchmark artifact may never silently fall back to an unpinned server.
      throw new IllegalStateException("Benchmark runtime identity verification failed", e);
    }
  }

  private static String libraryVersion(Path jar, String artifact) throws Exception {
    String prefix = "BOOT-INF/lib/" + artifact + "-";
    try (var archive = new java.util.jar.JarFile(jar.toFile())) {
      var matches = archive.stream().map(java.util.jar.JarEntry::getName)
          .filter(name -> name.startsWith(prefix) && name.endsWith(".jar")).toList();
      if (matches.size() != 1) throw new IllegalStateException("Ambiguous bundled library: " + artifact);
      return matches.getFirst().substring(prefix.length(), matches.getFirst().length() - 4);
    }
  }

  public static String sha256(Path path) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    try (var stream = Files.newInputStream(path)) {
      byte[] buffer = new byte[65536];
      int size;
      while ((size = stream.read(buffer)) != -1) digest.update(buffer, 0, size);
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
