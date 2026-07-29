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
                    String transformation = "KEEPs";
                    if (colPol.role() == ColumnRole.SENSITIVE) {
                        transformation = colPol.distinguishing() != null && colPol.distinguishing()
                            ? (colPol.quasiIdStrategy() != null ? colPol.quasiIdStrategy().toString() : "REDACT")
                            : (colPol.redactionStrategy() != null ? colPol.redactionStrategy().toString() : "REDACT");
                    } else if (colPol.role() == ColumnRole.INHERITED_ATTRIBUTE) {
                        transformation = "INHERIT from " + colPol.derivedFromTable() + "." + colPol.derivedFromColumn();
                    } else if (colPol.role() == ColumnRole.FOREIGN_KEY) {
                        transformation = "LINK to " + colPol.referencedTable();
                    }
                    columnActions.add(new AnonymisationReport.ColumnAction(colName, colPol.role(), transformation));
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
}
