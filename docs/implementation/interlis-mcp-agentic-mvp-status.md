# Agentic INTERLIS MVP Status

_Last updated: 2026-08-18, after consolidating automatic constraint proof generation, adding logical/edge-case coverage, expression-directed arithmetic solving and richer multi-step object-graph synthesis._

## Scope

The original agentic INTERLIS MVP (knowledge resources, prompts, model analysis, modeling-rule checks and model-corpus search) is implemented. The current development focus has moved to **agentic understanding, creation and proof of INTERLIS constraints**.

The constraint work is already useful, but its current boundary should be stated explicitly:

> The implemented semantic pipeline is currently centered on **`MANDATORY CONSTRAINT`**. It is not yet a complete implementation of all INTERLIS constraint kinds.

## Current constraint architecture

Constraint reasoning is now shared instead of being reimplemented in individual MCP tools.

```text
Decision table -------------------\
                                   \
Existing MANDATORY constraint       -> Semantic Constraint IR
(via ili2c AST) ------------------/          |
                                              v
                                    Standard Function Registry
                                              |
                                              v
                                    Evaluator / Coverage Planner
                                              |
                                              v
                                      Test-goal Solver
                                              |
                                              v
                                  Model Binding + Object Graph
                                           Synthesis
                                              |
                                              v
                                      XTF test fixture
                                              |
                                              v
                                       ilivalidator
```

The two important public frontends currently using this backend are:

```text
generateIliConstraintFromDecisionTable
  -> builds semantic IR from a structured decision table
  -> renders a Mandatory Constraint
  -> proves model-aware boundary/category cases

generateIliConstraintCases
  -> reads an existing Mandatory Constraint through ili2c AST
  -> translates it to semantic IR
  -> derives and proves model-aware cases
```

The final validator remains the oracle. The internal evaluator and solver propose assignments, but a generated witness, counterexample or boundary case is only considered verified after the resulting XTF has been checked by the real ilivalidator through `testIliConstraint`.

## What is implemented for `MANDATORY CONSTRAINT`

### Semantic IR

A typed, version-neutral `ConstraintExpression` IR represents the currently supported expression semantics:

- literals: NUMERIC, BOOLEAN, ENUM and TEXT;
- direct attributes and paths;
- function calls;
- `DEFINED`;
- `NOT`;
- `AND`, `OR`, `IMPLIES`;
- `==`, `!=`, `<`, `<=`, `>`, `>=`;
- numeric arithmetic.

INTERLIS 2.3 and 2.4 are represented through language profiles. For example, `Math.add(a,b)` in 2.3 and `a + b` in 2.4 can map to the same semantic operation.

### ili2c AST -> IR

`ConstraintAstTranslator` translates compiled ili2c AST nodes directly into the semantic IR. It deliberately does not use the map-shaped AST returned by `reviewIliConstraint`; that map remains an API representation, while the translator is the typed semantic adapter used by evaluator, solver and fixture synthesis.

The translator currently accepts `MandatoryConstraint` only. Unsupported constraint kinds fail explicitly instead of being guessed.

Object-path typing includes endpoint type plus nullability/cardinality introduced by supported association-role, reference-attribute and structure/composition navigation. This allows the solver and synthesizer to distinguish scalar from collection paths and optional from mandatory path prefixes.

### Standard functions

`StandardFunctionRegistry` provides one semantic catalog for the standard Math and Text/MTEXT functions. The evaluator has executable semantics for all currently registered standard Math and Text functions.

This means that a function being executable is no longer tied to whether it was written as `Math.*`, `Math_V2.*` or a native 2.4 arithmetic operator.

A model-defined/custom function can be preserved in the IR, but it has no executable semantics unless a dedicated adapter is provided. The solver therefore reports `UNSUPPORTED_FUNCTION_SEMANTICS` instead of inventing behavior.

### Evaluation, test goals and coverage

`ConstraintExpressionEngine` evaluates semantic expressions and models `UNDEFINED` explicitly. `ConstraintCoveragePlanner` derives model-aware probes including:

- numeric values directly below, at and above comparison boundaries;
- all BOOLEAN values;
- all ENUM values;
- DEFINED and UNDEFINED states;
- `AND`: all operands true plus each direct operand independently false while the other direct operands remain true;
- `OR`: all operands false plus each direct branch independently true while the other direct branches remain false;
- all four direct antecedent/consequent truth combinations for `IMPLIES`;
- both operand truth states for `NOT`;
- aggregate empty/non-empty states and a maximum relevant `SUM` cardinality within the bounded collection solver;
- selected numeric function edges: division denominator around zero, `log`/`log10` and `sqrt` around zero, and `round` around `-0.5` and `0.5` where the model precision permits those values;
- DEFINED/UNDEFINED propagation for standard function calls that depend on optional references.

These logical probes are deliberately described as **MC/DC-like for direct operands**, not as complete MC/DC for arbitrary nested or semantically dependent expressions. If a requested logical pattern cannot be realized in the model or the finite solver cannot find it, it remains visible as an unsolved coverage goal.

The decision-table frontend and `generateIliConstraintCases` both use this shared coverage logic.

### Solver

`ConstraintGoalSolver` remains a deterministic bounded finite-domain solver. It derives candidate values from:

- INTERLIS model domains;
- literals in the expression;
- numeric precision steps and boundaries;
- BOOLEAN and ENUM domains;
- small collection candidates for association paths and aggregates;
- the actual semantic operation for simple numeric equality equations.

For numeric equalities the solver can work backwards through `NUMERIC_ADD`, `NUMERIC_SUB`, `NUMERIC_MUL` and `NUMERIC_DIV`. `COLLECTION_SUM` can participate as a numeric term, so a required aggregate total can be derived from another scalar operand and then materialized through the existing collection distributor and object-graph synthesizer.

Examples of the implemented direction are:

```text
A - B == 20, B = 10
 -> derive A = 30

A / B == 2, A = 10
 -> derive B = 5

SUM(items->value) / Factor == 2
 -> derive compatible SUM/Factor candidates
 -> distribute the SUM over model-valid collection elements
```

The previous operation-blind `literal - pivot` and `literal / pivot` guesses have been removed. Expression-derived candidates are still only proposals: every complete assignment is checked by the semantic evaluator and `ConstraintModelSynthesizer`, and generated public proof cases still go through ilivalidator.

It is intentionally **not** claimed to be complete. `NO_SOLUTION_FOUND` still means that no solution was found in the derived finite candidate set, not that the constraint is mathematically proven unsatisfiable. The solver can also stop with an explicit search-limit result.

There is currently no Z3/SMT dependency and no general symbolic inverse solver for arbitrary nonlinear functions or arbitrary nested equations.

### Model binding and object-graph synthesis

`ConstraintModelSynthesizer` binds IR references to the ili2c model and creates concrete objects, reference attributes, embedded structures and association links from a solver assignment.

The supported path shape is now substantially broader:

- direct scalar attributes;
- multiple scalar navigation steps in one path;
- association-role navigation;
- `REFERENCE TO` attribute navigation;
- structure/composition navigation;
- mixtures such as `Eigentuemer->Land->Code`;
- collection paths with **one** multi-valued navigation step;
- aggregate scenarios such as `Math.sum("Nebenauspraegung->Gewichtung")`;
- several related paths with a common prefix.

Common prefixes are materialized as a small path tree. For example,

```text
Eigentuemer->Land->Code
Eigentuemer->Land->Rate
```

reuse one `Eigentuemer` target and one `Land` target. The two endpoint values therefore describe the same object graph instead of creating duplicate independent association/reference chains.

Identifiable reference targets are emitted as ordinary graph objects and the source object's `references` map points at their OIDs. Structures remain embedded values; the constraint test fixture writer can serialize nested maps/lists of maps to XTF and auto-fill ordinary mandatory scalar members that are not relevant to the tested constraint.

The AFU weighting constraint remains an important production-shaped proof case: DEFINED/NOT DEFINED of a SUM, arithmetic with the direct weight, association target generation and final ilivalidator verification all run through the shared semantic pipeline. Coverage can also force the empty, non-empty and maximum-relevant association cardinality cases within the solver's current collection cap.

### Automatic cases for an existing constraint

`generateIliConstraintCases` uses the complete shared backend:

```text
existing MANDATORY constraint
 -> compile/review
 -> ili2c AST
 -> semantic IR
 -> model binding
 -> coverage planner
 -> solver
 -> object graph
 -> testIliConstraint
 -> ilivalidator
```

It is no longer limited to one direct scalar comparison or direct `DEFINED(attribute)`.

For example, an existing constraint such as:

```ili
MANDATORY CONSTRAINT value >= 10 AND value <= 20;
```

can produce the model-aware coverage values `9`, `10`, `20` and `21` when the model domain permits them.

For a logical rule such as:

```ili
MANDATORY CONSTRAINT left == 1 OR right == 1;
```

the coverage planner can additionally request the three semantically important direct-branch patterns `left=true/right=false`, `left=false/right=true`, and `left=false/right=false`, and generated cases are still checked by ilivalidator.

The same MCP tool can exercise supported association/SUM and multi-step scalar path constraints. Generated responses expose:

- solved cases;
- witness/counterexample classification according to the complete constraint;
- the semantic coverage reason for each case;
- object and association-link counts;
- `coverageGoalCount` and `coverageSolvedCount`;
- `coverageComplete`;
- unsolved goals with solver reason codes;
- the real validator result.

`automaticCasesAvailable=true` is only returned after all generated cases have passed `testIliConstraint` with their expected outcomes.

### Decision tables

`generateIliConstraintFromDecisionTable` is primarily a frontend and orchestrator:

```text
Decision table
 -> semantic IR
 -> render constraint
 -> compile/review
 -> bind model
 -> coverage planner
 -> solver
 -> object graph
 -> testIliConstraint
 -> ilivalidator
```

The old decision-table-specific domain model, truth evaluator, AFU special case, SUM distribution and fixture generator have been removed.

The decision-table input format itself is still deliberately narrower than the backend. It currently focuses on NUMERIC/BOOLEAN/ENUM conditions, one-step association paths and the supported SUM/presence patterns.

## Important remaining work inside `MANDATORY CONSTRAINT`

Even before adding other INTERLIS constraint kinds, the Mandatory-Constraint support can still be generalized further.

### 1. Remaining path and object-graph shapes

Multi-step scalar navigation and shared path-prefix synthesis are implemented, but the graph synthesizer deliberately remains bounded. Important remaining cases include:

- paths with more than one multi-valued navigation step, which require nested collection/cartesian semantics instead of one flat assignment list;
- reference-attribute navigation originating inside an embedded structure;
- direct multi-valued scalar attributes and other transfer shapes that are not represented by the current path assignment model;
- more complex nested structured values containing their own references;
- multiple independent root objects in one semantic solution;
- cross-topic and cross-basket object graphs where the model permits or requires them.

The multiple-root and cross-object capabilities are especially important foundations for future Existence and Uniqueness constraints. They should be added because those constraint kinds need them, rather than by making the Mandatory synthesizer arbitrarily complex in advance.

### 2. Geometry and AREA semantics

`GEOMETRY` exists as an IR type, but generic geometry synthesis and geometry predicates are not implemented.

The separate INTERLIS AREA-related standard functions are also not part of the current Math/Text function registry. Geometry should remain a specialized extension rather than being forced into the scalar finite-domain solver. This work is currently deliberately lower priority than the remaining non-geometry Mandatory-Constraint gaps.

### 3. Solver completeness

The first expression-directed layer is implemented for simple numeric equalities over ADD/SUB/MUL/DIV and SUM terms, but the solver remains deliberately bounded and incomplete.

Important remaining cases include:

- equations where the same reference occurs more than once and cannot be inverted unambiguously;
- deeper symbolic rearrangement beyond the currently supported arithmetic chain;
- inequalities that would benefit from algebraic rather than candidate-based solving;
- nonlinear and non-invertible functions;
- function-specific inverse strategies where they are actually useful;
- a clear distinction between "not found" and a genuinely proven UNSAT result.

An optional SMT/Z3 adapter should still only be considered if real production constraints demonstrate that these bounded strategies are insufficient.

### 4. Coverage completeness

Direct logical branch and selected function/aggregate edge coverage are now implemented, but the coverage planner is intentionally not claimed to be complete.

Remaining useful extensions include:

- true condition-independence / MC/DC for deeper nested and semantically dependent business rules;
- additional function-specific domain and discontinuity cases beyond division, logarithm, square root and rounding;
- richer cardinality strategies for aggregates other than the currently explicit SUM maximum-relevant case;
- systematic undefined/null-propagation probes across nested expressions;
- coverage minimization when several semantic obligations can be proven by the same validator-backed fixture.

### 5. More validator-differential tests

The evaluator intentionally mirrors validator semantics, but the safest way to maintain that contract is to continuously compare semantic evaluation with real ilivalidator behavior.

More production constraints should be added as golden tests, especially for:

- undefined function arguments;
- arithmetic edge cases;
- Text/MTEXT functions;
- INTERLIS 2.4 surface syntax;
- inheritance and abstract/concrete class contexts;
- multi-step path undefined/cardinality behavior.

A dedicated differential suite should compare semantic-evaluator outcomes against generated XTF plus the real validator so divergences become ordinary regression failures.

## INTERLIS constraint kinds that are still missing

The ili2c metamodel distinguishes several constraint kinds. The current AST-to-IR pipeline handles `MandatoryConstraint`; the following major kinds still need dedicated semantics.

### `PLAUSIBILITY CONSTRAINT`

**Status:** not supported by the semantic translator/solver pipeline.

This is not merely a Boolean expression on one object. A plausibility constraint has dataset-level/statistical semantics, so proof requires multiple objects and cases around the permitted violation rate.

Needed work:

- a constraint-level IR that stores the plausibility parameters in addition to the Boolean condition;
- generation of multi-object datasets at, below and above the threshold;
- validator-backed boundary tests for the percentage/count semantics.

### `EXISTENCE CONSTRAINT`

**Status:** not supported.

Existence constraints require a value/path in one object set to exist in another path/object set. A useful witness and counterexample therefore need coordinated objects rather than a single root object.

Needed work:

- semantic representation of the required source and target paths;
- multi-root object synthesis;
- matching/non-matching target generation;
- cross-topic/basket handling where required by the model.

### `UNIQUE` / `UniquenessConstraint`

**Status:** not supported.

Uniqueness cannot be proven with one root object. At least two relevant objects are needed to demonstrate both distinct and duplicate key tuples.

Needed work:

- semantic representation of unique path tuples and preconditions/scopes;
- two-or-more-root-object synthesis;
- duplicate and non-duplicate assignments;
- support for applicable LOCAL/BASKET/scoping variants.

### `SET CONSTRAINT`

**Status:** not supported.

SET constraints operate at set/basket level rather than as a simple per-object predicate. INTERLIS 2.4 also has basket-related variants that must not be flattened into Mandatory-Constraint semantics.

Needed work:

- set-level constraint IR;
- explicit basket/set scope in generated fixtures;
- multi-object and possibly multi-basket synthesis;
- support for preconditions and the relevant 2.3/2.4 variants.

### Constraint contexts beyond the current class-centric path

Constraints attached to other valid model contexts, including domain-level constraint use cases, need explicit AST translation, binding and fixture tests instead of assuming every constraint can be treated as a class-level Mandatory Constraint.

## Recommended IR extension for the other constraint kinds

The existing `ConstraintExpression` should remain the IR for **expressions**. It should not be overloaded with dataset-level constraint semantics.

A small outer constraint IR would keep the architecture clean, for example:

```text
ConstraintSpec
├── MandatorySpec
│     └── condition: ConstraintExpression
├── PlausibilitySpec
│     ├── condition: ConstraintExpression
│     └── threshold/direction
├── ExistenceSpec
│     ├── sourcePath
│     └── targetPath
├── UniquenessSpec
│     ├── paths
│     ├── precondition
│     └── scope
└── SetSpec
      ├── condition
      ├── precondition
      └── scope
```

The existing expression evaluator can then be reused inside the kinds that actually contain Boolean expressions, while dataset-level solvers/synthesizers remain explicit.

## What is still missing for truly agentic constraint creation

Supporting constraint syntax is only one part of agentic authoring. A useful agent should be able to go from an intent to a constraint proposal **and prove why the proposal is credible**.

### Generic Mandatory-Constraint authoring tool

The system can automatically analyze and prove an **existing** supported Mandatory Constraint through `generateIliConstraintCases`, and a decision table can create a supported constraint through `generateIliConstraintFromDecisionTable`.

What is still missing is a general authoring frontend for arbitrary semantic Mandatory-Constraint proposals. Today the agent still has to assemble source text itself unless the intent fits the structured decision-table format.

A future high-level tool should accept a structured semantic proposal and perform the complete safe loop:

```text
model + context + semantic intent
 -> resolve attributes/paths/functions
 -> build typed IR
 -> render for INTERLIS 2.3/2.4
 -> compile with ili2c
 -> review translated AST
 -> generate coverage/witness/counterexample cases
 -> synthesize XTF
 -> validate with ilivalidator
 -> return source + proof + unsolved coverage goals
```

The tool should fail with explicit reasons when the requested semantics cannot be represented or proven.

### Constraint-kind selection guidance

The agent also needs guidance to choose the **native INTERLIS constraint kind** instead of emulating everything with a Mandatory Constraint. For example:

- "every object must satisfy ..." -> usually Mandatory;
- "at least / at most x percent may violate ..." -> Plausibility;
- "this value must occur in ..." -> Existence;
- "these attributes form a unique key" -> Unique;
- set/basket-wide invariant -> Set Constraint.

This belongs in the agent knowledge/tool guidance once the corresponding backends exist.

### Safe editing of an existing model

Generating a correct constraint block is not the same as safely editing a real `.ili` source file. A mature authoring workflow should eventually provide source-aware insertion/replacement based on model/constraint locations rather than relying on ad-hoc text placement.

This becomes especially important for:

- replacing an existing constraint;
- preserving comments and meta-attributes;
- choosing the correct `CONSTRAINTS OF` section;
- keeping minimal diffs.

### Redundancy and contradiction checks

A newly generated constraint can be valid in isolation and still be redundant with, or contradictory to, other constraints in the model.

A later agentic review should therefore check at least:

- whether the new rule is already implied by a model domain or another constraint;
- whether adding it makes the relevant model domain effectively empty;
- whether generated witnesses can satisfy the rest of the model at the same time;
- whether a simpler native INTERLIS construct would express the same rule better.

### Coverage evidence in authoring responses

A high-level creation tool should make the same evidence now exposed by `generateIliConstraintCases` visible for a newly proposed constraint:

- solved witness cases;
- solved counterexamples;
- boundary/category/logical-branch cases;
- unsolved goals and reason codes;
- validator results;
- known limitations of the proof.

A green compile alone is not enough evidence that a generated business constraint expresses the intended rule.

## Suggested next implementation order

1. Add a systematic **validator-differential test suite** for the semantic evaluator and generated fixtures.
2. Add a high-level, typed **Mandatory-Constraint authoring/proof tool** that uses the existing semantic pipeline.
3. Introduce the outer `ConstraintSpec` IR.
4. Add multiple-root object synthesis as required by dataset-level constraint kinds.
5. Implement **Uniqueness** and **Existence** constraints on top of multi-root synthesis.
6. Implement **Plausibility** and **Set** constraints with explicit dataset/basket semantics.
7. Keep geometry/AREA-specific semantics and synthesis as a later specialized extension.
8. Reconsider SMT/Z3 only when the bounded solver demonstrably blocks relevant production constraints.

## Historical MVP steps

The original non-constraint MVP remains completed:

- [x] Knowledge resources and prompts
- [x] Model analysis
- [x] Modeling rule checker
- [x] Model corpus search
- [x] Docs, contracts and E2E checks

The constraint work described above is the next generation of the agentic INTERLIS functionality and should be treated as an evolving capability rather than as a finished all-constraint implementation.
