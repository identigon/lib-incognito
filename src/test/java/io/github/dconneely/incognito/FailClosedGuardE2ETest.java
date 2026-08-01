package io.github.dconneely.incognito;

import static io.github.dconneely.incognito.api.ColumnRole.FOREIGN_KEY;
import static io.github.dconneely.incognito.api.ColumnRole.PAYLOAD;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dconneely.incognito.api.ColumnRole;
import io.github.dconneely.incognito.api.IncognitoException;
import io.github.dconneely.incognito.api.IncognitoPipeline;
import io.github.dconneely.incognito.api.SurrogateStrategy;
import io.github.dconneely.incognito.core.SchemaDiscoveryStage;
import io.github.dconneely.incognito.core.TableTransformLoadStage;
import io.github.dconneely.incognito.core.VerificationStage;
import io.github.dconneely.incognito.policy.AnonymisationPolicy;
import io.github.dconneely.incognito.policy.ColumnPolicy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Exercises the fail-closed guards for load configurations Incognito cannot safely handle — cases
 * that must abort loudly, never silently corrupt or drop (SPEC §5.2/§9, §7.3 invariant 6):
 * <ul>
 *   <li>a <b>non-superuser</b> target with cyclic FKs (cannot suppress FK enforcement) → fail fast;</li>
 *   <li>a <b>composite FK referencing a cyclic table</b> → not yet supported;</li>
 *   <li>a <b>cyclic FK on a table with no single-column PK</b> → nothing to key the pass-2 UPDATE on.</li>
 * </ul>
 * Every other E2E test runs as superuser with resolvable keys, so these paths were previously
 * unexercised. Requires Docker; skips gracefully otherwise.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FailClosedGuardE2ETest {

    private PostgreSQLContainer pg;
    private String jdbcBase;

    @BeforeAll
    void setUp() {
        boolean dockerAvailable;
        try {
            dockerAvailable = org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            dockerAvailable = false;
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker not available — skipping Testcontainers E2E");

        pg = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("guard").withUsername("test").withPassword("test");
        pg.start();
        jdbcBase = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getFirstMappedPort() + "/";
    }

    @AfterAll
    void tearDown() {
        if (pg != null) pg.stop();
    }

    // --- infra helpers ---
    private void admin(String sql) throws SQLException { exec(jdbcBase + "postgres", "test", "test", sql); }

    private void exec(String url, String user, String pass, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            c.setAutoCommit(true);
            try (Statement s = c.createStatement()) { s.execute(sql); }
        }
    }

    /** Creates a fresh source+target DB pair (owned by superuser {@code test}); loads {@code sourceSql}
     *  into the source and {@code targetDdl} into the target. */
    private void newPair(String name, String sourceSql, String targetDdl) throws SQLException {
        admin("DROP DATABASE IF EXISTS " + name + "_source");
        admin("DROP DATABASE IF EXISTS " + name + "_target");
        admin("CREATE DATABASE " + name + "_source");
        admin("CREATE DATABASE " + name + "_target");
        exec(jdbcBase + name + "_source", "test", "test", sourceSql);
        exec(jdbcBase + name + "_target", "test", "test", targetDdl);
    }

    private DataSource ds(String db, String user, String pass) {
        return new SimpleDataSource(jdbcBase + db, user, pass);
    }

    private static void run(DataSource src, DataSource tgt, AnonymisationPolicy policy) {
        IncognitoPipeline.builder().source(src).target(tgt).ephemeralSalt().policy(policy)
            .stage(new SchemaDiscoveryStage())
            .stage(new TableTransformLoadStage())
            .stage(new VerificationStage())
            .build().execute();
    }

    // ---------------------------------------------------------------------------------------------
    // Guard 1: a non-superuser target with cyclic FKs cannot suppress FK enforcement → fail fast.
    // ---------------------------------------------------------------------------------------------
    @Test
    void nonSuperuserWithCyclicFkFailsFast() throws Exception {
        Assumptions.assumeTrue(pg != null, "Docker not available");

        String ddl = """
            CREATE TABLE emp (
                id    INT PRIMARY KEY,
                boss  INT REFERENCES emp(id)
            );
            """;
        newPair("nosuper", ddl + "INSERT INTO emp (id, boss) VALUES (1, NULL), (2, 1);", ddl);

        // A real non-superuser role that can connect to the target but cannot set replica role.
        admin("DROP ROLE IF EXISTS limited");
        admin("CREATE ROLE limited LOGIN PASSWORD 'lim' NOSUPERUSER");
        admin("GRANT CONNECT ON DATABASE nosuper_target TO limited");

        AnonymisationPolicy policy = AnonymisationPolicy.builder()
            .table("emp", t -> t
                .column("id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column(ColumnPolicy.builder("boss").role(FOREIGN_KEY).references("emp", "id").build()))
            .build();

        IncognitoException.ConfigException ex = assertThrows(IncognitoException.ConfigException.class,
            () -> run(ds("nosuper_source", "test", "test"), ds("nosuper_target", "limited", "lim"), policy));
        assertTrue(ex.getMessage().contains("cyclic"), "message names the cyclic-FK cause: " + ex.getMessage());
    }

    // ---------------------------------------------------------------------------------------------
    // Guard 4: a composite FK referencing a cyclic table is not yet supported → fail closed.
    // ---------------------------------------------------------------------------------------------
    @Test
    void compositeFkToCyclicTableFailsClosed() throws Exception {
        Assumptions.assumeTrue(pg != null, "Docker not available");

        // Self-referential COMPOSITE FK: (pgrp, pid) -> (grp, id) on the same table. The deferrable
        // constraint lets the source hold a 2-cycle, guaranteeing the lookup misses whichever row is
        // transformed first.
        String ddl = """
            CREATE TABLE tree (
                grp   INT NOT NULL,
                id    INT NOT NULL,
                pgrp  INT,
                pid   INT,
                PRIMARY KEY (grp, id),
                FOREIGN KEY (pgrp, pid) REFERENCES tree(grp, id) DEFERRABLE INITIALLY DEFERRED
            );
            """;
        newPair("comp", ddl + "INSERT INTO tree (grp, id, pgrp, pid) VALUES (1, 1, 1, 2), (1, 2, 1, 1);", ddl);

        AnonymisationPolicy policy = AnonymisationPolicy.builder()
            .table("tree", t -> t
                .column("grp", PAYLOAD).column("id", PAYLOAD)
                .column(ColumnPolicy.builder("pgrp").role(FOREIGN_KEY).references("tree", "grp").build())
                .column(ColumnPolicy.builder("pid").role(FOREIGN_KEY).references("tree", "id").build()))
            .build();

        IncognitoException.ConstraintException ex = assertThrows(IncognitoException.ConstraintException.class,
            () -> run(ds("comp_source", "test", "test"), ds("comp_target", "test", "test"), policy));
        assertTrue(ex.getMessage().contains("composite") && ex.getMessage().contains("cyclic"),
            "message names the composite+cyclic cause: " + ex.getMessage());
    }

    // ---------------------------------------------------------------------------------------------
    // Guard 2/3: a cyclic FK on a table with no single-column PK has nothing to UPDATE in pass 2.
    // ---------------------------------------------------------------------------------------------
    @Test
    void cyclicFkWithoutPrimaryKeyFailsClosed() throws Exception {
        Assumptions.assumeTrue(pg != null, "Docker not available");

        // Self-referential single-column FK, but the table has a UNIQUE key and NO primary key, so the
        // deferred pass-2 UPDATE has no PK to key on.
        String ddl = """
            CREATE TABLE loops (
                uid   INT UNIQUE NOT NULL,
                boss  INT REFERENCES loops(uid)
            );
            """;
        newPair("nopk", ddl + "INSERT INTO loops (uid, boss) VALUES (1, NULL), (2, 1);", ddl);

        AnonymisationPolicy policy = AnonymisationPolicy.builder()
            .table("loops", t -> t
                .column("uid", PAYLOAD)
                .column(ColumnPolicy.builder("boss").role(FOREIGN_KEY).references("loops", "uid").build()))
            .build();

        IncognitoException.ConstraintException ex = assertThrows(IncognitoException.ConstraintException.class,
            () -> run(ds("nopk_source", "test", "test"), ds("nopk_target", "test", "test"), policy));
        assertTrue(ex.getMessage().contains("primary key") || ex.getMessage().contains("cyclic"),
            "message names the missing-PK cause: " + ex.getMessage());
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
