package io.github.dconneely.incognito.core;

import io.github.dconneely.incognito.api.IncognitoException;
import io.github.dconneely.incognito.api.PipelineContext;
import io.github.dconneely.incognito.api.PipelineStage;
import io.github.dconneely.incognito.engine.SchemaInspector;
import io.github.dconneely.incognito.engine.TableDependencyGraph;
import io.github.dconneely.incognito.policy.AnonymisationPolicy;
import io.github.dconneely.incognito.policy.ColumnPolicy;
import io.github.dconneely.incognito.policy.TablePolicy;
import io.github.dconneely.incognito.api.ColumnRole;
import io.github.dconneely.incognito.api.DirectIdStrategy;

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
 * </ul>
 */
public final class VerificationStage implements PipelineStage {

    /** RFC 2606 reserved domains that AlterEgo uses for fictional emails. */
    private static final List<String> RESERVED_EMAIL_DOMAINS = List.of(
        "example.com", "example.net", "example.org",
        "example.co.uk", "example.org.uk"
    );

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

        try (Connection conn = context.target().getConnection()) {
            // 1. Verify referential integrity.
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

                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery(checkSql)) {
                        if (rs.next() && rs.getLong(1) > 0) {
                            failures.add("Dangling FK: " + tableName + "." + fkColumn
                                + " has " + rs.getLong(1) + " orphaned references to " + parentTable);
                        }
                    }
                }
            }

            // 2. Verify fictionality of email DIRECT_ID columns.
            for (String tableName : plan.sequentialTableOrder()) {
                Optional<TablePolicy> tablePolicyOpt = policy.table(tableName);
                if (tablePolicyOpt.isEmpty()) continue;

                TablePolicy tablePolicy = tablePolicyOpt.get();
                for (Map.Entry<String, ColumnPolicy> entry : getColumnPolicies(tablePolicy)) {
                    ColumnPolicy colPolicy = entry.getValue();
                    if (colPolicy.role() == ColumnRole.DIRECT_ID
                            && colPolicy.directIdStrategy() == DirectIdStrategy.ALTEREGO_EMAIL) {
                        verifyEmailFictionality(conn, tableName, colPolicy.columnName(), failures);
                    }
                }
            }

        } catch (SQLException e) {
            throw new IncognitoException.SchemaException("Verification failed", e);
        }

        if (!failures.isEmpty()) {
            return new StageResult(
                "VerificationStage",
                false,
                failures.size(),
                "Verification FAILED:\n  " + String.join("\n  ", failures)
            );
        }

        return new StageResult("VerificationStage", true, 0, "All verifications passed");
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
