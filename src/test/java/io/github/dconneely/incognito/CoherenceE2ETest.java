package io.github.dconneely.incognito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dconneely.incognito.api.ColumnRole;
import io.github.dconneely.incognito.api.DirectIdStrategy;
import io.github.dconneely.incognito.api.IncognitoPipeline;
import io.github.dconneely.incognito.api.PipelineResult;
import io.github.dconneely.incognito.api.QuasiIdStrategy;
import io.github.dconneely.incognito.api.RedactionStrategy;
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
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * De-risking E2E for the two advanced Phase-4 relational paths that unit tests can't exercise:
 * <ul>
 *   <li><b>Root-ancestor {@code INHERITED_ATTRIBUTE}</b> — a 3-level chain
 *       {@code firm → contract → schedule}. {@code firm.name} is a fabricated {@code DIRECT_ID};
 *       both {@code contract.firm_name} (direct parent) and {@code schedule.firm_name}
 *       (<em>grandparent</em>, reached by walking the FK chain) are declared
 *       {@code INHERITED_ATTRIBUTE derivedFrom(firm, name)} and must show the firm's <em>fabricated</em>
 *       value, never the row's own real value (SPEC §6.1).</li>
 *   <li><b>Coherent group jitter</b> — {@code contract.start_date} and {@code schedule.due_date}
 *       share coherence group {@code engagement}; the schedule inherits its contract's day-delta,
 *       so every {@code due_date − start_date} interval is preserved exactly (SPEC §4.2).</li>
 * </ul>
 * Also locks the working text-redaction path ({@code contract.notes}, {@code MASK}).
 *
 * <p>Requires Docker; skips gracefully otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoherenceE2ETest {

    private PostgreSQLContainer pg;
    private DataSource sourceDs;
    private DataSource targetDs;

    private static final String DDL = """
        CREATE TABLE firm (
            id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            name  VARCHAR(100) NOT NULL
        );
        CREATE TABLE contract (
            id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            firm_id     BIGINT NOT NULL REFERENCES firm(id),
            firm_name   VARCHAR(100) NOT NULL,
            start_date  DATE NOT NULL,
            notes       VARCHAR(50) NOT NULL
        );
        CREATE TABLE schedule (
            id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            contract_id  BIGINT NOT NULL REFERENCES contract(id),
            firm_name    VARCHAR(100) NOT NULL,
            due_date     DATE NOT NULL
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
                .withDatabaseName("coherence_source").withUsername("test").withPassword("test");
            pg.start();

            try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(DDL);
                    // firm 1 = "Alpha Holdings", firm 2 = "Beta Partners"
                    stmt.execute("INSERT INTO firm (name) VALUES ('Alpha Holdings'), ('Beta Partners')");
                    // contracts: 1,2 belong to firm 1; 3 belongs to firm 2
                    stmt.execute("INSERT INTO contract (firm_id, firm_name, start_date, notes) VALUES "
                        + "(1, 'Alpha Holdings', DATE '2024-01-10', 'CONFIDENTIAL-1'),"
                        + "(1, 'Alpha Holdings', DATE '2024-03-01', 'CONFIDENTIAL-2'),"
                        + "(2, 'Beta Partners',  DATE '2024-02-15', 'CONFIDENTIAL-3')");
                    // schedules: intervals (due - start) are 10, 31, 14, 10 days
                    stmt.execute("INSERT INTO schedule (contract_id, firm_name, due_date) VALUES "
                        + "(1, 'Alpha Holdings', DATE '2024-01-20'),"   // contract1 start 01-10 → +10
                        + "(1, 'Alpha Holdings', DATE '2024-02-10'),"   // contract1 start 01-10 → +31
                        + "(2, 'Alpha Holdings', DATE '2024-03-15'),"   // contract2 start 03-01 → +14
                        + "(3, 'Beta Partners',  DATE '2024-02-25')");  // contract3 start 02-15 → +10
                }
            }

            String jdbcBase = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/";
            try (Connection admin = DriverManager.getConnection(jdbcBase + "postgres", pg.getUsername(), pg.getPassword())) {
                admin.setAutoCommit(true);
                try (Statement stmt = admin.createStatement()) {
                    stmt.execute("CREATE DATABASE coherence_target");
                }
            }
            String targetUrl = jdbcBase + "coherence_target";
            try (Connection conn = DriverManager.getConnection(targetUrl, pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(DDL);
                }
            }

            sourceDs = new SimpleDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            targetDs = new SimpleDataSource(targetUrl, pg.getUsername(), pg.getPassword());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set up coherence E2E databases", e);
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
                .column(ColumnPolicy.builder("firm_name").role(ColumnRole.INHERITED_ATTRIBUTE).derivedFrom("firm", "name").build())
                .column(ColumnPolicy.builder("start_date").role(ColumnRole.QUASI_ID)
                    .quasiIdStrategy(QuasiIdStrategy.JITTER_DAYS).jitterDays(30).coherenceGroup("engagement").build())
                .column("notes", ColumnRole.SENSITIVE, RedactionStrategy.MASK))
            .table("schedule", t -> t
                .column("id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column(ColumnPolicy.builder("contract_id").role(ColumnRole.FOREIGN_KEY).references("contract", "id").build())
                // Grandparent inheritance: schedule has no direct FK to firm — resolution must walk
                // schedule → contract → firm to read firm.name.
                .column(ColumnPolicy.builder("firm_name").role(ColumnRole.INHERITED_ATTRIBUTE).derivedFrom("firm", "name").build())
                .column(ColumnPolicy.builder("due_date").role(ColumnRole.QUASI_ID)
                    .quasiIdStrategy(QuasiIdStrategy.JITTER_DAYS).jitterDays(30).coherenceGroup("engagement").build()))
            .build();
    }

    @Test
    void inheritanceAndCoherentJitterHold() throws Exception {
        Assumptions.assumeTrue(sourceDs != null, "Docker/PostgreSQL not available");

        List<Integer> sourceIntervals = intervals(sourceDs);
        assertEquals(List.of(10, 10, 14, 31), sourceIntervals, "sanity: seeded source intervals");

        PipelineResult result = IncognitoPipeline.builder()
            .source(sourceDs).target(targetDs).ephemeralSalt().policy(policy())
            .stage(new SchemaDiscoveryStage())
            .stage(new TableTransformLoadStage())
            .stage(new VerificationStage())
            .build().execute();
        assertTrue(result.success(), "pipeline should succeed");

        try (Connection conn = targetDs.getConnection()) {
            // Row counts preserved.
            assertEquals(2, count(conn, "firm"));
            assertEquals(3, count(conn, "contract"));
            assertEquals(4, count(conn, "schedule"));

            // firm.name fabricated → no real firm name survives.
            assertEquals(0, scalar(conn,
                "SELECT COUNT(*) FROM firm WHERE name IN ('Alpha Holdings','Beta Partners')"),
                "DIRECT_ID firm.name must be fabricated");

            // Direct-parent inheritance: contract.firm_name == its firm's fabricated name, and no real value leaked.
            assertEquals(0, scalar(conn,
                "SELECT COUNT(*) FROM contract c JOIN firm f ON c.firm_id = f.id WHERE c.firm_name <> f.name"),
                "every contract.firm_name must equal its firm's fabricated name");
            assertEquals(0, scalar(conn,
                "SELECT COUNT(*) FROM contract WHERE firm_name IN ('Alpha Holdings','Beta Partners')"),
                "no real firm name may survive on contract (fail-open guard)");

            // Grandparent inheritance: schedule.firm_name == the firm reached via contract.
            assertEquals(0, scalar(conn,
                "SELECT COUNT(*) FROM schedule s JOIN contract c ON s.contract_id = c.id "
                    + "JOIN firm f ON c.firm_id = f.id WHERE s.firm_name <> f.name"),
                "every schedule.firm_name must equal its root-ancestor firm's fabricated name");
            assertEquals(0, scalar(conn,
                "SELECT COUNT(*) FROM schedule WHERE firm_name IN ('Alpha Holdings','Beta Partners')"),
                "no real firm name may survive on schedule (grandparent fail-open guard)");

            // Coherent jitter: the multiset of (due_date − start_date) intervals is unchanged.
            // If the schedule drew an independent delta from its contract, intervals would shift.
            assertEquals(sourceIntervals, intervals(targetDs),
                "coherent group jitter must preserve every parent-child date interval");

            // Redaction (text MASK) lock: notes fully masked, length preserved.
            assertEquals(0, scalar(conn, "SELECT COUNT(*) FROM contract WHERE notes !~ '^\\*+$'"),
                "MASK redaction should replace every character with '*'");
            assertEquals(0, scalar(conn, "SELECT COUNT(*) FROM contract WHERE length(notes) <> 14"),
                "MASK must preserve length");
            assertFalse(intervals(targetDs).isEmpty());
        }
    }

    /** Sorted list of (due_date − start_date) day intervals across the schedule⋈contract join. */
    private List<Integer> intervals(DataSource ds) throws SQLException {
        List<Integer> out = new ArrayList<>();
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT (s.due_date - c.start_date) AS d FROM schedule s "
                     + "JOIN contract c ON s.contract_id = c.id ORDER BY d")) {
            while (rs.next()) out.add(rs.getInt(1));
        }
        return out;
    }

    private long count(Connection conn, String table) throws SQLException {
        return scalar(conn, "SELECT COUNT(*) FROM " + table);
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
