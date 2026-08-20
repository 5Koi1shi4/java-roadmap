# 实验二：Redis Cache Aside 与更新一致性

这是一个 Spring Boot 3 / Java 17 学习实验：以商品详情为例实现 Cache Aside、提交后缓存失效、热点 Key 重建保护和可观测性，并用 Testcontainers 与 k6 验证真实 Redis/MySQL 协作。

本次实验中的环境、Docker、Testcontainers、Maven 和 k6 问题，见 [TROUBLESHOOTING.md](TROUBLESHOOTING.md)。

与 `macrozheng/mall` 的 Redis 通用服务及业务缓存服务的设计对照，见 [../../references/README.md](../../references/README.md)。

文档中的密码、token、连接串仅可使用本地 `.env` 或环境变量提供；不要提交、截图或发送真实凭据。

> 阅读提示：反引号包围的内容是命令、文件名、环境变量或指标名；建议在 VS Code 中打开本文件并按 `Ctrl+Shift+V` 查看 Markdown 预览，或直接在 GitHub 文件页面阅读渲染版本。

## 你将运行到的能力

- `GET /api/products/{id}`：按 Cache Aside 查询商品；命中空值缓存时返回 404，Redis 或 MySQL 故障不会伪装为“不存在”。
- `ProductUpdateService.updateProduct(...)`：数据库事务提交后才删除 `product:v1:{id}`，回滚时保留旧缓存。
- `RedissonRebuildLock`：热点 Key 未命中时最多等待 200ms；成功获得锁后再次检查缓存，避免多实例并发回源。
- `/actuator/metrics/cache.*`：查看命中、未命中、回源、锁忙和锁等待指标。
- `k6/hot-product.js`：对预热热点商品执行 50 VU、60 秒的 HTTP 压测。

## 前置条件

- JDK 17（不要使用 JDK 25）
- Docker Desktop 已启动，且 `docker compose version` 可用
- k6 v2.2.0（仅运行压测时需要；Windows 下可使用 `C:\Program Files\k6\k6.exe`）
- Windows 使用 PowerShell；Linux/macOS 将 `mvnw.cmd` 换成 `./mvnw`

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

## 热点 Key 缓存重建（生产使用 Redisson）

生产 HTTP 查询路径由 `CacheConfiguration` 将 `RedissonRebuildLock` 注入 `ProductQueryService`，通过 Redis 中的 `RLock` 按 product id 协调多实例缓存重建：

```text
缓存首次读取未命中
  -> 尝试获取 lock:product:rebuild:{id} 对应的 Redisson RLock
  -> 最多等待 200ms
  -> 获取锁后再次读取缓存（二次检查）
  -> 仍未命中时，只有持锁请求访问数据库并回填缓存/空值缓存
  -> finally 由当前持有线程释放 RLock
```

二次检查是必要的：等待者在等待锁期间，前一个请求可能已经完成数据库查询并写入缓存；此时等待者直接返回缓存，不能再次回源。未能于 200ms 内取得锁的请求会最后再读一次缓存；只有该次仍未命中、即将返回“系统繁忙，请稍后重试”时才增加 `cache.lock_busy`。Redisson 在不传 `leaseTime` 时使用 watchdog 续租；这降低慢回源超过固定 TTL 导致重复重建的风险，但不替代限流、降级与数据库保护。

二参/三参构造器仍保留 `LocalRebuildLock`，仅供历史学习与单 JVM 对照测试，不是当前生产 HTTP 路径。它的实际数据结构是 `ConcurrentHashMap<Long, LocalLock>`；每个 `LocalLock` 包含一个 `ReentrantLock` 和参与计数，最后一个参与者退出时才从本地表中移除。这一对照只说明单 JVM 锁对象的生命周期，不能作为多实例协调方案。

## 并发测试证据

`ProductQueryServiceRedissonRebuildLockIT.twoIndependentRedissonClientsRebuildOneExpiredHotKeyOnlyOnceForOneHundredConcurrentRequests` 使用两个独立 Redisson 客户端和 100 个并发请求，证明正常 Redis 可用场景下的跨实例协调。`ProductQueryServiceTest.rebuildsAnExpiredHotKeyWithExactlyOneRepositoryLookup` 与 `ProductQueryServiceLockLifecycleTest` 则是本地锁对照，用阶段闩锁覆盖单 JVM 二次检查与锁表回收，不代表生产锁实现。

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

默认连接为 `localhost:3308/redis_cache`（用户取自 `.env` 的 `MYSQL_USERNAME`，默认 `cache`）与 `localhost:6379`。密码只从未提交的 `.env`/环境变量读取。可用以下端点检查运行态：

```powershell
Invoke-WebRequest http://localhost:8080/api/products/7
Invoke-WebRequest http://localhost:8080/actuator/metrics/cache.hit
```

### 安全确认 MySQL 宿主机端口

端口冲突时，诊断命令只能显示 MySQL 的 `ports` 字段；不要运行或复制会输出完整 Compose 配置（其中可能包含环境字段）的命令。默认值和覆盖值分别应显示 `3308:3306` 与 `3310:3306`：

```powershell
docker compose config --format json |
  ConvertFrom-Json |
  Select-Object -ExpandProperty services |
  Select-Object -ExpandProperty mysql |
  Select-Object -ExpandProperty ports

$env:MYSQL_PORT = '3310'
docker compose config --format json |
  ConvertFrom-Json |
  Select-Object -ExpandProperty services |
  Select-Object -ExpandProperty mysql |
  Select-Object -ExpandProperty ports
```

只在当前 PowerShell 会话中设置覆盖值；应用也要使用同一 `MYSQL_PORT`，且不要提交 `.env`、密码或其他本地环境配置。

停止并清理本实验容器及卷：`docker compose down -v`。这会删除同一 Compose 项目的 MySQL 和 Redis 数据卷；它不是“只清空缓存”或“只清空监控数据”的命令。

## 手动验证查询与指标

应用启动后，先读取一次存在的商品，再重复读取以形成缓存命中：

```powershell
Invoke-RestMethod http://localhost:8080/api/products/7
Invoke-RestMethod http://localhost:8080/api/products/7
```

不存在的商品会返回 HTTP 404；PowerShell 将其表现为异常是预期结果。可查询指标确认第一次请求发生了 miss/回源，第二次请求发生了 hit：

```powershell
Invoke-RestMethod http://localhost:8080/actuator/metrics/cache.miss
Invoke-RestMethod http://localhost:8080/actuator/metrics/cache.repository_load
Invoke-RestMethod http://localhost:8080/actuator/metrics/cache.hit
```

| 方法 | 路径 | 成功响应 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/products/{id}` | 200，商品 JSON | 首次未命中时回源并写入正缓存 |
| GET | `/api/products/{id}` | 404 | 商品不存在时写入 30 秒空值缓存 |
| GET | `/actuator/metrics/cache.hit` | 200，指标 JSON | 查看缓存命中次数 |
| GET | `/actuator/metrics/cache.lock_busy` | 200，指标 JSON | 查看锁等待超时后仍未命中的受控失败次数 |

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

### 可视化：内置 Web Dashboard 或持久化 Prometheus/Grafana

一次压测后只想查看报告时，使用 k6 内置 Web Dashboard，不必部署监控服务；需要跨多轮压测保留指标、按批次比较时，使用本实验的 Prometheus Remote Write 与 Grafana。内置 Dashboard 的数据随 k6 进程结束而结束，不能替代后者的持久化存储。

```powershell
$env:K6_WEB_DASHBOARD = 'true'
$env:K6_WEB_DASHBOARD_OPEN = 'true'
& 'C:\Program Files\k6\k6.exe' run .\k6\hot-product.js
Remove-Item Env:K6_WEB_DASHBOARD, Env:K6_WEB_DASHBOARD_OPEN
```

持久化方案先在未提交的 `.env` 中设置本机 `GRAFANA_ADMIN_PASSWORD`，再在 `labs/02-redis-cache` 目录启动基础服务与监控服务，并用 JDK 17 启动应用：

```powershell
docker compose -f compose.yaml -f compose.monitoring.yaml up -d
docker compose -f compose.yaml -f compose.monitoring.yaml ps
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd spring-boot:run
```

另开一个 PowerShell，确认 `GET /api/products/7` 为 200 后，为每轮添加唯一 `testid` 并写入 Prometheus：

```powershell
Invoke-WebRequest http://localhost:8080/api/products/7
$env:K6_PROMETHEUS_RW_SERVER_URL = 'http://localhost:9090/api/v1/write'
$env:K6_PROMETHEUS_RW_TREND_STATS = 'p(95),p(99),min,max'
& 'C:\Program Files\k6\k6.exe' run -o experimental-prometheus-rw --tag testid=hot-product-001 .\k6\hot-product.js
& 'C:\Program Files\k6\k6.exe' run -o experimental-prometheus-rw --tag testid=hot-product-002 .\k6\hot-product.js
```

`experimental-prometheus-rw` 是 k6 的实验性输出；升级 k6 前应固定版本并重新核对指标名、趋势统计和 Grafana 查询，不能把当前序列命名视为永久 API。趋势设置必须包含 `p(95)`，否则 P95 面板没有对应序列。

先向 Prometheus 确认每批都有 `k6_http_reqs_total`，再在 Grafana 的 [k6 概览](http://localhost:3000/d/k6-overview/k6) 分别选择 `testid=hot-product-001` 与 `testid=hot-product-002`，检查请求速率、失败率、P95 和 VU：

```powershell
foreach ($testid in 'hot-product-001', 'hot-product-002') {
  $query = [uri]::EscapeDataString("k6_http_reqs_total{testid=`"$testid`"}")
  Invoke-RestMethod "http://localhost:9090/api/v1/query?query=$query"
}
```

日常只停止监控、保留 Prometheus 与 Grafana 卷时，运行：

```powershell
docker compose -f compose.yaml -f compose.monitoring.yaml stop prometheus grafana
```

同一组 `up -d` 命令可恢复服务。只有明确清空**整个**实验环境（包括 MySQL、Redis、Prometheus 和 Grafana 的所有数据卷）时才运行：

```powershell
docker compose -f compose.yaml -f compose.monitoring.yaml down -v
```

该命令会删除同一 Compose 项目的 MySQL/Redis 卷和监控卷，不能将它描述为只清空监控数据。

## 验证

需要 JDK 17、正在运行的 Docker Desktop，以及可拉取的 `mysql:8.4`、`redis:7.4-alpine` 镜像。在 PowerShell 中执行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\mvnw.cmd verify
```

2026-08-16 的最终复跑结果为 `BUILD SUCCESS`：Surefire 单元测试 24 个，`Failures: 0, Errors: 0, Skipped: 0`；Failsafe/Testcontainers 集成测试 22 个，`Failures: 0, Errors: 0, Skipped: 0`。集成测试启动 MySQL 8.4 与 Redis 7.4-alpine 容器，覆盖 Redis 序列化/TTL、提交后删除缓存、回滚保留数据库与缓存、原生 Redis 锁、Redisson 锁、跨实例热点重建、生产 `TransactionTemplate` 更新装配和 Actuator 指标。

## 2026-08-16 热点商品实测记录

在 Windows 本机、JDK 17.0.12、Docker Desktop 29.7.2、MySQL 8.4、Redis 7.4-alpine 和 k6 v2.2.0 下执行。先请求一次 `/api/products/7` 预热，再运行默认脚本（50 VU，60 秒）：

```powershell
& 'C:\Program Files\k6\k6.exe' version
& 'C:\Program Files\k6\k6.exe' run .\k6\hot-product.js
```

| 口径 | 实测值 | 默认阈值 | 结果 |
|---|---:|---:|---|
| HTTP 请求 P95 | 7.18ms | < 100ms | 通过 |
| 吞吐量 | 9,577.17 req/s（574,675 请求） | 仅记录 | — |
| HTTP 失败率 | 0.00%（0 / 574,675） | < 1% | 通过 |
| 业务 checks | 100.00%（1,149,350 / 1,149,350） | > 99% | 通过 |

Actuator 计数器在预热后、压测前分别为 `cache.hit=0`、`cache.miss=1`、`cache.repository_load=1`、`cache.lock_busy=0`、`cache.lock.wait COUNT=1`；压测后为 `574675`、`1`、`1`、`0`、`1`。因此压测期增量为 `cache.hit=574675`，其余上述 COUNT 均为 0，符合已预热热点 Key 全部从缓存返回、无额外 MySQL 回源或锁繁忙的预期。

本机排障记录：裸命令 `k6 version` 因 PATH 未包含安装目录而失败，但 `C:\Program Files\k6\k6.exe` 可运行并报告 v2.2.0。MySQL 默认宿主机端口已固定为 `3308`，因此 README 的 `docker compose up -d` 和应用默认数据源会使用 `3308:3306`；仅当 `3308` 被占用时，才显式设置相同的 `MYSQL_PORT` 覆盖 Compose 与应用端口。
