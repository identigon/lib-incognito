package io.github.dconneely.incognito.engine;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ANSI fallback generic dialect handler.
 * Does not support trigger isolation or sequence resync. Assumes strict topological ordering.
 */
public final class GenericDialectHandler implements DialectHandler {

    /** Creates a generic ANSI dialect handler. */
    public GenericDialectHandler() {}

    @Override
    public void preLoadTable(Connection targetConn, String tableName) throws SQLException {
        // No-op. Rely on topological ordering.
    }

    @Override
    public String buildInsertSql(String tableName, List<String> columns, boolean hasIdentityPk) {
        String cols = String.join(", ", columns);
        String placeholders = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        return "INSERT INTO " + tableName + " (" + cols + ") VALUES (" + placeholders + ")";
    }

    @Override
    public void postLoadTable(Connection targetConn, String tableName) throws SQLException {
        // No-op.
    }

    @Override
    public void resyncSequence(Connection targetConn, String tableName, String pkCol) throws SQLException {
        // No-op. Generic ANSI SQL has no standard way to resync sequences.
    }
}
