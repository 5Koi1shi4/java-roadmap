package com.example.search.unit;

import com.example.search.application.maintenance.RebuildJob;
import com.example.search.application.maintenance.RebuildJobRepository;
import com.example.search.application.maintenance.RebuildLeaseLostException;
import com.example.search.application.maintenance.RebuildPhase;
import com.example.search.application.maintenance.RebuildProgressListener;
import com.example.search.application.maintenance.RebuildStatus;
import com.example.search.application.maintenance.PreparedRebuild;
import com.example.search.application.maintenance.SearchRebuildPreparer;
import com.example.search.application.product.ProductRepository;
import com.example.search.application.sync.SearchCoordinationRepository;
import com.example.search.application.sync.SearchIndexWriter;
import com.example.search.application.sync.SearchOutboxRepository;
import com.example.search.application.sync.IndexMutation;
import com.example.search.application.sync.IndexWriteResult;
import com.example.search.application.sync.OutboxEventType;
import com.example.search.application.sync.SearchOutboxEvent;
import com.example.search.domain.Product;
import com.example.search.domain.ProductDetails;
import com.example.search.domain.ProductSearchSnapshot;
import com.example.search.domain.ProductStatus;
import com.example.search.infrastructure.elasticsearch.ElasticsearchIndexManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.inOrder;

class RebuildPreparerTest {
    @Test
    void snapshotsAllKeysetPagesAndRenewsBeforeEachWrite() {
        Fixture fixture = new Fixture();
        List<Product> first = products(1, 500);
        List<Product> second = products(501, 1);
        when(fixture.products.findPageAfter(0, 500)).thenReturn(first);
        when(fixture.products.findPageAfter(500, 500)).thenReturn(second);
        when(fixture.products.findPageAfter(501, 500)).thenReturn(List.of());
        when(fixture.outbox.highWatermark()).thenReturn(0L, 0L);
        List<String> events = new ArrayList<>();
        when(fixture.jobs.renewLease(eq(fixture.jobId), eq(fixture.owner), any())).thenAnswer(invocation -> {
            events.add("renew");
            return true;
        });
        stubRecordingAppliedWrites(fixture.writer, events);

        fixture.preparer.prepare(fixture.jobId);

        assertThat(events.subList(0, 6)).containsExactly("renew", "renew", "write:500", "renew", "renew", "write:1");
        verify(fixture.products).findPageAfter(0, 500);
        verify(fixture.products).findPageAfter(500, 500);
        verify(fixture.products).findPageAfter(501, 500);
    }

    @Test
    void replaysOnlyBoundedCatchUpPagesAndCountsImportedDelta() {
        Fixture fixture = new Fixture();
        when(fixture.products.findPageAfter(0, 500)).thenReturn(List.of());
        when(fixture.outbox.highWatermark()).thenReturn(10L, 1010L);
        List<SearchOutboxEvent> first = events(11, 500);
        List<SearchOutboxEvent> second = events(511, 500);
        when(fixture.outbox.eventsBetween(10, 1010, 500)).thenReturn(first);
        when(fixture.outbox.eventsBetween(510, 1010, 500)).thenReturn(second);
        stubAppliedWrites(fixture.writer);
        AtomicLong imported = new AtomicLong();
        when(fixture.jobs.find(fixture.jobId)).thenAnswer(invocation -> Optional.of(fixture.jobWithCount(imported.get())));
        when(fixture.jobs.addImportedCount(eq(fixture.jobId), eq(fixture.owner), anyLong())).thenAnswer(invocation -> {
            imported.addAndGet(invocation.getArgument(2, Long.class));
            return true;
        });

        PreparedRebuild prepared = fixture.preparer.prepare(fixture.jobId);

        assertThat(prepared.importedCount()).isEqualTo(1000);
        verify(fixture.outbox).eventsBetween(10, 1010, 500);
        verify(fixture.outbox).eventsBetween(510, 1010, 500);
        verify(fixture.writer, times(2)).bulkWrite(eq(fixture.target), argThat(m -> m.size() == 500));
        verify(fixture.jobs, times(2)).addImportedCount(fixture.jobId, fixture.owner, 500);
    }

    @Test
    void leaseLossBeforeSnapshotPageDoesNotCallWriterAndMarksFailedInCoordinationOrder() {
        Fixture fixture = new Fixture();
        when(fixture.products.findPageAfter(0, 500)).thenReturn(List.of(product(1)));
        when(fixture.outbox.highWatermark()).thenReturn(0L, 0L);
        stubAppliedWrites(fixture.writer);
        when(fixture.jobs.renewLease(eq(fixture.jobId), eq(fixture.owner), any()))
                .thenReturn(true, false);

        assertThatThrownBy(() -> fixture.preparer.prepare(fixture.jobId))
                .isInstanceOf(RebuildLeaseLostException.class);

        verify(fixture.writer, never()).bulkWrite(anyString(), any());
        InOrder order = inOrder(fixture.coordination, fixture.jobs);
        order.verify(fixture.coordination).lockExclusive();
        order.verify(fixture.jobs).markFailed(eq(fixture.jobId), eq(fixture.owner), anyString());
    }

    @Test
    void leaseLossBeforeCatchUpPageDoesNotCallWriterAndMarksFailed() {
        Fixture fixture = new Fixture();
        when(fixture.products.findPageAfter(0, 500)).thenReturn(List.of());
        when(fixture.outbox.highWatermark()).thenReturn(10L, 11L);
        when(fixture.outbox.eventsBetween(10, 11, 500)).thenReturn(events(11, 1));
        stubAppliedWrites(fixture.writer);
        when(fixture.jobs.renewLease(eq(fixture.jobId), eq(fixture.owner), any()))
                .thenReturn(true, true, false);

        assertThatThrownBy(() -> fixture.preparer.prepare(fixture.jobId))
                .isInstanceOf(RebuildLeaseLostException.class);

        verify(fixture.writer, never()).bulkWrite(anyString(), any());
        verify(fixture.jobs, never()).addImportedCount(any(), anyString(), anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "retryable", "permanent"})
    void writerFailureStopsBeforeNextPageAndMarksFailed(String failure) {
        Fixture fixture = new Fixture();
        when(fixture.products.findPageAfter(0, 500)).thenReturn(List.of(product(1)));
        when(fixture.products.findPageAfter(1, 500)).thenReturn(List.of(product(2)));
        when(fixture.outbox.highWatermark()).thenReturn(0L, 0L);
        IndexWriteResult result = switch (failure) {
            case "retryable" -> new IndexWriteResult(1, 1, IndexWriteResult.Outcome.RETRYABLE_FAILURE);
            case "permanent" -> new IndexWriteResult(1, 1, IndexWriteResult.Outcome.PERMANENT_FAILURE);
            default -> null;
        };
        when(fixture.writer.bulkWrite(anyString(), any())).thenReturn(
                "missing".equals(failure) ? List.of() : List.of(result));

        assertThatThrownBy(() -> fixture.preparer.prepare(fixture.jobId))
                .isInstanceOf(IllegalStateException.class);

        verify(fixture.writer).bulkWrite(eq(fixture.target), any());
        verify(fixture.products, never()).findPageAfter(1, 500);
        verify(fixture.jobs).markFailed(eq(fixture.jobId), eq(fixture.owner), anyString());
    }

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

    private static final class Fixture {
        private final SearchCoordinationRepository coordination = mock(SearchCoordinationRepository.class);
        private final RebuildJobRepository jobs = mock(RebuildJobRepository.class);
        private final ProductRepository products = mock(ProductRepository.class);
        private final SearchOutboxRepository outbox = mock(SearchOutboxRepository.class);
        private final SearchIndexWriter writer = mock(SearchIndexWriter.class);
        private final ElasticsearchIndexManager indexes = mock(ElasticsearchIndexManager.class);
        private final UUID jobId = UUID.randomUUID();
        private final String owner = "rebuild-owner";
        private final String target = "products-vtarget";
        private final RebuildJob job = new RebuildJob(jobId, target, RebuildStatus.RUNNING, RebuildPhase.SNAPSHOT,
                owner, Instant.now().plusSeconds(30), null, null, 0, 0, null, Instant.now(), null);
        private final SearchRebuildPreparer preparer;

        private Fixture() {
            when(jobs.find(jobId)).thenReturn(Optional.of(job));
            when(jobs.setStartWatermark(eq(jobId), eq(owner), anyLong())).thenReturn(true);
            when(jobs.setCatchUp(eq(jobId), eq(owner), anyLong(), anyLong())).thenReturn(true);
            when(jobs.addImportedCount(eq(jobId), eq(owner), anyLong())).thenReturn(true);
            when(jobs.renewLease(eq(jobId), eq(owner), any())).thenReturn(true);
            when(jobs.markFailed(eq(jobId), eq(owner), anyString())).thenReturn(true);
            when(jobs.find(jobId)).thenReturn(Optional.of(job));
            preparer = new SearchRebuildPreparer(coordination, jobs, products, outbox, writer, indexes,
                    RebuildProgressListener.NOOP);
        }

        private RebuildJob jobWithCount(long count) {
            return new RebuildJob(jobId, target, RebuildStatus.RUNNING, RebuildPhase.CATCH_UP,
                    owner, Instant.now().plusSeconds(30), 0L, 1010L, count, 0, null, job.createdAt(), null);
        }
    }

    private static void stubAppliedWrites(SearchIndexWriter writer) {
        when(writer.bulkWrite(anyString(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked") List<IndexMutation> mutations = invocation.getArgument(1);
            return mutations.stream().map(m -> new IndexWriteResult(m.productId(), m.sourceVersion(),
                    IndexWriteResult.Outcome.APPLIED)).toList();
        });
    }

    private static void stubRecordingAppliedWrites(SearchIndexWriter writer, List<String> events) {
        when(writer.bulkWrite(anyString(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked") List<IndexMutation> mutations = invocation.getArgument(1);
            events.add("write:" + mutations.size());
            return mutations.stream().map(m -> new IndexWriteResult(m.productId(), m.sourceVersion(),
                    IndexWriteResult.Outcome.APPLIED)).toList();
        });
    }

    private static List<Product> products(int firstId, int count) {
        List<Product> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(product(firstId + i));
        return result;
    }

    private static Product product(long id) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new Product(id, new ProductDetails("商品" + id, null, "描述", "BOOK", "图书",
                new BigDecimal("10.00"), ProductStatus.ON_SALE), 1, now, now);
    }

    private static List<SearchOutboxEvent> events(long firstId, int count) {
        List<SearchOutboxEvent> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long id = firstId + i;
            ProductSearchSnapshot snapshot = ProductSearchSnapshot.from(product(id));
            result.add(new SearchOutboxEvent(id, UUID.randomUUID(), id, 1, OutboxEventType.PRODUCT_UPSERT,
                    snapshot, 0));
        }
        return result;
    }
}
