# 14 - Constraint workflow hardening (Epic B12)

## Goal

B12 closes Epic B after the semantic/proof implementations for MANDATORY, UNIQUE, EXISTENCE, PLAUSIBILITY and SET. It does not add another constraint language feature. Instead it makes the completed capabilities safe and deterministic for an agent to choose and compose.

The central cleanup is to distinguish two independent gates:

1. **Constraint proof gate** - prove the semantics of one unchanged constraint with the real ilivalidator.
2. **Model change gate** - review the complete before/after model after a constraint was inserted or edited.

Conflating these gates either causes redundant validator work or leaves a model-level change unreviewed.

## Final decision hierarchy

### New constraints

| Constraint kind | Preferred authoring | Technical proof |
| --- | --- | --- |
| MANDATORY | `authorIliMandatoryConstraint` | included, require `proofVerified=true` |
| UNIQUE | no typed high-level authoring yet; simple keys may use `createUniqueConstraint` as snippet help | `generateIliConstraintCases`, require `generationVerified=true` |
| EXISTENCE | `authorIliExistenceConstraint` | included, require `proofVerified=true` |
| PLAUSIBILITY | `authorIliPlausibilityConstraint` | included, require `proofVerified=true` |
| SET | `authorIliSetConstraint` for the supported `INTERLIS.objectCount(ALL)` scope | included, require `proofVerified=true` |

A typed authoring tool owns source-preserving insertion, Before/After ili2c compilation, semantic roundtrip and validator proof. The proof stage reuses the compiled After context instead of compiling again.

UNIQUE is intentionally different: its proof semantics are complete enough for global, WHERE, `(BASKET)` and supported LOCAL cases, but there is no typed source-preserving high-level authoring DTO. The old `createUniqueConstraint` therefore remains only as a narrow simple-key snippet helper. Complex UNIQUE syntax must not be inferred from that schema.

### Existing constraints

- `reviewIliConstraint` explains AST, context, references, paths, types and structural edge cases.
- `generateIliConstraintCases` is the automatic semantic proof path for all five supported constraint kinds.
- `testIliConstraint` is reserved for explicitly supplied test cases and is not a routine second proof pass.
- `coverageUnsolved` and Safety-Reason-Codes are first-class outcomes. The agent must report them instead of inventing an approximate proof.

## Proof gate versus model gate

`proofVerified=true` from typed authoring, or `generationVerified=true` from automatic case generation, is final for the technical proof of that exact unchanged constraint. The agent should not routinely follow it with `testIliConstraint`, `validateXtf` or another automatic proof call.

However, these proof results do not replace a review of the complete changed INTERLIS model. If a constraint was added to an existing model, the final workflow is:

```text
Before model
    |
    +-- typed constraint authoring --------> proofVerified=true
    |        or
    +-- targeted UNIQUE/source edit ------> generationVerified=true
                                             |
                                             v
                                  reviewIliChange(Before, After)
                                             |
                                             v
                         afterCompilerValid + afterDiagnostics + afterReview
```

`reviewIliChange` is run exactly once for that unchanged After state. If the model is edited again afterwards, the new state must be reviewed again.

## Agent-facing contract

B12 adds:

- MCP prompt `author-interlis-constraint`
- MCP resource `interlis://knowledge/constraint-workflow`
- constraint-aware routing in `interlis-modeling-agent` and `extend-interlis-model`
- explicit Legacy-/Snippet descriptions on the old MANDATORY, EXISTENCE and SET helpers
- an explicit UNIQUE fallback contract

The prompt/resource wording is covered by deterministic tests so future tool additions cannot silently reintroduce an ambiguous hierarchy.

## Golden scenarios

`ConstraintWorkflowGoldenScenariosTest` locks down the runtime orchestration rather than only checking strings.

### Typed authoring

A MANDATORY constraint is authored and validator-proven with exactly two ili2c compilations:

```text
Before compile = 1
After compile  = 1
Proof          = 0 additional compiles
```

The returned `updatedModelText` is then passed to one `reviewIliChange`, which adds exactly two model-review compilations. Total for the full author-and-review workflow: four.

### UNIQUE fallback

An already integrated UNIQUE constraint is automatically proven with exactly one compile. A single subsequent `reviewIliChange` adds two compiles. Total for the fallback proof-and-review workflow: three.

These tests make redundant recompilation visible as a regression.

## STDIO e2e

`ConstraintWorkflowStdioE2eTest` verifies the public MCP surface:

1. the constraint workflow resource and `author-interlis-constraint` prompt are discoverable through the running STDIO server;
2. `authorIliSetConstraint` is invoked through real MCP JSON-RPC and must complete the full chain:

```text
MCP JSON payload
  -> typed request
  -> source-preserving insertion
  -> ili2c Before/After roundtrip
  -> typed SET IR
  -> generated object-count proof cases
  -> real ilivalidator
  -> proofVerified=true
```

This catches annotation-scanner, schema/deserialization and runtime wiring problems that focused Java tests cannot cover.

## Cleanup: UNIQUE snippet syntax

The old simple UNIQUE helper rendered `UNIQUE (a, b);`. The ili2c INTERLIS 2.4 grammar reserves the parenthesized form at that position for modifiers such as `(BASKET)`; a global uniqueness key is rendered as `UNIQUE a, b;`. B12 corrects the helper and its regression test.

## Epic B completion

After B12 the intended core constraint stack is complete:

- semantic constraint IR for all five kinds;
- source-aware compiled context reuse;
- multi-object and multi-basket validator fixtures;
- automatic validator-backed proofs for MANDATORY, UNIQUE, supported EXISTENCE, PLAUSIBILITY and supported SET;
- typed source-preserving authoring for MANDATORY, scalar EXISTENCE, PLAUSIBILITY and supported SET;
- explicit safety boundaries rather than approximate semantics;
- agent prompt/resource/golden/e2e contracts that select the right layer and avoid redundant checks.

Possible future work is deliberately outside Epic B: typed high-level UNIQUE authoring and geometry-rich proof extensions such as value-aware COORD/line/surface cases or geometry-aware SET functions.
