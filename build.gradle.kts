plugins {
    java
}

group = "io.github.dconneely"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()
}

dependencies {
    // Direct Dependency on lib-alterego for field-level pseudonymisation
    // (published artifactId is "alterego"; see ../lib-alterego)
    implementation("io.github.dconneely:alterego:0.2.0-SNAPSHOT")
    
    // Declarative YAML Policy Parser. TODO: move to a separate incognito-yaml module so
    // the core stays dependency-lean (SPECIFICATION.md §1); currently bundled in core.
    implementation("org.yaml:snakeyaml:2.2")

    // Testing Dependencies
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher") // required by Gradle 9.x test runner
    testImplementation("com.h2database:h2:2.2.224")

    // Testcontainers for PostgreSQL integration testing (v1.0 Tier-1 engine).
    // 2.x is required for Docker Engine 29.x (older docker-java probes API 1.32, which the daemon
    // rejects; needs ≥1.40). NOTE 2.x renamed the module artifacts (testcontainers-* prefix) and
    // moved PostgreSQLContainer to package org.testcontainers.postgresql.
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // PostgreSQL JDBC driver — the walking-skeleton test connects via raw DriverManager.
    testRuntimeOnly("org.postgresql:postgresql:42.7.3")
}
