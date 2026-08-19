# Scalar EXISTENCE Constraint Proofs

Epic B7 adds the first complete EXISTENCE vertical slice: typed authoring, source-preserving insertion, constraint-level semantic round-trip and validator-backed proof generation.

## Public workflows

For an existing scalar EXISTENCE constraint, use `generateIliConstraintCases`. The model is compiled once and the resulting `CompiledConstraintContext` is reused for semantic planning and ilivalidator fixtures.

For a new scalar EXISTENCE constraint, use `authorIliExistenceConstraint` with:

- `context`: the constraint context FQN,
- `constraintName`: technical constraint name,
- `restrictedPath`: the source attribute path,
- `requiredIn[]`: explicit `{viewableFqn, attributePath}` targets.

The authoring path compiles the Before model once, resolves every source/target path from that compiled metamodel, inserts a `CONSTRAINTS OF` block source-preservingly, compiles the After model once, verifies the B1 `SemanticConstraint.Existence` round-trip, then runs the proof pipeline without recompiling the unchanged After model.

## Proof obligations

For scalar NUMERIC, BOOLEAN, ENUM, TEXT and MTEXT paths B7 attempts to generate and verify:

1. a defined restricted value without any matching target — counterexample,
2. an equal source/target value for every `REQUIRED IN ... OR ...` alternative — witness,
3. a target object containing only a different value — counterexample,
4. an undefined restricted value when the source path is optional — witness.

The planner only constructs fixtures. `ConstraintTestTools` and the real ilivalidator remain the proof oracle. `generationVerified=true` is emitted only if every generated fixture produces the expected validator result.

## Explicit B7 boundary

Structure equality, geometry and other special EXISTENCE comparison semantics are not approximated. They remain explicit B8 work. If a scalar source/target domain intersection cannot be found by the bounded finite solver, the missing proof goal is reported through `coverageUnsolved`.

The legacy `createExistenceConstraint` helper is retained for compatibility but its old `refAttr + classFQNs` schema cannot represent the actual `ViewableRef : AttributePath` target semantics. New agent workflows should use `authorIliExistenceConstraint`.
