package io.github.dconneely.incognito.core;

import io.github.dconneely.alterego.AlterEgo;
import io.github.dconneely.incognito.api.IncognitoException;
import io.github.dconneely.incognito.api.IncognitoPipeline;
import io.github.dconneely.incognito.api.PipelineContext;
import io.github.dconneely.incognito.api.PipelineResult;
import io.github.dconneely.incognito.api.PipelineStage;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class DefaultIncognitoPipeline implements IncognitoPipeline {

    private final PipelineContext context;
    private final List<PipelineStage> stages;
    private final byte[] saltToClear;

    public DefaultIncognitoPipeline(PipelineContext context, List<PipelineStage> stages, byte[] saltToClear) {
        this.context = context;
        this.stages = List.copyOf(stages);
        this.saltToClear = saltToClear;
    }

    @Override
    public PipelineResult execute() throws IncognitoException {
        long startNanos = System.nanoTime();
        try {
            java.util.List<PipelineStage.StageResult> stageResults = new java.util.ArrayList<>();
            for (PipelineStage stage : stages) {
                stageResults.add(stage.process(context));
            }

            long rowsLoaded = stageResults.stream()
                .filter(r -> "TableTransformLoadStage".equals(r.stageName()))
                .mapToLong(PipelineStage.StageResult::processedCount).sum();
            int tablesProcessed = stageResults.stream()
                .filter(r -> "SchemaDiscoveryStage".equals(r.stageName()))
                .mapToInt(r -> (int) r.processedCount()).findFirst().orElse(0);

            return new PipelineResult(
                true,
                rowsLoaded,
                tablesProcessed,
                Duration.ofNanos(System.nanoTime() - startNanos),
                io.github.dconneely.incognito.core.AnonymisationReportBuilder.build(context, stageResults)
            );
        } catch (Exception e) {
            io.github.dconneely.incognito.core.IncognitoCleanUpHandler.compensate(context);
            if (e instanceof IncognitoException) {
                throw (IncognitoException) e;
            }
            throw new IncognitoException("Pipeline execution failed", e);
        } finally {
            if (saltToClear != null) {
                Arrays.fill(saltToClear, (byte) 0);
            }
        }
    }
}
