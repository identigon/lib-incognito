package io.github.dconneely.incognito;

import io.github.dconneely.incognito.api.ColumnRole;
import io.github.dconneely.incognito.api.DirectIdStrategy;
import io.github.dconneely.incognito.api.IncognitoPipeline;
import io.github.dconneely.incognito.api.PipelineResult;
import io.github.dconneely.incognito.api.SurrogateStrategy;
import io.github.dconneely.incognito.core.SchemaDiscoveryStage;
import io.github.dconneely.incognito.core.TableTransformLoadStage;
import io.github.dconneely.incognito.core.VerificationStage;
import io.github.dconneely.incognito.policy.AnonymisationPolicy;
import io.github.dconneely.incognito.policy.ColumnPolicy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * De-risking E2E for cyclic (self-referential) foreign keys — the untested Phase-6 machinery
 * (Tarjan SCC detection, {@code CyclicFkException} deferral + placeholder insert, and the Pass-2
 * {@code UPDATE} in {@link io.github.dconneely.incognito.core.BulkDatabaseLoadStage}).
 *
 * <p>Schema: {@code employee(id, name, buddy_id)} with {@code buddy_id → employee(id)}. Buddies
 * are seeded as <b>mutual pairs</b> ({@code 1↔2}, {@code 3↔4}), which form a genuine 2-cycle: no
 * topological processing order exists, so at least one row per pair is always a forward reference
 * to a not-yet-loaded row and <b>must</b> be deferred — guaranteeing the cyclic path runs regardless
 * of scan order. Employee 5 has no buddy (null FK).
 *
 * <p>Asserts the placeholder is fully resolved (no {@code -1} survives), referential integrity holds,
 * and the mutual-buddy topology is preserved through surrogate remapping.
 *
 * <p>Requires Docker; skips gracefully otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CyclicFkE2ETest {

    private PostgreSQLContainer pg;
    private DataSource sourceDs;
    private DataSource targetDs;

    private static final String DDL = """
        CREATE TABLE employee (
            id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            name      VARCHAR(100) NOT NULL,
            buddy_id  BIGINT REFERENCES employee(id)
        );
        """;

    @BeforeAll
    void setUp() {
        boolean dockerAvailable;
        try {
            dockerAvailable = org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            dockerAvailable = false;
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker not available — skipping Testcontainers E2E");

        try {
            pg = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("cyclic_source").withUsername("test").withPassword("test");
            pg.start();

            try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(DDL);
                    // Insert with null buddies (IDENTITY assigns ids 1..5), then wire up the mutual
                    // pairs via UPDATE — a self-ref FK can't be set to an id that doesn't exist yet.
                    stmt.execute("INSERT INTO employee (name) VALUES "
                        + "('Alice'), ('Bob'), ('Carol'), ('Dave'), ('Erin')");
                    stmt.execute("UPDATE employee SET buddy_id = 2 WHERE id = 1"); // 1 -> 2
                    stmt.execute("UPDATE employee SET buddy_id = 1 WHERE id = 2"); // 2 -> 1  (cycle)
                    stmt.execute("UPDATE employee SET buddy_id = 4 WHERE id = 3"); // 3 -> 4
                    stmt.execute("UPDATE employee SET buddy_id = 3 WHERE id = 4"); // 4 -> 3  (cycle)
                    // id 5: no buddy (null)
                }
            }

            String jdbcBase = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/";
            try (Connection admin = DriverManager.getConnection(jdbcBase + "postgres", pg.getUsername(), pg.getPassword())) {
                admin.setAutoCommit(true);
                try (Statement stmt = admin.createStatement()) {
                    stmt.execute("CREATE DATABASE cyclic_target");
                }
            }
            String targetUrl = jdbcBase + "cyclic_target";
            try (Connection conn = DriverManager.getConnection(targetUrl, pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(DDL);
                }
            }

            sourceDs = new SimpleDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            targetDs = new SimpleDataSource(targetUrl, pg.getUsername(), pg.getPassword());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set up cyclic-FK E2E databases", e);
        }
    }

    @AfterAll
    void tearDown() {
        if (pg != null) pg.stop();
    }

    private AnonymisationPolicy policy() {
        return AnonymisationPolicy.builder()
            .table("employee", t -> t
                .column("id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column("name", ColumnRole.DIRECT_ID, DirectIdStrategy.ALTEREGO_GENERIC)
                .column(ColumnPolicy.builder("buddy_id").role(ColumnRole.FOREIGN_KEY)
                    .references("employee", "id").build()))
            .build();
    }

    @Test
    void selfReferentialCycleResolves() throws Exception {
        Assumptions.assumeTrue(sourceDs != null, "Docker/PostgreSQL not available");

        PipelineResult result = IncognitoPipeline.builder()
            .source(sourceDs).target(targetDs).ephemeralSalt().policy(policy())
            .stage(new SchemaDiscoveryStage())
            .stage(new TableTransformLoadStage())
            .stage(new VerificationStage())
            .build().execute();
        assertTrue(result.success(), "pipeline should succeed");

        try (Connection conn = targetDs.getConnection()) {
            assertEquals(5, scalar(conn, "SELECT COUNT(*) FROM employee"), "row count preserved");

            // Pass-2 resolution: no placeholder (-1) may survive.
            assertEquals(0, scalar(conn, "SELECT COUNT(*) FROM employee WHERE buddy_id = -1"),
                "cyclic-FK placeholder must be resolved by the Pass-2 UPDATE");

            // Referential integrity: every non-null buddy_id points at a real employee.
            assertEquals(0, scalar(conn,
                "SELECT COUNT(*) FROM employee e WHERE e.buddy_id IS NOT NULL "
                    + "AND NOT EXISTS (SELECT 1 FROM employee b WHERE b.id = e.buddy_id)"),
                "no dangling buddy_id");

            // Topology preserved through surrogate remapping: exactly one employee has no buddy,
            // and every buddy relationship is still mutual (a → b implies b → a).
            assertEquals(1, scalar(conn, "SELECT COUNT(*) FROM employee WHERE buddy_id IS NULL"),
                "the un-buddied employee survives");
            assertEquals(0, scalar(conn,
                "SELECT COUNT(*) FROM employee e WHERE e.buddy_id IS NOT NULL "
                    + "AND NOT EXISTS (SELECT 1 FROM employee b WHERE b.id = e.buddy_id AND b.buddy_id = e.id)"),
                "mutual buddy pairs must survive remapping (a->b implies b->a)");
        }
    }

    private long scalar(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private record SimpleDataSource(String url, String user, String password) implements DataSource {
        @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url, user, password); }
        @Override public Connection getConnection(String u, String p) throws SQLException { return DriverManager.getConnection(url, u, p); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public void setLoginTimeout(int seconds) {}
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("Not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
