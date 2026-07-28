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
        try {
            java.util.List<PipelineStage.StageResult> stageResults = new java.util.ArrayList<>();
            // In Phase 2+, we will execute the stages here.
            // For now, it's just a walking skeleton wrapper.
            for (PipelineStage stage : stages) {
                stageResults.add(stage.process(context));
            }

            return new PipelineResult(
                true,
                0L,
                0,
                Duration.ZERO,
                new io.github.dconneely.incognito.api.AnonymisationReport(Collections.emptyList(), stageResults)
            );
        } finally {
            if (saltToClear != null) {
                Arrays.fill(saltToClear, (byte) 0);
            }
        }
    }
}
