package org.identigon.incognito.policy;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.identigon.incognito.api.ColumnRole;
import org.identigon.incognito.api.DirectIdStrategy;
import org.identigon.incognito.api.IncognitoException;
import org.identigon.incognito.api.StructuralUniquenessMode;
import org.junit.jupiter.api.Test;

class YamlConfigTest {

    @Test
    void testParseValidConfig() {
        String yamlString = """
            autoInfer: true
            maxCategoricalCardinality: 100
            tables:
              users:
                columns:
                  id:
                    role: PRIMARY_KEY
                    surrogateStrategy: SEQUENTIAL_LONG
                  email:
                    role: DIRECT_ID
                    directIdStrategy: ALTEREGO_EMAIL
                  dob:
                    role: QUASI_ID
                    quasiIdStrategy: SYNTHESISE
                  status:
                    role: PAYLOAD
              orders:
                columns:
                  id:
                    role: PRIMARY_KEY
                    surrogateStrategy: UUID_V4
                  user_id:
                    role: FOREIGN_KEY
                    references:
                      table: users
                      column: id
            """;

        InputStream inputStream = new ByteArrayInputStream(yamlString.getBytes(StandardCharsets.UTF_8));
        YamlPolicyParser parser = new YamlPolicyParser();

        AnonymisationPolicy policy = parser.parse(inputStream);

        assertTrue(policy.autoInfer());
        assertEquals(100, policy.maxCategoricalCardinality());
        assertNotNull(policy.tables());
        assertEquals(2, policy.tables().size());

        TablePolicy usersTable = policy.table("users").orElseThrow();
        assertEquals("users", usersTable.tableName());
        assertEquals(4, usersTable.columns().size());

        ColumnPolicy emailCol = usersTable.column("email").orElseThrow();
        assertEquals("email", emailCol.columnName());
        assertEquals(ColumnRole.DIRECT_ID, emailCol.role());
        assertEquals(DirectIdStrategy.ALTEREGO_EMAIL, emailCol.directIdStrategy());

        TablePolicy ordersTable = policy.table("orders").orElseThrow();
        assertEquals("orders", ordersTable.tableName());
        assertEquals(2, ordersTable.columns().size());

        ColumnPolicy userIdCol = ordersTable.column("user_id").orElseThrow();
        assertEquals("user_id", userIdCol.columnName());
        assertEquals(ColumnRole.FOREIGN_KEY, userIdCol.role());
        assertEquals("users", userIdCol.referencedTable());
        assertEquals("id", userIdCol.referencedColumn());
    }

    @Test
    void testParseInvalidYaml() {
        String yamlString = """
            autoInfer: true
              invalid_indentation: 100
            """;

        InputStream inputStream = new ByteArrayInputStream(yamlString.getBytes(StandardCharsets.UTF_8));
        YamlPolicyParser parser = new YamlPolicyParser();

        assertThrows(IncognitoException.ConfigException.class, () -> parser.parse(inputStream));
    }

    @Test
    void columnMissingRoleKeyParsesWithNullRoleNotPayloadDefault() {
        // A column entry present under `columns:` but missing the `role:` key (e.g. a policy
        // author who declared a strategy but forgot the role) must NOT silently resolve to
        // ColumnRole.PAYLOAD — SchemaDiscoveryStage relies on role() staying null to fail closed.
        String yamlString = """
            tables:
              customers:
                columns:
                  ssn:
                    directIdStrategy: ALTEREGO_GENERIC
            """;

        InputStream inputStream = new ByteArrayInputStream(yamlString.getBytes(StandardCharsets.UTF_8));
        AnonymisationPolicy policy = new YamlPolicyParser().parse(inputStream);

        ColumnPolicy ssnCol = policy.table("customers").orElseThrow().column("ssn").orElseThrow();
        assertNull(ssnCol.role(), "a column missing the role: key must parse with a null role, "
            + "never default to PAYLOAD");
    }

    @Test
    void testParseEmptyConfig() {
        String yamlString = "";

        InputStream inputStream = new ByteArrayInputStream(yamlString.getBytes(StandardCharsets.UTF_8));
        YamlPolicyParser parser = new YamlPolicyParser();

        AnonymisationPolicy policy = parser.parse(inputStream);

        assertFalse(policy.autoInfer());
        assertEquals(64, policy.maxCategoricalCardinality());
        assertEquals(StructuralUniquenessMode.OFF, policy.structuralUniqueness(), "off by default (SPEC §2.4)");
        assertEquals(5, policy.structuralRarenessK());
        assertTrue(policy.tables().isEmpty());
    }

    @Test
    void structuralUniquenessKeysParseFromYaml() {
        String yamlString = """
            structuralUniqueness: REPORT
            structuralRarenessK: 10
            """;

        InputStream inputStream = new ByteArrayInputStream(yamlString.getBytes(StandardCharsets.UTF_8));
        AnonymisationPolicy policy = new YamlPolicyParser().parse(inputStream);

        assertEquals(StructuralUniquenessMode.REPORT, policy.structuralUniqueness());
        assertEquals(10, policy.structuralRarenessK());
    }
}
