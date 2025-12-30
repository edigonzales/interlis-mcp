# Developer Guide

This document supports contributors who want to build, test, and extend the `interlis-mcp` server.

## Project structure
- **Spring Boot application** – `Application` starts the STDIO-only Spring context that hosts the MCP server. `spring.main.web-application-type=none` disables the web container so the process only reads and writes via STDIN/STDOUT.
- **Tool beans** – Classes in `ch.so.agi.mcp.tools` expose MCP tools using `@Tool` annotations. `ToolsConfig` registers all tool beans with Spring AI's `MethodToolCallbackProvider`, making each annotated method callable by clients.
- **Model classes** – Types in `ch.so.agi.mcp.model` define the JSON payloads for complex tools such as `createAttributeLineV2`.
- **Utility classes** – `NameValidator` centralizes identifier validation logic shared by multiple tools.

## Build and run
```bash
./gradlew bootJar
java -jar build/libs/interlis-mcp.jar
```
The Gradle build configures a Java 21 toolchain, names the Boot archive `interlis-mcp.jar`, and depends on `spring-ai-starter-mcp-server` to provide the MCP runtime wiring. During iteration you can run `./gradlew bootRun` to start the STDIO server without packaging.

## Testing
- Unit tests: `./gradlew test`
- End-to-end tests (tagged `@Tag("e2e")`): `./gradlew e2eTest`

Both tasks use JUnit Platform configuration defined in `build.gradle`.

## Configuration
Application-level settings live in `src/main/resources/application.properties`:
- Declares the MCP server name (`interlis-mcp`), version (`0.1.0`), and that only tool capabilities are enabled.
- Enables STDIO transport (`spring.ai.mcp.server.stdio=true`) and disables other MCP feature sets (resources, prompts, completions).
- Silences Spring Boot logging for cleaner STDIO channels.

Adjust these properties if you change metadata or add new capability types.

## Tool registry
All tools are wired through `ToolsConfig`, which injects each `@Component` tool class into a single `MethodToolCallbackProvider`. Adding a new tool only requires:
1. Creating a Spring component under `ch.so.agi.mcp.tools`.
2. Annotating public methods with `@Tool` and declaring parameters with `@ToolParam` (or POJO payloads).
3. Ensuring the component is listed in the `toolObjects` builder call.

Spring AI converts incoming MCP JSON-RPC payloads into method arguments and serializes return values back to the client.

## Data contracts
For attribute creation the server uses rich DTOs:
- `AttributeLineRequest` carries the attribute name, optional mandatory flag, optional collection, and a mutually exclusive `typeSpec`.
- `TypeSpec` enforces that either `domainFqn` or `baseType` is provided.
- `BaseType` supplies strong validation for numeric ranges, text lengths, and supported primitive kinds.
- `AttributeLineResponse` standardizes the returned snippet and cursor hint map.
Re-use these classes when introducing new tools that work with attributes to stay consistent with existing validation logic.

## Docker packaging
The Gradle build delegates to `gradle/docker.gradle` so you can run `./gradlew buildAndPushMultiArchImage` from the repository root. The resulting image launches the STDIO server immediately, making it convenient for MCP clients that manage tools as container commands.

## Coding guidelines
- Prefer `NameValidator.ascii()` to enforce classic INTERLIS identifier rules when accepting names or FQNs.
- Throw informative `IllegalArgumentException`s so MCP clients can surface actionable errors.
- Return `cursorHint` positions whenever you can to help editors position user cursors after inserting snippets.
