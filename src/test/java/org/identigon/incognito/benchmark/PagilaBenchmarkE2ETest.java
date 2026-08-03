package org.identigon.incognito.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.identigon.incognito.api.IncognitoPipeline;
import org.identigon.incognito.api.PipelineResult;
import org.identigon.incognito.core.SchemaDiscoveryStage;
import org.identigon.incognito.core.TableTransformLoadStage;
import org.identigon.incognito.core.VerificationStage;
import org.identigon.incognito.policy.AnonymisationPolicy;
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
 * <p>Schema is vendored; the ~5 MB {@code pagila-insert-data.sql} is fetched at test time and
 * SHA-256-verified (see {@code benchmarks/SOURCES.md}). Requires Docker and network; skips gracefully
 * if either is unavailable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PagilaBenchmarkE2ETest {

    private static final String DATA_URL =
        "https://raw.githubusercontent.com/devrimgunduz/pagila/refs/tags/pagila-v3.0.0/pagila-insert-data.sql";
    private static final String DATA_SHA256 =
        "136f3105263a1338a9805da4c06b6b37b60f1abc15ce7dbc8d6f5501f506aa22";

    /** The non-partitioned Sakila core tables the benchmark clones (payment + its partitions excluded). */
    private static final List<String> TABLES = List.of(
        "actor", "address", "category", "city", "country", "customer", "film", "film_actor",
        "film_category", "inventory", "language", "rental", "staff", "store");

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
        Assumptions.assumeTrue(dockerAvailable, "Docker not available — skipping Pagila benchmark");

        // Fetch + verify the data; skip (not fail) if the network is unavailable.
        String insertData = fetchAndVerify(DATA_URL, DATA_SHA256);
        Assumptions.assumeTrue(insertData != null, "Could not fetch Pagila data — skipping");

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

        pg = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("pagila_source").withUsername("test").withPassword("test");
        pg.start();

        try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), "test", "test")) {
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(schemaLoadable);
                stmt.execute(sourceData);
            }
        }

        String jdbcBase = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/";
        try (Connection admin = DriverManager.getConnection(jdbcBase + "postgres", "test", "test");
             Statement stmt = admin.createStatement()) {
            stmt.execute("CREATE DATABASE pagila_target");
        }
        String targetUrl = jdbcBase + "pagila_target";
        try (Connection conn = DriverManager.getConnection(targetUrl, "test", "test");
             Statement stmt = conn.createStatement()) {
            stmt.execute(schemaLoadable);
        }

        sourceDs = new SimpleDataSource(pg.getJdbcUrl(), "test", "test");
        targetDs = new SimpleDataSource(targetUrl, "test", "test");
    }

    @AfterAll
    void tearDown() {
        if (pg != null) pg.stop();
    }


    /** Loads the policy from a YAML test resource (exercises the {@code YamlPolicyParser} path E2E). */
    private AnonymisationPolicy policy() throws Exception {
        try (var in = PagilaBenchmarkE2ETest.class.getResourceAsStream("/benchmarks/pagila/policy.yaml")) {
            if (in == null) throw new IllegalStateException("missing test resource: /benchmarks/pagila/policy.yaml");
            return new org.identigon.incognito.policy.YamlPolicyParser().parse(in);
        }
    }

    @Test
    void pagilaClonesCoherently() throws Exception {
        Assumptions.assumeTrue(sourceDs != null, "Docker/PostgreSQL/network not available");

        PipelineResult result = IncognitoPipeline.builder()
            .source(sourceDs).target(targetDs).ephemeralSalt().policy(policy())
            .stage(new SchemaDiscoveryStage())
            .stage(new TableTransformLoadStage())
            .stage(new VerificationStage())
            .build().execute();
        String verifyMsg = result.report().stageResults().stream()
            .filter(r -> r.stageName().equals("VerificationStage")).findFirst().map(r -> r.message()).orElse("");
        assertTrue(result.success(), "Pagila should clone successfully; verification: " + verifyMsg);

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

    private static String fetchAndVerify(String url, String expectedSha) {
        try {
            HttpResponse<byte[]> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) return null;
            byte[] body = resp.body();
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            if (!sha.equals(expectedSha)) {
                throw new IllegalStateException("Pagila data SHA-256 mismatch: expected " + expectedSha + " got " + sha);
            }
            return new String(body, StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            return null; // network unavailable — caller skips
        }
    }

    private long scalar(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String resource(String path) throws Exception {
        try (var in = PagilaBenchmarkE2ETest.class.getResourceAsStream(path)) {
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
