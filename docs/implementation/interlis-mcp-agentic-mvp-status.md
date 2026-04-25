# Agentic INTERLIS MVP Status

## Goal

Implement the first agentic INTERLIS modeling MVP in `interlis-mcp`: knowledge resources, MCP prompts, model analysis, modeling-rule checks, and configurable search over local `.ili` example files.

## Worktree Note

The worktree was already dirty when this implementation started. Do not reset, revert, or overwrite unrelated existing edits.

## Steps

- [x] Step 1: Knowledge resources and prompts
  - Status: done
  - Date: 2026-04-25
  - Notes: Adds curated knowledge resources, prompt providers, and capability flags.
  - Tests: covered by contract/e2e checks.
- [x] Step 2: Model analysis
  - Status: done
  - Date: 2026-04-25
  - Notes: Adds shared ili2c compiler service and `analyzeIliModel`.
  - Tests: unit and contract tests.
- [x] Step 3: Modeling rule checker
  - Status: done
  - Date: 2026-04-25
  - Notes: Adds curated rule loader, list/check tools, and automated MVP rules.
  - Tests: unit and contract tests.
- [x] Step 4: Model corpus search
  - Status: done
  - Date: 2026-04-25
  - Notes: Adds configurable local `.ili` indexing and lexical search.
  - Tests: unit and contract tests.
- [x] Step 5: Docs, contracts, e2e
  - Status: done
  - Date: 2026-04-25
  - Notes: Updates user/developer docs and stdio E2E expectations.
  - Tests: `./gradlew test`, `./gradlew e2eTest`.
