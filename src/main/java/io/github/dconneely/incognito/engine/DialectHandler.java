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
     */
    void preLoadTable(Connection targetConn, String tableName) throws SQLException;
    
    /**
     * Returns the SQL snippet for INSERT.
     * @param tableName the target table name
     * @param columns the list of columns to insert
     * @param hasIdentityPk whether the table has an identity primary key (needs OVERRIDING SYSTEM VALUE on Postgres)
     */
    String buildInsertSql(String tableName, List<String> columns, boolean hasIdentityPk);
    
    /**
     * Called after loading a table. Used to restore foreign key enforcement and triggers.
     */
    void postLoadTable(Connection targetConn, String tableName) throws SQLException;
    
    /**
     * Resynchronizes the sequence for a table's primary key after data has been loaded.
     */
    void resyncSequence(Connection targetConn, String tableName, String pkCol) throws SQLException;
}
