package io.github.dconneely.incognito.core;

import io.github.dconneely.incognito.api.AttributeCascadeStore;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;

/**
 * In-memory {@link AttributeCascadeStore} — the v1.0 default: published attributes, FK linkage, and
 * coherence-group jitter deltas held in memory for the duration of one run.
 */
public final class InMemoryAttributeCascadeStore implements AttributeCascadeStore {

    /** Creates an empty in-memory attribute-cascade store. */
    public InMemoryAttributeCascadeStore() {}

    private static final class AttributeKey {
        private final String parentTable;
        private final Object parentId;
        private final String attributeName;

        public AttributeKey(String parentTable, Object parentId, String attributeName) {
            this.parentTable = parentTable;
            this.parentId = parentId;
            this.attributeName = attributeName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            AttributeKey that = (AttributeKey) o;
            return Objects.equals(parentTable, that.parentTable) &&
                   Objects.equals(parentId, that.parentId) &&
                   Objects.equals(attributeName, that.attributeName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(parentTable, parentId, attributeName);
        }
    }

    /** Composite key for a group-scoped, per-entity jitter delta (SPEC §4.2). */
    private record JitterKey(String coherenceGroup, String parentTable, Object parentId) {}

    private final ConcurrentHashMap<AttributeKey, Object> attributes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<JitterKey, Long> jitterDeltas = new ConcurrentHashMap<>();

    @Override
    public void put(String parentTable, Object parentId, String attributeName, Object value) {
        attributes.put(new AttributeKey(parentTable, parentId, attributeName), value);
    }

    @Override
    public Optional<Object> get(String parentTable, Object parentId, String attributeName) {
        return Optional.ofNullable(attributes.get(new AttributeKey(parentTable, parentId, attributeName)));
    }

    @Override
    public Optional<Object> resolveSharedAncestor(String tableA, Object idA, String tableB, Object idB, String attributeName) {
        Optional<Object> valA = get(tableA, idA, attributeName);
        Optional<Object> valB = get(tableB, idB, attributeName);
        if (valA.isPresent() && valB.isPresent() && valA.get().equals(valB.get())) {
            return valA;
        }
        return Optional.empty();
    }

    @Override
    public void putJitterDelta(String coherenceGroup, String parentTable, Object parentId, long deltaDays) {
        jitterDeltas.put(new JitterKey(coherenceGroup, parentTable, parentId), deltaDays);
    }

    @Override
    public Optional<Long> getJitterDelta(String coherenceGroup, String parentTable, Object parentId) {
        return Optional.ofNullable(jitterDeltas.get(new JitterKey(coherenceGroup, parentTable, parentId)));
    }

    @Override
    public void close() {
        attributes.clear();
        jitterDeltas.clear();
    }
}
