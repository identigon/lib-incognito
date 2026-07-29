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
- [x] Remaining Phase-1 **runtime** wiring:
  - Ephemeral secret-salt generator (≥128-bit `SecureRandom`) + `reproducible(salt, seed)`.
  - **AlterEgo integration (SPEC §5.1):** Incognito owns the salt and builds `AlterEgo` internally. High-cardinality `UNIQUE_CANDIDATE_KEY` columns use a length-preserving sequence fallback (zero-padded sequence overlaid on the value's tail; bare sequence for numeric columns) if `AlterEgo.unique()`'s collision threshold is reached. Zero Incognito's salt copy on completion.

---

## Phase 2: Walking Skeleton (end-to-end vertical slice)

- [x] PostgreSQL Testcontainer, 2-table schema (`users` parent, `orders` child) — `WalkingSkeletonTest`.
- [x] End-to-end run: schema discovery → in-memory store → fabricate `DIRECT_ID` (email) + `QUASI_ID` (dob) → batch load → sequence resync → `VerificationStage`.
- [x] Asserts: FK integrity, sequences work on new inserts, direct IDs in reserved domains, operational data preserved.
- [x] Fail-closed check: an unclassified column aborts with `ConfigException`.
- [x] **Gate A VERIFIED** — `WalkingSkeletonTest` runs end-to-end against a real PostgreSQL container: `2 passed, 0 skipped` (fabrication, FK integrity, sequence resync, reserved-domain fictionality, operational-data preservation, fail-closed). Requires Docker; it *skips gracefully* where Docker is unavailable.
- Fixes applied during review + Gate A:
  - **Privacy:** salt-keyed shape-preserving fabrication for `ALTEREGO_GENERIC` / string-`SYNTHESISE` (removed `hashCode`); dob `SYNTHESISE` → wide ±5y jitter (year destroyed).
  - **Load:** `session_replication_role` set on the *insert* connection; identity-PK detection drives `OVERRIDING SYSTEM VALUE`; fixed a `resyncSequences` `commit()`-under-autoCommit bug; default stages auto-assembled by the builder; `autoInfer` no longer fails open.
  - **Tooling:** Testcontainers `1.19.7 → 2.0.5` (Docker Engine 29.x needs API ≥1.40; 1.x probed 1.32); restored the PostgreSQL JDBC driver dep; `PostgreSQLContainer` package/generics updated for TC 2.x; fixed fragile test URL construction and an empty-`ResultSet` assertion bug.

---

## Phase 3: JDBC Schema Discovery, Declarative YAML & Topological Engine

- [x] `SchemaInspector`:
  - Query JDBC `DatabaseMetaData` for tables, columns, PKs, FKs, unique indexes, SQL types; filter to `TABLE` only.
  - Detect **computed** generated columns (`IS_GENERATEDCOLUMN`) vs **identity** columns (`IS_AUTOINCREMENT`, now a separate `identityColumns` list driving `OVERRIDING SYSTEM VALUE`).
  - [ ] **Composite PK/FK NOT done** — FKs are modelled as `Map<fkColumn → parentTable>` (single-column only); composite keys need a richer model before Pagila/Phase 7.
  - [x] `SENSITIVE` handling **done in Phase 4** — a **declared** boolean `distinguishing: true | false` (SPEC §2.2/§4.1), validated fail-closed at config time in `SchemaDiscoveryStage` (no automatic `COUNT(DISTINCT)` gate). The distinct-count survives only as a **default-on** misdeclaration lint on `distinguishing: false` columns (`distinguishingLint: WARN | ERROR | OFF`), whose runtime check is wired in `VerificationStage` (Phase 6, still outstanding).
- [x] **Policy Inference (`PolicyInferrer.java`)**:
  - Implement regex-based heuristic inference (e.g. `.*email.*` -> `DIRECT_ID`).
  - Add to report as `InferSuggestion` (do not auto-apply).
- [x] **YAML Parsing (`YamlPolicyParser.java`)**:
  - Integrate SnakeYAML.
  - Map YAML structure to `AnonymisationPolicy` records.
  - Throw `ConfigException` on syntax errors or invalid fields.
- [x] Test: `YamlConfigTest.java` (parse valid/invalid configs, verify `autoInfer` flag).
- [x] `TableDependencyGraph` — single-threaded topological sort (Kahn's algorithm; parent → child).
- [ ] **Cyclic-FK handling NOT implemented.** A detected FK cycle (including a self-referential FK) now **throws `SchemaException` (fail-loud)** instead of silently dropping the tables. Still to do: nullable cyclic via `NULL`-deferral, `NOT NULL` cyclic via placeholder surrogate (Pass 1) + batch `UPDATE` (Pass 2). (The class comment's "Tarjan's SCC" is aspirational — it's currently Kahn.)

---

## Phase 4: Transformation Model (Fabrication & Temporal Jitter)

- [x] `TableTransformLoadStage` streaming execution (`fetchSize = 5000`) — all roles below implemented and unit-tested. **Note:** the advanced relational paths (root-ancestor `INHERITED_ATTRIBUTE`, coherent group jitter) are covered by unit tests only; end-to-end coverage against a real inheritance/coherence schema arrives with the Phase 7 diamond benchmark.
  - `DIRECT_ID` via `lib-alterego` (`DirectIdStrategy`); `UNIQUE_CANDIDATE_KEY` via `AlterEgo.unique()` with a **length-preserving** collision fallback (zero-padded sequence overlaid on the value's tail — never widens a fixed-width/`CHECK` column; numeric columns fall back to a bare sequence).
  - `QUASI_ID` temporal jitter (SPEC §4.2): **standalone** dates → bucket-preserving `JITTER_WITHIN_MONTH`/`_YEAR` (exact per-period volumes); **ordered/related** dates (`created_at ≤ approved_at`, contract `start`/`end`, parent-child windows) → **one shared delta per coherence group** (exact orderings/intervals, "similar" volumes). Bucket jitter does NOT preserve ordering — only the shared delta does.
  - Cache the per-entity shared delta in `AttributeCascadeStore`, **scoped by coherence group** (`putJitterDelta(coherenceGroup, table, id, deltaDays)`), so a child inherits the delta anchoring *its* group (not an arbitrary FK parent's) and re-publishes it under itself so grandchildren inherit via one hop (SPEC §4.2).
  - `SENSITIVE` **declared `distinguishing` flag** (SPEC §2.2/§4.1): `distinguishing: false` $\rightarrow$ keep real; `distinguishing: true` $\rightarrow$ require `QuasiIdStrategy` or `RedactionStrategy`; missing `distinguishing`, or `distinguishing: true` with no strategy $\rightarrow$ `ConfigException` (fail-closed, checked at config time). Misdeclaration lint **on by default** (`distinguishingLint`: `WARN` default / `ERROR` fails the run / `OFF` skips): flag a `distinguishing: false` column whose real `COUNT(DISTINCT)` exceeds `maxCategoricalCardinality`.
  - `PAYLOAD` columns kept real.
  - Resolve `INHERITED_ATTRIBUTE` from the root ancestor: parents publish their fabricated value; the child walks its FK chain (via published source-id linkage) up to the `derivedFrom` table and reads it. Fail-closed — an unpublished ancestor or a genuine fork (two distinct ancestor rows) throws; a null FK yields null, never the child's own real value (SPEC §6.1).
  - Translate `PRIMARY_KEY` to surrogates; rewrite `FOREIGN_KEY` to mapped parent surrogates.

### Phase 4 follow-up — tech debt

- [ ] **Migrate hand-rolled value substitution into `lib-alterego`** (SPEC §1.4 delegation principle). `TableTransformLoadStage.fabricateShapePreserving(...)` performs class-preserving character substitution *inside Incognito*, which violates "Alterego fabricates fields; Incognito preserves relationships". Add a shape/class-preserving, guaranteed-fictional primitive to Alterego, publish it, and have `ALTEREGO_GENERIC` (and string-`SYNTHESISE`) delegate to it; then delete the Incognito copy. Until then it is a tracked bug, not a sanctioned exception.
- [ ] **Type-aware redaction** (`RedactionStrategy`): `CLEAR → null` breaks a `NOT NULL` column and `CONSTANT`/`MASK` assume text. These currently fail *loud* (a `SQLException` at insert, surfaced as `SchemaException`) — no silent corruption or privacy leak, so this is a robustness/usability item, not a safety one. The fix is type-appropriate, format-preserving value production, which belongs in lib-alterego (§1.4) — do it as part of the delegation migration above rather than hand-rolling a `switch(sqlType)` in Incognito.
- [ ] **Default-on misdeclaration lint runtime** (`distinguishingLint`): the enum + config field exist, but the `COUNT(DISTINCT)` check itself is not yet wired — it lands in `VerificationStage` (Phase 6), not Phase 4.

---

## Phase 5: Key Store & Complex Relational Handling

- [x] `InMemoryKeyTranslationStore` — bijective `old_pk → new_pk` (**single-column only**; `CompositeKey` tuples still pending, blocked on the composite PK/FK model — Phase 3 line 49 / Phase 7).
- [x] `InMemoryAttributeCascadeStore` — `(parentTable, parentId, attr) -> value` (also stores `@fk:` source-id linkage for ancestor walking) and `(coherenceGroup, parentTable, parentId) -> deltaDays`; root-ancestor FK-chain resolution and fork detection live in `TableTransformLoadStage` (SPEC §6.1). Built during Phase 4; diamond paths get end-to-end coverage in Phase 7.

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
- [ ] `VerificationStage`: assert referential integrity on target, verify fictionality (reserved e-mail domains/phones), check per-period volume tolerances, and run the default-on `distinguishing:false` misdeclaration lint (§4.1; `distinguishingLint` WARN/ERROR/OFF).
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
  - High-cardinality candidate keys transformed without collision crashes via the length-preserving sequence fallback.
  - Misdeclaration lint behaves per `distinguishingLint`: `OFF` runs no `COUNT(DISTINCT)` scan; `WARN` reports; `ERROR` fails. It is never the privacy gate (the `distinguishing` declaration is).
  - `AnonymisationReport` carries full DPIA accountability evidence.
