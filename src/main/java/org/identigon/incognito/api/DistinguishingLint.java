package org.identigon.incognito.api;

/**
 * Misdeclaration lint over distinguishing:false columns (SPEC §4.1), on by default.
 */
public enum DistinguishingLint {
    /** Report each misdeclared column as a warning; the run continues. */
    WARN,
    /** Fail the run on the first misdeclared column. */
    ERROR,
    /** Skip the check entirely — no {@code COUNT(DISTINCT)} scan is run. */
    OFF
}
