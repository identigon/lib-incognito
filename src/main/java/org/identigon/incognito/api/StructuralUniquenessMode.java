package org.identigon.incognito.api;

/**
 * Whether {@code VerificationStage} computes and reports structural-uniqueness findings (relational
 * fingerprints — a subject singled out by its FK fan-out rather than by any field value, SPEC §2.4),
 * off by default.
 */
public enum StructuralUniquenessMode {
    /** Skip the check entirely — no per-edge {@code GROUP BY} scan is run. */
    OFF,
    /** Compute per-edge fan-out fingerprints and record them as {@code StructuralUniquenessFinding}s. */
    REPORT
}
