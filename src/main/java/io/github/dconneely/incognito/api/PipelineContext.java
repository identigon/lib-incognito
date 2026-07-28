package io.github.dconneely.incognito.api;

import io.github.dconneely.incognito.policy.AnonymisationPolicy;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Shared runtime context accessible across all pipeline stages.
 */
public interface PipelineContext {
    DataSource source();
    DataSource target();
    KeyTranslationStore keyStore();
    AttributeCascadeStore cascadeStore();
    io.github.dconneely.alterego.AlterEgo alterEgo();
    AnonymisationPolicy policy();
    Map<String, Object> attributes();
}
