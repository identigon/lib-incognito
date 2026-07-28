package io.github.dconneely.incognito.policy;

import io.github.dconneely.incognito.api.IncognitoException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Parses declarative YAML configuration files into AnonymisationPolicy instances.
 */
public class YamlPolicyParser {

    /**
     * Parses an anonymisation policy from a YAML file path.
     *
     * @param yamlPath Path to the incognito-policy.yaml file.
     * @return Parsed AnonymisationPolicy object.
     * @throws IncognitoException.ConfigException if parsing fails or YAML is invalid.
     */
    public AnonymisationPolicy parse(Path yamlPath) throws IncognitoException.ConfigException {
        throw new UnsupportedOperationException("To be implemented in Phase 2");
    }

    /**
     * Parses an anonymisation policy from an InputStream.
     *
     * @param inputStream InputStream containing YAML content.
     * @return Parsed AnonymisationPolicy object.
     * @throws IncognitoException.ConfigException if parsing fails or YAML is invalid.
     */
    public AnonymisationPolicy parse(InputStream inputStream) throws IncognitoException.ConfigException {
        throw new UnsupportedOperationException("To be implemented in Phase 2");
    }
}
