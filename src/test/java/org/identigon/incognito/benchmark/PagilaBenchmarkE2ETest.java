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
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.identigon.incognito.TestPostgres;
import org.identigon.incognito.api.IncognitoPipeline;
import org.identigon.incognito.api.PipelineResult;
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
 * Phase-7 benchmark: Pagila — the canonical PostgreSQL Sakila port (pinned to tag {@code
 * pagila-v3.0.0}: no pgvector, no heavy partitioning). The DVD-rental domain adds, together, a real
 * 15-table Sakila graph with two <b>composite PKs</b> ({@code film_actor}, {@code film_category}),
 * seven <b>views</b> that must be excluded from cloning, opaque {@code bytea}/{@code tsvector}/array
 * columns surfaced in the passthrough audit, {@code email} columns, and {@code SERIAL}-style PKs.
 * The partitioned {@code payment} table is excluded from the policy.
 *
 * <p>Schema and the ~5 MB {@code pagila-insert-data.sql} are both vendored (see
 * {@code benchmarks/SOURCES.md} for the upstream tag and recorded SHA-256) — no network fetch, so
 * the benchmark can't silently skip in a network-restricted CI environment. Requires Docker; skips
 * gracefully otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PagilaBenchmarkE2ETest {

    /** The non-partitioned Sakila core tables the benchmark clones (payment + its partitions excluded). */
    private static final List<String> TABLES = List.of(
        "actor", "address", "category", "city", "country", "customer", "film", "film_actor",
        "film_category", "inventory", "language", "rental", "staff", "store");

    private PostgreSQLContainer pg;
    private DataSource sourceDs;
    private DataSource targetDs;

    @BeforeAll
    void setUp() throws Exception {
        assumeDockerAvailable("Pagila");

        String insertData = resource("/benchmarks/pagila/pagila-insert-data.sql");

        String schema = resource("/benchmarks/pagila/schema.sql");
        // `ALTER ... OWNER TO postgres` — that role does not exist in the test container.
        String schemaLoadable = schema.lines()
            .filter(l -> !l.contains("OWNER TO"))
            .collect(Collectors.joining("\n"));
        // Source data: drop the partitioned payment rows (payment excluded from the policy), and load
        // under replica role so the non-FK-ordered dump loads without FK-order failures.
        String sourceData = "SET session_replication_role = 'replica';\n" + insertData.lines()
            .filter(l -> !l.startsWith("INSERT INTO public.payment"))
            .collect(Collectors.joining("\n"));

        pg = new PostgreSQLContainer(TestPostgres.IMAGE)
            .withDatabaseName("pagila_source").withUsername("test").withPassword("test");
        pg.start();

        sourceDs = dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        execute(sourceDs, schemaLoadable);
        execute(sourceDs, sourceData);

        targetDs = createTargetDatabase(pg, "pagila_target");
        execute(targetDs, schemaLoadable);
    }

    @AfterAll
    void tearDown() {
        if (pg != null) pg.stop();
    }

    @Test
    void pagilaClonesCoherently() throws Exception {
        Assumptions.assumeTrue(sourceDs != null, "Docker/PostgreSQL not available");

        PipelineResult result = IncognitoPipeline.builder()
            .source(sourceDs).target(targetDs).ephemeralSalt().policy(loadPolicy("/benchmarks/pagila/policy.yaml"))
            .stage(new SchemaDiscoveryStage())
            .stage(new TableTransformLoadStage())
            .stage(new VerificationStage())
            .build().execute();
        String verifyMsg = result.report().stageResults().stream()
            .filter(r -> r.stageName().equals("VerificationStage")).findFirst().map(r -> r.message()).orElse("");
        assertTrue(result.success(), "Pagila should clone successfully; verification: " + verifyMsg);

        emitAndVerifyDpiaReport(result.report(), "pagila");

        try (Connection src = sourceDs.getConnection(); Connection tgt = targetDs.getConnection()) {
            // Row volumes preserved for every cloned core table.
            for (String table : TABLES) {
                assertEquals(scalar(src, "SELECT COUNT(*) FROM " + table), scalar(tgt, "SELECT COUNT(*) FROM " + table),
                    "row count preserved for " + table);
            }

            // Composite PKs intact after the surrogate remap.
            assertEquals(scalar(src, "SELECT COUNT(DISTINCT (actor_id, film_id)) FROM film_actor"),
                scalar(tgt, "SELECT COUNT(DISTINCT (actor_id, film_id)) FROM film_actor"),
                "film_actor composite PK preserved");

            // FK integrity across the remap (a representative slice of the graph).
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM film_actor fa "
                + "WHERE NOT EXISTS (SELECT 1 FROM actor a WHERE a.actor_id = fa.actor_id) "
                + "OR NOT EXISTS (SELECT 1 FROM film f WHERE f.film_id = fa.film_id)"),
                "film_actor FKs must resolve");
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM rental r "
                + "WHERE NOT EXISTS (SELECT 1 FROM customer c WHERE c.customer_id = r.customer_id) "
                + "OR NOT EXISTS (SELECT 1 FROM inventory i WHERE i.inventory_id = r.inventory_id)"),
                "rental FKs must resolve");
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM address a "
                + "WHERE NOT EXISTS (SELECT 1 FROM city c WHERE c.city_id = a.city_id)"),
                "address → city FK must resolve");

            // PII fabricated: no real Sakila customer e-mail (the @sakilacustomer.org convention) survives.
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM customer WHERE email LIKE '%@sakilacustomer.org'"),
                "customer e-mails must be fabricated");

            // Biometric bytea (staff.picture) redacted — a staff photo is personal data, not audit-passthrough.
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM staff WHERE picture IS NOT NULL"),
                "staff photos must be dropped, not copied");

            // Postal codes fabricated into GB-format postcodes with the guaranteed-fictional
            // inward-code letter (never a real, deliverable postcode).
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM address WHERE postal_code IS NOT NULL "
                + "AND postal_code !~ '^[A-Z]{1,2}[0-9]{1,2} [0-9][A-Z]{2}$'"),
                "address.postal_code must be a GB-format postcode");
            assertEquals(0, scalar(tgt, "SELECT COUNT(*) FROM address WHERE postal_code IS NOT NULL "
                + "AND RIGHT(postal_code, 1) NOT IN ('C','I','K','M','O','V')"),
                "address.postal_code must use the guaranteed-fictional inward-code letter");

            // Views were excluded from the clone (not treated as base tables).
            Set<String> reported = result.report().tables().stream().map(tr -> tr.table()).collect(Collectors.toSet());
            assertFalse(reported.contains("customer_list"), "view customer_list not cloned");
            assertFalse(reported.contains("staff_list"), "view staff_list not cloned");
        }
    }
}
