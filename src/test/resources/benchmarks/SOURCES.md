# Benchmark fixture provenance & licences

The SQL files under this directory are third-party sample databases used **only** as Phase-7
integration-test fixtures. They live in `src/test/resources`, so they are **not** bundled into the
published library JAR — but they *are* redistributed as part of this git repository, so their
provenance and licences are recorded here, the required attribution is in [`NOTICE`](NOTICE), and a
verbatim copy of each licence is under [`LICENCES/`](LICENCES). All three licences are permissive and
allow redistribution with attribution.

All URLs retrieved **2026-07-31**.

| Dataset | Upstream | Vendored here | Download URL | Licence | Licence text |
|---|---|---|---|---|---|
| PetClinic | [spring-projects/spring-petclinic](https://github.com/spring-projects/spring-petclinic) (`main`) | `petclinic/schema.sql`, `petclinic/data.sql` | `.../src/main/resources/db/postgres/{schema,data}.sql` | Apache-2.0 | `LICENCES/Apache-2.0.txt` |
| Pagila | [devrimgunduz/pagila](https://github.com/devrimgunduz/pagila) (`master`) | `pagila/schema.sql` (data: fetched, see below) | `.../pagila-schema.sql` | PostgreSQL License | `LICENCES/PostgreSQL-License.txt` |
| Northwind | [pthom/northwind_psql](https://github.com/pthom/northwind_psql) (`master`) | `northwind/northwind.sql` | `.../northwind.sql` | Ms-PL | `LICENCES/Ms-PL.txt` |

## PetClinic — Apache License 2.0  ✅ used (`PetClinicBenchmarkE2ETest`)

- **Download:** `https://raw.githubusercontent.com/spring-projects/spring-petclinic/main/src/main/resources/db/postgres/schema.sql` and `.../data.sql`
- **Licence:** Apache-2.0 — <https://www.apache.org/licenses/LICENSE-2.0> (copy: `LICENCES/Apache-2.0.txt`).
- Verified against canonical counts (6 vets, 3 specialties, 5 vet_specialties, 6 types, 10 owners, 13 pets, 4 visits).

## Pagila — PostgreSQL License

- A PostgreSQL port of MySQL's **Sakila** example DB (originally by Mike Hillyer, MySQL AB docs team).
- **Vendored:** `pagila/schema.sql` — `https://raw.githubusercontent.com/devrimgunduz/pagila/master/pagila-schema.sql`
- **Data NOT vendored** (the canonical `pagila-data.sql` is ~13 MB; too large to commit for a fixture no test uses yet). **Fetch at test time and verify:**
  - URL: `https://raw.githubusercontent.com/devrimgunduz/pagila/master/pagila-data.sql`
  - SHA-256: `a88efa94c7ae8bc9cf55def4efc9f164d064d5b9cd93f11719ba3b5ace1602f7`
  - (An INSERT-statement variant, `pagila-insert-data.sql`, also exists if COPY-format loading via JDBC is inconvenient.)
- **Licence:** PostgreSQL License — <https://www.postgresql.org/about/licence/> (copy: `LICENCES/PostgreSQL-License.txt`).

## Northwind — Microsoft Public License (Ms-PL)

- Microsoft's Northwind sample, ported to PostgreSQL; the DB originates from Microsoft under Ms-PL.
- **Download:** `https://raw.githubusercontent.com/pthom/northwind_psql/master/northwind.sql`
- **Licence:** Ms-PL — <https://opensource.org/license/ms-pl-html> (copy: `LICENCES/Ms-PL.txt`).
- Not yet wired to a test.

## Notes on the model (improvements over `lib-alterego`'s)

`lib-alterego` vendors small curated dictionaries that ship *inside the JAR*, so its `NOTICE` is
top-level and packaged into `META-INF`. These fixtures are **test-only**, so the whole set is scoped
under `benchmarks/` and kept out of the artifact. Two additions beyond that model: the **licence
URL** is recorded next to the data URL (not just the data provenance), and an **over-large fixture**
(Pagila data) is pinned by **URL + SHA-256** for fetch-at-test-time rather than vendored as a 13 MB
blob — provenance without the repo bloat.
