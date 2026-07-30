# ADR 0003: Declared `distinguishing` flag, not an automatic cardinality gate

Status: accepted (2026-07-30, backfilled)

## Context

After fabrication, some `SENSITIVE` columns can safely be kept real — a boolean or a small code set
singles out no one — while others cannot: a rare code, an unusual amount, or free-text narrative can
itself identify a person, making it effectively a quasi-identifier. An earlier design decided this
automatically with a `COUNT(DISTINCT)` cardinality gate. That gate was a k-anonymity-era residue and,
critically, the **last place the privacy path read source values** — a dataset-level probe we wanted
gone.

## Decision

The policy author **declares** per `SENSITIVE` column a boolean `distinguishing`:

- `distinguishing: false` → keep the real value.
- `distinguishing: true` → fabricate it (a `QuasiIdStrategy`) or redact it (a `RedactionStrategy`).
- absent → the run fails.

The declaration is validated at **config time**, before any row is read. The distinct-count survives
only as a **default-on, advisory misdeclaration lint** (`distinguishingLint`: `WARN` | `ERROR` |
`OFF`) that flags a `distinguishing: false` column whose real cardinality looks free-text. The gate
is the declaration; the statistic is never the decision (`pg_stats` is at most a pre-filter for the
lint).

## Consequences

- The privacy decision no longer reads any source value — it acts purely on the flag and the presence
  of a strategy, checked before load.
- Keep-vs-fabricate is an explicit, reviewable, one-word author decision rather than an emergent
  property of the data.
- A mislabelled column is caught by the lint (or fails, in `ERROR` mode) without the lint ever
  becoming the privacy gate. The name `distinguishing` was chosen over `identifying` to avoid
  confusion with the "identifier" role vocabulary.
