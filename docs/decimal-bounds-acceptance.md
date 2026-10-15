# Decimal bound contract acceptance, 2026-10-15

Implemented: numeric bounds accept numbers and decimal strings in the public MCP
schema. Strings preserve decimal places through typed authoring and changes.
Rendering retains zero/trailing zeros independently of units; mismatched scales
fail validation with both bounds and scales.

Performed locally with JDK 21:
- Full Java test suite passed on rerun. The first run had two failures referencing
  deleted temporary repository paths in ConstraintViewAuthoringRegressionTest;
  the isolated suite and subsequent full run both passed.
- Updated ToolRegistrationContractTest: 26 tests passed, including typed authoring
  and UPDATE_ATTRIBUTE through actual registered tool handlers.
- `check` passed, including existing benchmark/workflow/recorder checks.
- `e2eTest`: 24 tests passed against the executable JAR.
- Integrator's isolated stdio regression passed authoring and typed changes,
  comparing numeric bounds and scale in compiled attributes.

Open at this commit: remote release pipeline and installation of its published
artifact; final integrator-wide acceptance is recorded in its own repository.
No live business workflow or approval was changed for this acceptance.
