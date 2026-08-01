package io.github.dconneely.incognito.benchmark;

import static io.github.dconneely.incognito.api.ColumnRole.FOREIGN_KEY;
import static io.github.dconneely.incognito.api.ColumnRole.PAYLOAD;
import static io.github.dconneely.incognito.api.ColumnRole.PRIMARY_KEY;
import static io.github.dconneely.incognito.api.DirectIdStrategy.ALTEREGO_EMAIL;
import static io.github.dconneely.incognito.api.DirectIdStrategy.ALTEREGO_GENERIC;
import static io.github.dconneely.incognito.api.DirectIdStrategy.ALTEREGO_PHONE;
import static io.github.dconneely.incognito.api.SurrogateStrategy.SEQUENTIAL_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dconneely.incognito.api.ColumnRole;
import io.github.dconneely.incognito.api.DirectIdStrategy;
import io.github.dconneely.incognito.api.IncognitoPipeline;
import io.github.dconneely.incognito.api.PipelineResult;
import io.github.dconneely.incognito.api.QuasiIdStrategy;
import io.github.dconneely.incognito.api.SurrogateStrategy;
import io.github.dconneely.incognito.core.SchemaDiscoveryStage;
import io.github.dconneely.incognito.core.TableTransformLoadStage;
import io.github.dconneely.incognito.core.VerificationStage;
import io.github.dconneely.incognito.policy.AnonymisationPolicy;
import io.github.dconneely.incognito.policy.ColumnPolicy;
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
 * Phase-7 benchmark: Chinook — a music-store schema (artists, albums, tracks, invoices, customers,
 * employees). It adds coverage the other benchmarks lack: <b>{@code email} columns</b> transformed
 * by {@link DirectIdStrategy#ALTEREGO_EMAIL} and checked by the verification stage's e-mail
 * fictionality net; a <b>self-referential FK</b> ({@code employee.reports_to}) resolved by the
 * cyclic Pass-2 UPDATE while its {@code SERIAL}-free {@code INT} PK is <em>reassigned</em> by a
 * {@link SurrogateStrategy#SEQUENTIAL_LONG} surrogate; a <b>composite-PK</b> join table
 * ({@code playlist_track}); and a rich FK graph rewritten across the surrogate remap. Volumes are
 * compared source-vs-target (no magic numbers).
 *
 * <p>Fixture: {@code resources/benchmarks/chinook/chinook.sql} (lerocha/chinook-database, MIT — see
 * {@code benchmarks/SOURCES.md}). Requires Docker; skips gracefully otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChinookBenchmarkE2ETest {

    private static final List<String> TABLES = List.of(
        "album", "artist", "customer", "employee", "genre", "invoice", "invoice_line",
        "media_type", "playlist", "playlist_track", "track");

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
        Assumptions.assumeTrue(dockerAvailable, "Docker not available — skipping Chinook benchmark");

        String full = resource("/benchmarks/chinook/chinook.sql");
        // Schema DDL (tables, FK ALTERs, indexes) precedes all data; Chinook has no function bodies,
        // so the first INSERT is a safe schema/data boundary for the empty target.
        String schemaOnly = full.substring(0, full.indexOf("INSERT INTO"));

        pg = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("chinook_source").withUsername("test").withPassword("test");
        pg.start();

        try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) { stmt.execute(full); }
        }

        String jdbcBase = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/";
        try (Connection admin = DriverManager.getConnection(jdbcBase + "postgres", pg.getUsername(), pg.getPassword())) {
            admin.setAutoCommit(true);
            try (Statement stmt = admin.createStatement()) { stmt.execute("CREATE DATABASE chinook_target"); }
        }
        String targetUrl = jdbcBase + "chinook_target";
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
    private static ColumnPolicy pk(String name) {
        return ColumnPolicy.builder(name).role(PRIMARY_KEY).surrogateStrategy(SEQUENTIAL_LONG).build();
    }
    private static ColumnPolicy fk(String name, String table, String col) {
        return ColumnPolicy.builder(name).role(FOREIGN_KEY).references(table, col).build();
    }
    private static ColumnPolicy id(String name, DirectIdStrategy s) {
        return ColumnPolicy.builder(name).role(ColumnRole.DIRECT_ID).directIdStrategy(s).build();
    }

    private AnonymisationPolicy policy() {
        return AnonymisationPolicy.builder()
            .table("artist", t -> t.column(pk("artist_id")).column("name", PAYLOAD))
            .table("album", t -> t.column(pk("album_id")).column("title", PAYLOAD)
                .column(fk("artist_id", "artist", "artist_id")))
            .table("genre", t -> t.column(pk("genre_id")).column("name", PAYLOAD))
            .table("media_type", t -> t.column(pk("media_type_id")).column("name", PAYLOAD))
            .table("track", t -> t.column(pk("track_id")).column("name", PAYLOAD)
                .column(fk("album_id", "album", "album_id"))
                .column(fk("media_type_id", "media_type", "media_type_id"))
                .column(fk("genre_id", "genre", "genre_id"))
                .column("composer", PAYLOAD).column("milliseconds", PAYLOAD)
                .column("bytes", PAYLOAD).column("unit_price", PAYLOAD))
            .table("playlist", t -> t.column(pk("playlist_id")).column("name", PAYLOAD))
            .table("playlist_track", t -> t
                .column(fk("playlist_id", "playlist", "playlist_id"))
                .column(fk("track_id", "track", "track_id")))
            // self-referential FK (reports_to) + reassigned INT PK.
            .table("employee", t -> t.column(pk("employee_id"))
                .column(id("last_name", ALTEREGO_GENERIC)).column(id("first_name", ALTEREGO_GENERIC))
                .column("title", PAYLOAD).column(fk("reports_to", "employee", "employee_id"))
                .column(ColumnPolicy.builder("birth_date").role(ColumnRole.QUASI_ID)
                    .quasiIdStrategy(QuasiIdStrategy.SYNTHESISE).build())          // DOB (§7.3 #5)
                .column("hire_date", PAYLOAD).column(id("address", ALTEREGO_GENERIC))
                .column("city", PAYLOAD).column("state", PAYLOAD).column("country", PAYLOAD)
                .column(id("postal_code", ALTEREGO_GENERIC))
                .column(id("phone", ALTEREGO_PHONE)).column(id("fax", ALTEREGO_PHONE))
                .column(id("email", ALTEREGO_EMAIL)))
            .table("customer", t -> t.column(pk("customer_id"))
                .column(id("first_name", ALTEREGO_GENERIC)).column(id("last_name", ALTEREGO_GENERIC))
                .column("company", PAYLOAD).column(id("address", ALTEREGO_GENERIC))
                .column("city", PAYLOAD).column("state", PAYLOAD).column("country", PAYLOAD)
                .column(id("postal_code", ALTEREGO_GENERIC))
                .column(id("phone", ALTEREGO_PHONE)).column(id("fax", ALTEREGO_PHONE))
                .column(id("email", ALTEREGO_EMAIL))
                .column(fk("support_rep_id", "employee", "employee_id")))
            .table("invoice", t -> t.column(pk("invoice_id"))
                .column(fk("customer_id", "customer", "customer_id"))
                .column("invoice_date", PAYLOAD).column(id("billing_address", ALTEREGO_GENERIC))
                .column("billing_city", PAYLOAD).column("billing_state", PAYLOAD)
                .column("billing_country", PAYLOAD).column(id("billing_postal_code", ALTEREGO_GENERIC))
                .column("total", PAYLOAD))
            .table("invoice_line", t -> t.column(pk("invoice_line_id"))
                .column(fk("invoice_id", "invoice", "invoice_id"))
                .column(fk("track_id", "track", "track_id"))
                .column("unit_price", PAYLOAD).column("quantity", PAYLOAD))
            .build();
    }

    @Test
    void chinookClonesCoherently() throws Exception {
        Assumptions.assumeTrue(sourceDs != null, "Docker/PostgreSQL not available");

        PipelineResult result = IncognitoPipeline.builder()
            .source(sourceDs).target(targetDs).ephemeralSalt().policy(policy())
            .stage(new SchemaDiscoveryStage())
            .stage(new TableTransformLoadStage())
            .stage(new VerificationStage())
            .build().execute();
        assertTrue(result.success(), "Chinook should clone successfully");

        try (Connection src = sourceDs.getConnection(); Connection tgt = targetDs.getConnection()) {
            // Row volumes preserved for every table.
            for (String table : TABLES) {
                assertEquals(scalar(src, "SELECT COUNT(*) FROM " + table), scalar(tgt, "SELECT COUNT(*) FROM " + table),
                    "row count preserved for " + table);
            }

            // Composite PK (playlist_id, track_id) intact — no collapse after the surrogate remap.
            assertEquals(scalar(src, "SELECT COUNT(DISTINCT (playlist_id, track_id)) FROM playlist_track"),
                scalar(tgt, "SELECT COUNT(DISTINCT (playlist_id, track_id)) FROM playlist_track"),
                "playlist_track composite PK preserved");

            // Self-referential FK resolved by the cyclic Pass-2 UPDATE: no placeholder, no dangling.
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM employee WHERE reports_to = -1"),
                "reports_to placeholder must be resolved");
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM employee e WHERE e.reports_to IS NOT NULL "
                + "AND NOT EXISTS (SELECT 1 FROM employee m WHERE m.employee_id = e.reports_to)"),
                "reports_to must reference a real employee");

            // FK integrity across the reassigned PKs (single- and composite-key children).
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM invoice_line il "
                + "WHERE NOT EXISTS (SELECT 1 FROM invoice i WHERE i.invoice_id = il.invoice_id) "
                + "OR NOT EXISTS (SELECT 1 FROM track tr WHERE tr.track_id = il.track_id)"),
                "invoice_line FKs must resolve");
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM playlist_track pt "
                + "WHERE NOT EXISTS (SELECT 1 FROM playlist p WHERE p.playlist_id = pt.playlist_id) "
                + "OR NOT EXISTS (SELECT 1 FROM track tr WHERE tr.track_id = pt.track_id)"),
                "playlist_track FKs must resolve");
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM invoice i "
                + "WHERE NOT EXISTS (SELECT 1 FROM customer c WHERE c.customer_id = i.customer_id)"),
                "invoice → customer FK must resolve");

            // PII fabricated: no real employee name or corporate e-mail survives.
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM employee WHERE last_name IN "
                + "('Adams','Edwards','Peacock','Park','Johnson','Mitchell','King','Callahan')"),
                "employee surnames must be fabricated");
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM employee WHERE email LIKE '%@chinookcorp.com'"),
                "employee e-mails must be fabricated to fictional addresses");
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM customer WHERE email = 'luisg@embraer.com.br'"),
                "customer e-mails must be fabricated");

            // Operational categoricals kept real (not PII).
            assertTrue(scalar(tgt, "SELECT COUNT(*) FROM genre WHERE name = 'Rock'") > 0,
                "genre names kept real");
        }
    }

    private long scalar(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String resource(String path) throws Exception {
        try (var in = ChinookBenchmarkE2ETest.class.getResourceAsStream(path)) {
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
