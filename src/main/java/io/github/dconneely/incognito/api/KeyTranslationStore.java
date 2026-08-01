package io.github.dconneely.incognito.api;

import java.util.Optional;

/**
 * SPI for primary/foreign key surrogate translation stores.
 */
public interface KeyTranslationStore extends AutoCloseable {
    /**
     * Records the fabricated surrogate for a source key.
     *
     * @param tableName the table the key belongs to
     * @param oldPk     the original (source) key value
     * @param newPk     the fabricated (target) key value
     * @throws IncognitoException.StoreException if the mapping cannot be stored
     */
    void put(String tableName, Object oldPk, Object newPk) throws IncognitoException.StoreException;

    /**
     * Returns the surrogate previously recorded for a source key.
     *
     * @param tableName the table the key belongs to
     * @param oldPk     the original (source) key value
     * @return the fabricated key, or empty if none has been recorded
     * @throws IncognitoException.StoreException if the lookup fails
     */
    Optional<Object> get(String tableName, Object oldPk) throws IncognitoException.StoreException;

    /**
     * Reports whether a surrogate has been recorded for a source key.
     *
     * @param tableName the table the key belongs to
     * @param oldPk     the original (source) key value
     * @return {@code true} if a surrogate is recorded for the key
     */
    boolean contains(String tableName, Object oldPk);

    /**
     * Flushes any buffered mappings to durable storage.
     *
     * @throws IncognitoException.StoreException if the flush fails
     */
    void flush() throws IncognitoException.StoreException;
}
