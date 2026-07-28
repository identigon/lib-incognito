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
        List<String> generatedColumns
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
        throw new UnsupportedOperationException("To be implemented in Phase 2");
    }
}
