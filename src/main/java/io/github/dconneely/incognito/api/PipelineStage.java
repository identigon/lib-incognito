package io.github.dconneely.incognito.api;

/**
 * A modular stage in the anonymisation pipeline.
 */
@FunctionalInterface
public interface PipelineStage {
    /**
     * Processes a single pipeline stage.
     *
     * @param context Shared pipeline execution context.
     * @return Execution result of the stage.
     * @throws IncognitoException if processing fails.
     */
    StageResult process(PipelineContext context) throws IncognitoException;

    /**
     * The outcome of one pipeline stage.
     *
     * @param stageName the stage's name
     * @param success whether the stage succeeded
     * @param processedCount a stage-specific count (rows loaded, tables discovered, warnings, …)
     * @param message a human-readable summary
     */
    record StageResult(String stageName, boolean success, long processedCount, String message) {}
}
