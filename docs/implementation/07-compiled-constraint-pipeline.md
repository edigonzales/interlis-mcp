# Compiled constraint pipeline

Epic B2/B3 introduces a reusable `CompiledConstraintContext` and source-preserving constraint insertion.

## Compile ownership

A resolved context owns the complete model text, the successful `IliCompilerService.CompilationResult`, the `TransferDescription`, the selected ili2c `Constraint`, and the B1 `SemanticConstraint` IR. Downstream review/proof stages must reuse that context instead of recompiling unchanged text.

`generateIliConstraintCases` now compiles and resolves the model once. Coverage planning, model binding, object-graph synthesis and validator fixtures reuse the same compiled metamodel. The existing explicit-case validator is invoked through a strict precompiled adapter that refuses any different model text or repository configuration.

`authorIliMandatoryConstraint` now has a two-compile successful path:

1. compile the before model once,
2. insert the generated `CONSTRAINTS OF` block source-preservingly,
3. compile and resolve the after model once,
4. reuse the compiled after context for AST/semantic round-trip, coverage and validator proof.

## Source preservation

`ConstraintSourceEditService` reuses the A2 source infrastructure. The target context is resolved through ili2c, its owning topic is located by source line, and one insertion patch is applied immediately before the topic `END`. Existing comments, declaration order, whitespace and LF/CRLF style outside the insertion are left untouched.

Successful Mandatory authoring now returns both `updatedModelText` and a structured `sourceEdit` in addition to the existing `constraintBlock` and proof payload.
