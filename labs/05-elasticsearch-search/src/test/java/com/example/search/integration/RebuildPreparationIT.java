package com.example.search.integration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.example.search.application.maintenance.PreparedRebuild;
import com.example.search.application.maintenance.RebuildAlreadyRunningException;
import com.example.search.application.maintenance.RebuildJob;
import com.example.search.application.maintenance.RebuildJobRepository;
import com.example.search.application.maintenance.RebuildProgressListener;
import com.example.search.application.maintenance.SearchRebuildPreparer;
import com.example.search.application.product.CreateProductCommand;
import com.example.search.application.product.ProductCommandService;
import com.example.search.application.product.ProductView;
import com.example.search.application.product.UpdateProductCommand;
import com.example.search.domain.ProductDetails;
import com.example.search.domain.ProductStatus;
import com.example.search.infrastructure.elasticsearch.ElasticsearchIndexManager;
import com.example.search.application.maintenance.AliasTargets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies a repeatable-read snapshot catches a committed update after its outbox watermark. */
@SpringBootTest
class RebuildPreparationIT extends SharedSearchContainers {
    @org.springframework.beans.factory.annotation.Autowired DataSource dataSource;
    @org.springframework.beans.factory.annotation.Autowired JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired ProductCommandService products;
    @org.springframework.beans.factory.annotation.Autowired SearchRebuildPreparer preparer;
    @org.springframework.beans.factory.annotation.Autowired RebuildJobRepository rebuildJobs;
    @org.springframework.beans.factory.annotation.Autowired ElasticsearchIndexManager indexes;
    @org.springframework.beans.factory.annotation.Autowired ElasticsearchClient client;
    @org.springframework.beans.factory.annotation.Autowired PlatformTransactionManager transactionManager;
    @org.springframework.beans.factory.annotation.Autowired HookState hookState;

    @BeforeEach
    void clean() throws Exception {
        clearTables(dataSource);
        hookState.afterStartWatermark = null;
        indexes.createBootstrapIndex();
        if (!indexes.aliasTargets().isConfigured()) {
            indexes.installAliases("products-vbootstrap");
        }
    }

    @Test
    void replaysEveryEventAfterSnapshotWatermark() throws Exception {
        ProductView created = products.create(new CreateProductCommand(details("初始名称")));
        hookState.afterStartWatermark = () -> {
            Thread update = new Thread(() -> {
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tx.executeWithoutResult(ignored -> products.update(created.id(),
                        new UpdateProductCommand(1L, details("并发新名称"))));
            }, "rebuild-test-concurrent-update");
            update.start();
            try {
                update.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("concurrent update interrupted", interrupted);
            }
        };

        PreparedRebuild prepared = preparer.prepare(preparer.start("rebuild-a"));

        indexes.refresh(prepared.targetIndex());
        Map<String, Object> source = client.get(g -> g.index(prepared.targetIndex())
                        .id(Long.toString(created.id())), Map.class).source();
        assertThat(source).isNotNull();
        assertThat(((Number) source.get("sourceVersion")).longValue()).isEqualTo(2L);
        assertThat(source.get("name")).isEqualTo("并发新名称");
        assertThat(prepared.preparedWatermark()).isGreaterThan(prepared.startWatermark());
        Map<String, Object> job = jdbc.queryForMap("SELECT status, phase, start_watermark, final_watermark, lease_until > UTC_TIMESTAMP(6) AS lease_valid "
                + "FROM search_rebuild_job WHERE job_id=?", prepared.jobId().toString());
        assertThat(job).containsEntry("status", "RUNNING").containsEntry("phase", "CATCH_UP")
                .containsEntry("start_watermark", prepared.startWatermark())
                .containsEntry("final_watermark", prepared.preparedWatermark());
        assertThat(((Number) job.get("lease_valid")).intValue()).isEqualTo(1);
    }

    @Test
    void rejectsSecondStartWhileFirstRunnerHasValidLeaseAndKeepsActiveId() {
        UUID first = preparer.start("rebuild-a");

        assertThatThrownBy(() -> preparer.start("rebuild-b"))
                .isInstanceOf(RebuildAlreadyRunningException.class);
        assertThat(jdbc.queryForObject("SELECT active_rebuild_id FROM search_coordination WHERE id=1", String.class))
                .isEqualTo(first.toString());
        assertThat(rebuildJobs.find(first)).get().extracting(RebuildJob::status)
                .isEqualTo(com.example.search.application.maintenance.RebuildStatus.RUNNING);
    }

    @Test
    void renewRequiresOwnerAndDatabaseLeaseValidity() {
        UUID jobId = preparer.start("rebuild-a");
        RebuildJob job = rebuildJobs.find(jobId).orElseThrow();
        assertThat(job.leaseUntil()).isAfter(Instant.now().plusSeconds(20));
        assertThat(job.leaseUntil()).isBefore(Instant.now().plusSeconds(40));
        assertThat(rebuildJobs.renewLease(jobId, "wrong-owner", Instant.now().plusSeconds(30))).isFalse();

        jdbc.update("UPDATE search_rebuild_job SET lease_until=TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) WHERE job_id=?",
                jobId.toString());
        assertThat(rebuildJobs.renewLease(jobId, "rebuild-a", Instant.now().plusSeconds(30))).isFalse();
    }

    @Test
    void preparationNeverChangesReadWriteAliases() {
        products.create(new CreateProductCommand(details("别名不变")));
        AliasTargets before = indexes.aliasTargets();

        PreparedRebuild prepared = preparer.prepare(preparer.start("rebuild-a"));

        assertThat(prepared.targetIndex()).isNotEqualTo(before.write());
        assertThat(indexes.aliasTargets()).isEqualTo(before);
    }

    private static ProductDetails details(String name) {
        return new ProductDetails(name, null, "描述", "BOOK", "图书", new BigDecimal("10.00"),
                ProductStatus.ON_SALE);
    }

    static final class HookState {
        private Runnable afterStartWatermark;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class HookConfiguration {
        @Bean
        HookState hookState() {
            return new HookState();
        }

        @Bean
        RebuildProgressListener rebuildProgressListener(HookState state) {
            return (jobId, watermark) -> {
                if (state.afterStartWatermark != null) {
                    state.afterStartWatermark.run();
                    state.afterStartWatermark = null;
                }
            };
        }
    }
}
