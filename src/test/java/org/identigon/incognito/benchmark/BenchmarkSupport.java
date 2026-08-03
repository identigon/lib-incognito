package org.identigon.incognito.benchmark;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.identigon.incognito.api.AnonymisationReport;
import org.identigon.incognito.core.DpiaArtifactEmitter;
import org.identigon.incognito.policy.AnonymisationPolicy;
import org.identigon.incognito.policy.YamlPolicyParser;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared plumbing for the Phase-7 benchmark E2E tests, so each benchmark's own test class reads as
 * just its schema, its fixture-loading quirk (if any), and its assertions — not repeated JDBC/JUnit
 * boilerplate.
 */
final class BenchmarkSupport {

    private BenchmarkSupport() {}

    /**
     * Skips the calling test class (via {@link Assumptions#assumeTrue}) if Docker is not available.
     *
     * @param benchmarkName the benchmark's name, for the skip message
     */
    static void assumeDockerAvailable(String benchmarkName) {
        boolean dockerAvailable;
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            dockerAvailable = false;
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker not available — skipping " + benchmarkName + " benchmark");
    }

    /**
     * Reads a classpath resource fully as a UTF-8 string. The path is absolute (starts with
     * {@code /}), so which class loads it from is irrelevant.
     *
     * @param path an absolute classpath resource path
     * @return the resource's full content
     */
    static String resource(String path) throws Exception {
        try (var in = BenchmarkSupport.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing test resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Parses a policy from a YAML classpath resource (exercises the {@code YamlPolicyParser} path
     * end-to-end, the same as a real caller would).
     *
     * @param yamlResourcePath an absolute classpath resource path to a {@code policy.yaml}
     * @return the parsed policy
     */
    static AnonymisationPolicy loadPolicy(String yamlResourcePath) throws Exception {
        try (var in = BenchmarkSupport.class.getResourceAsStream(yamlResourcePath)) {
            if (in == null) throw new IllegalStateException("missing test resource: " + yamlResourcePath);
            return new YamlPolicyParser().parse(in);
        }
    }

    /**
     * A plain JDBC-backed {@link DataSource} for the given connection details.
     *
     * @param url the JDBC URL
     * @param user the database user
     * @param password the database password
     * @return a {@link DataSource} that opens a fresh connection per call
     */
    static DataSource dataSource(String url, String user, String password) {
        return new SimpleDataSource(url, user, password);
    }

    /**
     * Opens a connection to {@code ds} and executes {@code sql} as a single statement (autocommit
     * on) — for DDL/bulk-load scripts, not parameterised queries.
     *
     * @param ds the data source to execute against
     * @param sql the SQL to execute
     */
    static void execute(DataSource ds, String sql) throws SQLException {
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
            }
        }
    }

    /**
     * Creates a fresh database named {@code dbName} on the same PostgreSQL container as {@code pg}
     * (reusing its host/port/credentials), and returns a {@link DataSource} for it.
     *
     * @param pg the running PostgreSQL container to create the database on
     * @param dbName the new database's name
     * @return a {@link DataSource} for the newly created database
     */
    static DataSource createTargetDatabase(PostgreSQLContainer pg, String dbName) throws SQLException {
        String jdbcBase = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/";
        execute(dataSource(jdbcBase + "postgres", pg.getUsername(), pg.getPassword()), "CREATE DATABASE " + dbName);
        return dataSource(jdbcBase + dbName, pg.getUsername(), pg.getPassword());
    }

    /**
     * Runs {@code sql} and returns the first column of its first row as a {@code long} — for
     * {@code COUNT(*)}-shaped assertions.
     *
     * @param conn the connection to query on
     * @param sql the query to run
     * @return the scalar result
     */
    static long scalar(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Demonstrates the DPIA artifact-emission path (opt-in per the library's design; see PLAN.md)
     * against a real, full-scale report from an actual pipeline run — not just the synthetic
     * single-table sample in {@code DpiaArtifactEmitterTest}. Writes JSON, HTML and Markdown under
     * {@code build/dpia-reports/<benchmarkName>/} and asserts each file was actually written.
     *
     * @param report the pipeline's typed accountability report
     * @param benchmarkName the benchmark's name, used as the output subdirectory
     */
    static void emitAndVerifyDpiaReport(AnonymisationReport report, String benchmarkName) throws Exception {
        Path dir = Path.of("build", "dpia-reports", benchmarkName);
        Files.createDirectories(dir);
        Path jsonReport = dir.resolve("report.json");
        Path htmlReport = dir.resolve("report.html");
        Path mdReport = dir.resolve("report.md");
        DpiaArtifactEmitter.emitJson(report, jsonReport);
        DpiaArtifactEmitter.emitHtml(report, htmlReport);
        DpiaArtifactEmitter.emitMarkdown(report, mdReport);
        assertTrue(Files.size(jsonReport) > 0, "DPIA JSON report must be written");
        assertTrue(Files.size(htmlReport) > 0, "DPIA HTML report must be written");
        assertTrue(Files.size(mdReport) > 0, "DPIA Markdown report must be written");
        // A run through the builder must disclose how it was keyed (SPEC §5.1) — proves the
        // salt-mode attribute is wired from the builder through to the report end-to-end.
        assertNotNull(report.saltMode(), "DPIA report must disclose the salt mode");
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
