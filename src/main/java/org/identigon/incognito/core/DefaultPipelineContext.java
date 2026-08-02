package org.identigon.incognito.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.identigon.alterego.AlterEgo;
import org.identigon.incognito.api.AttributeCascadeStore;
import org.identigon.incognito.api.KeyTranslationStore;
import org.identigon.incognito.api.PipelineContext;
import org.identigon.incognito.policy.AnonymisationPolicy;

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
