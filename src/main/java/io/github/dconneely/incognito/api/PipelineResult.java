package io.github.dconneely.incognito.api;

import java.time.Duration;

/**
 * Summary metadata returned upon pipeline execution. The {@link AnonymisationReport} carries the
 * typed accountability record (per-column actions, fictionality, passthrough flags) for DPIA use.
 *
 * @param success whether every stage succeeded
 * @param totalRowsLoaded rows loaded to target (== source rows in v1.0; no outlier dropping)
 * @param tablesProcessed number of tables discovered and processed
 * @param duration wall-clock time for the whole run
 * @param report the typed accountability record for DPIA use
 */
public record PipelineResult(
    boolean success,
    long totalRowsLoaded,
    int tablesProcessed,
    Duration duration,
    AnonymisationReport report
) {}
