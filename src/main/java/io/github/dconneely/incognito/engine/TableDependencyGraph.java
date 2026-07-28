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
        
        java.util.Map<String, java.util.List<String>> adj = new java.util.HashMap<>();
        java.util.Map<String, Integer> inDegree = new java.util.HashMap<>();
        
        for (SchemaInspector.TableMetadata tm : metadata) {
            String table = tm.tableName();
            adj.putIfAbsent(table, new java.util.ArrayList<>());
            inDegree.putIfAbsent(table, 0);
        }
        
        for (SchemaInspector.TableMetadata tm : metadata) {
            String child = tm.tableName();
            for (String parent : tm.foreignKeys().values()) {
                if (adj.containsKey(parent)) {
                    adj.get(parent).add(child);
                    inDegree.put(child, inDegree.get(child) + 1);
                }
            }
        }
        
        java.util.Queue<String> queue = new java.util.LinkedList<>();
        for (java.util.Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }
        
        List<String> sequentialTableOrder = new java.util.ArrayList<>();
        
        while (!queue.isEmpty()) {
            String curr = queue.poll();
            sequentialTableOrder.add(curr);
            
            for (String neighbor : adj.getOrDefault(curr, new java.util.ArrayList<>())) {
                int newDegree = inDegree.get(neighbor) - 1;
                inDegree.put(neighbor, newDegree);
                if (newDegree == 0) {
                    queue.add(neighbor);
                }
            }
        }
        
        List<String> cyclicTablesToUpdatePass2 = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() > 0) {
                cyclicTablesToUpdatePass2.add(entry.getKey());
            }
        }
        
        return new TopologicalExecutionPlan(sequentialTableOrder, cyclicTablesToUpdatePass2);
    }
}
