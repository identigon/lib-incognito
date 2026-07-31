package io.github.dconneely.incognito.api;

import java.util.List;

/**
 * Typed accountability record produced by a pipeline run — the evidence a controller needs for a
 * DPIA / GDPR Art. 30 record. Structured, not free text, because the residual-risk evidence is the
 * product's differentiator.
 *
 * @param tables per-table outcomes, in processing order
 * @param stageResults the result of each pipeline stage, in execution order
 */
public record AnonymisationReport(
    List<TableReport> tables,
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
}
