package org.identigon.incognito.policy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Top-level anonymisation policy: default parameters and table-level policies. The fabrication
 * model (SPEC §4) has no k-factor or l-diversity. Whether a {@code SENSITIVE} column is kept real
 * or fabricated is <em>declared</em> per column via a boolean {@code distinguishing} flag (§2.2/§4.1);
 * {@code maxCategoricalCardinality} is only the threshold for the default-on misdeclaration lint
 * ({@code distinguishingLint}: WARN | ERROR | OFF) that flags a {@code distinguishing: false} column
 * looking free-text — it is not the gate.
 *
 * @param autoInfer whether auto-inference may suggest roles (it never assigns them); default {@code false}
 * @param maxCategoricalCardinality the distinct-count threshold for the misdeclaration lint (§4.1)
 * @param distinguishingLint how the misdeclaration lint behaves (WARN / ERROR / OFF)
 * @param tables the per-table policies, keyed by table name
 */
public record AnonymisationPolicy(
    boolean autoInfer,
    int maxCategoricalCardinality,
    org.identigon.incognito.api.DistinguishingLint distinguishingLint,
    Map<String, TablePolicy> tables
) {
    /**
     * Takes an unmodifiable, order-preserving defensive copy of the table map.
     */
    public AnonymisationPolicy {
        tables = Collections.unmodifiableMap(new LinkedHashMap<>(tables));
    }

    /**
     * Looks up the policy for a table.
     *
     * @param tableName the table name
     * @return the table's policy, or empty if the table is not declared
     */
    public Optional<TablePolicy> table(String tableName) {
        return Optional.ofNullable(tables.get(tableName));
    }

    /**
     * Starts building a policy.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for an {@link AnonymisationPolicy}. */
    public static class Builder {
        /** Creates a builder with fail-closed defaults (auto-inference off, lint {@code WARN}). */
        public Builder() {}

        // Fail-closed by default (SPEC §7.2): auto-inference must be opted into, and it only
        // suggests roles — it never silently classifies.
        private boolean autoInfer = false;
        private int maxCategoricalCardinality = 64;
        private org.identigon.incognito.api.DistinguishingLint distinguishingLint = org.identigon.incognito.api.DistinguishingLint.WARN;
        private final Map<String, TablePolicy> tables = new LinkedHashMap<>();

        /**
         * Enables auto-inference of column roles (suggestions only, never applied). Default {@code false}.
         *
         * @param autoInfer whether to enable auto-inference
         * @return this builder
         */
        public Builder autoInfer(boolean autoInfer) {
            this.autoInfer = autoInfer;
            return this;
        }

        /**
         * Sets the VARCHAR/SENSITIVE categorical threshold for the misdeclaration lint (SPEC §4.1).
         *
         * @param maxCategoricalCardinality the distinct-count threshold; default 64
         * @return this builder
         */
        public Builder maxCategoricalCardinality(int maxCategoricalCardinality) {
            this.maxCategoricalCardinality = maxCategoricalCardinality;
            return this;
        }

        /**
         * Sets how the default-on misdeclaration lint behaves.
         *
         * @param distinguishingLint WARN (default), ERROR, or OFF
         * @return this builder
         */
        public Builder distinguishingLint(org.identigon.incognito.api.DistinguishingLint distinguishingLint) {
            this.distinguishingLint = distinguishingLint;
            return this;
        }

        /**
         * Adds a table policy.
         *
         * @param tablePolicy the table policy
         * @return this builder
         */
        public Builder table(TablePolicy tablePolicy) {
            this.tables.put(tablePolicy.tableName(), tablePolicy);
            return this;
        }

        /**
         * Adds a table policy configured via a callback.
         *
         * @param tableName the table name
         * @param configurer configures the table's {@link TablePolicy.Builder}
         * @return this builder
         */
        public Builder table(String tableName, Consumer<TablePolicy.Builder> configurer) {
            TablePolicy.Builder tableBuilder = TablePolicy.builder(tableName);
            configurer.accept(tableBuilder);
            return table(tableBuilder.build());
        }

        /**
         * Builds the policy.
         *
         * @return the built {@link AnonymisationPolicy}
         */
        public AnonymisationPolicy build() {
            return new AnonymisationPolicy(autoInfer, maxCategoricalCardinality, distinguishingLint, tables);
        }
    }
}
