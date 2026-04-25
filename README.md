# interlis-mcp

`interlis-mcp` is a [Model Context Protocol (MCP)](https://modelcontextprotocol.io) server for generating and validating INTERLIS 2 snippets. It runs over STDIO and is currently built with Spring Boot `4.1.0-M2`, Spring AI `2.0.0-M2`, Gradle `8.14.3`, and Java `21`.

## Overview
- STDIO-only MCP server for IDE agents and desktop MCP clients.
- Tooling for models, topics, classes, structures, associations, domains, geometry helpers, constraints, identifier hygiene, formatting, and validation.
- Runtime verified against MCP protocol `2024-11-05`.
- Current initialize response advertises `tools` and runtime `logging`; resources, prompts, and completions are disabled.

## Architecture
```mermaid
flowchart LR
    Client["MCP client"]
    Transport["STDIO JSON-RPC"]
    Server["Spring Boot app"]
    Scanner["Spring AI MCP annotation scanner"]
    Tools["INTERLIS tool beans"]

    Client --> Transport --> Server --> Scanner --> Tools
```

## Quick Start
1. Ensure `java -version` reports Java 21.
2. Build the executable JAR:
   ```bash
   ./gradlew bootJar
   ```
3. Start the server:
   ```bash
   java -jar build/libs/interlis-mcp.jar
   ```

If multiple JDKs are installed, use the explicit Java 21 binary, for example `/path/to/java-21/bin/java -jar build/libs/interlis-mcp.jar`.

## Verification
```bash
./gradlew test
./gradlew e2eTest
```

## Docker
```bash
./gradlew buildAndPushMultiArchImage
docker run --rm -i interlis-mcp
```

Keep STDIN open and do not allocate a TTY.

## Client Setup
- Claude Desktop and VS Code examples are documented in [docs/USER_GUIDE.md](docs/USER_GUIDE.md).

## Developer Notes
- Build, test, runtime, and annotation-scanner details are documented in [docs/DEVELOPER_GUIDE.md](docs/DEVELOPER_GUIDE.md).

## License
[MIT](LICENSE)
