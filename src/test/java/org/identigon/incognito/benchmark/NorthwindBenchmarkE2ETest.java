package org.identigon.incognito.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.identigon.incognito.api.IncognitoPipeline;
import org.identigon.incognito.api.PipelineResult;
import org.identigon.incognito.core.SchemaDiscoveryStage;
import org.identigon.incognito.core.TableTransformLoadStage;
import org.identigon.incognito.core.VerificationStage;
import org.identigon.incognito.policy.AnonymisationPolicy;
import java.nio.charset.StandardCharsets;
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
 * Phase-7 benchmark: Northwind. A real 14-table schema that stresses, together, features no other
 * test combined: a <b>self-referential FK</b> ({@code employees.reports_to → employees}, exercising
 * the cyclic-FK deferral + Pass-2 UPDATE on real data), three <b>composite-PK</b> join tables
 * ({@code order_details}, {@code employee_territories}, {@code customer_customer_demo}), an <b>opaque
 * {@code bytea}</b> column kept and audited ({@code categories.picture}) alongside a biometric one
 * redacted ({@code employees.photo}), and <b>text
 * primary keys</b> ({@code customers.customer_id}). Schema and data come from the staged fixture
 * ({@code resources/benchmarks/northwind}); PKs are passthrough (they are not surrogatable as
 * numbers), PII is fabricated. Requires Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NorthwindBenchmarkE2ETest {

    private static final List<String> TABLES = List.of(
        "categories", "customer_demographics", "customer_customer_demo", "customers", "employees",
        "employee_territories", "order_details", "orders", "products", "region", "shippers",
        "suppliers", "territories", "us_states");

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
        Assumptions.assumeTrue(dockerAvailable, "Docker not available — skipping Northwind benchmark");

        String full = resource("/benchmarks/northwind/northwind.sql");
        // Schema-only for the target: drop the single-line INSERTs, keep CREATE TABLE + constraints.
        String schemaOnly = full.lines()
            .filter(l -> !l.startsWith("INSERT INTO"))
            .reduce(new StringBuilder(), (sb, l) -> sb.append(l).append('\n'), StringBuilder::append)
            .toString();

        pg = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("northwind_source").withUsername("test").withPassword("test");
        pg.start();

        try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) { stmt.execute(full); }
        }

        String jdbcBase = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/";
        try (Connection admin = DriverManager.getConnection(jdbcBase + "postgres", pg.getUsername(), pg.getPassword())) {
            admin.setAutoCommit(true);
            try (Statement stmt = admin.createStatement()) { stmt.execute("CREATE DATABASE northwind_target"); }
        }
        String targetUrl = jdbcBase + "northwind_target";
        try (Connection conn = DriverManager.getConnection(targetUrl, pg.getUsername(), pg.getPassword())) {
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) { stmt.execute(schemaOnly); }
        }

        sourceDs = new SimpleDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        targetDs = new SimpleDataSource(targetUrl, pg.getUsername(), pg.getPassword());
    }

    @AfterAll
    void tearDown() {
        if (pg != null) pg.stop();
    }


    /** Loads the policy from a YAML test resource (exercises the {@code YamlPolicyParser} path E2E). */
    private AnonymisationPolicy policy() throws Exception {
        try (var in = NorthwindBenchmarkE2ETest.class.getResourceAsStream("/benchmarks/northwind/policy.yaml")) {
            if (in == null) throw new IllegalStateException("missing test resource: /benchmarks/northwind/policy.yaml");
            return new org.identigon.incognito.policy.YamlPolicyParser().parse(in);
        }
    }

    @Test
    void northwindClonesCoherently() throws Exception {
        Assumptions.assumeTrue(sourceDs != null, "Docker/PostgreSQL not available");

        PipelineResult result = IncognitoPipeline.builder()
            .source(sourceDs).target(targetDs).ephemeralSalt().policy(policy())
            .stage(new SchemaDiscoveryStage())
            .stage(new TableTransformLoadStage())
            .stage(new VerificationStage())
            .build().execute();
        assertTrue(result.success(), "Northwind should clone successfully");

        try (Connection src = sourceDs.getConnection(); Connection tgt = targetDs.getConnection()) {
            // Row volumes preserved for every table.
            for (String table : TABLES) {
                assertEquals(scalar(src, "SELECT COUNT(*) FROM " + table), scalar(tgt, "SELECT COUNT(*) FROM " + table),
                    "row count preserved for " + table);
            }

            // Self-referential FK: resolved by the cyclic Pass-2 UPDATE — no placeholder, no dangling.
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM employees WHERE reports_to = -1"),
                "reports_to placeholder must be resolved");
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM employees e WHERE e.reports_to IS NOT NULL "
                + "AND NOT EXISTS (SELECT 1 FROM employees m WHERE m.employee_id = e.reports_to)"),
                "reports_to must reference a real employee");

            // Composite-PK join tables keep referential integrity.
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM order_details od "
                + "WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.order_id = od.order_id) "
                + "OR NOT EXISTS (SELECT 1 FROM products p WHERE p.product_id = od.product_id)"),
                "order_details FKs must resolve");
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM employee_territories et "
                + "WHERE NOT EXISTS (SELECT 1 FROM employees e WHERE e.employee_id = et.employee_id) "
                + "OR NOT EXISTS (SELECT 1 FROM territories tr WHERE tr.territory_id = et.territory_id)"),
                "employee_territories FKs must resolve");

            // PII fabricated: no real employee surname, no real customer company name survives.
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM employees WHERE last_name IN "
                + "('Davolio','Fuller','Leverling','Peacock','Buchanan','Suyama','King','Callahan','Dodsworth')"),
                "employee surnames must be fabricated");
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM customers WHERE company_name = 'Alfreds Futterkiste'"),
                "customer company names must be fabricated");

            // SENSITIVE free-text bios (employees.notes, distinguishing) redacted to a constant — no bio survives.
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM employees WHERE notes IS NOT NULL AND notes <> 'REDACTED'"),
                "employee notes must be redacted, not kept");
            assertTrue(scalar(tgt, "SELECT COUNT(*) FROM employees WHERE notes = 'REDACTED'") > 0,
                "the redaction actually ran on the populated bios");

            // Biometric photo (bytea) redacted away; photo_path filename no longer leaks the real surname.
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM employees WHERE photo IS NOT NULL"),
                "employee face images must be dropped, not copied");
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM employees WHERE photo_path LIKE '%davolio%'"),
                "the surname embedded in photo_path must not survive");

            // Opaque bytea columns kept and surfaced in the DPIA report's passthrough audit (§7.2).
            List<String> catFlags = result.report().tables().stream()
                .filter(tr -> tr.table().equals("categories")).findFirst().orElseThrow()
                .passthroughFlags().stream().map(pf -> pf.column()).toList();
            assertTrue(catFlags.contains("picture"), "categories.picture (bytea) flagged as passthrough");
        }
    }

    private long scalar(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String resource(String path) throws Exception {
        try (var in = NorthwindBenchmarkE2ETest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing test resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
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
