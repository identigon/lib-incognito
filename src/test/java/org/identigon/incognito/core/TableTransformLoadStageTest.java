package org.identigon.incognito.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Phase-4 unit coverage for the string {@code UNIQUE_CANDIDATE_KEY} collision fallback. The key
 * property (Goal 1): the fallback is EXACTLY length-preserving, so it can never overflow a
 * fixed-width / CHECK-constrained column — the bug in the earlier draft, which appended a 6-digit
 * suffix and grew short values.
 */
class TableTransformLoadStageTest {

    @Test
    void fallbackPreservesLengthForTypicalValue() {
        String out = TableTransformLoadStage.uniquenessFallback("ABCDEFGH", 1L);
        assertEquals(8, out.length());
        assertEquals("AB000001", out);
    }

    @Test
    void fallbackNeverOverflowsShortValues() {
        // The regression case: a 2-char value must stay 2 chars (old code produced "A000123", len 7).
        String out = TableTransformLoadStage.uniquenessFallback("AB", 123L);
        assertEquals(2, out.length());
        assertEquals("23", out);

        assertEquals(1, TableTransformLoadStage.uniquenessFallback("X", 7L).length());
        assertEquals("", TableTransformLoadStage.uniquenessFallback("", 5L));
    }

    @Test
    void fallbackIsLengthPreservingAcrossManyInputs() {
        for (int len = 1; len <= 20; len++) {
            String base = "Q".repeat(len);
            for (long seq : new long[]{0L, 1L, 9L, 999_999L, 1_000_000L, Long.MAX_VALUE}) {
                String out = TableTransformLoadStage.uniquenessFallback(base, seq);
                assertEquals(len, out.length(),
                    "length must be preserved for len=" + len + " seq=" + seq);
                assertTrue(out.chars().allMatch(c -> c == 'Q' || Character.isDigit(c)),
                    "fallback keeps a prefix and appends only digits");
            }
        }
    }
}
