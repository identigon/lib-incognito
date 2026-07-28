package io.github.dconneely.incognito.core;

import io.github.dconneely.incognito.api.AttributeCascadeStore;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;

public final class InMemoryAttributeCascadeStore implements AttributeCascadeStore {

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

    private final ConcurrentHashMap<AttributeKey, Object> attributes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<Object, Long>> jitterDeltas = new ConcurrentHashMap<>();

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
    public void putJitterDelta(String parentTable, Object parentId, long deltaDays) {
        jitterDeltas.computeIfAbsent(parentTable, k -> new ConcurrentHashMap<>()).put(parentId, deltaDays);
    }

    @Override
    public Optional<Long> getJitterDelta(String parentTable, Object parentId) {
        ConcurrentHashMap<Object, Long> tableMap = jitterDeltas.get(parentTable);
        if (tableMap != null) {
            return Optional.ofNullable(tableMap.get(parentId));
        }
        return Optional.empty();
    }

    @Override
    public void close() {
        attributes.clear();
        jitterDeltas.clear();
    }
}
