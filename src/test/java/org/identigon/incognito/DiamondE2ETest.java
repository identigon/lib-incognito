package org.identigon.incognito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.identigon.incognito.api.ColumnRole;
import org.identigon.incognito.api.DirectIdStrategy;
import org.identigon.incognito.api.IncognitoException;
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
 * Multi-path diamond (SPEC §6.1, Phase 7): {@code schedule} reaches {@code firm} by two FK paths —
 * {@code schedule → office → firm} and {@code schedule → contract → firm}. An
 * {@code INHERITED_ATTRIBUTE} on {@code schedule} derived from {@code firm.name} must:
 * <ul>
 *   <li><b>converge</b> when both paths reach the <em>same</em> firm — the two branches are not a
 *       conflict, so it resolves to that firm's fabricated name; and</li>
 *   <li><b>fail closed</b> when the paths reach <em>different</em> firms (a genuine fork).</li>
 * </ul>
 * Requires Docker; skips gracefully otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiamondE2ETest {

    private PostgreSQLContainer pg;
    private String jdbcBase;

    private static final String DDL = """
        CREATE TABLE firm (
            id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            name  VARCHAR(100) NOT NULL
        );
        CREATE TABLE office (
            id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            firm_id  BIGINT NOT NULL REFERENCES firm(id)
        );
        CREATE TABLE contract (
            id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            firm_id  BIGINT NOT NULL REFERENCES firm(id)
        );
        CREATE TABLE schedule (
            id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            office_id    BIGINT NOT NULL REFERENCES office(id),
            contract_id  BIGINT NOT NULL REFERENCES contract(id),
            firm_name    VARCHAR(100) NOT NULL
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
        pg = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("diamond").withUsername("test").withPassword("test");
        pg.start();
        jdbcBase = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/";
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
            .table("office", t -> t
                .column("id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column(ColumnPolicy.builder("firm_id").role(ColumnRole.FOREIGN_KEY).references("firm", "id").build()))
            .table("contract", t -> t
                .column("id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column(ColumnPolicy.builder("firm_id").role(ColumnRole.FOREIGN_KEY).references("firm", "id").build()))
            .table("schedule", t -> t
                .column("id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column(ColumnPolicy.builder("office_id").role(ColumnRole.FOREIGN_KEY).references("office", "id").build())
                .column(ColumnPolicy.builder("contract_id").role(ColumnRole.FOREIGN_KEY).references("contract", "id").build())
                .column(ColumnPolicy.builder("firm_name").role(ColumnRole.INHERITED_ATTRIBUTE).derivedFrom("firm", "name").build()))
            .build();
    }

    @Test
    void convergentDiamondResolvesFromSharedAncestor() throws Exception {
        Assumptions.assumeTrue(pg != null, "Docker/PostgreSQL not available");
        DataSource[] ds = freshDatabases("conv", conn -> {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO firm (name) VALUES ('Alpha'), ('Beta')");          // 1,2
                stmt.execute("INSERT INTO office (firm_id) VALUES (1), (2), (1)");            // o1,o3 -> firm1; o2 -> firm2
                stmt.execute("INSERT INTO contract (firm_id) VALUES (1), (2)");              // c1 -> firm1; c2 -> firm2
                // Each schedule's office and contract belong to the SAME firm (convergent).
                stmt.execute("INSERT INTO schedule (office_id, contract_id, firm_name) VALUES "
                    + "(1,1,'Alpha'), (2,2,'Beta'), (3,1,'Alpha')");
            }
        });

        PipelineResult result = run(ds);
        assertTrue(result.success(), "convergent diamond should load");

        try (Connection conn = ds[1].getConnection()) {
            assertEquals(3, scalar(conn, "SELECT COUNT(*) FROM schedule"));
            // firm_name matches the firm reached via BOTH the office path and the contract path.
            assertEquals(0, scalar(conn,
                "SELECT COUNT(*) FROM schedule s JOIN office o ON s.office_id=o.id JOIN firm f ON o.firm_id=f.id "
                    + "WHERE s.firm_name <> f.name"), "firm_name agrees with the office->firm path");
            assertEquals(0, scalar(conn,
                "SELECT COUNT(*) FROM schedule s JOIN contract c ON s.contract_id=c.id JOIN firm f ON c.firm_id=f.id "
                    + "WHERE s.firm_name <> f.name"), "firm_name agrees with the contract->firm path");
            assertEquals(0, scalar(conn, "SELECT COUNT(*) FROM schedule WHERE firm_name IN ('Alpha','Beta')"),
                "no real firm name survives (inherited from the fabricated ancestor)");
        }
    }

    @Test
    void forkedDiamondFailsClosed() throws Exception {
        Assumptions.assumeTrue(pg != null, "Docker/PostgreSQL not available");
        DataSource[] ds = freshDatabases("fork", conn -> {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO firm (name) VALUES ('Alpha'), ('Beta')");   // 1,2
                stmt.execute("INSERT INTO office (firm_id) VALUES (1)");              // o1 -> firm1
                stmt.execute("INSERT INTO contract (firm_id) VALUES (2)");            // c1 -> firm2
                // schedule's office (firm1) and contract (firm2) disagree — a genuine fork.
                stmt.execute("INSERT INTO schedule (office_id, contract_id, firm_name) VALUES (1,1,'Alpha')");
            }
        });

        // Two distinct ancestor firms for one INHERITED_ATTRIBUTE — must fail closed, not guess.
        assertThrows(IncognitoException.class, () -> run(ds),
            "a forked diamond (two distinct ancestor rows) must fail closed");
    }

    // --- helpers ---

    private interface Seeder { void seed(Connection conn) throws SQLException; }

    /** Creates fresh source+target databases (schema-identical), seeds the source, returns [source, target]. */
    private DataSource[] freshDatabases(String tag, Seeder seeder) throws SQLException {
        String src = "diamond_" + tag + "_src";
        String tgt = "diamond_" + tag + "_tgt";
        try (Connection admin = DriverManager.getConnection(jdbcBase + "postgres", pg.getUsername(), pg.getPassword())) {
            admin.setAutoCommit(true);
            try (Statement stmt = admin.createStatement()) {
                stmt.execute("DROP DATABASE IF EXISTS " + src);
                stmt.execute("DROP DATABASE IF EXISTS " + tgt);
                stmt.execute("CREATE DATABASE " + src);
                stmt.execute("CREATE DATABASE " + tgt);
            }
        }
        for (String db : new String[]{src, tgt}) {
            try (Connection conn = DriverManager.getConnection(jdbcBase + db, pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) { stmt.execute(DDL); }
            }
        }
        try (Connection conn = DriverManager.getConnection(jdbcBase + src, pg.getUsername(), pg.getPassword())) {
            conn.setAutoCommit(true);
            seeder.seed(conn);
        }
        return new DataSource[]{
            new SimpleDataSource(jdbcBase + src, pg.getUsername(), pg.getPassword()),
            new SimpleDataSource(jdbcBase + tgt, pg.getUsername(), pg.getPassword())
        };
    }

    private PipelineResult run(DataSource[] ds) {
        return IncognitoPipeline.builder()
            .source(ds[0]).target(ds[1]).ephemeralSalt().policy(policy())
            .stage(new SchemaDiscoveryStage())
            .stage(new TableTransformLoadStage())
            .stage(new VerificationStage())
            .build().execute();
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
