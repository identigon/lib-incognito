# Benchmark fixture provenance & licences

The SQL files under this directory are third-party sample databases used **only** as Phase-7
integration-test fixtures. They live in `src/test/resources`, so they are **not** bundled into the
published library JAR — but they *are* redistributed as part of this git repository, so their
provenance and licences are recorded here, the required attribution is in [`NOTICE`](NOTICE), and a
verbatim copy of each licence is under [`LICENCES/`](LICENCES). Every licence here allows
redistribution with attribution; the employees fixture additionally carries a **share-alike**
obligation (CC BY-SA 3.0) — satisfied by keeping it under the same licence, attributing the chain of
authors, and marking the changes made (see below).

All URLs retrieved **2026-07-31**.

| Dataset | Upstream | Vendored here | Download URL | Licence | Licence text |
|---|---|---|---|---|---|
| PetClinic | [spring-projects/spring-petclinic](https://github.com/spring-projects/spring-petclinic) (`main`) | `petclinic/schema.sql`, `petclinic/data.sql` | `.../src/main/resources/db/postgres/{schema,data}.sql` | Apache-2.0 | `LICENCES/Apache-2.0.txt` |
| Pagila | [devrimgunduz/pagila](https://github.com/devrimgunduz/pagila) (`master`) | `pagila/schema.sql` (data: fetched, see below) | `.../pagila-schema.sql` | PostgreSQL License | `LICENCES/PostgreSQL-License.txt` |
| Northwind | [pthom/northwind_psql](https://github.com/pthom/northwind_psql) (`master`) | `northwind/northwind.sql` | `.../northwind.sql` | Ms-PL | `LICENCES/Ms-PL.txt` |
| Employees | [bytebase/employee-sample-database](https://github.com/bytebase/employee-sample-database) (`main`) | `employees/employees.sql` (assembled) | `.../postgres/dataset_small/{employee,load_*}.sql` | CC BY-SA 3.0 | `LICENCES/CC-BY-SA-3.0.txt` |
| Chinook | [lerocha/chinook-database](https://github.com/lerocha/chinook-database) (`master`) | `chinook/chinook.sql` (preamble stripped) | `.../ChinookDatabase/DataSources/Chinook_PostgreSql.sql` | MIT | `LICENCES/MIT-Chinook.txt` |

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
- Wired to a test (`NorthwindBenchmarkE2ETest`).

## Employees — Creative Commons Attribution-Share Alike 3.0 (CC BY-SA 3.0)  ✅ used (`EmployeesBenchmarkE2ETest`)

The classic temporal HR sample database (employees, departments, salaries, titles) — the archetypal
anonymisation/DPIA scenario. Its licence lineage is explicit and unbroken: the data file itself
carries the original licence header inline.

- **Origin chain (from the file header):** original data by Fusheng Wang & Carlo Zaniolo (Siemens
  Corporate Research / Aalborg TimeCenter); relational schema by Giuseppe Maxia; XML→relational
  conversion by Patrick Crews; © 2007, 2008 MySQL AB. Licensed **CC BY-SA 3.0 Unported**.
- **Vendored bytes:** [bytebase/employee-sample-database](https://github.com/bytebase/employee-sample-database),
  `postgres/dataset_small`. Bytebase's *repository* `LICENSE` is MIT (© 2022 tianzhou) and covers
  their packaging/tooling only; the **data file itself is CC BY-SA 3.0** (header preserved verbatim
  in our copy), so that is the licence recorded and complied with here.
- **Assembled, not upstream-verbatim.** Our `employees/employees.sql` is a mechanical assembly of
  bytebase's `dataset_small/employee.sql` (schema) with its six `load_*.sql` data includes inlined in
  FK-dependency order, and the psql meta-commands (`\echo`, `\i`) removed so it loads through a plain
  JDBC statement stream. **No schema object or data row was altered.** The changes made (assembly +
  meta-command removal) are stated in the file's own header, satisfying the CC BY-SA "indicate
  changes" term; the work stays under CC BY-SA 3.0 (share-alike).
- **Download URLs:** `https://raw.githubusercontent.com/bytebase/employee-sample-database/main/postgres/dataset_small/employee.sql`
  plus `load_department.sql`, `load_employee.sql`, `load_dept_emp.sql`, `load_dept_manager.sql`,
  `load_title.sql`, `load_salary1.sql` in the same directory.
- **Licence:** CC BY-SA 3.0 Unported — <https://creativecommons.org/licenses/by-sa/3.0/> (copy:
  `LICENCES/CC-BY-SA-3.0.txt`).

## Chinook — MIT  ✅ used (`ChinookBenchmarkE2ETest`)

A music-store schema (artists, albums, tracks, invoices, customers, employees) — tool-generated,
fictional data. Adds coverage the other benchmarks lack: `email` columns (verification-stage e-mail
fictionality net), a self-referential `employee.reports_to` FK, and a `TIMESTAMP` date-of-birth.

- **Origin:** authored by Luis Rocha; the single-file PostgreSQL script is generated from the
  project's canonical data sources. © 2008–2024 Luis Rocha.
- **Vendored:** `chinook/chinook.sql` — the upstream `Chinook_PostgreSql.sql` with only its
  database-creation preamble removed (`DROP DATABASE` / `CREATE DATABASE` / `\c chinook`) so it loads
  into an existing database via a plain JDBC statement stream. No table or data row was altered; the
  upstream header and licence reference are preserved. The change is stated in the file's own header.
- **Download:** `https://raw.githubusercontent.com/lerocha/chinook-database/master/ChinookDatabase/DataSources/Chinook_PostgreSql.sql`
- **Licence:** MIT — <https://github.com/lerocha/chinook-database/blob/master/LICENSE.md> (copy:
  `LICENCES/MIT-Chinook.txt`).

## Notes on the model (improvements over `lib-alterego`'s)

`lib-alterego` vendors small curated dictionaries that ship *inside the JAR*, so its `NOTICE` is
top-level and packaged into `META-INF`. These fixtures are **test-only**, so the whole set is scoped
under `benchmarks/` and kept out of the artifact. Two additions beyond that model: the **licence
URL** is recorded next to the data URL (not just the data provenance), and an **over-large fixture**
(Pagila data) is pinned by **URL + SHA-256** for fetch-at-test-time rather than vendored as a 13 MB
blob — provenance without the repo bloat.
