package io.github.dconneely.incognito.core;

import io.github.dconneely.alterego.AlterEgo;
import io.github.dconneely.alterego.Randomness;
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
    /** Cascade-store attribute-name prefix under which a row's source FK ids are published for ancestor walking. */
    private static final String LINK_PREFIX = "@fk:";
    /** SYNTHESISE window for dates: ±5y destroys the identifying year (SPEC Appendix B). */
    private static final int SYNTHESISE_DATE_WINDOW_DAYS = 1825;

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

        // Which (table, column) values are the SOURCE of some INHERITED_ATTRIBUTE elsewhere? Parents
        // must publish exactly these to the cascade store so descendants can inherit the fabricated
        // value (SPEC §6.1). Empty when no policy uses INHERITED_ATTRIBUTE — the common, zero-overhead path.
        java.util.Set<String> publishTargets = computePublishTargets(policy);
        boolean anyInheritance = !publishTargets.isEmpty();

        long totalRows = 0;
        java.util.Map<String, Long> rowsPerTable = new java.util.LinkedHashMap<>();
        List<BulkDatabaseLoadStage.DeferredUpdate> deferredUpdates = new java.util.ArrayList<>();

        for (String tableName : plan.sequentialTableOrder()) {
            SchemaInspector.TableMetadata tableMeta = metadataByName.get(tableName);
            if (tableMeta == null) continue;

            Optional<TablePolicy> tablePolicyOpt = policy.table(tableName);
            if (tablePolicyOpt.isEmpty()) continue; // Skip tables not in policy

            TablePolicy tablePolicy = tablePolicyOpt.get();
            long rowCount = processTable(context, tableMeta, tablePolicy, alterEgo, keyStore,
                metadataByName, publishTargets, anyInheritance, plan.cyclicTablesToUpdatePass2(), deferredUpdates);
            totalRows += rowCount;
            rowsPerTable.put(tableName, rowCount);
        }

        // Pass 2: resolve deferred cyclic FKs
        BulkDatabaseLoadStage.resolveDeferredCyclicFKs(context, deferredUpdates);

        context.attributes().put("incognito.metrics.rowsPerTable", rowsPerTable);

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
            KeyTranslationStore keyStore,
            Map<String, SchemaInspector.TableMetadata> metadataByName,
            java.util.Set<String> publishTargets,
            boolean anyInheritance,
            List<String> cyclicTables,
            List<BulkDatabaseLoadStage.DeferredUpdate> deferredUpdates) throws IncognitoException {

        String tableName = tableMeta.tableName();

        // Determine which columns to SELECT from source and INSERT into target.
        // Exclude generated columns (computed columns).
        List<String> columnsToProcess = tableMeta.columns().stream()
            .filter(col -> !tableMeta.generatedColumns().contains(col))
            .toList();

        if (columnsToProcess.isEmpty()) return 0;

        // Build transformations for each column.
        List<ColumnTransformer> transformers = columnsToProcess.stream()
            .map(col -> buildTransformer(col, tablePolicy, tableMeta, alterEgo, metadataByName, cyclicTables))
            .toList();

        // Columns of THIS table whose fabricated value a descendant will inherit (SPEC §6.1) — publish these.
        java.util.Set<String> columnsToPublish = new java.util.LinkedHashSet<>();
        if (anyInheritance) {
            for (String c : columnsToProcess) {
                if (publishTargets.contains(tableName + " " + c)) {
                    columnsToPublish.add(c);
                }
            }
        }

        // OVERRIDING SYSTEM VALUE is required for identity PKs and errors on a non-identity PK,
        // so key strictly off the identity flag from the schema inspector.
        boolean hasIdentityPk = !tableMeta.primaryKeyColumns().isEmpty()
            && tableMeta.identityColumns().contains(tableMeta.primaryKeyColumns().getFirst());

        String selectSql = "SELECT " + String.join(", ", columnsToProcess) + " FROM " + tableName;

        AtomicLong surrogateCounter = new AtomicLong(1);
        long rowCount = 0;

        try (Connection sourceConn = context.source().getConnection();
             Connection targetConn = context.target().getConnection()) {

            sourceConn.setAutoCommit(false);
            targetConn.setAutoCommit(false);
            
            io.github.dconneely.incognito.engine.DialectHandler dialect = getDialectHandler(targetConn);
            String pkColumn = tableMeta.primaryKeyColumns().isEmpty() ? null : tableMeta.primaryKeyColumns().getFirst();

            try (Statement stmt = sourceConn.createStatement()) {
                stmt.setFetchSize(FETCH_SIZE);
                try (ResultSet rs = stmt.executeQuery(selectSql);
                     BulkDatabaseLoadStage loader = new BulkDatabaseLoadStage(dialect, targetConn, tableName, columnsToProcess, hasIdentityPk, pkColumn)) {
                     
                    ResultSetMetaData rsMeta = rs.getMetaData();
                    Object[] rowBuf = new Object[columnsToProcess.size()];

                    while (rs.next()) {
                            // Track deferred updates for this row
                            List<PendingUpdate> rowDeferred = new java.util.ArrayList<>();
                            Object targetPk = null;

                            // The row's own source PK (single-column PK only) — the key everything is
                            // stored under: PK translation, published attributes, and FK linkage.
                            Object sourcePk = null;
                            if (!tableMeta.primaryKeyColumns().isEmpty()) {
                                String pkCol = tableMeta.primaryKeyColumns().getFirst();
                                if (columnsToProcess.contains(pkCol)) {
                                    sourcePk = rs.getObject(pkCol);
                                }
                            }

                            // Publish this row's FK linkage (source parent ids) so a descendant's
                            // INHERITED_ATTRIBUTE can walk the FK chain up to its declared root ancestor.
                            if (anyInheritance && sourcePk != null) {
                                for (Map.Entry<String, String> fk : tableMeta.foreignKeys().entrySet()) {
                                    if (columnsToProcess.contains(fk.getKey())) {
                                        Object fkValue = rs.getObject(fk.getKey());
                                        if (fkValue != null) {
                                            context.cascadeStore().put(tableName, sourcePk, LINK_PREFIX + fk.getKey(), fkValue);
                                        }
                                    }
                                }
                            }

                            for (int i = 0; i < columnsToProcess.size(); i++) {
                                ColumnTransformer transformer = transformers.get(i);
                                String colName = columnsToProcess.get(i);
                                int sqlType = rsMeta.getColumnType(i + 1);

                                Object originalValue = rs.getObject(i + 1);

                                Object transformedValue;
                                try {
                                    transformedValue = transformer.transform(
                                        originalValue, rs, sourcePk, sqlType, surrogateCounter, context, tableMeta);
                                } catch (CyclicFkException e) {
                                    // Defer this update. We will create the DeferredUpdate after the row loop 
                                    // when the target PK is definitely known.
                                    rowDeferred.add(new PendingUpdate(colName, e.referencedTable, e.sourceFkValue));
                                    transformedValue = getPlaceholderForType(sqlType);
                                }

                                // Record PK translation if this is a PK column.
                                if (tableMeta.primaryKeyColumns().contains(colName) && originalValue != null) {
                                    keyStore.put(tableName, originalValue, transformedValue);
                                    targetPk = transformedValue;
                                }

                                // Publish the fabricated value for descendants to inherit (SPEC §6.1).
                                if (!columnsToPublish.isEmpty() && sourcePk != null && columnsToPublish.contains(colName)) {
                                    context.cascadeStore().put(tableName, sourcePk, colName, transformedValue);
                                }

                                rowBuf[i] = transformedValue;
                            }

                            if (!rowDeferred.isEmpty() && targetPk != null) {
                                for (PendingUpdate pu : rowDeferred) {
                                    deferredUpdates.add(new BulkDatabaseLoadStage.DeferredUpdate(
                                        tableName, pkColumn, targetPk, pu.colName, pu.refTable, pu.sourceFk
                                    ));
                                }
                            }

                            loader.insertRow(rowBuf);
                        }
                        
                        rowCount = loader.getRowCount();
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
            AlterEgo alterEgo,
            Map<String, SchemaInspector.TableMetadata> metadataByName,
            List<String> cyclicTables) {

        Optional<ColumnPolicy> policyOpt = tablePolicy.column(columnName);
        if (policyOpt.isEmpty()) {
            return ColumnTransformer.PASSTHROUGH;
        }

        ColumnPolicy colPolicy = policyOpt.get();
        ColumnRole role = colPolicy.role();

        return switch (role) {
            case PRIMARY_KEY -> buildPkTransformer(colPolicy);
            case FOREIGN_KEY -> buildFkTransformer(colPolicy, cyclicTables);
            case DIRECT_ID, UNIQUE_CANDIDATE_KEY -> buildDirectIdTransformer(colPolicy, alterEgo, tableMeta.tableName());
            case QUASI_ID -> buildQuasiIdTransformer(colPolicy, alterEgo, tableMeta.tableName());
            case SENSITIVE -> {
                if (Boolean.FALSE.equals(colPolicy.distinguishing())) {
                    yield ColumnTransformer.PASSTHROUGH;
                }
                if (colPolicy.redactionStrategy() != null) {
                    yield buildRedactionTransformer(colPolicy, alterEgo);
                }
                if (colPolicy.quasiIdStrategy() != null) {
                    yield buildQuasiIdTransformer(colPolicy, alterEgo, tableMeta.tableName());
                }
                yield ColumnTransformer.PASSTHROUGH;
            }
            case INHERITED_ATTRIBUTE -> {
                String derivedTable = colPolicy.derivedFromTable();
                String derivedColumn = colPolicy.derivedFromColumn();
                if (derivedTable == null || derivedColumn == null) {
                    throw new IncognitoException.ConfigException(
                        "INHERITED_ATTRIBUTE column '" + columnName + "' in table '" + tableMeta.tableName()
                            + "' must declare derivedFrom(table, column) (SPEC §6.1).");
                }
                yield (value, rs, pk, sqlType, counter, ctx, meta) ->
                    resolveInheritedValue(ctx, metadataByName, meta.tableName(), pk,
                        derivedTable, derivedColumn, columnName);
            }
            case PAYLOAD, GENERATED_COLUMN -> ColumnTransformer.PASSTHROUGH;
            default -> ColumnTransformer.PASSTHROUGH;
        };
    }

    private ColumnTransformer buildPkTransformer(ColumnPolicy colPolicy) {
        SurrogateStrategy strategy = colPolicy.surrogateStrategy();
        if (strategy == null) strategy = SurrogateStrategy.SEQUENTIAL_LONG;

        return switch (strategy) {
            case SEQUENTIAL_LONG -> (value, rs, pk, sqlType, counter, ctx, meta) ->
                counter.getAndIncrement();
            case UUID_V4 -> (value, rs, pk, sqlType, counter, ctx, meta) ->
                java.util.UUID.randomUUID();
            case PASSTHROUGH_SURROGATE -> ColumnTransformer.PASSTHROUGH;
        };
    }

    private ColumnTransformer buildFkTransformer(ColumnPolicy colPolicy, List<String> cyclicTables) {
        String referencedTable = colPolicy.referencedTable();
        return (value, rs, pk, sqlType, counter, ctx, meta) -> {
            if (value == null) return null;
            Optional<Object> mapped = ctx.keyStore().get(referencedTable, value);
            if (mapped.isPresent()) {
                return mapped.get();
            }
            if (cyclicTables.contains(referencedTable)) {
                throw new CyclicFkException(referencedTable, value);
            }
            throw new IncognitoException.ConstraintException(
                "No key translation found for FK value '" + value + "' referencing table '" + referencedTable + "'");
        };
    }

    private ColumnTransformer buildDirectIdTransformer(
            ColumnPolicy colPolicy, AlterEgo alterEgo, String tableName) {
        DirectIdStrategy strategy = colPolicy.directIdStrategy();
        if (strategy == null) strategy = DirectIdStrategy.ALTEREGO_GENERIC;

        String domain = "incognito:" + tableName + ":" + colPolicy.columnName();
        boolean isUnique = colPolicy.role() == ColumnRole.UNIQUE_CANDIDATE_KEY;

        Transformation<String> transformation = switch (strategy) {
            case ALTEREGO_NAME -> alterEgo.fullName();
            case ALTEREGO_EMAIL -> alterEgo.emailAddress();
            case ALTEREGO_PHONE -> alterEgo.phoneNumber();
            case ALTEREGO_GENERIC -> alterEgo.bind(domain, (input, ctx) -> fabricateShapePreserving(input, ctx.random()));
        };
        
        Transformation<String> finalTransformation = isUnique ? transformation.unique() : transformation;

        return (value, rs, pk, sqlType, counter, ctx, meta) -> {
            if (value == null) return null;
            if (!isUnique) {
                return finalTransformation.apply(value.toString());
            }
            try {
                return finalTransformation.apply(value.toString());
            } catch (io.github.dconneely.alterego.AlterEgoCollisionException e) {
                long seq = counter.getAndIncrement();
                if (sqlType == Types.INTEGER || sqlType == Types.BIGINT || sqlType == Types.NUMERIC || sqlType == Types.DECIMAL) {
                    return seq;
                }
                return uniquenessFallback(transformation.apply(value.toString()), seq);
            }
        };
    }

    private ColumnTransformer buildRedactionTransformer(ColumnPolicy colPolicy, AlterEgo alterEgo) {
        io.github.dconneely.incognito.api.RedactionStrategy strategy = colPolicy.redactionStrategy();
        return switch (strategy) {
            case CLEAR -> (val, rs, pk, type, counter, ctx, meta) -> null;
            case CONSTANT -> (val, rs, pk, type, counter, ctx, meta) -> val == null ? null : alterEgo.constant("REDACTED").apply(val.toString());
            case MASK -> (val, rs, pk, type, counter, ctx, meta) -> val == null ? null : alterEgo.mask('*', 0).apply(val.toString());
        };
    }

    private ColumnTransformer buildQuasiIdTransformer(
            ColumnPolicy colPolicy, AlterEgo alterEgo, String tableName) {
        QuasiIdStrategy strategy = colPolicy.quasiIdStrategy();
        if (strategy == null) strategy = QuasiIdStrategy.SYNTHESISE;

        return switch (strategy) {
            case JITTER_WITHIN_MONTH -> {
                Transformation<LocalDate> dateTransform = alterEgo.shiftDate(AlterEgo.DateField.MONTH);
                yield (value, rs, pk, sqlType, counter, ctx, meta) -> {
                    if (value == null) return null;
                    if (value instanceof LocalDate ld) return dateTransform.apply(ld);
                    if (value instanceof java.sql.Date sd) return java.sql.Date.valueOf(dateTransform.apply(sd.toLocalDate()));
                    return value;
                };
            }
            case JITTER_WITHIN_YEAR -> {
                Transformation<LocalDate> dateTransform = alterEgo.shiftDate(AlterEgo.DateField.YEAR);
                yield (value, rs, pk, sqlType, counter, ctx, meta) -> {
                    if (value == null) return null;
                    if (value instanceof LocalDate ld) return dateTransform.apply(ld);
                    if (value instanceof java.sql.Date sd) return java.sql.Date.valueOf(dateTransform.apply(sd.toLocalDate()));
                    return value;
                };
            }
            case JITTER_DAYS -> {
                int days = colPolicy.jitterDays() > 0 ? colPolicy.jitterDays() : 14;
                String group = colPolicy.coherenceGroup();
                
                if (group == null) {
                    Transformation<LocalDate> dateTransform = alterEgo.shiftDate(days);
                    yield (value, rs, pk, sqlType, counter, ctx, meta) -> {
                        if (value == null) return null;
                        if (value instanceof LocalDate ld) return dateTransform.apply(ld);
                        if (value instanceof java.sql.Date sd) return java.sql.Date.valueOf(dateTransform.apply(sd.toLocalDate()));
                        return value;
                    };
                } else {
                    Transformation<String> hmacer = alterEgo.bind("incognito:jitterdelta:" + group, (input, c) -> {
                        long d = c.random().nextLong(2L * days + 1) - days;
                        return Long.toString(d);
                    });

                    yield (value, rs, pk, sqlType, counter, ctx, meta) -> {
                        if (value == null) return null;

                        // Inherit the delta from the direct FK parent that anchors THIS coherence group.
                        // Scoping the lookup by group means an unrelated parent's delta can never
                        // contaminate, so multiple FKs are handled correctly (SPEC §4.2, guide §3).
                        Long delta = null;
                        for (Map.Entry<String, String> fk : meta.foreignKeys().entrySet()) {
                            Object fkValue = rs.getObject(fk.getKey());
                            if (fkValue != null) {
                                Optional<Long> parentDelta = ctx.cascadeStore().getJitterDelta(group, fk.getValue(), fkValue);
                                if (parentDelta.isPresent()) {
                                    delta = parentDelta.get();
                                    break;
                                }
                            }
                        }

                        // No anchor above: this entity originates the group — derive its own
                        // salt-keyed delta (never pk.hashCode(); the source PK is known).
                        if (delta == null) {
                            String key = pk != null ? pk.toString() : String.valueOf(value);
                            delta = Long.parseLong(hmacer.apply(key));
                        }

                        // Re-publish the effective delta under this entity so deeper descendants
                        // (grandchildren) inherit the same shift via a single one-hop lookup.
                        if (pk != null) {
                            ctx.cascadeStore().putJitterDelta(group, meta.tableName(), pk, delta);
                        }

                        long d = delta;
                        if (value instanceof LocalDate ld) return ld.plusDays(d);
                        if (value instanceof java.sql.Date sd) return java.sql.Date.valueOf(sd.toLocalDate().plusDays(d));
                        if (value instanceof java.sql.Timestamp ts) {
                            return java.sql.Timestamp.valueOf(ts.toLocalDateTime().plusDays(d));
                        }
                        return value;
                    };
                }
            }
            case SYNTHESISE -> {
                String domain = "incognito:synth:" + tableName + ":" + colPolicy.columnName();
                Transformation<LocalDate> dateTransform = alterEgo.shiftDate(SYNTHESISE_DATE_WINDOW_DAYS);
                Transformation<String> strTransform = alterEgo.bind(domain,
                    (input, ctx) -> fabricateShapePreserving(input, ctx.random()));
                yield (value, rs, pk, sqlType, counter, ctx, meta) -> {
                    if (value == null) return null;
                    if (value instanceof LocalDate || value instanceof java.sql.Date) {
                        LocalDate ld = (value instanceof java.sql.Date sd) ? sd.toLocalDate() : (LocalDate) value;
                        LocalDate result = dateTransform.apply(ld);
                        return (value instanceof java.sql.Date) ? java.sql.Date.valueOf(result) : result;
                    }
                    return strTransform.apply(value.toString());
                };
            }
        };
    }

    /**
     * The set of {@code "table column"} keys naming every column that is the SOURCE of some
     * {@code INHERITED_ATTRIBUTE} elsewhere in the policy — the columns whose fabricated value a
     * descendant will inherit, so a parent must publish them (SPEC §6.1).
     */
    private static java.util.Set<String> computePublishTargets(AnonymisationPolicy policy) {
        java.util.Set<String> targets = new java.util.HashSet<>();
        for (TablePolicy table : policy.tables().values()) {
            for (ColumnPolicy col : table.columns().values()) {
                if (col.role() == ColumnRole.INHERITED_ATTRIBUTE
                        && col.derivedFromTable() != null && col.derivedFromColumn() != null) {
                    targets.add(col.derivedFromTable() + ' ' + col.derivedFromColumn());
                }
            }
        }
        return targets;
    }

    /**
     * Resolves an {@code INHERITED_ATTRIBUTE} by walking the child's FK chain (via published source
     * linkage) up to the declared root-ancestor table, then reading the ancestor's already-fabricated
     * value from the cascade store (SPEC §6.1). Fail-closed:
     * <ul>
     *   <li>no reachable ancestor row (e.g. a nullable FK is null) → {@code null} — nothing to leak;</li>
     *   <li>two <em>distinct</em> ancestor rows reached (a genuine fork) → {@link IncognitoException.ConstraintException};</li>
     *   <li>ancestor reached but its value was never published (ordering/config error) → {@code ConstraintException}.</li>
     * </ul>
     * It never silently passes the child's own real value through.
     */
    private static Object resolveInheritedValue(
            PipelineContext ctx,
            Map<String, SchemaInspector.TableMetadata> metadataByName,
            String startTable, Object startId,
            String targetTable, String targetColumn,
            String columnName) throws IncognitoException {

        if (startId == null) return null; // no PK to anchor the walk → no ancestor

        java.util.Deque<Object[]> frontier = new java.util.ArrayDeque<>();
        frontier.add(new Object[]{startTable, startId});
        java.util.Set<Object> visited = new java.util.HashSet<>();
        java.util.Set<Object> ancestorIds = new java.util.LinkedHashSet<>();

        while (!frontier.isEmpty()) {
            Object[] cur = frontier.poll();
            String table = (String) cur[0];
            Object id = cur[1];
            if (!visited.add(java.util.List.of(table, id))) continue; // cycle / diamond convergence guard

            if (table.equals(targetTable)) {
                ancestorIds.add(id); // reached the declared ancestor — do not climb higher
                continue;
            }
            SchemaInspector.TableMetadata m = metadataByName.get(table);
            if (m == null) continue;
            for (Map.Entry<String, String> fk : m.foreignKeys().entrySet()) {
                Optional<Object> parentId = ctx.cascadeStore().get(table, id, LINK_PREFIX + fk.getKey());
                if (parentId.isPresent()) {
                    frontier.add(new Object[]{fk.getValue(), parentId.get()});
                }
            }
        }

        if (ancestorIds.isEmpty()) return null;
        if (ancestorIds.size() > 1) {
            throw new IncognitoException.ConstraintException(
                "INHERITED_ATTRIBUTE '" + columnName + "' resolves to " + ancestorIds.size()
                    + " distinct '" + targetTable + "' ancestor rows (a genuine fork) — cannot inherit coherently (SPEC §6.1).");
        }
        Object ancestorId = ancestorIds.iterator().next();
        return ctx.cascadeStore().get(targetTable, ancestorId, targetColumn).orElseThrow(() ->
            new IncognitoException.ConstraintException(
                "INHERITED_ATTRIBUTE '" + columnName + "': ancestor value '" + targetTable + '.' + targetColumn
                    + "' for id '" + ancestorId + "' was not published — is '" + targetTable
                    + "' loaded before its descendants and its column classified? (SPEC §6.1)."));
    }

    /**
     * Length-preserving uniqueness fallback for a string {@code UNIQUE_CANDIDATE_KEY} once AlterEgo's
     * {@code unique()} retry budget is exhausted: overlays a zero-padded sequence onto the TAIL of the
     * fabricated value, keeping the exact original length so a fixed-width / CHECK constraint still
     * holds (Goal 1). Interim — full format-preserving generation is the deferred lib-alterego
     * delegation (see PLAN).
     */
    static String uniquenessFallback(String base, long seq) {
        int n = base.length();
        if (n == 0) return base;
        int width = Math.min(n, 6);
        long mod = 1L;
        for (int i = 0; i < width; i++) mod *= 10L;
        String digits = String.format("%0" + width + "d", Math.floorMod(seq, mod));
        return base.substring(0, n - width) + digits;
    }

    private io.github.dconneely.incognito.engine.DialectHandler getDialectHandler(Connection conn) throws SQLException {
        String dbName = conn.getMetaData().getDatabaseProductName();
        if (dbName != null && dbName.toLowerCase().contains("postgresql")) {
            return new io.github.dconneely.incognito.engine.PostgresDialectHandler();
        }
        return new io.github.dconneely.incognito.engine.GenericDialectHandler();
    }

    /**
     * Salt-keyed, shape/length-preserving fabrication: each character is replaced by a random one of
     * the same class (digit/upper/lower) drawn from AlterEgo's HMAC-SHA256 stream; other characters
     * are kept so length and format survive (helps satisfy CHECK/length constraints — Goal 1).
     *
     * <p>TODO (SPEC §1.4, PLAN): this is field-value <em>substitution</em> and violates the rule that
     * Incognito delegates all value transformation to lib-alterego — it belongs in AlterEgo as a
     * shape-preserving primitive. Tracked for migration; kept here as an interim until AlterEgo exposes it.
     */
    private static String fabricateShapePreserving(String input, Randomness r) {
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isDigit(c)) sb.append(r.digit());
            else if (c >= 'A' && c <= 'Z') sb.append(r.letterUpper());
            else if (c >= 'a' && c <= 'z') sb.append(r.letterLower());
            else sb.append(c);
        }
        return sb.toString();
    }



    /**
     * Functional interface for per-column transformation logic.
     */
    @FunctionalInterface
    interface ColumnTransformer {
        /** Passthrough: returns the value unchanged. */
        ColumnTransformer PASSTHROUGH = (value, rs, pk, sqlType, counter, ctx, meta) -> value;

        Object transform(Object value, ResultSet rs, Object sourcePk, int sqlType, AtomicLong surrogateCounter,
                          PipelineContext ctx, SchemaInspector.TableMetadata meta) throws SQLException, IncognitoException;
    }

    private static Object getPlaceholderForType(int sqlType) {
        return switch (sqlType) {
            case java.sql.Types.BIGINT, java.sql.Types.NUMERIC, java.sql.Types.DECIMAL -> -1L;
            case java.sql.Types.INTEGER, java.sql.Types.SMALLINT, java.sql.Types.TINYINT -> -1;
            case java.sql.Types.VARCHAR, java.sql.Types.CHAR, java.sql.Types.NVARCHAR -> "00000000-0000-0000-0000-000000000000";
            default -> null;
        };
    }

    private static class CyclicFkException extends RuntimeException {
        final String referencedTable;
        final Object sourceFkValue;
        CyclicFkException(String referencedTable, Object sourceFkValue) {
            super(null, null, false, false); // Don't build stack trace for performance
            this.referencedTable = referencedTable;
            this.sourceFkValue = sourceFkValue;
        }
    }

    private record PendingUpdate(String colName, String refTable, Object sourceFk) {}
}
