package io.github.dconneely.incognito.policy;

import io.github.dconneely.incognito.api.ColumnRole;
import io.github.dconneely.incognito.api.DirectIdStrategy;
import io.github.dconneely.incognito.api.QuasiIdStrategy;
import io.github.dconneely.incognito.api.RedactionStrategy;
import io.github.dconneely.incognito.api.SurrogateStrategy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Anonymisation policy definition for a single database table.
 */
public record TablePolicy(
    String tableName,
    Map<String, ColumnPolicy> columns
) {
    public TablePolicy {
        Objects.requireNonNull(tableName, "tableName cannot be null");
        columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
    }

    public Optional<ColumnPolicy> column(String columnName) {
        return Optional.ofNullable(columns.get(columnName));
    }

    public static Builder builder(String tableName) {
        return new Builder(tableName);
    }

    public static class Builder {
        private final String tableName;
        private final Map<String, ColumnPolicy> columns = new LinkedHashMap<>();

        public Builder(String tableName) {
            this.tableName = tableName;
        }

        public Builder column(ColumnPolicy columnPolicy) {
            this.columns.put(columnPolicy.columnName(), columnPolicy);
            return this;
        }

        public Builder column(String columnName, ColumnPolicy.Builder builder) {
            return column(builder.build());
        }

        /** Declares a column by role (QUASI_ID with SYNTHESISE default / SENSITIVE / PAYLOAD / FOREIGN_KEY / ...). */
        public Builder column(String columnName, ColumnRole role) {
            return column(ColumnPolicy.builder(columnName).role(role).build());
        }

        /** Declares a PRIMARY_KEY column with its surrogate strategy. */
        public Builder column(String columnName, ColumnRole role, SurrogateStrategy strategy) {
            return column(ColumnPolicy.builder(columnName).role(role).surrogateStrategy(strategy).build());
        }

        /** Declares a DIRECT_ID / UNIQUE_CANDIDATE_KEY column with its lib-alterego generator. */
        public Builder column(String columnName, ColumnRole role, DirectIdStrategy strategy) {
            return column(ColumnPolicy.builder(columnName).role(role).directIdStrategy(strategy).build());
        }

        /** Declares a QUASI_ID column with its fabrication strategy (synthesise / jitter). */
        public Builder column(String columnName, ColumnRole role, QuasiIdStrategy strategy) {
            return column(ColumnPolicy.builder(columnName).role(role).quasiIdStrategy(strategy).build());
        }

        /** Declares a {@code distinguishing: true} SENSITIVE column redacted via clear / mask / constant (SPEC §2.2/§4.1). */
        public Builder column(String columnName, ColumnRole role, RedactionStrategy strategy) {
            return column(ColumnPolicy.builder(columnName).role(role).distinguishing(true).redactionStrategy(strategy).build());
        }

        public TablePolicy build() {
            return new TablePolicy(tableName, columns);
        }
    }
}
