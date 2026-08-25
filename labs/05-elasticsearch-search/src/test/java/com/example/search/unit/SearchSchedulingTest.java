package com.example.search.unit;

import com.example.search.application.maintenance.SearchIndexBootstrap;
import com.example.search.application.maintenance.SearchRebuildRecovery;
import com.example.search.application.sync.OutboxDispatcher;
import com.example.search.config.SearchSchedulingConfiguration;
import com.example.search.observability.OutboxScheduler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import org.mockito.InOrder;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SearchSchedulingTest {
    @Test
    void startupRecoversBeforeBootstrapAndOnlyThenOpensSchedulerGate() throws Exception {
        SearchRebuildRecovery recovery = mock(SearchRebuildRecovery.class);
        SearchIndexBootstrap bootstrap = mock(SearchIndexBootstrap.class);
        AtomicBoolean ready = new AtomicBoolean();

        new SearchSchedulingConfiguration().searchStartupRunner(recovery, bootstrap, ready).run(null);

        InOrder ordered = inOrder(recovery, bootstrap);
        ordered.verify(recovery).recoverInterruptedCutover();
        ordered.verify(bootstrap).ensureInitialized();
        org.assertj.core.api.Assertions.assertThat(ready).isTrue();
    }

    @Test
    void schedulerDoesNotClaimBeforeStartupAndDispatchesOnlyOncePerTick() {
        OutboxDispatcher dispatcher = mock(OutboxDispatcher.class);
        AtomicBoolean ready = new AtomicBoolean();
        OutboxScheduler scheduler = new OutboxScheduler(dispatcher, ready);

        scheduler.dispatch();
        verify(dispatcher, never()).dispatchOnce();

        ready.set(true);
        scheduler.dispatch();
        verify(dispatcher).dispatchOnce();
    }
}
