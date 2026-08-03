package org.identigon.incognito.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Regression test: {@link PostgresDialectHandler#preLoadTable} must not silently lose the original
 * "insufficient privilege" exception when its owner-mode fallback (ALTER TABLE ... DISABLE TRIGGER
 * USER) also fails — both must be visible to whoever debugs the failure.
 *
 * <p>A non-superuser connection reaches the fallback (SET session_replication_role fails with
 * SQLState 42501); pointing it at a table that does not exist makes the fallback statement itself
 * fail too (SQLState 42P01), which previously would have propagated alone, hiding the real
 * "why are we even in owner-mode fallback" context.
 *
 * <p>Requires Docker; skips gracefully otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresDialectHandlerPreLoadFailureChainingE2ETest {

    private PostgreSQLContainer pg;
    private Connection nonSuperuserConn;

    @BeforeAll
    void setUp() throws Exception {
        boolean dockerAvailable;
        try {
            dockerAvailable = org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            dockerAvailable = false;
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker not available — skipping dialect-handler E2E");

        pg = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("preload_chaining").withUsername("test").withPassword("test");
        pg.start();

        try (Connection admin = DriverManager.getConnection(pg.getJdbcUrl(), "test", "test");
             Statement stmt = admin.createStatement()) {
            stmt.execute("DROP ROLE IF EXISTS chaining_role");
            stmt.execute("CREATE ROLE chaining_role LOGIN PASSWORD 'x' NOSUPERUSER");
            stmt.execute("GRANT CONNECT ON DATABASE preload_chaining TO chaining_role");
        }

        nonSuperuserConn = DriverManager.getConnection(pg.getJdbcUrl(), "chaining_role", "x");
        nonSuperuserConn.setAutoCommit(true);
    }

    @AfterAll
    void tearDown() throws SQLException {
        if (nonSuperuserConn != null) nonSuperuserConn.close();
        if (pg != null) pg.stop();
    }

    @Test
    void fallbackFailureKeepsOriginalPermissionErrorAsSuppressed() {
        Assumptions.assumeTrue(nonSuperuserConn != null, "Docker/PostgreSQL not available");

        PostgresDialectHandler handler = new PostgresDialectHandler();

        SQLException thrown = assertThrows(SQLException.class,
            () -> handler.preLoadTable(nonSuperuserConn, "this_table_does_not_exist"),
            "the fallback ALTER TABLE on a nonexistent table must itself fail");

        // The fallback's own failure (undefined_table) is what propagates...
        assertEquals("42P01", thrown.getSQLState(), "fallback failure should be 'undefined_table'");

        // ...but the original insufficient-privilege failure that triggered the fallback must not
        // be silently lost — it should be attached as a suppressed exception.
        boolean originalCauseKept = false;
        for (Throwable suppressed : thrown.getSuppressed()) {
            if (suppressed instanceof SQLException se && "42501".equals(se.getSQLState())) {
                originalCauseKept = true;
            }
        }
        assertTrue(originalCauseKept,
            "the original 'insufficient privilege' exception must be kept as a suppressed exception, "
                + "not silently discarded when the fallback also fails");
    }
}
