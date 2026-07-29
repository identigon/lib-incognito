package io.github.dconneely.incognito.policy;

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
 */
public record AnonymisationPolicy(
    boolean autoInfer,
    int maxCategoricalCardinality,
    io.github.dconneely.incognito.api.DistinguishingLint distinguishingLint,
    Map<String, TablePolicy> tables
) {
    public AnonymisationPolicy {
        tables = Collections.unmodifiableMap(new LinkedHashMap<>(tables));
    }

    public Optional<TablePolicy> table(String tableName) {
        return Optional.ofNullable(tables.get(tableName));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        // Fail-closed by default (SPEC §7.2): auto-inference must be opted into, and it only
        // suggests roles — it never silently classifies.
        private boolean autoInfer = false;
        private int maxCategoricalCardinality = 64;
        private io.github.dconneely.incognito.api.DistinguishingLint distinguishingLint = io.github.dconneely.incognito.api.DistinguishingLint.WARN;
        private final Map<String, TablePolicy> tables = new LinkedHashMap<>();

        public Builder autoInfer(boolean autoInfer) {
            this.autoInfer = autoInfer;
            return this;
        }

        /** VARCHAR/SENSITIVE categorical threshold (SPEC §4.1). */
        public Builder maxCategoricalCardinality(int maxCategoricalCardinality) {
            this.maxCategoricalCardinality = maxCategoricalCardinality;
            return this;
        }

        public Builder distinguishingLint(io.github.dconneely.incognito.api.DistinguishingLint distinguishingLint) {
            this.distinguishingLint = distinguishingLint;
            return this;
        }

        public Builder table(TablePolicy tablePolicy) {
            this.tables.put(tablePolicy.tableName(), tablePolicy);
            return this;
        }

        public Builder table(String tableName, Consumer<TablePolicy.Builder> configurer) {
            TablePolicy.Builder tableBuilder = TablePolicy.builder(tableName);
            configurer.accept(tableBuilder);
            return table(tableBuilder.build());
        }

        public AnonymisationPolicy build() {
            return new AnonymisationPolicy(autoInfer, maxCategoricalCardinality, distinguishingLint, tables);
        }
    }
}
