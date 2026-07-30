# ADR 0005: Coherent temporal jitter keyed on the parent's source id

Status: accepted (2026-07-30, backfilled)

## Context

Related dates must move together. A contract's `start`/`end`, and a child event's date relative to
its parent's, encode orderings and intervals that tests rely on. Jittering each date independently
destroys those relationships (a schedule can land before its contract starts). Two failure modes had
to be avoided: an *independent* per-field delta (breaks coherence), and a delta derived from
`pk.hashCode()` (the source PK is known, so a hashCode delta is trivially reversible — a leak).

## Decision

Derive **one shared day-delta per entity** from `lib-alterego`'s salt-keyed HMAC stream (never
`hashCode`), namespaced by a **coherence group**. A child inherits its **parent's** delta, looked up
by the parent's **source** id and scoped to the group, and applies the same shift to its own dates.
Each entity re-publishes its effective delta under its own id, so a grandchild inherits the same
shift through a single one-hop lookup.

## Consequences

- Parent–child windows and event orderings are preserved exactly; interval `child − parent` is
  invariant under the shift.
- Scoping the delta by coherence group means a table with several foreign keys inherits only the
  delta anchoring *its* group — an unrelated parent's delta can never contaminate it.
- Bucket-preserving `JITTER_WITHIN_MONTH` / `_YEAR` remain available for **standalone** dates (they
  preserve per-period volumes but not ordering); the shared delta is for ordered/related dates.
- Deltas live in the `AttributeCascadeStore`, keyed on source ids — consistent with all other
  parent lookups.
