package org.identigon.incognito.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.identigon.incognito.TestPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Regression test: {@link SchemaInspector#inspect} must not record a column as independently
 * unique ({@code TableMetadata.uniqueCandidateKeys()}) when it is merely one column of a
 * multi-column composite unique index — only the sole column of a genuinely single-column unique
 * index qualifies.
 *
 * <p>Requires Docker; skips gracefully otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchemaInspectorCompositeUniqueIndexTest {

    private PostgreSQLContainer pg;
    private DataSource dataSource;

    @BeforeAll
    void setUp() throws Exception {
        boolean dockerAvailable;
        try {
            dockerAvailable = org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            dockerAvailable = false;
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker not available — skipping schema-inspector E2E");

        pg = new PostgreSQLContainer(TestPostgres.IMAGE)
            .withDatabaseName("composite_unique").withUsername("test").withPassword("test");
        pg.start();

        try (Connection conn = DriverManager.getConnection(pg.getJdbcUrl(), "test", "test");
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE membership (
                    id         INT PRIMARY KEY,
                    email      VARCHAR(100) UNIQUE,
                    tenant_id  INT NOT NULL,
                    code       VARCHAR(20) NOT NULL,
                    UNIQUE (tenant_id, code)
                );
                """);
        }

        dataSource = new SimpleDataSource(pg.getJdbcUrl(), "test", "test");
    }

    @AfterAll
    void tearDown() {
        if (pg != null) pg.stop();
    }

    @Test
    void onlySingleColumnUniqueIndexesAreRecordedAsCandidateKeys() {
        Assumptions.assumeTrue(dataSource != null, "Docker/PostgreSQL not available");

        List<SchemaInspector.TableMetadata> metadata = new SchemaInspector().inspect(dataSource);
        SchemaInspector.TableMetadata membership = metadata.stream()
            .filter(t -> t.tableName().equals("membership")).findFirst().orElseThrow();

        assertTrue(membership.uniqueCandidateKeys().contains("email"),
            "a column with its own single-column UNIQUE constraint must be recorded");
        assertFalse(membership.uniqueCandidateKeys().contains("tenant_id"),
            "a column that is only part of a composite UNIQUE(tenant_id, code) must NOT be "
                + "recorded as independently unique");
        assertFalse(membership.uniqueCandidateKeys().contains("code"),
            "a column that is only part of a composite UNIQUE(tenant_id, code) must NOT be "
                + "recorded as independently unique");
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
