package io.github.dconneely.incognito.core;

import io.github.dconneely.alterego.AlterEgo;
import io.github.dconneely.incognito.api.AttributeCascadeStore;
import io.github.dconneely.incognito.api.KeyTranslationStore;
import io.github.dconneely.incognito.api.PipelineContext;
import io.github.dconneely.incognito.policy.AnonymisationPolicy;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The default {@link PipelineContext} — an immutable record of the run's collaborators with a mutable
 * concurrent attribute map for stages to share state.
 *
 * @param source       the source data source
 * @param target       the target data source
 * @param keyStore     the key-translation store
 * @param cascadeStore the attribute-cascade store
 * @param alterEgo     the AlterEgo instance backing fabrication
 * @param policy       the anonymisation policy
 * @param attributes   the shared run-scoped attribute map
 */
public record DefaultPipelineContext(
    DataSource source,
    DataSource target,
    KeyTranslationStore keyStore,
    AttributeCascadeStore cascadeStore,
    AlterEgo alterEgo,
    AnonymisationPolicy policy,
    Map<String, Object> attributes
) implements PipelineContext {
    /** Copies {@code attributes} into a mutable concurrent map (empty if {@code null}). */
    public DefaultPipelineContext {
        attributes = new ConcurrentHashMap<>(attributes == null ? Map.of() : attributes);
    }
}
