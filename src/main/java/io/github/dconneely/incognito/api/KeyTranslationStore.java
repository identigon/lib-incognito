package io.github.dconneely.incognito.api;

import java.util.Optional;

/**
 * SPI for primary/foreign key surrogate translation stores.
 */
public interface KeyTranslationStore extends AutoCloseable {
    void put(String tableName, Object oldPk, Object newPk) throws IncognitoException.StoreException;
    Optional<Object> get(String tableName, Object oldPk) throws IncognitoException.StoreException;
    boolean contains(String tableName, Object oldPk);
    void flush() throws IncognitoException.StoreException;
}
