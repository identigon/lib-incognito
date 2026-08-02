package org.identigon.incognito.core;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.identigon.incognito.api.IncognitoException;
import org.identigon.incognito.api.KeyTranslationStore;

/**
 * In-memory {@link KeyTranslationStore} — the single-JVM, non-persistent v1.0 default.
 */
public final class InMemoryKeyTranslationStore implements KeyTranslationStore {
    private final ConcurrentHashMap<String, ConcurrentHashMap<Object, Object>> store = new ConcurrentHashMap<>();

    /** Creates an empty in-memory key-translation store. */
    public InMemoryKeyTranslationStore() {}

    @Override
    public void put(String tableName, Object oldPk, Object newPk) throws IncognitoException.StoreException {
        store.computeIfAbsent(tableName, k -> new ConcurrentHashMap<>()).put(oldPk, newPk);
    }

    @Override
    public Optional<Object> get(String tableName, Object oldPk) throws IncognitoException.StoreException {
        ConcurrentHashMap<Object, Object> tableMap = store.get(tableName);
        if (tableMap != null) {
            return Optional.ofNullable(tableMap.get(oldPk));
        }
        return Optional.empty();
    }

    @Override
    public boolean contains(String tableName, Object oldPk) {
        ConcurrentHashMap<Object, Object> tableMap = store.get(tableName);
        return tableMap != null && tableMap.containsKey(oldPk);
    }

    @Override
    public void flush() throws IncognitoException.StoreException {
        // No-op for in-memory
    }

    @Override
    public void close() {
        store.clear();
    }
}
