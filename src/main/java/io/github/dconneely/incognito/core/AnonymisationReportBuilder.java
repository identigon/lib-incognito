package io.github.dconneely.incognito.core;

import io.github.dconneely.incognito.api.AnonymisationReport;
import io.github.dconneely.incognito.api.ColumnRole;
import io.github.dconneely.incognito.api.PipelineContext;
import io.github.dconneely.incognito.api.PipelineStage;
import io.github.dconneely.incognito.engine.SchemaInspector;
import io.github.dconneely.incognito.engine.TableDependencyGraph;
import io.github.dconneely.incognito.policy.AnonymisationPolicy;
import io.github.dconneely.incognito.policy.ColumnPolicy;
import io.github.dconneely.incognito.policy.TablePolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class AnonymisationReportBuilder {

    private AnonymisationReportBuilder() {}

    @SuppressWarnings("unchecked")
    public static AnonymisationReport build(PipelineContext context, List<PipelineStage.StageResult> stageResults) {
        Object planObj = context.attributes().get("incognito.schema.executionPlan");
        Object metaObj = context.attributes().get("incognito.schema.tableMetadata");
        Object inferObj = context.attributes().get("incognito.schema.inferSuggestions");
        Object rowsObj = context.attributes().get("incognito.metrics.rowsPerTable");
        Object verifiedTablesObj = context.attributes().get("incognito.verification.verifiedTables"); // Optional

        if (planObj == null || metaObj == null) {
            return new AnonymisationReport(Collections.emptyList(), stageResults);
        }

        TableDependencyGraph.TopologicalExecutionPlan plan = (TableDependencyGraph.TopologicalExecutionPlan) planObj;
        List<SchemaInspector.TableMetadata> metadataList = (List<SchemaInspector.TableMetadata>) metaObj;
        Map<String, List<AnonymisationReport.InferSuggestion>> suggestions = inferObj != null ? 
            (Map<String, List<AnonymisationReport.InferSuggestion>>) inferObj : Collections.emptyMap();
        Map<String, Long> rowsPerTable = rowsObj != null ? 
            (Map<String, Long>) rowsObj : Collections.emptyMap();
        List<String> verifiedTables = verifiedTablesObj != null ?
            (List<String>) verifiedTablesObj : Collections.emptyList();

        AnonymisationPolicy policy = context.policy();
        List<AnonymisationReport.TableReport> tableReports = new ArrayList<>();

        for (String tableName : plan.sequentialTableOrder()) {
            SchemaInspector.TableMetadata tableMeta = metadataList.stream()
                .filter(m -> m.tableName().equals(tableName)).findFirst().orElse(null);
            
            if (tableMeta == null) continue;

            Optional<TablePolicy> tablePolicyOpt = policy.table(tableName);
            if (tablePolicyOpt.isEmpty()) continue;
            TablePolicy tablePolicy = tablePolicyOpt.get();

            List<AnonymisationReport.ColumnAction> columnActions = new ArrayList<>();
            List<AnonymisationReport.PassthroughFlag> passthroughFlags = new ArrayList<>();

            for (String colName : tableMeta.columns()) {
                if (tableMeta.generatedColumns().contains(colName)) continue;

                tablePolicy.column(colName).ifPresent(colPol -> {
                    // Does the column keep its real value (a passthrough), or is it transformed?
                    boolean keptReal = colPol.role() == ColumnRole.PAYLOAD
                        || (colPol.role() == ColumnRole.SENSITIVE && Boolean.FALSE.equals(colPol.distinguishing()));

                    String transformation;
                    if (colPol.role() == ColumnRole.SENSITIVE) {
                        if (Boolean.FALSE.equals(colPol.distinguishing())) {
                            transformation = "KEEP";
                        } else if (colPol.redactionStrategy() != null) {
                            transformation = colPol.redactionStrategy().toString();
                        } else if (colPol.quasiIdStrategy() != null) {
                            transformation = colPol.quasiIdStrategy().toString();
                        } else {
                            transformation = "REDACT";
                        }
                    } else if (colPol.role() == ColumnRole.INHERITED_ATTRIBUTE) {
                        transformation = "INHERIT from " + colPol.derivedFromTable() + "." + colPol.derivedFromColumn();
                    } else if (colPol.role() == ColumnRole.FOREIGN_KEY) {
                        transformation = "LINK to " + colPol.referencedTable();
                    } else {
                        transformation = switch (colPol.role()) {
                            case PRIMARY_KEY -> colPol.surrogateStrategy() != null ? colPol.surrogateStrategy().toString() : "SURROGATE";
                            case DIRECT_ID -> colPol.directIdStrategy() != null ? colPol.directIdStrategy().toString() : "FABRICATE";
                            case UNIQUE_CANDIDATE_KEY -> (colPol.directIdStrategy() != null ? colPol.directIdStrategy().toString() : "FABRICATE") + " (unique)";
                            case QUASI_ID -> colPol.quasiIdStrategy() != null ? colPol.quasiIdStrategy().toString() : "SYNTHESISE";
                            default -> "KEEP"; // PAYLOAD, GENERATED_COLUMN
                        };
                    }
                    columnActions.add(new AnonymisationReport.ColumnAction(colName, colPol.role(), transformation));

                    // Opaque-type audit (SPEC §7.2): a KEPT column of a complex/untransformable JDBC
                    // type is surfaced in the DPIA report so a retained potentially-identifying value
                    // (JSONB, array, geometry, INET, BLOB, …) is visible, never silently passed through.
                    if (keptReal) {
                        Integer sqlType = tableMeta.columnTypes() == null ? null : tableMeta.columnTypes().get(colName);
                        String opaque = opaqueTypeName(sqlType);
                        if (opaque != null) {
                            passthroughFlags.add(new AnonymisationReport.PassthroughFlag(
                                colName, opaque, "untransformed potentially-identifying type kept as-is (SPEC §7.2)"));
                        }
                    }
                });
            }

            long rowsProcessed = rowsPerTable.getOrDefault(tableName, 0L);
            List<AnonymisationReport.InferSuggestion> tableSuggestions = suggestions.getOrDefault(tableName, Collections.emptyList());
            boolean verified = verifiedTables.contains(tableName);

            tableReports.add(new AnonymisationReport.TableReport(
                tableName,
                columnActions,
                rowsProcessed,
                passthroughFlags,
                tableSuggestions,
                verified
            ));
        }

        return new AnonymisationReport(tableReports, stageResults);
    }

    /**
     * If {@code sqlType} is a complex/opaque JDBC type that v1.0 does not transform (JSONB, arrays,
     * geometry, INET, XML, LOBs, …), returns its JDBC type name; otherwise {@code null}. PostgreSQL
     * maps JSONB/JSON/INET/geometry to {@link java.sql.Types#OTHER}, and SQL arrays to
     * {@link java.sql.Types#ARRAY}.
     */
    private static String opaqueTypeName(Integer sqlType) {
        if (sqlType == null) return null;
        return switch (sqlType) {
            case java.sql.Types.ARRAY, java.sql.Types.OTHER, java.sql.Types.STRUCT, java.sql.Types.REF,
                 java.sql.Types.JAVA_OBJECT, java.sql.Types.SQLXML, java.sql.Types.DATALINK,
                 java.sql.Types.BLOB, java.sql.Types.CLOB, java.sql.Types.NCLOB,
                 java.sql.Types.BINARY, java.sql.Types.VARBINARY, java.sql.Types.LONGVARBINARY -> {
                try {
                    yield java.sql.JDBCType.valueOf(sqlType).getName();
                } catch (IllegalArgumentException e) {
                    yield "TYPE_" + sqlType;
                }
            }
            default -> null;
        };
    }
}
