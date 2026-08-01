package io.github.dconneely.incognito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dconneely.incognito.api.ColumnRole;
import io.github.dconneely.incognito.api.DirectIdStrategy;
import io.github.dconneely.incognito.api.IncognitoPipeline;
import io.github.dconneely.incognito.api.PipelineResult;
import io.github.dconneely.incognito.api.SurrogateStrategy;
import io.github.dconneely.incognito.core.SchemaDiscoveryStage;
import io.github.dconneely.incognito.core.TableTransformLoadStage;
import io.github.dconneely.incognito.core.VerificationStage;
import io.github.dconneely.incognito.policy.AnonymisationPolicy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Views and materialized views must be excluded from discovery (SPEC §7.2) — Pagila has both.
 * {@code SchemaInspector} filters {@code getTables} to type {@code TABLE}; this proves a plain
 * {@code VIEW} and a {@code MATERIALIZED VIEW} are neither classified, loaded, nor reported.
 * Requires Docker; skips gracefully otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ViewExclusionE2ETest {

    private PostgreSQLContainer pg;
    private DataSource sourceDs;
    private DataSource targetDs;

    private static final String DDL = """
        CREATE TABLE person (
            id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            email  VARCHAR(255) NOT NULL
        );
        CREATE VIEW person_v AS SELECT id, email FROM person;
        CREATE MATERIALIZED VIEW person_mv AS SELECT id, email FROM person;
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
                .withDatabaseName("view_source").withUsername("test").withPassword("test");
            pg.start();

            try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(DDL);
                    stmt.execute("INSERT INTO person (email) VALUES ('a@realcorp.com'), ('b@realcorp.com')");
                    stmt.execute("REFRESH MATERIALIZED VIEW person_mv");
                }
            }

            String jdbcBase = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/";
            try (Connection admin = DriverManager.getConnection(jdbcBase + "postgres", pg.getUsername(), pg.getPassword())) {
                admin.setAutoCommit(true);
                try (Statement stmt = admin.createStatement()) {
                    stmt.execute("CREATE DATABASE view_target");
                }
            }
            String targetUrl = jdbcBase + "view_target";
            try (Connection conn = DriverManager.getConnection(targetUrl, pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(DDL);
                }
            }

            sourceDs = new SimpleDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            targetDs = new SimpleDataSource(targetUrl, pg.getUsername(), pg.getPassword());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set up view-exclusion E2E databases", e);
        }
    }

    @AfterAll
    void tearDown() {
        if (pg != null) pg.stop();
    }

    @Test
    void viewsAreExcludedFromDiscoveryAndLoad() throws Exception {
        Assumptions.assumeTrue(sourceDs != null, "Docker/PostgreSQL not available");

        // Policy classifies ONLY the base table. If a view leaked into discovery, fail-closed
        // classification would abort (its columns are unclassified) — so success alone is meaningful.
        AnonymisationPolicy policy = AnonymisationPolicy.builder()
            .table("person", t -> t
                .column("id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column("email", ColumnRole.DIRECT_ID, DirectIdStrategy.ALTEREGO_EMAIL))
            .build();

        PipelineResult result = IncognitoPipeline.builder()
            .source(sourceDs).target(targetDs).ephemeralSalt().policy(policy)
            .stage(new SchemaDiscoveryStage())
            .stage(new TableTransformLoadStage())
            .stage(new VerificationStage())
            .build().execute();
        assertTrue(result.success(), "pipeline should succeed (views excluded, only base table processed)");

        // The report must mention only the base table, never the views.
        List<String> reported = result.report().tables().stream()
            .map(io.github.dconneely.incognito.api.AnonymisationReport.TableReport::table).toList();
        assertTrue(reported.contains("person"), "base table processed");
        assertFalse(reported.contains("person_v"), "plain VIEW must not be processed");
        assertFalse(reported.contains("person_mv"), "MATERIALIZED VIEW must not be processed");

        try (Connection conn = targetDs.getConnection()) {
            assertEquals(2, scalar(conn, "SELECT COUNT(*) FROM person"), "base table loaded");
            // The plain view reflects the loaded (fabricated) base table automatically.
            assertEquals(0, scalar(conn, "SELECT COUNT(*) FROM person_v WHERE email LIKE '%@realcorp.com'"),
                "no real email visible through the view — fabrication applied to the base table");
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
