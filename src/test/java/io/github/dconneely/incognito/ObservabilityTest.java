package io.github.dconneely.incognito;

import io.github.dconneely.incognito.api.PipelineContext;
import io.github.dconneely.incognito.core.DefaultPipelineContext;
import io.github.dconneely.incognito.core.IncognitoCleanUpHandler;
import io.github.dconneely.incognito.engine.TableDependencyGraph;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the observability behaviour of the swallowed compensation failures: a best-effort
 * clean-up step that fails is surfaced as a {@code System.Logger} WARNING (so it is not invisible),
 * yet is logged <b>coarsely</b> — operation, table and SQLState only, never the exception message,
 * which must never carry a field value (SPEC §7.3 / hard invariant 3). No Docker required.
 */
class ObservabilityTest {

    /** A {@link DataSource} whose {@link #getConnection()} always fails, to force the compensate path. */
    private record FailingDataSource(String secretMessage, String sqlState) implements DataSource {
        @Override public Connection getConnection() throws SQLException { throw new SQLException(secretMessage, sqlState); }
        @Override public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public void setLoginTimeout(int seconds) {}
        @Override public java.util.logging.Logger getParentLogger() { return Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("Not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    @Test
    void compensationConnectFailureIsLoggedCoarsely() {
        String secret = "host=db.internal user=admin password=hunter2";   // must never reach the log
        String sqlState = "08001";
        var plan = new TableDependencyGraph.TopologicalExecutionPlan(List.of("some_table"), List.of());
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("incognito.schema.executionPlan", plan);
        PipelineContext ctx = new DefaultPipelineContext(
            null, new FailingDataSource(secret, sqlState), null, null, null, null, attrs);

        Logger jul = Logger.getLogger(IncognitoCleanUpHandler.class.getName());
        List<LogRecord> captured = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord r) { captured.add(r); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Level previous = jul.getLevel();
        jul.setLevel(Level.ALL);
        jul.addHandler(handler);
        try {
            // Best-effort: must never throw, even though it can do nothing.
            IncognitoCleanUpHandler.compensate(ctx);
        } finally {
            jul.removeHandler(handler);
            jul.setLevel(previous);
        }

        LogRecord warning = captured.stream()
            .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
            .findFirst().orElseThrow(() -> new AssertionError("no WARNING was logged for the connect failure"));

        String message = new SimpleFormatter().formatMessage(warning);
        assertTrue(message.contains("connect"), "warning names the failed operation: " + message);
        assertTrue(message.contains(sqlState), "warning includes the SQLState for diagnosis: " + message);
        assertFalse(message.contains("hunter2"), "the raw exception message (with secrets) must not be logged: " + message);
        assertFalse(message.contains(secret), "no field/connection detail leaks into the log: " + message);
    }
}
