# Step 2: Model Analysis

## Goal

Add `analyzeIliModel` so an agent can inspect a complete INTERLIS model structurally before editing or reviewing it.

## Components

- `ch.so.agi.mcp.service.IliCompilerService`
- `ch.so.agi.mcp.analysis.ModelAnalysisTools`
- `ch.so.agi.mcp.tools.ValidationTools`
- `ch.so.agi.mcp.tools.FormattingTools`
- `ch.so.agi.mcp.tools.RenameTools`

## Acceptance Criteria

- `analyzeIliModel` returns compiler validity, compiler messages, elements, imports, attributes, metaattributes, and `summaryMarkdown`.
- Compiler errors are returned as `valid=false`; normal model errors do not throw top-level MCP exceptions.
- Existing tool response shapes remain unchanged.

## Tests

- Unit tests for successful analysis and invalid model handling.
- Contract test for tool schema.

## Non-Goals

- No source-preserving AST editing.
- No full semantic modeling recommendations here; rule checks live in step 3.

