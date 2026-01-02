# User Guide

This guide explains how to start the `interlis-mcp` server, connect it to common MCP clients, and call the INTERLIS tooling functions it exposes.

## Prerequisites
- Java 21 runtime (Gradle automatically provisions a Java 21 toolchain for builds). 
- Optional: Docker if you prefer containerized execution.

## Starting the server

### Build and run with Gradle
1. Compile the executable JAR:
   ```bash
   ./gradlew bootJar
   ```
2. Launch the STDIO MCP server:
   ```bash
   java -jar build/libs/interlis-mcp.jar
   ```
   Keep the process attached to your terminal. MCP clients communicate with the server through standard input and output streams.

### Run straight from Gradle (development)
During development you can start the server without building the JAR:
```bash
./gradlew bootRun
```
Gradle will launch the Spring Boot application in STDIO mode using the configuration from `src/main/resources/application.properties`.

### Run with Docker
Follow the Docker instructions from the project README:
```bash
./gradlew buildAndPushMultiArchImage
docker run --rm -i interlis-mcp
```
Ensure `stdin_open` remains true and no TTY is allocated so the MCP JSON-RPC stream stays intact.

## Connecting clients

### Claude Desktop
1. Start the server locally (JAR, Gradle, or Docker).
2. Open **Claude Desktop → Settings → Developer → Local MCP servers → Edit Config**.
3. Add an entry to your `claude_desktop_config.json` similar to:
   ```json
   {
      "mcpServers" : {
          "interlis-mcp": {
              "command" : "/Users/stefan/.sdkman/candidates/java/21.0.4-graal/bin/java",
              "args": ["-jar", "/Users/stefan/sources/mcp-interlis/build/libs/interlis-mcp.jar"],
              "env": {
                  "JAVA_TOOL_OPTIONS": "-Xms512m -Xmx512m"
              }
          } 
      }
   }m
   ```
4. Claude can now call the registered tools whenever it needs snippets or validation logic.

### Visual Studio Code
1. Use the `MCP: Add Server` command and follow the instructions.
2. A `mcp.json` file will be openend at the end where you can validate the mcp server settings.

## Tool reference
The server registers all tools in `ToolsConfig` and advertises itself as an STDIO, tool-capable MCP endpoint. Each tool returns an object that at least contains `iliSnippet` (the generated INTERLIS fragment) and, where applicable, a `cursorHint` map indicating where to place the caret in editors.

### Model and topic helpers
- **`createModelSnippet`** – Generate a model skeleton (`MODEL … END`). Parameters: `name` (required), optional `lang`, `uri`, `version`, `iliVersion` (default `2.4`), `includeSolothurnHeader` (default `false`), and additional `imports`. Defaults fill in `lang="de"`, `version=today`, and `uri=https://example.org/<name>`. Returns `iliSnippet` and a cursor hint pointing to the import section.
- **`createTopicSnippet`** – Produce a `TOPIC` block. Parameters: `name` (required), optional `oidType` declaration, and `isAbstract` flag. Returns snippet with placeholder comment for classes.

### Domains and units
- **`createEnumDomainSnippet`** – Emit a `DOMAIN` definition with enumerated items. Provide `name` and an ordered list of `items`.
- **`createNumericDomainSnippet`** – Emit a `DOMAIN` for numeric ranges with optional unit (`unitFqn`). Requires `name`, `min`, and `max`.
- **`createUnitSnippet`** – Define a custom unit by specifying `name`, `kind` (e.g., `LENGTH`), and base unit (`INTERLIS.m`, etc.).

### Class and structure builders
- **`createClassSnippet`** – Build a `CLASS` block with optional abstract flag, `EXTENDS` clause, `OID` declaration, and optional attribute lines. Supplying `attrLines` inserts raw INTERLIS lines; otherwise a placeholder comment is inserted.
- **`createStructureSnippet`** – Analogous to `createClassSnippet` but for `STRUCTURE` definitions without OIDs.
- **`createAssociationSnippet`** – Create an `ASSOCIATION` with role descriptors. Provide `name` and at least two `roles` (each role carries `name`, `classFQN`, and `card`).

### Attribute helpers
- **`createAttributeLineV2`** – Preferred endpoint for a single attribute line. Accepts an object with fields:
  - `name` (identifier),
  - optional `mandatory` boolean,
  - optional `collection` (`NONE`, `LIST_OF`, `BAG_OF`),
  - `typeSpec` which must contain **either** `domainFqn` or a `baseType` object.
    - `baseType` requires `kind` (`TEXT`, `MTEXT`, `NUM_RANGE`, `BOOLEAN`, `COORD`, `POLYLINE`, `SURFACE_SIMPLE`).
    - Numeric ranges need `min`, `max`, and optional `unitFqn`.
    - Text kinds accept optional `length`.
  Returns the attribute line and default cursor hint `{line:0, col:0}`.
- **`createStructureAttributeLine`** – Emit an attribute referencing a `STRUCTURE`. Parameters: `name`, `structureFqn`, optional `mandatory`, and optional `collection` (`NONE`, `LIST_OF`, `BAG_OF`).

### Constraint helpers
- **`createUniqueConstraint`** – Wrap attribute names in a `CONSTRAINTS` block with `UNIQUE`.
- **`createMandatoryConstraint`** – Build a `MANDATORY CONSTRAINT` with an arbitrary boolean expression.
- **`createSetConstraint`** – Produce a `SET CONSTRAINT` block with a multi-line expression.
- **`createPresentIfConstraint`** – Ensure an attribute is present under a condition.
- **`createValueRangeConstraint`** – Restrict an attribute to a specified range.
- **`createExistenceConstraint`** – Require a reference attribute to point to one of the provided class FQNs.

### Identifier utilities
- **`sanitizeIdentifier`** – Convert arbitrary strings into legal INTERLIS identifiers (letters, digits, underscores) and report whether the value was changed.
- **`validateIdentifier`** – Throw if the supplied identifier violates `^[A-Za-z][A-Za-z0-9_]*$`; otherwise return `{valid:true}`.
- **`validateFqn`** – Validate a dot-separated fully qualified name and return `{valid:true}` on success.

## Server metadata
The MCP metadata advertises the server name, version, and capabilities. Tools are registered through Spring's `MethodToolCallbackProvider` so all annotated beans under `ch.so.agi.mcp.tools` become available to clients.

## Tips
- Tool parameters use standard MCP JSON serialization—send lists and objects exactly as shown in the descriptions above.
- Many helpers validate identifiers and FQNs before emitting snippets, returning descriptive errors that MCP clients can surface to users.
- Use the returned `cursorHint` coordinates to position the editor caret after inserting generated snippets.
