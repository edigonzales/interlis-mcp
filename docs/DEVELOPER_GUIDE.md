# Developer Guide

This guide describes how `interlis-mcp` is built, wired, tested, and extended.

## Compatibility Baseline
- Spring Boot `4.1.0-M2`
- Spring AI `2.0.0-M2`
- Gradle Wrapper `8.14.3`
- Java toolchain and runtime `21`
- MCP protocol observed in manual smoke tests: `2024-11-05`

## Project Structure
- `src/main/java/ch/so/agi/mcp/Application.java`
  Starts the non-web Spring Boot application.
- `src/main/java/ch/so/agi/mcp/tools/*`
  MCP tool beans. Public methods are annotated with `@McpTool` and `@McpToolParam`.
- `src/main/java/ch/so/agi/mcp/model/*`
  DTOs used by structured tools such as `createAttributeLine`.
- `src/main/java/ch/so/agi/mcp/util/NameValidator.java`
  Shared INTERLIS identifier and FQN validation.
- `src/test/java`
  Unit and contract tests.
- `src/e2e/java`
  End-to-end STDIO test against the packaged JAR.

## Tool Discovery
The project no longer has a manual `ToolsConfig`. Tool registration is provided by Spring AI's MCP annotation scanner auto-configuration from `spring-ai-starter-mcp-server`.

Important upstream detail: as of `2026-03-14`, the official Spring AI `2.0.0-M2` starter still resolves MCP tool annotations transitively from `org.springaicommunity:mcp-annotations:0.8.0`. That is why tool classes still import:

```java
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
```

Nullability annotations were migrated from `org.springframework.lang.Nullable` to `org.jspecify.annotations.Nullable`.

## Build And Run
```bash
./gradlew bootJar
java -jar build/libs/interlis-mcp.jar
```

The application is STDIO-only. `spring.main.web-application-type=none` disables any web stack at runtime.

During development:

```bash
./gradlew bootRun
```

## Testing
- Unit and integration tests:
  ```bash
  ./gradlew test
  ```
- End-to-end STDIO test:
  ```bash
  ./gradlew e2eTest
  ```

`e2eTest` depends on `bootJar` and starts the server with the same Java binary as the test JVM. This avoids mismatches between the Gradle daemon JDK and the packaged application JDK.

## Runtime Configuration
Application settings live in `src/main/resources/application.properties`.

Key settings:
- `spring.main.web-application-type=none`
- `spring.ai.mcp.server.stdio=true`
- `spring.ai.mcp.server.type=SYNC`
- `spring.ai.mcp.server.capabilities.tool=true`
- `spring.ai.mcp.server.capabilities.resource=false`
- `spring.ai.mcp.server.capabilities.prompt=false`
- `spring.ai.mcp.server.capabilities.completion=false`
- `spring.ai.mcp.server.name=interlis-mcp`
- `spring.ai.mcp.server.version=${mcpServerVersion}` in `application.properties`, expanded at build time
- `spring.ai.mcp.server.instructions=...`

The server version placeholder is expanded during `processResources` from Gradle's `project.version`. Local builds therefore still expose `0.0.LOCALBUILD`, while CI builds expose the computed build version automatically.

The current runtime advertises `tools` and the MCP runtime's built-in `logging` capability. Resources, prompts, and completions are intentionally disabled.

## Logging
`src/main/resources/logback-spring.xml` writes to STDERR only. The root logger is `WARN`, and noisy startup loggers are suppressed to keep the STDIO transport readable.

## Data Contracts
The most structured tool is `createAttributeLine`. In MCP, it currently accepts a nested payload under `req`:

```json
{
  "req": {
    "name": "hoehe",
    "typeSpec": {
      "baseType": {
        "kind": "NUM_RANGE",
        "min": 0.0,
        "max": 100.0,
        "unitFqn": "INTERLIS.m"
      }
    }
  }
}
```

Relevant DTOs:
- `AttributeLineRequest`
- `TypeSpec`
- `BaseType`
- `ReferenceTypeSpec`
- `BlackboxTypeSpec`
- `EnumTreeValueTypeSpec`
- `BasketTypeSpec`
- `ObjectTypeSpec`
- `MetaobjectTypeSpec`
- `AttributeLineResponse`

`TypeSpec` is now a strict one-of union across these families. `enumTreeValueType` is modeled via a named enum-tree domain FQN because raw `ENUMTREEVAL` is not accepted by ili2c in attribute declarations.

## Extension Rules
When adding a new tool:
1. Add a Spring `@Component` under `ch.so.agi.mcp.tools`.
2. Annotate the public method with `@McpTool`.
3. Mark optional parameters explicitly with `required = false`.
4. Prefer `NameValidator.ascii()` for identifiers and FQNs.
5. Add at least one focused unit test and, if the MCP contract matters, a schema assertion in `ToolRegistrationContractTest`.

## Docker Packaging
`gradle/docker.gradle` builds the container image from the packaged JAR:

```bash
./gradlew buildAndPushMultiArchImage
```
