# Step 3: Modeling Rule Checker

## Goal

Add a small, explicit rule system based on the modeling handbook so agents can report automated findings and manual checklist items.

## Components

- `src/main/resources/knowledge/modeling-rules.yml`
- `ch.so.agi.mcp.knowledge.KnowledgeRuleLoader`
- `ch.so.agi.mcp.knowledge.ModelingRuleTools`

## Acceptance Criteria

- `listModelingRules` lists all curated MVP rules.
- `checkModelingRules` returns `findings`, `manualChecks`, and `validForAutomatedRules`.
- Automated MVP checks cover publication associations, ili2c validity, LV95/CHLV95 geometry conventions, and model metaattributes.

## Tests

- Unit tests for loader and each automated rule.
- Contract test for tool schema.

## Non-Goals

- No attempt to encode the entire handbook.
- No human approval workflow; manual checks are reported only.

