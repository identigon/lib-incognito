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

    private static final System.Logger LOG = System.getLogger(PostgresDialectHandler.class.getName());

    /** Creates a PostgreSQL dialect handler. */
    public PostgresDialectHandler() {}

    @Override
    public void preLoadTable(Connection targetConn, String tableName) throws SQLException {
        try (Statement stmt = targetConn.createStatement()) {
            // Attempt to use superuser session_replication_role for fast trigger/FK suppression
            stmt.execute("SET session_replication_role = 'replica'");
        } catch (SQLException e) {
            // Non-superuser: fall back to owner-mode trigger disabling (does not suppress FK enforcement).
            LOG.log(System.Logger.Level.DEBUG,
                "session_replication_role unavailable (SQLState {0}); falling back to owner-mode DISABLE TRIGGER on {1}",
                e.getSQLState(), tableName);
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
        } catch (SQLException e) {
            // Often benign: on the superuser session_replication_role path triggers were never disabled
            // via ALTER TABLE, so re-enabling can fail harmlessly. Surface at DEBUG for diagnosis only.
            LOG.log(System.Logger.Level.DEBUG,
                "ENABLE TRIGGER USER failed on {0} (SQLState {1}); usually benign after session_replication_role",
                tableName, e.getSQLState());
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
