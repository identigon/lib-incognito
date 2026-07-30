# ADR 0002: Two libraries — value transformation vs relational coherence

Status: accepted (2026-07-30, backfilled)

## Context

Incognito is a distinct library from `lib-alterego`. The original justification ("Alterego works on
records, Incognito works on datasets") was a k-anonymity-era assumption — that Incognito needed a
global statistical view. With fabrication (ADR 0001) there is no such pass, so that framing no
longer holds and the real reason for the split had to be restated.

## Decision

Split by **responsibility**, not batch size:

- **`lib-alterego`** transforms a single value or the fields of one record — deterministic in
  `(salt, domain, value)`, stateless with respect to the dataset, and DB-agnostic (reusable on a
  CSV, an API payload, or a message).
- **Incognito** owns everything relational: schema discovery and role classification, topological
  load order, key translation (PK → surrogate, FK rewritten to the same mapping), coherent
  cross-entity temporal deltas, root-ancestor attribute cascade, bulk loading with trigger
  isolation, and DPIA reporting.

Incognito **consumes** Alterego and delegates **all** field-value substitution to it. Where
Incognito needs a value transformation Alterego does not expose, the fix is to add the primitive to
Alterego — never to hand-roll it in Incognito.

## Consequences

- Alterego stays reusable as a standalone value transformer; folding relational concerns in would
  couple it to JDBC and destroy that.
- The boundary is a one-line test: *Alterego fabricates fields; Incognito preserves relationships.*
- Any value logic that leaks into Incognito is tracked as debt, not sanctioned (currently:
  `fabricateShapePreserving`, and type-aware redaction — see PLAN).
