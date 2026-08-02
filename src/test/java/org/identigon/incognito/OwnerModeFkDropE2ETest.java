package org.identigon.incognito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
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
 * Owner-mode degraded path for cyclic FKs (SPEC §9): a non-superuser target that <b>owns</b> its
 * tables cannot suppress FK enforcement via {@code session_replication_role='replica'}, so Incognito
 * drops the cyclic FK constraints for the load and recreates them after the pass-2 UPDATE. This case
 * used to fail fast (see {@code FailClosedGuardE2ETest}); here it must succeed and leave the FK back
 * in place. Requires Docker; skips gracefully otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OwnerModeFkDropE2ETest {

    private static final String DDL = """
        CREATE TABLE emp (
            id    INT PRIMARY KEY,
            boss  INT REFERENCES emp(id),
            ename VARCHAR(50) NOT NULL
        );
        """;

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
        Assumptions.assumeTrue(dockerAvailable, "Docker not available — skipping owner-mode FK-drop E2E");

        pg = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("owner_source").withUsername("test").withPassword("test");
        pg.start();

        try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), "test", "test");
             Statement stmt = conn.createStatement()) {
            stmt.execute(DDL);
            stmt.execute("INSERT INTO emp (id, boss, ename) VALUES "
                + "(1, NULL, 'Alice'), (2, 1, 'Bob'), (3, 1, 'Carol'), (4, 2, 'Dave')");
        }

        String jdbcBase = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/";
        try (Connection admin = DriverManager.getConnection(jdbcBase + "postgres", "test", "test");
             Statement stmt = admin.createStatement()) {
            stmt.execute("CREATE DATABASE owner_target");
            stmt.execute("DROP ROLE IF EXISTS owner_role");
            stmt.execute("CREATE ROLE owner_role LOGIN PASSWORD 'own' NOSUPERUSER");
            stmt.execute("GRANT CONNECT ON DATABASE owner_target TO owner_role");
        }
        String targetUrl = jdbcBase + "owner_target";
        try (Connection conn = DriverManager.getConnection(targetUrl, "test", "test");
             Statement stmt = conn.createStatement()) {
            stmt.execute(DDL);
            // Hand the table to a plain (non-superuser) role: it becomes the owner, so it can DROP/ADD
            // the constraint and DISABLE TRIGGER, but cannot set session_replication_role.
            stmt.execute("GRANT ALL ON SCHEMA public TO owner_role");
            stmt.execute("ALTER TABLE emp OWNER TO owner_role");
        }

        sourceDs = new SimpleDataSource(pg.getJdbcUrl(), "test", "test");
        targetDs = new SimpleDataSource(targetUrl, "owner_role", "own");
    }

    @AfterAll
    void tearDown() {
        if (pg != null) pg.stop();
    }

    @Test
    void ownerModeDropsAndRecreatesCyclicFk() throws Exception {
        Assumptions.assumeTrue(sourceDs != null, "Docker/PostgreSQL not available");

        AnonymisationPolicy policy = AnonymisationPolicy.builder()
            .table("emp", t -> t
                .column("id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column(ColumnPolicy.builder("boss").role(ColumnRole.FOREIGN_KEY).references("emp", "id").build())
                .column("ename", ColumnRole.DIRECT_ID, DirectIdStrategy.ALTEREGO_GENERIC))
            .build();

        PipelineResult result = IncognitoPipeline.builder()
            .source(sourceDs).target(targetDs).ephemeralSalt().policy(policy)
            .stage(new SchemaDiscoveryStage())
            .stage(new TableTransformLoadStage())
            .stage(new VerificationStage())
            .build().execute();
        assertTrue(result.success(), "owner-mode cyclic load should succeed via FK drop/recreate");

        try (Connection conn = targetDs.getConnection()) {
            assertEquals(4, scalar(conn, "SELECT COUNT(*) FROM emp"), "rows preserved");

            // The self-referential FK was resolved by the pass-2 UPDATE — no dangling references.
            assertEquals(0, scalar(conn, "SELECT COUNT(*) FROM emp WHERE boss = -1"),
                "no placeholder left behind");
            assertEquals(0, scalar(conn, "SELECT COUNT(*) FROM emp e WHERE e.boss IS NOT NULL "
                + "AND NOT EXISTS (SELECT 1 FROM emp m WHERE m.id = e.boss)"),
                "boss must reference a real employee");

            // The dropped FK constraint was recreated — the schema is intact afterwards.
            assertEquals(1, scalar(conn,
                "SELECT COUNT(*) FROM pg_constraint WHERE conrelid = 'emp'::regclass AND contype = 'f'"),
                "the foreign-key constraint is back in place");

            // PII fabricated.
            assertEquals(0, scalar(conn, "SELECT COUNT(*) FROM emp WHERE ename IN ('Alice','Bob','Carol','Dave')"),
                "employee names must be fabricated");
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
