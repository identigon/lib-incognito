package io.github.dconneely.incognito.api;

import java.util.Optional;

/**
 * SPI for parent-child attribute cascading, root-ancestor resolution, and coherent temporal jitter
 * deltas (SPEC §4.2, §6.1).
 */
public interface AttributeCascadeStore extends AutoCloseable {

    /**
     * Publishes a parent entity's attribute value for its children to inherit.
     *
     * @param parentTable the parent table name
     * @param parentId the parent's source primary key
     * @param attributeName the attribute (column) name
     * @param value the fabricated value to publish
     */
    void put(String parentTable, Object parentId, String attributeName, Object value);

    /**
     * Reads a published parent attribute value.
     *
     * @param parentTable the parent table name
     * @param parentId the parent's source primary key
     * @param attributeName the attribute (column) name
     * @return the published value, or empty if none was published
     */
    Optional<Object> get(String parentTable, Object parentId, String attributeName);

    /**
     * Resolves an {@code INHERITED_ATTRIBUTE} for a child reachable via two convergent parent paths
     * (a diamond) by reading the value from their **shared (root) ancestor** entity, rather than
     * comparing the two branch copies. This makes convergent paths incapable of conflicting:
     * inheritance is always taken from the declared {@code derived_from} ancestor (SPEC §6.1). A
     * value set on an intermediate branch is not an "override" — inheritance is by definition from
     * the ancestor.
     *
     * @param tableA the first branch's table
     * @param idA the first branch's source id
     * @param tableB the second branch's table
     * @param idB the second branch's source id
     * @param attributeName the attribute (column) name
     * @return the shared ancestor's value if the branches agree, otherwise empty
     */
    Optional<Object> resolveSharedAncestor(String tableA, Object idA, String tableB, Object idB, String attributeName);

    /**
     * Caches the single shared temporal jitter delta for an entity within a named coherence group
     * (SPEC §4.2), so its children inherit it. Keying on {@code coherenceGroup} keeps deltas from
     * different groups from contaminating one another when a table has several FK parents.
     *
     * @param coherenceGroup the named coherence group the delta belongs to
     * @param parentTable the entity's table
     * @param parentId the entity's source primary key
     * @param deltaDays the shared day-delta to cache
     */
    void putJitterDelta(String coherenceGroup, String parentTable, Object parentId, long deltaDays);

    /**
     * Reads an entity's shared jitter delta for a coherence group, so a child's dates shift
     * coherently with the parent.
     *
     * @param coherenceGroup the named coherence group
     * @param parentTable the entity's table
     * @param parentId the entity's source primary key
     * @return the cached day-delta, or empty if none
     */
    Optional<Long> getJitterDelta(String coherenceGroup, String parentTable, Object parentId);
}
