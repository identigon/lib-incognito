package org.identigon.incognito.benchmark;

import static org.identigon.incognito.benchmark.BenchmarkSupport.assumeDockerAvailable;
import static org.identigon.incognito.benchmark.BenchmarkSupport.createTargetDatabase;
import static org.identigon.incognito.benchmark.BenchmarkSupport.dataSource;
import static org.identigon.incognito.benchmark.BenchmarkSupport.emitAndVerifyDpiaReport;
import static org.identigon.incognito.benchmark.BenchmarkSupport.execute;
import static org.identigon.incognito.benchmark.BenchmarkSupport.loadPolicy;
import static org.identigon.incognito.benchmark.BenchmarkSupport.resource;
import static org.identigon.incognito.benchmark.BenchmarkSupport.scalar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.identigon.incognito.TestPostgres;
import org.identigon.incognito.api.IncognitoPipeline;
import org.identigon.incognito.api.PipelineResult;
import org.identigon.incognito.api.SurrogateStrategy;
import org.identigon.incognito.core.SchemaDiscoveryStage;
import org.identigon.incognito.core.TableTransformLoadStage;
import org.identigon.incognito.core.VerificationStage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

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
        assumeDockerAvailable("employees");

        String full = resource("/benchmarks/employees/employees.sql");
        // The fixture is schema (DDL, incl. views/trigger/RLS) then an inlined DATA section. The
        // schema-only slice for the target is everything before that marker — a plain "drop INSERT
        // lines" filter would corrupt the multi-row INSERTs and the trigger body's INSERT INTO audit.
        int dataStart = full.indexOf("---- DATA (inlined");
        String schemaOnly = dataStart < 0 ? full : full.substring(0, full.lastIndexOf('\n', dataStart));

        pg = new PostgreSQLContainer(TestPostgres.IMAGE)
            .withDatabaseName("employees_source").withUsername("test").withPassword("test");
        pg.start();

        sourceDs = dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        execute(sourceDs, full);

        targetDs = createTargetDatabase(pg, "employees_target");
        execute(targetDs, schemaOnly);
    }

    @AfterAll
    void tearDown() {
        if (pg != null) pg.stop();
    }

    @Test
    void employeesClonesCoherently() throws Exception {
        Assumptions.assumeTrue(sourceDs != null, "Docker/PostgreSQL not available");

        PipelineResult result = IncognitoPipeline.builder()
            .source(sourceDs).target(targetDs).ephemeralSalt().policy(loadPolicy("/benchmarks/employees/policy.yaml"))
            .stage(new SchemaDiscoveryStage())
            .stage(new TableTransformLoadStage())
            .stage(new VerificationStage())
            .build().execute();
        assertTrue(result.success(), "employees should clone successfully");

        emitAndVerifyDpiaReport(result.report(), "employees");

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
}
