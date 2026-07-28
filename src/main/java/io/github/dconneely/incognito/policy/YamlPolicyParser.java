package io.github.dconneely.incognito.policy;

import io.github.dconneely.incognito.api.ColumnRole;
import io.github.dconneely.incognito.api.DirectIdStrategy;
import io.github.dconneely.incognito.api.IncognitoException;
import io.github.dconneely.incognito.api.QuasiIdStrategy;
import io.github.dconneely.incognito.api.RedactionStrategy;
import io.github.dconneely.incognito.api.SurrogateStrategy;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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
        try (InputStream is = Files.newInputStream(yamlPath)) {
            return parse(is);
        } catch (Exception e) {
            throw new IncognitoException.ConfigException("Failed to read YAML from path: " + yamlPath, e);
        }
    }

    /**
     * Parses an anonymisation policy from an InputStream.
     *
     * @param inputStream InputStream containing YAML content.
     * @return Parsed AnonymisationPolicy object.
     * @throws IncognitoException.ConfigException if parsing fails or YAML is invalid.
     */
    @SuppressWarnings("unchecked")
    public AnonymisationPolicy parse(InputStream inputStream) throws IncognitoException.ConfigException {
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new org.yaml.snakeyaml.LoaderOptions()));
            Map<String, Object> root = yaml.load(inputStream);
            if (root == null) {
                return AnonymisationPolicy.builder().build(); // Empty config
            }

            AnonymisationPolicy.Builder builder = AnonymisationPolicy.builder();

            if (root.containsKey("autoInfer")) {
                builder.autoInfer((Boolean) root.get("autoInfer"));
            }
            if (root.containsKey("maxCategoricalCardinality")) {
                builder.maxCategoricalCardinality((Integer) root.get("maxCategoricalCardinality"));
            }

            if (root.containsKey("tables")) {
                Map<String, Map<String, Object>> tables = (Map<String, Map<String, Object>>) root.get("tables");
                for (Map.Entry<String, Map<String, Object>> tableEntry : tables.entrySet()) {
                    String tableName = tableEntry.getKey();
                    Map<String, Object> tableNode = tableEntry.getValue();

                    TablePolicy.Builder tableBuilder = TablePolicy.builder(tableName);
                    if (tableNode != null && tableNode.containsKey("columns")) {
                        Map<String, Map<String, Object>> columns = (Map<String, Map<String, Object>>) tableNode.get("columns");
                        for (Map.Entry<String, Map<String, Object>> colEntry : columns.entrySet()) {
                            String colName = colEntry.getKey();
                            Map<String, Object> colNode = colEntry.getValue();

                            ColumnPolicy.Builder colBuilder = ColumnPolicy.builder(colName);
                            if (colNode != null) {
                                if (colNode.containsKey("role")) {
                                    colBuilder.role(ColumnRole.valueOf(String.valueOf(colNode.get("role")).toUpperCase()));
                                }
                                if (colNode.containsKey("surrogateStrategy")) {
                                    colBuilder.surrogateStrategy(SurrogateStrategy.valueOf(String.valueOf(colNode.get("surrogateStrategy")).toUpperCase()));
                                }
                                if (colNode.containsKey("directIdStrategy")) {
                                    colBuilder.directIdStrategy(DirectIdStrategy.valueOf(String.valueOf(colNode.get("directIdStrategy")).toUpperCase()));
                                }
                                if (colNode.containsKey("quasiIdStrategy")) {
                                    colBuilder.quasiIdStrategy(QuasiIdStrategy.valueOf(String.valueOf(colNode.get("quasiIdStrategy")).toUpperCase()));
                                }
                                if (colNode.containsKey("redactionStrategy")) {
                                    colBuilder.redactionStrategy(RedactionStrategy.valueOf(String.valueOf(colNode.get("redactionStrategy")).toUpperCase()));
                                }
                                if (colNode.containsKey("jitterDays")) {
                                    colBuilder.jitterDays((Integer) colNode.get("jitterDays"));
                                }
                                if (colNode.containsKey("coherenceGroup")) {
                                    colBuilder.coherenceGroup(String.valueOf(colNode.get("coherenceGroup")));
                                }
                                if (colNode.containsKey("references")) {
                                    Map<String, String> ref = (Map<String, String>) colNode.get("references");
                                    colBuilder.references(ref.get("table"), ref.get("column"));
                                }
                                if (colNode.containsKey("derivedFrom")) {
                                    Map<String, String> df = (Map<String, String>) colNode.get("derivedFrom");
                                    colBuilder.derivedFrom(df.get("table"), df.get("column"));
                                }
                            }
                            tableBuilder.column(colBuilder.build());
                        }
                    }
                    builder.table(tableBuilder.build());
                }
            }

            return builder.build();
        } catch (Exception e) {
            throw new IncognitoException.ConfigException("Failed to parse YAML policy", e);
        }
    }
}
