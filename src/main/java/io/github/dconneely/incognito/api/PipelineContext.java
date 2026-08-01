package io.github.dconneely.incognito.api;

import io.github.dconneely.incognito.policy.AnonymisationPolicy;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Shared runtime context accessible across all pipeline stages.
 */
public interface PipelineContext {
    /**
     * Returns the source database to read production data from.
     *
     * @return the source data source
     */
    DataSource source();

    /**
     * Returns the target database the fabricated clone is written to.
     *
     * @return the target data source
     */
    DataSource target();

    /**
     * Returns the store mapping source keys to their fabricated surrogates.
     *
     * @return the key-translation store
     */
    KeyTranslationStore keyStore();

    /**
     * Returns the store of published attributes and coherence-group jitter deltas.
     *
     * @return the attribute-cascade store
     */
    AttributeCascadeStore cascadeStore();

    /**
     * Returns the {@code lib-alterego} instance backing all value fabrication.
     *
     * @return the AlterEgo instance
     */
    io.github.dconneely.alterego.AlterEgo alterEgo();

    /**
     * Returns the anonymisation policy governing the run.
     *
     * @return the policy
     */
    AnonymisationPolicy policy();

    /**
     * Returns a mutable map for stages to share arbitrary run-scoped state.
     *
     * @return the shared attribute map
     */
    Map<String, Object> attributes();
}
