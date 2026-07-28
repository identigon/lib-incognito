package io.github.dconneely.incognito.api;

/**
 * Selects which {@code lib-alterego} generator backs a {@link ColumnRole#DIRECT_ID} or
 * {@link ColumnRole#UNIQUE_CANDIDATE_KEY} column.
 */
public enum DirectIdStrategy {
    /** Full-name generator ({@code AlterEgo.fullName()}). */
    ALTEREGO_NAME,
    /** Email-address generator ({@code AlterEgo.emailAddress()}). */
    ALTEREGO_EMAIL,
    /** Phone-number generator ({@code AlterEgo.phoneNumber()}). */
    ALTEREGO_PHONE,
    /**
     * Generic pattern/mask-based generator for other direct identifiers, and the **default**
     * when a {@code DIRECT_ID}/{@code UNIQUE_CANDIDATE_KEY} column names no strategy. Resolved
     * in {@code TableTransformStage} (Phase 5); a {@code null} strategy is never an error.
     */
    ALTEREGO_GENERIC
}
