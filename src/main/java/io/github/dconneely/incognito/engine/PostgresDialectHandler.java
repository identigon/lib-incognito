package io.github.dconneely.incognito.engine;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PostgreSQL-specific dialect handler.
 * Implements trigger isolation using session_replication_role or ALTER TABLE DISABLE TRIGGER USER.
 */
public final class PostgresDialectHandler implements DialectHandler {

    @Override
    public void preLoadTable(Connection targetConn, String tableName) throws SQLException {
        try (Statement stmt = targetConn.createStatement()) {
            // Attempt to use superuser session_replication_role for fast trigger/FK suppression
            stmt.execute("SET session_replication_role = 'replica'");
        } catch (SQLException e) {
            // Fallback for non-superusers (must be owner of the table)
            try (Statement stmt = targetConn.createStatement()) {
                stmt.execute("ALTER TABLE " + tableName + " DISABLE TRIGGER USER");
            }
        }
    }

    @Override
    public String buildInsertSql(String tableName, List<String> columns, boolean hasIdentityPk) {
        String cols = String.join(", ", columns);
        String placeholders = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        String sql = "INSERT INTO " + tableName + " (" + cols + ") ";
        if (hasIdentityPk) {
            sql += "OVERRIDING SYSTEM VALUE ";
        }
        sql += "VALUES (" + placeholders + ")";
        return sql;
    }

    @Override
    public void postLoadTable(Connection targetConn, String tableName) throws SQLException {
        // Safe to call even if we didn't disable triggers this way, to ensure they are enabled
        try (Statement stmt = targetConn.createStatement()) {
            stmt.execute("ALTER TABLE " + tableName + " ENABLE TRIGGER USER");
        } catch (SQLException ignored) {
            // Ignored, might not be owner or might not be necessary if session_replication_role was used
        }
    }

    @Override
    public boolean canDeferCyclicForeignKeys(Connection targetConn) throws SQLException {
        // session_replication_role='replica' (the only way to suppress FK enforcement without
        // dropping constraints) requires a superuser role.
        try (Statement stmt = targetConn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT current_setting('is_superuser')")) {
            return rs.next() && "on".equalsIgnoreCase(rs.getString(1));
        }
    }

    @Override
    public void resyncSequence(Connection targetConn, String tableName, String pkCol) throws SQLException {
        try (Statement stmt = targetConn.createStatement()) {
            // Find the sequence associated with the column and resync it
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT pg_get_serial_sequence('" + tableName + "', '" + pkCol + "')")) {
                if (rs.next()) {
                    String seqName = rs.getString(1);
                    if (seqName != null) {
                        stmt.execute("SELECT setval('" + seqName + "', "
                            + "(SELECT COALESCE(MAX(" + pkCol + "), 1) FROM " + tableName + "))");
                    }
                }
            }
        }
    }
}
