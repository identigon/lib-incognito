package org.identigon.incognito;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.identigon.incognito.api.RedactionStrategy;
import org.identigon.incognito.api.SurrogateStrategy;
import org.identigon.incognito.core.SchemaDiscoveryStage;
import org.identigon.incognito.core.TableTransformLoadStage;
import org.identigon.incognito.core.VerificationStage;
import org.identigon.incognito.policy.AnonymisationPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Type-aware redaction (SPEC §4.1, delegated to {@code AlterEgo.redact}/{@code constant}). Redaction
 * used to assume text — {@code CONSTANT}/{@code MASK} stringified the value, so a numeric, temporal
 * or boolean {@code SENSITIVE} column failed at insert (varchar vs the column type). This locks the
 * fix: each redacted column receives a <em>type-appropriate</em> constant that fits the column.
 * Requires Docker; skips gracefully otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedactionTypeE2ETest {

    private PostgreSQLContainer pg;
    private DataSource sourceDs;
    private DataSource targetDs;

    private static final String DDL = """
        CREATE TABLE record (
            id          SERIAL PRIMARY KEY,
            amount      INTEGER NOT NULL,
            balance     NUMERIC(10,2) NOT NULL,
            event_date  DATE NOT NULL,
            active      BOOLEAN NOT NULL,
            note        VARCHAR(20) NOT NULL
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
            pg = new PostgreSQLContainer(TestPostgres.IMAGE)
                .withDatabaseName("redact_source").withUsername("test").withPassword("test");
            pg.start();

            try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(DDL);
                    stmt.execute("INSERT INTO record (amount, balance, event_date, active, note) VALUES "
                        + "(50000, 1234.56, DATE '1987-03-14', true, 'topsecret'), "
                        + "(99999, 4321.00, DATE '1991-11-02', false, 'confidential')");
                }
            }

            String jdbcBase = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/";
            try (Connection admin = DriverManager.getConnection(jdbcBase + "postgres", pg.getUsername(), pg.getPassword())) {
                admin.setAutoCommit(true);
                try (Statement stmt = admin.createStatement()) { stmt.execute("CREATE DATABASE redact_target"); }
            }
            String targetUrl = jdbcBase + "redact_target";
            try (Connection conn = DriverManager.getConnection(targetUrl, pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) { stmt.execute(DDL); }
            }

            sourceDs = new SimpleDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            targetDs = new SimpleDataSource(targetUrl, pg.getUsername(), pg.getPassword());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set up redaction E2E databases", e);
        }
    }

    @AfterAll
    void tearDown() {
        if (pg != null) pg.stop();
    }

    @Test
    void redactionFitsEachColumnType() throws Exception {
        Assumptions.assumeTrue(sourceDs != null, "Docker/PostgreSQL not available");

        AnonymisationPolicy policy = AnonymisationPolicy.builder()
            .table("record", t -> t
                .column("id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column("amount", ColumnRole.SENSITIVE, RedactionStrategy.CONSTANT)      // INTEGER → 0
                .column("balance", ColumnRole.SENSITIVE, RedactionStrategy.CONSTANT)     // NUMERIC → 0
                .column("event_date", ColumnRole.SENSITIVE, RedactionStrategy.CONSTANT)  // DATE → 1970-01-01
                .column("active", ColumnRole.SENSITIVE, RedactionStrategy.CONSTANT)      // BOOLEAN → false
                .column("note", ColumnRole.SENSITIVE, RedactionStrategy.MASK))           // TEXT → '*' × len
            .build();

        PipelineResult result = IncognitoPipeline.builder()
            .source(sourceDs).target(targetDs).ephemeralSalt().policy(policy)
            .stage(new SchemaDiscoveryStage())
            .stage(new TableTransformLoadStage())
            .stage(new VerificationStage())
            .build().execute();
        assertTrue(result.success(), "redaction across non-text column types should load, not fail at insert");

        try (Connection conn = targetDs.getConnection()) {
            assertEquals(2, scalar(conn, "SELECT COUNT(*) FROM record"));
            // Each redacted column holds a type-appropriate constant — no real value survives.
            assertEquals(2, scalar(conn, "SELECT COUNT(*) FROM record WHERE amount = 0"), "INTEGER redacted to 0");
            assertEquals(2, scalar(conn, "SELECT COUNT(*) FROM record WHERE balance = 0"), "NUMERIC redacted to 0");
            assertEquals(2, scalar(conn, "SELECT COUNT(*) FROM record WHERE event_date = DATE '1970-01-01'"), "DATE redacted to epoch");
            assertEquals(2, scalar(conn, "SELECT COUNT(*) FROM record WHERE active = false"), "BOOLEAN redacted to false");
            assertEquals(0, scalar(conn, "SELECT COUNT(*) FROM record WHERE note IN ('topsecret','confidential')"), "note masked");
            assertEquals(2, scalar(conn, "SELECT COUNT(*) FROM record WHERE note ~ '^\\*+$'"), "note fully '*'-masked");
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
