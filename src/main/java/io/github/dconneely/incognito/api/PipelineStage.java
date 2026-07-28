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

    record StageResult(String stageName, boolean success, long processedCount, String message) {}
}
