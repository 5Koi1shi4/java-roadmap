package com.example.search.integration;

import com.example.search.application.maintenance.SearchRebuildRecovery;
import com.example.search.infrastructure.elasticsearch.ElasticsearchIndexManager;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RebuildRecoveryIT extends SharedSearchContainers {
    @Autowired SearchRebuildRecovery recovery;
    @Autowired JdbcTemplate jdbc;
    @Autowired ElasticsearchIndexManager indexes;
    @Autowired ElasticsearchClient client;

    @Test
    void bothAliasesTargetCompletesAndUnpauses() throws Exception {
        UUID job = seedCutoverJob();
        String target = jdbc.queryForObject("SELECT target_index FROM search_rebuild_job WHERE job_id=?", String.class, job.toString());
        indexes.createPhysicalIndex(job);
        setAliases(target, target);

        recovery.recoverInterruptedCutover();

        assertThat(jdbc.queryForObject("SELECT status FROM search_rebuild_job WHERE job_id=?", String.class, job.toString())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT dispatcher_paused FROM search_coordination WHERE id=1", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT active_rebuild_id FROM search_coordination WHERE id=1", String.class)).isNull();
    }

    @Test
    void bothOldAliasesFailAndUnpause() throws Exception {
        UUID job = seedCutoverJob();
        setAliases("products-vbootstrap", "products-vbootstrap");

        recovery.recoverInterruptedCutover();

        assertThat(jdbc.queryForObject("SELECT status FROM search_rebuild_job WHERE job_id=?", String.class, job.toString())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT dispatcher_paused FROM search_coordination WHERE id=1", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT active_rebuild_id FROM search_coordination WHERE id=1", String.class)).isNull();
    }

    @Test
    void splitAliasesFailButRemainPausedAndActive() throws Exception {
        UUID job = seedCutoverJob();
        indexes.createPhysicalIndex(job);
        String target = jdbc.queryForObject("SELECT target_index FROM search_rebuild_job WHERE job_id=?", String.class, job.toString());
        setAliases("products-vbootstrap", target);

        recovery.recoverInterruptedCutover();
        recovery.recoverInterruptedCutover();

        assertThat(jdbc.queryForObject("SELECT status FROM search_rebuild_job WHERE job_id=?", String.class, job.toString())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT dispatcher_paused FROM search_coordination WHERE id=1", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT active_rebuild_id FROM search_coordination WHERE id=1", String.class)).isEqualTo(job.toString());
    }

    private UUID seedCutoverJob() {
        UUID job = UUID.randomUUID();
        jdbc.update("INSERT INTO search_rebuild_job(job_id,target_index,status,phase,owner,lease_until,created_at) "
                        + "VALUES (?,?, 'RUNNING','CUTOVER','recovery-it',TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)),UTC_TIMESTAMP(6))",
                job.toString(), indexes.physicalIndexName(job));
        jdbc.update("UPDATE search_coordination SET dispatcher_paused=TRUE, active_rebuild_id=? WHERE id=1", job.toString());
        return job;
    }

    private void setAliases(String read, String write) throws Exception {
        var current = indexes.aliasTargets();
        var actions = new java.util.ArrayList<Action>();
        if (current.read() != null) actions.add(Action.of(a -> a.remove(r -> r.index(current.read()).alias("products-read"))));
        if (current.write() != null) actions.add(Action.of(a -> a.remove(r -> r.index(current.write()).alias("products-write"))));
        actions.add(Action.of(a -> a.add(x -> x.index(read).alias("products-read"))));
        actions.add(Action.of(a -> a.add(x -> x.index(write).alias("products-write"))));
        client.indices().updateAliases(u -> u.actions(actions));
    }
}
