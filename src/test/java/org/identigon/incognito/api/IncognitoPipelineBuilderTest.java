package org.identigon.incognito.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IncognitoPipelineBuilder}'s salt/seed derivation (package-private, tested
 * directly rather than through a full pipeline run — no database needed).
 */
class IncognitoPipelineBuilderTest {

    private static final byte[] SALT = "0123456789abcdef".getBytes();

    @Test
    void sameSaltAndSeedDeriveIdenticalOutput() {
        byte[] a = IncognitoPipelineBuilder.deriveReproducibleSalt(SALT, 7L);
        byte[] b = IncognitoPipelineBuilder.deriveReproducibleSalt(SALT, 7L);
        assertArrayEquals(a, b, "same (salt, seed) must derive byte-for-byte identical output "
            + "(SPEC §5.2 reproducibility)");
    }

    @Test
    void differentSeedsDeriveDifferentOutput() {
        byte[] a = IncognitoPipelineBuilder.deriveReproducibleSalt(SALT, 1L);
        byte[] b = IncognitoPipelineBuilder.deriveReproducibleSalt(SALT, 2L);
        assertFalse(Arrays.equals(a, b),
            "varying the seed alone (same salt) must change the derived salt — previously the "
                + "seed argument was silently ignored entirely");
    }

    @Test
    void derivedSaltMeetsAlterEgoMinimumLength() {
        byte[] derived = IncognitoPipelineBuilder.deriveReproducibleSalt(SALT, 42L);
        assertTrue(derived.length >= 16, "AlterEgo requires a salt of at least 16 bytes");
    }
}
