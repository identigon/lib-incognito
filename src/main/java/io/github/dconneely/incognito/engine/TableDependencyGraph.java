package io.github.dconneely.incognito.engine;

import io.github.dconneely.incognito.api.IncognitoException;
import java.util.List;

/**
 * Computes a Directed Acyclic Graph (DAG) for processing order using Tarjan's SCC algorithm.
 * Resolves cyclic foreign key dependencies.
 */
public class TableDependencyGraph {

    public record TopologicalExecutionPlan(
        List<String> sequentialTableOrder,
        List<String> cyclicTablesToUpdatePass2
    ) {}

    /**
     * Computes the linear topological execution order for a list of tables.
     *
     * @param metadata List of table metadata discovered by SchemaInspector.
     * @return Ordered execution plan.
     * @throws IncognitoException.SchemaException if unresolvable non-nullable cycles exist.
     */
    public TopologicalExecutionPlan computeTopologicalOrder(List<SchemaInspector.TableMetadata> metadata)
            throws IncognitoException.SchemaException {
        throw new UnsupportedOperationException("To be implemented in Phase 2");
    }
}
