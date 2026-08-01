package io.github.dconneely.incognito.core;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dconneely.incognito.api.ColumnRole;
import io.github.dconneely.incognito.api.DirectIdStrategy;
import io.github.dconneely.incognito.api.DistinguishingLint;
import io.github.dconneely.incognito.api.IncognitoException;
import io.github.dconneely.incognito.api.IncognitoPipeline;
import io.github.dconneely.incognito.api.PipelineResult;
import io.github.dconneely.incognito.api.QuasiIdStrategy;
import io.github.dconneely.incognito.api.SurrogateStrategy;
import io.github.dconneely.incognito.policy.AnonymisationPolicy;
import io.github.dconneely.incognito.policy.ColumnPolicy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Tests the {@code distinguishingLint} runtime check in {@link VerificationStage} (SPEC §4.1).
 *
 * <p>Uses a table with two SENSITIVE columns:
 * <ul>
 *   <li>{@code flag} — low cardinality (2 values): correctly declared {@code distinguishing: false}.</li>
 *   <li>{@code notes} — high cardinality (100 distinct values): mis-declared
 *       {@code distinguishing: false} — should trigger the lint.</li>
 * </ul>
 *
 * <p>Verifies all three modes: WARN continues with a warning, ERROR throws, OFF skips silently.
 * Requires Docker; skips gracefully otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DistinguishingLintTest {

    private PostgreSQLContainer pg;
    private DataSource sourceDs;
    private DataSource targetDs;

    @BeforeAll
    void setUp() {
        boolean dockerAvailable;
        try {
            dockerAvailable = org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            dockerAvailable = false;
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker not available — skipping lint E2E tests");

        try {
            pg = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("lint_source").withUsername("test").withPassword("test");
            pg.start();

            try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("""
                        CREATE TABLE customers (
                            id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                            email   VARCHAR(255) NOT NULL,
                            dob     DATE NOT NULL,
                            flag    VARCHAR(10) NOT NULL,
                            notes   VARCHAR(200) NOT NULL
                        )
                        """);
                    // Seed 100 rows: flag alternates between 2 values; notes has a distinct value
                    // per row (100 distinct values >> default threshold of 64).
                    StringBuilder sb = new StringBuilder("INSERT INTO customers (email, dob, flag, notes) VALUES ");
                    for (int i = 0; i < 100; i++) {
                        if (i > 0) sb.append(",");
                        String flagVal = (i % 2 == 0) ? "YES" : "NO";
                        sb.append(String.format("('user%d@realcorp.com', '1990-01-01', '%s', 'Note #%03d unique text')",
                            i, flagVal, i));
                    }
                    stmt.execute(sb.toString());

                    // ANALYZE so pg_stats is populated for the pre-filter test.
                    stmt.execute("ANALYZE customers");
                }
            }

            String jdbcBase = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/";
            try (Connection admin = DriverManager.getConnection(jdbcBase + "postgres", pg.getUsername(), pg.getPassword())) {
                admin.setAutoCommit(true);
                try (Statement stmt = admin.createStatement()) {
                    stmt.execute("CREATE DATABASE lint_target");
                }
            }
            String targetUrl = jdbcBase + "lint_target";
            try (Connection conn = DriverManager.getConnection(targetUrl, pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("""
                        CREATE TABLE customers (
                            id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                            email   VARCHAR(255) NOT NULL,
                            dob     DATE NOT NULL,
                            flag    VARCHAR(10) NOT NULL,
                            notes   VARCHAR(200) NOT NULL
                        )
                        """);
                }
            }

            sourceDs = new SimpleDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            targetDs = new SimpleDataSource(targetUrl, pg.getUsername(), pg.getPassword());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set up lint test databases", e);
        }
    }

    @AfterAll
    void tearDown() {
        if (pg != null) pg.stop();
    }

    /** Build a policy where both SENSITIVE columns are declared distinguishing:false. */
    private AnonymisationPolicy policy(DistinguishingLint lintMode) {
        return AnonymisationPolicy.builder()
            .distinguishingLint(lintMode)
            .table("customers", t -> t
                .column("id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column("email", ColumnRole.DIRECT_ID, DirectIdStrategy.ALTEREGO_EMAIL)
                .column("dob", ColumnRole.QUASI_ID, QuasiIdStrategy.SYNTHESISE)
                .column(ColumnPolicy.builder("flag").role(ColumnRole.SENSITIVE).distinguishing(false).build())
                .column(ColumnPolicy.builder("notes").role(ColumnRole.SENSITIVE).distinguishing(false).build()))
            .build();
    }

    private void truncateTarget() throws SQLException {
        try (Connection conn = targetDs.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE customers RESTART IDENTITY");
        }
    }

    @Test
    void warnMode_completesWithWarning() throws Exception {
        Assumptions.assumeTrue(sourceDs != null, "Docker/PostgreSQL not available");
        truncateTarget();

        PipelineResult result = IncognitoPipeline.builder()
            .source(sourceDs).target(targetDs).ephemeralSalt().policy(policy(DistinguishingLint.WARN))
            .stage(new SchemaDiscoveryStage())
            .stage(new TableTransformLoadStage())
            .stage(new VerificationStage())
            .build().execute();

        assertTrue(result.success(), "Pipeline should succeed in WARN mode");
        // The VerificationStage result should contain a warning about 'notes'.
        var verificationResult = result.report().stageResults().stream()
            .filter(r -> r.stageName().equals("VerificationStage"))
            .findFirst().orElseThrow();
        assertTrue(verificationResult.message().contains("Misdeclaration lint"),
            "VerificationStage message should contain misdeclaration warning for 'notes'");
        assertTrue(verificationResult.message().contains("notes"),
            "Warning should mention the 'notes' column");
        // 'flag' has only 2 distinct values (below threshold 64), so no warning for it.
        assertFalse(verificationResult.message().contains("flag"),
            "Low-cardinality 'flag' should not trigger the lint");
    }

    @Test
    void errorMode_failsOnMisdeclaredColumn() {
        Assumptions.assumeTrue(sourceDs != null, "Docker/PostgreSQL not available");
        try { truncateTarget(); } catch (SQLException ignored) {}

        assertThrows(IncognitoException.ConstraintException.class, () ->
            IncognitoPipeline.builder()
                .source(sourceDs).target(targetDs).ephemeralSalt().policy(policy(DistinguishingLint.ERROR))
                .stage(new SchemaDiscoveryStage())
                .stage(new TableTransformLoadStage())
                .stage(new VerificationStage())
                .build().execute(),
            "ERROR lint mode should throw ConstraintException for high-cardinality 'notes'");
    }

    @Test
    void offMode_skipsCheckEntirely() throws Exception {
        Assumptions.assumeTrue(sourceDs != null, "Docker/PostgreSQL not available");
        truncateTarget();

        PipelineResult result = IncognitoPipeline.builder()
            .source(sourceDs).target(targetDs).ephemeralSalt().policy(policy(DistinguishingLint.OFF))
            .stage(new SchemaDiscoveryStage())
            .stage(new TableTransformLoadStage())
            .stage(new VerificationStage())
            .build().execute();

        assertTrue(result.success(), "Pipeline should succeed in OFF mode");
        var verificationResult = result.report().stageResults().stream()
            .filter(r -> r.stageName().equals("VerificationStage"))
            .findFirst().orElseThrow();
        assertFalse(verificationResult.message().contains("Misdeclaration"),
            "OFF mode should produce no misdeclaration warnings");
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
