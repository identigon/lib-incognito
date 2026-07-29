package io.github.dconneely.incognito.policy;

import io.github.dconneely.incognito.api.ColumnRole;
import io.github.dconneely.incognito.api.DirectIdStrategy;
import io.github.dconneely.incognito.api.QuasiIdStrategy;
import io.github.dconneely.incognito.api.RedactionStrategy;
import io.github.dconneely.incognito.api.SurrogateStrategy;
import java.util.Objects;

/**
 * Anonymisation policy definition for a single database column (SPEC §4.1). The applicable
 * strategy depends on the role: {@code surrogateStrategy} for {@code PRIMARY_KEY};
 * {@code directIdStrategy} for {@code DIRECT_ID}/{@code UNIQUE_CANDIDATE_KEY}; {@code quasiIdStrategy}
 * (with {@code jitterDays}/{@code coherenceGroup} for temporal jitter) for {@code QUASI_ID};
 * {@code referenced*} for {@code FOREIGN_KEY}; {@code derivedFrom*} for {@code INHERITED_ATTRIBUTE}.
 */
public record ColumnPolicy(
    String columnName,
    ColumnRole role,
    SurrogateStrategy surrogateStrategy,
    DirectIdStrategy directIdStrategy,
    QuasiIdStrategy quasiIdStrategy,
    RedactionStrategy redactionStrategy,
    Boolean distinguishing,
    int jitterDays,
    String coherenceGroup,
    String referencedTable,
    String referencedColumn,
    String derivedFromTable,
    String derivedFromColumn
) {
    public ColumnPolicy {
        Objects.requireNonNull(columnName, "columnName cannot be null");
        Objects.requireNonNull(role, "role cannot be null");
    }

    public static Builder builder(String columnName) {
        return new Builder(columnName);
    }

    public static class Builder {
        private final String columnName;
        private ColumnRole role = ColumnRole.PAYLOAD;
        private SurrogateStrategy surrogateStrategy = SurrogateStrategy.SEQUENTIAL_LONG;
        private DirectIdStrategy directIdStrategy;
        private QuasiIdStrategy quasiIdStrategy;
        private RedactionStrategy redactionStrategy;
        private Boolean distinguishing;
        private int jitterDays;
        private String coherenceGroup;
        private String referencedTable;
        private String referencedColumn;
        private String derivedFromTable;
        private String derivedFromColumn;

        public Builder(String columnName) {
            this.columnName = columnName;
        }

        public Builder role(ColumnRole role) {
            this.role = role;
            return this;
        }

        public Builder surrogateStrategy(SurrogateStrategy strategy) {
            this.surrogateStrategy = strategy;
            return this;
        }

        public Builder directIdStrategy(DirectIdStrategy strategy) {
            this.directIdStrategy = strategy;
            return this;
        }

        public Builder quasiIdStrategy(QuasiIdStrategy strategy) {
            this.quasiIdStrategy = strategy;
            return this;
        }

        public Builder redactionStrategy(RedactionStrategy strategy) {
            this.redactionStrategy = strategy;
            return this;
        }

        public Builder distinguishing(Boolean distinguishing) {
            this.distinguishing = distinguishing;
            return this;
        }

        /** ±window in days for {@link QuasiIdStrategy#JITTER_DAYS}. */
        public Builder jitterDays(int jitterDays) {
            this.jitterDays = jitterDays;
            return this;
        }

        /** Names a set of temporal columns (within a row/entity) that share one jitter delta (SPEC §4.2). */
        public Builder coherenceGroup(String coherenceGroup) {
            this.coherenceGroup = coherenceGroup;
            return this;
        }

        public Builder references(String table, String column) {
            this.referencedTable = table;
            this.referencedColumn = column;
            return this;
        }

        public Builder derivedFrom(String table, String column) {
            this.derivedFromTable = table;
            this.derivedFromColumn = column;
            return this;
        }

        public ColumnPolicy build() {
            return new ColumnPolicy(
                columnName, role, surrogateStrategy, directIdStrategy, quasiIdStrategy, redactionStrategy,
                distinguishing, jitterDays, coherenceGroup,
                referencedTable, referencedColumn,
                derivedFromTable, derivedFromColumn
            );
        }
    }
}
