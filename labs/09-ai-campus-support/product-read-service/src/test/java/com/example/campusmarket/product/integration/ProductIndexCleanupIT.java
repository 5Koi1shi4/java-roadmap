package com.example.campusmarket.product.integration;

import com.example.campusmarket.product.infrastructure.ProductIndexCleanupRepository;
import com.example.campusmarket.product.infrastructure.ProductRebuildGateRepository;
import com.example.campusmarket.product.search.ElasticsearchProductSearch;
import com.example.campusmarket.product.search.ProductIndexCleanupScheduler;
import com.example.campusmarket.product.search.ProductIndexCleanupWorker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 旧代清理在门禁推进后可执行，并由独立调度器按配置触发。 */
class ProductIndexCleanupIT {

    @Test
    void oldGenerationTaskIsDeletedAfterGateAdvances() {
        ProductIndexCleanupRepository cleanup = mock(ProductIndexCleanupRepository.class);
        ProductRebuildGateRepository gate = mock(ProductRebuildGateRepository.class);
        ElasticsearchProductSearch search = mock(ElasticsearchProductSearch.class);
        ProductIndexCleanupRepository.Claim claim = new ProductIndexCleanupRepository.Claim(
                "task-1", "campus-product-rebuild-old", 1, "cleanup-owner", "token-1",
                Instant.now().plusSeconds(30), 1);
        when(gate.isOpenForIndexing()).thenReturn(true);
        when(gate.isOpenForGeneration(1)).thenReturn(true);
        when(cleanup.claimBatch("cleanup-owner", 10, Duration.ofSeconds(30)))
                .thenReturn(List.of(claim));
        when(search.readAllAliasMembers()).thenReturn(Set.of(), Set.of());
        when(cleanup.markDone(claim)).thenReturn(true);

        ProductIndexCleanupWorker worker = new ProductIndexCleanupWorker(cleanup, gate, search);

        assertThat(worker.cleanupOnce("cleanup-owner", 10, Duration.ofSeconds(30))).isEqualTo(1);
        verify(search).deleteIndex("campus-product-rebuild-old");
        verify(cleanup).markDone(claim);
    }

    @Test
    void schedulerPassesOwnerLeaseAndBatchToWorker() {
        ProductIndexCleanupWorker worker = mock(ProductIndexCleanupWorker.class);
        ProductIndexCleanupScheduler scheduler = new ProductIndexCleanupScheduler(
                worker, "cleanup-owner", 17, Duration.ofSeconds(25), true);

        scheduler.cleanup();

        verify(worker).cleanupOnce("cleanup-owner", 17, Duration.ofSeconds(25));
    }

    @Test
    void disabledSchedulerDoesNotClaimCleanupTasks() {
        ProductIndexCleanupWorker worker = mock(ProductIndexCleanupWorker.class);
        ProductIndexCleanupScheduler scheduler = new ProductIndexCleanupScheduler(
                worker, "cleanup-owner", 10, Duration.ofSeconds(30), false);

        scheduler.cleanup();

        verifyNoInteractions(worker);
    }

    @Test
    void schedulerKeepsRunningWhenWorkerTemporarilyFails() {
        ProductIndexCleanupWorker worker = mock(ProductIndexCleanupWorker.class);
        doThrow(new RuntimeException("ES unavailable"))
                .when(worker).cleanupOnce(any(), eq(10), eq(Duration.ofSeconds(30)));
        ProductIndexCleanupScheduler scheduler = new ProductIndexCleanupScheduler(
                worker, "cleanup-owner", 10, Duration.ofSeconds(30), true);

        scheduler.cleanup();

        verify(worker).cleanupOnce("cleanup-owner", 10, Duration.ofSeconds(30));
    }
}
