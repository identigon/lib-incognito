package io.github.dconneely.incognito.core;

import io.github.dconneely.incognito.engine.DialectHandler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

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

    public void insertRow(Object[] row) throws SQLException {
        for (int i = 0; i < row.length; i++) {
            insertStmt.setObject(i + 1, row[i]);
        }
        insertStmt.addBatch();
        batchCount++;
        rowCount++;
        
        if (batchCount >= BATCH_SIZE) {
            insertStmt.executeBatch();
            batchCount = 0;
        }
    }
    
    public long getRowCount() {
        return rowCount;
    }

    @Override
    public void close() throws SQLException {
        try {
            if (batchCount > 0) {
                insertStmt.executeBatch();
            }
        } finally {
            try {
                insertStmt.close();
            } finally {
                dialect.postLoadTable(targetConn, tableName);
                if (pkColumn != null) {
                    dialect.resyncSequence(targetConn, tableName, pkColumn);
                }
            }
        }
    }

    public record DeferredUpdate(String tableName, String pkColumn, Object pkValue, String fkColumn, String referencedTable, Object sourceFkValue) {}

    public static void resolveDeferredCyclicFKs(io.github.dconneely.incognito.api.PipelineContext context, List<DeferredUpdate> deferredUpdates) throws io.github.dconneely.incognito.api.IncognitoException {
        if (deferredUpdates == null || deferredUpdates.isEmpty()) return;
        
        try (Connection targetConn = context.target().getConnection()) {
            for (DeferredUpdate update : deferredUpdates) {
                // Get the final fabricated FK value
                java.util.Optional<Object> mapped = context.keyStore().get(update.referencedTable(), update.sourceFkValue());
                if (mapped.isEmpty()) {
                    throw new io.github.dconneely.incognito.api.IncognitoException.ConstraintException(
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
            throw new io.github.dconneely.incognito.api.IncognitoException.SchemaException("Failed to resolve deferred cyclic FKs", e);
        }
    }
}
