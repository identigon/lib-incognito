# Incognito — Implementation Plan

Phased plan for building `Incognito` — a Java 25 library that clones a production database into a schema-identical test database with all PII replaced by **clearly fictional** data, preserving data volumes and inter-entity relationships. See `SPECIFICATION.md` §1 for goals/non-goals.

**v1.0 scope (locked):** PostgreSQL only · in-memory `KeyTranslationStore` only · **fabrication** of identifiers/quasi-identifiers via `lib-alterego` — **not** k-anonymity / l-diversity / t-closeness (explicit non-goals; no statistical analysis of the clone) · no JSON/JSONB, spatial, biometric/media, array, or INET transformations · single-threaded.

**Build prerequisite:** `lib-alterego` is consumed from the local Maven repo as `io.github.dconneely:alterego:0.2.0-SNAPSHOT`. Until it is published to a shared repository, build it first: `cd ../lib-alterego && ./gradlew publishToMavenLocal`. (Bump the version in `build.gradle.kts` if lib-alterego advances.)

---

## Phase 1: Project Foundation & Core API Interfaces

- [x] Set up Java 25 build (`build.gradle.kts` / `settings.gradle.kts`) with a Java 25 toolchain. `io.github.dconneely:alterego:0.2.0-SNAPSHOT` dependency. `./gradlew test` is green.
- [x] Add `lib-alterego` and SnakeYAML dependencies (SnakeYAML bundled in core; module split tracked in Phase 3). **No Jedis / Redis in v1.0** (in-memory `KeyTranslationStore` only).
- [x] Create package structure: `io.github.dconneely.incognito.{api,core,spi,policy,engine}`.
- [x] Core API **interfaces & records**:
  - `IncognitoPipeline`, `PipelineStage`, `PipelineContext`, `KeyTranslationStore`, `AttributeCascadeStore`.
  - `CompositeKey` record; `SurrogateStrategy`; `QuasiIdStrategy`; `DirectIdStrategy`; `RedactionStrategy`.
  - `ColumnRole` — v1.0 active: `PRIMARY_KEY`, `UNIQUE_CANDIDATE_KEY`, `FOREIGN_KEY`, `DIRECT_ID`, `QUASI_ID`, `INHERITED_ATTRIBUTE`, `GENERATED_COLUMN`, `SENSITIVE`, `PAYLOAD`. Reserved: `BIOMETRIC_MEDIA`, `SPATIAL_GEOMETRY`, `ARRAY_ELEMENT`, `NETWORK_INET`, `JSON_DOCUMENT`.
  - `AnonymisationPolicy`, `TablePolicy`, `ColumnPolicy` records (`ColumnPolicy.Builder` supporting `coherenceGroup` and `jitterDays`; reached from a table via `column(name, ColumnPolicy.Builder)`).
  - `IncognitoException` hierarchy (`ConfigException`, `SchemaException`, `ConstraintException`, `StoreException`).
  - `PipelineResult` + typed `AnonymisationReport`.
- [x] Core **builder implementations (stubs)** covered by `SpecExamplesTest`.
- [ ] Remaining Phase-1 **runtime** wiring:
  - Ephemeral secret-salt generator (≥128-bit `SecureRandom`) + `reproducible(salt, seed)`.
  - **AlterEgo integration (SPEC §5.1):** Incognito owns the salt and builds `AlterEgo` internally. High-cardinality `UNIQUE_CANDIDATE_KEY` columns use sequence-decorated fallback (`Value_000001`) if `AlterEgo.unique()` dictionary collision threshold is reached. Zero Incognito's salt copy on completion.

---

## Phase 2: Walking Skeleton (end-to-end vertical slice)

- [ ] PostgreSQL Testcontainer with a 2-table schema (`users` parent, `orders` child; single-column PKs; one FK).
- [ ] End-to-end run: schema discovery → in-memory store → `DIRECT_ID` (email) via `lib-alterego` + one `QUASI_ID` (dob) jittered → batch load → sequence resync → `VerificationStage`.
- [ ] Assert: FK integrity holds in target, sequences work on new inserts, direct IDs replaced with fictional reserved domains.
- [ ] Fail-closed check: an unclassified column aborts the run with `ConfigException`.

---

## Phase 3: JDBC Schema Discovery, Declarative YAML & Topological Engine

- [ ] `SchemaInspector`:
  - Query JDBC `DatabaseMetaData` for tables, columns, PKs, FKs, unique indexes (`getIndexInfo(..., unique=true)`), SQL types.
  - Filter to `TABLE` only (exclude `VIEW`, `MATERIALIZED VIEW`, `SYSTEM TABLE`).
  - Detect **computed** generated columns vs identity PKs via **portable JDBC metadata** (`IS_GENERATEDCOLUMN`, `IS_AUTOINCREMENT`) as the definitive path. *Optional optimisation:* the Postgres `pg_attribute.attgenerated` catalog where JDBC is ambiguous — not relied upon (SPEC §1).
  - `SENSITIVE` cardinality gate: **definitive** measurement is a portable, exact `COUNT(DISTINCT col)` (privacy-critical, must not depend on estimates). *Optional optimisation:* consult `pg_stats.n_distinct` to skip the count only when the estimate is comfortably clear of the threshold; near the threshold / no stats → exact count (SPEC §4.1).
  - Composite PK/FK support (`CompositeKey`).
- [ ] `PolicyInferrer` (opt-in only): regex over column names (`email`, `phone`, `ssn`, `dob`, `address`, `postcode`) that **suggests** roles surfaced in `AnonymisationReport`. Fail-closed (unclassified columns fail the run, SPEC §7.2).
- [ ] **Passthrough audit**: flag complex/opaque `PAYLOAD` columns (`JSONB`, PostGIS, array, `INET`, BLOB) in `AnonymisationReport` (SPEC §7.2).
- [ ] `YamlPolicyParser`: parse `incognito-policy.yaml` → `AnonymisationPolicy`.
- [ ] `TableDependencyGraph` & cycle resolution:
  - Tarjan's SCC to detect cyclic FK references.
  - Nullable cyclic FKs via 2-pass `NULL` deferral; `NOT NULL` cyclic FKs via dummy placeholder surrogate keys (Pass 1) + batch `UPDATE` (Pass 2).
  - Single-threaded topological sort (parent → child).

---

## Phase 4: Transformation Model (Fabrication & Temporal Jitter)

- [ ] `TableTransformStage` streaming execution (`fetchSize = 5000`):
  - `DIRECT_ID` via `lib-alterego` (`DirectIdStrategy`); `UNIQUE_CANDIDATE_KEY` via `AlterEgo.unique()` with sequence-decorated fallback (`Value_000001`).
  - `QUASI_ID` temporal jitter (SPEC §4.2): **standalone** dates → bucket-preserving `JITTER_WITHIN_MONTH`/`_YEAR` (exact per-period volumes); **ordered/related** dates (`created_at ≤ approved_at`, contract `start`/`end`, parent-child windows) → **one shared delta per coherence group** (exact orderings/intervals, "similar" volumes). Bucket jitter does NOT preserve ordering — only the shared delta does.
  - Cache the per-entity shared delta in `AttributeCascadeStore` (`putJitterDelta(table, id, deltaDays)`) so child event dates inherit the parent's shift and stay within parent bounds (SPEC §4.2).
  - `SENSITIVE` cardinality gate: distinct-value count $\le 64 \rightarrow$ keep real; $> 64 \rightarrow$ require `QuasiIdStrategy` or `RedactionStrategy`.
  - `PAYLOAD` columns kept real.
  - Resolve `INHERITED_ATTRIBUTE` directly from root ancestor entity in `AttributeCascadeStore` (SPEC §6.1).
  - Translate `PRIMARY_KEY` to surrogates; rewrite `FOREIGN_KEY` to mapped parent surrogates.

---

## Phase 5: Key Store & Complex Relational Handling

- [ ] `InMemoryKeyTranslationStore` — bijective `old_pk → new_pk`, single and `CompositeKey` tuples.
- [ ] `AttributeCascadeStore` (in-memory): `(parentTable, parentId, attr) -> value` and `(parentTable, parentId) -> deltaDays`. Root-ancestor resolution for diamond paths (SPEC §6.1).

---

## Phase 6: Loader Engine, Trigger Isolation, Clean-Up & Verification

- [ ] `PostgresDialectHandler` (+ uncertified `GenericDialectHandler` ANSI fallback):
  - `reWriteBatchedInserts=true`, `SET session_replication_role='replica'` (superuser) with `ALTER TABLE ... DISABLE TRIGGER USER` owner-fallback, `OVERRIDING SYSTEM VALUE`.
- [ ] `BulkDatabaseLoadStage`:
  - Pre-load: suppress FK enforcement + user triggers.
  - Insert: batched `PreparedStatement.executeBatch()`, per-table transaction boundaries.
  - Deferred: 2-pass batch `UPDATE` for nullable and `NOT NULL` placeholder cyclic FKs.
  - Post-load: restore FK enforcement + triggers; resync sequences (`SELECT setval(...)`).
- [ ] `IncognitoCleanUpHandler`: on failure, re-enable triggers + FK enforcement, resync sequences, truncate partially loaded tables, and zero Incognito's salt copy + release `AlterEgo` instance (SPEC §5.1, §8.1).
- [ ] `VerificationStage`: assert referential integrity on target, verify fictionality (reserved e-mail domains/phones), check per-period volume tolerances.
- [ ] `AnonymisationReport` emitter: serialise typed report to JSON/HTML as concrete DPIA artifact.

---

## Phase 7: Benchmark Integration Testing & Traceability Verification

- [ ] Testcontainers PostgreSQL benchmark suites:
  - **Pagila / DVD Rental**: `customer`, `address`, `staff`, `payment`, `film_actor`. Verify `VIEW` exclusion and passthrough audit.
  - **Northwind & Spring PetClinic**: self-referential hierarchies, unique candidate keys.
  - **Multi-path diamond**: `firm → office → schedule` and `firm → contract → schedule`.
- [ ] Verify invariants & traceability:
  - Direct IDs & QIs fabricated; secret salt never persisted/logged and destroyed on completion.
  - Monotonic date sequence ordering preserved; coherent parent-child date deltas maintained.
  - High-cardinality candidate keys transformed without collision crashes via sequence-decorated fallback.
  - System catalog cardinality probe executes fast without table scans.
  - `AnonymisationReport` carries full DPIA accountability evidence.
