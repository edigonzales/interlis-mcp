# PLAUSIBILITY population proof and authoring

Epic B9 adds constraint-level proof and typed authoring for INTERLIS PLAUSIBILITY constraints without reducing them to Mandatory-constraint semantics.

## Validator semantics

The iox-ili validator evaluates the PLAUSIBILITY condition for every applicable object and accumulates a population result. An explicit `TRUE` result counts as successful. A condition that cannot be evaluated (`skipEvaluation`, for example because an input is undefined) also counts as successful. An explicit `FALSE` result counts only toward the total population.

After the population has been collected, the validator computes:

```text
successful / total * 100
```

and compares that percentage with the declared threshold using the constraint direction (`>=` / AT_LEAST or `<=` / AT_MOST).

## Automatic proof planning

`generateIliConstraintCases` translates the compiled PLAUSIBILITY constraint to `SemanticConstraint.Plausibility`, binds the existing boolean `ConstraintExpression`, and solves two reusable population-member templates:

- one model-valid assignment where the condition is TRUE;
- one model-valid assignment where the condition is FALSE.

Those templates are repeated to form real multi-object XTF populations. The planner searches populations of at most 20 context objects and selects the closest representable ratios:

- below the threshold;
- exactly on the threshold, when representable within the bound;
- above the threshold.

Expected validity is computed with exact integer/decimal cross multiplication rather than from a rounded display percentage. Every generated population is then verified by the real ilivalidator through the existing `ConstraintTestTools` pipeline.

If the solver can produce an undefined condition, B9 additionally generates `UNDEFINED_COUNTS_AS_SUCCESS` to verify the validator's `skipEvaluation` behavior.

## Population safety

A synthesized TRUE/FALSE member graph must contribute exactly one object with the constraint context FQN. If the object graph would make the population denominator ambiguous, the corresponding goal is reported as `coverageUnsolved` instead of being approximated.

The finite expression solver remains bounded. Missing TRUE/FALSE assignments, unsupported functions, or unsafe object-graph shapes therefore remain explicit coverage gaps.

## Typed authoring

`authorIliPlausibilityConstraint` reuses the structured expression-node schema from `authorIliMandatoryConstraint` and adds constraint-level fields:

- `direction`: `AT_LEAST` / `>=` or `AT_MOST` / `<=`;
- `percentage`: 0 through 100.

The workflow is:

```text
typed request
  -> render PLAUSIBILITY source block
  -> compile Before once
  -> source-preserving insertion
  -> compile/resolve After once
  -> verify kind, context, direction and percentage in SemanticConstraint.Plausibility
  -> generate population proof from the compiled After context
  -> verify all generated XTF populations with ilivalidator
```

A successful authoring path therefore performs exactly two real ili2c compilations (Before + After). The proof stages reuse the compiled After context and do not recompile the model.

## Remaining boundary

SET constraints are intentionally not covered by B9. `ALL` and `OBJECTS OF` require their own object-set expression IR and proof semantics rather than population emulation through PLAUSIBILITY or Mandatory constraints.
