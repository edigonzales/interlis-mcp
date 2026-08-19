# B10/B11 — Complete SET semantics and proof slice

This step intentionally combines the former B10 object-set IR work with B11 proof and authoring. The object-set IR is useful only when it is immediately exercised by real validator-backed SET cases.

## Semantic boundary

`OBJECTS OF` is an INTERLIS function-parameter semantic type. The actual object-set expression used by a SET condition is represented by ili2c as `ch.interlis.ili2c.metamodel.Objects` and rendered in model source as `ALL`.

The constraint-level IR therefore adds:

- `ObjectSetExpression`
- `AllObjects(contextFqn, baseFqn, restrictedToFqns)`
- `ObjectCountSetCondition(objects, operator, threshold)`

`AllObjects` preserves ili2c base and `RESTRICTION` metadata even though the first executable proof slice accepts only plain `ALL`. Unsupported SET nodes continue to use `UntranslatedSetCondition`; no unknown or geometry-aware SET semantics are approximated.

## Proof-capable condition

The automatic proof pipeline recognizes compiled forms equivalent to:

```ili
SET CONSTRAINT
  INTERLIS.objectCount(ALL) >= 2;
```

All six comparison operators are represented in the semantic IR. The translator also normalizes the comparison if `objectCount(ALL)` appears on the right-hand side.

`generateIliConstraintCases` dispatches `SemanticConstraint.Set` to `SetConstraintCasePlanner` and returns:

- `pattern=SET_OBJECT_COUNT_PROOF`
- generated witness/counterexample cases near the count boundary
- `coverageGoalCount`, `coverageSolvedCount`, `coverageComplete`, optional `coverageUnsolved`
- real `testIliConstraint` / ilivalidator verification

The unchanged model is compiled exactly once. Validation reuses the B2 `PrecompiledCompiler` adapter.

## WHERE semantics

For a SET precondition/`WHERE`, iox-ili first selects the context objects for which the precondition evaluates true and then supplies that selected object set to `ALL`.

The proof planner therefore solves the precondition twice:

- one included object (`WHERE=true`)
- one excluded object (`WHERE=false`)

Every count case with a WHERE contains the requested number of included objects plus one excluded object. This proves that excluded objects do not contribute to `objectCount(ALL)`. To keep the count unambiguous, the current automatic WHERE proof requires each solver template to synthesize exactly one direct context object and no auxiliary graph objects or links.

## Global versus `(BASKET)`

B4 multi-basket fixtures are reused directly. The planner searches two per-basket selected counts where:

```text
global validity = valid(countA + countB)
per-basket validity = valid(countA) && valid(countB)
```

and the two results differ. For `objectCount(ALL) >= 2`, for example, one selected object in each of two baskets is:

- globally valid (`2 >= 2`)
- invalid for `(BASKET)` (`1 >= 2` is false in each basket)

The expected result is chosen from the compiled `Set.perBasket` flag and verified by the real validator.

## Typed authoring

`authorIliSetConstraint` authors the proof-capable high-level subset instead of accepting a raw SET expression.

Required inputs:

- `modelText`
- `context`
- `constraintName`
- `operator`
- `threshold`

Optional inputs:

- `perBasket`
- `where = {attribute, operator, valueKind, value}`
- `modelRepositories`

The generated statement is source-preserving and has the semantic shape:

```ili
CONSTRAINTS OF Model.Topic.Item =
  !!@ name = "AtLeastTwoHigh"
  SET CONSTRAINT WHERE value >= 5:
    INTERLIS.objectCount(ALL) >= 2;
END;
```

The authoring pipeline is:

```text
Before compile
  -> source-preserving insertion
  -> After compile + constraint resolution
  -> SET IR roundtrip
  -> objectCount/WHERE/BASKET semantic checks
  -> generated SET proof using the compiled After context
  -> real ilivalidator
```

Compile contract: exactly two real ili2c compiles (Before + After). Proof generation does not compile again.

## Explicit safety gates

The combined B10/B11 slice does not claim automatic proof for:

- `ALL(base)` or `ALL(... RESTRICTION ...)` selection variants
- complex WHERE graphs that require auxiliary objects or links
- zero selected objects for plain ALL without a WHERE (the explicit fixture harness has no context object to mark the constraint as exercised)
- geometry-aware SET functions such as `INTERLIS.areAreas` / `areAreas2`
- other unknown SET-only AST/function forms

These cases remain visible through typed metadata and `coverageUnsolved` / explicit reason codes.

## Result

After B10/B11, all five INTERLIS constraint kinds have a semantic proof path for their documented supported subsets:

- Mandatory
- Uniqueness
- Existence
- Plausibility
- Set

B12 can therefore focus on cleanup, prompts, golden scenarios and end-to-end agent evaluation rather than introducing another constraint semantic layer.
