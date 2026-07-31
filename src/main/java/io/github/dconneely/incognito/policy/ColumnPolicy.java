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
 *
 * @param columnName the column name
 * @param role the column's {@link ColumnRole}
 * @param surrogateStrategy how a {@code PRIMARY_KEY} is surrogated
 * @param directIdStrategy how a {@code DIRECT_ID}/{@code UNIQUE_CANDIDATE_KEY} is fabricated
 * @param quasiIdStrategy how a {@code QUASI_ID} is jittered/synthesised
 * @param redactionStrategy how a distinguishing {@code SENSITIVE} column is redacted
 * @param distinguishing the {@code SENSITIVE} keep-vs-fabricate declaration (§4.1); {@code null} if not declared
 * @param jitterDays the ±window in days for {@link QuasiIdStrategy#JITTER_DAYS}
 * @param coherenceGroup the shared temporal-jitter group name (SPEC §4.2)
 * @param referencedTable the parent table for a {@code FOREIGN_KEY}
 * @param referencedColumn the parent column for a {@code FOREIGN_KEY}
 * @param derivedFromTable the ancestor table for an {@code INHERITED_ATTRIBUTE}
 * @param derivedFromColumn the ancestor column for an {@code INHERITED_ATTRIBUTE}
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

    /**
     * Starts a builder for a column policy.
     *
     * @param columnName the column name
     * @return a new builder
     */
    public static Builder builder(String columnName) {
        return new Builder(columnName);
    }

    /** Fluent builder for a {@link ColumnPolicy}. */
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

        /**
         * @param columnName the column this policy is for
         */
        public Builder(String columnName) {
            this.columnName = columnName;
        }

        /**
         * @param role the column's role
         * @return this builder
         */
        public Builder role(ColumnRole role) {
            this.role = role;
            return this;
        }

        /**
         * @param strategy the surrogate strategy for a {@code PRIMARY_KEY}
         * @return this builder
         */
        public Builder surrogateStrategy(SurrogateStrategy strategy) {
            this.surrogateStrategy = strategy;
            return this;
        }

        /**
         * @param strategy the fabrication strategy for a {@code DIRECT_ID}/{@code UNIQUE_CANDIDATE_KEY}
         * @return this builder
         */
        public Builder directIdStrategy(DirectIdStrategy strategy) {
            this.directIdStrategy = strategy;
            return this;
        }

        /**
         * @param strategy the jitter/synthesise strategy for a {@code QUASI_ID}
         * @return this builder
         */
        public Builder quasiIdStrategy(QuasiIdStrategy strategy) {
            this.quasiIdStrategy = strategy;
            return this;
        }

        /**
         * @param strategy the redaction strategy for a distinguishing {@code SENSITIVE} column
         * @return this builder
         */
        public Builder redactionStrategy(RedactionStrategy strategy) {
            this.redactionStrategy = strategy;
            return this;
        }

        /**
         * @param distinguishing the {@code SENSITIVE} keep-vs-fabricate declaration (§4.1)
         * @return this builder
         */
        public Builder distinguishing(Boolean distinguishing) {
            this.distinguishing = distinguishing;
            return this;
        }

        /**
         * Sets the ±window in days for {@link QuasiIdStrategy#JITTER_DAYS}.
         *
         * @param jitterDays the half-range of the day shift
         * @return this builder
         */
        public Builder jitterDays(int jitterDays) {
            this.jitterDays = jitterDays;
            return this;
        }

        /**
         * Names a set of temporal columns (within a row/entity) that share one jitter delta (SPEC §4.2).
         *
         * @param coherenceGroup the coherence-group name
         * @return this builder
         */
        public Builder coherenceGroup(String coherenceGroup) {
            this.coherenceGroup = coherenceGroup;
            return this;
        }

        /**
         * Declares the parent this {@code FOREIGN_KEY} references.
         *
         * @param table the referenced table
         * @param column the referenced column
         * @return this builder
         */
        public Builder references(String table, String column) {
            this.referencedTable = table;
            this.referencedColumn = column;
            return this;
        }

        /**
         * Declares the ancestor an {@code INHERITED_ATTRIBUTE} is derived from.
         *
         * @param table the ancestor table
         * @param column the ancestor column
         * @return this builder
         */
        public Builder derivedFrom(String table, String column) {
            this.derivedFromTable = table;
            this.derivedFromColumn = column;
            return this;
        }

        /** @return the built {@link ColumnPolicy} */
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
