package com.example.campusmarket.integration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.example.campusmarket.catalog.search.ProductSearchPort;
import com.example.campusmarket.catalog.search.ElasticsearchProductSearch;
import com.example.campusmarket.catalog.search.SearchRebuildService;
import com.example.campusmarket.catalog.search.SearchProjector;
import com.example.campusmarket.catalog.search.SearchOutboxDispatcher;
import com.example.campusmarket.catalog.search.SearchOutboxRepository;
import com.example.campusmarket.catalog.search.SearchGateRepository;
import com.example.campusmarket.catalog.search.SearchAliasCoordinator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ActiveProfiles("local")
class SearchRebuildIT extends SharedContainers {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ProductSearchPort search;
    @Autowired SearchProjector projector;
    @Autowired SearchRebuildService rebuild;
    @Autowired SearchOutboxDispatcher dispatcher;
    @Autowired SearchOutboxRepository searchOutbox;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ElasticsearchProductSearch elasticsearch;
    @Autowired ElasticsearchClient elasticsearchClient;

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
    }

    @BeforeEach
    void fixture() {
        jdbc.update("UPDATE search_rebuild_gate SET mode='OPEN',owner_id=NULL,claim_token=NULL,lease_until=NULL WHERE id=1");
        jdbc.update("DELETE FROM inventory_movement");
        jdbc.update("DELETE FROM payment_callback_event");
        jdbc.update("DELETE FROM refund_order");
        jdbc.update("DELETE FROM settlement");
        jdbc.update("DELETE FROM payment_order");
        jdbc.update("DELETE FROM order_deadline_claim");
        jdbc.update("DELETE FROM order_transition");
        jdbc.update("DELETE FROM order_command");
        jdbc.update("DELETE FROM trade_review");
        jdbc.update("DELETE FROM handoff_record");
        jdbc.update("DELETE FROM dispute_evidence");
        jdbc.update("DELETE FROM return_case");
        jdbc.update("DELETE FROM seller_obligation");
        jdbc.update("DELETE FROM warranty_case");
        jdbc.update("DELETE FROM dispute_case");
        jdbc.update("DELETE FROM trade_order");
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM object_upload_session");
        jdbc.update("DELETE FROM email_verification");
        jdbc.update("DELETE FROM external_identity");
        jdbc.update("DELETE FROM listing_media");
        jdbc.update("DELETE FROM search_outbox");
        jdbc.update("DELETE FROM search_rebuild_intent");
        jdbc.update("DELETE FROM search_index_cleanup_task");
        jdbc.update("DELETE FROM listing");
        jdbc.update("DELETE FROM campus_user");
        UUID seller = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_user(id,email,password_hash,status,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            seller.toString(), seller + "@stu.example.edu.cn", "hash");
        UUID listing = UUID.randomUUID();
        jdbc.update("INSERT INTO listing(id,seller_id,title,description,category,unit_price_fen,available_quantity,quarantined_quantity,warranty_days,warranty_scope,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,0,NULL,NULL,'ON_SALE',?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            listing.toString(), seller.toString(), "重建并发商品", "在线重建", "教材", 2000L, 4, 1L);
        jdbc.update("INSERT INTO search_outbox(id,listing_id,aggregate_version,event_type,payload,status,attempt_count,available_at,created_at) VALUES (?,?,?,'LISTING_UPDATED',CAST('{}' AS JSON),'NEW',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            UUID.randomUUID().toString(), listing.toString(), 1L);
    }

    @Test
    void expiredLeaseCanBeTakenOverAndStaleOwnerCannotReleaseOrRenew() {
        // Each repository obtains an independent pooled JDBC connection; the
        // assertions below therefore cover committed heartbeat visibility,
        // takeover, and stale-owner fencing rather than thread-local state.
        SearchGateRepository firstCoordinator = new SearchGateRepository(new JdbcTemplate(dataSource));
        SearchGateRepository secondCoordinator = new SearchGateRepository(new JdbcTemplate(dataSource));
        SearchGateRepository.Lease old = firstCoordinator.acquire("rebuild-old", java.time.Duration.ofMinutes(1));
        assertThat(secondCoordinator.renew(old, java.time.Duration.ofMinutes(1))).isTrue();
        jdbc.update("UPDATE search_rebuild_gate SET lease_until=TIMESTAMPADD(MICROSECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=1");

        SearchGateRepository.Lease current = secondCoordinator.acquire("rebuild-current", java.time.Duration.ofMinutes(1));
        assertThat(firstCoordinator.release(old)).isZero();

        assertThat(jdbc.queryForObject("SELECT owner_id FROM search_rebuild_gate WHERE id=1", String.class))
            .isEqualTo("rebuild-current");
        assertThat(firstCoordinator.renew(old, java.time.Duration.ofMinutes(1))).isFalse();
        assertThat(secondCoordinator.renew(current, java.time.Duration.ofMinutes(1))).isTrue();
        secondCoordinator.release(current);
        assertThat(jdbc.queryForObject("SELECT mode FROM search_rebuild_gate WHERE id=1", String.class)).isEqualTo("OPEN");
    }

    @Test
    void aliasCoordinatorSerializesWorkers() throws Exception {
        SearchAliasCoordinator first = new SearchAliasCoordinator(dataSource);
        SearchAliasCoordinator second = new SearchAliasCoordinator(dataSource);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch waiterEntered = new CountDownLatch(1);
        CountDownLatch waiterCallback = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Void> owner = workers.submit(() -> first.execute(Duration.ofSeconds(5), connection -> {
                locked.countDown();
                awaitBarrier(locked, release);
                return null;
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
            Future<Integer> waiter = workers.submit(() -> {
                waiterEntered.countDown();
                return second.execute(Duration.ofSeconds(5), connection -> {
                    waiterCallback.countDown();
                    return queryInt(connection, "SELECT 1");
                });
            });
            assertThat(waiterEntered.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(waiter.isDone()).isFalse();
            assertThat(waiterCallback.await(200, TimeUnit.MILLISECONDS)).isFalse();
            release.countDown();
            owner.get(30, TimeUnit.SECONDS);
            assertThat(waiter.get(30, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(waiterCallback.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void aliasCoordinatorWorksWithPoolSizeOne() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(MYSQL.getJdbcUrl());
        config.setUsername(MYSQL.getUsername());
        config.setPassword(MYSQL.getPassword());
        config.setMaximumPoolSize(1);
        try (HikariDataSource oneConnection = new HikariDataSource(config)) {
            SearchAliasCoordinator coordinator = new SearchAliasCoordinator(oneConnection);
            assertThat(coordinator.<Integer>execute(Duration.ofSeconds(5), connection -> queryInt(connection, "SELECT 1")))
                .isEqualTo(1);
        }
    }

    @Test
    void dispatchesEnqueuedChangeThroughClaimProjectAndPublish() {
        assertThat(dispatcher.dispatchOnce(10)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM search_outbox LIMIT 1", String.class)).isEqualTo("PUBLISHED");
    }

    @Test
    void gateClosedDefersWithoutConsumingDeliveryAttempt() {
        SearchGateRepository coordinator = new SearchGateRepository(jdbc);
        SearchGateRepository.Lease lease = coordinator.acquire("rebuild-barrier", java.time.Duration.ofMinutes(1));
        assertThat(dispatcher.dispatchOnce(10)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM search_outbox LIMIT 1", String.class)).isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM search_outbox LIMIT 1", Integer.class)).isZero();
        coordinator.release(lease);
    }

    @Test
    void outboxInsertRollsBackWithItsFactTransaction() {
        UUID listing = UUID.fromString(jdbc.queryForObject("SELECT id FROM listing LIMIT 1", String.class));
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM search_outbox WHERE listing_id=?", Integer.class, listing.toString());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            searchOutbox.enqueue(listing, 1L, "LISTING_UPDATED");
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM search_outbox WHERE listing_id=?", Integer.class, listing.toString()))
            .isEqualTo(before);
    }

    @Test
    void rebuildCreateFailureKeepsOldAliasAndReconcilesAfterProxyRecovery() {
        search.refresh();
        String previous = elasticsearch.currentReadIndex();
        try {
            ELASTICSEARCH_PROXY.setConnectionCut(true);
            assertThrows(RuntimeException.class, rebuild::rebuild);
            assertThat(jdbc.queryForObject("SELECT mode FROM search_rebuild_gate WHERE id=1", String.class)).isEqualTo("OPEN");
        } finally {
            ELASTICSEARCH_PROXY.setConnectionCut(false);
        }
        assertThat(elasticsearch.currentReadIndex()).isEqualTo(previous);
        String failedTarget = jdbc.queryForObject("SELECT target_index FROM search_rebuild_intent WHERE phase='CREATED' LIMIT 1", String.class);
        SearchRebuildService.RebuildReport recovered = rebuild.rebuild();
        elasticsearch.cleanupPending();
        search.refresh();
        assertThat(recovered.index()).isNotBlank();
        assertThat(elasticsearch.currentReadIndex()).isNotEqualTo(previous);
        assertThat(jdbc.queryForObject("SELECT phase FROM search_rebuild_intent WHERE target_index=?", String.class, failedTarget))
            .isEqualTo("RECONCILED");
    }

    @Test
    void switchesAliasesAtomicallyAndThreeRunsDoNotRegressVersions() {
        SearchRebuildService.RebuildReport first = rebuild.rebuild();
        search.refresh();
        ProductSearchPort.SearchPage firstPage = search.search(new ProductSearchPort.SearchRequest("重建", null, null, null, 0, 20));
        SearchRebuildService.RebuildReport second = rebuild.rebuild();
        SearchRebuildService.RebuildReport third = rebuild.rebuild();
        search.refresh();
        ProductSearchPort.SearchPage finalPage = search.search(new ProductSearchPort.SearchRequest("重建", null, null, null, 0, 20));

        assertThat(first.index()).isNotEqualTo(second.index()).isNotEqualTo(third.index());
        assertThat(firstPage.items()).hasSize(1);
        assertThat(finalPage.items()).extracting(ProductSearchPort.SearchItem::listingId)
            .containsExactlyElementsOf(firstPage.items().stream().map(ProductSearchPort.SearchItem::listingId).toList());
        assertThat(finalPage.items()).extracting(ProductSearchPort.SearchItem::aggregateVersion)
            .containsExactly(1L);
        assertThat(jdbc.queryForObject("SELECT status FROM search_index_cleanup_task WHERE index_name=?", String.class, third.index()))
            .isEqualTo("DONE");
    }

    @Test
    void concurrentPrefilledRebuildsUseLiveAliasesAtEachAtomicCutover() throws Exception {
        CountDownLatch firstReady = new CountDownLatch(1);
        CountDownLatch secondReady = new CountDownLatch(1);
        CountDownLatch firstRelease = new CountDownLatch(1);
        CountDownLatch secondRelease = new CountDownLatch(1);
        Runnable firstBarrier = () -> awaitBarrier(firstReady, firstRelease);
        Runnable secondBarrier = () -> awaitBarrier(secondReady, secondRelease);
        SearchRebuildService first = new SearchRebuildService(jdbc, projector, elasticsearch, transactionManager,
            new SearchGateRepository(new JdbcTemplate(dataSource)), firstBarrier);
        SearchRebuildService second = new SearchRebuildService(jdbc, projector, elasticsearch, transactionManager,
            new SearchGateRepository(new JdbcTemplate(dataSource)), secondBarrier);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<SearchRebuildService.RebuildReport> firstResult = workers.submit(first::rebuild);
            Future<SearchRebuildService.RebuildReport> secondResult = workers.submit(second::rebuild);
            assertThat(firstReady.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(secondReady.await(30, TimeUnit.SECONDS)).isTrue();
            firstRelease.countDown();
            SearchRebuildService.RebuildReport firstReport = firstResult.get(30, TimeUnit.SECONDS);
            secondRelease.countDown();
            SearchRebuildService.RebuildReport secondReport = secondResult.get(30, TimeUnit.SECONDS);

            assertThat(secondReport.index()).isNotEqualTo(firstReport.index());
            assertThat(elasticsearchClient.indices().getAlias(g -> g.name(ProductSearchPort.READ_ALIAS)).result()).hasSize(1).containsKey(secondReport.index());
            assertThat(elasticsearchClient.indices().getAlias(g -> g.name(ProductSearchPort.WRITE_ALIAS)).result()).hasSize(1).containsKey(secondReport.index());
            assertThat(jdbc.queryForObject("SELECT status FROM search_index_cleanup_task WHERE index_name=?", String.class, firstReport.index()))
                .isEqualTo("DONE");
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void staleAliasWorkerIsFencedAfterReadingMembersBeforeUpdate() throws Exception {
        String previous = elasticsearch.currentReadIndex();
        String targetA = elasticsearch.createRebuildIndex();
        elasticsearch.recordRebuildIntent(targetA, previous);
        elasticsearch.markRebuildIntentBuilding(targetA);
        elasticsearch.registerRebuildTarget(targetA);
        SearchGateRepository oldGate = new SearchGateRepository(new JdbcTemplate(dataSource));
        SearchGateRepository.Lease oldLease = oldGate.acquire("alias-old", java.time.Duration.ofSeconds(30));
        String oldToken = UUID.randomUUID().toString();
        assertThat(elasticsearch.claimRebuildIntentSwitch(targetA, oldLease.owner(), oldToken, oldLease.generation())).isTrue();

        CountDownLatch readMembers = new CountDownLatch(1);
        CountDownLatch releaseAliasRequest = new CountDownLatch(1);
        ElasticsearchProductSearch oldWorker = new ElasticsearchProductSearch(elasticsearchClient, new JdbcTemplate(dataSource), members -> {
            readMembers.countDown();
            awaitBarrier(readMembers, releaseAliasRequest);
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<ElasticsearchProductSearch.AliasTransition> stale = workers.submit(() -> oldWorker.switchAliasesToSingleLive(
                targetA, new ElasticsearchProductSearch.AliasFence(oldLease.owner(), oldLease.token(), oldToken, oldLease.generation())));
            assertThat(readMembers.await(30, TimeUnit.SECONDS)).isTrue();

            // The first worker is paused while still holding the named lock.
            // Expire and replace the MySQL gate, then queue B behind that
            // same lock. A's second fence check must reject its late request.
            jdbc.update("UPDATE search_rebuild_gate SET lease_until=TIMESTAMPADD(MICROSECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=1");
            SearchGateRepository newGate = new SearchGateRepository(new JdbcTemplate(dataSource));
            SearchGateRepository.Lease newLease = newGate.acquire("alias-new", java.time.Duration.ofSeconds(30));
            String targetB = elasticsearch.createRebuildIndex();
            elasticsearch.recordRebuildIntent(targetB, previous);
            elasticsearch.markRebuildIntentBuilding(targetB);
            elasticsearch.registerRebuildTarget(targetB);
            String newToken = UUID.randomUUID().toString();
            assertThat(elasticsearch.claimRebuildIntentSwitch(targetB, newLease.owner(), newToken, newLease.generation())).isTrue();
            Future<ElasticsearchProductSearch.AliasTransition> current = workers.submit(() -> elasticsearch.switchAliasesToSingleLive(
                targetB, new ElasticsearchProductSearch.AliasFence(newLease.owner(), newLease.token(), newToken, newLease.generation())));
            assertThat(current.isDone()).isFalse();
            releaseAliasRequest.countDown();

            assertThatThrownBy(stale::get).hasCauseInstanceOf(SearchGateRepository.SearchGateClosedException.class);
            current.get(30, TimeUnit.SECONDS);
            assertThat(elasticsearch.currentReadIndexes()).containsExactly(targetB);
            assertThat(elasticsearch.currentWriteIndexes()).containsExactly(targetB);
        } finally {
            releaseAliasRequest.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void aliasReconciliationRemovesAllDriftedMembersAndCleanupProtectsAnyLiveMember() throws Exception {
        String first = elasticsearch.createRebuildIndex();
        String authoritative = elasticsearch.createRebuildIndex();
        elasticsearchClient.indices().updateAliases(a -> a
            .actions(x -> x.add(v -> v.index(first).alias(ProductSearchPort.READ_ALIAS)))
            .actions(x -> x.add(v -> v.index(first).alias(ProductSearchPort.WRITE_ALIAS)))
            .actions(x -> x.add(v -> v.index(authoritative).alias(ProductSearchPort.READ_ALIAS)))
            .actions(x -> x.add(v -> v.index(authoritative).alias(ProductSearchPort.WRITE_ALIAS))));

        elasticsearch.scheduleCleanup(first);
        elasticsearch.cleanupPending();
        assertThat(elasticsearchClient.indices().exists(e -> e.index(first)).value()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM search_index_cleanup_task WHERE index_name=?", String.class, first))
            .isEqualTo("DONE");
        elasticsearch.switchAliasesToSingleLive(authoritative);
        assertThat(elasticsearch.currentReadIndexes()).containsExactly(authoritative);
        assertThat(elasticsearch.currentWriteIndexes()).containsExactly(authoritative);
    }

    @Test
    void reconciliationCannotRollbackWhileHigherGenerationIsSwitching() {
        SearchRebuildService.RebuildReport old = rebuild.rebuild();
        String target = elasticsearch.createRebuildIndex();
        elasticsearch.recordRebuildIntent(target, old.index());
        elasticsearch.markRebuildIntentBuilding(target);
        elasticsearch.registerRebuildTarget(target);
        long activeGeneration = jdbc.queryForObject("SELECT generation FROM search_rebuild_gate WHERE id=1", Long.class) + 1;
        jdbc.update("UPDATE search_rebuild_intent SET phase='SWITCHING',generation=?,owner_id='new-owner',claim_token='new-token',lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)) WHERE target_index=?", activeGeneration, target);
        elasticsearch.switchAliasesToSingleLive(target);

        elasticsearch.cleanupPending();

        assertThat(elasticsearch.currentReadIndexes()).containsExactly(target);
        assertThat(elasticsearch.currentWriteIndexes()).containsExactly(target);
    }

    @Test
    void stageFailureLeavesOldAliasAndRecoveryConverges() {
        for (String failedStage : java.util.List.of("FILL", "REFRESH_FILL", "REPLAY", "REFRESH_REPLAY", "ALIAS_SWAP")) {
            jdbc.update("UPDATE search_rebuild_gate SET mode='OPEN',owner_id=NULL,claim_token=NULL,lease_until=NULL WHERE id=1");
            String previous = elasticsearch.currentReadIndex();
            AtomicBoolean tripped = new AtomicBoolean();
            SearchRebuildService failing = new SearchRebuildService(jdbc, projector, elasticsearch, transactionManager,
                new SearchGateRepository(new JdbcTemplate(dataSource)), () -> { }, stage -> {
                    if (failedStage.equals(stage) && tripped.compareAndSet(false, true)) ELASTICSEARCH_PROXY.setConnectionCut(true);
                });
            try {
                assertThrows(RuntimeException.class, failing::rebuild);
            } finally {
                ELASTICSEARCH_PROXY.setConnectionCut(false);
            }
            assertThat(tripped).as("stage hook %s", failedStage).isTrue();
            assertThat(elasticsearch.currentReadIndex()).isEqualTo(previous);
            elasticsearch.cleanupPending();
            SearchRebuildService.RebuildReport recovered = rebuild.rebuild();
            assertThat(recovered.index()).isNotEqualTo(previous);
            assertThat(elasticsearch.currentReadIndex()).isEqualTo(recovered.index());
        }
    }

    private static void awaitBarrier(CountDownLatch ready, CountDownLatch release) {
        ready.countDown();
        try {
            if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("测试 barrier 超时");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试 barrier 被中断", interrupted);
        }
    }

    private static int queryInt(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new SQLException("查询未返回结果");
            return result.getInt(1);
        }
    }

    @Test
    void cleanupRecoversExpiredBuildingTargetAndNeverCallsEsForAttemptThree() {
        String building = "campus-listing-rebuild-building-" + UUID.randomUUID().toString().replace("-", "");
        String exhausted = "campus-listing-rebuild-exhausted-" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO search_index_cleanup_task(id,index_name,status,owner_id,claim_token,lease_until,attempt_count,available_at,created_at) VALUES (?,?, 'BUILDING','dead-owner','dead-token',TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)),0,TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)),CURRENT_TIMESTAMP(6))", UUID.randomUUID().toString(), building);
        jdbc.update("INSERT INTO search_index_cleanup_task(id,index_name,status,attempt_count,available_at,created_at,owner_id,claim_token,lease_until) VALUES (?,?, 'RUNNING',3,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),'dead-owner','dead-token',TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)))", UUID.randomUUID().toString(), exhausted);

        elasticsearch.cleanupPending();

        assertThat(jdbc.queryForObject("SELECT status FROM search_index_cleanup_task WHERE index_name=?", String.class, building))
            .isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT status FROM search_index_cleanup_task WHERE index_name=?", String.class, exhausted))
            .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM search_index_cleanup_task WHERE index_name=?", Integer.class, exhausted))
            .isEqualTo(3);
    }

    @Test
    void reconciliationProtectsAliasTargetAfterSwitchBeforeIntentCommit() {
        String previous = elasticsearch.currentReadIndex();
        String target = elasticsearch.createRebuildIndex();
        elasticsearch.recordRebuildIntent(target, previous);
        elasticsearch.markRebuildIntentBuilding(target);
        elasticsearch.registerRebuildTarget(target);

        // Simulate the external alias request succeeding immediately before
        // the DB phase update/commit is interrupted.
        elasticsearch.switchAliases(target, previous);
        elasticsearch.cleanupPending();

        assertThat(elasticsearch.currentReadIndex()).isEqualTo(target);
        assertThat(jdbc.queryForObject("SELECT status FROM search_index_cleanup_task WHERE index_name=?", String.class, target))
            .isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT phase FROM search_rebuild_intent WHERE target_index=?", String.class, target))
            .isEqualTo("RECONCILED");
    }

    @Test
    void twoCleanupWorkersUseSkipLockedAndOwnerFencing() throws Exception {
        String target = elasticsearch.createRebuildIndex();
        elasticsearch.scheduleCleanup(target);
        CountDownLatch claimCommitted = new CountDownLatch(1);
        CountDownLatch releaseDelete = new CountDownLatch(1);
        ElasticsearchProductSearch firstWorker = new ElasticsearchProductSearch(elasticsearchClient, new JdbcTemplate(dataSource),
            ignored -> { }, index -> {
                claimCommitted.countDown();
                awaitBarrier(claimCommitted, releaseDelete);
            });
        ElasticsearchProductSearch secondWorker = new ElasticsearchProductSearch(elasticsearchClient, new JdbcTemplate(dataSource));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(firstWorker::cleanupPending);
            assertThat(claimCommitted.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT status FROM search_index_cleanup_task WHERE index_name=?", String.class, target))
                .isEqualTo("RUNNING");
            // The claim transaction has committed before the first worker
            // enters ES delete. A second independent worker can observe it.
            var observer = workers.submit(secondWorker::cleanupPending);
            observer.get(30, TimeUnit.SECONDS);
            jdbc.update("UPDATE search_index_cleanup_task SET lease_until=TIMESTAMPADD(MICROSECOND,-1,CURRENT_TIMESTAMP(6)) WHERE index_name=?", target);
            var takeover = workers.submit(secondWorker::cleanupPending);
            takeover.get(30, TimeUnit.SECONDS);
            releaseDelete.countDown();
            first.get(30, TimeUnit.SECONDS);
        } finally {
            releaseDelete.countDown();
            workers.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM search_index_cleanup_task WHERE index_name=?", String.class, target))
            .isEqualTo("DONE");
    }
}
