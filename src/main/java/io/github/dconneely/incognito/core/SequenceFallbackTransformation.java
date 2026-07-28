package io.github.dconneely.incognito.core;

import io.github.dconneely.alterego.AlterEgoCollisionException;
import io.github.dconneely.alterego.Transformation;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A wrapper transformation that catches {@link AlterEgoCollisionException} and
 * applies a sequence-decorated fallback (e.g., appends "_000001") to ensure
 * high-cardinality candidate keys remain unique even when the base dictionary
 * is exhausted.
 */
public final class SequenceFallbackTransformation implements Transformation<String> {

    private final Transformation<String> delegate;
    private final Transformation<String> uniqueDelegate;
    private final AtomicLong sequence = new AtomicLong(1);
    private final String suffixFormat;

    public SequenceFallbackTransformation(Transformation<String> delegate, String suffixFormat) {
        this.delegate = delegate;
        this.uniqueDelegate = delegate.unique();
        this.suffixFormat = suffixFormat;
    }

    public SequenceFallbackTransformation(Transformation<String> delegate) {
        this(delegate, "_%06d");
    }

    @Override
    public String apply(String input) {
        try {
            return uniqueDelegate.apply(input);
        } catch (AlterEgoCollisionException e) {
            // Base dictionary is exhausted for unique generation; fallback to sequence.
            // Generate a non-unique base fabricated value (so we don't leak real input).
            String baseFabricated = delegate.apply(input);
            // Append the sequence to guarantee uniqueness.
            // (Note: To ensure referential consistency, TableTransformStage will wrap this in .stored())
            return baseFabricated + String.format(suffixFormat, sequence.getAndIncrement());
        }
    }

    @Override
    public Transformation<String> unique() {
        return this; // we handle uniqueness inherently
    }

    @Override
    public Transformation<String> stored() {
        throw new UnsupportedOperationException("stored() not fully implemented for fallback wrapper yet (Phase 4)");
    }
}
