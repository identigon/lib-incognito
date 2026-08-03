package org.identigon.incognito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.identigon.incognito.api.ColumnRole;
import org.identigon.incognito.api.DirectIdStrategy;
import org.identigon.incognito.api.IncognitoPipeline;
import org.identigon.incognito.api.PipelineResult;
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
 * Composite primary / foreign keys (SPEC §5.2) — the Phase-7 blocker.
 *
 * <p>Schema: {@code author}, {@code book}, {@code authorship(author_id, book_id)} — a join table
 * with a <b>composite PK made of two single-column FKs</b> (the Pagila {@code film_actor} shape) —
 * and {@code chapter(author_id, book_id, chapter_no)} whose {@code (author_id, book_id)} is a
 * <b>genuine composite FK</b> referencing {@code authorship}'s composite PK.
 *
 * <p>Asserts row counts, single- and composite-FK referential integrity after surrogate remapping,
 * and that identifiers are fabricated. Requires Docker; skips gracefully otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompositeKeyE2ETest {

    private PostgreSQLContainer pg;
    private DataSource sourceDs;
    private DataSource targetDs;

    private static final String DDL = """
        CREATE TABLE author (
            id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            name  VARCHAR(100) NOT NULL
        );
        CREATE TABLE book (
            id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            title  VARCHAR(100) NOT NULL
        );
        CREATE TABLE authorship (
            author_id  BIGINT NOT NULL REFERENCES author(id),
            book_id    BIGINT NOT NULL REFERENCES book(id),
            royalty    INT NOT NULL,
            PRIMARY KEY (author_id, book_id)
        );
        CREATE TABLE chapter (
            author_id   BIGINT NOT NULL,
            book_id     BIGINT NOT NULL,
            chapter_no  INT NOT NULL,
            title       VARCHAR(100) NOT NULL,
            PRIMARY KEY (author_id, book_id, chapter_no),
            FOREIGN KEY (author_id, book_id) REFERENCES authorship(author_id, book_id)
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
            pg = new PostgreSQLContainer(TestPostgres.IMAGE)
                .withDatabaseName("composite_source").withUsername("test").withPassword("test");
            pg.start();

            try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(DDL);
                    stmt.execute("INSERT INTO author (name) VALUES ('Alice'), ('Bob')");     // ids 1,2
                    stmt.execute("INSERT INTO book (title) VALUES ('BookX'), ('BookY')");     // ids 1,2
                    stmt.execute("INSERT INTO authorship (author_id, book_id, royalty) VALUES "
                        + "(1,1,10), (1,2,20), (2,1,30)");                                     // Alice-X, Alice-Y, Bob-X
                    stmt.execute("INSERT INTO chapter (author_id, book_id, chapter_no, title) VALUES "
                        + "(1,1,1,'A'), (1,1,2,'B'), (1,2,1,'C'), (2,1,1,'D')");
                }
            }

            String jdbcBase = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/";
            try (Connection admin = DriverManager.getConnection(jdbcBase + "postgres", pg.getUsername(), pg.getPassword())) {
                admin.setAutoCommit(true);
                try (Statement stmt = admin.createStatement()) {
                    stmt.execute("CREATE DATABASE composite_target");
                }
            }
            String targetUrl = jdbcBase + "composite_target";
            try (Connection conn = DriverManager.getConnection(targetUrl, pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(DDL);
                }
            }

            sourceDs = new SimpleDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            targetDs = new SimpleDataSource(targetUrl, pg.getUsername(), pg.getPassword());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set up composite-key E2E databases", e);
        }
    }

    @AfterAll
    void tearDown() {
        if (pg != null) pg.stop();
    }

    private AnonymisationPolicy policy() {
        return AnonymisationPolicy.builder()
            .table("author", t -> t
                .column("id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column("name", ColumnRole.DIRECT_ID, DirectIdStrategy.ALTEREGO_GENERIC))
            .table("book", t -> t
                .column("id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column("title", ColumnRole.PAYLOAD))
            .table("authorship", t -> t
                // composite PK columns are themselves single-column FKs
                .column(ColumnPolicy.builder("author_id").role(ColumnRole.FOREIGN_KEY).references("author", "id").build())
                .column(ColumnPolicy.builder("book_id").role(ColumnRole.FOREIGN_KEY).references("book", "id").build())
                .column("royalty", ColumnRole.PAYLOAD))
            .table("chapter", t -> t
                // genuine composite FK (author_id, book_id) -> authorship
                .column(ColumnPolicy.builder("author_id").role(ColumnRole.FOREIGN_KEY).references("authorship", "author_id").build())
                .column(ColumnPolicy.builder("book_id").role(ColumnRole.FOREIGN_KEY).references("authorship", "book_id").build())
                .column("chapter_no", ColumnRole.PAYLOAD)
                .column("title", ColumnRole.PAYLOAD))
            .build();
    }

    @Test
    void compositeKeysRemapCoherently() throws Exception {
        Assumptions.assumeTrue(sourceDs != null, "Docker/PostgreSQL not available");

        PipelineResult result = IncognitoPipeline.builder()
            .source(sourceDs).target(targetDs).ephemeralSalt().policy(policy())
            .stage(new SchemaDiscoveryStage())
            .stage(new TableTransformLoadStage())
            .stage(new VerificationStage())
            .build().execute();
        assertTrue(result.success(), "pipeline should succeed");

        try (Connection conn = targetDs.getConnection()) {
            assertEquals(2, scalar(conn, "SELECT COUNT(*) FROM author"));
            assertEquals(2, scalar(conn, "SELECT COUNT(*) FROM book"));
            assertEquals(3, scalar(conn, "SELECT COUNT(*) FROM authorship"));
            assertEquals(4, scalar(conn, "SELECT COUNT(*) FROM chapter"));

            // Single-column FK integrity on the composite-PK join table.
            assertEquals(0, scalar(conn,
                "SELECT COUNT(*) FROM authorship a WHERE NOT EXISTS (SELECT 1 FROM author p WHERE p.id=a.author_id) "
                    + "OR NOT EXISTS (SELECT 1 FROM book b WHERE b.id=a.book_id)"),
                "authorship single-column FKs must resolve");

            // Composite FK integrity: every chapter (author_id, book_id) must exist in authorship.
            assertEquals(0, scalar(conn,
                "SELECT COUNT(*) FROM chapter c WHERE NOT EXISTS "
                    + "(SELECT 1 FROM authorship a WHERE a.author_id=c.author_id AND a.book_id=c.book_id)"),
                "chapter composite FK must resolve to a real authorship row");

            // The composite PK itself is intact (no collapsed/duplicated rows).
            assertEquals(3, scalar(conn, "SELECT COUNT(DISTINCT (author_id, book_id)) FROM authorship"),
                "authorship composite PK preserved (3 distinct pairs)");

            // Identifiers fabricated.
            assertEquals(0, scalar(conn, "SELECT COUNT(*) FROM author WHERE name IN ('Alice','Bob')"),
                "author names must be fabricated");
        }
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
