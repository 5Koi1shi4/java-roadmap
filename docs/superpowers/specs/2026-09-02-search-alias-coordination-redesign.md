# 搜索别名协调协议结构性重构设计

## 1. 背景与目标

实验七的 Elasticsearch 在线重建已具备一致性快照、Outbox 高水位补放、外部版本、持久 tombstone、PIT 分页、重建 intent、清理任务和 DB 租约，但现有实现把 MySQL gate、MySQL named lock 与 Elasticsearch alias/delete 分散在多个对象中。五轮修复后的最终复审仍证明以下插入窗口存在：

- reconciliation 读到 gate `OPEN` 后，新重建可在 ES alias mutation 前取得 gate；
- rebuild 最后一次 fence 通过后，租约可在 ES 请求前被接管；
- cleanup 读到索引非 live 后，该索引可在 ES delete 前变为 live；
-部分路径只读取 alias 的第一个成员；
-持有 named-lock 专用连接时，临界区 SQL 再向连接池借连接会在池大小为 1 时自我等待。

本重构将这些操作归并为一个跨实例线性化协议。目标不是增加搜索功能，而是保证任何时刻都不会因并发重建、恢复或清理而把 alias 回退到未授权代际、留下多个成员，或删除 live 索引。

## 2. 选择与非目标

采用 MySQL `GET_LOCK('campus-market:search-alias-coordination', timeout)` 作为跨实例协调锁。所有 gate 状态迁移、alias mutation 和受 alias 影响的 index delete 都必须经过同一个协调器。

不采用以下方案：

- 不用 JVM 本地锁，因为不能覆盖多实例；
- 不用数据库行锁事务包住 ES I/O，因为会产生长事务并阻塞租约可见性；
- 不接受仅靠 reconciliation 的最终一致性，因为它允许 alias 在窗口期回退；
- 不引入 Redis 锁或新的基础设施依赖。

本设计不改变商品搜索 API、索引文档格式、Outbox 高水位、PIT 游标、库存事实源或 Task 8 之后的业务范围。

## 3. 核心不变量

1. `search-read` 和 `search-write` 各自恰有一个 concrete index 成员，并且二者指向同一 target；`search-write` 只有一个 write index。
2. 只有持有统一协调锁的代码可以执行 ES alias mutation 或删除可能成为 alias 成员的 rebuild index。
3. `OPEN -> REBUILDING`、过期 owner takeover 和 `REBUILDING -> OPEN` 的 gate 状态迁移都在统一协调锁内完成。
4. alias mutation 前的 gate、owner、gate token、intent token、generation 和 lease 校验，必须使用持有 named lock 的同一物理 JDBC `Connection`；校验通过到 ES 请求发出之间不存在另一个 gate acquire/takeover。
5. reconciliation 在统一协调锁内重新读取 gate、活动 intent 和 read/write alias 完整成员集合；gate 非 `OPEN` 或存在未过期 `SWITCHING` 时不得修改 alias。
6. cleanup 在统一协调锁内重新读取完整 alias 成员集合并完成 ES delete；检查与删除之间不能插入 alias mutation。
7. named lock 临界区不开启数据库事务；SQL 使用持锁的同一物理连接自动提交，避免长事务和连接池二次借用。
8. named lock 必须有有限等待，并在同一连接的 `finally` 中释放；连接异常或进程退出由 MySQL 自动释放会话锁。

## 4. 组件边界

### 4.1 `SearchAliasCoordinator`

新增独立基础设施组件，唯一职责是管理 named lock 及固定连接临界区：

```java
public final class SearchAliasCoordinator {
    public <T> T execute(Duration timeout, AliasCriticalSection<T> section);

    @FunctionalInterface
    public interface AliasCriticalSection<T> {
        T run(Connection connection) throws Exception;
    }
}
```

`execute` 从 `DataSource` 获取一条物理连接，设置 `autoCommit=true`，调用 `GET_LOCK`，执行 section，最后在同一连接调用 `RELEASE_LOCK`。获取超时、SQL 失败和释放失败统一转换为可观测的协调异常；释放失败不得覆盖业务主异常，而应作为 suppressed exception 或告警记录。

### 4.2 `SearchGateRepository`

gate repository 不再自行完成可与 alias mutation 并发的状态迁移。新增接受固定 `Connection` 的 package-private SQL 方法，由协调器临界区调用：

```java
Lease acquire(Connection connection, String owner, Duration lease);
boolean renew(Connection connection, Lease lease, Duration extension);
int release(Connection connection, Lease lease);
void assertLease(Connection connection, Lease lease);
```

生产入口 `acquire/release` 必须通过 `SearchAliasCoordinator.execute` 包装。长时间 fill/replay 的 heartbeat renew 仍可使用短自动提交 SQL；但 takeover 必须先取得统一协调锁，因此不能越过正在进行的 alias mutation。

### 4.3 alias storage adapter

`ElasticsearchProductSearch` 保留 ES 查询与 mutation 实现，但不再公开无 fence 的 alias 切换入口。alias 更新只接受协调器临界区构造出的授权对象，并在临界区内读取 read/write 两个 alias 的完整 `Set<String>` 后发出一次 `_aliases` 请求：移除全部现有成员，再添加唯一 target。

测试需要制造漂移时直接使用测试持有的 `ElasticsearchClient`，不能保留生产 public bypass。

### 4.4 reconciliation

reconciliation 每次在统一协调锁内执行以下顺序：

1. 用固定连接读取 gate；非 `OPEN` 立即退出；
2. 用固定连接读取所有未过期 `SWITCHING` intent；存在则退出；
3. 选择最高成功 generation；
4. 读取 read/write alias 完整成员集合；
5. 只有集合不等于 `{authoritativeTarget}` 时才执行单成员 alias mutation；
6. alias 成功后以短自动提交 SQL 更新 cleanup/intent；若后续 SQL 失败，下一次 reconciliation 重复同一幂等 alias mutation并继续收敛。

`reconcileRebuildIntents` 的所有 live 判断也使用 read/write 完整集合的并集，不再调用 `currentReadIndex/currentWriteIndex`。

### 4.5 cleanup

cleanup 的 claim 与完成/失败 CAS 仍是短自动提交 SQL，ES delete 不加入 Spring transaction。claim 成功后，worker 进入统一协调锁：

1. 用固定连接重新验证 cleanup owner、claim token 与未过期 lease；
2. 读取完整 alias 成员集合；若 target live，则 CAS 为 `DONE` 并退出；
3. target 非 live 时在仍持有协调锁的情况下调用 ES delete；
4. ES 返回后用固定连接或显式短 SQL CAS 完成；过期旧 owner 的 CAS 必须为 0。

`cleanupPending` 使用 `PROPAGATION_NOT_SUPPORTED` 或独立非事务 worker bean，确保调用方外层事务不能包住 ES I/O。claim、完成和失败更新使用 `REQUIRES_NEW` 小方法或固定连接自动提交。

## 5. 并发时序

### 5.1 旧重建 alias 请求与新 owner takeover

旧 owner A 取得协调锁后，新 owner B 即使观察到 gate lease 已过期，也必须等待同一 named lock，不能完成 takeover。A 在固定连接上执行最后 fence；若此时仍是合法 owner，则完成一次单成员 alias mutation并释放锁。B 随后取得锁、执行 takeover并开始新 generation。若 A 在取得锁前已经失效，则 fence 拒绝且不发送 ES 请求。

因此，一旦 B 的 rebuild 成功返回，不存在更早的 A 请求仍能在其后修改 alias。

### 5.2 reconciliation 与新重建

reconciliation 与 gate acquire 使用同一 named lock。若 reconciliation 先取得锁，它完成 gate `OPEN` 检查和必要 alias 修复后释放，B 才能把 gate 改为 `REBUILDING`。若 B 先取得锁并改变 gate，reconciliation 后取得锁时会看到 `REBUILDING` 并退出。不存在检查后被 gate acquire 插入的窗口。

### 5.3 cleanup 与 alias 切换

cleanup 的 live-set 检查和 ES delete 与 alias mutation共用同一 named lock。若 cleanup 先取得锁并确认非 live，它删除后才允许后续 alias mutation；alias mutation必须验证 target index 存在，不能把已删除索引设为 live。若 cutover 先取得锁，cleanup 后取得锁时会在完整成员集合中看到 target 并禁止删除。

## 6. 失败处理与可观测性

- named lock 获取超过 30 秒：本轮 worker 返回可重试协调失败，不消费额外业务重试次数；记录 lock name、operation、owner 和等待时间。
- ES alias/delete 超时：协调锁在请求返回或抛错后释放；gate/intent/cleanup 保持可恢复状态，由后续同协议 worker 重试。
- `RELEASE_LOCK` 返回 0/NULL：记录 error 指标；若已有主异常，将释放异常附加为 suppressed，不覆盖根因。
-固定连接断开：MySQL 自动释放 named lock；当前操作失败，持久状态由 lease/intent/cleanup reconciliation 接管。
-连接池最小值不作特殊要求；临界区内不得通过 `JdbcTemplate` 二次借连接，因此 `maximumPoolSize=1` 也不自锁。

新增指标：named lock 获取耗时/超时、alias mutation成功/失败、reconciliation因 gate 或 active intent 跳过、cleanup live保护、释放失败。

## 7. 测试设计

所有并发测试使用 latch/barrier，不使用 `sleep`：

1. A 在协调锁内读完 alias 后暂停；A lease 过期，B 尝试 takeover但无法完成；释放 A 后验证 A/B严格串行，最终 alias单成员且旧 owner不能迟到修改。
2. reconciliation 读到 gate `OPEN` 后暂停；B acquire 必须阻塞，reconciliation完成后 B才进入 `REBUILDING`。
3. B 已进入 `REBUILDING` 或有 live `SWITCHING` intent 时，reconciliation 不执行 ES alias mutation。
4. 构造 read/write alias 多成员且目标位于非首位，所有 reconciliation 与 cleanup 判断仍正确。
5. cleanup 在完整 live-set检查后暂停；并发 cutover 必须等待；验证不会删除 live target。
6. cleanup ES delete 阻塞时，claim 已提交且第二连接可观察；lease过期后旧 owner完成 CAS为0。
7. Hikari `maximumPoolSize=1` 下完成 fenced alias mutation，不发生二次借连接等待。
8. named lock acquire timeout、固定连接断开和 release失败路径可观测且不泄漏锁。
9. FILL、REFRESH_FILL、REPLAY、REFRESH_REPLAY、ALIAS_SWAP故障恢复继续覆盖。
10. 原有 ProductSearchIT、SchemaIT、InventoryIT、ListingMediaIT、PIT、high-water、tombstone 和 outbox rollback全部回归，0 skipped。

## 8. 完成标准

- 生产代码不存在公开无 fence alias mutation旁路；
- gate acquire/takeover/release、reconciliation alias mutation和cleanup delete全部走统一协调器；
-临界区 SQL 全部复用持锁物理连接且不开长事务；
-上述确定性并发测试全部通过，完整组合测试 0 failures、0 errors、0 skipped；
-独立规格与质量复审无 Critical/Important；
-进度账本记录旧五轮 breaker与新协议重构的独立提交范围。
