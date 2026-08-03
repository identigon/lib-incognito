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
import org.identigon.incognito.api.DirectIdStrategy;
import org.identigon.incognito.api.IncognitoPipeline;
import org.identigon.incognito.api.PipelineResult;
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
 * Regression test: a nullable column that is the declared {@code derivedFrom} source of some
 * {@code INHERITED_ATTRIBUTE} elsewhere must not crash the load when its value is actually
 * {@code NULL} for a row. Publishing that row's fabricated (also {@code null}) value into the
 * {@code AttributeCascadeStore} previously called {@code ConcurrentHashMap.put(key, null)}
 * unguarded, which throws {@link NullPointerException} by contract — crashing the whole load and
 * surfacing only as a generic "Pipeline execution failed" (the real cause was masked by
 * {@code DefaultIncognitoPipeline}'s outer catch).
 *
 * <p>{@code firm2} has a {@code NULL} name and no {@code contract} row references it, so nothing
 * ever tries to *read* the never-published attribute — this isolates the publish-time crash from
 * the separate question of what a reader should see for a genuinely null-valued ancestor value.
 *
 * <p>Requires Docker; skips gracefully otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NullableInheritedAttributeSourceE2ETest {

    private PostgreSQLContainer pg;
    private DataSource sourceDs;
    private DataSource targetDs;

    private static final String DDL = """
        CREATE TABLE firm (
            id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            name  VARCHAR(100)
        );
        CREATE TABLE contract (
            id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            firm_id     BIGINT NOT NULL REFERENCES firm(id),
            firm_name   VARCHAR(100)
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
                .withDatabaseName("nullable_inherited_source").withUsername("test").withPassword("test");
            pg.start();

            try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(DDL);
                    // firm 1 has a real name and a referencing contract; firm 2's name is NULL and
                    // nothing references it — nothing ever tries to read its inherited attribute.
                    stmt.execute("INSERT INTO firm (name) VALUES ('Alpha Holdings'), (NULL)");
                    stmt.execute("INSERT INTO contract (firm_id, firm_name) VALUES (1, 'Alpha Holdings')");
                }
            }

            String jdbcBase = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/";
            try (Connection admin = DriverManager.getConnection(jdbcBase + "postgres", pg.getUsername(), pg.getPassword())) {
                admin.setAutoCommit(true);
                try (Statement stmt = admin.createStatement()) {
                    stmt.execute("CREATE DATABASE nullable_inherited_target");
                }
            }
            String targetUrl = jdbcBase + "nullable_inherited_target";
            try (Connection conn = DriverManager.getConnection(targetUrl, pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(DDL);
                }
            }

            sourceDs = new SimpleDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            targetDs = new SimpleDataSource(targetUrl, pg.getUsername(), pg.getPassword());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set up nullable-inherited-source E2E databases", e);
        }
    }

    @AfterAll
    void tearDown() {
        if (pg != null) pg.stop();
    }

    private AnonymisationPolicy policy() {
        return AnonymisationPolicy.builder()
            .table("firm", t -> t
                .column("id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column("name", ColumnRole.DIRECT_ID, DirectIdStrategy.ALTEREGO_GENERIC))
            .table("contract", t -> t
                .column("id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column(ColumnPolicy.builder("firm_id").role(ColumnRole.FOREIGN_KEY).references("firm", "id").build())
                .column(ColumnPolicy.builder("firm_name").role(ColumnRole.INHERITED_ATTRIBUTE).derivedFrom("firm", "name").build()))
            .build();
    }

    @Test
    void nullSourceValueDoesNotCrashTheLoad() throws Exception {
        Assumptions.assumeTrue(sourceDs != null, "Docker/PostgreSQL not available");

        PipelineResult result = IncognitoPipeline.builder()
            .source(sourceDs).target(targetDs).ephemeralSalt().policy(policy())
            .stage(new SchemaDiscoveryStage())
            .stage(new TableTransformLoadStage())
            .stage(new VerificationStage())
            .build().execute();

        assertTrue(result.success(), "a NULL INHERITED_ATTRIBUTE source value must not crash the load");

        try (Connection conn = targetDs.getConnection()) {
            assertEquals(2, scalar(conn, "SELECT COUNT(*) FROM firm"));
            assertEquals(1, scalar(conn, "SELECT COUNT(*) FROM contract"));
            // The referencing contract's inherited attribute still resolves correctly to its
            // (fabricated) ancestor's real, non-null value.
            assertEquals(0, scalar(conn,
                "SELECT COUNT(*) FROM contract c JOIN firm f ON c.firm_id = f.id WHERE c.firm_name <> f.name"),
                "contract.firm_name must equal its firm's fabricated name");
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
