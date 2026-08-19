# UNIQUE Constraint Semantic Proof

`generateIliConstraintCases` supports compiled INTERLIS `UNIQUE` constraints in addition to the existing Mandatory pipeline.

The UNIQUE planner consumes the constraint-level semantic IR introduced in B1 and reuses the B2 compiled context, the existing finite-domain expression solver/object-graph synthesizer, the B4 multi-basket fixture topology and `testIliConstraint`/ilivalidator as the final oracle.

## Global UNIQUE

For model shapes supported by the scalar path binder, the planner produces and verifies:

- a single participating-object witness,
- a same-key duplicate counterexample in one basket,
- a cross-basket same-key case that is invalid for global `UNIQUE` and valid for `UNIQUE (BASKET)`,
- a WHERE-excluded branch when a precondition exists,
- undefined-key-component witnesses when the key domain permits undefined values.

Composite keys are represented as multiple semantic key paths; every key component must be defined for the duplicate-key counterexample.

## LOCAL UNIQUE

The first proof-capable LOCAL scope supports a direct STRUCTURE/composition prefix and direct scalar member keys. It verifies:

- one member as a witness,
- duplicate member keys inside one parent as a counterexample,
- the same member key in two different parent objects as a witness,
- WHERE-excluded parents when a precondition exists,
- undefined optional member-key components where the solver can construct them.

Navigated LOCAL member keys are reported as unsolved instead of being approximated.

## Result contract

UNIQUE responses use `pattern=UNIQUE_SEMANTIC_PROOF` and the same high-level proof fields as Mandatory generation:

- `automaticCasesGenerated`
- `generationVerified`
- `coverageGoalCount`
- `coverageSolvedCount`
- `coverageComplete`
- optional `coverageUnsolved`
- `verification`

`generationVerified=true` is emitted only when every generated fixture produces the expected result in the real validator. `coverageComplete=false` remains possible when the finite-domain solver cannot isolate a requested semantic branch; generated cases may still be individually validator-verified.

## Compile contract

Public `generateIliConstraintCases` performs one real ili2c compile for a valid existing UNIQUE constraint. All planning and validator verification reuse that compiled context through the strict precompiled adapter.
