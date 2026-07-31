# Changelog

All notable changes to Incognito are recorded here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## Output-stability guarantees

These hold for every release within a major version, and are what make an entry in this file
meaningful rather than just a feature list:

- **Fabrication, not statistics.** Direct identifiers and quasi-identifiers are replaced with
  fictional values; there is no k-anonymity / l-diversity / t-closeness model and no global
  statistical pass over the clone (ADR 0001). A change to this model is a breaking change.
- **Fail-closed.** An unclassified column, or a `SENSITIVE` column with no `distinguishing`
  declaration, aborts the run rather than copying real data through (ADR 0003, ADR 0004). Relaxing
  this is a breaking change and is called out explicitly.
- **The salt is a secret**, generated per run and destroyed on completion; it is never persisted or
  logged. `reproducible(salt, seed)` mode produces stable fabricated output across runs for a fixed
  salt and seed.
- **Relational coherence is preserved**: referential integrity, foreign-key topology, per-period
  volumes, coherent parent–child date deltas, and root-ancestor inherited attributes.

## [Unreleased]

Pre-1.0 development. v1.0 scope: PostgreSQL only; in-memory key/cascade stores; single-threaded.

### Added

- **Pipeline & policy engine** (Phases 1–3): `IncognitoPipeline` builder with auto-assembled default
  stages; `AnonymisationPolicy` / `TablePolicy` / `ColumnPolicy` records and builders; a
  programmatic and YAML (`YamlPolicyParser`) policy surface; `SchemaInspector` (tables, PKs, FKs,
  unique candidate keys, identity vs generated columns); `TableDependencyGraph` topological
  ordering; fail-closed classification with advisory `PolicyInferrer` suggestions.
- **Fabrication engine** (Phase 4): streaming transform+load; `DIRECT_ID` / `UNIQUE_CANDIDATE_KEY`
  via `lib-alterego` with a length-preserving collision fallback; `QUASI_ID` temporal jitter,
  including one salt-keyed delta per coherence group inherited by descendants (ADR 0005); declared
  `distinguishing` handling for `SENSITIVE` columns (ADR 0003); root-ancestor `INHERITED_ATTRIBUTE`
  resolution (ADR 0007); primary-key surrogates and foreign-key rewriting.
- **Key & cascade stores** (Phase 5): `InMemoryKeyTranslationStore` and
  `InMemoryAttributeCascadeStore` (published attributes, FK linkage, and group-scoped jitter
  deltas). Single-column **and** composite (`CompositeKey`) keys.
- **Loader, cyclic FKs, clean-up & verification** (Phase 6): `PostgresDialectHandler` (+ generic
  ANSI fallback) with `session_replication_role` trigger isolation, `OVERRIDING SYSTEM VALUE`, and
  sequence resync; cyclic / self-referential foreign keys via Tarjan SCC plus a placeholder and a
  second-pass `UPDATE` (ADR 0006); `IncognitoCleanUpHandler` compensation on failure with salt
  destruction; `VerificationStage` (referential integrity, e-mail fictionality, per-period volume
  tolerances, the default-on misdeclaration lint, and a source-value survival net);
  `AnonymisationReportBuilder` (with the §7.2 opaque-type passthrough audit) and a
  `DpiaArtifactEmitter` that writes JSON, HTML, or Markdown.

### Known gaps (tracked in PLAN.md)

- Composite PK + cyclic FK together (each supported alone; the combination fails closed).
- Migrating the shape-preserving fabricator and type-aware redaction into `lib-alterego`.
- Owner-mode (non-superuser) degraded load: FK-constraint drop/recreate (cyclic FKs currently
  require a superuser target and fail fast otherwise).
- Benchmark suites (Pagila, Northwind, PetClinic) not yet wired.
