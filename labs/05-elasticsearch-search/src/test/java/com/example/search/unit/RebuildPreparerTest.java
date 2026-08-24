package com.example.search.unit;

import com.example.search.application.maintenance.RebuildJob;
import com.example.search.application.maintenance.RebuildJobRepository;
import com.example.search.application.maintenance.RebuildLeaseLostException;
import com.example.search.application.maintenance.RebuildPhase;
import com.example.search.application.maintenance.RebuildProgressListener;
import com.example.search.application.maintenance.RebuildStatus;
import com.example.search.application.maintenance.SearchRebuildPreparer;
import com.example.search.application.product.ProductRepository;
import com.example.search.application.sync.SearchCoordinationRepository;
import com.example.search.application.sync.SearchIndexWriter;
import com.example.search.application.sync.SearchOutboxRepository;
import com.example.search.infrastructure.elasticsearch.ElasticsearchIndexManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RebuildPreparerTest {
    @Test
    void doesNotResurrectPendingJobAfterAnotherOwnerTakesActiveSlot() {
        Coordination coordination = new Coordination();
        RebuildJobRepository jobs = mock(RebuildJobRepository.class);
        ProductRepository products = mock(ProductRepository.class);
        SearchOutboxRepository outbox = mock(SearchOutboxRepository.class);
        SearchIndexWriter writer = mock(SearchIndexWriter.class);
        ElasticsearchIndexManager indexes = mock(ElasticsearchIndexManager.class);
        UUID oldJob = UUID.randomUUID();
        UUID replacement = UUID.randomUUID();
        RebuildJob pending = new RebuildJob(oldJob, "products-vold", RebuildStatus.PENDING, RebuildPhase.CREATED,
                "owner", Instant.now().plusSeconds(30), null, null, 0, 0, null, Instant.now(), null);
        when(jobs.findForUpdate(any())).thenReturn(Optional.of(pending));
        when(jobs.markRunning(any(), eq("owner"), any())).thenReturn(false);
        when(jobs.markFailed(any(), eq("owner"), anyString())).thenReturn(true);
        when(indexes.physicalIndexName(any())).thenAnswer(invocation -> "products-v" + invocation.getArgument(0));
        when(indexes.createPhysicalIndex(any())).thenAnswer(invocation -> {
            coordination.setActiveRebuildId(replacement);
            return "products-v" + invocation.getArgument(0);
        });

        SearchRebuildPreparer preparer = new SearchRebuildPreparer(coordination, jobs, products, outbox,
                writer, indexes, RebuildProgressListener.NOOP);

        assertThatThrownBy(() -> preparer.start("owner"))
                .isInstanceOf(RebuildLeaseLostException.class);
        assertThat(coordination.activeRebuildId()).contains(replacement);
        verify(jobs).markFailed(any(), eq("owner"), anyString());
        verify(jobs, never()).markRunning(any(), eq("owner"), any());
    }

    private static final class Coordination implements SearchCoordinationRepository {
        private UUID active;

        @Override public void lockShared() { }
        @Override public void lockExclusive() { }
        @Override public Optional<UUID> activeRebuildId() { return Optional.ofNullable(active); }
        @Override public void setActiveRebuildId(UUID jobId) { active = jobId; }
        @Override public boolean clearActiveRebuildId(UUID jobId) {
            if (!jobId.equals(active)) return false;
            active = null;
            return true;
        }
    }
}
