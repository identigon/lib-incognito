package io.github.dconneely.incognito.core;

import io.github.dconneely.alterego.AlterEgo;
import io.github.dconneely.incognito.api.AttributeCascadeStore;
import io.github.dconneely.incognito.api.KeyTranslationStore;
import io.github.dconneely.incognito.api.PipelineContext;
import io.github.dconneely.incognito.policy.AnonymisationPolicy;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public record DefaultPipelineContext(
    DataSource source,
    DataSource target,
    KeyTranslationStore keyStore,
    AttributeCascadeStore cascadeStore,
    AlterEgo alterEgo,
    AnonymisationPolicy policy,
    Map<String, Object> attributes
) implements PipelineContext {
    public DefaultPipelineContext {
        attributes = new ConcurrentHashMap<>(attributes == null ? Map.of() : attributes);
    }
}
