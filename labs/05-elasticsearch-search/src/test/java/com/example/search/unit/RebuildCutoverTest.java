package com.example.search.unit;

import com.example.search.application.maintenance.AliasTargets;
import com.example.search.application.maintenance.PreparedRebuild;
import com.example.search.application.maintenance.RebuildJob;
import com.example.search.application.maintenance.RebuildJobRepository;
import com.example.search.application.maintenance.RebuildPhase;
import com.example.search.application.maintenance.RebuildStatus;
import com.example.search.application.maintenance.RebuildValidation;
import com.example.search.application.maintenance.RebuildValidator;
import com.example.search.application.maintenance.SearchRebuildCutover;
import com.example.search.application.sync.SearchCoordinationRepository;
import com.example.search.application.sync.SearchIndexWriter;
import com.example.search.application.sync.SearchOutboxRepository;
import com.example.search.infrastructure.elasticsearch.ElasticsearchIndexManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RebuildCutoverTest {
    @Test
    void aliasAttemptFollowedByDatabaseFailureKeepsRecoverySignals() {
        SearchCoordinationRepository coordination = mock(SearchCoordinationRepository.class);
        RebuildJobRepository jobs = mock(RebuildJobRepository.class);
        SearchOutboxRepository outbox = mock(SearchOutboxRepository.class);
        SearchIndexWriter writer = mock(SearchIndexWriter.class);
        ElasticsearchIndexManager indexes = mock(ElasticsearchIndexManager.class);
        RebuildValidator validator = mock(RebuildValidator.class);
        SearchRebuildCutover cutover = new SearchRebuildCutover(coordination, jobs, outbox, writer, indexes, validator);
        UUID jobId = UUID.randomUUID();
        String owner = "cutover-unit";
        String target = "products-vtarget";
        RebuildJob running = new RebuildJob(jobId, target, RebuildStatus.RUNNING, RebuildPhase.CUTOVER,
                owner, Instant.now().plusSeconds(30), 10L, 10L, 1, 0, null,
                Instant.now(), null, "products-vold");

        when(jobs.find(jobId)).thenReturn(Optional.of(running));
        when(outbox.hasUnexpiredProcessing()).thenReturn(false);
        when(indexes.aliasTargets()).thenReturn(new AliasTargets("products-vold", "products-vold"));
        when(jobs.markCutover(jobId, owner, "products-vold")).thenReturn(true);
        when(jobs.fenceCutover(jobId, owner)).thenReturn(true);
        when(coordination.activeRebuildId()).thenReturn(Optional.of(jobId));
        when(jobs.findForUpdate(jobId)).thenReturn(Optional.of(running));
        when(outbox.highWatermark()).thenReturn(10L);
        when(validator.validate(target, 10L)).thenReturn(RebuildValidation.consistent(List.of()));
        when(jobs.markCompleted(jobId, owner, 10L, 0L)).thenReturn(false);

        assertThatThrownBy(() -> cutover.cutover(new PreparedRebuild(jobId, target, 10, 10, 1)))
                .isInstanceOf(IllegalStateException.class);

        verify(indexes).swapReadWriteAliases("products-vold", target);
        verify(jobs, never()).markFailed(jobId, owner, "could not finalize rebuild job");
        verify(coordination, never()).clearActiveRebuildId(jobId, owner);
        verify(coordination, never()).setDispatcherPaused(false);
        verify(coordination, atLeastOnce()).setDispatcherPaused(true);
    }
}
