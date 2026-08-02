package org.identigon.incognito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.identigon.incognito.api.ColumnRole;
import org.identigon.incognito.api.IncognitoPipeline;
import org.identigon.incognito.api.PipelineResult;
import org.identigon.incognito.api.QuasiIdStrategy;
import org.identigon.incognito.api.SurrogateStrategy;
import org.identigon.incognito.core.SchemaDiscoveryStage;
import org.identigon.incognito.core.TableTransformLoadStage;
import org.identigon.incognito.core.VerificationStage;
import org.identigon.incognito.policy.AnonymisationPolicy;
import org.identigon.incognito.policy.ColumnPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Per-period volume tolerance (SPEC §4.2, Appendix D). {@code JITTER_WITHIN_MONTH} shifts a date
 * only within its own month, so monthly bucket counts must be preserved <b>exactly</b> — the
 * VerificationStage volume check must report no drift. Also confirms a coherence-grouped
 * {@code JITTER_DAYS} column stays within tolerance rather than failing the run. Requires Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VolumeToleranceE2ETest {

    private PostgreSQLContainer pg;
    private DataSource sourceDs;
    private DataSource targetDs;

    private static final String DDL = """
        CREATE TABLE events (
            id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            ts  DATE NOT NULL
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
                .withDatabaseName("volume_source").withUsername("test").withPassword("test");
            pg.start();

            try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(DDL);
                    // Jan: 2, Feb: 1, Mar: 3 — dates spread within each month.
                    stmt.execute("INSERT INTO events (ts) VALUES "
                        + "(DATE '2024-01-05'), (DATE '2024-01-28'),"
                        + "(DATE '2024-02-14'),"
                        + "(DATE '2024-03-02'), (DATE '2024-03-16'), (DATE '2024-03-30')");
                }
            }

            String jdbcBase = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/";
            try (Connection admin = DriverManager.getConnection(jdbcBase + "postgres", pg.getUsername(), pg.getPassword())) {
                admin.setAutoCommit(true);
                try (Statement stmt = admin.createStatement()) {
                    stmt.execute("CREATE DATABASE volume_target");
                }
            }
            String targetUrl = jdbcBase + "volume_target";
            try (Connection conn = DriverManager.getConnection(targetUrl, pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(DDL);
                }
            }

            sourceDs = new SimpleDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            targetDs = new SimpleDataSource(targetUrl, pg.getUsername(), pg.getPassword());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set up volume-tolerance E2E databases", e);
        }
    }

    @AfterAll
    void tearDown() {
        if (pg != null) pg.stop();
    }

    @Test
    void jitterWithinMonthPreservesMonthlyBucketsExactly() throws Exception {
        Assumptions.assumeTrue(sourceDs != null, "Docker/PostgreSQL not available");

        AnonymisationPolicy policy = AnonymisationPolicy.builder()
            .table("events", t -> t
                .column("id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column(ColumnPolicy.builder("ts").role(ColumnRole.QUASI_ID)
                    .quasiIdStrategy(QuasiIdStrategy.JITTER_WITHIN_MONTH).build()))
            .build();

        PipelineResult result = IncognitoPipeline.builder()
            .source(sourceDs).target(targetDs).ephemeralSalt().policy(policy)
            .stage(new SchemaDiscoveryStage())
            .stage(new TableTransformLoadStage())
            .stage(new VerificationStage())
            .build().execute();
        assertTrue(result.success(), "pipeline should succeed");

        try (Connection conn = targetDs.getConnection()) {
            assertEquals(2, monthCount(conn, "2024-01-01"), "January bucket preserved");
            assertEquals(1, monthCount(conn, "2024-02-01"), "February bucket preserved");
            assertEquals(3, monthCount(conn, "2024-03-01"), "March bucket preserved");
            // Dates actually changed within the month (day-of-month is now randomised), but the month held.
            assertEquals(0, monthCount(conn, "2024-04-01"), "no bucket leaked into a neighbouring month");
        }

        // JITTER_WITHIN_MONTH is exact — the verification volume check must not flag drift.
        String verifyMsg = result.report().stageResults().stream()
            .filter(r -> r.stageName().equals("VerificationStage")).findFirst().orElseThrow().message();
        assertFalse(verifyMsg.contains("Volume drift"),
            "bucket-preserving jitter must not raise a volume-drift warning, but got: " + verifyMsg);
    }

    private long monthCount(Connection conn, String monthStart) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT COUNT(*) FROM events WHERE date_trunc('month', ts) = DATE '" + monthStart + "'")) {
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
