# Incognito

Incognito is a Java 25 library that clones a production database into a **schema-identical** test
database with every piece of PII replaced by **clearly fictional** data. It preserves what makes a
clone useful for testing — data volumes (including per-period volumes), referential integrity, the
foreign-key topology, and cardinalities — while severing the link to any real person. Direct
identifiers and quasi-identifiers are *fabricated*, not generalised or suppressed: Incognito is a
fabrication engine, **not** a k-anonymity / l-diversity / t-closeness implementation (those are
explicit non-goals — see [ADR 0001](docs/adr/0001-fabrication-not-k-anonymity.md)).

> **In one sentence:** *"I have a great production database and I want a test environment with similar data volumes and similar relationships between entities, but with no danger of leaking PII — a cloned database where the PII has been anonymised, and obviously anonymised, using clearly fictional data."*

See [`SPECIFICATION.md`](SPECIFICATION.md) for the full behavioural contract, [`PLAN.md`](PLAN.md)
for the implementation phases, [`docs/adr/`](docs/adr/) for the key design decisions and why they
were made, and [`CHANGELOG.md`](CHANGELOG.md) for what has changed between versions.

## Relationship to `lib-alterego`

Incognito delegates all **field-value** transformation to its sibling library
[`lib-alterego`](../lib-alterego) and owns everything relational on top:

| | `lib-alterego` | Incognito |
| :--- | :--- | :--- |
| **Scope** | one value, or the fields of one record | a whole relational database |
| **Job** | fabricate a replacement value (name, e-mail, date shift, …), deterministic in `(salt, domain, value)` | clone a schema and load it while keeping every cross-row / cross-table invariant intact |
| **Knows about** | values and formats | tables, primary/foreign keys, load order, triggers, sequences, DPIA reporting |

The boundary is simply: **Alterego fabricates fields; Incognito preserves relationships**
([ADR 0002](docs/adr/0002-two-libraries-two-responsibilities.md)). Incognito never implements its
own value substitution — where it needs a transformation Alterego does not yet expose, the fix is
to add it to Alterego, not to hand-roll it.

## Quick start

```java
byte[] ignored; // Incognito owns the secret salt internally and destroys it on completion.

AnonymisationPolicy policy = AnonymisationPolicy.builder()
    .table("customers", t -> t
        .column("id",    ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
        .column("email", ColumnRole.DIRECT_ID,   DirectIdStrategy.ALTEREGO_EMAIL)
        .column("dob",   ColumnRole.QUASI_ID,    QuasiIdStrategy.SYNTHESISE)
        .column("status", ColumnRole.PAYLOAD))            // operational data — kept real
    .build();

PipelineResult result = IncognitoPipeline.builder()
    .source(productionDataSource)   // read-only
    .target(testDataSource)         // schema-identical, empty
    .ephemeralSalt()                // >= 128-bit, generated per run, destroyed on completion
    .policy(policy)
    .build()                        // default stages (discover, transform+load, verify) auto-assembled
    .execute();
```

`execute()` streams every table in topological order, fabricates the classified columns, translates
primary keys to fresh surrogates and rewrites foreign keys to match, bulk-loads the target, and
verifies the result. It returns a `PipelineResult` carrying an `AnonymisationReport` (a DPIA
artifact).

## How it works

Every fabricated value is a deterministic function of a per-run secret salt, a domain, and the
input value — via Alterego's HMAC-SHA256 keyed generation. Direct identifiers and quasi-identifiers
are replaced with fictional values (RFC 2606 reserved e-mail domains, wide date jitter, and so on),
so a clone can never accidentally reference a real mailbox or single out a real person. Because
identifiers are fabricated, **operational data can be kept real** for realistic testing, and a
**low-cardinality sensitive flag can be kept real** too — once a row cannot be tied to a person, a
boolean discloses nothing about anyone.

Relational coherence is Incognito's job: primary keys become fresh surrogates and foreign keys are
rewritten to the same mapping ([key translation]); related dates shift by one shared, salt-keyed
delta per entity so parent–child windows and orderings survive ([coherent jitter][adr5]);
denormalised attributes are resolved from their root ancestor's fabricated value
([inherited attributes][adr7]); and cyclic / self-referential foreign keys load via a placeholder
plus a second-pass update ([cyclic FKs][adr6]).

[adr5]: docs/adr/0005-coherent-temporal-jitter.md
[adr6]: docs/adr/0006-cyclic-fk-two-pass-load.md
[adr7]: docs/adr/0007-inherited-attribute-root-ancestor.md
[key translation]: SPECIFICATION.md

## Fail-closed by design

An unclassified column **aborts the run** — Incognito never copies a column it was not told how to
handle, because an unspotted identifier is the one mistake that leaks real data
([ADR 0004](docs/adr/0004-fail-closed-classification.md)). Auto-inference only *suggests* roles; it
never silently assigns one. Whether a `SENSITIVE` column is kept real or fabricated is a one-word
**declaration** (`distinguishing: true | false`), checked before any row is read, not guessed from
the data ([ADR 0003](docs/adr/0003-declared-distinguishing-flag.md)).

## This is pseudonymisation-grade severing, and the salt is a secret

Fabrication severs the linkage between the clone and real subjects, and the fictionality guarantee
stops the clone being mistaken for real data. The per-run salt is generated fresh, never persisted
or logged, and destroyed when the run completes. Treat any salt you supply (in `reproducible`
mode) exactly like a credential.

## Building

Java 25 and Gradle. Incognito depends on `lib-alterego` as a local `-SNAPSHOT`; build it into your
local Maven repo first:

```
cd ../lib-alterego && ./gradlew publishToMavenLocal
cd ../lib-incognito && ./gradlew build
```

The integration tests use [Testcontainers](https://testcontainers.com/) and require Docker; they
skip gracefully where Docker is unavailable.

For best bulk-load throughput, configure the **target** `DataSource`'s JDBC URL with
`reWriteBatchedInserts=true` (a PostgreSQL driver setting that collapses a batch into a single
multi-row insert). Incognito batches its inserts but cannot set this on a `DataSource` you supply,
so it's a connection-config recommendation on your side.

## Licence

The source code is MIT-licensed — see [`LICENCE`](LICENCE).

### Benchmark test data

The Phase-7 benchmark fixtures under `src/test/resources/benchmarks/` are third-party sample
databases used only in tests (never bundled into the published JAR). Each is redistributed under its
own permissive licence — Spring PetClinic (Apache-2.0), Pagila (PostgreSQL License), Northwind
(Ms-PL). Full provenance, download and licence URLs are in
[`benchmarks/SOURCES.md`](src/test/resources/benchmarks/SOURCES.md), attribution in
[`benchmarks/NOTICE`](src/test/resources/benchmarks/NOTICE), and verbatim licence texts under
`benchmarks/LICENCES/`.
