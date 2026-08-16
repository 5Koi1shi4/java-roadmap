# 实验二：Redis Cache Aside 与更新一致性

本实验以商品详情为例实现 Cache Aside，并把“更新数据库后删除缓存”限定为**数据库事务提交后**才执行。缓存键为 `product:v1:{id}`；正缓存与空值缓存都通过同一个 `evict(id)` 失效。

## 更新时序

```text
请求更新商品
  -> ProductUpdateService.updateProduct(command)
  -> ProductRepository.update(product)                 （仍在数据库事务内）
  -> 注册 TransactionSynchronization.afterCommit 回调
  -> 数据库事务提交成功
  -> ProductCache.evict(productId)
```

若 repository 更新抛异常或事务回滚，`afterCommit` 不会执行，旧缓存保留；读取方继续看到与未提交数据库状态一致的旧数据。

不能在更新数据库**之前**先删缓存：删除后到数据库提交前，其他请求可能发生缓存未命中并从数据库读回旧数据，再把旧值写入缓存。随后数据库即使成功提交，缓存仍可能是过期旧值；而数据库最终回滚时，预先删除还会无谓丢失原本正确的缓存。因此先更新数据库、只在提交完成后删除缓存，才把缓存失效与已提交状态对齐。

## 热点 Key 缓存重建（本地互斥）

缓存未命中时，`ProductQueryService` 按 product id 在**单个 JVM 内**协调重建，避免同一热点 Key 的大量并发请求同时穿透到数据库：

```text
缓存首次读取未命中
  -> 按 id 保留带参与计数的 ReentrantLock
  -> 最多等待 200ms 获取锁
  -> 获取锁后再次读取缓存（二次检查）
  -> 仍未命中时，只有持锁请求访问数据库并回填缓存/空值缓存
  -> finally 解锁，并减少参与计数；最后一个参与者移除该 id 的锁引用
```

二次检查是必要的：等待者在等待锁期间，前一个请求可能已经完成数据库查询并写入缓存；此时等待者直接返回缓存，不能再次回源。未能于 200ms 内取得锁的请求会再读一次缓存；若仍未命中，返回“系统繁忙，请稍后重试”，以受控失败代替无限等待或热点穿透。线程被中断时会恢复中断标记并走同一受控失败语义。

锁表使用 `ConcurrentHashMap<Long, RebuildLock>`，而不是永久保留每个访问过的 Key。每个进入重建路径的请求先增加参与计数，退出时在 `finally` 中减少；计数归零才移除锁对象。因此，排队请求到达期间同一 Key 始终复用同一把锁，同时大量一次性 Key 不会让本地锁表无界增长。

这是本地互斥：它只抑制同一应用实例内的回源。多实例场景仍可能各自重建，需要结合分布式锁、逻辑过期/异步刷新、限流和数据库保护策略评估；不应把它误写成跨节点的强一致方案。

## 并发测试证据

`ProductQueryServiceTest.rebuildsAnExpiredHotKeyWithExactlyOneRepositoryLookup` 用 100 个线程和阶段闩锁固定时序：全部请求先完成首次缓存读取并同时看到未命中，再开始竞争重建；断言 100 个请求均得到商品、仓储 `findById` 恰好调用 1 次，且结束后锁表为空。`ProductQueryServiceLockLifecycleTest.retainsOneLockWhileQueuedRequestsArriveDuringARebuild` 用阶段闩锁确认第二、第三个请求在首个重建尚未完成时已经保留锁引用，仍只使用一把锁；释放回源后所有请求成功，最终锁表回收。另有超时测试覆盖重建超过 200ms 时等待请求返回受控“系统繁忙”。

**P2 后续项：** 为锁等待超时、回源耗时、锁表大小和缓存未命中建立指标与告警；压测评估 200ms 是否符合接口 SLA；多实例部署时再按一致性与成本选择分布式协调或逻辑过期方案，并在数据库侧配合限流、熔断和降级。

## 失败边界

缓存删除发生在 `afterCommit()`，因此 Redis 删除失败不能回滚已提交的数据库事务。`ProductUpdateServiceTest.keepsUpdatedProductWhenCacheEvictionFailsAfterCommit` 让 `ProductCache.evict` 抛出 `redis unavailable`，断言提交回调抛错后仓储中仍是更新后的 `Effective Java` 商品。这是“数据库已提交、缓存删除需走观测与重试”的单元测试证据，而不是分布式事务实现。

## 本地运行

先基于示例创建只保存在本地的 Docker 密码文件：

```powershell
Copy-Item .env.example .env
# 编辑 .env，将两个 replace-with-a-... 值改成仅用于本机的不同密码。
docker compose up -d
docker compose ps
```

MySQL 与 Redis 健康后，JDK 17 下启动应用：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\mvnw.cmd spring-boot:run
```

默认连接为 `localhost:3306/redis_cache`（用户取自 `.env` 的 `MYSQL_USERNAME`，默认 `cache`）与 `localhost:6379`。密码只从未提交的 `.env`/环境变量读取。可用以下端点检查运行态：

```powershell
Invoke-WebRequest http://localhost:8080/api/products/7
Invoke-WebRequest http://localhost:8080/actuator/metrics/cache.hit
```

停止并清理本实验容器及卷：`docker compose down -v`。

## 热点商品 k6 压测与指标解读

压测脚本位于 `k6/hot-product.js`，固定请求同一个热点商品（默认 `7`）。默认配置为 **50 个并发 VU、持续 60 秒**；通过条件为 HTTP 请求 P95 小于 **100ms**、失败率低于 **1%**、业务检查成功率高于 **99%**。这些阈值用于本机开发环境的回归比较，不应直接当作生产 SLA。

先按上节用 JDK 17 启动 MySQL、Redis 和应用；确认商品可读后，在另一个 PowerShell 窗口记录压测前的计数器：

```powershell
Invoke-RestMethod http://localhost:8080/api/products/7

$metricNames = 'cache.hit', 'cache.miss', 'cache.repository_load', 'cache.lock_busy', 'cache.lock.wait'
foreach ($name in $metricNames) {
  $metric = Invoke-RestMethod "http://localhost:8080/actuator/metrics/$name"
  "{0}={1}" -f $name, $metric.measurements[0].value
}
```

从本实验目录运行 k6；可以用环境变量改为其他本机地址或已存在的商品 ID：

```powershell
k6 version
k6 run .\k6\hot-product.js
# 例如：k6 run -e BASE_URL=http://localhost:8080 -e PRODUCT_ID=7 .\k6\hot-product.js
```

压测结束后，重复前述指标循环并与压测前的值作差。`cache.hit` 是首读命中和锁后二次读取命中次数；`cache.miss` 是首次缓存未命中次数；`cache.repository_load` 是实际回源 MySQL 的次数；`cache.lock_busy` 表示等待重建锁超时后仍未读到缓存、以“系统繁忙”受控失败的次数；`cache.lock.wait` 的 `COUNT` 是进入重建路径并尝试获取锁的次数，`TOTAL_TIME`/`MAX` 可用于观察锁等待开销。

热点键已经预热时，60 秒内 `cache.hit` 的增量应接近成功请求数，`cache.repository_load` 增量应为 0，且 `cache.lock_busy` 应为 0。若先清空 Redis 或让键过期后再运行，第一次重建会带来 `cache.miss` 与通常一次 `cache.repository_load`；并发请求在重建期间可能增加 `cache.lock.wait`。若回源时间超过锁等待上限（200ms），`cache.lock_busy` 增加，同时 k6 的失败率或 P95 阈值可能失败，说明此配置下热点保护或下游容量不足。

本机尚未安装 k6 时，可保留脚本并安装官方 k6 CLI 后重跑上述命令；`k6 version` 若提示命令不存在，即为压测执行被本机依赖阻塞，不能据此虚构压测结果。

## 验证

需要 JDK 17、正在运行的 Docker Desktop，以及可拉取的 `mysql:8.4`、`redis:7.4-alpine` 镜像。在 PowerShell 中执行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\mvnw.cmd verify
```

2026-08-15 的复跑结果为 `BUILD SUCCESS`：Surefire 单元测试 14 个，`Failures: 0, Errors: 0, Skipped: 0`；Failsafe/Testcontainers 集成测试 5 个，`Failures: 0, Errors: 0, Skipped: 0`。集成测试启动 MySQL 8.4 与 Redis 7.4-alpine 容器，覆盖提交后删除缓存、回滚保留数据库与缓存、以及正/空值缓存删除。
