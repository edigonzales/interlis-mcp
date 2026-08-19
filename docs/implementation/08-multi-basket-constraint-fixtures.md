# Multi-basket constraint fixtures

Epic B4 extends `testIliConstraint` so one explicit test case can contain multiple baskets of the same topic.

## Input

`TestObject.basketId` is optional. Objects with the same explicit `basketId` and topic are written to the same basket. Different IDs create distinct baskets. When `basketId` is omitted, the existing behavior is preserved: one deterministic implicit basket is allocated per topic (`b1`, `b2`, ...).

Heavyweight association links can also set `TestLink.basketId`. Lightweight associations have no independent transfer object and therefore inherit the owner object's basket; supplying `basketId` for a lightweight link is rejected.

A basket ID may not be reused for two different topics in one test case.

## XTF and references

Fixtures are grouped and written by `(topic, basketId)` rather than only by topic. References and association-role references add `BID` whenever the target object is in a different basket, including a different basket of the same topic. The underlying INTERLIS model still has to permit the corresponding external reference semantics.

Each case result exposes `basketCount` and a `baskets` summary so later proof planners can verify that the intended basket topology was actually generated.

## Why this matters

`UNIQUE (BASKET)` is evaluated separately for each basket, whereas ordinary `UNIQUE` is conceptually global. B4 deliberately verifies this distinction with the real ilivalidator before automatic UNIQUE proof planning is introduced in B5/B6.
