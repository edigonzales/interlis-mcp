# Step 1: Knowledge Resources And Prompts

## Goal

Expose curated INTERLIS modeling knowledge through MCP resources and prompt templates so coding agents can load task-specific guidance without relying on long manual prompts.

## Components

- `src/main/resources/application.properties`
- `src/main/resources/knowledge/modeling-rules.core.yml`
- `src/main/resources/knowledge/modeling-rules.so.yml`
- `ch.so.agi.mcp.knowledge.KnowledgeResources`
- `ch.so.agi.mcp.knowledge.AgentPrompts`

## Acceptance Criteria

- MCP initialize advertises `resources` and `prompts`.
- `resources/list` includes:
  - `interlis://knowledge/handbook-rules`
  - `interlis://knowledge/agent-workflow`
  - `interlis://knowledge/model-corpus-index`
- `prompts/list` includes:
  - `interlis-modeling-agent`
  - `review-interlis-model`
  - `extend-interlis-model`
- `completion` remains disabled.

## Tests

- Contract test for resource and prompt specs where available.
- Stdio E2E for resource and prompt list/read/get.

## Non-Goals

- No runtime crawling of the external handbook.
- No schema-job or GRETL knowledge in this step.
