package io.github.dconneely.incognito.api;

import java.util.List;

/**
 * Typed accountability record produced by a pipeline run — the evidence a controller needs for a
 * DPIA / GDPR Art. 30 record. Structured, not free text, because the residual-risk evidence is the
 * product's differentiator.
 */
public record AnonymisationReport(
    List<TableReport> tables,
    List<PipelineStage.StageResult> stageResults
) {
    /** Per-table outcome: what each column was classified as and how it was transformed. */
    public record TableReport(
        String table,
        List<ColumnAction> columns,
        long rowsProcessed,
        List<PassthroughFlag> passthroughFlags,
        List<InferSuggestion> inferSuggestions,
        boolean fictionalityVerified    // SPEC §4.3 checks passed for this table
    ) {}

    /** The role a column was assigned and the transformation actually applied (e.g. "ALTEREGO_EMAIL", "KEEP"). */
    public record ColumnAction(String column, ColumnRole role, String transformation) {}

    /** A kept column of a complex/opaque JDBC type that v1.0 could not transform (SPEC §7.2 audit). */
    public record PassthroughFlag(String column, String jdbcType, String reason) {}

    /** An auto-inference suggestion — surfaced only, never auto-applied (fail-closed, SPEC §7.2). */
    public record InferSuggestion(String column, ColumnRole suggestedRole, String matchedHeuristic) {}
}
