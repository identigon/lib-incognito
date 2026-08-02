package org.identigon.incognito.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import org.identigon.alterego.AlterEgo;
import org.identigon.incognito.api.PipelineContext;
import org.junit.jupiter.api.Test;

/**
 * Locks the salt-lifecycle invariant (SPEC §5.1/§8.1, hard invariant 3): on completion the pipeline
 * destroys the secret salt — both Incognito's own copy (zeroed) and the {@code AlterEgo} instance's
 * internal clone (via {@code close()}, after which the instance is unusable). Runs the destruction in
 * {@code DefaultIncognitoPipeline}'s {@code finally}, so it holds whether the run succeeds or fails.
 * No Docker required.
 */
class SaltLifecycleTest {

    @Test
    void saltIsZeroedAndAlterEgoClosedOnCompletion() {
        byte[] salt = "0123456789abcdef0123456789abcdef".getBytes();
        byte[] saltToClear = salt.clone();
        AlterEgo alterEgo = AlterEgo.builder().salt(salt).build();

        PipelineContext ctx = new DefaultPipelineContext(
            null, null, null, null, alterEgo, null, new HashMap<>());
        // No stages: execute() has no work but must still run its salt-destruction finally.
        DefaultIncognitoPipeline pipeline = new DefaultIncognitoPipeline(ctx, List.of(), saltToClear);

        try {
            pipeline.execute();
        } catch (RuntimeException ignored) {
            // Destruction is in a finally block, so it happens regardless of the run's outcome.
        }

        assertArrayEquals(new byte[saltToClear.length], saltToClear,
            "Incognito's salt copy must be zeroed on completion");
        assertThrows(IllegalStateException.class, () -> alterEgo.pattern("D"),
            "the AlterEgo instance's salt clone must be destroyed (instance unusable after close)");
    }
}
