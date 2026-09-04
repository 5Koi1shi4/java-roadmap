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
import com.example.campusmarket.catalog.search.SearchIndexCleanupRepository;
import com.example.campusmarket.catalog.search.SearchIndexCleanupWorker;
import com.example.campusmarket.catalog.search.SearchRebuildIntentRepository;
import com.example.campusmarket.catalog.search.SearchRebuildReconciler;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Set;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.awaitility.Awaitility.await;

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
    @Autowired SearchIndexCleanupWorker cleanupWorker;
    @Autowired SearchIndexCleanupRepository cleanupRepository;
    @Autowired SearchRebuildIntentRepository intentRepository;
    @Autowired SearchRebuildReconciler reconciler;
    @Autowired MeterRegistry metrics;

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
        // 每个仓储都从连接池取得独立 JDBC 连接；以下断言覆盖已提交的心跳可见性、
        // 租约接管和旧 owner fencing，而不是线程本地状态。
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
            assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
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
            SearchGateRepository gate = new SearchGateRepository(new JdbcTemplate(oneConnection));
            SearchGateRepository.Lease lease = gate.acquire("pool-one-cutover", Duration.ofSeconds(30));
            String target = elasticsearch.createRebuildIndex();
            Set<String> live = new java.util.LinkedHashSet<>(elasticsearch.currentReadIndexes());
            ElasticsearchProductSearch oneSearch = new ElasticsearchProductSearch(elasticsearchClient, new JdbcTemplate(oneConnection));
            coordinator.execute(Duration.ofSeconds(5), "rebuild-cutover", "pool-one-cutover", connection -> {
                invokeGateLease(gate, connection, lease);
                invokeAliasMutation(oneSearch, target, live);
                return null;
            });
            gate.release(lease);
            assertThat(elasticsearch.currentReadIndexes()).containsExactly(target);
        }
    }

    @Test
    void reconcilerArmCleanupUsesHeldConnectionWithPoolSizeOne() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(MYSQL.getJdbcUrl());
        config.setUsername(MYSQL.getUsername());
        config.setPassword(MYSQL.getPassword());
        config.setMaximumPoolSize(1);
        try (HikariDataSource oneConnection = new HikariDataSource(config)) {
            JdbcTemplate oneJdbc = new JdbcTemplate(oneConnection);
            SearchAliasCoordinator coordinator = new SearchAliasCoordinator(oneConnection);
            SearchIndexCleanupRepository repository = new SearchIndexCleanupRepository(oneJdbc);
            SearchRebuildReconciler oneReconciler = new SearchRebuildReconciler(
                new SearchGateRepository(oneJdbc), coordinator, elasticsearch, repository, new SimpleMeterRegistry());
            coordinator.execute(Duration.ofSeconds(5), "reconcile", "pool-one-arm", connection -> {
                invokeReconcilerArm(oneReconciler, connection, "not-present");
                return null;
            });
        }
    }

    @Test
    void expiredLeaseWorkersSerializeFencedAliasMutationsInOrder() throws Exception {
        String targetA = elasticsearch.createRebuildIndex();
        String targetB = elasticsearch.createRebuildIndex();
        Set<String> live = new java.util.LinkedHashSet<>(elasticsearch.currentReadIndexes());
        SearchGateRepository gateA = new SearchGateRepository(new JdbcTemplate(dataSource));
        SearchGateRepository gateB = new SearchGateRepository(new JdbcTemplate(dataSource));
        SearchAliasCoordinator coordinatorA = new SearchAliasCoordinator(dataSource);
        CountDownLatch bAcquireAttempted = new CountDownLatch(1);
        SearchAliasCoordinator coordinatorB = coordinatorWithAcquireAttemptHook(dataSource, metrics, bAcquireAttempted::countDown);
        java.util.concurrent.atomic.AtomicInteger aliasMutations = new java.util.concurrent.atomic.AtomicInteger();
        ElasticsearchProductSearch mutationSearch = new ElasticsearchProductSearch(elasticsearchClient,
            jdbc, ignored -> aliasMutations.incrementAndGet());
        SearchGateRepository.Lease leaseA = gateA.acquire("lease-expiry-A", Duration.ofSeconds(30));
        CountDownLatch beforeFinalFenceA = new CountDownLatch(1);
        CountDownLatch releaseFinalFenceA = new CountDownLatch(1);
        CountDownLatch bEntered = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Void> first = workers.submit(() -> {
                coordinatorA.execute(Duration.ofSeconds(30), "rebuild-cutover", "lease-expiry-A", connection -> {
                    invokeGateLease(gateA, connection, leaseA);
                    beforeFinalFenceA.countDown();
                    awaitBarrier(beforeFinalFenceA, releaseFinalFenceA);
                    invokeGateLease(gateA, connection, leaseA);
                    invokeAliasMutation(mutationSearch, targetA, live);
                    return null;
                });
                return null;
            });
            assertThat(beforeFinalFenceA.await(30, TimeUnit.SECONDS)).isTrue();
            jdbc.update("UPDATE search_rebuild_gate SET lease_until=TIMESTAMPADD(MICROSECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=1");
            Future<Void> second = workers.submit(() -> {
                coordinatorB.execute(Duration.ofSeconds(30), "rebuild-cutover", "lease-expiry-B", connection -> {
                    SearchGateRepository.Lease leaseB = invokeGateAcquire(gateB, connection, "lease-expiry-B", Duration.ofSeconds(30));
                    try {
                        invokeGateLease(gateB, connection, leaseB);
                        bEntered.countDown();
                        Set<String> currentLive = new java.util.LinkedHashSet<>(elasticsearch.currentReadIndexes());
                        invokeAliasMutation(mutationSearch, targetB, currentLive);
                        return null;
                    } finally {
                        invokeGateRelease(gateB, connection, leaseB);
                    }
                });
                return null;
            });
            assertThat(bAcquireAttempted.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(bEntered.await(0, TimeUnit.MILLISECONDS)).isFalse();
            releaseFinalFenceA.countDown();
            ExecutionException staleA = assertThrows(ExecutionException.class, () -> first.get(30, TimeUnit.SECONDS));
            assertThat(staleA.getCause()).isInstanceOf(SearchGateRepository.SearchGateClosedException.class);
            assertThat(aliasMutations).hasValue(0);
            assertThat(bEntered.await(30, TimeUnit.SECONDS)).isTrue();
            second.get(30, TimeUnit.SECONDS);
            assertThat(aliasMutations).hasValue(1);
            assertThat(elasticsearch.currentReadIndexes()).containsExactly(targetB);
            assertThat(elasticsearch.currentWriteIndexes()).containsExactly(targetB);
        } finally {
            gateA.release(leaseA);
            releaseFinalFenceA.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void disconnectedFixedConnectionDoesNotLeakNamedLockAndRecordsReleaseFailure() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(MYSQL.getJdbcUrl());
        config.setUsername(MYSQL.getUsername());
        config.setPassword(MYSQL.getPassword());
        config.setMaximumPoolSize(1);
        try (HikariDataSource isolated = new HikariDataSource(config)) {
            SearchAliasCoordinator coordinator = new SearchAliasCoordinator(isolated, metrics);
            RuntimeException disconnected = assertThrows(RuntimeException.class,
                () -> coordinator.execute(Duration.ofSeconds(5), "disconnect-check", "disconnect-owner", connection -> {
                    connection.close();
                    throw new IllegalStateException("fixed connection disconnected");
                }));
            assertThat(disconnected).hasMessage("fixed connection disconnected");
            assertThat(disconnected.getSuppressed()).anySatisfy(suppressed ->
                assertThat(suppressed).isInstanceOf(SearchAliasCoordinator.SearchCoordinationException.class));
            assertThat(metrics.get("search.alias.coordination.lock.release.failure").counter().count()).isGreaterThan(0);
            assertThat(new SearchAliasCoordinator(isolated).<Integer>execute(Duration.ofSeconds(5),
                "disconnect-recovery", "recovery-owner", connection -> queryInt(connection, "SELECT 1"))).isEqualTo(1);
        }
    }

    @Test
    void aliasCoordinatorWrapsCheckedFailureAndRetainsReleaseFailure() {
        Exception callbackFailure = new Exception("checked callback failure");
        SearchAliasCoordinator coordinator = new SearchAliasCoordinator(failingNamedLockDataSource());

        SearchAliasCoordinator.SearchCoordinationException thrown = assertThrows(
            SearchAliasCoordinator.SearchCoordinationException.class,
            () -> coordinator.execute(Duration.ofSeconds(5), connection -> {
                connection.close();
                throw callbackFailure;
            }));

        assertThat(thrown.getCause()).isSameAs(callbackFailure);
        assertThat(thrown.getSuppressed())
            .anySatisfy(suppressed -> assertThat(suppressed).isInstanceOf(SearchAliasCoordinator.SearchCoordinationException.class));
    }

    private static DataSource failingNamedLockDataSource() {
        Connection connection = (Connection) Proxy.newProxyInstance(
            SearchRebuildIT.class.getClassLoader(), new Class<?>[] { Connection.class }, new java.lang.reflect.InvocationHandler() {
                private boolean closed;
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    return switch (method.getName()) {
                        case "close" -> { closed = true; yield null; }
                        case "isClosed" -> closed;
                        case "setAutoCommit" -> null;
                        case "prepareStatement" -> {
                            if (closed) throw new SQLException("Connection is closed");
                            yield failingLockStatement();
                        }
                        case "toString" -> "failing-named-lock-connection";
                        default -> defaultValue(method.getReturnType());
                    };
                }
            });
        return (DataSource) Proxy.newProxyInstance(
            SearchRebuildIT.class.getClassLoader(), new Class<?>[] { DataSource.class }, (proxy, method, args) ->
                method.getName().equals("getConnection") ? connection : defaultValue(method.getReturnType()));
    }

    private static PreparedStatement failingLockStatement() {
        ResultSet result = (ResultSet) Proxy.newProxyInstance(
            SearchRebuildIT.class.getClassLoader(), new Class<?>[] { ResultSet.class }, new java.lang.reflect.InvocationHandler() {
                private boolean returned;
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) {
                    return switch (method.getName()) {
                        case "next" -> {
                            boolean result = !returned;
                            returned = true;
                            yield result;
                        }
                        case "getInt" -> 1;
                        default -> defaultValue(method.getReturnType());
                    };
                }
            });
        return (PreparedStatement) Proxy.newProxyInstance(
            SearchRebuildIT.class.getClassLoader(), new Class<?>[] { PreparedStatement.class }, (proxy, method, args) -> switch (method.getName()) {
                case "executeQuery" -> result;
                default -> defaultValue(method.getReturnType());
            });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
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
        invokeReconcilerHook(newReconcilerOrNull(), () -> { });
        cleanupWorker.runOnce();
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
                .isEqualTo("NEW");
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void reconciliationCannotRollbackWhileHigherGenerationIsSwitching() throws Exception {
        SearchRebuildService.RebuildReport old = rebuild.rebuild();
        String target = elasticsearch.createRebuildIndex();
        intentRepository.record(target, old.index());
        intentRepository.markBuilding(target);
        cleanupRepository.registerBuilding(target, "search-cleanup-test", UUID.randomUUID().toString());
        long activeGeneration = jdbc.queryForObject("SELECT generation FROM search_rebuild_gate WHERE id=1", Long.class) + 1;
        jdbc.update("UPDATE search_rebuild_intent SET phase='SWITCHING',generation=?,owner_id='new-owner',claim_token='new-token',lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)) WHERE target_index=?", activeGeneration, target);
        Set<String> liveMembers = new java.util.LinkedHashSet<>(elasticsearch.currentReadIndexes());
        liveMembers.addAll(elasticsearch.currentWriteIndexes());
        elasticsearchClient.indices().updateAliases(a -> {
            for (String member : liveMembers) {
                a.actions(x -> x.remove(v -> v.index(member).alias(ProductSearchPort.READ_ALIAS)));
                a.actions(x -> x.remove(v -> v.index(member).alias(ProductSearchPort.WRITE_ALIAS)));
            }
            return a;
        });
        elasticsearchClient.indices().updateAliases(a -> a
            .actions(x -> x.add(v -> v.index(target).alias(ProductSearchPort.READ_ALIAS)))
            .actions(x -> x.add(v -> v.index(target).alias(ProductSearchPort.WRITE_ALIAS))));

        Set<String> beforeRead = elasticsearch.currentReadIndexes();
        Set<String> beforeWrite = elasticsearch.currentWriteIndexes();
        SearchRebuildReconciler reconciler = new SearchRebuildReconciler(
            new SearchGateRepository(new JdbcTemplate(dataSource)), new SearchAliasCoordinator(dataSource), elasticsearch);
        assertThat(reconciler.runOnce()).isEqualTo(SearchRebuildReconciler.ReconcileResult.SKIPPED_SWITCHING);

        assertThat(elasticsearch.currentReadIndexes()).containsExactlyElementsOf(beforeRead);
        assertThat(elasticsearch.currentWriteIndexes()).containsExactlyElementsOf(beforeWrite);
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
            invokeReconcilerHook(newReconcilerOrNull(), () -> { });
            cleanupWorker.runOnce();
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

    private static void invokeAliasMutation(ElasticsearchProductSearch search, String target, Set<String> live) {
        try {
            Method mutation = ElasticsearchProductSearch.class.getDeclaredMethod(
                "replaceAliasesWithSingleTarget", String.class, Set.class);
            mutation.setAccessible(true);
            mutation.invoke(search, target, live);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void invokeGateLease(SearchGateRepository gate, Connection connection,
                                        SearchGateRepository.Lease lease) {
        try {
            Method assertion = SearchGateRepository.class.getDeclaredMethod(
                "assertLease", Connection.class, SearchGateRepository.Lease.class);
            assertion.setAccessible(true);
            assertion.invoke(gate, connection, lease);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static SearchAliasCoordinator coordinatorWithAcquireAttemptHook(
        DataSource dataSource, MeterRegistry metrics, Runnable hook) {
        try {
            Constructor<SearchAliasCoordinator> constructor = SearchAliasCoordinator.class.getDeclaredConstructor(
                DataSource.class, MeterRegistry.class, Runnable.class);
            constructor.setAccessible(true);
            return constructor.newInstance(dataSource, metrics, hook);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("构造协调器测试接缝失败", failure);
        }
    }

    private static SearchGateRepository.Lease invokeGateAcquire(SearchGateRepository gate,
                                                                 Connection connection, String owner,
                                                                 Duration duration) {
        try {
            Method acquire = SearchGateRepository.class.getDeclaredMethod(
                "acquire", Connection.class, String.class, Duration.class);
            acquire.setAccessible(true);
            return (SearchGateRepository.Lease) acquire.invoke(gate, connection, owner, duration);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static int invokeGateRelease(SearchGateRepository gate, Connection connection,
                                         SearchGateRepository.Lease lease) {
        try {
            Method release = SearchGateRepository.class.getDeclaredMethod(
                "release", Connection.class, SearchGateRepository.Lease.class);
            release.setAccessible(true);
            return (int) release.invoke(gate, connection, lease);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void invokeReconcilerArm(SearchRebuildReconciler reconciler,
                                            Connection connection, String index) {
        try {
            Method arm = SearchRebuildReconciler.class.getDeclaredMethod(
                "armCleanup", Connection.class, String.class);
            arm.setAccessible(true);
            arm.invoke(reconciler, connection, index);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Set<String> allAliasMembersFromRawClient() {
        try {
            Set<String> members = new java.util.LinkedHashSet<>();
            members.addAll(elasticsearchClient.indices().getAlias(g -> g.name(ProductSearchPort.READ_ALIAS)).result().keySet());
            members.addAll(elasticsearchClient.indices().getAlias(g -> g.name(ProductSearchPort.WRITE_ALIAS)).result().keySet());
            return members;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void cleanupRecoversExpiredBuildingTargetAndNeverCallsEsForAttemptThree() {
        String building = "campus-listing-rebuild-building-" + UUID.randomUUID().toString().replace("-", "");
        String exhausted = "campus-listing-rebuild-exhausted-" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO search_index_cleanup_task(id,index_name,status,owner_id,claim_token,lease_until,attempt_count,available_at,created_at) VALUES (?,?, 'BUILDING','dead-owner','dead-token',TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)),0,TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)),CURRENT_TIMESTAMP(6))", UUID.randomUUID().toString(), building);
        jdbc.update("INSERT INTO search_index_cleanup_task(id,index_name,status,attempt_count,available_at,created_at,owner_id,claim_token,lease_until) VALUES (?,?, 'RUNNING',3,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),'dead-owner','dead-token',TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)))", UUID.randomUUID().toString(), exhausted);

        cleanupWorker.runOnce();

        assertThat(jdbc.queryForObject("SELECT status FROM search_index_cleanup_task WHERE index_name=?", String.class, building))
            .isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT status FROM search_index_cleanup_task WHERE index_name=?", String.class, exhausted))
            .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM search_index_cleanup_task WHERE index_name=?", Integer.class, exhausted))
            .isEqualTo(3);
    }

    @Test
    void reconciliationProtectsAliasTargetAfterSwitchBeforeIntentCommit() throws Exception {
        String previous = elasticsearch.currentReadIndex();
        String target = elasticsearch.createRebuildIndex();
        intentRepository.record(target, previous);
        intentRepository.markBuilding(target);
        cleanupRepository.registerBuilding(target, "search-cleanup-test", UUID.randomUUID().toString());

        // 模拟外部别名请求已成功，但数据库阶段更新或提交随即中断。
        Set<String> liveMembers = new java.util.LinkedHashSet<>(elasticsearch.currentReadIndexes());
        liveMembers.addAll(elasticsearch.currentWriteIndexes());
        elasticsearchClient.indices().updateAliases(a -> {
            for (String member : liveMembers) {
                a.actions(x -> x.remove(v -> v.index(member).alias(ProductSearchPort.READ_ALIAS)));
                a.actions(x -> x.remove(v -> v.index(member).alias(ProductSearchPort.WRITE_ALIAS)));
            }
            return a;
        });
        elasticsearchClient.indices().updateAliases(a -> a
            .actions(x -> x.add(v -> v.index(target).alias(ProductSearchPort.READ_ALIAS)))
            .actions(x -> x.add(v -> v.index(target).alias(ProductSearchPort.WRITE_ALIAS))));
        invokeReconcilerHook(newReconcilerOrNull(), () -> { });
        cleanupWorker.runOnce();

        assertThat(elasticsearch.currentReadIndex()).isEqualTo(target);
        assertThat(jdbc.queryForObject("SELECT status FROM search_index_cleanup_task WHERE index_name=?", String.class, target))
            .isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT phase FROM search_rebuild_intent WHERE target_index=?", String.class, target))
            .isEqualTo("RECONCILED");
    }

    @Test
    void twoCleanupWorkersUseSkipLockedAndOwnerFencing() throws Exception {
        String target = elasticsearch.createRebuildIndex();
        cleanupRepository.schedule(target);
        CountDownLatch claimCommitted = new CountDownLatch(1);
        CountDownLatch releaseDelete = new CountDownLatch(1);
        SearchIndexCleanupRepository firstRepository = new SearchIndexCleanupRepository(new JdbcTemplate(dataSource));
        SearchIndexCleanupRepository secondRepository = new SearchIndexCleanupRepository(new JdbcTemplate(dataSource));
        SearchIndexCleanupWorker firstWorker = new SearchIndexCleanupWorker(firstRepository,
            new SearchAliasCoordinator(dataSource), elasticsearch);
        firstWorker.setCleanupDeleteHook(index -> {
                claimCommitted.countDown();
                awaitBarrier(claimCommitted, releaseDelete);
            });
        SearchIndexCleanupWorker secondWorker = new SearchIndexCleanupWorker(secondRepository,
            new SearchAliasCoordinator(dataSource), elasticsearch);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(firstWorker::runOnce);
            assertThat(claimCommitted.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT status FROM search_index_cleanup_task WHERE index_name=?", String.class, target))
                .isEqualTo("RUNNING");
            SearchIndexCleanupRepository.CleanupClaim stale = jdbc.queryForObject(
                "SELECT id,index_name,attempt_count,owner_id,claim_token,lease_until FROM search_index_cleanup_task WHERE index_name=?",
                (rs, rowNum) -> new SearchIndexCleanupRepository.CleanupClaim(rs.getString(1), rs.getString(2),
                    rs.getInt(3), rs.getString(4), rs.getString(5), rs.getTimestamp(6)), target);
            // 领取事务在首个 worker 进入 ES 删除前已经提交，第二个独立 worker 应能观察到该状态。
            var observer = workers.submit(secondWorker::runOnce);
            observer.get(30, TimeUnit.SECONDS);
            jdbc.update("UPDATE search_index_cleanup_task SET lease_until=TIMESTAMPADD(MICROSECOND,-1,CURRENT_TIMESTAMP(6)) WHERE index_name=?", target);
            var takeover = workers.submit(secondWorker::runOnce);
            await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT owner_id FROM search_index_cleanup_task WHERE index_name=?", String.class, target))
                    .isNotEqualTo(stale.owner()));
            assertThat(firstRepository.complete(stale)).isZero();
            releaseDelete.countDown();
            first.get(30, TimeUnit.SECONDS);
            takeover.get(30, TimeUnit.SECONDS);
        } finally {
            releaseDelete.countDown();
            firstWorker.setCleanupDeleteHook(null);
            workers.shutdownNow();
            assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM search_index_cleanup_task WHERE index_name=?", String.class, target))
            .isEqualTo("DONE");
    }

    @Test
    void cleanupAndCutoverShareCoordinator() throws Exception {
        String target = elasticsearch.createRebuildIndex();
        String cutoverTarget = elasticsearch.createRebuildIndex();
        cleanupRepository.schedule(target);
        CountDownLatch cleanupRead = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        CountDownLatch cutoverAcquireAttempted = new CountDownLatch(1);
        cleanupWorker.setCleanupDeleteHook(index -> {
            cleanupRead.countDown();
            awaitBarrier(cleanupRead, releaseCleanup);
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> cleanup = workers.submit(cleanupWorker::runOnce);
            assertThat(cleanupRead.await(30, TimeUnit.SECONDS)).isTrue();

            CountDownLatch cutoverEntered = new CountDownLatch(1);
            SearchAliasCoordinator cutoverCoordinator = coordinatorWithAcquireAttemptHook(
                dataSource, metrics, cutoverAcquireAttempted::countDown);
            Future<Void> cutover = workers.submit(() -> {
                cutoverCoordinator.execute(Duration.ofSeconds(30), "rebuild-cutover", "cleanup-cutover", connection -> {
                    cutoverEntered.countDown();
                    Set<String> currentLive = allAliasMembersFromRawClient();
                    invokeAliasMutation(elasticsearch, cutoverTarget, currentLive);
                    return null;
                });
                return null;
            });
            assertThat(cutoverAcquireAttempted.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(cutoverEntered.await(0, TimeUnit.MILLISECONDS)).isFalse();
            releaseCleanup.countDown();
            cleanup.get(30, TimeUnit.SECONDS);
            cutover.get(30, TimeUnit.SECONDS);
            assertThat(cutoverEntered.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(elasticsearch.currentReadIndexes()).containsExactly(cutoverTarget);
            assertThat(elasticsearch.currentWriteIndexes()).containsExactly(cutoverTarget);
        } finally {
            releaseCleanup.countDown();
            cleanupWorker.setCleanupDeleteHook(null);
            workers.shutdownNow();
            assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void cleanupSuspendsCallerTransaction() {
        String target = elasticsearch.createRebuildIndex();
        cleanupRepository.schedule(target);
        AtomicBoolean observedSuspended = new AtomicBoolean();
        cleanupWorker.setCleanupDeleteHook(index -> observedSuspended.set(!TransactionSynchronizationManager.isActualTransactionActive()));
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                cleanupWorker.runOnce();
            });
        } finally {
            cleanupWorker.setCleanupDeleteHook(null);
        }
        assertThat(observedSuspended).isTrue();
    }

    @Test
    void cleanupClaimIsVisibleAndStaleCompletionIsFenced() {
        String target = elasticsearch.createRebuildIndex();
        cleanupRepository.schedule(target);
        SearchIndexCleanupRepository first = new SearchIndexCleanupRepository(jdbc);
        SearchIndexCleanupRepository second = new SearchIndexCleanupRepository(new JdbcTemplate(dataSource));
        SearchIndexCleanupRepository.CleanupClaim oldClaim = first.claimBatch(20).get(0);
        jdbc.update("UPDATE search_index_cleanup_task SET lease_until=TIMESTAMPADD(MICROSECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?", oldClaim.id());
        SearchIndexCleanupRepository.CleanupClaim currentClaim = second.claimBatch(20).get(0);

        assertThat(currentClaim.owner()).isNotEqualTo(oldClaim.owner());
        assertThat(first.complete(oldClaim)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM search_index_cleanup_task WHERE id=?", String.class, oldClaim.id()))
            .isEqualTo("RUNNING");
        assertThat(second.complete(currentClaim)).isEqualTo(1);
    }

    @Test
    void coordinationTimeoutReturnsCleanupClaimWithoutConsumingBusinessAttempt() {
        String target = elasticsearch.createRebuildIndex();
        cleanupRepository.schedule(target);
        SearchIndexCleanupRepository repository = new SearchIndexCleanupRepository(jdbc);
        SearchIndexCleanupRepository.CleanupClaim claim = repository.claimBatch(20).get(0);
        SearchAliasCoordinator.SearchCoordinationTimeoutException timeout =
            new SearchAliasCoordinator.SearchCoordinationTimeoutException("cleanup-delete timeout", 1_000_000);

        assertThat(repository.retryAfterCoordinationFailure(claim, timeout)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM search_index_cleanup_task WHERE id=?", String.class, claim.id()))
            .isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM search_index_cleanup_task WHERE id=?", Integer.class, claim.id()))
            .isZero();
    }

    @Test
    void reconciliationAndGateAcquireShareOneLinearizationPoint() throws Exception {
        Object reconciler = newReconcilerOrNull();
        assertThat(reconciler).as("SearchRebuildReconciler must be available").isNotNull();

        CountDownLatch gateObservedOpen = new CountDownLatch(1);
        CountDownLatch finishRepair = new CountDownLatch(1);
        SearchGateRepository secondGate = new SearchGateRepository(new JdbcTemplate(dataSource));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Void> repairing = workers.submit(() -> {
                invokeReconcilerHook(reconciler, () -> awaitBarrier(gateObservedOpen, finishRepair));
                return null;
            });
            assertThat(gateObservedOpen.await(30, TimeUnit.SECONDS)).isTrue();
            Future<SearchGateRepository.Lease> acquiring = workers.submit(() ->
                secondGate.acquire("new-rebuild", Duration.ofSeconds(30)));
            assertThat(acquiring.isDone()).isFalse();
            finishRepair.countDown();
            repairing.get(30, TimeUnit.SECONDS);
            SearchGateRepository.Lease lease = acquiring.get(30, TimeUnit.SECONDS);
            assertThat(lease.generation()).isPositive();
            secondGate.release(lease);
        } finally {
            finishRepair.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void driftedAliasesUseCompleteMemberSets() throws Exception {
        Object reconciler = newReconcilerOrNull();
        assertThat(reconciler).as("SearchRebuildReconciler must be available").isNotNull();
        SearchRebuildService.RebuildReport authoritative = rebuild.rebuild();
        String drift = elasticsearch.createRebuildIndex();

        // 只使用测试持有的原始客户端构造漂移；不能假定权威 target 是 map 的第一个 key。
        elasticsearchClient.indices().updateAliases(a -> a
            .actions(x -> x.add(v -> v.index(drift).alias(ProductSearchPort.READ_ALIAS)))
            .actions(x -> x.add(v -> v.index(drift).alias(ProductSearchPort.WRITE_ALIAS))));
        assertThat(elasticsearch.currentReadIndexes()).contains(drift, authoritative.index());
        assertThat(elasticsearch.currentWriteIndexes()).contains(drift, authoritative.index());

        invokeReconcilerHook(reconciler, () -> { });
        assertThat(elasticsearch.currentReadIndexes()).containsExactly(authoritative.index());
        assertThat(elasticsearch.currentWriteIndexes()).containsExactly(authoritative.index());
    }

    @Test
    void cutoverFailureAfterAliasMutationRetainsCleanupMetadataForEveryFormerMember() throws Exception {
        SearchRebuildService.RebuildReport authoritative = rebuild.rebuild();
        String drift = elasticsearch.createRebuildIndex();
        elasticsearchClient.indices().updateAliases(a -> a
            .actions(x -> x.add(v -> v.index(drift).alias(ProductSearchPort.READ_ALIAS)))
            .actions(x -> x.add(v -> v.index(drift).alias(ProductSearchPort.WRITE_ALIAS))));
        Set<String> formerMembers = new java.util.LinkedHashSet<>(elasticsearch.currentReadIndexes());
        formerMembers.addAll(elasticsearch.currentWriteIndexes());

        AtomicBoolean tripped = new AtomicBoolean();
        SearchRebuildService failing = new SearchRebuildService(jdbc, projector, elasticsearch, transactionManager,
            new SearchGateRepository(new JdbcTemplate(dataSource)), () -> { }, stage -> {
                if ("AFTER_ALIAS_SWAP".equals(stage) && tripped.compareAndSet(false, true)) {
                    throw new IllegalStateException("simulated post-alias failure");
                }
            });
        assertThrows(RuntimeException.class, failing::rebuild);
        assertThat(tripped).isTrue();

        String target = jdbc.queryForObject("SELECT target_index FROM search_rebuild_intent WHERE phase='SWITCHING' LIMIT 1", String.class);
        assertThat(elasticsearch.currentReadIndexes()).containsExactly(target);
        assertThat(elasticsearch.currentWriteIndexes()).containsExactly(target);
        for (String former : formerMembers) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM search_index_cleanup_task WHERE index_name=?", Integer.class, former))
                .as("cleanup row for former alias member %s", former).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM search_index_cleanup_task WHERE index_name=?", String.class, former))
                .as("former alias member remains protected before reconciliation: %s", former)
                .isIn("BUILDING", "DONE");
        }

        jdbc.update("UPDATE search_rebuild_intent SET lease_until=TIMESTAMPADD(MICROSECOND,-1,CURRENT_TIMESTAMP(6)) WHERE target_index=?", target);
        invokeReconcilerHook(newReconcilerOrNull(), () -> { });
        for (String former : formerMembers) {
            assertThat(jdbc.queryForObject("SELECT status FROM search_index_cleanup_task WHERE index_name=?", String.class, former))
                .as("former alias member is durably protected/eligible after reconciliation: %s", former)
                .isIn("NEW", "DONE");
        }
        cleanupWorker.runOnce();
        assertThat(elasticsearch.currentReadIndexes()).containsExactly(target);
    }

    @Test
    void productionAliasApiHasNoUnfencedBypass() throws Exception {
        Class<?> type = Class.forName("com.example.campusmarket.catalog.search.ElasticsearchProductSearch");
        assertThat(Arrays.stream(type.getMethods())
            .noneMatch(method -> method.getName().equals("switchAliases") && method.getParameterCount() == 2))
            .as("legacy two-argument switchAliases must be removed").isTrue();
        assertThat(Arrays.stream(type.getMethods())
            .noneMatch(method -> method.getName().equals("switchAliasesToSingleLive") && method.getParameterCount() == 1))
            .as("unfenced single-target switchAliasesToSingleLive must be removed").isTrue();
    }

    @Test
    void aliasMutationVerifiesTargetExistsBeforeSendingUpdate() throws Exception {
        String target = elasticsearch.createRebuildIndex();
        Set<String> live = new java.util.LinkedHashSet<>(elasticsearch.currentReadIndexes());
        elasticsearchClient.indices().delete(d -> d.index(target));
        Method mutation = ElasticsearchProductSearch.class.getDeclaredMethod(
            "replaceAliasesWithSingleTarget", String.class, Set.class);
        mutation.setAccessible(true);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
            () -> mutation.invoke(elasticsearch, target, live));

        assertThat(thrown.getCause()).isInstanceOf(ElasticsearchProductSearch.SearchUnavailableException.class);
        assertThat(elasticsearch.currentReadIndexes()).containsExactlyElementsOf(live);
    }

    @Test
    void aliasMutationAndFailureAreMeasured() throws Exception {
        rebuild.rebuild();
        assertThat(metrics.get("search.alias.mutation.success").counter().count()).isGreaterThan(0);
        String missing = elasticsearch.createRebuildIndex();
        Set<String> live = new java.util.LinkedHashSet<>(elasticsearch.currentReadIndexes());
        elasticsearchClient.indices().delete(d -> d.index(missing));
        double failuresBefore = metrics.counter("search.alias.mutation.failure").count();
        assertThrows(InvocationTargetException.class, () -> {
            Method mutation = ElasticsearchProductSearch.class.getDeclaredMethod(
                "replaceAliasesWithSingleTarget", String.class, Set.class);
            mutation.setAccessible(true);
            mutation.invoke(elasticsearch, missing, live);
        });
        assertThat(metrics.counter("search.alias.mutation.failure").count()).isGreaterThan(failuresBefore);
    }

    @Test
    void reconciliationSkipReasonsAreMeasuredWithLowCardinalityTags() {
        jdbc.update("UPDATE search_rebuild_gate SET mode='REBUILDING',owner_id='skip-owner',claim_token='skip-token',lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)) WHERE id=1");
        assertThat(reconciler.runOnce()).isEqualTo(SearchRebuildReconciler.ReconcileResult.SKIPPED_GATE);
        jdbc.update("UPDATE search_rebuild_gate SET mode='OPEN',owner_id=NULL,claim_token=NULL,lease_until=NULL WHERE id=1");
        jdbc.update("INSERT INTO search_rebuild_intent(id,target_index,phase,owner_id,claim_token,generation,lease_until,created_at,updated_at) VALUES (?,?,?,?,?, ?,TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)),CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
            UUID.randomUUID().toString(), elasticsearch.createRebuildIndex(), "SWITCHING", "active-owner", "active-token", 1L);
        assertThat(reconciler.runOnce()).isEqualTo(SearchRebuildReconciler.ReconcileResult.SKIPPED_SWITCHING);
        assertThat(metrics.get("search.alias.reconciliation.skip").tag("reason", "gate").counter().count()).isGreaterThan(0);
        assertThat(metrics.get("search.alias.reconciliation.skip").tag("reason", "active_intent").counter().count()).isGreaterThan(0);
        assertThat(metrics.get("search.alias.reconciliation.skip").counter().getId().getTags())
            .noneMatch(tag -> tag.getKey().equals("owner"));
    }

    @Test
    void cleanupLiveProtectionIsMeasured() throws Exception {
        String target = elasticsearch.createRebuildIndex();
        cleanupRepository.schedule(target);
        elasticsearchClient.indices().updateAliases(a -> a
            .actions(x -> x.add(v -> v.index(target).alias(ProductSearchPort.READ_ALIAS)))
            .actions(x -> x.add(v -> v.index(target).alias(ProductSearchPort.WRITE_ALIAS))));
        cleanupWorker.runOnce();
        assertThat(metrics.get("search.alias.cleanup.live.protected").counter().count()).isGreaterThan(0);
    }

    @Test
    void reconciliationDoesNotInitializeAliasesWhileGateIsRebuilding() throws Exception {
        Set<String> liveMembers = new java.util.LinkedHashSet<>(elasticsearch.currentReadIndexes());
        liveMembers.addAll(elasticsearch.currentWriteIndexes());
        elasticsearchClient.indices().updateAliases(a -> {
            for (String member : liveMembers) {
                a.actions(x -> x.remove(v -> v.index(member).alias(ProductSearchPort.READ_ALIAS)));
                a.actions(x -> x.remove(v -> v.index(member).alias(ProductSearchPort.WRITE_ALIAS)));
            }
            return a;
        });
        jdbc.update("UPDATE search_rebuild_gate SET mode='REBUILDING',owner_id='quality-test',claim_token='quality-token',lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)) WHERE id=1");

        ElasticsearchProductSearch coldSearch = new ElasticsearchProductSearch(elasticsearchClient, jdbc);
        SearchRebuildReconciler coldReconciler = new SearchRebuildReconciler(
            new SearchGateRepository(new JdbcTemplate(dataSource)), new SearchAliasCoordinator(dataSource), coldSearch);
        try {
            assertThat(coldReconciler.runOnce()).isEqualTo(SearchRebuildReconciler.ReconcileResult.SKIPPED_GATE);
            assertThrows(RuntimeException.class, () -> elasticsearchClient.indices().getAlias(g -> g.name(ProductSearchPort.READ_ALIAS)));
            assertThrows(RuntimeException.class, () -> elasticsearchClient.indices().getAlias(g -> g.name(ProductSearchPort.WRITE_ALIAS)));
        } finally {
            jdbc.update("UPDATE search_rebuild_gate SET mode='OPEN',owner_id=NULL,claim_token=NULL,lease_until=NULL WHERE id=1");
            coldSearch.currentReadIndexes();
        }
    }

    @Test
    void reconciliationInitializesMissingAliasesOnOpenGateWithoutNestedCoordination() throws Exception {
        Set<String> liveMembers = new java.util.LinkedHashSet<>(elasticsearch.currentReadIndexes());
        liveMembers.addAll(elasticsearch.currentWriteIndexes());
        elasticsearchClient.indices().updateAliases(a -> {
            for (String member : liveMembers) {
                a.actions(x -> x.remove(v -> v.index(member).alias(ProductSearchPort.READ_ALIAS)));
                a.actions(x -> x.remove(v -> v.index(member).alias(ProductSearchPort.WRITE_ALIAS)));
            }
            return a;
        });
        jdbc.update("UPDATE search_rebuild_gate SET mode='OPEN',owner_id=NULL,claim_token=NULL,lease_until=NULL WHERE id=1");

        ElasticsearchProductSearch coldSearch = new ElasticsearchProductSearch(elasticsearchClient, jdbc);
        SearchRebuildReconciler coldReconciler = new SearchRebuildReconciler(
            new SearchGateRepository(new JdbcTemplate(dataSource)), new SearchAliasCoordinator(dataSource), coldSearch);
        assertThat(coldReconciler.runOnce()).isEqualTo(SearchRebuildReconciler.ReconcileResult.UNCHANGED);
        Set<String> readMembers = elasticsearchClient.indices().getAlias(g -> g.name(ProductSearchPort.READ_ALIAS)).result().keySet();
        Set<String> writeMembers = elasticsearchClient.indices().getAlias(g -> g.name(ProductSearchPort.WRITE_ALIAS)).result().keySet();
        assertThat(readMembers).hasSize(1);
        assertThat(writeMembers).containsExactlyElementsOf(readMembers);
    }

    @Test
    void rebuildDoesNotInitializeAliasesBeforeAcquiringGate() throws Exception {
        Set<String> liveMembers = new java.util.LinkedHashSet<>(elasticsearch.currentReadIndexes());
        liveMembers.addAll(elasticsearch.currentWriteIndexes());
        elasticsearchClient.indices().updateAliases(a -> {
            for (String member : liveMembers) {
                a.actions(x -> x.remove(v -> v.index(member).alias(ProductSearchPort.READ_ALIAS)));
                a.actions(x -> x.remove(v -> v.index(member).alias(ProductSearchPort.WRITE_ALIAS)));
            }
            return a;
        });

        SearchGateRepository existingOwner = new SearchGateRepository(new JdbcTemplate(dataSource));
        SearchGateRepository.Lease existingLease = existingOwner.acquire("existing-rebuild-owner", Duration.ofSeconds(30));
        ElasticsearchProductSearch coldSearch = new ElasticsearchProductSearch(elasticsearchClient, jdbc);
        SearchRebuildService blockedRebuild = new SearchRebuildService(jdbc, projector, coldSearch, transactionManager,
            new SearchGateRepository(new JdbcTemplate(dataSource)));
        try {
            assertThrows(RuntimeException.class, blockedRebuild::rebuild);
            assertThrows(RuntimeException.class, () -> elasticsearchClient.indices().getAlias(g -> g.name(ProductSearchPort.READ_ALIAS)));
            assertThrows(RuntimeException.class, () -> elasticsearchClient.indices().getAlias(g -> g.name(ProductSearchPort.WRITE_ALIAS)));
        } finally {
            existingOwner.release(existingLease);
            jdbc.update("UPDATE search_rebuild_gate SET mode='OPEN',owner_id=NULL,claim_token=NULL,lease_until=NULL WHERE id=1");
            coldSearch.currentReadIndexes();
        }
    }

    @Test
    void reconcilerCannotStealFreshRebuildBeforeGateAcquire() throws Exception {
        CountDownLatch reachedGateBarrier = new CountDownLatch(1);
        CountDownLatch releaseGateBarrier = new CountDownLatch(1);
        SearchRebuildService guarded = new SearchRebuildService(jdbc, projector, elasticsearch, transactionManager,
            new SearchGateRepository(new JdbcTemplate(dataSource)), () -> {
                reachedGateBarrier.countDown();
                awaitBarrier(reachedGateBarrier, releaseGateBarrier);
            });
        SearchRebuildReconciler reconciler = new SearchRebuildReconciler(
            new SearchGateRepository(new JdbcTemplate(dataSource)), new SearchAliasCoordinator(dataSource), elasticsearch);
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<SearchRebuildService.RebuildReport> rebuilding = workers.submit(guarded::rebuild);
            assertThat(reachedGateBarrier.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(reconciler.runOnce()).isEqualTo(SearchRebuildReconciler.ReconcileResult.UNCHANGED);
            releaseGateBarrier.countDown();
            assertThat(rebuilding.get(30, TimeUnit.SECONDS).index()).isNotBlank();
        } finally {
            releaseGateBarrier.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void doneCleanupRowIsRestagedForNextCutover() {
        SearchRebuildService.RebuildReport first = rebuild.rebuild();
        assertThat(jdbc.queryForObject("SELECT status FROM search_index_cleanup_task WHERE index_name=?", String.class, first.index()))
            .isEqualTo("DONE");

        SearchRebuildService.RebuildReport second = rebuild.rebuild();
        assertThat(second.index()).isNotEqualTo(first.index());
        assertThat(jdbc.queryForObject("SELECT status FROM search_index_cleanup_task WHERE index_name=?", String.class, first.index()))
            .isEqualTo("NEW");
    }

    private Object newReconcilerOrNull() {
        try {
            Class<?> type = Class.forName("com.example.campusmarket.catalog.search.SearchRebuildReconciler");
            SearchAliasCoordinator coordinator = new SearchAliasCoordinator(dataSource);
            Object[] available = { jdbc, new SearchGateRepository(new JdbcTemplate(dataSource)), coordinator, elasticsearch };
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                Class<?>[] parameterTypes = constructor.getParameterTypes();
                Object[] arguments = new Object[parameterTypes.length];
                boolean matched = true;
                for (int i = 0; i < parameterTypes.length; i++) {
                    Class<?> parameterType = parameterTypes[i];
                    arguments[i] = Arrays.stream(available).filter(candidate -> parameterType.isInstance(candidate)).findFirst().orElse(null);
                    if (arguments[i] == null) matched = false;
                }
                if (matched) {
                    constructor.setAccessible(true);
                    return constructor.newInstance(arguments);
                }
            }
            return null;
        } catch (ReflectiveOperationException failure) {
            return null;
        }
    }

    private static void invokeReconcilerHook(Object reconciler, Runnable hook) {
        try {
            Method method = Arrays.stream(reconciler.getClass().getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("runOnce") && candidate.getParameterCount() == 1)
                .findFirst().orElseThrow();
            method.setAccessible(true);
            method.invoke(reconciler, hook);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
