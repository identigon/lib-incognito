package io.github.dconneely.incognito.benchmark;

import io.github.dconneely.incognito.api.ColumnRole;
import io.github.dconneely.incognito.api.IncognitoPipeline;
import io.github.dconneely.incognito.api.PipelineResult;
import io.github.dconneely.incognito.api.QuasiIdStrategy;
import io.github.dconneely.incognito.api.SurrogateStrategy;
import io.github.dconneely.incognito.core.SchemaDiscoveryStage;
import io.github.dconneely.incognito.core.TableTransformLoadStage;
import io.github.dconneely.incognito.core.VerificationStage;
import io.github.dconneely.incognito.policy.AnonymisationPolicy;
import io.github.dconneely.incognito.policy.ColumnPolicy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static io.github.dconneely.incognito.api.ColumnRole.FOREIGN_KEY;
import static io.github.dconneely.incognito.api.ColumnRole.PAYLOAD;
import static io.github.dconneely.incognito.api.ColumnRole.PRIMARY_KEY;
import static io.github.dconneely.incognito.api.DirectIdStrategy.ALTEREGO_GENERIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-7 benchmark: the classic <b>employees</b> temporal HR database — the archetypal DPIA
 * scenario (names, birth dates, salaries). It exercises, together on real data, a shape no other
 * test does: <b>composite primary keys whose first column is a surrogated foreign key and whose
 * remaining columns are kept temporal values</b> — {@code salary(emp_no, from_date)} and
 * {@code title(emp_no, title, from_date)} — where {@code emp_no} is reassigned by a
 * {@link SurrogateStrategy#SEQUENTIAL_LONG} surrogate on the {@code SERIAL} {@code employee} PK and
 * must be rewritten consistently into every child composite key. Alongside: a strongly-identifying
 * {@code birth_date} (SPEC §7.3 invariant 5 — synthesised, never year-shifted), {@code DIRECT_ID}
 * names, and two {@code VIEW}s that must be excluded from cloning yet remain live over the clone.
 *
 * <p>Fixture: {@code resources/benchmarks/employees/employees.sql} (Bytebase {@code dataset_small},
 * CC BY-SA 3.0 — see {@code benchmarks/SOURCES.md}). Requires Docker; skips gracefully otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmployeesBenchmarkE2ETest {

    /** Base tables the engine must clone (the two views are excluded). */
    private static final List<String> TABLES =
        List.of("employee", "department", "dept_emp", "dept_manager", "title", "salary", "audit");

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
        Assumptions.assumeTrue(dockerAvailable, "Docker not available — skipping employees benchmark");

        String full = resource("/benchmarks/employees/employees.sql");
        // The fixture is schema (DDL, incl. views/trigger/RLS) then an inlined DATA section. The
        // schema-only slice for the target is everything before that marker — a plain "drop INSERT
        // lines" filter would corrupt the multi-row INSERTs and the trigger body's INSERT INTO audit.
        int dataStart = full.indexOf("---- DATA (inlined");
        String schemaOnly = dataStart < 0 ? full : full.substring(0, full.lastIndexOf('\n', dataStart));

        pg = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("employees_source").withUsername("test").withPassword("test");
        pg.start();

        try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) { stmt.execute(full); }
        }

        String jdbcBase = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/";
        try (Connection admin = DriverManager.getConnection(jdbcBase + "postgres", pg.getUsername(), pg.getPassword())) {
            admin.setAutoCommit(true);
            try (Statement stmt = admin.createStatement()) { stmt.execute("CREATE DATABASE employees_target"); }
        }
        String targetUrl = jdbcBase + "employees_target";
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

    // --- policy helpers ---
    private static ColumnPolicy pkSeq(String name) {
        return ColumnPolicy.builder(name).role(PRIMARY_KEY).surrogateStrategy(SurrogateStrategy.SEQUENTIAL_LONG).build();
    }
    private static ColumnPolicy pkPass(String name) {
        return ColumnPolicy.builder(name).role(PRIMARY_KEY).surrogateStrategy(SurrogateStrategy.PASSTHROUGH_SURROGATE).build();
    }
    private static ColumnPolicy fk(String name, String table, String col) {
        return ColumnPolicy.builder(name).role(FOREIGN_KEY).references(table, col).build();
    }
    private static ColumnPolicy id(String name) {
        return ColumnPolicy.builder(name).role(ColumnRole.DIRECT_ID).directIdStrategy(ALTEREGO_GENERIC).build();
    }

    private AnonymisationPolicy policy() {
        return AnonymisationPolicy.builder()
            // SERIAL PK reassigned → forces FK rewrite into every child composite key.
            .table("employee", t -> t
                .column(pkSeq("emp_no"))
                .column(ColumnPolicy.builder("birth_date").role(ColumnRole.QUASI_ID)
                    .quasiIdStrategy(QuasiIdStrategy.SYNTHESISE).build())   // strongly-identifying DOB (§7.3 #5)
                .column(id("first_name")).column(id("last_name"))
                .column("gender", PAYLOAD).column("hire_date", PAYLOAD))
            .table("department", t -> t
                .column(pkPass("dept_no"))                                  // 'd001'.. non-identifying code
                .column("dept_name", PAYLOAD))
            .table("dept_emp", t -> t
                .column(fk("emp_no", "employee", "emp_no")).column(fk("dept_no", "department", "dept_no"))
                .column("from_date", PAYLOAD).column("to_date", PAYLOAD))
            .table("dept_manager", t -> t
                .column(fk("emp_no", "employee", "emp_no")).column(fk("dept_no", "department", "dept_no"))
                .column("from_date", PAYLOAD).column("to_date", PAYLOAD))
            // composite PK (emp_no, title, from_date): surrogated FK + kept text + kept date.
            .table("title", t -> t
                .column(fk("emp_no", "employee", "emp_no"))
                .column("title", PAYLOAD).column("from_date", PAYLOAD).column("to_date", PAYLOAD))
            // composite PK (emp_no, from_date): surrogated FK + kept date.
            .table("salary", t -> t
                .column(fk("emp_no", "employee", "emp_no"))
                .column("amount", PAYLOAD).column("from_date", PAYLOAD).column("to_date", PAYLOAD))
            // trigger-populated audit log: empty, but must still be classified (fail-closed).
            .table("audit", t -> t
                .column(pkSeq("id")).column("operation", PAYLOAD).column("query", PAYLOAD)
                .column("user_name", PAYLOAD).column("changed_at", PAYLOAD))
            .build();
    }

    @Test
    void employeesClonesCoherently() throws Exception {
        Assumptions.assumeTrue(sourceDs != null, "Docker/PostgreSQL not available");

        PipelineResult result = IncognitoPipeline.builder()
            .source(sourceDs).target(targetDs).ephemeralSalt().policy(policy())
            .stage(new SchemaDiscoveryStage())
            .stage(new TableTransformLoadStage())
            .stage(new VerificationStage())
            .build().execute();
        assertTrue(result.success(), "employees should clone successfully");

        try (Connection src = sourceDs.getConnection(); Connection tgt = targetDs.getConnection()) {
            // Row volumes preserved for every base table.
            for (String table : TABLES) {
                assertEquals(scalar(src, "SELECT COUNT(*) FROM " + table), scalar(tgt, "SELECT COUNT(*) FROM " + table),
                    "row count preserved for " + table);
            }

            // Composite PKs intact — no collapsed or duplicated rows after remapping.
            assertEquals(9488, scalar(tgt, "SELECT COUNT(DISTINCT (emp_no, from_date)) FROM salary"),
                "salary composite PK (emp_no, from_date) preserved");
            assertEquals(1470, scalar(tgt, "SELECT COUNT(DISTINCT (emp_no, title, from_date)) FROM title"),
                "title composite PK (emp_no, title, from_date) preserved");

            // The SERIAL emp_no was genuinely reassigned (SEQUENTIAL_LONG from 1), not passed through:
            // originals start at 10001, so nothing in that range may survive anywhere it is used.
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM employee WHERE emp_no >= 10001"),
                "employee.emp_no reassigned by the surrogate");
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM salary WHERE emp_no >= 10001"),
                "salary.emp_no rewritten to the surrogate value");

            // FK integrity across the surrogate remap (single- and composite-key children).
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM salary s "
                + "WHERE NOT EXISTS (SELECT 1 FROM employee e WHERE e.emp_no = s.emp_no)"),
                "every salary row references a real employee");
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM title t "
                + "WHERE NOT EXISTS (SELECT 1 FROM employee e WHERE e.emp_no = t.emp_no)"),
                "every title row references a real employee");
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM dept_emp de "
                + "WHERE NOT EXISTS (SELECT 1 FROM employee e WHERE e.emp_no = de.emp_no) "
                + "OR NOT EXISTS (SELECT 1 FROM department d WHERE d.dept_no = de.dept_no)"),
                "dept_emp FKs must resolve");
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM dept_manager dm "
                + "WHERE NOT EXISTS (SELECT 1 FROM employee e WHERE e.emp_no = dm.emp_no) "
                + "OR NOT EXISTS (SELECT 1 FROM department d WHERE d.dept_no = dm.dept_no)"),
                "dept_manager FKs must resolve");

            // PII fabricated: no real employee name from the source survives.
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM employee WHERE last_name IN "
                + "('Facello','Simmel','Bamford','Koblick','Maliniak','Preusig')"),
                "employee surnames must be fabricated");
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM employee WHERE first_name IN "
                + "('Georgi','Bezalel','Parto','Chirstian','Kyoichi','Anneke')"),
                "employee forenames must be fabricated");

            // Views were excluded from the clone (not treated as base tables)...
            Set<String> reported = result.report().tables().stream().map(tr -> tr.table()).collect(java.util.stream.Collectors.toSet());
            assertTrue(reported.containsAll(TABLES), "all base tables reported");
            assertFalse(reported.contains("current_dept_emp"), "view current_dept_emp not cloned");
            assertFalse(reported.contains("dept_emp_latest_date"), "view dept_emp_latest_date not cloned");
            // ...yet remain live over the cloned data on the target.
            assertTrue(scalar(tgt, "SELECT COUNT(*) FROM current_dept_emp") > 0,
                "current_dept_emp view resolves over the clone");
        }
    }

    private long scalar(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String resource(String path) throws Exception {
        try (var in = EmployeesBenchmarkE2ETest.class.getResourceAsStream(path)) {
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
