package org.identigon.incognito.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Regression test: {@link PostgresDialectHandler#dropForeignKeysReferencing} and
 * {@link PostgresDialectHandler#recreateForeignKeys} must quote the TABLE name, not just the
 * constraint name, in the {@code ALTER TABLE ... DROP/ADD CONSTRAINT} statements they build.
 * Exercises the dialect handler directly (not the full pipeline) against a mixed-case table name
 * that requires quoting to isolate this fix from the rest of the engine's broader, unrelated,
 * out-of-scope unquoted-identifier surface (insert SQL, sequence resync, etc.).
 *
 * <p>Requires Docker; skips gracefully otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresDialectHandlerFkQuotingE2ETest {

    private PostgreSQLContainer pg;
    private Connection conn;

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
            .withDatabaseName("fk_quoting").withUsername("test").withPassword("test");
        pg.start();

        conn = DriverManager.getConnection(pg.getJdbcUrl(), "test", "test");
        conn.setAutoCommit(true);
        try (Statement stmt = conn.createStatement()) {
            // A mixed-case table name: PostgreSQL folds unquoted identifiers to lowercase, so this
            // one is only reachable when properly double-quoted everywhere.
            stmt.execute("""
                CREATE TABLE "MixedCaseEmployee" (
                    id    INT PRIMARY KEY,
                    boss  INT REFERENCES "MixedCaseEmployee"(id)
                );
                """);
        }
    }

    @AfterAll
    void tearDown() throws SQLException {
        if (conn != null) conn.close();
        if (pg != null) pg.stop();
    }

    @Test
    void dropAndRecreateQuoteTheTableName() throws Exception {
        Assumptions.assumeTrue(conn != null, "Docker/PostgreSQL not available");

        PostgresDialectHandler handler = new PostgresDialectHandler();

        List<DialectHandler.DroppedForeignKey> dropped =
            handler.dropForeignKeysReferencing(conn, Set.of("MixedCaseEmployee"));
        assertEquals(1, dropped.size(), "the self-referential FK should be captured");
        assertEquals(0, countForeignKeys(), "the FK constraint must actually be dropped");

        handler.recreateForeignKeys(conn, dropped);
        assertEquals(1, countForeignKeys(), "the FK constraint must be recreated");
    }

    private long countForeignKeys() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT COUNT(*) FROM pg_constraint WHERE conrelid = '\"MixedCaseEmployee\"'::regclass AND contype = 'f'")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
