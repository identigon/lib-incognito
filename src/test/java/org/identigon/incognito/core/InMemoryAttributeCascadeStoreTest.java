package org.identigon.incognito.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.identigon.incognito.api.AttributeCascadeStore;
import org.junit.jupiter.api.Test;

/** Phase-4 coverage for the cascade store: attribute publish/read and group-scoped jitter deltas (SPEC §4.2, §6.1). */
class InMemoryAttributeCascadeStoreTest {

    @Test
    void publishesAndReadsAttributes() {
        AttributeCascadeStore store = new InMemoryAttributeCascadeStore();
        store.put("firm", 7L, "firm_type", "LLP");

        assertEquals("LLP", store.get("firm", 7L, "firm_type").orElseThrow());
        assertTrue(store.get("firm", 8L, "firm_type").isEmpty(), "different id → miss");
        assertTrue(store.get("office", 7L, "firm_type").isEmpty(), "different table → miss");
        assertTrue(store.get("firm", 7L, "other").isEmpty(), "different attribute → miss");
    }

    @Test
    void jitterDeltasAreScopedByCoherenceGroup() {
        AttributeCascadeStore store = new InMemoryAttributeCascadeStore();
        store.putJitterDelta("contract_window", "contract", 42L, 5L);

        // Same (group, table, id) → hit.
        assertEquals(5L, store.getJitterDelta("contract_window", "contract", 42L).orElseThrow());

        // A DIFFERENT coherence group must NOT see this delta — this is the contamination guard:
        // a child with several FK parents only inherits the delta anchoring ITS group.
        assertTrue(store.getJitterDelta("customer_window", "contract", 42L).isEmpty(),
            "delta must not leak across coherence groups");

        // Different entity id in the same group → miss.
        assertTrue(store.getJitterDelta("contract_window", "contract", 99L).isEmpty());
    }

    @Test
    void closeClearsState() throws Exception {
        InMemoryAttributeCascadeStore store = new InMemoryAttributeCascadeStore();
        store.put("firm", 1L, "firm_type", "LLP");
        store.putJitterDelta("g", "firm", 1L, 3L);

        store.close();

        assertTrue(store.get("firm", 1L, "firm_type").isEmpty());
        assertTrue(store.getJitterDelta("g", "firm", 1L).isEmpty());
    }

    @Test
    void resolveSharedAncestorMatchesOnlyOnAgreement() {
        AttributeCascadeStore store = new InMemoryAttributeCascadeStore();
        store.put("firm", 1L, "firm_type", "LLP");
        store.put("firm", 2L, "firm_type", "LLP");
        store.put("firm", 3L, "firm_type", "PLC");

        assertEquals("LLP", store.resolveSharedAncestor("firm", 1L, "firm", 2L, "firm_type").orElseThrow());
        assertFalse(store.resolveSharedAncestor("firm", 1L, "firm", 3L, "firm_type").isPresent(),
            "disagreeing branch values → no shared value");
    }
}
