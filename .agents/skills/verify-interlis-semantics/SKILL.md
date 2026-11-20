---
name: verify-interlis-semantics
description: Verify INTERLIS semantics after changes to compiler integration, model analysis, constraint IR, solver, fixtures, XTF, or validator.
---

Compile the affected models with ili2c and validate representative XTF with
ilivalidator. Check INTERLIS 2.3 and 2.4 wherever the behavior applies.
For compiler or parser changes, run two requests in parallel with distinct
errors and check each response for only its own diagnostics.

For the affected behavior, verify at least one positive and one negative case
with expected outcomes derived independently of the implementation under test.
For supported constraints, ilivalidator must accept the positive case and
report the intended violation for the negative case.

Check that `fixtureValid` and `constraintExercised` are true where applicable
and `targetViolationCount` matches the expectation (zero for the positive case).
Every successful proof requires `coverageComplete = true`. Also require
`constraintProofs[].status = PASSED` for typed authoring and
`generationVerified = true` for case generation. Unknown external semantics
always block a successful proof.

Use the existing JUnit tests under `src/test/java/ch/so/agi/mcp/`:

- `tools/ConstraintReconstructionTest.java` runs P01–P10 and N11/N12 from
  `src/test/resources/constraint/reconstruction/`. N11/N12 expect
  `EXTERNAL_FUNCTION_SEMANTICS_REQUIRED`.
- `constraint/ConstraintValidatorDifferentialTest.java` checks constraint
  behavior against ilivalidator in both INTERLIS versions.
- `constraint/ConstraintCoverageProofRegressionTest.java` checks proof coverage.
- `service/IliCompilerServiceTest.java` covers compiler diagnostics.
- `analysis/SemanticChangeSafetyTest.java` covers semantic changes, and
  `analysis/ModelChangeToolsTest.java` covers formatting-only changes.

Record the checks run, their results, and any remaining verification gaps.
