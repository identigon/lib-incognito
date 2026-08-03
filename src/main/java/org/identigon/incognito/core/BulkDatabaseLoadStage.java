package org.identigon.incognito.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.identigon.incognito.engine.DialectHandler;

/**
 * Handles batch insertion of transformed rows into the target database.
 * Isolates dialect-specific trigger and foreign key enforcement mechanics.
 */
public final class BulkDatabaseLoadStage implements AutoCloseable {

    private static final int BATCH_SIZE = 1000;

    private final DialectHandler dialect;
    private final Connection targetConn;
    private final String tableName;
    private final String pkColumn;
    private final PreparedStatement insertStmt;

    private int batchCount = 0;
    private long rowCount = 0;

    /**
     * Opens a batched loader for one table: suppresses triggers/FKs and prepares the insert.
     *
     * @param dialect       the dialect handler
     * @param targetConn    the target connection to insert on
     * @param tableName     the table being loaded
     * @param columns       the columns to insert, in order
     * @param hasIdentityPk whether the table has an identity PK (needs {@code OVERRIDING SYSTEM VALUE})
     * @param pkColumn      the primary-key column to resync afterwards, or {@code null}
     * @throws SQLException if load preparation fails
     */
    public BulkDatabaseLoadStage(DialectHandler dialect, Connection targetConn, String tableName,
                                 List<String> columns, boolean hasIdentityPk, String pkColumn) throws SQLException {
        this.dialect = dialect;
        this.targetConn = targetConn;
        this.tableName = tableName;
        this.pkColumn = pkColumn;

        dialect.preLoadTable(targetConn, tableName);
        String insertSql = dialect.buildInsertSql(tableName, columns, hasIdentityPk);
        this.insertStmt = targetConn.prepareStatement(insertSql);
    }

    /**
     * Buffers a row, flushing the batch when it reaches the batch size.
     *
     * @param row the column values in insert order
     * @throws SQLException if a batch flush fails
     */
    public void insertRow(Object[] row) throws SQLException {
        for (int i = 0; i < row.length; i++) {
            Object value = row[i];
            // Bind String values as 'unknown' (Types.OTHER) so PostgreSQL casts them to the column's
            // actual type — the way a string literal does. This lets a kept enum / user-type value
            // (e.g. an mpaa_rating) round-trip, where a plain varchar bind fails with a type mismatch.
            if (value instanceof String) {
                insertStmt.setObject(i + 1, value, java.sql.Types.OTHER);
            } else {
                insertStmt.setObject(i + 1, value);
            }
        }
        insertStmt.addBatch();
        batchCount++;
        rowCount++;

        if (batchCount >= BATCH_SIZE) {
            insertStmt.executeBatch();
            batchCount = 0;
        }
    }

    /**
     * Returns the number of rows buffered so far.
     *
     * @return the row count
     */
    public long getRowCount() {
        return rowCount;
    }

    @Override
    public void close() throws SQLException {
        // Every step below must still be attempted even if an earlier one fails (matching the
        // original nested try/finally), but the FIRST failure — typically the most actionable one,
        // e.g. a batch constraint violation — must remain primary. A plain try/finally would let a
        // later failure (e.g. closing an already-broken statement) silently replace it; chaining
        // every subsequent failure as suppressed keeps all of them visible instead.
        SQLException primary = null;
        try {
            if (batchCount > 0) {
                insertStmt.executeBatch();
            }
        } catch (SQLException e) {
            primary = e;
        }
        try {
            insertStmt.close();
        } catch (SQLException e) {
            if (primary == null) primary = e; else primary.addSuppressed(e);
        }
        try {
            dialect.postLoadTable(targetConn, tableName);
            if (pkColumn != null) {
                dialect.resyncSequence(targetConn, tableName, pkColumn);
            }
        } catch (SQLException e) {
            if (primary == null) primary = e; else primary.addSuppressed(e);
        }
        if (primary != null) throw primary;
    }

    /**
     * A pending pass-2 {@code UPDATE} that resolves one cyclic/self-referential FK once the referenced
     * row's surrogate key is known.
     *
     * @param tableName       the table to update
     * @param pkColumn        the PK column identifying the row to update
     * @param pkValue         the target PK value of the row to update
     * @param fkColumn        the FK column to set
     * @param referencedTable the parent table the FK references
     * @param sourceFkValue   the original (source) FK value to translate
     */
    public record DeferredUpdate(String tableName, String pkColumn, Object pkValue, String fkColumn, String referencedTable, Object sourceFkValue) {}

    /**
     * Applies all deferred pass-2 {@code UPDATE}s, resolving cyclic-FK placeholders to real surrogates.
     *
     * @param context         the pipeline context (for the target connection and key store)
     * @param deferredUpdates the pending updates (may be {@code null} or empty)
     * @throws org.identigon.incognito.api.IncognitoException if an update fails
     */
    public static void resolveDeferredCyclicFKs(org.identigon.incognito.api.PipelineContext context, List<DeferredUpdate> deferredUpdates) throws org.identigon.incognito.api.IncognitoException {
        if (deferredUpdates == null || deferredUpdates.isEmpty()) return;

        try (Connection targetConn = context.target().getConnection()) {
            for (DeferredUpdate update : deferredUpdates) {
                // Get the final fabricated FK value
                java.util.Optional<Object> mapped = context.keyStore().get(update.referencedTable(), update.sourceFkValue());
                if (mapped.isEmpty()) {
                    throw new org.identigon.incognito.api.IncognitoException.ConstraintException(
                        "Deferred cyclic FK: no key translation found for FK value '" + update.sourceFkValue()
                        + "' referencing table '" + update.referencedTable() + "'");
                }

                String updateSql = "UPDATE " + update.tableName() + " SET " + update.fkColumn() + " = ? WHERE " + update.pkColumn() + " = ?";
                try (PreparedStatement stmt = targetConn.prepareStatement(updateSql)) {
                    stmt.setObject(1, mapped.get());
                    stmt.setObject(2, update.pkValue());
                    stmt.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new org.identigon.incognito.api.IncognitoException.SchemaException("Failed to resolve deferred cyclic FKs", e);
        }
    }
}
