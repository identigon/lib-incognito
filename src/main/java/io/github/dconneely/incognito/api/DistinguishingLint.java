package io.github.dconneely.incognito.api;

/**
 * Misdeclaration lint over distinguishing:false columns (SPEC §4.1), on by default.
 */
public enum DistinguishingLint {
    WARN,
    ERROR,
    OFF
}
