package org.identigon.incognito.api;

/**
 * Selects which {@code lib-alterego} generator backs a {@link ColumnRole#DIRECT_ID} or
 * {@link ColumnRole#UNIQUE_CANDIDATE_KEY} column.
 */
public enum DirectIdStrategy {
    /** Full-name generator ({@code AlterEgo.fullName()}). */
    ALTEREGO_NAME,
    /** First-name generator ({@code AlterEgo.firstName()}). */
    ALTEREGO_FIRST_NAME,
    /** Surname generator ({@code AlterEgo.lastName()}) — authored, obviously-fictional surnames (ADR 0010). */
    ALTEREGO_LAST_NAME,
    /** Organisation-name generator ({@code AlterEgo.organisationName()}). */
    ALTEREGO_ORGANISATION,
    /** Town/city generator ({@code AlterEgo.city()}). */
    ALTEREGO_CITY,
    /** Street-address generator ({@code AlterEgo.streetAddress()}) — authored, obviously-fictional streets (ADR 0010). */
    ALTEREGO_STREET_ADDRESS,
    /**
     * Postcode generator ({@code AlterEgo.postcode()}) — GB format only. {@code lib-alterego} ships
     * no other country's postcode table yet; this is the same GB-only reality every typed generator
     * in this enum already has, since only GB dictionaries are bundled.
     */
    ALTEREGO_POSTCODE,
    /** Email-address generator ({@code AlterEgo.emailAddress()}). */
    ALTEREGO_EMAIL,
    /** Phone-number generator ({@code AlterEgo.phoneNumber()}). */
    ALTEREGO_PHONE,
    /** Domain-name generator ({@code AlterEgo.domainName()}) — RFC 2606 reserved domains/TLDs. */
    ALTEREGO_DOMAIN,
    /** URL generator ({@code AlterEgo.url()}) — a scheme plus {@code AlterEgo.domainName()}, with an optional random path. */
    ALTEREGO_URL,
    /**
     * Generic shape-preserving generator ({@code fabricateShapePreserving} on AlterEgo's salt-keyed
     * stream), and the **default** when a {@code DIRECT_ID}/{@code UNIQUE_CANDIDATE_KEY} column names
     * no strategy. Best for <b>code-like</b> fields (reference numbers, codes, usernames): it preserves
     * length and character classes but carries <b>no fictionality guarantee</b>. Avoid it for names and
     * addresses, where the preserved shape (e.g. the capitalisation of {@code McDonald}) is itself
     * identifying — use the typed generators above.
     */
    ALTEREGO_GENERIC
}
