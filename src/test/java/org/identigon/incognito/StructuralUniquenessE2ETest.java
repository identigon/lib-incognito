package org.identigon.incognito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.identigon.incognito.api.AnonymisationReport;
import org.identigon.incognito.api.ColumnRole;
import org.identigon.incognito.api.DirectIdStrategy;
import org.identigon.incognito.api.IncognitoPipeline;
import org.identigon.incognito.api.PipelineResult;
import org.identigon.incognito.api.StructuralUniquenessMode;
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
 * Structural-uniqueness findings (SPEC §2.4): a subject can be singled out by its FK fan-out alone,
 * even with every field fabricated. One customer has 5 orders (a distinctive relational
 * fingerprint); the other 20 customers have exactly 1 order each —
 * the finding must report the fan-out extreme and flag that one customer as uniquely fingerprinted.
 * Off by default; one test opts in via {@code structuralUniqueness(REPORT)}. Requires Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StructuralUniquenessE2ETest {

    private PostgreSQLContainer pg;
    private String jdbcBase;

    private static final String DDL = """
        CREATE TABLE customers (
            customer_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            name        VARCHAR(100) NOT NULL
        );
        CREATE TABLE orders (
            order_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            customer_id BIGINT NOT NULL REFERENCES customers(customer_id)
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
        pg = new PostgreSQLContainer(TestPostgres.IMAGE)
            .withDatabaseName("structural").withUsername("test").withPassword("test");
        pg.start();
        jdbcBase = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/";
    }

    @AfterAll
    void tearDown() {
        if (pg != null) pg.stop();
    }

    private AnonymisationPolicy policy(StructuralUniquenessMode mode) {
        return AnonymisationPolicy.builder()
            .structuralUniqueness(mode)
            .table("customers", t -> t
                .column("customer_id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column("name", ColumnRole.DIRECT_ID, DirectIdStrategy.ALTEREGO_GENERIC))
            .table("orders", t -> t
                .column("order_id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column(ColumnPolicy.builder("customer_id").role(ColumnRole.FOREIGN_KEY)
                    .references("customers", "customer_id").build()))
            .build();
    }

    @Test
    void distinctiveFanOutIsReportedAsAStructuralFinding() throws Exception {
        Assumptions.assumeTrue(pg != null, "Docker/PostgreSQL not available");
        DataSource[] ds = freshDatabases("report");

        PipelineResult result = run(ds, StructuralUniquenessMode.REPORT);
        assertTrue(result.success(), "pipeline should succeed — structural findings never fail the run");

        AnonymisationReport.StructuralUniquenessFinding finding = result.report().structuralFindings().stream()
            .filter(f -> f.parentTable().equals("customers") && f.childTable().equals("orders"))
            .findFirst().orElseThrow(() -> new AssertionError("expected a customers/orders structural finding"));

        assertEquals(java.util.List.of("customer_id"), finding.childColumns(),
            "the finding identifies which FK edge produced it");
        assertEquals(21, finding.distinctParents(), "all 21 customers have at least one order");
        assertEquals(5, finding.maxChildCount(), "the distinctive customer's fan-out is the extreme");
        assertEquals(1, finding.uniqueFingerprintCount(), "exactly one customer is singled out by fan-out");
        assertTrue(finding.rareFingerprintCount() >= 1, "the unique fan-out also counts as rare (below k)");
        assertEquals(5, finding.k(), "default rareness threshold");
    }

    @Test
    void offByDefaultProducesNoStructuralFindings() throws Exception {
        Assumptions.assumeTrue(pg != null, "Docker/PostgreSQL not available");
        DataSource[] ds = freshDatabases("off");

        PipelineResult result = run(ds, StructuralUniquenessMode.OFF);
        assertTrue(result.success(), "pipeline should succeed");
        assertTrue(result.report().structuralFindings().isEmpty(),
            "structural-uniqueness scan must not run unless explicitly opted in");
    }

    // --- helpers ---

    /** Creates fresh source+target databases (schema-identical) with the fan-out fixture seeded. */
    private DataSource[] freshDatabases(String tag) throws SQLException {
        String src = "structural_" + tag + "_src";
        String tgt = "structural_" + tag + "_tgt";
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
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO customers (name) SELECT 'Customer ' || i FROM generate_series(1, 21) AS i");
                // The customer with the smallest id gets 5 orders — a distinctive fan-out.
                stmt.execute("INSERT INTO orders (customer_id) "
                    + "(SELECT customer_id FROM customers ORDER BY customer_id LIMIT 1) "
                    + "UNION ALL (SELECT customer_id FROM customers ORDER BY customer_id LIMIT 1) "
                    + "UNION ALL (SELECT customer_id FROM customers ORDER BY customer_id LIMIT 1) "
                    + "UNION ALL (SELECT customer_id FROM customers ORDER BY customer_id LIMIT 1) "
                    + "UNION ALL (SELECT customer_id FROM customers ORDER BY customer_id LIMIT 1)");
                // The other 20 customers get exactly 1 order each.
                stmt.execute("INSERT INTO orders (customer_id) "
                    + "SELECT customer_id FROM customers ORDER BY customer_id OFFSET 1");
            }
        }
        return new DataSource[]{
            new SimpleDataSource(jdbcBase + src, pg.getUsername(), pg.getPassword()),
            new SimpleDataSource(jdbcBase + tgt, pg.getUsername(), pg.getPassword())
        };
    }

    private PipelineResult run(DataSource[] ds, StructuralUniquenessMode mode) {
        return IncognitoPipeline.builder()
            .source(ds[0]).target(ds[1]).ephemeralSalt().policy(policy(mode))
            .stage(new SchemaDiscoveryStage())
            .stage(new TableTransformLoadStage())
            .stage(new VerificationStage())
            .build().execute();
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
