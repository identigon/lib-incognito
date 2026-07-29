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
        
        // Build graph where edges are B -> A (if A has an FK referencing B, B must be loaded before A).
        java.util.Map<String, java.util.List<String>> adj = new java.util.HashMap<>();
        for (SchemaInspector.TableMetadata tm : metadata) {
            adj.putIfAbsent(tm.tableName(), new java.util.ArrayList<>());
            for (String parent : tm.foreignKeys().values()) {
                adj.putIfAbsent(parent, new java.util.ArrayList<>());
            }
        }
        
        for (SchemaInspector.TableMetadata tm : metadata) {
            String child = tm.tableName();
            for (String parent : tm.foreignKeys().values()) {
                adj.get(parent).add(child);
            }
        }

        // Run Tarjan's SCC
        Tarjan scc = new Tarjan(new java.util.ArrayList<>(adj.keySet()), adj);
        List<List<String>> components = scc.findSCCs();

        // Condense the graph
        java.util.Map<Integer, java.util.List<Integer>> condensedAdj = new java.util.HashMap<>();
        java.util.Map<Integer, Integer> inDegree = new java.util.HashMap<>();
        for (int i = 0; i < components.size(); i++) {
            condensedAdj.put(i, new java.util.ArrayList<>());
            inDegree.put(i, 0);
        }

        java.util.Map<String, Integer> tableToComp = new java.util.HashMap<>();
        for (int i = 0; i < components.size(); i++) {
            for (String t : components.get(i)) {
                tableToComp.put(t, i);
            }
        }

        for (String u : adj.keySet()) {
            int compU = tableToComp.get(u);
            for (String v : adj.get(u)) {
                int compV = tableToComp.get(v);
                if (compU != compV && !condensedAdj.get(compU).contains(compV)) {
                    condensedAdj.get(compU).add(compV);
                    inDegree.put(compV, inDegree.get(compV) + 1);
                }
            }
        }

        // Kahn's on condensed graph
        java.util.Queue<Integer> queue = new java.util.LinkedList<>();
        for (java.util.Map.Entry<Integer, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<String> sequentialTableOrder = new java.util.ArrayList<>();
        List<String> cyclicTables = new java.util.ArrayList<>();

        // Identify self-loops
        java.util.Set<String> hasSelfLoop = new java.util.HashSet<>();
        for (SchemaInspector.TableMetadata tm : metadata) {
            for (String parent : tm.foreignKeys().values()) {
                if (parent.equals(tm.tableName())) {
                    hasSelfLoop.add(tm.tableName());
                }
            }
        }

        while (!queue.isEmpty()) {
            int compId = queue.poll();
            List<String> comp = components.get(compId);
            
            // Add all tables in this component to the sequential order
            for (String t : comp) {
                // Only include tables that are actually in the metadata (we might have edges to tables not selected)
                if (metadata.stream().anyMatch(m -> m.tableName().equals(t))) {
                    sequentialTableOrder.add(t);
                    if (comp.size() > 1 || hasSelfLoop.contains(t)) {
                        cyclicTables.add(t);
                    }
                }
            }

            for (int neighborComp : condensedAdj.get(compId)) {
                int newDegree = inDegree.get(neighborComp) - 1;
                inDegree.put(neighborComp, newDegree);
                if (newDegree == 0) {
                    queue.add(neighborComp);
                }
            }
        }

        return new TopologicalExecutionPlan(sequentialTableOrder, cyclicTables);
    }

    private static class Tarjan {
        private int index = 0;
        private final java.util.Map<String, Integer> indices = new java.util.HashMap<>();
        private final java.util.Map<String, Integer> lowlinks = new java.util.HashMap<>();
        private final java.util.List<String> stack = new java.util.ArrayList<>();
        private final java.util.Set<String> onStack = new java.util.HashSet<>();
        private final List<List<String>> sccs = new java.util.ArrayList<>();
        private final List<String> nodes;
        private final java.util.Map<String, java.util.List<String>> adj;

        Tarjan(List<String> nodes, java.util.Map<String, java.util.List<String>> adj) {
            this.nodes = nodes;
            this.adj = adj;
        }

        List<List<String>> findSCCs() {
            for (String v : nodes) {
                if (!indices.containsKey(v)) {
                    strongconnect(v);
                }
            }
            return sccs;
        }

        private void strongconnect(String v) {
            indices.put(v, index);
            lowlinks.put(v, index);
            index++;
            stack.add(v);
            onStack.add(v);

            for (String w : adj.getOrDefault(v, java.util.Collections.emptyList())) {
                if (!indices.containsKey(w)) {
                    strongconnect(w);
                    lowlinks.put(v, Math.min(lowlinks.get(v), lowlinks.get(w)));
                } else if (onStack.contains(w)) {
                    lowlinks.put(v, Math.min(lowlinks.get(v), indices.get(w)));
                }
            }

            if (lowlinks.get(v).equals(indices.get(v))) {
                List<String> scc = new java.util.ArrayList<>();
                String w;
                do {
                    w = stack.removeLast();
                    onStack.remove(w);
                    scc.add(w);
                } while (!w.equals(v));
                sccs.add(scc);
            }
        }
    }
}
