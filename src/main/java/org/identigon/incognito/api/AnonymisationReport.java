package org.identigon.incognito.api;

import java.util.List;

/**
 * Typed accountability record produced by a pipeline run — the evidence a controller needs for a
 * DPIA / GDPR Art. 30 record. Structured, not free text, because the residual-risk evidence is the
 * product's differentiator.
 *
 * @param saltMode how the run was keyed (SPEC §5.1/§5.2) — a top-level fact because it governs the
 *     strength of the whole run's anonymity claim (Recital 26), independent of any single column
 * @param tables per-table outcomes, in processing order
 * @param survivalFindings DIRECT_ID columns where real source values reappeared in the target
 *     (SPEC §4.3) — the quantified singling-out evidence (Art. 29 WP 05/2014) a reviewer needs
 * @param lintFindings {@code SENSITIVE distinguishing: false} columns whose real cardinality exceeds
 *     the categorical threshold (SPEC §4.1) — candidate misdeclarations kept opaque; populated only
 *     when the lint runs in {@link DistinguishingLint#WARN} mode (in {@code ERROR} mode the run
 *     aborts before any report is built)
 * @param structuralFindings per-FK-edge relational fingerprints (SPEC §2.4) — subjects singled out
 *     by their FK fan-out rather than by any field value; populated only when
 *     {@link StructuralUniquenessMode#REPORT} is enabled (off by default)
 * @param stageResults the result of each pipeline stage, in execution order
 */
public record AnonymisationReport(
    SaltMode saltMode,
    List<TableReport> tables,
    List<SurvivalFinding> survivalFindings,
    List<LintFinding> lintFindings,
    List<StructuralUniquenessFinding> structuralFindings,
    List<PipelineStage.StageResult> stageResults
) {
    /**
     * Per-table outcome: what each column was classified as and how it was transformed.
     *
     * @param table the table name
     * @param columns the per-column action taken
     * @param rowsProcessed how many rows were transformed and loaded
     * @param passthroughFlags kept columns of an opaque JDBC type, surfaced for the DPIA (SPEC §7.2)
     * @param inferSuggestions auto-inference suggestions surfaced for this table (never auto-applied)
     * @param fictionalityVerified whether the SPEC §4.3 fictionality checks passed for this table
     */
    public record TableReport(
        String table,
        List<ColumnAction> columns,
        long rowsProcessed,
        List<PassthroughFlag> passthroughFlags,
        List<InferSuggestion> inferSuggestions,
        boolean fictionalityVerified
    ) {}

    /**
     * The role a column was assigned and the transformation actually applied.
     *
     * @param column the column name
     * @param role the classified {@link ColumnRole}
     * @param transformation the transformation applied, e.g. {@code "ALTEREGO_EMAIL"} or {@code "KEEP"}
     */
    public record ColumnAction(String column, ColumnRole role, String transformation) {}

    /**
     * A kept column of a complex/opaque JDBC type that v1.0 could not transform (SPEC §7.2 audit).
     *
     * @param column the column name
     * @param jdbcType the JDBC type name that could not be transformed (e.g. {@code "OTHER"}, {@code "ARRAY"})
     * @param reason why it was surfaced rather than transformed
     */
    public record PassthroughFlag(String column, String jdbcType, String reason) {}

    /**
     * An auto-inference suggestion — surfaced only, never auto-applied (fail-closed, SPEC §7.2).
     *
     * @param column the column name
     * @param suggestedRole the role the inferrer suggests
     * @param matchedHeuristic the heuristic that produced the suggestion
     */
    public record InferSuggestion(String column, ColumnRole suggestedRole, String matchedHeuristic) {}

    /**
     * A DIRECT_ID column where real source values reappeared in the target (SPEC §4.3) — the
     * quantified evidence for the Article 29 WP 05/2014 "singling-out" test. Fabrication of a
     * low-entropy value can collide with a real value purely by chance, so a small overlap is a
     * coincidence rather than a leak: {@code hardFailure} distinguishes a genuine passthrough (which
     * resurfaces ~all values and fails the run) from that statistical noise.
     *
     * @param table the table name
     * @param column the column name
     * @param sampledDistinct how many distinct non-null source values were sampled and checked
     * @param survived how many of those sampled values reappeared in the target
     * @param hardFailure {@code true} if the survival ratio crossed the failure threshold (a probable
     *     un-fabricated passthrough that failed the run); {@code false} if it was below it (recorded
     *     as a likely-coincidental collision, the run still passed)
     */
    public record SurvivalFinding(
        String table, String column, long sampledDistinct, long survived, boolean hardFailure) {}

    /**
     * A {@code SENSITIVE distinguishing: false} column whose real distinct-value count exceeds the
     * categorical-cardinality threshold (SPEC §4.1) — a candidate misdeclaration: the column was
     * kept opaque as low-cardinality categorical data but looks high-cardinality enough to be
     * quasi-identifying. This is a safety net, never the privacy gate (ADR 0003) — the
     * {@code distinguishing} declaration alone decides keep-vs-fabricate.
     *
     * @param table the table name
     * @param column the column name
     * @param distinctValues the real distinct-value count observed on the source
     * @param threshold the {@code maxCategoricalCardinality} the count exceeded
     */
    public record LintFinding(String table, String column, long distinctValues, int threshold) {}

    /**
     * A relational fingerprint on a single FK edge (SPEC §2.4): row counts and the FK graph are
     * preserved 1:1, so a parent row with a rare or unique count of referencing child rows (e.g.
     * "the one customer with 300 orders") can be singled out from <em>structure</em> alone, even
     * though every field on it was fabricated. This is advisory evidence, never a privacy gate — it
     * changes no data and never fails the run (there is no {@code ERROR} mode).
     *
     * @param parentTable the singled-out subject's table
     * @param childTable the table whose FK to {@code parentTable} produced this fingerprint
     * @param childColumns the child's FK column(s), in key order — identifies <em>which</em> edge this
     *     is when a child references the same parent through more than one foreign key
     * @param distinctParents how many parent rows have at least one referencing child row
     * @param maxChildCount the largest number of referencing child rows observed for any parent
     * @param uniqueFingerprintCount how many parent rows are the sole holder of their child count —
     *     singled out by fan-out alone
     * @param rareFingerprintCount how many parent rows have a child count shared by fewer than
     *     {@code k} parents in total (so a unique fingerprint, group size 1, is also rare when k &gt; 1)
     * @param k the rareness threshold {@code rareFingerprintCount} was computed against
     */
    public record StructuralUniquenessFinding(
        String parentTable, String childTable, List<String> childColumns, long distinctParents,
        long maxChildCount, long uniqueFingerprintCount, long rareFingerprintCount, int k) {}
}
