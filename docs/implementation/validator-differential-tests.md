# Validator differential constraint tests

The semantic constraint evaluator is intentionally not treated as the final oracle. The real iox-ili/ilivalidator runtime remains authoritative for transfer and constraint behavior.

`ConstraintValidatorDifferentialTest` protects that contract by running representative `MANDATORY CONSTRAINT` assignments through both paths:

```text
INTERLIS model
  -> ili2c AST
  -> semantic ConstraintExpression
  -> ConstraintExpressionEngine

same assignment
  -> ConstraintModelSynthesizer
  -> XTF fixture
  -> ConstraintTestTools
  -> ilivalidator
```

Each assignment also carries an explicit expected Mandatory-Constraint outcome. The test therefore checks three things independently:

1. the semantic evaluator produces the expected result;
2. the same assignment can be materialized as a valid model-aware XTF fixture;
3. ilivalidator observes the same constraint result.

A difference between the internal semantic result and ilivalidator fails as an ordinary JUnit regression. Explicit expectations also prevent a false positive where evaluator and fixture accidentally agree on the same wrong behavior.

The initial suite covers:

- undefined propagation through a standard Math function;
- valid and invalid Math-function results;
- TEXT and MTEXT standard-function behavior, including undefined inputs;
- INTERLIS 2.4 native arithmetic and division by zero;
- inherited attributes used by a constraint on a concrete subclass;
- multi-step association/structure paths with present and absent optional nested structures.

These tests are deliberately separate from unit tests that only exercise `ConstraintExpressionEngine`. They are also separate from solver/coverage tests: the assignments are explicit so a solver change cannot hide an evaluator/validator semantic divergence.

The suite should grow when production constraints expose additional semantic edge cases. Geometry/AREA remains outside the current priority scope.
