# Extended EXISTENCE proof semantics

Epic B8 extends the validator-backed EXISTENCE proof pipeline beyond the scalar B7 slice without replacing validator semantics with approximations.

## Direct STRUCTURE / COMPOSITION values

`generateIliConstraintCases` can automatically prove a direct STRUCTURE-valued EXISTENCE constraint when the restricted and `REQUIRED IN` attributes:

- are direct attributes of identifiable classes,
- use the same STRUCTURE component type,
- use the same transfer attribute name, matching the current iox-ili structure-comparison implementation, and
- have a small compatible positive cardinality that can be represented by the fixture generator.

The planner creates and verifies:

- a defined restricted STRUCTURE with no target value (counterexample),
- an equal member-wise STRUCTURE in each supported `REQUIRED IN` target (witness),
- a target STRUCTURE with one comparable scalar member changed when a second domain value exists (counterexample), and
- an omitted optional restricted STRUCTURE (witness).

Nested structure values are written through the existing `ConstraintTestTools` map/list fixture representation. The real iox-ili/ilivalidator implementation remains the proof oracle.

## Explicit safety gates

B8 deliberately does not report `proofVerified=true` when the current fixture or validator behavior is not strong enough for an equality proof:

- `REFERENCE TO`: `EXISTENCE_REFERENCE_VALUE_PROOF_UNSAFE`. The currently used iox-ili reference comparison path is not sufficiently value-discriminating for an automatically generated equality/counterexample proof.
- `COORD`: `EXISTENCE_COORD_FIXTURE_NOT_VALUE_AWARE`. The validator has dedicated coordinate equality semantics, but the automatic fixture layer cannot yet inject arbitrary model-valid coordinate values.
- POLYLINE / SURFACE / AREA: `EXISTENCE_COMPLEX_GEOMETRY_FIXTURE_UNAVAILABLE`.
- navigated non-scalar paths: `EXISTENCE_SPECIAL_PATH_NAVIGATION_UNSUPPORTED`.

These cases remain reviewable with `reviewIliConstraint` and can be tested with explicit/handcrafted XTF where appropriate. They are not emulated as scalar constraints.

## Compile contract

The B2 compile contract remains unchanged: `generateIliConstraintCases` compiles an existing constraint model exactly once; special-type planning and validator-backed fixture execution reuse that compiled context.
