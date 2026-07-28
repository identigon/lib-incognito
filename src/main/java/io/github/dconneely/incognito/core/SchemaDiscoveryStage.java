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

import java.util.List;

/**
 * Stage 1: Inspects the source database schema via JDBC metadata, validates the
 * {@link AnonymisationPolicy} against discovered columns (fail-closed), and builds a
 * topological execution plan for table processing. Results are stored in the pipeline
 * context's {@code attributes()} map for downstream stages.
 */
public final class SchemaDiscoveryStage implements PipelineStage {

    /** Key used to store the discovered schema metadata in the pipeline context attributes. */
    public static final String ATTR_TABLE_METADATA = "incognito.schema.tableMetadata";

    /** Key used to store the topological execution plan in the pipeline context attributes. */
    public static final String ATTR_EXECUTION_PLAN = "incognito.schema.executionPlan";

    public static final String ATTR_INFER_SUGGESTIONS = "incognito.schema.inferSuggestions";

    private final SchemaInspector schemaInspector;
    private final TableDependencyGraph dependencyGraph;
    private final io.github.dconneely.incognito.policy.PolicyInferrer inferrer = new io.github.dconneely.incognito.policy.PolicyInferrer();

    public SchemaDiscoveryStage() {
        this(new SchemaInspector(), new TableDependencyGraph());
    }

    public SchemaDiscoveryStage(SchemaInspector inspector, TableDependencyGraph graph) {
        this.schemaInspector = inspector;
        this.dependencyGraph = graph;
    }

    @Override
    public StageResult process(PipelineContext context) throws IncognitoException {
        // 1. Inspect the source database schema.
        List<SchemaInspector.TableMetadata> metadata = schemaInspector.inspect(context.source());

        java.util.Map<String, java.util.List<io.github.dconneely.incognito.api.AnonymisationReport.InferSuggestion>> suggestions = new java.util.HashMap<>();

        // 2. Validate policy: every discovered column in policy-declared tables must have a role.
        AnonymisationPolicy policy = context.policy();
        for (SchemaInspector.TableMetadata table : metadata) {
            policy.table(table.tableName()).ifPresent(tablePolicy ->
                validateTablePolicy(table, tablePolicy, policy.autoInfer(), suggestions)
            );
        }

        // 3. Build the topological execution plan.
        TableDependencyGraph.TopologicalExecutionPlan plan =
            dependencyGraph.computeTopologicalOrder(metadata);

        // 4. Store results in context for downstream stages.
        context.attributes().put(ATTR_TABLE_METADATA, metadata);
        context.attributes().put(ATTR_EXECUTION_PLAN, plan);
        context.attributes().put(ATTR_INFER_SUGGESTIONS, suggestions);

        return new StageResult(
            "SchemaDiscoveryStage",
            true,
            metadata.size(),
            "Discovered " + metadata.size() + " tables, processing order: " + plan.sequentialTableOrder()
        );
    }

    /**
     * Validates that every column in the discovered table has a declared role in the policy.
     * Fail-closed: an unclassified column aborts the run with ConfigException (SPEC §7.2).
     */
    private void validateTablePolicy(
            SchemaInspector.TableMetadata table,
            TablePolicy tablePolicy,
            boolean autoInfer,
            java.util.Map<String, java.util.List<io.github.dconneely.incognito.api.AnonymisationReport.InferSuggestion>> allSuggestions) {

        java.util.List<io.github.dconneely.incognito.api.AnonymisationReport.InferSuggestion> tableSuggestions = new java.util.ArrayList<>();

        for (String column : table.columns()) {
            // Skip generated columns — they are excluded from INSERT and need no classification.
            if (table.generatedColumns().contains(column)) {
                continue;
            }

            if (tablePolicy.column(column).isEmpty()) {
                if (!autoInfer) {
                    throw new IncognitoException.ConfigException(
                        "Fail-closed: column '" + column + "' in table '" + table.tableName()
                            + "' has no declared ColumnRole in the policy. "
                            + "Either classify it explicitly or enable autoInfer (opt-in).");
                }
                
                // autoInfer is opt-in only — it suggests roles but never silently assigns.
                inferrer.inferRole(column).ifPresent(inferred -> {
                    tableSuggestions.add(new io.github.dconneely.incognito.api.AnonymisationReport.InferSuggestion(
                        column, inferred.role(), inferred.heuristic()
                    ));
                });
            }
        }
        allSuggestions.put(table.tableName(), tableSuggestions);
    }
}
