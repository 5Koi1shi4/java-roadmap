package com.example.campusmarket.unit.catalog;

import com.example.campusmarket.catalog.search.ProductEventPublisher;
import com.example.campusmarket.catalog.search.ProductReplayBootstrapScheduler;
import com.example.campusmarket.catalog.search.ProductReplayService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductReplayBootstrapSchedulerTest {
    @Test
    void idleClaimWithoutConfirmedCompletionMustRetryNextScheduledRound() {
        ProductReplayService replay = mock(ProductReplayService.class);
        ProductEventPublisher publisher = mock(ProductEventPublisher.class);
        when(publisher.brokerHealthy()).thenReturn(true);
        when(replay.replayBatch(1000)).thenReturn(
                new ProductReplayService.ReplayBatchResult(0, false),
                new ProductReplayService.ReplayBatchResult(0, true));

        ProductReplayBootstrapScheduler scheduler =
                new ProductReplayBootstrapScheduler(replay, publisher);
        scheduler.bootstrap();
        scheduler.bootstrap();
        scheduler.bootstrap();

        verify(replay, times(2)).replayBatch(1000);
    }
}
