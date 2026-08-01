package io.github.dconneely.incognito.engine;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Handles database dialect-specific load mechanics, such as trigger isolation,
 * batch rewriting, insert syntax, and sequence resynchronization.
 */
public interface DialectHandler {
    
    /**
     * Called before loading a table. Used to suppress foreign key enforcement
     * and user triggers (e.g., via session_replication_role or ALTER TABLE).
     *
     * @param targetConn the target connection performing the inserts
     * @param tableName  the table about to be loaded
     * @throws SQLException if the suppression cannot be applied
     */
    void preLoadTable(Connection targetConn, String tableName) throws SQLException;

    /**
     * Returns the SQL snippet for INSERT.
     * @param tableName the target table name
     * @param columns the list of columns to insert
     * @param hasIdentityPk whether the table has an identity primary key (needs OVERRIDING SYSTEM VALUE on Postgres)
     * @return the dialect-specific {@code INSERT} statement
     */
    String buildInsertSql(String tableName, List<String> columns, boolean hasIdentityPk);

    /**
     * Called after loading a table. Used to restore foreign key enforcement and triggers.
     *
     * @param targetConn the target connection that performed the inserts
     * @param tableName  the table that was loaded
     * @throws SQLException if enforcement cannot be restored
     */
    void postLoadTable(Connection targetConn, String tableName) throws SQLException;

    /**
     * Resynchronizes the sequence for a table's primary key after data has been loaded.
     *
     * @param targetConn the target connection
     * @param tableName  the loaded table
     * @param pkCol      the identity/serial primary-key column whose sequence to resync
     * @throws SQLException if the resync fails
     */
    void resyncSequence(Connection targetConn, String tableName, String pkCol) throws SQLException;

    /**
     * Whether this dialect can suppress foreign-key enforcement on {@code targetConn} for the
     * placeholder inserts a cyclic-FK load performs (Pass 1). The owner-mode trigger fallback does
     * <em>not</em> disable FK enforcement, so cyclic loads need the privileged path (on PostgreSQL,
     * a superuser for {@code session_replication_role='replica'}). Returns {@code false} by default
     * so a dialect that can't guarantee it triggers a clear fail-fast rather than a confusing FK
     * violation mid-load.
     *
     * @param targetConn the target connection that would perform the placeholder inserts
     * @return {@code true} if FK enforcement can be suppressed on this connection
     * @throws SQLException if the capability cannot be probed
     */
    default boolean canDeferCyclicForeignKeys(Connection targetConn) throws SQLException {
        return false;
    }
}
