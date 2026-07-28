package io.github.dconneely.incognito;

import io.github.dconneely.incognito.api.ColumnRole;
import io.github.dconneely.incognito.api.DirectIdStrategy;
import io.github.dconneely.incognito.api.IncognitoException;
import io.github.dconneely.incognito.api.IncognitoPipeline;
import io.github.dconneely.incognito.api.PipelineResult;
import io.github.dconneely.incognito.api.QuasiIdStrategy;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2 Walking Skeleton: end-to-end vertical slice through the full pipeline.
 *
 * <p>Schema: 2 tables ({@code users} parent, {@code orders} child; single-column PKs; one FK).
 * <p>Asserts: FK integrity holds in target, sequences work on new inserts,
 *    direct IDs replaced with fictional reserved domains.
 * <p>Fail-closed: an unclassified column aborts the run with {@code ConfigException}.
 *
 * <p>Requires Docker. Tests are skipped (not failed) if Docker is unavailable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WalkingSkeletonTest {

    private PostgreSQLContainer pg;
    private DataSource sourceDs;
    private DataSource targetDs;

    @BeforeAll
    void setUp() {
        // Check Docker availability — skip gracefully if absent.
        boolean dockerAvailable;
        try {
            dockerAvailable = org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            dockerAvailable = false;
        }
        Assumptions.assumeTrue(dockerAvailable,
            "Docker is not available — skipping Testcontainers integration tests");

        try {
            pg = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("incognito_source")
                .withUsername("test")
                .withPassword("test");
            pg.start();

            // Create the source schema and seed data.
            try (Connection conn = DriverManager.getConnection(
                    pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);

                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("""
                        CREATE TABLE users (
                            id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                            email    VARCHAR(255) NOT NULL,
                            dob      DATE NOT NULL,
                            status   VARCHAR(50) NOT NULL
                        )
                        """);
                    stmt.execute("""
                        CREATE TABLE orders (
                            id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                            user_id     BIGINT NOT NULL REFERENCES users(id),
                            amount      NUMERIC(12,2) NOT NULL,
                            order_date  DATE NOT NULL
                        )
                        """);

                    // Seed source data.
                    stmt.execute("INSERT INTO users (email, dob, status) VALUES "
                        + "('alice@realcorp.com', '1990-03-15', 'ACTIVE'),"
                        + "('bob@realcorp.com', '1985-07-22', 'INACTIVE'),"
                        + "('charlie@realcorp.com', '1992-11-08', 'ACTIVE')");

                    stmt.execute("INSERT INTO orders (user_id, amount, order_date) VALUES "
                        + "(1, 99.99, '2024-01-15'),"
                        + "(1, 149.50, '2024-02-20'),"
                        + "(2, 200.00, '2024-03-10'),"
                        + "(3, 50.00, '2024-04-05'),"
                        + "(3, 75.25, '2024-04-12')");
                }
            }

            // Create the target database. Build URLs from host/port — getJdbcUrl() carries query
            // params (e.g. ?loggerLevel=OFF), so appending a db name to it produces a bad URL.
            String jdbcBase = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/";
            try (Connection adminConn = DriverManager.getConnection(
                    jdbcBase + "postgres", pg.getUsername(), pg.getPassword())) {
                adminConn.setAutoCommit(true);
                try (Statement stmt = adminConn.createStatement()) {
                    stmt.execute("CREATE DATABASE incognito_target");
                }
            }

            // Create the same schema in the target (schema-identical clone).
            String targetUrl = jdbcBase + "incognito_target";
            try (Connection conn = DriverManager.getConnection(
                    targetUrl, pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("""
                        CREATE TABLE users (
                            id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                            email    VARCHAR(255) NOT NULL,
                            dob      DATE NOT NULL,
                            status   VARCHAR(50) NOT NULL
                        )
                        """);
                    stmt.execute("""
                        CREATE TABLE orders (
                            id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                            user_id     BIGINT NOT NULL REFERENCES users(id),
                            amount      NUMERIC(12,2) NOT NULL,
                            order_date  DATE NOT NULL
                        )
                        """);
                }
            }

            // Build DataSources.
            sourceDs = createDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            targetDs = createDataSource(targetUrl, pg.getUsername(), pg.getPassword());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set up test databases", e);
        }
    }

    @AfterAll
    void tearDown() {
        if (pg != null) {
            pg.stop();
        }
    }

    @Test
    void endToEndAnonymisation() throws Exception {
        Assumptions.assumeTrue(sourceDs != null, "Docker/PostgreSQL not available");

        // Build the anonymisation policy.
        AnonymisationPolicy policy = AnonymisationPolicy.builder()
            .table("users", t -> t
                .column("id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column("email", ColumnRole.DIRECT_ID, DirectIdStrategy.ALTEREGO_EMAIL)
                .column("dob", ColumnRole.QUASI_ID, QuasiIdStrategy.SYNTHESISE)
                .column("status", ColumnRole.PAYLOAD))
            .table("orders", t -> t
                .column("id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column(ColumnPolicy.builder("user_id")
                    .role(ColumnRole.FOREIGN_KEY)
                    .references("users", "id")
                    .build())
                .column("amount", ColumnRole.PAYLOAD)
                .column("order_date", ColumnRole.PAYLOAD))
            .build();

        // Build and execute the pipeline.
        PipelineResult result = IncognitoPipeline.builder()
            .source(sourceDs)
            .target(targetDs)
            .ephemeralSalt()
            .policy(policy)
            .stage(new SchemaDiscoveryStage())
            .stage(new TableTransformLoadStage())
            .stage(new VerificationStage())
            .build()
            .execute();

        assertTrue(result.success(), "Pipeline should succeed");

        // --- Assertions ---
        try (Connection conn = targetDs.getConnection()) {
            // 1. Row counts match source.
            assertEquals(3, countRows(conn, "users"), "users row count");
            assertEquals(5, countRows(conn, "orders"), "orders row count");

            // 2. FK integrity: every orders.user_id references an existing users.id.
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM orders o "
                         + "WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = o.user_id)")) {
                rs.next();
                assertEquals(0, rs.getLong(1), "No dangling FK references");
            }

            // 3. Fictionality: all emails use RFC 2606 reserved domains.
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT email FROM users WHERE email NOT LIKE '%@example.com' "
                         + "AND email NOT LIKE '%@example.net' "
                         + "AND email NOT LIKE '%@example.org' "
                         + "AND email NOT LIKE '%@example.co.uk' "
                         + "AND email NOT LIKE '%@example.org.uk'")) {
                boolean foundNonReserved = rs.next();
                String offending = foundNonReserved ? rs.getString(1) : "none";
                assertFalse(foundNonReserved,
                    "All emails should use RFC 2606 reserved domains, but found: " + offending);
            }

            // 4. No real email survived.
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM users WHERE email LIKE '%@realcorp.com'")) {
                rs.next();
                assertEquals(0, rs.getLong(1), "No real emails should survive");
            }

            // 5. Dates were jittered (dob should be non-null after transformation).
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT dob FROM users WHERE dob IS NULL")) {
                assertFalse(rs.next(), "All dob values should be non-null after jitter");
            }

            // 6. Sequences work: inserting a new row with default PK should succeed.
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO users (email, dob, status) VALUES "
                    + "('new@example.com', '2000-01-01', 'ACTIVE')");
            }
            assertEquals(4, countRows(conn, "users"),
                "New row should be insertable after sequence resync");

            // 7. Operational data preserved: amounts should be unchanged.
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT SUM(amount) FROM orders")) {
                rs.next();
                assertEquals(574.74, rs.getDouble(1), 0.01,
                    "Operational PAYLOAD amounts should be preserved");
            }
        }
    }

    @Test
    void failClosedOnUnclassifiedColumn() {
        Assumptions.assumeTrue(sourceDs != null, "Docker/PostgreSQL not available");

        // Build a policy that deliberately omits the 'status' column from 'users'.
        AnonymisationPolicy incompletePolicy = AnonymisationPolicy.builder()
            .table("users", t -> t
                .column("id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column("email", ColumnRole.DIRECT_ID, DirectIdStrategy.ALTEREGO_EMAIL)
                .column("dob", ColumnRole.QUASI_ID, QuasiIdStrategy.SYNTHESISE))
                // 'status' deliberately missing → fail-closed
            .build();

        assertThrows(IncognitoException.ConfigException.class, () ->
            IncognitoPipeline.builder()
                .source(sourceDs)
                .target(targetDs)
                .ephemeralSalt()
                .policy(incompletePolicy)
                .stage(new SchemaDiscoveryStage())
                .stage(new TableTransformLoadStage())
                .build()
                .execute(),
            "Should fail with ConfigException when a column has no declared role");
    }

    // --- Helpers ---

    private long countRows(Connection conn, String table) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private DataSource createDataSource(String url, String user, String password) {
        return new SimpleDataSource(url, user, password);
    }

    /**
     * Minimal DataSource implementation for testing — wraps DriverManager.
     */
    private record SimpleDataSource(String url, String user, String password) implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, user, password);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public void setLoginTimeout(int seconds) {}
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("Not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
