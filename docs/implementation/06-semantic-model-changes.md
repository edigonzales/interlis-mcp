# Semantic Model Changes

`applyIliModelChange` is the high-level entry point for deterministic, source-preserving changes to an existing complete INTERLIS model.

## Current operation: ADD_ATTRIBUTE

`ADD_ATTRIBUTE` accepts a local CLASS or STRUCTURE FQN plus the same typed `AttributeLineRequest` used by `createAttributeLine`.

The change pipeline:

1. validates and renders the typed attribute request,
2. compiles the before model once with ili2c,
3. resolves the target container from the compiled metamodel,
4. rejects imported targets and existing/inherited attribute names,
5. maps the ili2c source line back to an exact source-preserving insertion point,
6. compiles the candidate after model once,
7. reuses `ModelChangeReviewService` for the semantic diff and after-model rule review,
8. releases `updatedModelText` only if the semantic diff contains exactly the requested attribute addition (plus explicitly requested attribute meta attributes).

If the source edit compiles but produces collateral semantic changes, the result is `UNEXPECTED_SEMANTIC_CHANGE`; the candidate is returned only as `candidateModelText` for diagnosis.

A successful `APPLIED` result already contains the final semantic diff, compiler state and `afterReview`. Clients should not routinely call `reviewIliChange` or `reviewIliModel` again for the unchanged result.
