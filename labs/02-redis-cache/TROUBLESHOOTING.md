# 实验二排障复盘：Redis Cache Aside 与一致性

本文按“现象—原因—解决—预防”记录本实验中的环境、缓存、分布式锁与压测问题。密码、连接串等敏感值只应放在本地 `.env` 或环境变量中，不能提交到 Git。

## 阅读提示

- 反引号包围的内容是命令、环境变量、端口、异常文本或指标名。
- 在 VS Code 中打开本文件后按 `Ctrl+Shift+V` 可查看完整预览；在 GitHub 中打开本文件可直接阅读渲染版本。
- 下文的“现象—原因—解决—预防”分别对应：看到什么、为什么发生、如何操作、以后如何避免。

## 1. Maven 使用了错误的 JDK

**现象：** `java -version` 显示 JDK 25，或编译/测试结果与本实验基线不一致。

**原因：** Windows 的 PATH 或已打开的终端仍指向旧 JDK；项目的编译目标是 Java 17，但 Maven 实际使用的运行时取决于当前进程环境。

**解决：** 在每个 PowerShell 会话中先设置并确认：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
.\mvnw.cmd verify
```

**预防：** 所有手动验证和 CI 都固定 JDK 17；不要把 JDK 25 的结果写入验收证据。

## 2. Testcontainers 找不到 Docker named pipe

**现象：** `mvnw.cmd verify` 在集成测试阶段报 `docker_engine` 或 `dockerDesktopLinuxEngine` named pipe 不可访问。

**原因：** Docker Desktop 未启动、尚未完成初始化，或当前终端没有访问 Docker daemon 的权限；这不是 Redis/MySQL 断言失败。

**解决：** 启动 Docker Desktop 后确认：

```powershell
docker version
docker ps
.\mvnw.cmd verify
```

**预防：** 先确认 `docker ps` 正常，再运行 Failsafe/Testcontainers 集成测试；Docker 不可用时只把单元测试结果视为部分证据。

## 3. MySQL Testcontainer 报 JDBC Driver 类不存在

**现象：** 集成测试在创建 MySQL 容器前报 `ClassNotFoundException: com.mysql.jdbc.Driver` 或 `com.mysql.cj.jdbc.Driver`。

**原因：** `org.testcontainers:mysql` 负责容器生命周期，不携带 JDBC 驱动本身。

**解决：** 在 `pom.xml` 中保留由 Spring Boot BOM 管理版本的 `com.mysql:mysql-connector-j`，然后重新执行：

```powershell
.\mvnw.cmd verify
```

**预防：** 数据库容器测试除了 Testcontainers 模块，还要检查运行期 JDBC 驱动是否在测试类路径中。

## 4. PowerShell 运行指定集成测试时报 Unknown lifecycle phase

**现象：** 执行 `mvnw.cmd -Dit.test=RedisProductCacheIT verify` 后，Maven 报 `Unknown lifecycle phase ".test=RedisProductCacheIT"`。

**原因：** PowerShell 会把未正确传递的参数拆分，导致 Maven 收到错误的 lifecycle phase。

**解决：** 将整个 `-D` 参数用单引号包住：

```powershell
.\mvnw.cmd '-Dit.test=RedisProductCacheIT' verify
.\mvnw.cmd '-Dit.test=ProductQueryServiceRedissonRebuildLockIT' verify
```

**预防：** 在 PowerShell 中执行带点号的 Maven 系统属性时始终使用单引号。

## 5. Docker Compose 的 MySQL 端口冲突

**现象：** `docker compose up -d` 报 `ports are not available`，本机默认 MySQL 端口 `3308` 已被占用。

**原因：** 本地 MySQL 或其他实验容器已绑定同一宿主机端口。

**解决：** `3308` 是仓库默认值。先查看占用；仅当它冲突时，再显式设置一个空闲的 `MYSQL_PORT`，例如 3310：

```powershell
netstat -ano | findstr :3308
$env:MYSQL_PORT = '3310'
docker compose up -d
```

随后以同一个 `MYSQL_PORT` 启动应用。不要提交本地覆盖或 `.env`。

**预防：** 每次启动前只筛选 MySQL 的 `ports` 字段，避免诊断输出包含可复制的环境字段；不要运行或复制裸的完整 Compose 配置输出命令：

```powershell
docker compose config --format json |
  ConvertFrom-Json |
  Select-Object -ExpandProperty services |
  Select-Object -ExpandProperty mysql |
  Select-Object -ExpandProperty ports
```

默认结果应为 `3308:3306`；在同一会话设置 `$env:MYSQL_PORT = '3310'` 后重新运行上述安全命令，应为 `3310:3306`。该命令只显示端口映射，不显示密码、token 或其他环境值。

## 6. k6 已安装但 PowerShell 找不到命令

**现象：** `k6 version` 提示不是可识别的命令。

**原因：** Windows 的 PATH 没有包含 k6 安装目录，或当前 PowerShell 在安装前已打开。

**解决：** 使用绝对路径验证并运行脚本：

```powershell
& 'C:\Program Files\k6\k6.exe' version
& 'C:\Program Files\k6\k6.exe' run .\k6\hot-product.js
```

**预防：** 重开终端后再检查 PATH；README 中保留绝对路径作为 Windows 兜底命令。

## 7. 原生 Redis 锁不能直接 DEL

**现象：** 持锁请求耗时超过 3 秒后，旧请求的 `DEL` 删除了新请求已获得的锁。

**原因：** 原生锁的 TTL 到期后，其他请求可取得同一个锁键；不比对持有者 token 的 `DEL` 无法识别锁所有权。

**解决：** 使用每次获取时生成的 UUID token，并以 Lua 原子执行“值仍等于 token 才删除”。

**预防：** 原生 `SET NX PX` 必须配合唯一 token 与 compare-and-delete；固定 TTL 不能保证慢回源只发生一次。

## 8. 200ms 锁等待不能在截止时间后再次抢锁

**现象：** 锁等待刚好跨过截止时间时，竞争请求仍可能在超时后成功获取锁。

**原因：** 仅在失败后检查 deadline，`sleep` 返回后可能已经超过预算，但下一轮仍先执行了一次 `SET NX`。

**解决：** 使用 `System.nanoTime()` 计算单调 deadline，并在每一次 Redis 获取尝试**之前**检查是否超时。

**预防：** 为“sleep 恰好推进至 deadline”的边界写受控时钟单元测试；外部时间不用于计算等待预算。

## 9. 生产更新必须走 TransactionTemplate 与 afterCommit

**现象：** 仓储更新已执行，但事务随后回滚时缓存却被删除；或 Redis 删除失败后误以为数据库也会回滚。

**原因：** 删缓存若不是登记在 Spring 事务同步的 `afterCommit` 阶段，就可能与数据库最终状态脱节。`afterCommit` 开始执行时数据库事务已经提交，因此之后的 Redis 异常无法再回滚该数据库事务。

**解决：** 生产 `CacheConfiguration` 使用四参构造器创建 `ProductUpdateService`，注入 `TransactionTemplate` 与 `SpringTransactionCallbacks`。`updateProduct` 在模板事务中先更新仓储，再登记 `afterCommit` 删除；若随后标记 rollback-only，数据库保留旧值且缓存不删除。删除本身失败时，应记录指标/日志并通过幂等重试、Outbox 或定期校验补偿，不得宣称已提交的数据库会回滚。

**预防：** `ProductionCacheWiringIT.rollbackAfterProductionUpdateRegistersAfterCommitRetainsDatabaseAndCache` 直接使用上下文中的生产 Bean 与 `TransactionTemplate`，在回调登记后设置 rollback-only；`ProductUpdateServiceTest.keepsUpdatedProductWhenCacheEvictionFailsAfterCommit` 证明删除失败不会回滚已提交的更新。

## 10. Prometheus 或 Grafana 端口冲突

**现象：** 启动监控叠加服务时报 `bind: address already in use`，或访问 `http://localhost:9090`、`http://localhost:3000` 的不是本实验服务。

**原因：** 其他本机服务已绑定 Prometheus 的 9090 或 Grafana 的 3000；这两个端口由 `compose.monitoring.yaml` 固定发布。

**解决：** 先确认占用进程，再停止本实验中不需要的同名服务或选择不冲突的本机环境；不要为了排查而输出完整 Compose 配置：

```powershell
netstat -ano | findstr :9090
netstat -ano | findstr :3000
docker compose -f compose.yaml -f compose.monitoring.yaml ps prometheus grafana
```

**预防：** 每次压测前访问 `http://localhost:9090/-/ready` 与 `http://localhost:3000/api/health`，两者都应返回 200。

## 11. Grafana 管理员密码未设置

**现象：** Compose 提示 `GRAFANA_ADMIN_PASSWORD is missing a value`。

**原因：** 监控叠加服务刻意不提供默认管理员密码，要求从未提交的 `.env` 注入。

**解决：** 由操作者在本机 `.env` 设置独立的 `GRAFANA_ADMIN_PASSWORD` 后重新运行启动命令；不要把值粘到终端记录、文档或 Git。已有 `grafana-data` 卷时，首次初始化后的管理员密码不会被新环境变量重置，应使用原密码或按 Grafana 的受控恢复流程处理。

**预防：** `.env.example` 只保留占位符，真实 `.env` 始终忽略且不提交。

## 12. k6 Remote Write receiver 未开启或指标为空

**现象：** k6 报 Remote Write 请求失败，或 Prometheus 查询 `k6_http_reqs_total{testid="..."}` 结果为空。

**原因：** Prometheus 没有以 `--web.enable-remote-write-receiver` 启动、`K6_PROMETHEUS_RW_SERVER_URL` 不是 `http://localhost:9090/api/v1/write`、压测尚未结束，或者 k6 未设置趋势统计而 P95 序列不存在。

**解决：** 从本实验目录启动 `prometheus`，确认 ready，再使用绝对路径执行 k6；为每次运行加唯一 `testid`，并保留 `p(95)`：

```powershell
Invoke-WebRequest http://localhost:9090/-/ready
$env:K6_PROMETHEUS_RW_SERVER_URL = 'http://localhost:9090/api/v1/write'
$env:K6_PROMETHEUS_RW_TREND_STATS = 'p(95),p(99),min,max'
& 'C:\Program Files\k6\k6.exe' run -o experimental-prometheus-rw --tag testid=hot-product-001 .\k6\hot-product.js
```

之后用 README 的 Prometheus 查询确认 `testid` 标签。若 `k6_http_reqs_total` 已有结果但 P95 为空，先检查趋势统计设置；若所有指标为空，检查 receiver 与写入 URL，而不是先修改 Grafana 面板。

在浏览器打开 `http://127.0.0.1:9090` 后，先执行最小查询确认写入：

```promql
k6_http_reqs_total{testid="hot-product-001"}
```

有结果后再按同一 `testid` 查询请求速率、失败率、P95 与 VU；完整 PromQL 示例见 [README.md](README.md#在-prometheus-浏览器中查询压测过程)。

## 13. 需要保留或恢复监控数据

**现象：** 需要临时停止监控，或重启后希望继续查看历史压测批次。

**原因：** Prometheus 与 Grafana 使用命名卷；`stop` 保留卷，`down -v` 删除同一 Compose 项目的全部卷。

**解决：** 日常暂停并恢复监控使用：

```powershell
docker compose -f compose.yaml -f compose.monitoring.yaml stop prometheus grafana
docker compose -f compose.yaml -f compose.monitoring.yaml up -d prometheus grafana
```

只有明确放弃整个实验的数据时才运行 `docker compose -f compose.yaml -f compose.monitoring.yaml down -v`。它同时删除 MySQL、Redis、Prometheus 和 Grafana 的数据卷，不能当作“清空监控数据”的安全替代。

**预防：** 需要跨环境留档时，在删除卷前使用组织批准的备份方式导出；不要依赖可删除的本地命名卷作为唯一副本。

## 14. 产品接口的中文在 PowerShell 中显示为乱码

**现象：** `GET /api/products/7` 返回的商品名称在 PowerShell 中显示为 `ç¼...` 等乱码，而浏览器或其他客户端可能正常。

**原因：** JSON 原始字节是 UTF-8，但响应头未明确声明字符集时，旧版 Windows PowerShell 可能按单字节编码解码；另外，终端代码页不是 UTF-8 时也会导致中文输出异常。

**解决：** 应用已在 `application.yml` 中通过 `server.servlet.encoding.charset: UTF-8` 和 `server.servlet.encoding.force-response: true` 强制响应使用 UTF-8。重启应用后检查：

```powershell
(Invoke-WebRequest http://localhost:8080/api/products/7).Headers['Content-Type']
```

结果应包含 `application/json;charset=UTF-8`。若 PowerShell 仍无法正确显示中文，将以下配置加入 `$PROFILE`，然后重开终端或执行 `. $PROFILE`：

```powershell
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8NoBom
[Console]::OutputEncoding = $utf8NoBom
$OutputEncoding = $utf8NoBom
chcp.com 65001 > $null
```

**预防：** `ActuatorE2EIT.returnsProductJsonWithAnExplicitUtf8ResponseEncoding` 使用真实 HTTP、MySQL 与 Redis Testcontainers 断言 `Content-Type` 包含 UTF-8，且响应字节可正确解码“Java 编程思想”。

## 最终验收证据

- JDK 17 + Docker Desktop 下，`mvnw.cmd verify` 覆盖 24 个单元测试与 23 个 Testcontainers 集成测试，均为 0 failures、0 errors、0 skipped；
- 两个独立 Redisson 客户端、100 个并发请求同一失效热点 Key，仓储仅回源 1 次；
- k6 v2.2.0 以 50 VU 持续 60 秒实测：574,675 请求、P95 7.18ms、失败率 0%、业务 checks 100%；
- 缓存写入、提交后失效、缓存重建锁和 HTTP 指标的手动命令均记录在 [README.md](README.md)。
