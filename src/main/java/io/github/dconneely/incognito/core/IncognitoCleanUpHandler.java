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

    private static final System.Logger LOG = System.getLogger(IncognitoCleanUpHandler.class.getName());

    /**
     * Logs a coarse WARNING that a best-effort compensation step failed, so a target left inconsistent
     * (triggers disabled, partial data, unsynced sequences) is not silently invisible to the operator.
     * Deliberately records only the operation, table and SQLState — never the exception message, which
     * must never carry a field value (SPEC §7.3).
     */
    private static void warnCompensation(String operation, String table, SQLException e) {
        LOG.log(System.Logger.Level.WARNING,
            "compensation step [{0}] failed on table {1} (SQLState {2}); target may be left inconsistent",
            operation, table, e.getSQLState());
    }

    /**
     * Compensates a failed run: re-enables triggers, truncates partially loaded tables, and resyncs
     * sequences. Best-effort.
     *
     * @param context the pipeline context of the failed run
     */
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
                } catch (SQLException e) {
                    warnCompensation("truncate partially loaded data", tableName, e);
                }

                // 2. Re-enable triggers and FKs (safe to call even if they weren't disabled)
                try {
                    dialect.postLoadTable(targetConn, tableName);
                } catch (SQLException e) {
                    warnCompensation("re-enable triggers/FKs", tableName, e);
                }

                // 3. Resync sequences
                SchemaInspector.TableMetadata meta = metadataByName.get(tableName);
                if (meta != null && !meta.primaryKeyColumns().isEmpty()) {
                    try {
                        dialect.resyncSequence(targetConn, tableName, meta.primaryKeyColumns().getFirst());
                    } catch (SQLException e) {
                        warnCompensation("resync sequence", tableName, e);
                    }
                }
            }

            // Recreate any FK constraints dropped for an owner-mode cyclic load (SPEC §9). The tables
            // were just emptied, so the constraints validate trivially — leaving them dropped would be
            // a silent schema regression.
            Object droppedObj = context.attributes().get("incognito.droppedForeignKeys");
            if (droppedObj instanceof List<?> droppedList && !droppedList.isEmpty()) {
                try {
                    @SuppressWarnings("unchecked")
                    List<DialectHandler.DroppedForeignKey> dropped =
                        (List<DialectHandler.DroppedForeignKey>) droppedList;
                    dialect.recreateForeignKeys(targetConn, dropped);
                } catch (SQLException e) {
                    warnCompensation("recreate dropped foreign keys", "(schema)", e);
                }
            }
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.WARNING,
                "compensation could not connect to the target database (SQLState {0}); no clean-up performed",
                e.getSQLState());
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
