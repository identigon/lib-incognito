package io.github.dconneely.incognito.api;

import io.github.dconneely.incognito.policy.AnonymisationPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;

/**
 * Package-private builder for {@link IncognitoPipeline}. Collects configuration and validates it;
 * the returned pipeline's stage execution is not yet implemented (Phases 2–6). Kept in the api
 * package so {@link IncognitoPipeline#builder()} does not create an api -&gt; core cycle.
 */
final class IncognitoPipelineBuilder implements IncognitoPipeline.Builder {

    private enum SaltMode { EPHEMERAL, PERSISTENT, REPRODUCIBLE }

    private DataSource source;
    private DataSource target;
    private Locale locale = Locale.UK;
    private KeyTranslationStore keyStore;
    private AnonymisationPolicy policy;
    private final List<PipelineStage> stages = new ArrayList<>();

    private SaltMode saltMode;
    private byte[] salt;
    private long seed;

    @Override
    public IncognitoPipeline.Builder source(DataSource source) {
        this.source = source;
        return this;
    }

    @Override
    public IncognitoPipeline.Builder target(DataSource target) {
        this.target = target;
        return this;
    }

    @Override
    public IncognitoPipeline.Builder locale(Locale locale) {
        this.locale = locale;
        return this;
    }

    @Override
    public IncognitoPipeline.Builder keyStore(KeyTranslationStore store) {
        this.keyStore = store;
        return this;
    }

    @Override
    public IncognitoPipeline.Builder ephemeralSalt() {
        setSaltMode(SaltMode.EPHEMERAL);
        return this;
    }

    @Override
    public IncognitoPipeline.Builder persistentSalt(byte[] salt) {
        setSaltMode(SaltMode.PERSISTENT);
        this.salt = salt == null ? null : salt.clone();
        return this;
    }

    @Override
    public IncognitoPipeline.Builder reproducible(byte[] salt, long seed) {
        setSaltMode(SaltMode.REPRODUCIBLE);
        this.salt = salt == null ? null : salt.clone();
        this.seed = seed;
        return this;
    }

    @Override
    public IncognitoPipeline.Builder policy(AnonymisationPolicy policy) {
        this.policy = policy;
        return this;
    }

    @Override
    public IncognitoPipeline.Builder stage(PipelineStage stage) {
        this.stages.add(stage);
        return this;
    }

    @Override
    public IncognitoPipeline build() {
        if (source == null || target == null) {
            throw new IncognitoException.ConfigException("source and target DataSources are required");
        }
        if (policy == null) {
            throw new IncognitoException.ConfigException("an AnonymisationPolicy is required");
        }
        if (saltMode == null) {
            saltMode = SaltMode.EPHEMERAL; // default: fresh secret salt per run
        }
        return new StubPipeline();
    }

    // The three salt modes are mutually exclusive (SPEC §5.1).
    private void setSaltMode(SaltMode mode) {
        if (saltMode != null && saltMode != mode) {
            throw new IncognitoException.ConfigException(
                "salt modes are mutually exclusive: " + saltMode + " already set, cannot also set " + mode);
        }
        saltMode = mode;
    }

    /** Placeholder pipeline: configuration is validated, but stage execution is pending. */
    private static final class StubPipeline implements IncognitoPipeline {
        @Override
        public PipelineResult execute() {
            throw new UnsupportedOperationException(
                "IncognitoPipeline.execute() is not yet implemented (Phases 2-6); "
                    + "the builder API is in place, the stages are pending.");
        }
    }
}
