package org.identigon.incognito;

/**
 * The single PostgreSQL Docker image every Testcontainers-based test in this module runs against,
 * so bumping the version is a one-line change instead of an edit to every test file.
 */
public final class TestPostgres {

    private TestPostgres() {}

    /** The Testcontainers {@code PostgreSQLContainer} image tag used by every integration test. */
    public static final String IMAGE = "postgres:18-alpine";
}
