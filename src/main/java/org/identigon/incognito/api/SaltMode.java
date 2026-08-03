package org.identigon.incognito.api;

/**
 * How a run derived the salt that keys all fabrication (SPEC §5.1/§5.2). Recorded on the
 * {@link AnonymisationReport} because it is the single most safety-critical fact a DPIA reviewer
 * needs to weigh the Recital 26 "means reasonably likely to be used" test: two runs with identical
 * per-column transformations make materially different anonymity claims depending on how they were
 * keyed.
 */
public enum SaltMode {
    /**
     * A fresh random salt, generated per run and destroyed on completion — the default. Output is
     * unlinkable across runs and the mapping is irreversible: the strongest anonymity claim.
     */
    EPHEMERAL,
    /**
     * A caller-supplied fixed salt reused across runs. Deliberately makes output <em>linkable</em>
     * between runs (the same source value fabricates to the same surrogate every time), which
     * forfeits irreversibility (SPEC §5.2) — a reviewer must account for the retained salt as a
     * re-identification vector.
     */
    PERSISTENT,
    /**
     * A fixed salt plus a fixed RNG seed, making a run byte-for-byte reproducible for regression
     * fixtures (SPEC §5.2). Carries the same linkability/irreversibility caveats as
     * {@link #PERSISTENT}, and is intended for test data, not production clones.
     */
    REPRODUCIBLE
}
