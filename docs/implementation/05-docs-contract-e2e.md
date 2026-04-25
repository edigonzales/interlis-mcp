# Step 5: Docs, Contracts, E2E

## Goal

Update public docs and verify all new MCP surfaces over unit, integration, and stdio E2E tests.

## Components

- `README.md`
- `docs/USER_GUIDE.md`
- `docs/DEVELOPER_GUIDE.md`
- `ToolRegistrationContractTest`
- `StdioE2eTest`

## Acceptance Criteria

- Docs describe resources, prompts, new tools, and model corpus properties.
- Contract tests include new tool schemas.
- E2E covers resources/list, resources/read, prompts/list, prompts/get, `analyzeIliModel`, and `checkModelingRules`.

## Tests

- `./gradlew test`
- `./gradlew e2eTest`

## Non-Goals

- No OpenCode-specific config in this repo.
- No changes to deployment or Docker publishing.

