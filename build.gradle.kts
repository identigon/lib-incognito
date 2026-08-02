plugins {
    `java-library`
    `maven-publish`
    // Minimal code hygiene, kept in step with sibling repos (../play-bazlang, ../lib-alterego).
    // Tidy-only (no googleJavaFormat) so it does not reflow the hand-maintained style. SpotBugs +
    // find-sec-bugs is a tracked follow-up (see PLAN).
    id("com.diffplug.spotless") version "8.8.0"
    id("com.github.spotbugs") version "6.5.9"
}

group = "org.identigon"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    // Maven Central requires both alongside the binary jar.
    withSourcesJar()
    withJavadocJar()
}

// Light, non-reflowing hygiene: tidy imports/whitespace only, never a full reformat (which would
// fight the hand-maintained style). `spotlessCheck` runs as part of `check`; `spotlessApply` fixes.
spotless {
    java {
        target("src/**/*.java")
        importOrder()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

repositories {
    mavenCentral()
    // lib-alterego is consumed as a local -SNAPSHOT until it is published to a shared repository
    // (see PLAN.md "Build prerequisite").
    mavenLocal()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).apply {
        // A doclint warning fails the build instead of the backlog quietly accumulating (mirrors
        // lib-alterego). The full `all` group is enforced — including `missing` (a doc comment /
        // @param / @return / @throws on every public element) — so the published javadoc jar is
        // complete and warning-free.
        addBooleanOption("Xdoclint:all", true)
        addBooleanOption("Xwerror", true)
    }
}

dependencies {
    // lib-alterego is exposed through Incognito's public API (e.g. PipelineContext.alterEgo()), so it
    // is `api`, not `implementation` — consumers writing custom stages compile against its types.
    api("org.identigon:alterego:0.3.0")

    // Declarative YAML policy parser — an internal detail. TODO: move to a separate incognito-yaml
    // module so the core stays dependency-lean (SPECIFICATION.md §1); currently bundled in core.
    implementation("org.yaml:snakeyaml:2.2")

    // Testing dependencies
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher") // required by the Gradle 9.x test runner
    testImplementation("com.h2database:h2:2.2.224")

    // Testcontainers for PostgreSQL integration testing (v1.0 Tier-1 engine).
    // 2.x is required for Docker Engine 29.x (older docker-java probes API 1.32, which the daemon
    // rejects; needs ≥1.40). NOTE 2.x renamed the module artifacts (testcontainers-* prefix) and
    // moved PostgreSQLContainer to package org.testcontainers.postgresql.
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // PostgreSQL JDBC driver — the integration tests connect via raw DriverManager.
    testRuntimeOnly("org.postgresql:postgresql:42.7.3")

    spotbugsPlugins("com.h3xstream.findsecbugs:findsecbugs-plugin:1.13.0")
}

tasks.test {
    useJUnitPlatform {
        includeEngines("junit-jupiter")
    }
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// The LICENCE must travel inside the built artifact: most consumers receive only the jar, never the
// repository, so packaging it at the repo root alone is not enough (Maven Central also expects it).
// No NOTICE is packaged — Incognito bundles no third-party data in the jar (the benchmark fixtures
// under src/test/resources are test-only); their attribution lives with them, not in the artifact.
tasks.named<Jar>("jar") {
    from(rootProject.file("LICENCE")) {
        into("META-INF")
    }
}

// No repository/credentials are configured here — that's environment-specific and not this project's
// job to commit. This produces a correct, complete POM plus the three artifact jars (binary, sources,
// javadoc) for `./gradlew publishToMavenLocal`; wiring an actual remote is a separate, later decision.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "incognito" // the library's own name, distinct from the lib-incognito repo/directory name

            pom {
                name = "Incognito"
                description = "A Java library that clones a production database into a schema-identical " +
                    "test database with all PII replaced by clearly fictional data."
                url = "https://github.com/identigon/lib-incognito"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://github.com/identigon/lib-incognito/blob/main/LICENCE"
                    }
                }
                developers {
                    developer {
                        id = "identigon"
                        name = "Identigon"
                    }
                }
                scm {
                    connection = "scm:git:https://github.com/identigon/lib-incognito.git"
                    developerConnection = "scm:git:https://github.com/identigon/lib-incognito.git"
                    url = "https://github.com/identigon/lib-incognito"
                }
            }
        }
    }
}

spotbugs {
    toolVersion = "4.9.8"
    ignoreFailures = false
    excludeFilter = file("config/spotbugs/exclude.xml")
}

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports {
        create("html") {
            required = true
        }
        create("xml") {
            required = false
        }
    }
}
