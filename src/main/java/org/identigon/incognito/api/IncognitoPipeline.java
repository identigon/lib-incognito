package org.identigon.incognito.api;

import org.identigon.incognito.policy.AnonymisationPolicy;
import javax.sql.DataSource;

/**
 * Entry point for executing a relational database anonymisation pipeline.
 */
public interface IncognitoPipeline {

    /**
     * Starts building a pipeline. {@code IncognitoPipelineBuilder} is package-private in this
     * (api) package, so this factory does not create an api -&gt; core dependency cycle.
     *
     * @return a new {@link Builder}
     */
    static Builder builder() {
        return new IncognitoPipelineBuilder();
    }

    /**
     * Executes the anonymisation pipeline.
     *
     * @return Result metadata containing tables transformed, row counts, and execution metrics.
     * @throws IncognitoException if configuration, schema discovery, transformation, or loading fails.
     */
    PipelineResult execute() throws IncognitoException;

    /**
     * Fluent builder for an {@link IncognitoPipeline}. Incognito owns the salt and builds the backing
     * {@code AlterEgo} internally (in-memory mapping store), so there is deliberately no way to inject
     * one — that guarantees the salt lifecycle (SPEC §5.1).
     */
    interface Builder {
        /**
         * Sets the production source database to read from.
         *
         * @param source the source data source
         * @return this builder
         */
        Builder source(DataSource source);

        /**
         * Sets the target database the fabricated clone is written to.
         *
         * @param target the target data source
         * @return this builder
         */
        Builder target(DataSource target);

        /**
         * Sets the locale for direct-ID generators (default {@code Locale.UK}).
         *
         * @param locale the generator locale
         * @return this builder
         */
        Builder locale(java.util.Locale locale);

        /**
         * Supplies a custom key-translation store (v1.0 ships an in-memory store).
         *
         * @param store the key-translation store
         * @return this builder
         */
        Builder keyStore(KeyTranslationStore store);

        /**
         * Uses a random secret salt, destroyed on completion (the default, irreversible mode).
         *
         * @return this builder
         */
        Builder ephemeralSalt();

        /**
         * Uses a caller-supplied fixed salt — opt-in linkable mode that forfeits irreversibility.
         *
         * @param salt the persistent salt bytes
         * @return this builder
         */
        Builder persistentSalt(byte[] salt);

        /**
         * Uses a fixed salt and RNG seed for deterministic, reproducible output (for tests).
         *
         * @param salt the fixed salt bytes
         * @param seed the fixed RNG seed
         * @return this builder
         */
        Builder reproducible(byte[] salt, long seed);

        /**
         * Sets the anonymisation policy (column roles, strategies, and the distinguishing flag).
         *
         * @param policy the policy
         * @return this builder
         */
        Builder policy(AnonymisationPolicy policy);

        /**
         * Appends a pipeline stage; stages run in the order added.
         *
         * @param stage the stage to append
         * @return this builder
         */
        Builder stage(PipelineStage stage);

        /**
         * Builds the configured pipeline.
         *
         * @return the assembled pipeline
         */
        IncognitoPipeline build();
    }
}
