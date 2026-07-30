# ADR 0006: Cyclic foreign keys via Tarjan SCC + placeholder + 2-pass UPDATE

Status: accepted (2026-07-30, backfilled)

## Context

A schema-identical clone must load tables whose foreign keys form a cycle, including the common
self-referential case (`employee.manager_id → employee`). Strict topological ordering — parents
before children — cannot order a cycle: some row always references a row not yet loaded. Silently
dropping such tables (an earlier behaviour) is a data-integrity hole.

## Decision

Detect strongly-connected components with **Tarjan's SCC**, condense them, and topologically order
the condensation (parents before children; a cycle becomes one component). For a foreign key that
cannot yet be resolved during a component's load, insert a **type-appropriate placeholder** (Pass 1)
with FK enforcement suppressed on the insert connection, record a deferred update, and after all
tables are loaded run a second-pass **`UPDATE`** setting the real mapped surrogate (Pass 2). It is
**fail-closed**: a deferred FK on a row with no resolvable single-column primary key throws rather
than leaving a dangling placeholder.

## Consequences

- Cyclic and self-referential schemas load with referential integrity intact after Pass 2.
- Pass 1 relies on suppressing FK enforcement — `session_replication_role = 'replica'` (superuser),
  or a documented degraded owner-mode; a non-superuser without FK-dropping fails loud on the
  placeholder insert.
- Composite primary/foreign keys are not yet supported (Pass 2 keys on a single-column PK); until
  then a cyclic table without a single-column PK fails-closed rather than corrupting data.
- Verified end-to-end by a mutual self-reference test (`CyclicFkE2ETest`).
