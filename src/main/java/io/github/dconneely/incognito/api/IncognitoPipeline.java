package io.github.dconneely.incognito.api;

import io.github.dconneely.incognito.policy.AnonymisationPolicy;
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

    interface Builder {
        Builder source(DataSource source);
        Builder target(DataSource target);
        // No alterEgo(...) method: Incognito owns the salt and builds AlterEgo internally
        // (in-memory MappingStore, rawMappingKeys=false) to guarantee the salt lifecycle (SPEC §5.1).
        Builder locale(java.util.Locale locale); // optional; direct-ID generator locale (default Locale.UK)
        Builder keyStore(KeyTranslationStore store); // v1.0: in-memory only
        Builder ephemeralSalt();                     // default: random secret salt, destroyed on completion
        Builder persistentSalt(byte[] salt);         // opt-in linkable mode; forfeits irreversibility
        Builder reproducible(byte[] salt, long seed); // fixed salt + RNG seed for deterministic tests
        Builder policy(AnonymisationPolicy policy);  // roles, strategies, auto-infer, cardinality gate
        Builder stage(PipelineStage stage);
        IncognitoPipeline build();
    }
}
