package io.github.dconneely.incognito.api;

import java.util.Optional;

/**
 * SPI for parent-child attribute cascading, root-ancestor resolution, and coherent temporal jitter
 * deltas (SPEC §4.2, §6.1).
 */
public interface AttributeCascadeStore extends AutoCloseable {

    /** Publishes a parent entity's attribute value for its children to inherit. */
    void put(String parentTable, Object parentId, String attributeName, Object value);

    /** Reads a published parent attribute value. */
    Optional<Object> get(String parentTable, Object parentId, String attributeName);

    /**
     * Resolves an {@code INHERITED_ATTRIBUTE} for a child reachable via two convergent parent paths
     * (a diamond) by reading the value from their **shared (root) ancestor** entity, rather than
     * comparing the two branch copies. This makes convergent paths incapable of conflicting:
     * inheritance is always taken from the declared {@code derived_from} ancestor (SPEC §6.1). A
     * value set on an intermediate branch is not an "override" — inheritance is by definition from
     * the ancestor.
     */
    Optional<Object> resolveSharedAncestor(String tableA, Object idA, String tableB, Object idB, String attributeName);

    /** Caches the single shared temporal jitter delta for an entity (SPEC §4.2), so its children inherit it. */
    void putJitterDelta(String parentTable, Object parentId, long deltaDays);

    /** Reads an entity's shared jitter delta, so a child's dates shift coherently with the parent. */
    Optional<Long> getJitterDelta(String parentTable, Object parentId);
}
