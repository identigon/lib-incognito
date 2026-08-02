package org.identigon.incognito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.identigon.incognito.api.ColumnRole;
import org.identigon.incognito.api.DirectIdStrategy;
import org.identigon.incognito.api.IncognitoException;
import org.identigon.incognito.api.IncognitoPipeline;
import org.identigon.incognito.api.PipelineResult;
import org.identigon.incognito.api.QuasiIdStrategy;
import org.identigon.incognito.api.SurrogateStrategy;
import org.identigon.incognito.policy.AnonymisationPolicy;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Verifies the SPECIFICATION.md examples compile and behave as documented (fabrication model). */
class SpecExamplesTest {

    @Test
    void policyExampleBuilds() {
        AnonymisationPolicy policy = AnonymisationPolicy.builder()
            .autoInfer(false)
            .table("customers", table -> table
                .column("id", ColumnRole.PRIMARY_KEY, SurrogateStrategy.SEQUENTIAL_LONG)
                .column("full_name", ColumnRole.DIRECT_ID, DirectIdStrategy.ALTEREGO_NAME)
                .column("email", ColumnRole.DIRECT_ID, DirectIdStrategy.ALTEREGO_EMAIL)
                .column("dob", ColumnRole.QUASI_ID, QuasiIdStrategy.SYNTHESISE)
                .column("debt_recovery_flag", ColumnRole.SENSITIVE)   // non-distinguishing → kept real (distinguishing flag is Phase 4)
                .column("status", ColumnRole.PAYLOAD)                 // operational → kept real
            )
            .build();

        assertFalse(policy.autoInfer(), "fail-closed default");
        assertEquals(64, policy.maxCategoricalCardinality());
        assertTrue(policy.table("customers").isPresent());
        assertEquals(ColumnRole.DIRECT_ID,
            policy.table("customers").orElseThrow().column("email").orElseThrow().role());
        assertEquals(QuasiIdStrategy.SYNTHESISE,
            policy.table("customers").orElseThrow().column("dob").orElseThrow().quasiIdStrategy());
    }

    /** Compile-only mirror of the pipeline example; not executed (execute() is a Phase 2-6 stub). */
    @SuppressWarnings("unused")
    static void pipelineExampleCompiles(DataSource productionDataSource, DataSource testDataSource,
                                        AnonymisationPolicy policy) {
        IncognitoPipeline pipeline = IncognitoPipeline.builder()
            .source(productionDataSource)
            .target(testDataSource)
            .ephemeralSalt()
            .policy(policy)
            .build();

        PipelineResult result = pipeline.execute();
    }

    @Test
    void builderRejectsMissingConfig() {
        assertThrows(IncognitoException.ConfigException.class, () -> IncognitoPipeline.builder().build());
    }

    @Test
    void saltModesAreMutuallyExclusive() {
        assertThrows(IncognitoException.ConfigException.class,
            () -> IncognitoPipeline.builder().ephemeralSalt().persistentSalt(new byte[16]));
    }
}
