package ch.so.agi.mcp.service;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class RuntimeIdentityTest {
  @TempDir Path directory;
  @Test void rejectsAnOldLaunchedJarChangedDependencyAndWrongContentAddress() throws Exception {
    var mapper=new ObjectMapper();
    Path jar=directory.resolve("temporary.jar");
    try(var zip=new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
      for(String library:List.of("ili2c-core-5.6.8","ilivalidator-1.14.3","iox-ili-1.24.4")) {
        zip.putNextEntry(new java.util.jar.JarEntry("BOOT-INF/lib/"+library+".jar"));zip.closeEntry();
      }
    }
    String hash=RuntimeIdentity.sha256(jar);
    Path artifact=directory.resolve(hash);Files.createDirectory(artifact);
    jar=Files.move(jar,artifact.resolve("interlis-mcp.jar")).toRealPath();
    Files.writeString(artifact.resolve("build.json"),mapper.writeValueAsString(Map.of("jarSha256",hash,"buildId","source-identity")));
    Path deps=directory.resolve("deps");Files.createDirectory(deps);deps=deps.toRealPath();
    Path model=deps.resolve("A.ili");Files.writeString(model,"dependency bytes");
    Files.writeString(deps.resolve("manifest.json"),mapper.writeValueAsString(Map.of("files",List.of(Map.of("path","A.ili","sha256",RuntimeIdentity.sha256(model))))));
    var env=Map.of("INTERLIS_MCP_IMMUTABLE_JAR",jar.toString(),"INTERLIS_MCP_DEPENDENCY_MANIFEST",deps.resolve("manifest.json").toString(),"INTERLIS_MCP_MODEL_REPOSITORIES",deps.toString());
    String launched=jar.toString();
    assertThat(RuntimeIdentity.capture(env,launched)).containsEntry("verified",true).containsEntry("jarSha256",hash);
    assertThatThrownBy(() -> RuntimeIdentity.capture(env,"old.jar")).isInstanceOf(IllegalStateException.class);
    Files.writeString(model,"changed bytes");
    assertThatThrownBy(() -> RuntimeIdentity.capture(env,launched)).isInstanceOf(IllegalStateException.class);
    Files.writeString(jar,"changed jar bytes");
    assertThatThrownBy(() -> RuntimeIdentity.capture(env,launched)).isInstanceOf(IllegalStateException.class);
  }
  @Test void ordinaryServerDoesNotClaimBenchmarkProvenance() {
    assertThat(RuntimeIdentity.capture(Map.of(),"Application")).containsEntry("verified",false).containsEntry("mode","UNPINNED");
  }
}
