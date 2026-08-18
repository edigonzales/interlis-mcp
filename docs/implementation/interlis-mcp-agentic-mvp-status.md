# Agentic INTERLIS MVP Status

_Last updated: 2026-08-18, after the constraint-pipeline consolidation in PR #79._

## Scope

The original agentic INTERLIS MVP (knowledge resources, prompts, model analysis, modeling-rule checks and model-corpus search) is implemented. The current development focus has moved to **agentic understanding, creation and proof of INTERLIS constraints**.

The constraint work is already useful, but it is important to describe its current boundary correctly:

> The implemented semantic pipeline is currently centered on **`MANDATORY CONSTRAINT`**. It is not yet a complete implementation of all INTERLIS constraint kinds.

## Current constraint architecture

The important architectural result is that constraint reasoning is no longer implemented separately in each MCP tool.

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

The final validator remains the oracle. The internal evaluator and solver are used to propose test assignments, but a generated witness or counterexample is only considered proven after the resulting XTF has been checked by the real validator.

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

`ConstraintAstTranslator` translates compiled ili2c AST nodes directly into the semantic IR. It deliberately does not use the map-shaped review AST as an intermediate representation.

The translator currently accepts `MandatoryConstraint` only. Unsupported constraint kinds fail explicitly instead of being guessed.

### Standard functions

`StandardFunctionRegistry` provides one semantic catalog for the standard Math and Text/MTEXT functions. The evaluator has executable semantics for all currently registered standard Math and Text functions.

This means that a function being executable is no longer tied to whether it was written as `Math.*`, `Math_V2.*` or a native 2.4 arithmetic operator.

A model-defined/custom function can be preserved in the IR, but it has no executable semantics unless a dedicated adapter is provided. The solver therefore reports `UNSUPPORTED_FUNCTION_SEMANTICS` instead of inventing behavior.

### Evaluation, test goals and coverage

`ConstraintExpressionEngine` evaluates semantic expressions and models `UNDEFINED` explicitly. `ConstraintCoveragePlanner` derives model-aware probes such as:

- numeric values directly below, at and above comparison boundaries;
- all BOOLEAN values;
- all ENUM values;
- DEFINED and UNDEFINED states.

The consolidated decision-table path now uses this same backend instead of maintaining a separate truth evaluator, numeric-domain model and fixture generator.

### Solver

`ConstraintGoalSolver` is a deterministic bounded finite-domain solver. It derives candidate values from:

- INTERLIS model domains;
- literals in the expression;
- numeric precision steps and boundaries;
- BOOLEAN and ENUM domains;
- small collection candidates for association paths and aggregates.

It is intentionally **not** claimed to be complete. `NO_SOLUTION_FOUND` currently means that no solution was found in the derived finite candidate set, not that the constraint is mathematically proven unsatisfiable. The solver can also stop with an explicit search-limit result.

There is currently no Z3/SMT dependency and no symbolic inverse solver for arbitrary nonlinear functions.

### Model binding and object-graph synthesis

`ConstraintModelSynthesizer` binds IR references to the ili2c model and creates concrete objects and association links from a solver assignment.

The currently strongest path support is:

- direct scalar attributes;
- one association step `Role->Attribute`;
- scalar association paths where the role cardinality permits one target;
- collection-valued association paths;
- aggregate scenarios such as `Math.sum("Nebenauspraegung->Gewichtung")`.

The AFU weighting constraint is an important production-shaped proof case: DEFINED/NOT DEFINED of a SUM, arithmetic with the direct weight, association target generation and final ilivalidator verification all run through the shared semantic pipeline.

### Decision tables

`generateIliConstraintFromDecisionTable` is now primarily a frontend and orchestrator:

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

Even before adding other INTERLIS constraint kinds, the Mandatory-Constraint support can be made substantially more general.

### 1. Expose the generic backend through the MCP tools

`generateIliConstraintCases` is still an older, narrow implementation. It currently generates cases only for direct scalar comparisons and DEFINED/NOT DEFINED on direct optional attributes and explicitly rejects paths, functions and aggregates.

This is now behind the capabilities of the semantic backend.

**High priority:** rewrite `generateIliConstraintCases` to use:

```text
ili2c AST
 -> semantic IR
 -> ConstraintCoveragePlanner
 -> ConstraintGoalSolver
 -> ConstraintModelSynthesizer
 -> testIliConstraint
```

This would make the generic capabilities available to an agent instead of only to internal tests and the decision-table frontend.

### 2. More path and object-graph shapes

The current generic synthesizer is intentionally small. Missing or insufficiently generalized cases include:

- paths with more than one navigation step;
- nested structures/compositions;
- reference-attribute navigation;
- several related paths that require a more complex shared object graph;
- direct multi-valued attributes/structures;
- cross-topic and cross-basket object graphs where the model permits/requires them.

### 3. Geometry and AREA semantics

`GEOMETRY` exists as an IR type, but generic geometry synthesis and geometry predicates are not implemented.

The separate INTERLIS AREA-related standard functions are also not part of the current Math/Text function registry. Geometry should remain a specialized extension rather than being forced into the scalar finite-domain solver.

### 4. Solver completeness

The current finite-domain search is deliberately bounded and heuristic. It works well for many comparisons, boundaries and small aggregates, but complex valid constraints can still produce `NO_SOLUTION_FOUND` because the interesting value was not in the candidate set.

Possible future improvements:

- more expression-directed candidate derivation;
- symbolic handling of arithmetic equations;
- function-specific inverse/boundary strategies;
- an optional SMT/Z3 solver adapter if real models justify the dependency;
- a clear distinction between "not found" and a genuinely proven UNSAT result.

### 5. Better coverage goals

Current coverage is strong for comparison boundaries, BOOLEAN/ENUM domains and DEFINED/UNDEFINED. It is not yet a complete logical coverage system.

Useful additions include:

- explicit branch coverage for each `AND`/`OR`/`IMPLIES` alternative;
- condition-independence / MC/DC-like cases for complex business rules;
- function-specific edge cases such as division by zero, logarithm domains and rounding boundaries;
- aggregate cardinality edges (empty, one, maximum relevant count);
- systematic checks of undefined/null propagation against ilivalidator semantics.

### 6. More validator-differential tests

The evaluator intentionally mirrors validator semantics, but the safest way to maintain that contract is to continuously compare semantic evaluation with real ilivalidator behavior.

More production constraints should be added as golden tests, especially for:

- undefined function arguments;
- arithmetic edge cases;
- Text/MTEXT functions;
- INTERLIS 2.4 surface syntax;
- inheritance and abstract/concrete class contexts.

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

Today the structured decision table is the main real authoring frontend. For arbitrary Mandatory Constraints, the agent still has to assemble source text itself.

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

### Coverage evidence in the tool response

The high-level creation tool should make the evidence visible to the agent/user:

- solved witness cases;
- solved counterexamples;
- boundary cases;
- unsolved goals and reason codes;
- validator result;
- known limitations of the proof.

A green compile alone is not enough evidence that a generated business constraint expresses the intended rule.

## Suggested next implementation order

1. **Replace the legacy `generateIliConstraintCases` implementation with the shared AST -> IR -> coverage -> solver -> synthesizer pipeline.**
2. Add a high-level, typed **Mandatory-Constraint authoring/proof tool** that uses the same pipeline.
3. Generalize object-graph synthesis to multiple root objects and richer paths.
4. Introduce the outer `ConstraintSpec` IR.
5. Implement **Uniqueness** and **Existence** constraints on top of multi-root synthesis.
6. Implement **Plausibility** and **Set** constraints with explicit dataset/basket semantics.
7. Add geometry/AREA-specific semantics and synthesis separately.
8. Reconsider SMT/Z3 only when the bounded solver demonstrably blocks relevant production constraints.

## Historical MVP steps

The original non-constraint MVP remains completed:

- [x] Knowledge resources and prompts
- [x] Model analysis
- [x] Modeling rule checker
- [x] Model corpus search
- [x] Docs, contracts and E2E checks

The constraint work described above is the next generation of the agentic INTERLIS functionality and should be treated as an evolving capability rather than as a finished all-constraint implementation.
