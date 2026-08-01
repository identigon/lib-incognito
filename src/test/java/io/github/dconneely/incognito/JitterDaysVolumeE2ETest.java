package io.github.dconneely.incognito;

import io.github.dconneely.incognito.api.ColumnRole;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code JITTER_DAYS} deliberately shifts a date by up to ±N days, so it routinely crosses month
 * boundaries and does <b>not</b> preserve monthly buckets — verifying monthly counts against a ±2%
 * tolerance therefore raised spurious "volume drift" warnings. The VerificationStage now checks
 * <b>yearly</b> buckets for {@code JITTER_DAYS}. This test concentrates every row on the last day of
 * a mid-year month: the jitter spills many rows into the next month (monthly buckets would flag) but
 * none out of the year, so no drift warning must be raised. Requires Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JitterDaysVolumeE2ETest {

    private static final String DDL = """
        CREATE TABLE spikes (
            id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            ts  DATE NOT NULL
        );
        """;

    // Fixed salt + seed so the (salt-keyed) jitter is deterministic and the test is stable.
    private static final byte[] SALT = "0123456789abcdef0123456789abcdef".getBytes();

    private PostgreSQLContainer pg;
    private DataSource sourceDs;
    private DataSource targetDs;

    @BeforeAll
    void setUp() throws Exception {
        boolean dockerAvailable;
        try {
            dockerAvailable = org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            dockerAvailable = false;
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker not available — skipping Testcontainers E2E");

        pg = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("jd_source").withUsername("test").withPassword("test");
        pg.start();

        try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), "test", "test");
             Statement stmt = conn.createStatement()) {
            stmt.execute(DDL);
            // 20 distinct late-June dates (5 rows each), mid-year and far from any year boundary. The
            // dates are distinct because JITTER_DAYS is value-keyed — identical dates shift identically.
            stmt.execute("INSERT INTO spikes (ts) SELECT DATE '2024-06-11' + (n % 20) FROM generate_series(1, 100) n");
        }

        String jdbcBase = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/";
        try (Connection admin = DriverManager.getConnection(jdbcBase + "postgres", "test", "test");
             Statement stmt = admin.createStatement()) {
            stmt.execute("CREATE DATABASE jd_target");
        }
        String targetUrl = jdbcBase + "jd_target";
        try (Connection conn = DriverManager.getConnection(targetUrl, "test", "test");
             Statement stmt = conn.createStatement()) {
            stmt.execute(DDL);
        }

        sourceDs = new SimpleDataSource(pg.getJdbcUrl(), "test", "test");
        targetDs = new SimpleDataSource(targetUrl, "test", "test");
    }

    @AfterAll
    void tearDown() {
        if (pg != null) pg.stop();
    }

    @Test
    void jitterDaysAcrossMonthBoundaryRaisesNoDriftWarning() throws Exception {
        Assumptions.assumeTrue(sourceDs != null, "Docker/PostgreSQL not available");

        AnonymisationPolicy policy = AnonymisationPolicy.builder()
            .table("spikes", t -> t
                .column("id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column(ColumnPolicy.builder("ts").role(ColumnRole.QUASI_ID)
                    .quasiIdStrategy(QuasiIdStrategy.JITTER_DAYS).jitterDays(20).build()))
            .build();

        PipelineResult result = IncognitoPipeline.builder()
            .source(sourceDs).target(targetDs).reproducible(SALT, 7L).policy(policy)
            .stage(new SchemaDiscoveryStage())
            .stage(new TableTransformLoadStage())
            .stage(new VerificationStage())
            .build().execute();
        assertTrue(result.success(), "pipeline should succeed");

        try (Connection conn = targetDs.getConnection()) {
            // The jitter genuinely crossed the month boundary — so a monthly check WOULD have drifted...
            assertTrue(scalar(conn, "SELECT COUNT(*) FROM spikes WHERE ts >= DATE '2024-07-01'") > 0,
                "some rows must have spilled into July");
            // ...yet the year is fully preserved.
            assertEquals(100, scalar(conn, "SELECT COUNT(*) FROM spikes WHERE date_trunc('year', ts) = DATE '2024-01-01'"),
                "all rows stay within the year");
        }

        // The yearly-bucket check must not raise a spurious drift warning for JITTER_DAYS.
        String verifyMsg = result.report().stageResults().stream()
            .filter(r -> r.stageName().equals("VerificationStage")).findFirst().orElseThrow().message();
        assertFalse(verifyMsg.contains("Volume drift"),
            "JITTER_DAYS crossing a month boundary must not raise a volume-drift warning, but got: " + verifyMsg);
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
