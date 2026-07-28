package io.github.dconneely.incognito.core;

import io.github.dconneely.alterego.AlterEgo;
import io.github.dconneely.alterego.Transformation;
import io.github.dconneely.incognito.api.ColumnRole;
import io.github.dconneely.incognito.api.IncognitoException;
import io.github.dconneely.incognito.api.KeyTranslationStore;
import io.github.dconneely.incognito.api.PipelineContext;
import io.github.dconneely.incognito.api.PipelineStage;
import io.github.dconneely.incognito.engine.SchemaInspector;
import io.github.dconneely.incognito.engine.TableDependencyGraph;
import io.github.dconneely.incognito.policy.AnonymisationPolicy;
import io.github.dconneely.incognito.policy.ColumnPolicy;
import io.github.dconneely.incognito.policy.TablePolicy;
import io.github.dconneely.incognito.api.DirectIdStrategy;
import io.github.dconneely.incognito.api.QuasiIdStrategy;
import io.github.dconneely.incognito.api.SurrogateStrategy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Stage 2+3 combined for the walking skeleton: iterates tables in topological order,
 * streams rows from the source, transforms columns per the policy, and batch-inserts
 * into the target. Also resyncs sequences after loading.
 */
public final class TableTransformLoadStage implements PipelineStage {

    private static final int FETCH_SIZE = 5000;
    private static final int BATCH_SIZE = 1000;

    @Override
    @SuppressWarnings("unchecked")
    public StageResult process(PipelineContext context) throws IncognitoException {
        // Retrieve schema metadata and execution plan from SchemaDiscoveryStage.
        List<SchemaInspector.TableMetadata> allMetadata =
            (List<SchemaInspector.TableMetadata>) context.attributes().get(SchemaDiscoveryStage.ATTR_TABLE_METADATA);
        TableDependencyGraph.TopologicalExecutionPlan plan =
            (TableDependencyGraph.TopologicalExecutionPlan) context.attributes().get(SchemaDiscoveryStage.ATTR_EXECUTION_PLAN);

        if (allMetadata == null || plan == null) {
            throw new IncognitoException.ConfigException(
                "SchemaDiscoveryStage must run before TableTransformLoadStage");
        }

        // Build a lookup map: tableName -> TableMetadata
        Map<String, SchemaInspector.TableMetadata> metadataByName = allMetadata.stream()
            .collect(Collectors.toMap(SchemaInspector.TableMetadata::tableName, m -> m));

        AnonymisationPolicy policy = context.policy();
        AlterEgo alterEgo = context.alterEgo();
        KeyTranslationStore keyStore = context.keyStore();

        long totalRows = 0;

        try {
            // Suppress FK enforcement on target for loading.
            suppressFkEnforcement(context);

            for (String tableName : plan.sequentialTableOrder()) {
                SchemaInspector.TableMetadata tableMeta = metadataByName.get(tableName);
                if (tableMeta == null) continue;

                Optional<TablePolicy> tablePolicyOpt = policy.table(tableName);
                if (tablePolicyOpt.isEmpty()) continue; // Skip tables not in policy

                TablePolicy tablePolicy = tablePolicyOpt.get();
                long rowCount = processTable(context, tableMeta, tablePolicy, alterEgo, keyStore);
                totalRows += rowCount;
            }

            // Resync sequences on target.
            resyncSequences(context, plan.sequentialTableOrder(), metadataByName);

            // Restore FK enforcement.
            restoreFkEnforcement(context);

        } catch (SQLException e) {
            throw new IncognitoException.SchemaException("Error during transform/load", e);
        }

        return new StageResult(
            "TableTransformLoadStage",
            true,
            totalRows,
            "Transformed and loaded " + totalRows + " rows across " + plan.sequentialTableOrder().size() + " tables"
        );
    }

    private long processTable(
            PipelineContext context,
            SchemaInspector.TableMetadata tableMeta,
            TablePolicy tablePolicy,
            AlterEgo alterEgo,
            KeyTranslationStore keyStore) throws IncognitoException {

        String tableName = tableMeta.tableName();

        // Determine which columns to SELECT from source and INSERT into target.
        // Exclude generated columns (computed columns).
        List<String> columnsToProcess = tableMeta.columns().stream()
            .filter(col -> !tableMeta.generatedColumns().contains(col))
            .toList();

        if (columnsToProcess.isEmpty()) return 0;

        // Build transformations for each column.
        List<ColumnTransformer> transformers = columnsToProcess.stream()
            .map(col -> buildTransformer(col, tablePolicy, tableMeta, alterEgo))
            .toList();

        // Determine if the PK is an identity column (needs OVERRIDING SYSTEM VALUE).
        boolean hasIdentityPk = !tableMeta.primaryKeyColumns().isEmpty()
            && tableMeta.columns().contains(tableMeta.primaryKeyColumns().getFirst());

        String selectSql = "SELECT " + String.join(", ", columnsToProcess) + " FROM " + tableName;
        String insertSql = buildInsertSql(tableName, columnsToProcess, hasIdentityPk);

        AtomicLong surrogateCounter = new AtomicLong(1);
        long rowCount = 0;

        try (Connection sourceConn = context.source().getConnection();
             Connection targetConn = context.target().getConnection()) {

            sourceConn.setAutoCommit(false);
            targetConn.setAutoCommit(false);

            try (Statement stmt = sourceConn.createStatement()) {
                stmt.setFetchSize(FETCH_SIZE);
                try (ResultSet rs = stmt.executeQuery(selectSql)) {
                    ResultSetMetaData rsMeta = rs.getMetaData();

                    try (PreparedStatement insertStmt = targetConn.prepareStatement(insertSql)) {
                        int batchCount = 0;

                        while (rs.next()) {
                            for (int i = 0; i < columnsToProcess.size(); i++) {
                                ColumnTransformer transformer = transformers.get(i);
                                String colName = columnsToProcess.get(i);
                                int sqlType = rsMeta.getColumnType(i + 1);

                                Object originalValue = rs.getObject(i + 1);
                                Object transformedValue = transformer.transform(
                                    originalValue, sqlType, surrogateCounter, keyStore, tableName);

                                // Record PK translation if this is a PK column.
                                if (tableMeta.primaryKeyColumns().contains(colName) && originalValue != null) {
                                    keyStore.put(tableName, originalValue, transformedValue);
                                }

                                insertStmt.setObject(i + 1, transformedValue);
                            }

                            insertStmt.addBatch();
                            batchCount++;
                            rowCount++;

                            if (batchCount >= BATCH_SIZE) {
                                insertStmt.executeBatch();
                                batchCount = 0;
                            }
                        }

                        if (batchCount > 0) {
                            insertStmt.executeBatch();
                        }
                    }
                }
            }

            targetConn.commit();
        } catch (SQLException e) {
            throw new IncognitoException.SchemaException(
                "Error processing table '" + tableName + "'", e);
        }

        return rowCount;
    }

    private ColumnTransformer buildTransformer(
            String columnName,
            TablePolicy tablePolicy,
            SchemaInspector.TableMetadata tableMeta,
            AlterEgo alterEgo) {

        Optional<ColumnPolicy> policyOpt = tablePolicy.column(columnName);
        if (policyOpt.isEmpty()) {
            // No policy for this column — keep it real (payload).
            return ColumnTransformer.PASSTHROUGH;
        }

        ColumnPolicy colPolicy = policyOpt.get();
        ColumnRole role = colPolicy.role();

        return switch (role) {
            case PRIMARY_KEY -> buildPkTransformer(colPolicy);
            case FOREIGN_KEY -> buildFkTransformer(colPolicy);
            case DIRECT_ID -> buildDirectIdTransformer(colPolicy, alterEgo, tableMeta.tableName());
            case QUASI_ID -> buildQuasiIdTransformer(colPolicy, alterEgo, tableMeta.tableName());
            case SENSITIVE, PAYLOAD -> ColumnTransformer.PASSTHROUGH;
            case GENERATED_COLUMN -> ColumnTransformer.PASSTHROUGH; // shouldn't reach here
            default -> ColumnTransformer.PASSTHROUGH;
        };
    }

    private ColumnTransformer buildPkTransformer(ColumnPolicy colPolicy) {
        SurrogateStrategy strategy = colPolicy.surrogateStrategy();
        if (strategy == null) strategy = SurrogateStrategy.SEQUENTIAL_LONG;

        return switch (strategy) {
            case SEQUENTIAL_LONG -> (value, sqlType, counter, keyStore, tableName) ->
                counter.getAndIncrement();
            case UUID_V4 -> (value, sqlType, counter, keyStore, tableName) ->
                java.util.UUID.randomUUID();
            case PASSTHROUGH_SURROGATE -> ColumnTransformer.PASSTHROUGH;
        };
    }

    private ColumnTransformer buildFkTransformer(ColumnPolicy colPolicy) {
        String referencedTable = colPolicy.referencedTable();
        return (value, sqlType, counter, keyStore, tableName) -> {
            if (value == null) return null;
            Optional<Object> mapped = keyStore.get(referencedTable, value);
            return mapped.orElseThrow(() -> new IncognitoException.ConstraintException(
                "No key translation found for FK value '" + value + "' referencing table '" + referencedTable + "'"));
        };
    }

    private ColumnTransformer buildDirectIdTransformer(
            ColumnPolicy colPolicy, AlterEgo alterEgo, String tableName) {
        DirectIdStrategy strategy = colPolicy.directIdStrategy();
        if (strategy == null) strategy = DirectIdStrategy.ALTEREGO_GENERIC;

        String domain = "incognito:" + tableName + ":" + colPolicy.columnName();

        Transformation<String> transformation = switch (strategy) {
            case ALTEREGO_NAME -> alterEgo.fullName();
            case ALTEREGO_EMAIL -> alterEgo.emailAddress();
            case ALTEREGO_PHONE -> alterEgo.phoneNumber();
            case ALTEREGO_GENERIC -> alterEgo.bind(domain, (input, ctx) -> {
                // Generic: produce a deterministic but fictional replacement
                return "ANON-" + Math.abs(input.hashCode());
            });
        };

        return (value, sqlType, counter, keyStore, tbl) -> {
            if (value == null) return null;
            return transformation.apply(value.toString());
        };
    }

    private ColumnTransformer buildQuasiIdTransformer(
            ColumnPolicy colPolicy, AlterEgo alterEgo, String tableName) {
        QuasiIdStrategy strategy = colPolicy.quasiIdStrategy();
        if (strategy == null) strategy = QuasiIdStrategy.SYNTHESISE;

        return switch (strategy) {
            case JITTER_WITHIN_MONTH -> {
                Transformation<LocalDate> dateTransform = alterEgo.shiftDate(AlterEgo.DateField.MONTH);
                yield (value, sqlType, counter, keyStore, tbl) -> {
                    if (value == null) return null;
                    if (value instanceof LocalDate ld) return dateTransform.apply(ld);
                    if (value instanceof java.sql.Date sd) return java.sql.Date.valueOf(dateTransform.apply(sd.toLocalDate()));
                    return value;
                };
            }
            case JITTER_WITHIN_YEAR -> {
                Transformation<LocalDate> dateTransform = alterEgo.shiftDate(AlterEgo.DateField.YEAR);
                yield (value, sqlType, counter, keyStore, tbl) -> {
                    if (value == null) return null;
                    if (value instanceof LocalDate ld) return dateTransform.apply(ld);
                    if (value instanceof java.sql.Date sd) return java.sql.Date.valueOf(dateTransform.apply(sd.toLocalDate()));
                    return value;
                };
            }
            case JITTER_DAYS -> {
                int days = colPolicy.jitterDays() > 0 ? colPolicy.jitterDays() : 14;
                Transformation<LocalDate> dateTransform = alterEgo.shiftDate(days);
                yield (value, sqlType, counter, keyStore, tbl) -> {
                    if (value == null) return null;
                    if (value instanceof LocalDate ld) return dateTransform.apply(ld);
                    if (value instanceof java.sql.Date sd) return java.sql.Date.valueOf(dateTransform.apply(sd.toLocalDate()));
                    return value;
                };
            }
            case SYNTHESISE -> {
                // For dates: generate a random date in a plausible range.
                // For strings: generate a fictional replacement.
                String domain = "incognito:synth:" + tableName + ":" + colPolicy.columnName();
                yield (value, sqlType, counter, keyStore, tbl) -> {
                    if (value == null) return null;
                    if (value instanceof LocalDate || value instanceof java.sql.Date) {
                        // Synthesise: random day within the same year (reasonable default).
                        Transformation<LocalDate> dateTransform = alterEgo.shiftDate(AlterEgo.DateField.YEAR);
                        LocalDate ld = (value instanceof java.sql.Date sd) ? sd.toLocalDate() : (LocalDate) value;
                        LocalDate result = dateTransform.apply(ld);
                        return (value instanceof java.sql.Date) ? java.sql.Date.valueOf(result) : result;
                    }
                    // For other types, apply a generic string transformation.
                    Transformation<String> strTransform = alterEgo.bind(domain,
                        (input, ctx) -> "SYNTH-" + Math.abs(input.hashCode()));
                    return strTransform.apply(value.toString());
                };
            }
        };
    }

    private String buildInsertSql(String tableName, List<String> columns, boolean hasIdentityPk) {
        String cols = String.join(", ", columns);
        String placeholders = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        String sql = "INSERT INTO " + tableName + " (" + cols + ") ";
        if (hasIdentityPk) {
            sql += "OVERRIDING SYSTEM VALUE ";
        }
        sql += "VALUES (" + placeholders + ")";
        return sql;
    }

    private void suppressFkEnforcement(PipelineContext context) throws SQLException {
        try (Connection conn = context.target().getConnection();
             Statement stmt = conn.createStatement()) {
            try {
                stmt.execute("SET session_replication_role = 'replica'");
                conn.commit();
            } catch (SQLException e) {
                // Fallback: not superuser — will rely on topological ordering.
                // In a full implementation, we'd disable triggers per-table here.
            }
        }
    }

    private void restoreFkEnforcement(PipelineContext context) throws SQLException {
        try (Connection conn = context.target().getConnection();
             Statement stmt = conn.createStatement()) {
            try {
                stmt.execute("SET session_replication_role = 'origin'");
                conn.commit();
            } catch (SQLException e) {
                // Fallback: was not set, no action needed.
            }
        }
    }

    private void resyncSequences(
            PipelineContext context,
            List<String> tables,
            Map<String, SchemaInspector.TableMetadata> metadataByName) throws SQLException {
        try (Connection conn = context.target().getConnection();
             Statement stmt = conn.createStatement()) {
            for (String tableName : tables) {
                SchemaInspector.TableMetadata meta = metadataByName.get(tableName);
                if (meta == null || meta.primaryKeyColumns().isEmpty()) continue;

                String pkCol = meta.primaryKeyColumns().getFirst();
                try {
                    // PostgreSQL: find the sequence associated with the column and resync it.
                    ResultSet rs = stmt.executeQuery(
                        "SELECT pg_get_serial_sequence('" + tableName + "', '" + pkCol + "')");
                    if (rs.next()) {
                        String seqName = rs.getString(1);
                        if (seqName != null) {
                            stmt.execute("SELECT setval('" + seqName + "', "
                                + "(SELECT COALESCE(MAX(" + pkCol + "), 1) FROM " + tableName + "))");
                        }
                    }
                    rs.close();
                } catch (SQLException e) {
                    // Non-PostgreSQL or no sequence — skip.
                }
            }
            conn.commit();
        }
    }

    /**
     * Functional interface for per-column transformation logic.
     */
    @FunctionalInterface
    interface ColumnTransformer {
        /** Passthrough: returns the value unchanged. */
        ColumnTransformer PASSTHROUGH = (value, sqlType, counter, keyStore, tableName) -> value;

        Object transform(Object value, int sqlType, AtomicLong surrogateCounter,
                          KeyTranslationStore keyStore, String tableName) throws IncognitoException;
    }
}
