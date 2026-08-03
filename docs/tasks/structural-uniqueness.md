# Design: Structural-uniqueness report (DPIA evidence)

Status: draft (post-v1.0 roadmap; see `PLAN.md` → "Post-v1.0")

## 1. Problem

Incognito fabricates every field value but deliberately preserves *shape*: row counts per table are
kept 1:1 and the foreign-key graph is reproduced edge-for-edge (Goal 2 / SPEC §4.5). That structural
fidelity is what makes the clone useful — and it is also the one re-identification vector v1.0 does
**not** mitigate, called out explicitly in SPEC §2.4:

> Row counts and the FK graph are preserved 1:1, so a subject with a distinctive relational
> fingerprint (e.g. the one entity with 300 linked children) may be re-identifiable from *structure*
> even with fabricated fields — and any real value on that row (a kept operational/sensitive field)
> is then disclosed. v1.0 does not mitigate this; a **structural-uniqueness report** is a roadmap
> DPIA-evidence item.

The GDPR framing (SPEC §2.1): anonymity requires that a subject cannot be **singled out**,
**linked**, or **inferred** (Recital 26, Art. 29 WP Opinion 05/2014). A relational fingerprint
singles out directly — "the customer with exactly 300 orders, one of them refunded twice" identifies
a row regardless of what its name column now says. A motivated intruder who knows one real subject's
approximate structure can locate that subject's row and read any value Incognito legitimately kept
on it (a `PAYLOAD` or `distinguishing: false SENSITIVE` column, SPEC §7.2).

This is **not** a bug to fix by changing the data — preserving structure is a requirement, and
generalising it away would defeat the product's purpose. It is a residual risk to **quantify and
surface**, so the human DPIA can weigh it. That is exactly what the existing report already does for
opaque-type passthroughs, source-value survival, and misdeclaration lint; this closes the last named
gap.

## 2. Goal and non-goals

**Goal.** Emit, per run, structured evidence of how many subjects carry a rare or unique
*structural* fingerprint, so a DPIA reviewer can judge the singling-out exposure instead of
reasoning about it unaided.

**Non-goals.**

- **Not a privacy gate.** Like `pg_stats` (ADR 0003) and the misdeclaration lint (SPEC §4.1), this
  is advisory evidence, never a keep-vs-fabricate decision and never a run-abort by default. It
  changes no data.
- **Not a k-anonymity engine.** We are not enforcing a minimum group size or perturbing structure to
  reach one (ADR 0001 — Incognito fabricates, it does not generalise). Measuring uniqueness ≠
  eliminating it.
- **Not a full graph-isomorphism analysis.** A complete "can this subgraph be matched against
  external knowledge" assessment is a research problem and stays the DPIA's job. We compute cheap,
  explainable local fingerprints, not global structural equivalence.

## 3. What "structural fingerprint" means here

The signal a distinctive subject leaves in the FK graph is captured by a few cheap, per-row local
degree features. For a table `T`:

- **Child-count (fan-out) fingerprint.** For each parent row, the vector of counts of referencing
  rows per child FK, e.g. `customer` → (number of `order` rows, number of `address` rows). The "one
  entity with 300 linked children" is a rare value in this vector's distribution.
- **In-degree along a single edge.** The simplest special case: `COUNT(*)` of children grouped by
  the parent key, one child table at a time. Rare counts (the max, or any count held by only one
  parent) are the singling-out risk.

v1 of *this feature* should implement the single-edge in-degree case first (cheapest, most
explainable) and treat the multi-edge fan-out vector as a follow-on, because the vector's
cardinality explodes and its "rareness" is harder to threshold defensibly.

Fingerprints are computed on the **source** database. Because fabrication preserves structure
exactly, the source and target fingerprints are identical by construction — so we measure on the
source (which we already open connections to in `VerificationStage`) and the finding describes the
target's residual risk without a second scan. (A cross-check that target structure actually equals
source structure is already partly covered by referential-integrity verification; a strict
structural-equality assertion could be added but is not required for the evidence to be sound.)

## 4. The metric

For a single FK edge `child(fkCols) -> parent(pk)`:

```
counts = SELECT parent_key, COUNT(*) AS c
         FROM child
         GROUP BY parent_key            -- (parents with zero children are absent; treated as c=0)
```

From the `c` distribution, report:

- `distinctParents` — number of parent rows that have at least one child.
- `maxChildCount` — the largest fan-out (the "300 children" row).
- `uniqueFingerprintCount` — how many `c` values are held by exactly one parent (a row singled out
  by its child count alone).
- `rareFingerprintCount` — how many parents have a `c` shared by fewer than `k` parents, for a
  configurable `k` (the same "how small is too small" knob a DPIA reviewer thinks in).

A parent row whose child-count is unique is, on that edge alone, structurally singled out. That is
the headline number for the report.

## 5. Report schema

Add a top-level list to `AnonymisationReport` (mirroring `survivalFindings` / `lintFindings`):

```java
public record StructuralUniquenessFinding(
    String parentTable,       // the singled-out subject's table
    String childTable,        // the edge that produced the fingerprint
    long distinctParents,     // parents with >=1 child on this edge
    long maxChildCount,       // largest fan-out observed
    long uniqueFingerprintCount,  // parents singled out by their child-count alone
    long rareFingerprintCount,    // parents in a fingerprint-group smaller than k
    int k                     // the rareness threshold used
) {}
```

Emitted in all three `DpiaArtifactEmitter` formats under a "Structural re-identification risk"
heading, next to the survival/lint tables. Populated by a new analysis in (or invoked from)
`VerificationStage`, published to the report via a context attribute exactly like
`ATTR_SURVIVAL_FINDINGS` / `ATTR_LINT_FINDINGS`.

## 6. Where it runs in the pipeline

`VerificationStage` already holds source and target connections and iterates
`plan.sequentialTableOrder()` with each table's `SchemaInspector.TableMetadata` (which carries
`foreignKeyConstraints()` and `primaryKeyColumns()`). The analysis fits as a new numbered section
there:

1. For each table `T` with a primary key, find every *child* edge (another table whose
   `ForeignKeyConstraint.parentTable()` is `T`).
2. Run the grouped-count query per edge on the source connection.
3. Aggregate the `c` distribution in Java (or with a second `GROUP BY c` SQL pass for large tables).
4. Emit one `StructuralUniquenessFinding` per edge that has any unique/rare fingerprints.

Composite parent keys use the full ordered `parentColumns`/`primaryKeyColumns` in the `GROUP BY`.

## 7. Configuration

- **Opt-in and off by default**, because it is a per-edge aggregate scan whose cost scales with
  child table size — the same cost profile that makes the misdeclaration lint configurable (SPEC
  §4.1). A policy-level toggle, e.g. `structuralUniqueness: OFF | REPORT` (default `OFF`), parallel
  to `distinguishingLint`. There is deliberately no `ERROR` mode: this never fails a run, because
  structural uniqueness is inherent to a faithful clone, not a defect to reject.
- **`k` threshold** (rareness cutoff), reusing the mental model of `maxCategoricalCardinality`;
  default small (e.g. `k = 5`).
- Honour any existing table/column include-exclude scoping so the scan skips tables out of policy.

## 8. Cost and scaling

- One `GROUP BY parent_key` per FK edge. On PostgreSQL this is an index-assisted aggregate when the
  FK column is indexed (usually true for FKs that back joins); worst case a sequential scan per
  edge.
- `pg_stats.n_distinct` on the child FK column can pre-filter edges that are obviously low-fan-out
  (every parent has ~the same handful of children) and skip the exact scan — same
  optimisation-not-gate role pg_stats plays elsewhere (SPEC §4.1, ADR 0003). Never let a
  missing/stale statistic *suppress* a finding; pre-filter only to *skip work that cannot produce* a
  rare fingerprint.
- Bound the aggregation memory: stream the `GROUP BY c` rollup rather than pulling every parent key
  into the JVM.

## 9. What this does and does not tell a DPIA reviewer

It tells them: on edge X, N subjects are singled out by their relational shape alone, and the
extreme fan-out is M. Combined with the passthrough audit (which real values are retained on those
rows), that is concrete, quantified singling-out evidence.

It does **not** assert the clone is or is not anonymous. SPEC §2.4 stands: "A DPIA /
motivated-intruder assessment remains the final arbiter of 'anonymous.'" This feature makes that
assessment better informed; it does not perform it.

## 10. Implementation checklist (mechanical)

Follow these steps in order; each mirrors an existing pattern in the codebase so you can copy from a
working example rather than invent.

**Step 1 — report schema.** Add the `StructuralUniquenessFinding` record from §5 to
`AnonymisationReport` (api package), and a top-level `List<StructuralUniquenessFinding>
structuralFindings` field on the record. Copy the shape and Javadoc style of the existing
`SurvivalFinding` / `LintFinding` records verbatim. Update the SPEC §7 schema block and the
`AnonymisationReport` constructor calls in `AnonymisationReportBuilder` (there are two) — the
compiler will point you at both.

**Step 2 — attribute key.** In `AnonymisationReportBuilder`, add `public static final String
ATTR_STRUCTURAL_FINDINGS = "incognito.verification.structuralFindings";` next to the existing
`ATTR_SURVIVAL_FINDINGS`. In `build(...)`, read it with `getOrDefault(..., Collections.emptyList())`
exactly like `survivalFindings`, and pass it into both constructor calls.

**Step 3 — the analysis.** In `VerificationStage`, add a new numbered section (after the survival
check) that, for each in-plan table `T` with a non-empty `primaryKeyColumns()`, finds every child
edge — iterate all tables' `foreignKeyConstraints()` and keep those whose `parentTable()` equals
`T`. For each such edge run this rollup on the **source** connection:

```sql
SELECT c, COUNT(*) AS parents
FROM (
    SELECT <fkCol1>, <fkCol2>, ..., COUNT(*) AS c
    FROM <childTable>
    WHERE <fkCol1> IS NOT NULL AND <fkCol2> IS NOT NULL AND ...   -- every FK column not null
    GROUP BY <fkCol1>, <fkCol2>, ...
) parent_counts
GROUP BY c
```

The `<fkColN>` come from `ForeignKeyConstraint.childColumns()` in order (a single-column FK has one;
a composite FK has several — join them with commas for both the `SELECT`/`GROUP BY` list and the `IS
NOT NULL` predicate). Quote identifiers the same way the rest of `VerificationStage` /
`PostgresDialectHandler` does. Then compute, iterating the `(c, parents)` rows in Java:

- `distinctParents` = Σ `parents`
- `maxChildCount` = max `c`
- `uniqueFingerprintCount` = number of rows where `parents == 1` (each is exactly one singled-out
  parent)
- `rareFingerprintCount` = Σ `parents` over rows where `parents < k`

Emit a `StructuralUniquenessFinding` for the edge only if `uniqueFingerprintCount > 0` or
`rareFingerprintCount > 0`. Collect them into a local `List` and, at the end of `process(...)`,
publish it with `context.attributes().put(AnonymisationReportBuilder.ATTR_STRUCTURAL_FINDINGS,
list)` — copy the two lines that already publish `survivalFindings` / `lintFindings`.

**Step 4 — config toggle.** Add a `structuralUniqueness` enum (`OFF` default, `REPORT`) to
`AnonymisationPolicy` and its YAML parser, mirroring `distinguishingLint`. Guard the whole Step-3
section behind `policy.structuralUniqueness() == REPORT`. Reuse (or add alongside) a `k` value; a
plain `int structuralRarenessK` defaulting to 5 is fine — mirror how `maxCategoricalCardinality` is
threaded.

**Step 5 — emitter.** In `DpiaArtifactEmitter`, render `structuralFindings` in all three formats
under a "Structural re-identification risk" heading, copying the table/loop structure already used
for `survivalFindings`. No new escaping helpers are needed.

**Step 6 — tests (definition of done).**
- Extend `DpiaArtifactEmitterTest.sampleReport()` to include a `StructuralUniquenessFinding` and
  assert each format renders it (copy the survival/lint assertions).
- Add one Testcontainers E2E test: a parent table where exactly one row has a distinctive child
  count (e.g. 1 parent with 5 children, 20 parents with 1 child each), assert the finding reports
  `maxChildCount == 5` and `uniqueFingerprintCount == 1`. Use `TestPostgres.IMAGE` and the
  `assumeDockerAvailable(...)` guard like the other E2E tests.
- `./gradlew build` green (compile + tests + the strict javadoc gate: every new public record/field
  needs a full `@param` set).

Scope guard: do **not** add an `ERROR`/abort mode, do not perturb data, and do not join across more
than one edge (that multi-edge case is a §11 follow-on). This feature only measures and reports.

## 11. Open questions and follow-ons

- **Multi-edge fan-out vectors.** Do we ever combine edges into a joint fingerprint, or only report
  per-edge? Per-edge is cheaper and more explainable; the joint vector is more faithful to real
  singling-out but harder to threshold. Recommendation: ship per-edge first.
- **Attribute + structure combinations.** A kept `distinguishing: false` value *plus* a rare fan-out
  is a stronger fingerprint than either alone. Out of scope for v1 of this feature, but worth noting
  as the natural next increment and cross-linking to the passthrough audit.
- **Self-referential / cyclic tables** (handled by the two-pass loader, ADR 0006) need the edge
  enumeration to tolerate a table being its own parent; the grouped-count query is unaffected but
  the edge-discovery loop must not assume parent ≠ child.
