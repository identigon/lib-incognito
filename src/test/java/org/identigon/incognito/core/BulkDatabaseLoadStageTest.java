package org.identigon.incognito.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.identigon.incognito.engine.DialectHandler;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link BulkDatabaseLoadStage#close()}'s exception handling: if the batch flush
 * fails AND closing the (now broken) statement also fails, both must be visible — the first
 * (typically the more actionable, e.g. a constraint violation) as the primary thrown exception, the
 * second attached via {@link Throwable#addSuppressed}, rather than the second silently replacing
 * the first via plain try/finally semantics.
 *
 * <p>Uses hand-rolled JDBC proxies to deterministically force both failures — no database needed.
 */
class BulkDatabaseLoadStageTest {

    private static final SQLException BATCH_FAILURE =
        new SQLException("duplicate key value violates unique constraint", "23505");
    private static final SQLException CLOSE_FAILURE =
        new SQLException("connection already closed", "08003");

    /** A no-op dialect handler: never touches the connection, so the fake below stays minimal. */
    private static final DialectHandler NOOP_DIALECT = new DialectHandler() {
        @Override public void preLoadTable(Connection c, String t) {}
        @Override public String buildInsertSql(String t, List<String> cols, boolean identity) { return "INSERT"; }
        @Override public void postLoadTable(Connection c, String t) {}
        @Override public void resyncSequence(Connection c, String t, String pk) {}
    };

    @Test
    void batchFailureIsPrimaryAndCloseFailureIsSuppressedNotLost() throws Exception {
        PreparedStatement fakeStmt = fakePreparedStatement();
        Connection fakeConn = fakeConnection(fakeStmt);

        BulkDatabaseLoadStage stage = new BulkDatabaseLoadStage(
            NOOP_DIALECT, fakeConn, "t", List.of("col"), false, null);
        stage.insertRow(new Object[] {"x"}); // batchCount > 0, so close() actually calls executeBatch()

        SQLException thrown = assertThrows(SQLException.class, stage::close,
            "close() must propagate the batch failure, not swallow it");

        assertEquals("23505", thrown.getSQLState(), "the batch failure must be the PRIMARY exception");
        assertTrue(java.util.Arrays.stream(thrown.getSuppressed())
                .anyMatch(s -> s instanceof SQLException se && "08003".equals(se.getSQLState())),
            "the statement-close failure must be kept as a suppressed exception, not discarded");
    }

    private static PreparedStatement fakePreparedStatement() {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "executeBatch" -> throw BATCH_FAILURE;
            case "close" -> throw CLOSE_FAILURE;
            case "addBatch", "setObject" -> null;
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "FakePreparedStatement";
            default -> null;
        };
        return (PreparedStatement) Proxy.newProxyInstance(
            BulkDatabaseLoadStageTest.class.getClassLoader(), new Class<?>[] {PreparedStatement.class}, handler);
    }

    private static Connection fakeConnection(PreparedStatement stmt) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "prepareStatement" -> stmt;
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "FakeConnection";
            default -> null;
        };
        return (Connection) Proxy.newProxyInstance(
            BulkDatabaseLoadStageTest.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
    }
}
