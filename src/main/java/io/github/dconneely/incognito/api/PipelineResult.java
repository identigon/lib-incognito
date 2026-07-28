package io.github.dconneely.incognito.api;

import java.time.Duration;

/**
 * Summary metadata returned upon pipeline execution. The {@link AnonymisationReport} carries the
 * typed accountability record (per-column actions, fictionality, passthrough flags) for DPIA use.
 */
public record PipelineResult(
    boolean success,
    long totalRowsLoaded,   // rows loaded to target (== source rows in v1.0; no outlier dropping)
    int tablesProcessed,
    Duration duration,
    AnonymisationReport report
) {}
