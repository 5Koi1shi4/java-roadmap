# Search Alias Coordination Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the split gate/alias/cleanup protocol with one cross-instance linearization boundary so rebuild, reconciliation, and cleanup cannot interleave unsafe Elasticsearch side effects.

**Architecture:** Add a `SearchAliasCoordinator` that owns one MySQL named lock on one physical auto-commit connection. Gate transitions, fenced alias cutover, reconciliation alias repair, and rebuild-index deletion all enter that coordinator; connection-scoped SQL reuses the held connection, while Elasticsearch I/O runs without a database transaction. Alias decisions use complete read/write member sets, and public unfenced mutation entry points are removed.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring JDBC, HikariCP, MySQL 8.4 `GET_LOCK`, Elasticsearch Java client 8.18, JUnit 5, AssertJ, Testcontainers, Toxiproxy.

## Global Constraints

- Work only in `labs/07-campus-market`; preserve Tasks 1–6 behavior and the existing Task 7 search API.
- MySQL remains the product fact source; Elasticsearch remains rebuildable and must never be used for inventory decisions.
- Do not add Redis locking, Redisson, ZooKeeper, or another infrastructure dependency.
- Do not hold a database transaction open across Elasticsearch I/O.
- All concurrency tests use latches/barriers, not timing sleeps.
- Keep money, inventory, JWT, media, Outbox/Inbox, PIT, external-gte, tombstone, and high-water semantics unchanged.
- All required unit and integration tests must report 0 failures, 0 errors, and 0 skipped.

---

### Task 1: Add the fixed-connection cross-instance coordinator

**Files:**
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/search/SearchAliasCoordinator.java`
- Modify: `labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/search/SearchGateRepository.java`
- Modify: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/SearchRebuildIT.java`

**Interfaces:**
- Consumes: the existing application `DataSource` and `SearchGateRepository.Lease` value type.
- Produces: `SearchAliasCoordinator.execute(Duration, CriticalSection<T>)`; connection-scoped gate methods `acquire(Connection,...)`, `assertLease(Connection,...)`, and `release(Connection,...)`.

- [ ] **Step 1: Write RED tests for lock serialization, one-connection pools, and release**

Add deterministic tests that create two coordinator instances over the same MySQL container. The first section holds the named lock behind a latch; assert the second section has entered its worker but cannot pass its callback until release. Add a Hikari datasource with `maximumPoolSize=1` and prove the callback can execute `SELECT 1` through the supplied `Connection` without borrowing another connection.

```java
SearchAliasCoordinator first = new SearchAliasCoordinator(dataSource);
SearchAliasCoordinator second = new SearchAliasCoordinator(dataSource);
CountDownLatch locked = new CountDownLatch(1);
CountDownLatch release = new CountDownLatch(1);

Future<Void> owner = workers.submit(() -> first.execute(Duration.ofSeconds(5), connection -> {
    locked.countDown();
    awaitBarrier(locked, release);
    return null;
}));
assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
Future<Integer> waiter = workers.submit(() -> second.execute(Duration.ofSeconds(5), connection ->
    queryInt(connection, "SELECT 1")));
assertThat(waiter.isDone()).isFalse();
release.countDown();
owner.get(30, TimeUnit.SECONDS);
assertThat(waiter.get(30, TimeUnit.SECONDS)).isEqualTo(1);
```

Add this test helper in `SearchRebuildIT`:

```java
private static int queryInt(Connection connection, String sql) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new SQLException("查询未返回结果");
        return result.getInt(1);
    }
}
```

- [ ] **Step 2: Run the coordinator tests and verify RED**

Run:

```powershell
.\mvnw.cmd '-Dit.test=SearchRebuildIT#aliasCoordinatorSerializesWorkers+aliasCoordinatorWorksWithPoolSizeOne' verify
```

Expected: test compilation fails because `SearchAliasCoordinator` does not exist.

- [ ] **Step 3: Implement `SearchAliasCoordinator`**

Implement a Spring component with this contract:

```java
@Component
public final class SearchAliasCoordinator {
    static final String LOCK_NAME = "campus-market:search-alias-coordination";
    private final DataSource dataSource;

    public <T> T execute(Duration timeout, CriticalSection<T> section) {
        // validate timeout: > 0 and <= 60 seconds
        // 获取一个 Connection，并设置 autoCommit=true
        // 执行 SELECT GET_LOCK(LOCK_NAME, timeoutSeconds)
        // 使用同一个 Connection 执行临界区
        // 最后仍在同一个 Connection 上执行 SELECT RELEASE_LOCK(LOCK_NAME)
        // 保留主异常，并将释放锁失败作为 suppressed 异常附加
    }

    @FunctionalInterface
    public interface CriticalSection<T> {
        T run(Connection connection) throws Exception;
    }
}
```

Use `PreparedStatement` directly. Treat `GET_LOCK` result other than `1` as a bounded coordination failure. Treat `RELEASE_LOCK` result other than `1` as a release failure. Do not use `JdbcTemplate` inside this class.

- [ ] **Step 4: Move gate state transitions under the coordinator**

Make public `SearchGateRepository.acquire` and `release` enter `SearchAliasCoordinator.execute`. Add package-private connection-scoped implementations and reuse them from alias critical sections:

```java
Lease acquire(Connection connection, String owner, Duration leaseDuration);
void assertLease(Connection connection, Lease lease);
int release(Connection connection, Lease lease);
GateState readState(Connection connection);
```

Define `GateState` as `record GateState(String mode, String owner, String token, long generation, Timestamp leaseUntil)` with `boolean isOpen()` returning `"OPEN".equals(mode)`.

`acquire(Connection,...)` performs the current owner/token/generation/DB-time CAS with `PreparedStatement`; it must not call `JdbcTemplate`. Heartbeat `renew(Lease,Duration)` remains a short auto-commit operation because it does not grant a new owner.

- [ ] **Step 5: Run Task 1 tests**

Run:

```powershell
.\mvnw.cmd '-Dit.test=SearchRebuildIT' verify
```

Expected: all SearchRebuildIT cases pass, including coordinator serialization and pool-size-one coverage; 0 skipped.

- [ ] **Step 6: Commit Task 1**

```powershell
git add labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/search/SearchAliasCoordinator.java labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/search/SearchGateRepository.java labs/07-campus-market/src/test/java/com/example/campusmarket/integration/SearchRebuildIT.java
git commit -m "refactor(campus-market): unify search coordination lock"
```

---

### Task 2: Make rebuild cutover and reconciliation linearizable

**Files:**
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/search/SearchRebuildReconciler.java`
- Modify: `labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/search/ElasticsearchProductSearch.java`
- Modify: `labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/search/SearchRebuildService.java`
- Modify: `labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/search/SearchOutboxScheduler.java`
- Modify: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/SearchRebuildIT.java`

**Interfaces:**
- Consumes: `SearchAliasCoordinator.execute`, connection-scoped gate methods, existing rebuild intent fields, and Elasticsearch read/write aliases.
- Produces: one fenced cutover entry point used by `SearchRebuildService`; `SearchRebuildReconciler.runOnce()` and package-private `runOnce(Runnable afterGateRead)`; full-set reconciliation; no public unfenced alias mutation API.

- [ ] **Step 1: Write the exact gate/reconciliation RED interleaving**

Add a reconciliation test seam that pauses after it reads gate `OPEN` while holding the coordinator. Start a new gate acquire on another worker and prove it cannot return until reconciliation finishes. Then assert reconciliation cannot mutate after the gate becomes `REBUILDING`.

```java
Future<Void> repairing = workers.submit(() -> {
    reconciler.runOnce(() -> {
        gateObservedOpen.countDown();
        awaitBarrier(gateObservedOpen, finishRepair);
    });
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
```

- [ ] **Step 2: Write RED tests for full alias sets and removal of bypasses**

Construct drift with two read/write members through the test-owned raw `ElasticsearchClient`. Assert reconciliation reduces both aliases to exactly the authoritative target even when that target is not the first returned key. Replace all test calls to production `switchAliases(...)` or unfenced overloads with either the fenced rebuild service or raw test client setup. Add a reflection/API assertion that no public two-argument legacy switch method and no public unfenced single-target switch method remain.

- [ ] **Step 3: Run the new tests and verify RED**

Run:

```powershell
.\mvnw.cmd '-Dit.test=SearchRebuildIT#reconciliationAndGateAcquireShareOneLinearizationPoint+driftedAliasesUseCompleteMemberSets+productionAliasApiHasNoUnfencedBypass' verify
```

Expected: at least the gate-acquire interleaving or public API assertion fails against `af2c6fc`.

- [ ] **Step 4: Implement one fenced cutover critical section**

In `SearchRebuildService`, replace the split gate check and alias call with one coordinator callback:

```java
AliasTransition transition = coordinator.execute(Duration.ofSeconds(30), connection -> {
    gate.assertLease(connection, lease);
    elasticsearch.assertSwitchingIntent(connection, target, owner, switchToken, lease.generation());
    Set<String> live = elasticsearch.readAllAliasMembers();
    return elasticsearch.replaceAliasesWithSingleTarget(target, live);
});
```

The callback must use the supplied `Connection` for gate and intent SQL. `replaceAliasesWithSingleTarget` is package-private and callable only from code already inside the coordinator. Remove `switchAliases(String,String)` and the public unfenced `switchAliasesToSingleLive(String)` overload.

- [ ] **Step 5: Move reconciliation decisions into the same coordinator**

Create `SearchRebuildReconciler`. Its production `runOnce()` delegates to a package-private hook overload used only by deterministic integration tests. Move `reconcileLatestSuccessfulGeneration` and `reconcileRebuildIntents` out of `ElasticsearchProductSearch`. Within one coordinator callback:

```java
SearchGateRepository.GateState state = gate.readState(connection);
if (!state.isOpen()) return ReconcileResult.SKIPPED_GATE;
if (intentRepository.hasLiveSwitching(connection)) return ReconcileResult.SKIPPED_SWITCHING;
RebuildIntent authoritative = intentRepository.latestSuccessful(connection);
Set<String> live = elasticsearch.readAllAliasMembers();
if (live.equals(Set.of(authoritative.target()))) return ReconcileResult.UNCHANGED;
elasticsearch.replaceAliasesWithSingleTarget(authoritative.target(), live);
return ReconcileResult.REPAIRED;
```

Define `ReconcileResult` inside `SearchRebuildReconciler` as `SKIPPED_GATE`, `SKIPPED_SWITCHING`, `UNCHANGED`, and `REPAIRED`.

Update every path in `reconcileRebuildIntents` to use the complete union of read/write members. Remove safety decisions based on `currentReadIndex()` or `currentWriteIndex()`.

Update `SearchOutboxScheduler` to invoke `SearchRebuildReconciler.runOnce()` before the existing cleanup entry point. Task 3 will replace that cleanup entry point without changing reconciliation.

- [ ] **Step 6: Run Task 2 tests**

Run:

```powershell
.\mvnw.cmd '-Dit.test=ProductSearchIT,SearchRebuildIT' verify
```

Expected: ProductSearchIT and SearchRebuildIT pass with 0 skipped; read/write aliases each contain exactly one identical target after every rebuild/reconciliation case.

- [ ] **Step 7: Commit Task 2**

```powershell
git add labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/search/SearchRebuildReconciler.java labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/search/ElasticsearchProductSearch.java labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/search/SearchRebuildService.java labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/search/SearchOutboxScheduler.java labs/07-campus-market/src/test/java/com/example/campusmarket/integration/SearchRebuildIT.java
git commit -m "fix(campus-market): linearize search alias cutover"
```

---

### Task 3: Serialize cleanup live checks and deletion without long transactions

**Files:**
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/search/SearchIndexCleanupRepository.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/search/SearchIndexCleanupWorker.java`
- Modify: `labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/search/ElasticsearchProductSearch.java`
- Modify: `labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/search/SearchOutboxScheduler.java`
- Modify: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/SearchRebuildIT.java`

**Interfaces:**
- Consumes: cleanup task rows, `SearchAliasCoordinator`, complete alias-member reads, and Elasticsearch delete.
- Produces: `SearchIndexCleanupRepository.CleanupClaim`; `SearchIndexCleanupRepository.claimBatch/owned/complete/fail`; a non-transactional production `SearchIndexCleanupWorker.runOnce()` with short claim/CAS operations and a coordinator-protected live-check/delete critical section.

- [ ] **Step 1: Write RED cleanup/cutover and transaction-boundary tests**

Pause cleanup after it reads a target as non-live while holding the coordinator. Start a fenced cutover that wants that target and assert the cutover cannot pass until cleanup completes. Separately, invoke `runOnce()` from an outer `TransactionTemplate` and verify the worker suspends the caller transaction before reaching the ES delete barrier.

```java
assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
cleanupWorker.runOnce();
// Inside cleanupDeleteHook:
assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); // 已暂停调用方事务
```

Retain the existing two-worker lease-expiry test and assert the stale owner's completion CAS changes zero rows.

- [ ] **Step 2: Run cleanup tests and verify RED**

Run:

```powershell
.\mvnw.cmd '-Dit.test=SearchRebuildIT#cleanupAndCutoverShareCoordinator+cleanupSuspendsCallerTransaction+cleanupClaimIsVisibleAndStaleCompletionIsFenced' verify
```

Expected: cleanup/cutover serialization or transaction suspension fails against the current public `cleanupPending()` implementation.

- [ ] **Step 3: Implement `SearchIndexCleanupWorker`**

Use a separate Spring bean so proxy semantics are explicit:

```java
@Service
public class SearchIndexCleanupWorker {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int runOnce() {
        List<CleanupClaim> claims = repository.claimBatch(20); // REQUIRES_NEW bean
        for (CleanupClaim claim : claims) cleanupOne(claim); // 逐个处理已领取的清理任务
        return claims.size();
    }

    private void cleanupOne(CleanupClaim claim) {
        coordinator.execute(Duration.ofSeconds(30), connection -> {
            if (!repository.owned(connection, claim)) return null;
            Set<String> live = elasticsearch.readAllAliasMembers();
            if (live.contains(claim.indexName())) {
                repository.completeProtected(connection, claim);
                return null;
            }
            elasticsearch.deleteIndex(claim.indexName());
            repository.complete(connection, claim);
            return null;
        });
    }
}
```

Implement `SearchIndexCleanupRepository` as a separate Spring bean. `claimBatch`, `complete`, and `fail` use `@Transactional(propagation = Propagation.REQUIRES_NEW)`; `owned(Connection,CleanupClaim)` and `complete(Connection,CleanupClaim)` use the supplied physical connection inside the coordinator. Do not use self-invoked `@Transactional` methods.

Define its value type explicitly:

```java
public record CleanupClaim(
    String id, String indexName, int attemptCount,
    String owner, String token, Timestamp leaseUntil) { }
```

- [ ] **Step 4: Remove cleanup ownership from the Elasticsearch adapter**

`ElasticsearchProductSearch` should expose package-private ES primitives (`readAllAliasMembers`, `deleteIndex`) and no longer own the public scheduler workflow. Update `SearchOutboxScheduler` to call `SearchIndexCleanupWorker.runOnce()`.

- [ ] **Step 5: Run Task 3 tests**

Run:

```powershell
.\mvnw.cmd '-Dit.test=SearchRebuildIT,SchemaIT' verify
```

Expected: cleanup concurrency, stale owner, attempt limit, schema, and transaction-boundary cases pass with 0 skipped.

- [ ] **Step 6: Commit Task 3**

```powershell
git add labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/search/SearchIndexCleanupRepository.java labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/search/SearchIndexCleanupWorker.java labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/search/ElasticsearchProductSearch.java labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/search/SearchOutboxScheduler.java labs/07-campus-market/src/test/java/com/example/campusmarket/integration/SearchRebuildIT.java
git commit -m "fix(campus-market): serialize search index cleanup"
```

---

### Task 4: Run the structural acceptance suite and document the protocol

**Files:**
- Create: `docs/superpowers/reports/2026-09-02-search-alias-coordination-verification.md`
- Create: `E:/test/work/java-roadmap/.superpowers/sdd/2026-08-30-campus-market/task-7-redesign-report.md`
- Modify: `E:/test/work/java-roadmap/.superpowers/sdd/2026-08-30-campus-market/progress.md`

**Interfaces:**
- Consumes: Tasks 1–3 commits and the existing Maven/Testcontainers environment.
- Produces: complete executable evidence, a protocol report, and a clean independent-review package.

- [ ] **Step 1: Run focused concurrency tests three times**

Run the complete `SearchRebuildIT` three times in separate Maven invocations:

```powershell
1..3 | ForEach-Object { .\mvnw.cmd '-Dit.test=SearchRebuildIT' verify; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE } }
```

Expected each run: all SearchRebuildIT cases pass, 0 skipped, no named-lock timeout, no leaked lock, and aliases end with one shared target.

- [ ] **Step 2: Run the required combined regression**

Run:

```powershell
.\mvnw.cmd '-Dit.test=ProductSearchIT,SearchRebuildIT,SchemaIT,InventoryIT,ListingMediaIT' verify
```

Expected: unit and all selected integration tests pass with 0 failures, 0 errors, and 0 skipped; Flyway applies V1–V12.

- [ ] **Step 3: Check repository hygiene**

Run:

```powershell
git diff --check
git status --short
```

Expected: no whitespace errors and only the intended report/ledger documentation changes remain uncommitted.

- [ ] **Step 4: Write the redesign evidence report**

Record exact commits, RED failures, GREEN test counts, the three repeated SearchRebuildIT results, combined regression counts, Docker/MySQL/Elasticsearch versions, named-lock timeout/release behavior, and any residual concern. Do not state Task 7 is complete before independent review.

- [ ] **Step 5: Commit Task 4 verification report**

```powershell
git add docs/superpowers/reports/2026-09-02-search-alias-coordination-verification.md
git commit -m "test(campus-market): record alias coordination verification"
```

- [ ] **Step 6: Request independent final review**

Create a review package spanning `8e29c80..<Task 3 head>` and require separate Standards and Spec verdicts. Approval requires no Critical/Important finding in alias linearization, gate transitions, full-set reconciliation, cleanup deletion, transaction boundaries, pool-size-one operation, and failure recovery.
