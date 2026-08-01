package io.github.dconneely.incognito;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.github.dconneely.incognito.policy.ColumnPolicy;
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
 * De-risks {@code SERIAL} (sequence-default) primary keys — as used by Pagila — versus
 * {@code GENERATED ALWAYS AS IDENTITY} (used by every other test). The PostgreSQL driver reports
 * both as {@code IS_AUTOINCREMENT=YES}, but {@code OVERRIDING SYSTEM VALUE} is valid <em>only</em>
 * for true identity columns; emitting it on a {@code SERIAL} column errors. This guards the Phase-7
 * Pagila load. Requires Docker; skips gracefully otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SerialPkE2ETest {

    private PostgreSQLContainer pg;
    private DataSource sourceDs;
    private DataSource targetDs;

    private static final String DDL = """
        CREATE TABLE account (
            id      SERIAL PRIMARY KEY,
            holder  VARCHAR(100) NOT NULL
        );
        CREATE TABLE txn (
            id          SERIAL PRIMARY KEY,
            account_id  INT NOT NULL REFERENCES account(id),
            amount      NUMERIC(10,2) NOT NULL
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
                .withDatabaseName("serial_source").withUsername("test").withPassword("test");
            pg.start();

            try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(DDL);
                    stmt.execute("INSERT INTO account (holder) VALUES ('Alice'), ('Bob')");
                    stmt.execute("INSERT INTO txn (account_id, amount) VALUES (1, 10.00), (1, 20.00), (2, 5.50)");
                }
            }

            String jdbcBase = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/";
            try (Connection admin = DriverManager.getConnection(jdbcBase + "postgres", pg.getUsername(), pg.getPassword())) {
                admin.setAutoCommit(true);
                try (Statement stmt = admin.createStatement()) {
                    stmt.execute("CREATE DATABASE serial_target");
                }
            }
            String targetUrl = jdbcBase + "serial_target";
            try (Connection conn = DriverManager.getConnection(targetUrl, pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(DDL);
                }
            }

            sourceDs = new SimpleDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            targetDs = new SimpleDataSource(targetUrl, pg.getUsername(), pg.getPassword());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set up SERIAL-PK E2E databases", e);
        }
    }

    @AfterAll
    void tearDown() {
        if (pg != null) pg.stop();
    }

    @Test
    void serialPrimaryKeysLoad() throws Exception {
        Assumptions.assumeTrue(sourceDs != null, "Docker/PostgreSQL not available");

        AnonymisationPolicy policy = AnonymisationPolicy.builder()
            .table("account", t -> t
                .column("id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column("holder", ColumnRole.DIRECT_ID, DirectIdStrategy.ALTEREGO_GENERIC))
            .table("txn", t -> t
                .column("id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column(ColumnPolicy.builder("account_id").role(ColumnRole.FOREIGN_KEY).references("account", "id").build())
                .column("amount", ColumnRole.PAYLOAD))
            .build();

        PipelineResult result = IncognitoPipeline.builder()
            .source(sourceDs).target(targetDs).ephemeralSalt().policy(policy)
            .stage(new SchemaDiscoveryStage())
            .stage(new TableTransformLoadStage())
            .stage(new VerificationStage())
            .build().execute();
        assertTrue(result.success(), "pipeline should succeed loading SERIAL-PK tables");

        try (Connection conn = targetDs.getConnection()) {
            assertEquals(2, scalar(conn, "SELECT COUNT(*) FROM account"));
            assertEquals(3, scalar(conn, "SELECT COUNT(*) FROM txn"));
            assertEquals(0, scalar(conn,
                "SELECT COUNT(*) FROM txn t WHERE NOT EXISTS (SELECT 1 FROM account a WHERE a.id=t.account_id)"),
                "no dangling FK");
            assertEquals(0, scalar(conn, "SELECT COUNT(*) FROM account WHERE holder IN ('Alice','Bob')"),
                "holder fabricated");

            // Sequences must still work after load: a default-PK insert must succeed and not collide.
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO account (holder) VALUES ('new')");
            }
            assertEquals(3, scalar(conn, "SELECT COUNT(*) FROM account"),
                "SERIAL sequence resynced — new default-PK insert succeeds");
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
