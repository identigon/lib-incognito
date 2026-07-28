package io.github.dconneely.incognito.api;

/**
 * How to redact a {@link ColumnRole#SENSITIVE} column that cannot be kept real (its distinct-value
 * count exceeds {@code maxCategoricalCardinality}, so the value could itself identify — SPEC §4.1).
 * An alternative to fabricating it with a {@link QuasiIdStrategy}. Backed by {@code lib-alterego}.
 */
public enum RedactionStrategy {
    /** Replace with {@code NULL} (nullable columns) or a type-appropriate empty value. */
    CLEAR,
    /** Mask all but the last few characters ({@code AlterEgo.mask}). */
    MASK,
    /** Replace every value with a single fixed fictional placeholder ({@code AlterEgo.constant}). */
    CONSTANT
}
