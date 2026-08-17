# Developer Guide

This guide describes how `interlis-mcp` is built, wired, tested, and extended.

## Compatibility Baseline
- Spring Boot `4.1.0`
- Spring AI `2.0.0`
- Gradle Wrapper `8.14.3`
- Java toolchain and runtime `21`
- MCP protocol observed in automated STDIO tests: `2025-06-18`

## Project Structure
- `src/main/java/ch/so/agi/mcp/Application.java`
  Starts the non-web Spring Boot application.
- `src/main/java/ch/so/agi/mcp/tools/*`
  MCP tool beans. Public methods are annotated with `@McpTool` and `@McpToolParam`.
- `src/main/java/ch/so/agi/mcp/analysis/*`
  Structural model analysis and semantic before/after review tools built on ili2c.
- `src/main/java/ch/so/agi/mcp/knowledge/*`
  Curated modeling rules, MCP resources, MCP prompts, high-level model review, rule checks, and local `.ili` corpus search.
- `src/main/java/ch/so/agi/mcp/service/IliCompilerService.java`
  Shared ili2c compilation service and compiler-diagnostic normalization, including source excerpts for diagnostics from the submitted model text.
- `src/main/java/ch/so/agi/mcp/service/XtfService.java`
  Shared XTF generation and XTF validation service (`generateExampleXtf`, `validateXtf`).
- `src/main/java/ch/so/agi/mcp/model/*`
  DTOs used by structured tools such as `createAttributeLine`.
- `src/main/java/ch/so/agi/mcp/util/NameValidator.java`
  Shared INTERLIS identifier and FQN validation, including reserved-word checks.
- `src/test/java`
  Unit, contract, compile-based semantic tests, and deterministic agentic golden scenarios.
- `src/e2e/java`
  End-to-end STDIO test against the packaged JAR.

## Tool Discovery
The project no longer has a manual `ToolsConfig`. Tool registration is provided by Spring AI's MCP annotation scanner auto-configuration from `spring-ai-starter-mcp-server`.

With Spring AI `2.0.0`, MCP annotations come from the official Spring AI package:

```java
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.ai.mcp.annotation.McpArg;
```

Nullability annotations use `org.jspecify.annotations.Nullable`.

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
- Unit, contract, semantic, and golden-scenario tests:
  ```bash
  ./gradlew test
  ```
- End-to-end STDIO test:
  ```bash
  ./gradlew e2eTest
  ```

`e2eTest` depends on `bootJar` and starts the server with the same Java binary as the test JVM. This avoids mismatches between the Gradle daemon JDK and the packaged application JDK.

`src/test/java/ch/so/agi/mcp/eval/AgenticGoldenScenariosTest.java` freezes the intended agentic behavior without introducing an LLM test framework. The scenarios call real deterministic tools where possible and cover:
- one high-level `reviewIliModel` for a complete new model
- before/after `reviewIliChange` followed by the current prompt's final `reviewIliModel`, including compiler-call counts
- compiler repair diagnostics with `sourceExcerpt`
- missing cardinalities and generated names remaining open domain questions
- `findSimilarModels` followed by `readModelExample`
- breaking-change detection
- rejection of routine low-level `validate + analyze + checkRules` triple checks

These tests protect workflow contracts; they are not a simulation of an LLM planner.

## Runtime Configuration
Application settings live in `src/main/resources/application.properties`.

Key settings:
- `spring.main.web-application-type=none`
- `spring.ai.mcp.server.stdio=true`
- `spring.ai.mcp.server.type=SYNC`
- `spring.ai.mcp.server.capabilities.tool=true`
- `spring.ai.mcp.server.capabilities.resource=true`
- `spring.ai.mcp.server.capabilities.prompt=true`
- `spring.ai.mcp.server.capabilities.completion=false`
- `interlis.knowledge.model-paths=`
- `interlis.knowledge.max-model-bytes=1048576`
- `interlis.knowledge.max-search-results=10`
- `spring.ai.mcp.server.name=interlis-mcp`
- `spring.ai.mcp.server.version=${mcpServerVersion}` in `application.properties`, expanded at build time
- `spring.ai.mcp.server.instructions=...`

The server version placeholder is expanded during `processResources` from Gradle's `project.version`. Local builds therefore still expose `0.0.LOCALBUILD`, while CI builds expose the computed build version automatically.

The current runtime advertises `tools`, `resources`, `prompts`, and the MCP runtime's built-in `logging` capability. Completions are intentionally disabled.

`interlis.knowledge.model-paths` is a comma-separated list of local files or directories. Directories are scanned recursively for `.ili` files. The MVP uses an in-memory scan and lexical scoring only; it does not use embeddings, a database, or network access at runtime.

## Review And Diagnostic Contracts
`reviewIliModel` is the standard high-level review for one complete current model. It compiles exactly once and combines:
- compiler validity and `compilerDiagnostics`
- structural/semantic `structure`
- automated `ruleFindings`
- `manualChecks`
- `openQuestions`

`reviewIliChange` is the standard before/after review. It compiles each version exactly once, computes semantic `added`/`removed`/`changed` items, derives `impact` and `potentiallyBreakingChanges`, and reuses the already analyzed after-model for `afterReview` instead of compiling it a third time.

`validateIliModel`, `analyzeIliModel`, and `checkModelingRules` remain low-level tools for targeted diagnostics. Tool descriptions, prompts, and `interlis://knowledge/tool-guide` explicitly discourage calling all three routinely after a high-level review.

`IliCompilerService` enriches diagnostics that belong to the submitted model text with a `sourceExcerpt` containing `startLine`, `endLine`, and a small nearby text window. Diagnostics attributable to other files are not given an excerpt from the submitted model.

## Structural Analysis
`ModelAnalysisTools` deliberately exposes selected ili2c metamodel semantics rather than serializing the complete metamodel generically. Current parser-backed structure includes, where applicable:
- `extends`
- `abstract` / `final`
- topic `dependsOn`
- association roles with name, target, cardinality, aggregation kind, ordering, role inheritance, and `external`
- deterministic `typeText` fingerprints used by semantic change review for important type semantics

The same analyzed data is reused by `reviewIliChange`, so structural changes can participate in semantic diffing without a separate parsing layer.

## XTF Services
`XtfService` provides two capabilities:

1. `generateExampleXtf`
   - Compiles the model via `IliCompilerService`.
   - Creates deterministic minimal transfer content via `iox-ili` (`XtfWriter` + IOX events).
   - Generates only identifiable, non-abstract, non-implicit classes from the last compiled model file.
   - Fills only mandatory attributes for a safe subset (`TEXT`, `NUMERIC`, `BOOLEAN`, enum, enum-tree value, coordinate, references to generated classes).
   - If a class contains unsupported mandatory types, the class is not emitted and appears in `skippedClasses`.

2. `validateXtf`
   - Compiles the model first (same compiler pathway as model tools).
   - Persists model and XTF text to temporary files.
   - Validates with `org.interlis2.validator.Validator` (core `ilivalidator` API, no custom functions in MVP).
   - Collects structured ERROR/WARNING messages through an `EhiLogger` listener.

Both paths are text-based in MVP (`xtfText` payloads, no file upload contract).

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

`TypeSpec.requireSingleType()` enforces a strict one-of selection across these families. `enumTreeValueType` is modeled via a named enum-tree domain FQN because raw `ENUMTREEVAL` is not accepted by ili2c in attribute declarations.

`createUnitSnippet` intentionally supports only linearly derived units. Its contract is `name`, positive `factor`, and `base`, plus optional annotations. Base/abstract unit declarations are not synthesized by this helper.

`createAssociationSnippet` may generate deterministic association and role names when they are omitted, but those names are technical placeholders. Generated names and missing cardinalities are surfaced through `openQuestions` rather than treated as confirmed domain semantics.

## Extension Rules
When adding a new tool:
1. Prefer extending an existing focused component when that keeps the code simpler; do not introduce a new hierarchy or factory without a concrete need.
2. If a new Spring component is needed, place it under the appropriate package and use constructor injection for its dependencies.
3. Annotate MCP entrypoints with `@McpTool` and parameters with `@McpToolParam`.
4. Mark optional parameters explicitly with `required = false` and `@Nullable` where appropriate.
5. Prefer `NameValidator.ascii()` for identifiers and FQNs.
6. Add at least one focused unit test and, if the MCP contract matters, a schema assertion in `ToolRegistrationContractTest`.
7. For complete-model workflows, prefer extending the high-level review result instead of making agents compose several redundant low-level calls.

For `generateExampleXtf`, keep generation deterministic and conservative. If a mandatory value cannot be generated safely, skip the class and surface the reason instead of emitting potentially invalid placeholder data.

When adding curated modeling rules:
1. Extend `src/main/resources/knowledge/modeling-rules.core.yml` for generic checks or `src/main/resources/knowledge/modeling-rules.so.yml` for Solothurn-specific checks.
2. Keep each rule explicit about `appliesTo` and `checkKind`.
3. Implement automated checks in `ModelingRuleTools` only when they are deterministic from the model text or ili2c metamodel.
4. Keep manual checks visible in `manualChecks` instead of pretending that the server can infer missing fachliche Entscheide.

## Docker Packaging
`gradle/docker.gradle` builds the container image from the packaged JAR:

```bash
./gradlew buildAndPushMultiArchImage
```
