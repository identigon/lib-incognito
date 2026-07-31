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
  - [x] **Composite PK/FK done** (SPEC §5.2; unblocks Pagila `film_actor`, Northwind `Order Details`, PetClinic `vet_specialties`). No SPI change — `KeyTranslationStore` keys on `Object` and `CompositeKey` has value equality; the work was constructing/using composite keys:
    - [x] `SchemaInspector` groups `getImportedKeys` rows (by FK name, ordered by `KEY_SEQ`) into structured `ForeignKeyConstraint(parentTable, childColumns, parentColumns)`; the per-column `foreignKeys` map is kept for ordering/linkage.
    - [x] Composite **PK translation** records `CompositeKey(source PK) → CompositeKey(new PK)` **once per row** — the per-PK-column `keyStore.put` pollution is gone (it worked before only by luck of identity surrogates on `1..N`).
    - [x] Composite **FK rewrite** builds the parent `CompositeKey` from the sibling FK columns (ordered to the parent's PK), looks it up, and returns this column's component. Single-column FKs keep the simple path.
    - [x] `VerificationStage` checks each FK as a whole tuple (composite-aware), so a broken composite FK is now *caught*, not missed.
    - [ ] Remaining sub-deferral: **composite PK + cyclic FK** together (the Pass-2 `UPDATE` keys on one column) — fail-closed with a clear message rather than corrupt; not in the benchmarks.
    - [x] `CompositeKeyE2ETest` — a composite-PK join table (`authorship`, the `film_actor` shape) **and** a genuine composite FK (`chapter → authorship`); asserts single- and composite-FK integrity after surrogate remapping.
  - [x] `SENSITIVE` handling **done in Phase 4** — a **declared** boolean `distinguishing: true | false` (SPEC §2.2/§4.1), validated fail-closed at config time in `SchemaDiscoveryStage` (no automatic `COUNT(DISTINCT)` gate). The distinct-count survives only as a **default-on** misdeclaration lint on `distinguishing: false` columns (`distinguishingLint: WARN | ERROR | OFF`), whose runtime check is now wired in `VerificationStage` (Phase 6, done — `DistinguishingLintTest`).
- [x] **Policy Inference (`PolicyInferrer.java`)**:
  - Implement regex-based heuristic inference (e.g. `.*email.*` -> `DIRECT_ID`).
  - Add to report as `InferSuggestion` (do not auto-apply).
- [x] **YAML Parsing (`YamlPolicyParser.java`)**:
  - Integrate SnakeYAML.
  - Map YAML structure to `AnonymisationPolicy` records.
  - Throw `ConfigException` on syntax errors or invalid fields.
- [x] Test: `YamlConfigTest.java` (parse valid/invalid configs, verify `autoInfer` flag).
- [x] `TableDependencyGraph` — single-threaded topological sort (Kahn's algorithm; parent → child).
- [x] **Cyclic-FK handling implemented (Phase 6).** `TableDependencyGraph` now uses **Tarjan's SCC** + condensation + Kahn on the condensed DAG (parents before children; cycles/self-loops clustered). Cyclic tables insert a **type-appropriate placeholder** for the unresolved FK (Pass 1), and `BulkDatabaseLoadStage.resolveDeferredCyclicFKs` runs a 2-pass **`UPDATE`** once the referenced surrogate is known. Fail-closed: a deferred FK on a row with no resolved single-column PK throws (never left as a dangling placeholder). Covered E2E by `CyclicFkE2ETest` (mutual self-ref cycle).

---

## Phase 4: Transformation Model (Fabrication & Temporal Jitter)

- [x] `TableTransformLoadStage` streaming execution (`fetchSize = 5000`) — all roles below implemented. The advanced relational paths (root-ancestor `INHERITED_ATTRIBUTE`, coherent group jitter) now have **end-to-end coverage** via `CoherenceE2ETest` (`firm → contract → schedule`: grandparent inheritance + preserved parent-child intervals), beyond the store/fallback unit tests.
  - `DIRECT_ID` via `lib-alterego` (`DirectIdStrategy`); `UNIQUE_CANDIDATE_KEY` via `AlterEgo.unique()` with a **length-preserving** collision fallback (zero-padded sequence overlaid on the value's tail — never widens a fixed-width/`CHECK` column; numeric columns fall back to a bare sequence).
  - `QUASI_ID` temporal jitter (SPEC §4.2): **standalone** dates → bucket-preserving `JITTER_WITHIN_MONTH`/`_YEAR` (exact per-period volumes); **ordered/related** dates (`created_at ≤ approved_at`, contract `start`/`end`, parent-child windows) → **one shared delta per coherence group** (exact orderings/intervals, "similar" volumes). Bucket jitter does NOT preserve ordering — only the shared delta does.
  - Cache the per-entity shared delta in `AttributeCascadeStore`, **scoped by coherence group** (`putJitterDelta(coherenceGroup, table, id, deltaDays)`), so a child inherits the delta anchoring *its* group (not an arbitrary FK parent's) and re-publishes it under itself so grandchildren inherit via one hop (SPEC §4.2).
  - `SENSITIVE` **declared `distinguishing` flag** (SPEC §2.2/§4.1): `distinguishing: false` $\rightarrow$ keep real; `distinguishing: true` $\rightarrow$ require `QuasiIdStrategy` or `RedactionStrategy`; missing `distinguishing`, or `distinguishing: true` with no strategy $\rightarrow$ `ConfigException` (fail-closed, checked at config time). Misdeclaration lint **on by default** (`distinguishingLint`: `WARN` default / `ERROR` fails the run / `OFF` skips): flag a `distinguishing: false` column whose real `COUNT(DISTINCT)` exceeds `maxCategoricalCardinality`.
  - `PAYLOAD` columns kept real.
  - Resolve `INHERITED_ATTRIBUTE` from the root ancestor: parents publish their fabricated value; the child walks its FK chain (via published source-id linkage) up to the `derivedFrom` table and reads it. Fail-closed — an unpublished ancestor or a genuine fork (two distinct ancestor rows) throws; a null FK yields null, never the child's own real value (SPEC §6.1).
  - Translate `PRIMARY_KEY` to surrogates; rewrite `FOREIGN_KEY` to mapped parent surrogates.

### Phase 4 follow-up — tech debt

- [ ] **Migrate hand-rolled value substitution into `lib-alterego`** (SPEC §1.4 delegation principle). `TableTransformLoadStage.fabricateShapePreserving(...)` performs class-preserving character substitution *inside Incognito*, which violates "Alterego fabricates fields; Incognito preserves relationships". Once AlterEgo ships the primitive, have `ALTEREGO_GENERIC` (and string-`SYNTHESISE`) delegate to it and delete the Incognito copy. Until then it is a tracked bug, not a sanctioned exception. **AlterEgo-side deliverable tracked in `../lib-alterego/PLAN.md` → Deferred → Downstream requests.**
  - **Privacy caveat (fictionality gap):** until this lands, `ALTEREGO_GENERIC` / string-`SYNTHESISE` output is only shape-preserving — it carries **no fictionality guarantee**, unlike the reserved-space built-ins (`emailAddress` → RFC 2606, `phoneNumber` → Ofcom, authored names). A fabricated generic value *could*, by coincidence, equal a real one. The guaranteed-fictional primitive closes this; the DIRECT_ID survival check (`VerificationStage`) is only a probabilistic net, not the guarantee.
- [ ] **Type-aware redaction** (`RedactionStrategy`): `CLEAR → null` breaks a `NOT NULL` column and `CONSTANT`/`MASK` assume text. These currently fail *loud* (a `SQLException` at insert, surfaced as `SchemaException`) — no silent corruption or privacy leak, so this is a robustness/usability item, not a safety one. The type-appropriate, format-preserving value production belongs in lib-alterego (§1.4); Incognito only picks which redaction each column gets. **AlterEgo-side deliverable tracked in `../lib-alterego/PLAN.md` → Deferred → Downstream requests.**
- [x] **Default-on misdeclaration lint runtime** (`distinguishingLint`) — done in Phase 6: `VerificationStage` runs `COUNT(DISTINCT)` (with a `pg_stats` pre-filter) on every `distinguishing: false` SENSITIVE column; `WARN` reports, `ERROR` throws, `OFF` skips. Never the gate. Covered by `DistinguishingLintTest`.

---

## Phase 5: Key Store & Complex Relational Handling

- [x] `InMemoryKeyTranslationStore` — bijective `old_pk → new_pk`, single-column values **and** `CompositeKey` tuples (the SPI keys on `Object`; composite PK/FK support landed in Phase 3 — `CompositeKeyE2ETest`).
- [x] `InMemoryAttributeCascadeStore` — `(parentTable, parentId, attr) -> value` (also stores `@fk:` source-id linkage for ancestor walking) and `(coherenceGroup, parentTable, parentId) -> deltaDays`; root-ancestor FK-chain resolution and fork detection live in `TableTransformLoadStage` (SPEC §6.1). Built during Phase 4; diamond paths get end-to-end coverage in Phase 7.

---

## Phase 6: Loader Engine, Trigger Isolation, Clean-Up & Verification

- [x] `PostgresDialectHandler` (+ uncertified `GenericDialectHandler` ANSI fallback): `SET session_replication_role='replica'` (superuser) on the **insert** connection, with `ALTER TABLE ... DISABLE TRIGGER USER` owner-fallback, `OVERRIDING SYSTEM VALUE`, and `setval(...)` resync.
  - `reWriteBatchedInserts=true` is a JDBC-URL param on the user's `DataSource` (not settable by the handler); now **documented** as a recommended target-connection setting in `README.md`.
  - [ ] Gap: the owner-mode degraded path still does **not** drop/recreate FK constraints (SPEC §9). A non-superuser target with cyclic FKs now **fails fast at config time** with a clear message (via `DialectHandler.canDeferCyclicForeignKeys`) instead of a confusing FK violation mid-load — but the full FK-dropping degraded mode remains outstanding.
- [x] `BulkDatabaseLoadStage` (implemented as an `AutoCloseable` per-table helper, not a discrete `PipelineStage` — transform+load stay coupled to keep streaming): pre-load trigger/FK suppression, batched `executeBatch()`, per-table transaction boundaries, 2-pass deferred `UPDATE` for placeholder cyclic FKs, post-load trigger restore + sequence resync.
- [x] `IncognitoCleanUpHandler`: on failure re-enables triggers, truncates partially loaded tables, resyncs sequences. Salt zeroing happens in `DefaultIncognitoPipeline.execute()`'s `finally` (both success and failure paths). Releasing the `AlterEgo` salt clone is not fully realised — see the Phase 6 follow-up below.
- [x] `VerificationStage`: referential integrity, e-mail fictionality, per-period volume tolerances (exact for `JITTER_WITHIN_*`, ±2% for `JITTER_DAYS`), the default-on misdeclaration lint (§4.1), and a source-value survival net (ratio-based: hard-fails on ~passthrough, warns on coincidental low-entropy collisions). Pipeline `success` now reflects stage failures.
- [x] `AnonymisationReport` emitter (`DpiaArtifactEmitter`) — serialises the typed report as **JSON**, **HTML**, and Markdown (all zero-dependency). The **passthrough audit** (opaque/untransformable-type flags — JSONB, array, geometry, INET, LOBs — §7.2) is now populated: `SchemaInspector` captures per-column JDBC types and kept columns of an opaque type are surfaced in the report. Report `transformation` labels corrected (`SENSITIVE distinguishing:false` now reads `KEEP`, not `REDACT`).

### Phase 6 follow-up — tech debt

- [ ] **Release the `AlterEgo` salt on completion** (SPEC §5.1/§8.1). `DefaultIncognitoPipeline` zeroes *Incognito's* salt copy in `finally`, but the `AlterEgo` instance retains its own defensive clone with no `close()`/zeroing API, and the pipeline holds `context` (→ `AlterEgo`) after `execute()` — so the secret outlives the run in memory. Incognito side: call the new API and drop the reference. **AlterEgo-side `destroy()`/`close()` deliverable tracked in `../lib-alterego/PLAN.md` → Deferred → Downstream requests.**
- [ ] **Test-coverage debt — two guards remain unexercised:** the **non-superuser fail-fast** (`canDeferCyclicForeignKeys`; tests run as superuser) and the **fail-closed cyclic-no-PK / composite-PK+cyclic** guards (`TableTransformLoadStage`). Add a non-superuser role and a PK-less/composite-PK cyclic table to cover them. (The §7.2 opaque-type **passthrough audit** is now covered by `PassthroughAuditE2ETest`.)
- [ ] **Observability / optional logging.** The library logs nothing today — deliberate: **the salt and row values must never be logged** (SPEC §7.3/§5.1). If operational logging is later wanted, use the JDK `System.Logger` facade (zero-dependency, agreed direction). First target: surface the currently **swallowed** failures — every `catch (SQLException ignored)` in `PostgresDialectHandler` and `IncognitoCleanUpHandler` means a failed compensation step (triggers left disabled, partial data) is invisible to the operator. Collect these into the `AnonymisationReport` and/or a `System.Logger` warning. Never log the salt or field values.
- [ ] **Complete public-API Javadoc** (remove the doclint `-missing` exclusion). The build now enforces Javadoc *quality* (`Xdoclint:all,-missing` + `-Xwerror`: syntax, HTML, references, accessibility). A doc comment / `@param` / `@return` on **every** public element across the API surface (the `api` interfaces/enums, `IncognitoException`, the strategy enums, and the `core`/`engine` stages) is a larger retrofit — the policy/report/store API is done; finishing it, then dropping `-missing`, matches lib-alterego's warning-free standard (its M5).
- [ ] Minor: JITTER_DAYS per-period volume tolerance (±2% monthly) can emit spurious *warnings* when the window pushes rows across month boundaries — cosmetic (never fails the run). The `DpiaArtifactEmitter` is an opt-in utility (not auto-invoked by the pipeline) — by design, but worth a doc note.

---

## Phase 7: Benchmark Integration Testing & Traceability Verification

**Pre-benchmark de-risk (done):** standalone Testcontainers tests already cover the schema features the
benchmarks rely on, so a benchmark failure points at the schema, not an unproven mechanism — `SERIAL`
(sequence-default) PKs vs `IDENTITY`/`OVERRIDING SYSTEM VALUE` (`SerialPkE2ETest`); `VIEW` +
`MATERIALIZED VIEW` exclusion (`ViewExclusionE2ETest`); exact per-period volume preservation for
`JITTER_WITHIN_MONTH` (`VolumeToleranceE2ETest`); composite PK/FK (`CompositeKeyE2ETest`); cyclic/self-ref
FKs (`CyclicFkE2ETest`); root-ancestor inheritance + coherent jitter (`CoherenceE2ETest`); and the
opaque-type passthrough audit (`PassthroughAuditE2ETest`).

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

---

## Post-v1.0 — possible future directions

Out of the locked v1.0 scope; recorded so the intent isn't lost, not committed to.

- [ ] **`RedisKeyTranslationStore` — a persisted, out-of-process key store.** v1.0 uses an in-memory `KeyTranslationStore` only (Redis is an explicit v1.0 non-goal). A persisted store would let key translation outlive a single JVM run — useful for very large clones that don't fit in heap, for resuming an interrupted load, and for cross-run stability of surrogates. **Constraint:** a persisted key store maps source PKs to surrogates and so is itself sensitive — it must be destroyed on successful completion (SPEC §5.3), exactly as the salt is. (A `redis:7-alpine` dev `docker-compose.yml` was removed once v1.0 shipped without it; reinstate a local service definition alongside this work if picked up.)
