# Incognito — Implementation Plan

Phased plan for building `Incognito` — a Java 25 library that clones a production database into a schema-identical test database with all PII replaced by **clearly fictional** data, preserving data volumes and inter-entity relationships. See `SPECIFICATION.md` §1 for goals/non-goals.

**v1.0 scope (locked):** PostgreSQL only · in-memory `KeyTranslationStore` only · **fabrication** of identifiers/quasi-identifiers via `lib-alterego` — **not** k-anonymity / l-diversity / t-closeness (explicit non-goals; no statistical analysis of the clone) · no JSON/JSONB, spatial, biometric/media, array, or INET transformations · single-threaded.

**Build prerequisite:** `lib-alterego` is consumed from the local Maven repo as `io.github.dconneely:alterego:0.2.0-SNAPSHOT`. Until it is published to a shared repository, build it first: `cd ../lib-alterego && ./gradlew publishToMavenLocal`. (Bump the version in `build.gradle.kts` if lib-alterego advances.)

---

## Phase 1: Project Foundation & Core API Interfaces

- [x] Set up Java 25 build (`build.gradle.kts` / `settings.gradle.kts`) with a Java 25 toolchain. `io.github.dconneely:alterego:0.2.0-SNAPSHOT` dependency. `./gradlew test` is green.
- [x] Add `lib-alterego` and SnakeYAML dependencies (SnakeYAML bundled in core; module split tracked in Phase 3). **No Jedis / Redis in v1.0** (in-memory `KeyTranslationStore` only).
- [x] Create package structure: `org.identigon.incognito.{api,core,spi,policy,engine}`.
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

- [x] **Shape-preserving fabrication stays in Incognito — sanctioned via AlterEgo's extension API (decision).** `TableTransformLoadStage.fabricateShapePreserving(...)` is a caller-supplied `Strategy<String>` bound through `alterEgo.bind(domain, (input, ctx) -> …)` (the `ALTEREGO_GENERIC` and string-`SYNTHESISE` paths). It runs on AlterEgo's salt-keyed stream (`ctx.random()`) and inherits determinism, `unique()`, `stored()`, and record-coherence parity from the bind; only the ~6-line character-class walk is local. Because value production happens *on* AlterEgo's rails, this is **not** a §1.4 / invariant-#8 violation — the earlier "tracked violation, migrate it" framing was wrong. **Decision: it lives here permanently, provided it keeps using AlterEgo's extension mechanism.** It carries **no fictionality guarantee** — genuinely impossible for an arbitrary shape (a guarantee needs a reserved space, hence a known format; cf. AlterEgo's blocked `companyNumber()`); that guarantee is delivered only by the **typed** generators. Optional and unplanned: AlterEgo could promote a shared `shapePreserving(domain)` built-in for cross-consumer reuse, but Incognito does not need it.
  - **Privacy position (settled, not a temporary gap):** `ALTEREGO_GENERIC` / string-`SYNTHESISE` output is shape-preserving with **no fictionality guarantee** — a fabricated generic value *could*, by coincidence, equal a real one. This is inherent (above). The guarantee is available only via reserved-space built-ins (`emailAddress` → RFC 2606, `phoneNumber` → Ofcom, authored names, the identifier built-ins); route a column to a typed strategy when the guarantee matters. The DIRECT_ID survival check (`VerificationStage`) is a probabilistic net, not the guarantee.
- [x] **Type-aware redaction** (`RedactionStrategy`) — **done** (consumes AlterEgo 0.3.0-SNAPSHOT). `CONSTANT`/`MASK` no longer assume text: value production is delegated to `AlterEgo.redact(Class<T>)` / `constant` / `mask`, so a numeric, temporal, boolean or opaque `SENSITIVE` column receives a type-appropriate constant that fits the column instead of failing at insert. `MASK` masks text and falls back to the typed constant for non-text; `CLEAR` stays `null` (a `NOT NULL` column should use `CONSTANT`/`MASK`). Covered by `RedactionTypeE2ETest` (INTEGER/NUMERIC/DATE/BOOLEAN + text MASK).
- [x] **Default-on misdeclaration lint runtime** (`distinguishingLint`) — done in Phase 6: `VerificationStage` runs `COUNT(DISTINCT)` (with a `pg_stats` pre-filter) on every `distinguishing: false` SENSITIVE column; `WARN` reports, `ERROR` throws, `OFF` skips. Never the gate. Covered by `DistinguishingLintTest`.

---

## Phase 5: Key Store & Complex Relational Handling

- [x] `InMemoryKeyTranslationStore` — bijective `old_pk → new_pk`, single-column values **and** `CompositeKey` tuples (the SPI keys on `Object`; composite PK/FK support landed in Phase 3 — `CompositeKeyE2ETest`).
- [x] `InMemoryAttributeCascadeStore` — `(parentTable, parentId, attr) -> value` (also stores `@fk:` source-id linkage for ancestor walking) and `(coherenceGroup, parentTable, parentId) -> deltaDays`; root-ancestor FK-chain resolution and fork detection live in `TableTransformLoadStage` (SPEC §6.1). Built during Phase 4; diamond paths get end-to-end coverage in Phase 7.

---

## Phase 6: Loader Engine, Trigger Isolation, Clean-Up & Verification

- [x] `PostgresDialectHandler` (+ uncertified `GenericDialectHandler` ANSI fallback): `SET session_replication_role='replica'` (superuser) on the **insert** connection, with `ALTER TABLE ... DISABLE TRIGGER USER` owner-fallback, `OVERRIDING SYSTEM VALUE`, and `setval(...)` resync.
  - `reWriteBatchedInserts=true` is a JDBC-URL param on the user's `DataSource` (not settable by the handler); now **documented** as a recommended target-connection setting in `README.md`.
  - [x] **Owner-mode FK-drop/recreate done** (SPEC §9). A non-superuser target that **owns** its tables now clones cyclic/self-referential FKs by dropping the cyclic FK constraints before the load — capturing their exact `pg_get_constraintdef` definitions — and recreating them verbatim after the pass-2 `UPDATE`, each step atomic (transactional DDL). `IncognitoCleanUpHandler` recreates them on failure too. A role that can neither set `session_replication_role` (superuser) nor drop the constraints (owner) still fails fast with a clear message. Also fixed a latent bug the path exposed: the failed `SET session_replication_role` aborted the autocommit-off load transaction, so the owner-mode `DISABLE TRIGGER` fallback could never run — now rolled back first. Covered by `OwnerModeFkDropE2ETest`.
- [x] `BulkDatabaseLoadStage` (implemented as an `AutoCloseable` per-table helper, not a discrete `PipelineStage` — transform+load stay coupled to keep streaming): pre-load trigger/FK suppression, batched `executeBatch()`, per-table transaction boundaries, 2-pass deferred `UPDATE` for placeholder cyclic FKs, post-load trigger restore + sequence resync.
- [x] `IncognitoCleanUpHandler`: on failure re-enables triggers, truncates partially loaded tables, resyncs sequences. Salt zeroing happens in `DefaultIncognitoPipeline.execute()`'s `finally` (both success and failure paths) — Incognito's own copy **and** the `AlterEgo` instance's internal clone (via `AlterEgo.close()`, see the now-resolved Phase 6 follow-up).
- [x] `VerificationStage`: referential integrity, e-mail fictionality, per-period volume tolerances (exact for `JITTER_WITHIN_*`, ±2% for `JITTER_DAYS`), the default-on misdeclaration lint (§4.1), and a source-value survival net (ratio-based: hard-fails on ~passthrough, warns on coincidental low-entropy collisions). Pipeline `success` now reflects stage failures.
- [x] `AnonymisationReport` emitter (`DpiaArtifactEmitter`) — serialises the typed report as **JSON**, **HTML**, and Markdown (all zero-dependency). The **passthrough audit** (opaque/untransformable-type flags — JSONB, array, geometry, INET, LOBs — §7.2) is now populated: `SchemaInspector` captures per-column JDBC types and kept columns of an opaque type are surfaced in the report. Report `transformation` labels corrected (`SENSITIVE distinguishing:false` now reads `KEEP`, not `REDACT`).

### Phase 6 follow-up — tech debt

- [x] **Release the `AlterEgo` salt on completion** (SPEC §5.1/§8.1) — **done** (consumes AlterEgo 0.3.0-SNAPSHOT). `AlterEgo` is now `AutoCloseable` with `destroy()`/`close()` that zeroes its internal salt clone; `DefaultIncognitoPipeline.execute()`'s `finally` calls `context.alterEgo().close()` after zeroing Incognito's own copy, on both success and failure paths. Every E2E test exercises it.
- [x] **Fail-closed guards now covered** — `FailClosedGuardE2ETest`: the **non-superuser fail-fast** (cyclic FK against a real `NOSUPERUSER` role → `ConfigException`), the **composite FK → cyclic table** guard (self-referential composite FK over a deferrable 2-cycle → `ConstraintException`), and the **cyclic FK with no single-column PK** guard (self-ref FK on a `UNIQUE`-but-no-PK table → `ConstraintException`). (The §7.2 opaque-type passthrough audit is covered by `PassthroughAuditE2ETest`.)
- [x] **Observability / optional logging — done.** Swallowed failures now surface via the JDK `System.Logger` facade (zero-dependency): `IncognitoCleanUpHandler`'s four best-effort compensation steps (truncate, re-enable triggers/FKs, resync sequence, connect-to-target) log a **WARNING** so a target left inconsistent is not invisible; `PostgresDialectHandler`'s often-benign `ENABLE TRIGGER` swallow and its owner-mode fallback, plus `VerificationStage`'s pg_stats-unavailable fallthrough, log at **DEBUG**. Each record carries only the **operation, table and SQLState** — never the exception message (which could contain a field value) nor the salt (SPEC §7.3/§5.1, hard invariant 3). Covered by `ObservabilityTest`, which also plants a secret in the failure and asserts it never reaches the log. No other logging is emitted.
- [x] **Complete public-API Javadoc — done.** The doclint `-missing` exclusion is dropped: the build now enforces the full `Xdoclint:all` group (`+ -Xwerror`), so a doc comment / `@param` / `@return` / `@throws` on **every** public element (the `api` interfaces/enums, `IncognitoException`, the strategy enums, and the `core`/`engine` stages, handlers, stores and records) is required and the published javadoc jar is warning-free — matching lib-alterego's standard.
- [x] **JITTER_DAYS volume check uses yearly buckets** — a ±N-day jitter crosses month boundaries, so the old monthly ±2% check raised spurious drift *warnings* (cosmetic, never a failure); yearly buckets barely leak. Covered by `JitterDaysVolumeE2ETest`. The `Instant`/`LocalDateTime` temporal-shift branches — unreachable via the JDBC read path (`rs.getObject` yields `java.sql.Date`/`Timestamp`) — are now unit-tested by `TemporalShiftTest` (the helper is package-private for that).
- [x] Minor: documented that `DpiaArtifactEmitter` is **opt-in** (not auto-invoked) — the pipeline always builds the report (`PipelineResult.report()`); persisting a JSON/HTML/Markdown DPIA file is a caller choice. Note added to the emitter's Javadoc.

---

## Phase 7: Benchmark Integration Testing & Traceability Verification

**Pre-benchmark de-risk (done):** standalone Testcontainers tests already cover the schema features the
benchmarks rely on, so a benchmark failure points at the schema, not an unproven mechanism — `SERIAL`
(sequence-default) PKs vs `IDENTITY`/`OVERRIDING SYSTEM VALUE` (`SerialPkE2ETest`); `VIEW` +
`MATERIALIZED VIEW` exclusion (`ViewExclusionE2ETest`); exact per-period volume preservation for
`JITTER_WITHIN_MONTH` (`VolumeToleranceE2ETest`); composite PK/FK (`CompositeKeyE2ETest`); cyclic/self-ref
FKs (`CyclicFkE2ETest`); root-ancestor inheritance + coherent jitter (`CoherenceE2ETest`); and the
opaque-type passthrough audit (`PassthroughAuditE2ETest`).

- Testcontainers PostgreSQL benchmark suites:
  - [x] **Multi-path diamond** (`firm → office/contract → schedule`) — `DiamondE2ETest`: convergent paths resolve an `INHERITED_ATTRIBUTE` from the shared ancestor; divergent paths (two distinct ancestor rows) **fail closed** (SPEC §6.1).
  - [x] **Spring PetClinic** — `PetClinicBenchmarkE2ETest` (from the staged fixture): `GENERATED BY DEFAULT AS IDENTITY` PKs, a **PK-less** `UNIQUE` join table (`vet_specialties`), a functional unique index, and the `owner → pet → visit` graph clone with integrity + PII fabrication.
  - [x] **Pagila (Sakila)** — `PagilaBenchmarkE2ETest`, pinned to tag **`pagila-v3.0.0`** (pre-pgvector: 22 tables, no extension) rather than `master` (needs the **pgvector** extension for `film_embedding` + has 55 `payment_p*` partitions). Clones the 15 non-partitioned Sakila core tables; the partitioned `payment` is excluded from the policy. Schema vendored; the ~5 MB `pagila-insert-data.sql` is fetched at test time and SHA-256-verified (skips without network); `OWNER TO postgres` is stripped at load; source data loads under `session_replication_role='replica'` to bypass the dump's non-FK-ordered rows. Exercises two composite PKs (`film_actor`, `film_category`), seven excluded views, opaque `bytea`/`tsvector`/array passthrough, and `email` fabrication. **Surfaced and fixed** a real engine gap: a kept **enum** / user-type column failed on re-insert (a read `String` bound as `varchar` → type mismatch), so no table with an enum could be cloned — now `String` values bind as `Types.OTHER` and PostgreSQL casts to the column's type. **DVD Rental** (postgresqltutorial) was re-evaluated and rejected again: no citable licence + binary `pg_restore` format (not our JDBC statement-stream harness).
  - [x] **Northwind** — `NorthwindBenchmarkE2ETest` (from the vendored fixture): a real 14-table graph exercising, together, the self-referential `employees.reports_to` cyclic FK, three composite-PK join tables, opaque `bytea` columns (surfaced in the passthrough audit), and text PKs — with row volumes preserved, referential integrity intact, and PII fabricated.
  - [x] **Employees** — `EmployeesBenchmarkE2ETest` (from the vendored fixture): the classic temporal HR schema — the archetypal DPIA scenario. Covers a shape no other test does: **composite PKs of a surrogated FK + kept temporal value** (`salary(emp_no, from_date)`, `title(emp_no, title, from_date)`), where a `SEQUENTIAL_LONG` surrogate on the `SERIAL` `employee` PK is rewritten consistently into every child composite key. Also a strongly-identifying `birth_date` (SPEC §7.3 #5, synthesised), `DIRECT_ID` names, and two `VIEW`s excluded from cloning yet live over the clone. ~12 k rows across 7 base tables.
  - [x] **Chinook** — `ChinookBenchmarkE2ETest` (from the vendored fixture): a music-store schema. Adds coverage the others lack — `email` columns (`ALTEREGO_EMAIL` + the verification stage's e-mail fictionality net), a self-referential `employee.reports_to` FK **whose reassigned `INT` PK is surrogated** (self-ref cyclic Pass-2 UPDATE across a `SEQUENTIAL_LONG` remap), a composite-PK join table (`playlist_track`), and a `TIMESTAMP` DOB. Volumes compared source-vs-target. **Surfaced and fixed** a real engine bug: `QUASI_ID SYNTHESISE` emitted a `varchar` for a `TIMESTAMP`/`LocalDateTime` column (fits `DATE` only), so a `TIMESTAMP` DOB failed at insert — now shifted within the same salt-keyed ±5y window preserving type and time-of-day (`TableTransformLoadStage`).
- [x] **Benchmark-fixture provenance/licence done** — re-sourced from canonical upstreams with recorded download + licence URLs; verbatim licence texts under `benchmarks/LICENCES/`, attribution in `benchmarks/NOTICE`, provenance in `benchmarks/SOURCES.md`. Licences: PetClinic Apache-2.0, Pagila PostgreSQL License, Northwind Ms-PL, Employees CC BY-SA 3.0 (share-alike honoured: same licence, full attribution chain, changes noted in the file header), Chinook MIT. Pagila's 13 MB data is pinned by URL+SHA-256 rather than vendored.
- [x] **Temporal jitter/synthesise made type-complete** (was: `JITTER_DAYS` timestamp leak found via Chinook). Every QI branch — `JITTER_WITHIN_MONTH`/`_YEAR`, `JITTER_DAYS` (grouped and not), and `SYNTHESISE` — now shifts `LocalDate`/`java.sql.Date` (`shiftDate`), `java.sql.Timestamp`/`LocalDateTime` (`shiftDateTime`), and `Instant` (`shiftDateTime` at UTC) through a shared `shiftTemporalOrNull` helper. Previously a `TIMESTAMP`/`LocalDateTime` passed through **unshifted** under the non-`SYNTHESISE` branches — a real QI surviving (§7.3). The shared helper's timestamp path is exercised via Chinook (`SYNTHESISE` on a `TIMESTAMP` DOB); the jitter branches reuse it.
- [x] **`shiftInstant` domain bug — fixed upstream** (lib-alterego `082a4a5`). It built a domain containing `=` that failed AlterEgo's own domain regex and threw for any arguments; it now routes through the same fragment helpers as `shiftDateTime`. Incognito nonetheless shifts `Instant` via `shiftDateTime` at UTC **by design** — one path covers every jitter mode, and `shiftInstant` has no `DateField` overload so it cannot serve the within-month / within-year modes. No change needed here; the former workaround is simply the uniform choice.
- [x] **Invariants & traceability verified** — each mapped to a test:
  - Direct IDs & QIs fabricated → the five benchmarks (name/email/DOB assertions); secret salt destroyed on completion — Incognito's copy zeroed **and** the `AlterEgo` clone closed → `SaltLifecycleTest`; never logged → `ObservabilityTest` (coarse logs only) + by construction (no persistence path).
  - Coherent parent–child date deltas / monotonic ordering preserved → `CoherenceE2ETest` (source date-intervals equal target intervals, so relative order holds).
  - High-cardinality candidate keys transformed via the length-preserving sequence fallback, no collision crash → `TableTransformLoadStageTest` (length-preserving, no overflow across many inputs).
  - Misdeclaration lint per `distinguishingLint` (`OFF` no scan / `WARN` reports / `ERROR` fails; never the gate) → `DistinguishingLintTest`.
  - `AnonymisationReport` DPIA accountability evidence → `DpiaArtifactEmitterTest` (JSON/HTML/Markdown) + the benchmark passthrough-audit assertions.

---

## Code hygiene tooling

Kept in step with the sibling repos (`../play-bazlang`, `../lib-alterego`); deliberately minimal to start.

- [x] **Spotless (tidy-only)** — `importOrder`, `removeUnusedImports`, trailing-whitespace, EOF newline; **no** `googleJavaFormat` (it would reflow the hand-maintained style). `spotlessCheck` runs in `check`; `spotlessApply` fixes. Plugin `com.diffplug.spotless` 8.8.0.
- [x] **pre-commit / prek hooks** (`.pre-commit-config.yaml`) — `spotlessApply` + `compile` (local Gradle), the native hygiene hooks (trailing-whitespace, end-of-file-fixer, check-yaml, check-added-large-files), and **gitleaks** secret-scanning. SpotBugs and the test suite are omitted (too slow for a commit hook).
- [ ] **SpotBugs + find-sec-bugs** (CI, `ignoreFailures = false`) — the security-focused follow-up. The first run will flag the ~18 SQL-by-string-concatenation sites (catalog identifiers, not user input); resolve by quoting the identifiers or a justified `config/spotbugs/exclude.xml`. Versions to match `../play-bazlang`: spotbugs plugin 6.5.9, tool 4.9.8.
- [ ] Optional / consistency-only: PMD (bug-focused; prefer over Checkstyle, which duplicates the `Xdoclint:all` gate) and JaCoCo (a coverage metric — the suite is already thorough by design).

## Post-v1.0 — possible future directions

Out of the locked v1.0 scope; recorded so the intent isn't lost, not committed to.

- [ ] **Declarative-partitioning support.** Surfaced by the Pagila benchmark, which excludes the partitioned `payment` table. Today partition children are discovered as plain tables and the partitioned parent isn't specially handled, so a partitioned table can't be cloned coherently. A proper treatment would recognise the parent/child relationship (`pg_partitioned_table`/`pg_inherits`), clone by inserting into the parent (letting Postgres route to partitions), and skip the children — preserving per-partition volumes. Non-partitioned tables are unaffected.
- [ ] **`RedisKeyTranslationStore` — a persisted, out-of-process key store.** v1.0 uses an in-memory `KeyTranslationStore` only (Redis is an explicit v1.0 non-goal). A persisted store would let key translation outlive a single JVM run — useful for very large clones that don't fit in heap, for resuming an interrupted load, and for cross-run stability of surrogates. **Constraint:** a persisted key store maps source PKs to surrogates and so is itself sensitive — it must be destroyed on successful completion (SPEC §5.3), exactly as the salt is. (A `redis:7-alpine` dev `docker-compose.yml` was removed once v1.0 shipped without it; reinstate a local service definition alongside this work if picked up.)
