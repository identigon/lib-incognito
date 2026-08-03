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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.List;
import javax.sql.DataSource;
import org.identigon.incognito.TestPostgres;
import org.identigon.incognito.api.DirectIdStrategy;
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
        assumeDockerAvailable("Chinook");

        String full = resource("/benchmarks/chinook/chinook.sql");
        // Schema DDL (tables, FK ALTERs, indexes) precedes all data; Chinook has no function bodies,
        // so the first INSERT is a safe schema/data boundary for the empty target.
        String schemaOnly = full.substring(0, full.indexOf("INSERT INTO"));

        pg = new PostgreSQLContainer(TestPostgres.IMAGE)
            .withDatabaseName("chinook_source").withUsername("test").withPassword("test");
        pg.start();

        sourceDs = dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        execute(sourceDs, full);

        targetDs = createTargetDatabase(pg, "chinook_target");
        execute(targetDs, schemaOnly);
    }

    @AfterAll
    void tearDown() {
        if (pg != null) pg.stop();
    }

    @Test
    void chinookClonesCoherently() throws Exception {
        Assumptions.assumeTrue(sourceDs != null, "Docker/PostgreSQL not available");

        PipelineResult result = IncognitoPipeline.builder()
            .source(sourceDs).target(targetDs).ephemeralSalt().policy(loadPolicy("/benchmarks/chinook/policy.yaml"))
            .stage(new SchemaDiscoveryStage())
            .stage(new TableTransformLoadStage())
            .stage(new VerificationStage())
            .build().execute();
        assertTrue(result.success(), "Chinook should clone successfully");

        emitAndVerifyDpiaReport(result.report(), "chinook");

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

            // Postal codes fabricated into GB-format postcodes with the guaranteed-fictional
            // inward-code letter (never a real, deliverable postcode).
            for (String col : new String[] {"employee.postal_code", "customer.postal_code", "invoice.billing_postal_code"}) {
                String table = col.substring(0, col.indexOf('.'));
                String column = col.substring(col.indexOf('.') + 1);
                assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM " + table + " WHERE " + column
                    + " !~ '^[A-Z]{1,2}[0-9]{1,2} [0-9][A-Z]{2}$'"), col + " must be a GB-format postcode");
                assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM " + table + " WHERE RIGHT(" + column
                    + ", 1) NOT IN ('C','I','K','M','O','V')"), col + " must use the guaranteed-fictional inward-code letter");
            }

            // Operational categoricals kept real (not PII).
            assertTrue(scalar(tgt, "SELECT COUNT(*) FROM genre WHERE name = 'Rock'") > 0,
                "genre names kept real");
        }
    }
}
