package io.github.dconneely.incognito.api;

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
    Map<String, Object> attributes();
}
