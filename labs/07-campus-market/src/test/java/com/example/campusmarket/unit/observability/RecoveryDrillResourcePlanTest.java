package com.example.campusmarket.unit.observability;

import com.example.campusmarket.integration.RecoveryDrillResourcePlan;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryDrillResourcePlanTest {
    @Test
    void rabbitPaymentProviderTargetsTheRabbitHttpServer() {
        assertThat(RecoveryDrillResourcePlan.rabbitPaymentProviderUrl())
            .isEqualTo("http://localhost:" + RecoveryDrillResourcePlan.RABBIT_HTTP_PORT + "/simulated-provider");
    }

    @Test
    void heavyDependenciesNeverOverlapWithinARecoveryStage() {
        assertThat(RecoveryDrillResourcePlan.stages()).allSatisfy(stage -> {
            long heavy = stage.resources().stream()
                .filter(resource -> resource.equals("RABBIT") || resource.equals("MINIO") || resource.equals("ELASTICSEARCH"))
                .count();
            assertThat(heavy).as(stage.name()).isEqualTo(1);
        });
    }

    @Test
    void everyRoundHasCoreAclAndSearchStages() {
        assertThat(RecoveryDrillResourcePlan.stages()).extracting(RecoveryDrillResourcePlan.Stage::name)
            .containsExactly("rabbit-core", "rabbit-acl", "rabbit-search", "search-core", "search-acl", "search-search",
                "storage-core", "storage-acl", "storage-search");
    }
}
