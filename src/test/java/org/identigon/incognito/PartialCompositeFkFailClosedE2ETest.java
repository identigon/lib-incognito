package org.identigon.incognito;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.identigon.incognito.api.ColumnRole;
import org.identigon.incognito.api.IncognitoException;
import org.identigon.incognito.api.IncognitoPipeline;
import org.identigon.incognito.api.SurrogateStrategy;
import org.identigon.incognito.core.SchemaDiscoveryStage;
import org.identigon.incognito.core.TableTransformLoadStage;
import org.identigon.incognito.policy.AnonymisationPolicy;
import org.identigon.incognito.policy.ColumnPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Regression test: a composite FK that references a {@code UNIQUE} constraint narrower than the
 * parent's actual (wider) primary key must fail closed at transform-time, not silently return each
 * row's real, untranslated FK value forever (SPEC §7.2).
 *
 * <p>{@code authorship}'s real PK is {@code (author_id, book_id, edition)} (3 columns); {@code
 * chapter}'s composite FK references only {@code UNIQUE (author_id, book_id)} (2 columns) — the FK
 * cannot resolve via the key store, which only tracks PK-based surrogate mappings.
 *
 * <p>Requires Docker; skips gracefully otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PartialCompositeFkFailClosedE2ETest {

    private PostgreSQLContainer pg;
    private DataSource sourceDs;
    private DataSource targetDs;

    private static final String DDL = """
        CREATE TABLE authorship (
            author_id  BIGINT NOT NULL,
            book_id    BIGINT NOT NULL,
            edition    INT NOT NULL DEFAULT 1,
            PRIMARY KEY (author_id, book_id, edition),
            UNIQUE (author_id, book_id)
        );
        CREATE TABLE chapter (
            author_id   BIGINT NOT NULL,
            book_id     BIGINT NOT NULL,
            chapter_no  INT NOT NULL,
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
                .withDatabaseName("partial_fk_source").withUsername("test").withPassword("test");
            pg.start();

            try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(DDL);
                    stmt.execute("INSERT INTO authorship (author_id, book_id, edition) VALUES (1, 1, 1)");
                    stmt.execute("INSERT INTO chapter (author_id, book_id, chapter_no) VALUES (1, 1, 1)");
                }
            }

            String jdbcBase = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/";
            try (Connection admin = DriverManager.getConnection(jdbcBase + "postgres", pg.getUsername(), pg.getPassword())) {
                admin.setAutoCommit(true);
                try (Statement stmt = admin.createStatement()) {
                    stmt.execute("CREATE DATABASE partial_fk_target");
                }
            }
            String targetUrl = jdbcBase + "partial_fk_target";
            try (Connection conn = DriverManager.getConnection(targetUrl, pg.getUsername(), pg.getPassword())) {
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(DDL);
                }
            }

            sourceDs = new SimpleDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            targetDs = new SimpleDataSource(targetUrl, pg.getUsername(), pg.getPassword());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set up partial-composite-FK E2E databases", e);
        }
    }

    @AfterAll
    void tearDown() {
        if (pg != null) pg.stop();
    }

    private AnonymisationPolicy policy() {
        return AnonymisationPolicy.builder()
            .table("authorship", t -> t
                .column(ColumnPolicy.builder("author_id").role(ColumnRole.PRIMARY_KEY).surrogateStrategy(SurrogateStrategy.PASSTHROUGH_SURROGATE).build())
                .column(ColumnPolicy.builder("book_id").role(ColumnRole.PRIMARY_KEY).surrogateStrategy(SurrogateStrategy.PASSTHROUGH_SURROGATE).build())
                .column(ColumnPolicy.builder("edition").role(ColumnRole.PRIMARY_KEY).surrogateStrategy(SurrogateStrategy.PASSTHROUGH_SURROGATE).build()))
            .table("chapter", t -> t
                .column(ColumnPolicy.builder("author_id").role(ColumnRole.FOREIGN_KEY).references("authorship", "author_id").build())
                .column(ColumnPolicy.builder("book_id").role(ColumnRole.FOREIGN_KEY).references("authorship", "book_id").build())
                .column("chapter_no", ColumnRole.PAYLOAD))
            .build();
    }

    @Test
    void partialCompositeFkFailsClosedInsteadOfPassingRealValueThrough() {
        Assumptions.assumeTrue(sourceDs != null, "Docker/PostgreSQL not available");

        IncognitoException.ConstraintException ex = assertThrows(IncognitoException.ConstraintException.class, () ->
            IncognitoPipeline.builder()
                .source(sourceDs).target(targetDs).ephemeralSalt().policy(policy())
                .stage(new SchemaDiscoveryStage())
                .stage(new TableTransformLoadStage())
                .build()
                .execute(),
            "a composite FK not covering the full parent PK must fail closed");

        assertTrue(ex.getMessage().contains("does not cover every column"),
            "message should explain the partial-composite-FK problem: " + ex.getMessage());
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
