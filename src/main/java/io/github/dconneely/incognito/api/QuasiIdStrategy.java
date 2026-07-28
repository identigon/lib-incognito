package io.github.dconneely.incognito.api;

/**
 * Fabrication strategy for a {@link ColumnRole#QUASI_ID} column. Temporal modes preserve
 * per-period volumes (SPEC §4.2); the default for a QI that names no strategy is
 * {@link #SYNTHESISE}.
 *
 * <p>There is deliberately no "keep" option: a quasi-identifier is always fabricated, because a
 * surviving real QI value would break the "no real quasi-identifier value survives" guarantee
 * (SPEC §2.1). To keep a column's real value, classify it {@link ColumnRole#PAYLOAD}.
 */
public enum QuasiIdStrategy {
    /** Fresh fictional value; distribution NOT preserved. Default for e.g. {@code dob}, {@code postcode}. */
    SYNTHESISE,
    /** {@code AlterEgo.shiftDate(MONTH)}: random day within the value's own month → exact monthly volumes. */
    JITTER_WITHIN_MONTH,
    /** {@code AlterEgo.shiftDate(YEAR)}: random day within the value's own year → exact yearly volumes. */
    JITTER_WITHIN_YEAR,
    /** Bounded ±window shift ({@code jitterDays}, with optional {@code coherenceGroup}, on the column policy). */
    JITTER_DAYS
}
