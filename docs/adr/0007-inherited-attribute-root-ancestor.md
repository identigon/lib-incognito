# ADR 0007: INHERITED_ATTRIBUTE resolved from the root ancestor, fail-closed

Status: accepted (2026-07-30, backfilled)

## Context

Schemas often denormalise an ancestor attribute onto descendant rows (a firm's type copied onto its
contracts and schedules). For the clone to stay coherent, a denormalised copy must show the
ancestor's **fabricated** value — the same value the ancestor row now carries — not its own original
value and not an independently fabricated one. The naive implementation (return the row's own value
when the ancestor can't be resolved) is *fail-open*: it silently passes real data through.

## Decision

Model these columns as `INHERITED_ATTRIBUTE` with a declared `derivedFrom(table, column)`. As each
row loads, parents **publish** their fabricated attribute value and their FK source-id **linkage**
to the cascade store. A descendant resolves its value by **walking the FK chain** (via that linkage)
up to the declared root-ancestor table and reading the ancestor's published value. Resolution is
**fail-closed**:

- a null ancestor (nullable FK is null) yields `null` — nothing to leak;
- two *distinct* ancestor rows reached (a genuine fork) throws `ConstraintException`;
- an ancestor reached but with no published value (an ordering/config error) throws.

It never returns the child's own real value.

## Consequences

- Denormalised copies remain consistent with the fabricated ancestor across arbitrary FK depth
  (parent, grandparent, …), not just one hop.
- Requires the ancestor to load before its descendants — guaranteed by topological order.
- The publish/linkage machinery is gated on inheritance being in use, so schemas without
  `INHERITED_ATTRIBUTE` pay nothing. Verified end-to-end by `CoherenceE2ETest`
  (`firm → contract → schedule`, including the grandparent hop).
