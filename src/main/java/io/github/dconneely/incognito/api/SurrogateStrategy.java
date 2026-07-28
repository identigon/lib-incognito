package io.github.dconneely.incognito.api;

/**
 * Strategy for generating synthetic primary surrogate keys in target databases.
 */
public enum SurrogateStrategy {
    /** Sequential 64-bit integer values starting from 1. */
    SEQUENTIAL_LONG,

    /** Random version-4 UUID values (lower-case string or UUID type). */
    UUID_V4,

    /** Preserves original key values (used only when primary keys are non-identifying surrogate numbers). */
    PASSTHROUGH_SURROGATE
}
