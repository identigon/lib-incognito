package io.github.dconneely.incognito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dconneely.incognito.api.AnonymisationReport;
import io.github.dconneely.incognito.api.ColumnRole;
import io.github.dconneely.incognito.api.DirectIdStrategy;
import io.github.dconneely.incognito.api.IncognitoPipeline;
import io.github.dconneely.incognito.api.PipelineResult;
import io.github.dconneely.incognito.api.SurrogateStrategy;
import io.github.dconneely.incognito.policy.AnonymisationPolicy;
import java.sql.Connection;
import java.sql.DriverManager;
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
 * De-risks the §7.2 opaque-type passthrough audit: a kept ({@code PAYLOAD}) column of a
 * complex/untransformable JDBC type (PostgreSQL maps {@code jsonb} and {@code inet} to
 * {@link java.sql.Types#OTHER}) must be surfaced in the DPIA report's passthrough flags, never
 * silently copied. Requires Docker; skips gracefully otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PassthroughAuditE2ETest {

    private PostgreSQLContainer pg;
    private DataSource sourceDs;
    private DataSource targetDs;

    private static final String DDL = """
        CREATE TABLE docs (
            id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            owner  VARCHAR(100) NOT NULL,
            meta   JSONB NOT NULL,
            ip     INET
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
                .withDatabaseName("audit_source").withUsername("test").withPassword("test");
            pg.start();

            try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(DDL);
                    stmt.execute("INSERT INTO docs (owner, meta, ip) VALUES "
                        + "('alice', '{\"k\":1}', '10.0.0.1'), ('bob', '{\"k\":2}', NULL)");
                }
            }

            String jdbcBase = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/";
            try (Connection admin = DriverManager.getConnection(jdbcBase + "postgres", pg.getUsername(), pg.getPassword())) {
                admin.setAutoCommit(true);
                try (Statement stmt = admin.createStatement()) {
                    stmt.execute("CREATE DATABASE audit_target");
                }
            }
            String targetUrl = jdbcBase + "audit_target";
            try (Connection conn = DriverManager.getConnection(targetUrl, pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(DDL);
                }
            }

            sourceDs = new SimpleDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            targetDs = new SimpleDataSource(targetUrl, pg.getUsername(), pg.getPassword());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set up passthrough-audit E2E databases", e);
        }
    }

    @AfterAll
    void tearDown() {
        if (pg != null) pg.stop();
    }

    @Test
    void opaqueKeptColumnsAreFlagged() throws Exception {
        Assumptions.assumeTrue(sourceDs != null, "Docker/PostgreSQL not available");

        AnonymisationPolicy policy = AnonymisationPolicy.builder()
            .table("docs", t -> t
                .column("id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column("owner", ColumnRole.DIRECT_ID, DirectIdStrategy.ALTEREGO_GENERIC)
                .column("meta", ColumnRole.PAYLOAD)   // jsonb — kept real, opaque → flagged
                .column("ip", ColumnRole.PAYLOAD))     // inet — kept real, opaque → flagged
            .build();

        PipelineResult result = IncognitoPipeline.builder()
            .source(sourceDs).target(targetDs).ephemeralSalt().policy(policy)
            .stage(new io.github.dconneely.incognito.core.SchemaDiscoveryStage())
            .stage(new io.github.dconneely.incognito.core.TableTransformLoadStage())
            .stage(new io.github.dconneely.incognito.core.VerificationStage())
            .build().execute();

        assertTrue(result.success(), "pipeline should succeed (opaque types are kept, not failed)");

        AnonymisationReport.TableReport docs = result.report().tables().stream()
            .filter(tr -> tr.table().equals("docs")).findFirst().orElseThrow();

        List<String> flaggedColumns = docs.passthroughFlags().stream()
            .map(AnonymisationReport.PassthroughFlag::column).toList();

        assertTrue(flaggedColumns.contains("meta"), "jsonb PAYLOAD column must be flagged as passthrough");
        assertTrue(flaggedColumns.contains("ip"), "inet PAYLOAD column must be flagged as passthrough");
        // The fabricated DIRECT_ID and the surrogate PK are NOT opaque passthroughs.
        assertEquals(2, docs.passthroughFlags().size(), "only the two opaque columns are flagged");
        assertTrue(docs.passthroughFlags().stream().allMatch(pf -> pf.jdbcType().equals("OTHER")),
            "jsonb/inet report as JDBC type OTHER");

        // Sanity: the opaque values were kept (copied through), and rows loaded.
        try (Connection conn = targetDs.getConnection();
             Statement stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT COUNT(*) FROM docs WHERE meta->>'k' IN ('1','2')")) {
            rs.next();
            assertEquals(2, rs.getLong(1), "jsonb payload kept intact");
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
