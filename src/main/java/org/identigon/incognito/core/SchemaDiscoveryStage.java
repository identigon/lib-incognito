package org.identigon.incognito.core;

import java.util.List;
import org.identigon.incognito.api.ColumnRole;
import org.identigon.incognito.api.IncognitoException;
import org.identigon.incognito.api.PipelineContext;
import org.identigon.incognito.api.PipelineStage;
import org.identigon.incognito.engine.SchemaInspector;
import org.identigon.incognito.engine.TableDependencyGraph;
import org.identigon.incognito.policy.AnonymisationPolicy;
import org.identigon.incognito.policy.ColumnPolicy;
import org.identigon.incognito.policy.TablePolicy;

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

    /** Key used to store the auto-inference role suggestions in the pipeline context attributes. */
    public static final String ATTR_INFER_SUGGESTIONS = "incognito.schema.inferSuggestions";

    private final SchemaInspector schemaInspector;
    private final TableDependencyGraph dependencyGraph;
    private final org.identigon.incognito.policy.PolicyInferrer inferrer = new org.identigon.incognito.policy.PolicyInferrer();

    /** Creates a schema-discovery stage with the default inspector and dependency graph. */
    public SchemaDiscoveryStage() {
        this(new SchemaInspector(), new TableDependencyGraph());
    }

    /**
     * Creates a schema-discovery stage with explicit collaborators (for testing).
     *
     * @param inspector the JDBC schema inspector
     * @param graph     the table dependency graph
     */
    public SchemaDiscoveryStage(SchemaInspector inspector, TableDependencyGraph graph) {
        this.schemaInspector = inspector;
        this.dependencyGraph = graph;
    }

    @Override
    public StageResult process(PipelineContext context) throws IncognitoException {
        // 1. Inspect the source database schema.
        List<SchemaInspector.TableMetadata> metadata = schemaInspector.inspect(context.source());

        java.util.Map<String, java.util.List<org.identigon.incognito.api.AnonymisationReport.InferSuggestion>> suggestions = new java.util.HashMap<>();

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
            java.util.Map<String, java.util.List<org.identigon.incognito.api.AnonymisationReport.InferSuggestion>> allSuggestions) {

        java.util.List<org.identigon.incognito.api.AnonymisationReport.InferSuggestion> tableSuggestions = new java.util.ArrayList<>();

        for (String column : table.columns()) {
            // Skip generated columns — they are excluded from INSERT and need no classification.
            if (table.generatedColumns().contains(column)) {
                continue;
            }

            if (tablePolicy.column(column).isEmpty()) {
                // Auto-inference only SUGGESTS a role; it never silently assigns one, so an
                // unclassified column ALWAYS fails-closed (SPEC §7.2) — it must never pass through
                // as real data. With autoInfer on, the suggestion is added to the message to help.
                var inferred = inferrer.inferRole(column);
                String hint = inferred
                    .map(r -> " (auto-infer suggests " + r.role() + " via " + r.heuristic() + ")")
                    .orElse("");
                throw new IncognitoException.ConfigException(
                    "Fail-closed: column '" + column + "' in table '" + table.tableName()
                        + "' has no declared ColumnRole in the policy" + hint
                        + ". Classify it explicitly — auto-infer only suggests, never assigns.");
            } else {
                ColumnPolicy colPol = tablePolicy.column(column).get();
                if (colPol.role() == ColumnRole.SENSITIVE) {
                    if (colPol.distinguishing() == null) {
                        throw new IncognitoException.ConfigException(
                            "Fail-closed: SENSITIVE column '" + column + "' in table '" + table.tableName()
                                + "' does not declare the 'distinguishing' flag. It must explicitly be distinguishing: true or false (SPEC §4.1).");
                    }
                    if (colPol.distinguishing() && colPol.quasiIdStrategy() == null && colPol.redactionStrategy() == null) {
                        throw new IncognitoException.ConfigException(
                            "Fail-closed: SENSITIVE column '" + column + "' in table '" + table.tableName()
                                + "' is distinguishing: true, but declares no RedactionStrategy or QuasiIdStrategy (SPEC §4.1).");
                    }
                }
            }
        }
        allSuggestions.put(table.tableName(), tableSuggestions);
    }
}
