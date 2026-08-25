package com.example.search.unit;

import com.example.search.application.maintenance.AliasTargets;
import com.example.search.application.maintenance.PreparedRebuild;
import com.example.search.application.maintenance.RebuildJob;
import com.example.search.application.maintenance.RebuildJobRepository;
import com.example.search.application.maintenance.RebuildPhase;
import com.example.search.application.maintenance.RebuildStatus;
import com.example.search.application.maintenance.RebuildValidationException;
import com.example.search.application.maintenance.SearchRebuildCutover;
import com.example.search.application.maintenance.SearchRebuildPreparer;
import com.example.search.application.maintenance.SearchRebuildRunner;
import com.example.search.application.sync.SearchCoordinationRepository;
import com.example.search.application.sync.SearchSyncMetrics;
import com.example.search.infrastructure.elasticsearch.ElasticsearchIndexManager;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchRebuildRunnerMetricsTest {
    @Test
    void recordsValidationDifferenceCountOnFailedRebuild() {
        SearchRebuildPreparer preparer = mock(SearchRebuildPreparer.class);
        SearchRebuildCutover cutover = mock(SearchRebuildCutover.class);
        SearchCoordinationRepository coordination = mock(SearchCoordinationRepository.class);
        RebuildJobRepository jobs = mock(RebuildJobRepository.class);
        ElasticsearchIndexManager indexes = mock(ElasticsearchIndexManager.class);
        SearchSyncMetrics metrics = mock(SearchSyncMetrics.class);
        UUID jobId = UUID.randomUUID();
        String target = "products-vtarget";
        RebuildJob job = new RebuildJob(jobId, target, RebuildStatus.RUNNING, RebuildPhase.CATCH_UP,
                "metrics-owner", Instant.now().plusSeconds(30), 1L, 1L, 1, 0, null,
                Instant.now(), null, "products-vold");
        PreparedRebuild prepared = new PreparedRebuild(jobId, target, 1, 1, 1);
        when(jobs.find(jobId)).thenReturn(Optional.of(job));
        when(preparer.prepare(jobId)).thenReturn(prepared);
        when(cutover.cutover(prepared)).thenThrow(new RebuildValidationException("nine differences", 9));
        when(indexes.aliasTargets()).thenReturn(new AliasTargets("products-vold", "products-vold"));

        assertThatThrownBy(() -> new SearchRebuildRunner(
                preparer, cutover, coordination, jobs, indexes, null, metrics).run(jobId))
                .isInstanceOf(RebuildValidationException.class);

        verify(metrics).recordRebuild(eq(false), any(Duration.class), eq(9L));
    }
}
