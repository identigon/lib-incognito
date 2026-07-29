package io.github.dconneely.incognito.core;

import io.github.dconneely.incognito.api.PipelineContext;
import io.github.dconneely.incognito.engine.DialectHandler;
import io.github.dconneely.incognito.engine.GenericDialectHandler;
import io.github.dconneely.incognito.engine.PostgresDialectHandler;
import io.github.dconneely.incognito.engine.TableDependencyGraph;
import io.github.dconneely.incognito.engine.SchemaInspector;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Compensating transaction handler. When a pipeline fails mid-execution, this handler
 * ensures that the target database is left in a safe, consistent state (triggers/FKs enabled,
 * sequences resynchronized, partially loaded data truncated).
 */
public final class IncognitoCleanUpHandler {

    private IncognitoCleanUpHandler() {}

    @SuppressWarnings("unchecked")
    public static void compensate(PipelineContext context) {
        Object planObj = context.attributes().get("incognito.schema.executionPlan");
        if (planObj == null) return;
        TableDependencyGraph.TopologicalExecutionPlan plan = 
            (TableDependencyGraph.TopologicalExecutionPlan) planObj;

        Map<String, SchemaInspector.TableMetadata> metadataByName = Collections.emptyMap();
        Object metaObj = context.attributes().get("incognito.schema.tableMetadata");
        if (metaObj != null) {
            // Re-build map from List
            List<SchemaInspector.TableMetadata> metaList = (List<SchemaInspector.TableMetadata>) metaObj;
            metadataByName = metaList.stream()
                .collect(java.util.stream.Collectors.toMap(SchemaInspector.TableMetadata::tableName, m -> m));
        }

        try (Connection targetConn = context.target().getConnection()) {
            DialectHandler dialect = getDialectHandler(targetConn);

            // Clean up in reverse topological order (children before parents).
            List<String> tables = new ArrayList<>(plan.sequentialTableOrder());
            Collections.reverse(tables);

            for (String tableName : tables) {
                // 1. Truncate partially loaded data
                try (Statement stmt = targetConn.createStatement()) {
                    // Truncate to wipe out partially loaded data
                    stmt.execute("DELETE FROM " + tableName);
                } catch (SQLException ignored) {
                    // Ignore truncate errors during compensation
                }

                // 2. Re-enable triggers and FKs (safe to call even if they weren't disabled)
                try {
                    dialect.postLoadTable(targetConn, tableName);
                } catch (SQLException ignored) {
                }

                // 3. Resync sequences
                SchemaInspector.TableMetadata meta = metadataByName.get(tableName);
                if (meta != null && !meta.primaryKeyColumns().isEmpty()) {
                    try {
                        dialect.resyncSequence(targetConn, tableName, meta.primaryKeyColumns().getFirst());
                    } catch (SQLException ignored) {
                    }
                }
            }
        } catch (SQLException ignored) {
            // Cannot connect to target database to compensate
        }
    }

    private static DialectHandler getDialectHandler(Connection conn) throws SQLException {
        String dbName = conn.getMetaData().getDatabaseProductName();
        if (dbName != null && dbName.toLowerCase().contains("postgresql")) {
            return new PostgresDialectHandler();
        }
        return new GenericDialectHandler();
    }
}
