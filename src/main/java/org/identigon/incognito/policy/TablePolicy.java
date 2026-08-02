package org.identigon.incognito.policy;

import org.identigon.incognito.api.ColumnRole;
import org.identigon.incognito.api.DirectIdStrategy;
import org.identigon.incognito.api.QuasiIdStrategy;
import org.identigon.incognito.api.RedactionStrategy;
import org.identigon.incognito.api.SurrogateStrategy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Anonymisation policy definition for a single database table.
 *
 * @param tableName the table name
 * @param columns the per-column policies, keyed by column name
 */
public record TablePolicy(
    String tableName,
    Map<String, ColumnPolicy> columns
) {
    /**
     * Validates the table name and takes an unmodifiable, order-preserving copy of the columns.
     *
     * @throws NullPointerException if {@code tableName} is null
     */
    public TablePolicy {
        Objects.requireNonNull(tableName, "tableName cannot be null");
        columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
    }

    /**
     * Looks up the policy for a column.
     *
     * @param columnName the column name
     * @return the column's policy, or empty if the column is not declared
     */
    public Optional<ColumnPolicy> column(String columnName) {
        return Optional.ofNullable(columns.get(columnName));
    }

    /**
     * Starts a builder for a table policy.
     *
     * @param tableName the table name
     * @return a new builder
     */
    public static Builder builder(String tableName) {
        return new Builder(tableName);
    }

    /** Fluent builder for a {@link TablePolicy}. */
    public static class Builder {
        private final String tableName;
        private final Map<String, ColumnPolicy> columns = new LinkedHashMap<>();

        /**
         * Creates a builder for the given table.
         *
         * @param tableName the table this policy is for
         */
        public Builder(String tableName) {
            this.tableName = tableName;
        }

        /**
         * Adds a fully-built column policy.
         *
         * @param columnPolicy the column policy
         * @return this builder
         */
        public Builder column(ColumnPolicy columnPolicy) {
            this.columns.put(columnPolicy.columnName(), columnPolicy);
            return this;
        }

        /**
         * Adds a column from a column-policy builder.
         *
         * @param columnName the column name (for readability; the built policy's own name is used as the key)
         * @param builder the column-policy builder
         * @return this builder
         */
        public Builder column(String columnName, ColumnPolicy.Builder builder) {
            return column(builder.build());
        }

        /**
         * Declares a column by role (e.g. {@code SENSITIVE} / {@code PAYLOAD}; a {@code QUASI_ID}
         * defaults to SYNTHESISE).
         *
         * @param columnName the column name
         * @param role the column role
         * @return this builder
         */
        public Builder column(String columnName, ColumnRole role) {
            return column(ColumnPolicy.builder(columnName).role(role).build());
        }

        /**
         * Declares a {@code PRIMARY_KEY} column with its surrogate strategy.
         *
         * @param columnName the column name
         * @param role the column role (expected {@code PRIMARY_KEY})
         * @param strategy the surrogate strategy
         * @return this builder
         */
        public Builder column(String columnName, ColumnRole role, SurrogateStrategy strategy) {
            return column(ColumnPolicy.builder(columnName).role(role).surrogateStrategy(strategy).build());
        }

        /**
         * Declares a {@code DIRECT_ID} / {@code UNIQUE_CANDIDATE_KEY} column with its lib-alterego generator.
         *
         * @param columnName the column name
         * @param role the column role
         * @param strategy the lib-alterego generator strategy
         * @return this builder
         */
        public Builder column(String columnName, ColumnRole role, DirectIdStrategy strategy) {
            return column(ColumnPolicy.builder(columnName).role(role).directIdStrategy(strategy).build());
        }

        /**
         * Declares a {@code QUASI_ID} column with its fabrication strategy (synthesise / jitter).
         *
         * @param columnName the column name
         * @param role the column role (expected {@code QUASI_ID})
         * @param strategy the quasi-id strategy
         * @return this builder
         */
        public Builder column(String columnName, ColumnRole role, QuasiIdStrategy strategy) {
            return column(ColumnPolicy.builder(columnName).role(role).quasiIdStrategy(strategy).build());
        }

        /**
         * Declares a {@code distinguishing: true} {@code SENSITIVE} column redacted via
         * clear / mask / constant (SPEC §2.2/§4.1).
         *
         * @param columnName the column name
         * @param role the column role (expected {@code SENSITIVE})
         * @param strategy the redaction strategy
         * @return this builder
         */
        public Builder column(String columnName, ColumnRole role, RedactionStrategy strategy) {
            return column(ColumnPolicy.builder(columnName).role(role).distinguishing(true).redactionStrategy(strategy).build());
        }

        /**
         * Builds the table policy.
         *
         * @return the built {@link TablePolicy}
         */
        public TablePolicy build() {
            return new TablePolicy(tableName, columns);
        }
    }
}
