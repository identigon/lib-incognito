package io.github.dconneely.incognito.core;

import io.github.dconneely.incognito.api.ColumnRole;
import io.github.dconneely.incognito.api.DirectIdStrategy;
import io.github.dconneely.incognito.api.DistinguishingLint;
import io.github.dconneely.incognito.api.IncognitoException;
import io.github.dconneely.incognito.api.PipelineContext;
import io.github.dconneely.incognito.api.PipelineStage;
import io.github.dconneely.incognito.engine.SchemaInspector;
import io.github.dconneely.incognito.engine.TableDependencyGraph;
import io.github.dconneely.incognito.policy.AnonymisationPolicy;
import io.github.dconneely.incognito.policy.ColumnPolicy;
import io.github.dconneely.incognito.policy.TablePolicy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Stage 4: Verifies the target database after loading.
 * <ul>
 *   <li>Referential integrity: no dangling FK references.</li>
 *   <li>Fictionality: DIRECT_ID email columns use RFC 2606 reserved domains.</li>
 *   <li>Misdeclaration lint (SPEC §4.1): cross-checks every {@code SENSITIVE distinguishing: false}
 *       column's real {@code COUNT(DISTINCT)} against {@code maxCategoricalCardinality}. Behaviour
 *       is set by {@code distinguishingLint}: {@code WARN} (default) records a warning;
 *       {@code ERROR} fails the run; {@code OFF} skips the check entirely.</li>
 * </ul>
 */
public final class VerificationStage implements PipelineStage {

    /** RFC 2606 reserved domains that AlterEgo uses for fictional emails. */
    private static final List<String> RESERVED_EMAIL_DOMAINS = List.of(
        "example.com", "example.net", "example.org",
        "example.co.uk", "example.org.uk"
    );

    /**
     * Margin above the threshold at which we skip the pg_stats pre-filter and run the exact count
     * directly. If pg_stats reports n_distinct within this factor of the threshold we fall through
     * to the exact query to avoid false negatives from stale statistics.
     */
    private static final double PG_STATS_MARGIN = 2.0;

    @Override
    @SuppressWarnings("unchecked")
    public StageResult process(PipelineContext context) throws IncognitoException {
        List<SchemaInspector.TableMetadata> allMetadata =
            (List<SchemaInspector.TableMetadata>) context.attributes().get(SchemaDiscoveryStage.ATTR_TABLE_METADATA);
        TableDependencyGraph.TopologicalExecutionPlan plan =
            (TableDependencyGraph.TopologicalExecutionPlan) context.attributes().get(SchemaDiscoveryStage.ATTR_EXECUTION_PLAN);

        if (allMetadata == null || plan == null) {
            throw new IncognitoException.ConfigException(
                "SchemaDiscoveryStage must run before VerificationStage");
        }

        Map<String, SchemaInspector.TableMetadata> metadataByName = allMetadata.stream()
            .collect(Collectors.toMap(SchemaInspector.TableMetadata::tableName, m -> m));

        AnonymisationPolicy policy = context.policy();
        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try (Connection targetConn = context.target().getConnection()) {
            // 1. Verify referential integrity on the target.
            for (String tableName : plan.sequentialTableOrder()) {
                SchemaInspector.TableMetadata meta = metadataByName.get(tableName);
                if (meta == null) continue;

                for (Map.Entry<String, String> fk : meta.foreignKeys().entrySet()) {
                    String fkColumn = fk.getKey();
                    String parentTable = fk.getValue();
                    SchemaInspector.TableMetadata parentMeta = metadataByName.get(parentTable);
                    if (parentMeta == null || parentMeta.primaryKeyColumns().isEmpty()) continue;

                    String parentPk = parentMeta.primaryKeyColumns().getFirst();

                    String checkSql = "SELECT COUNT(*) FROM " + tableName + " c "
                        + "WHERE c." + fkColumn + " IS NOT NULL "
                        + "AND NOT EXISTS (SELECT 1 FROM " + parentTable + " p WHERE p." + parentPk + " = c." + fkColumn + ")";

                    try (Statement stmt = targetConn.createStatement();
                         ResultSet rs = stmt.executeQuery(checkSql)) {
                        if (rs.next() && rs.getLong(1) > 0) {
                            failures.add("Dangling FK: " + tableName + "." + fkColumn
                                + " has " + rs.getLong(1) + " orphaned references to " + parentTable);
                        }
                    }
                }
            }

            // 2. Verify fictionality of email DIRECT_ID columns on the target.
            for (String tableName : plan.sequentialTableOrder()) {
                Optional<TablePolicy> tablePolicyOpt = policy.table(tableName);
                if (tablePolicyOpt.isEmpty()) continue;

                TablePolicy tablePolicy = tablePolicyOpt.get();
                for (Map.Entry<String, ColumnPolicy> entry : getColumnPolicies(tablePolicy)) {
                    ColumnPolicy colPolicy = entry.getValue();
                    if (colPolicy.role() == ColumnRole.DIRECT_ID
                            && colPolicy.directIdStrategy() == DirectIdStrategy.ALTEREGO_EMAIL) {
                        verifyEmailFictionality(targetConn, tableName, colPolicy.columnName(), failures);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IncognitoException.SchemaException("Verification failed", e);
        }

        // 3. Misdeclaration lint: cross-check distinguishing:false columns against real distinct
        //    counts on the SOURCE database (SPEC §4.1). This is NOT the privacy gate — the
        //    distinguishing declaration is — this is a safety net against misdeclaration.
        DistinguishingLint lintMode = policy.distinguishingLint();
        if (lintMode != DistinguishingLint.OFF) {
            int threshold = policy.maxCategoricalCardinality();
            try (Connection sourceConn = context.source().getConnection()) {
                for (String tableName : plan.sequentialTableOrder()) {
                    Optional<TablePolicy> tablePolicyOpt = policy.table(tableName);
                    if (tablePolicyOpt.isEmpty()) continue;

                    TablePolicy tablePolicy = tablePolicyOpt.get();
                    for (Map.Entry<String, ColumnPolicy> entry : getColumnPolicies(tablePolicy)) {
                        ColumnPolicy colPolicy = entry.getValue();
                        if (colPolicy.role() != ColumnRole.SENSITIVE) continue;
                        if (!Boolean.FALSE.equals(colPolicy.distinguishing())) continue;
                        // This is a SENSITIVE distinguishing:false column — check its real cardinality.

                        long distinctCount = countDistinctWithPgStatsPreFilter(
                            sourceConn, tableName, colPolicy.columnName(), threshold);

                        if (distinctCount > threshold) {
                            String msg = "Misdeclaration lint: " + tableName + "." + colPolicy.columnName()
                                + " is declared distinguishing: false but has " + distinctCount
                                + " distinct values (threshold: " + threshold + ")."
                                + " Consider whether this column should be distinguishing: true.";

                            if (lintMode == DistinguishingLint.ERROR) {
                                throw new IncognitoException.ConstraintException(msg);
                            }
                            // WARN: record the warning and continue.
                            warnings.add(msg);
                        }
                    }
                }
            } catch (IncognitoException e) {
                throw e; // re-throw ConstraintException from ERROR mode
            } catch (SQLException e) {
                throw new IncognitoException.SchemaException(
                    "Misdeclaration lint failed querying source database", e);
            }
        }

        // Build the result message.
        if (!failures.isEmpty()) {
            String msg = "Verification FAILED:\n  " + String.join("\n  ", failures);
            if (!warnings.isEmpty()) {
                msg += "\nWarnings:\n  " + String.join("\n  ", warnings);
            }
            return new StageResult("VerificationStage", false, failures.size(), msg);
        }

        String msg = "All verifications passed";
        if (!warnings.isEmpty()) {
            msg += "\nWarnings:\n  " + String.join("\n  ", warnings);
        }
        return new StageResult("VerificationStage", true, warnings.size(), msg);
    }

    /**
     * Returns the distinct count of a column, using PostgreSQL {@code pg_stats.n_distinct} as a
     * cheap pre-filter where available (SPEC §4.1). If pg_stats reports a value comfortably below
     * the threshold we can skip the exact scan. Near the boundary or on non-PostgreSQL databases
     * we fall through to the exact {@code COUNT(DISTINCT)}.
     *
     * <p>pg_stats encodes: positive values = estimated distinct count; negative values = fraction
     * of rows (e.g. −1.0 = all unique). A negative value or a value near/above the threshold
     * triggers the exact count.
     */
    private long countDistinctWithPgStatsPreFilter(
            Connection conn, String tableName, String columnName, int threshold) throws SQLException {

        // Try pg_stats first (PostgreSQL only; silently falls through on other databases).
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT n_distinct FROM pg_stats WHERE tablename = '" + tableName
                     + "' AND attname = '" + columnName + "'")) {
            if (rs.next()) {
                float nDistinct = rs.getFloat(1);
                if (!rs.wasNull()) {
                    if (nDistinct >= 0 && nDistinct < threshold / PG_STATS_MARGIN) {
                        // Comfortably below threshold — pg_stats says it's low-cardinality.
                        return (long) nDistinct;
                    }
                    // Near or above threshold, or negative (fraction-of-rows, likely high) — fall
                    // through to the exact count.
                }
            }
        } catch (SQLException ignored) {
            // Not PostgreSQL, or pg_stats not accessible — fall through to exact count.
        }

        // Exact count.
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT COUNT(DISTINCT " + columnName + ") FROM " + tableName)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void verifyEmailFictionality(
            Connection conn, String tableName, String columnName, List<String> failures) throws SQLException {

        // Check that all non-null email values end with a reserved domain.
        String domainCondition = RESERVED_EMAIL_DOMAINS.stream()
            .map(d -> columnName + " LIKE '%@" + d + "'")
            .collect(Collectors.joining(" OR "));

        String sql = "SELECT COUNT(*) FROM " + tableName
            + " WHERE " + columnName + " IS NOT NULL"
            + " AND NOT (" + domainCondition + ")";

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next() && rs.getLong(1) > 0) {
                failures.add("Fictionality violation: " + tableName + "." + columnName
                    + " has " + rs.getLong(1) + " email(s) not using RFC 2606 reserved domains");
            }
        }
    }

    /** Helper to get column policies from a TablePolicy. Uses the columns() accessor. */
    private Iterable<Map.Entry<String, ColumnPolicy>> getColumnPolicies(TablePolicy tablePolicy) {
        return tablePolicy.columns().entrySet();
    }
}
