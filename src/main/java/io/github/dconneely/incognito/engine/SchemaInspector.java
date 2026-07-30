package io.github.dconneely.incognito.engine;

import io.github.dconneely.incognito.api.IncognitoException;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Inspects JDBC metadata to discover physical tables, primary keys, foreign keys, unique candidate keys, and data types.
 */
public class SchemaInspector {

    public record TableMetadata(
        String tableName,
        List<String> primaryKeyColumns,
        Map<String, String> foreignKeys, // fkColumn -> parentTable.parentColumn
        List<String> uniqueCandidateKeys,
        List<String> columns,
        List<String> generatedColumns,
        List<String> identityColumns,  // IS_AUTOINCREMENT=YES — inserted with OVERRIDING SYSTEM VALUE
        Map<String, Integer> columnTypes  // column -> java.sql.Types code (for the opaque-type audit, SPEC §7.2)
    ) {}

    /**
     * Inspects the target database schema via JDBC DatabaseMetaData.
     * Filters out VIEW and MATERIALIZED VIEW objects, keeping physical TABLE objects only.
     *
     * @param dataSource DataSource connection.
     * @return List of discovered physical table metadata objects.
     * @throws IncognitoException.SchemaException if JDBC inspection fails.
     */
    public List<TableMetadata> inspect(DataSource dataSource) throws IncognitoException.SchemaException {
        List<TableMetadata> tables = new java.util.ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            java.sql.DatabaseMetaData meta = connection.getMetaData();
            String catalog = connection.getCatalog();
            String schema = connection.getSchema();

            try (java.sql.ResultSet tablesRs = meta.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
                while (tablesRs.next()) {
                    String tableName = tablesRs.getString("TABLE_NAME");
                    
                    List<String> columns = new java.util.ArrayList<>();
                    List<String> generatedColumns = new java.util.ArrayList<>();
                    List<String> identityColumns = new java.util.ArrayList<>();
                    List<String> primaryKeyColumns = new java.util.ArrayList<>();
                    // LinkedHashMap: deterministic FK iteration order (getImportedKeys order) is
                    // required for reproducible mode (SPEC §5.2) — coherent-jitter parent selection
                    // and root-ancestor walking must not depend on hash-bucket ordering.
                    Map<String, String> foreignKeys = new java.util.LinkedHashMap<>();
                    List<String> uniqueCandidateKeys = new java.util.ArrayList<>();
                    Map<String, Integer> columnTypes = new java.util.LinkedHashMap<>();

                    try (java.sql.ResultSet pksRs = meta.getPrimaryKeys(catalog, schema, tableName)) {
                        Map<Short, String> pkMap = new java.util.TreeMap<>();
                        while (pksRs.next()) {
                            pkMap.put(pksRs.getShort("KEY_SEQ"), pksRs.getString("COLUMN_NAME"));
                        }
                        primaryKeyColumns.addAll(pkMap.values());
                    }

                    try (java.sql.ResultSet colsRs = meta.getColumns(catalog, schema, tableName, "%")) {
                        while (colsRs.next()) {
                            String colName = colsRs.getString("COLUMN_NAME");
                            columns.add(colName);
                            columnTypes.put(colName, colsRs.getInt("DATA_TYPE"));

                            String isAutoIncrement = colsRs.getString("IS_AUTOINCREMENT");
                            String isGeneratedColumn = colsRs.getString("IS_GENERATEDCOLUMN");
                            
                            boolean isAutoInc = "YES".equalsIgnoreCase(isAutoIncrement);
                            boolean isGenerated = "YES".equalsIgnoreCase(isGeneratedColumn);

                            if (isAutoInc) {
                                identityColumns.add(colName);
                            }
                            if (isGenerated && !isAutoInc) {
                                generatedColumns.add(colName);
                            }
                        }
                    }

                    try (java.sql.ResultSet fksRs = meta.getImportedKeys(catalog, schema, tableName)) {
                        while (fksRs.next()) {
                            String fkColumnName = fksRs.getString("FKCOLUMN_NAME");
                            String pkTableName = fksRs.getString("PKTABLE_NAME");
                            foreignKeys.put(fkColumnName, pkTableName);
                        }
                    }

                    try (java.sql.ResultSet idxRs = meta.getIndexInfo(catalog, schema, tableName, true, false)) {
                        while (idxRs.next()) {
                            String colName = idxRs.getString("COLUMN_NAME");
                            if (colName != null && !primaryKeyColumns.contains(colName)) {
                                if (!uniqueCandidateKeys.contains(colName)) {
                                    uniqueCandidateKeys.add(colName);
                                }
                            }
                        }
                    }

                    tables.add(new TableMetadata(tableName, primaryKeyColumns, foreignKeys, uniqueCandidateKeys, columns, generatedColumns, identityColumns, columnTypes));
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IncognitoException.SchemaException("Failed to inspect schema", e);
        }
        return tables;
    }
}
