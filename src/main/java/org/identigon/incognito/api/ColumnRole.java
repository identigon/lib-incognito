package org.identigon.incognito.api;

/**
 * Categorization taxonomy for columns in relational database tables processed by Incognito.
 */
public enum ColumnRole {
    /** Primary key column translated to a synthetic surrogate key. */
    PRIMARY_KEY,

    /** Non-PK candidate key carrying a UNIQUE SQL constraint or index. */
    UNIQUE_CANDIDATE_KEY,

    /** Foreign key column referencing a parent table's primary key. */
    FOREIGN_KEY,

    /** Direct personal identifier (Name, Email, Phone, SSN, NHS Number). */
    DIRECT_ID,

    /**
     * Quasi-identifier attribute (DOB, Postcode, Age, Salary) — fabricated via
     * {@code QuasiIdStrategy} (synthesised or jittered), not generalised/suppressed under a
     * k-anonymity model, which this library deliberately does not implement (SPEC §2.3, ADR 0001).
     */
    QUASI_ID,

    /** Attribute inherited or cascaded from a parent entity. */
    INHERITED_ATTRIBUTE,

    /** Computed column (GENERATED ALWAYS AS) excluded from target INSERT lists. */
    GENERATED_COLUMN,

    /**
     * Sensitive payload attribute, kept real or fabricated/redacted per the declared
     * {@code distinguishing} flag (SPEC §2.2/§4.1) — not protected via l-diversity, which this
     * library deliberately does not implement (SPEC §2.3, ADR 0001).
     */
    SENSITIVE,

    /** Non-identifying payload passed through unchanged. */
    PAYLOAD,

    // --- Reserved for post-v1.0. Parsed, but a policy that assigns one fails fast in v1.0. ---

    /** RESERVED (post-v1.0): facial photo or image BLOB subject to GDPR Art. 9 biometric rules. */
    BIOMETRIC_MEDIA,

    /** RESERVED (post-v1.0): PostGIS or spatial coordinate point (POINT, GEOMETRY). */
    SPATIAL_GEOMETRY,

    /** RESERVED (post-v1.0): element within a SQL array (e.g. text[]). */
    ARRAY_ELEMENT,

    /** RESERVED (post-v1.0): IP address string or INET/CIDR type. */
    NETWORK_INET,

    /** RESERVED (post-v1.0): embedded JSON or JSONB document column. */
    JSON_DOCUMENT
}
