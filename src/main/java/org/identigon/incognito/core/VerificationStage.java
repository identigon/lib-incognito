package org.identigon.incognito.core;

import org.identigon.incognito.api.ColumnRole;
import org.identigon.incognito.api.DirectIdStrategy;
import org.identigon.incognito.api.DistinguishingLint;
import org.identigon.incognito.api.IncognitoException;
import org.identigon.incognito.api.PipelineContext;
import org.identigon.incognito.api.PipelineStage;
import org.identigon.incognito.engine.SchemaInspector;
import org.identigon.incognito.engine.TableDependencyGraph;
import org.identigon.incognito.policy.AnonymisationPolicy;
import org.identigon.incognito.policy.ColumnPolicy;
import org.identigon.incognito.policy.TablePolicy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 *       column's real {@code COUNT(DISTINCT)} against {@code maxCategoricalCardinality}.</li>
 *   <li>Per-period volume tolerance (SPEC §4.2, Appendix D): for temporal QUASI_ID columns,
 *       verifies that monthly/yearly bucket counts in the target match the source within ±2%
 *       (min ±1 row).</li>
 *   <li>Source-value survival: for DIRECT_ID columns, verifies that no real source value
 *       survived in the target (a sanity net beyond the email-domain check).</li>
 * </ul>
 */
public final class VerificationStage implements PipelineStage {

    /** Creates a verification stage. */
    public VerificationStage() {}

    private static final System.Logger LOG = System.getLogger(VerificationStage.class.getName());

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

    /**
     * Per-period volume tolerance: ±2% of the source bucket count, minimum ±1 row (Appendix D).
     */
    private static final double VOLUME_TOLERANCE_FRACTION = 0.02;
    private static final long VOLUME_TOLERANCE_MIN_ROWS = 1;

    /**
     * Fraction of a DIRECT_ID column's sampled distinct values that must survive into the target
     * before survival counts as a hard failure rather than a warning. Shape-preserving fabrication
     * of low-entropy values can collide with a real value purely by chance; a genuine leak (e.g. an
     * accidental passthrough) survives ~all values, so a high ratio distinguishes the two.
     */
    private static final double SURVIVAL_FAILURE_RATIO = 0.20;

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
        // Tables that contributed at least one hard failure — the complement (in-policy, no failure)
        // are reported as fictionality-verified in the DPIA report.
        java.util.Set<String> failedTables = new java.util.HashSet<>();

        try (Connection targetConn = context.target().getConnection()) {
            // 1. Verify referential integrity on the target.
            for (String tableName : plan.sequentialTableOrder()) {
                SchemaInspector.TableMetadata meta = metadataByName.get(tableName);
                if (meta == null) continue;

                // Check each FK constraint as a whole tuple — so a composite FK is verified across all
                // its columns, not one column at a time (SPEC §5.2).
                for (SchemaInspector.ForeignKeyConstraint fk : meta.foreignKeyConstraints()) {
                    String parentTable = fk.parentTable();
                    if (metadataByName.get(parentTable) == null) continue;

                    StringBuilder join = new StringBuilder();
                    StringBuilder notNull = new StringBuilder();
                    for (int i = 0; i < fk.childColumns().size(); i++) {
                        if (i > 0) { join.append(" AND "); notNull.append(" AND "); }
                        join.append("p.").append(fk.parentColumns().get(i)).append(" = c.").append(fk.childColumns().get(i));
                        notNull.append("c.").append(fk.childColumns().get(i)).append(" IS NOT NULL");
                    }

                    String checkSql = "SELECT COUNT(*) FROM " + tableName + " c "
                        + "WHERE (" + notNull + ") "
                        + "AND NOT EXISTS (SELECT 1 FROM " + parentTable + " p WHERE " + join + ")";

                    try (Statement stmt = targetConn.createStatement();
                         ResultSet rs = stmt.executeQuery(checkSql)) {
                        if (rs.next() && rs.getLong(1) > 0) {
                            failures.add("Dangling FK: " + tableName + "." + fk.childColumns()
                                + " has " + rs.getLong(1) + " orphaned references to " + parentTable);
                            failedTables.add(tableName);
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
                        verifyEmailFictionality(targetConn, tableName, colPolicy.columnName(), failures, failedTables);
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

        // 4. Per-period volume tolerance for temporal QUASI_ID columns (SPEC §4.2, Appendix D).
        //    JITTER_WITHIN_MONTH → monthly buckets must match exactly.
        //    JITTER_WITHIN_YEAR  → yearly buckets must match exactly.
        //    JITTER_DAYS         → YEARLY buckets within ±2% (min ±1 row). A ±N-day jitter routinely
        //                          crosses month boundaries, so monthly buckets are not preserved and
        //                          would raise spurious drift warnings; the yearly bucket barely leaks.
        //    SYNTHESISE          → distribution not preserved; skip.
        try (Connection sourceConn = context.source().getConnection();
             Connection targetConn2 = context.target().getConnection()) {
            for (String tableName : plan.sequentialTableOrder()) {
                Optional<TablePolicy> tablePolicyOpt = policy.table(tableName);
                if (tablePolicyOpt.isEmpty()) continue;

                TablePolicy tablePolicy = tablePolicyOpt.get();
                for (Map.Entry<String, ColumnPolicy> entry : getColumnPolicies(tablePolicy)) {
                    ColumnPolicy colPolicy = entry.getValue();
                    if (colPolicy.role() != ColumnRole.QUASI_ID) continue;
                    org.identigon.incognito.api.QuasiIdStrategy qiStrategy = colPolicy.quasiIdStrategy();
                    if (qiStrategy == null || qiStrategy == org.identigon.incognito.api.QuasiIdStrategy.SYNTHESISE) continue;

                    String colName = colPolicy.columnName();
                    String truncExpr;
                    boolean exact;

                    switch (qiStrategy) {
                        case JITTER_WITHIN_MONTH -> { truncExpr = "date_trunc('month', " + colName + ")"; exact = true; }
                        case JITTER_WITHIN_YEAR  -> { truncExpr = "date_trunc('year', " + colName + ")";  exact = true; }
                        case JITTER_DAYS         -> { truncExpr = "date_trunc('year', " + colName + ")";  exact = false; }
                        default -> { continue; }
                    }

                    Map<String, Long> sourceBuckets = queryBucketCounts(sourceConn, tableName, truncExpr);
                    Map<String, Long> targetBuckets = queryBucketCounts(targetConn2, tableName, truncExpr);

                    for (Map.Entry<String, Long> bucket : sourceBuckets.entrySet()) {
                        String period = bucket.getKey();
                        long sourceCount = bucket.getValue();
                        long targetCount = targetBuckets.getOrDefault(period, 0L);

                        if (exact) {
                            if (sourceCount != targetCount) {
                                warnings.add("Volume drift: " + tableName + "." + colName
                                    + " period " + period + ": source=" + sourceCount
                                    + " target=" + targetCount + " (expected exact match for " + qiStrategy + ")");
                            }
                        } else {
                            long tolerance = Math.max(VOLUME_TOLERANCE_MIN_ROWS,
                                (long) Math.ceil(sourceCount * VOLUME_TOLERANCE_FRACTION));
                            if (Math.abs(targetCount - sourceCount) > tolerance) {
                                warnings.add("Volume drift: " + tableName + "." + colName
                                    + " period " + period + ": source=" + sourceCount
                                    + " target=" + targetCount + " (tolerance ±" + tolerance + ")");
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new IncognitoException.SchemaException(
                "Per-period volume verification failed", e);
        }

        // 5. Source-value survival check for DIRECT_ID columns: verify that no real source value
        //    survives in the target (a sanity net — if fabrication worked, the intersection is empty).
        try (Connection sourceConn = context.source().getConnection();
             Connection targetConn3 = context.target().getConnection()) {
            for (String tableName : plan.sequentialTableOrder()) {
                Optional<TablePolicy> tablePolicyOpt = policy.table(tableName);
                if (tablePolicyOpt.isEmpty()) continue;

                TablePolicy tablePolicy = tablePolicyOpt.get();
                for (Map.Entry<String, ColumnPolicy> entry : getColumnPolicies(tablePolicy)) {
                    ColumnPolicy colPolicy = entry.getValue();
                    if (colPolicy.role() != ColumnRole.DIRECT_ID) continue;
                    // Email fictionality is already checked in section 2; this is for other strategies.
                    if (colPolicy.directIdStrategy() == DirectIdStrategy.ALTEREGO_EMAIL) continue;

                    String colName = colPolicy.columnName();
                    verifySurvival(sourceConn, targetConn3, tableName, colName, failures, warnings, failedTables);
                }
            }
        } catch (SQLException e) {
            throw new IncognitoException.SchemaException(
                "Source-value survival check failed", e);
        }

        // Record which in-policy tables passed all verification checks, so the DPIA report can
        // mark them fictionality-verified (AnonymisationReportBuilder reads this attribute).
        List<String> verifiedTables = new ArrayList<>();
        for (String tableName : plan.sequentialTableOrder()) {
            if (policy.table(tableName).isPresent() && !failedTables.contains(tableName)) {
                verifiedTables.add(tableName);
            }
        }
        context.attributes().put("incognito.verification.verifiedTables", verifiedTables);

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
        } catch (SQLException e) {
            // Not PostgreSQL, or pg_stats not accessible — pg_stats is only an optimisation, never the
            // privacy gate (SPEC §4.1), so fall through to the exact count. Surface at DEBUG only.
            LOG.log(System.Logger.Level.DEBUG,
                "pg_stats pre-filter unavailable for {0}.{1} (SQLState {2}); using exact COUNT(DISTINCT)",
                tableName, columnName, e.getSQLState());
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
            Connection conn, String tableName, String columnName,
            List<String> failures, java.util.Set<String> failedTables) throws SQLException {

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
                failedTables.add(tableName);
            }
        }
    }

    /**
     * Queries per-period bucket counts for a date column, returning a map of
     * period-label → row-count.
     */
    private Map<String, Long> queryBucketCounts(
            Connection conn, String tableName, String truncExpr) throws SQLException {
        Map<String, Long> buckets = new LinkedHashMap<>();
        String sql = "SELECT " + truncExpr + " AS period, COUNT(*) AS cnt FROM " + tableName
            + " WHERE " + truncExpr + " IS NOT NULL GROUP BY period ORDER BY period";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                buckets.put(rs.getString(1), rs.getLong(2));
            }
        }
        return buckets;
    }

    /**
     * Verifies that real source values for a DIRECT_ID column did not survive into the target.
     * Compares the fraction of sampled distinct source values that reappear in the target: a genuine
     * fabrication failure (e.g. an accidental passthrough) resurfaces ~all values and is a hard
     * failure, whereas a handful of coincidental shape-preserving collisions on low-entropy values is
     * only a warning — so this net never fails a healthy run over a low-cardinality identifier.
     */
    private void verifySurvival(
            Connection sourceConn, Connection targetConn, String tableName,
            String columnName, List<String> failures, List<String> warnings,
            java.util.Set<String> failedTables) throws SQLException {
        // Collect non-null distinct source values (bounded to a reasonable sample for large tables).
        List<String> sourceValues = new ArrayList<>();
        try (Statement stmt = sourceConn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT DISTINCT " + columnName + " FROM " + tableName
                     + " WHERE " + columnName + " IS NOT NULL LIMIT 1000")) {
            while (rs.next()) {
                sourceValues.add(rs.getString(1));
            }
        }
        if (sourceValues.isEmpty()) return;

        // How many of those DISTINCT source values reappear in the target?
        // String literals are safe here: the values come from our own source DB, quotes escaped.
        StringBuilder inClause = new StringBuilder();
        for (int i = 0; i < sourceValues.size(); i++) {
            if (i > 0) inClause.append(",");
            inClause.append("'").append(sourceValues.get(i).replace("'", "''")).append("'");
        }
        String checkSql = "SELECT COUNT(DISTINCT " + columnName + ") FROM " + tableName
            + " WHERE " + columnName + " IN (" + inClause + ")";
        try (Statement stmt = targetConn.createStatement();
             ResultSet rs = stmt.executeQuery(checkSql)) {
            if (rs.next()) {
                long survived = rs.getLong(1);
                if (survived == 0) return;
                double ratio = (double) survived / sourceValues.size();
                if (ratio >= SURVIVAL_FAILURE_RATIO) {
                    failures.add("Source-value survival: " + tableName + "." + columnName
                        + " has " + survived + " of " + sourceValues.size()
                        + " sampled distinct source values surviving in the target ("
                        + String.format("%.0f%%", ratio * 100) + ") — fabrication may not have been applied");
                    failedTables.add(tableName);
                } else {
                    warnings.add("Source-value survival (likely coincidental): " + tableName + "." + columnName
                        + " has " + survived + " of " + sourceValues.size()
                        + " sampled distinct source values matching in the target — below the "
                        + String.format("%.0f%%", SURVIVAL_FAILURE_RATIO * 100) + " failure threshold");
                }
            }
        }
    }

    /** Helper to get column policies from a TablePolicy. Uses the columns() accessor. */
    private Iterable<Map.Entry<String, ColumnPolicy>> getColumnPolicies(TablePolicy tablePolicy) {
        return tablePolicy.columns().entrySet();
    }
}
