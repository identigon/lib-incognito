package org.identigon.incognito.api;

import java.util.Arrays;
import java.util.Objects;

/**
 * Encapsulates multi-column primary or foreign key tuples, with value equality over the components.
 *
 * @param components the ordered key-column values; at least one, none of the array itself null
 */
public record CompositeKey(Object... components) {
    /**
     * Validates that at least one component is present.
     *
     * @throws NullPointerException     if the components array is null
     * @throws IllegalArgumentException if there are no components
     */
    public CompositeKey {
        Objects.requireNonNull(components, "components cannot be null");
        if (components.length == 0) {
            throw new IllegalArgumentException("CompositeKey must contain at least one component");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CompositeKey that = (CompositeKey) o;
        return Arrays.equals(components, that.components);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(components);
    }

    @Override
    public String toString() {
        return "CompositeKey" + Arrays.toString(components);
    }
}
