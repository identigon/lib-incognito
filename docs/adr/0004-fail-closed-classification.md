# ADR 0004: Fail-closed classification

Status: accepted (2026-07-30, backfilled)

## Context

The one mistake that leaks real data is an identifier nobody classified — a column the policy simply
did not mention, copied through verbatim because the engine assumed it was harmless. Auto-inference
can *guess* a column's role from its name, but a wrong or missing guess must never result in real
data being copied silently.

## Decision

Every discovered column must resolve to a `ColumnRole` before the run starts. A column with no
explicit role — and no *accepted* inferred role — **aborts the run** with `ConfigException`, even
when auto-inference is enabled. Auto-inference only adds *suggestions* to the report; it never
assigns a role. A `SENSITIVE` column with no `distinguishing` declaration (ADR 0003) fails the same
way. Opaque/untransformable types that are retained are surfaced in the report, never silently
copied.

## Consequences

- Safe by default: an unspotted identifier stops the pipeline instead of leaking.
- The author is forced to make an explicit decision per column; the cost is that a brand-new column
  in the source will fail a previously-passing policy until it is classified — which is the point.
- Validation happens at config/discovery time, before any row is read, so failures are cheap and
  early.
