# Elasticsearch 商品搜索实验

这个实验把 MySQL 作为商品事实源，通过事务 Outbox 可靠同步到 Elasticsearch，并用官方 `analysis-smartcn` 插件提供中文搜索。它同时演示租约与 token fencing、`external_gte` 外部版本、在线重建、高水位重放和故障恢复。

## 结构与数据流

代码按四层组织：

- `domain`：商品、状态和不可变搜索快照，不依赖 Spring 或存储。
- `application`：商品命令、Outbox 分发、搜索、重建和一致性用例。
- `infrastructure`：MySQL JDBC 与 Elasticsearch Java Client 适配。
- `api` / `observability` / `config`：HTTP 边界、Micrometer 指标和运行配置。

一次商品写入的主路径是：

```text
HTTP 商品命令
  -> MySQL 同一事务写 product + search_outbox
  -> dispatcher 有界领取 Outbox（租约 + claim token）
  -> products-write alias -> 当前物理索引 <- products-read alias <- HTTP 搜索
```

`products-write` 与 `products-read` 是两个职责不同的别名，正常状态下始终共同指向同一个物理索引，不是两个索引之间复制。

写 API 成功只表示 `product` 与不可变 Outbox 快照已在 MySQL 同一事务提交，不表示 Elasticsearch 已同步。dispatcher 是至少一次投递：重复写入是正常情况，Elasticsearch 用商品版本作为 `external_gte` 外部版本，防止旧更新覆盖新更新，也防止旧删除/更新错误复活商品。旧 owner 只有携带仍有效的 claim token 才能完成、重试或终止事件。

数据库锁顺序固定为：

```text
search_coordination -> search_rebuild_job -> product -> search_outbox
```

路径可以跳过不需要的表，但不能逆序。商品写入、Outbox 领取、修复和重建切换都受数据库 coordination 锁协调；重建短暂独占门禁与 Outbox 租约/token fencing 共同避免竞态。

## 环境要求与启动

- JDK 17
- Docker Engine / Docker Desktop
- MySQL `8.4`
- Elasticsearch `8.18.8`，插件必须是同版本镜像内安装的官方 `analysis-smartcn`
- Maven Wrapper `3.9.11`

复制配置模板并把尖括号占位符替换为本机值；不要提交 `.env`：

```powershell
Copy-Item .env.example .env
docker compose --env-file .env up -d --build
docker compose ps
```

Compose 启动 MySQL 和包含 SmartCN 的自定义 Elasticsearch 镜像。应用不自动读取 `.env`，需要在当前终端设置对应环境变量。例如 PowerShell：

```powershell
$env:DB_URL = 'jdbc:mysql://localhost:3311/product_search'
$env:DB_USERNAME = 'product_search'
$env:DB_PASSWORD = '<与 .env 中 MYSQL_PASSWORD 相同的值>'
$env:ELASTICSEARCH_URIS = 'http://localhost:9200'
$env:SEARCH_MAINTENANCE_ENABLED = 'true'
.\mvnw.cmd spring-boot:run
```

Unix shell 使用同名环境变量和 Maven 3.9.11 的 `mvn spring-boot:run`。本实验当前只提交 Windows wrapper 脚本 `mvnw.cmd`；Unix 环境需预装固定版本 Maven。如果保留默认端口，MySQL 为 `3311`，Elasticsearch 为 `9200`。运维接口默认关闭；只有显式设置 `SEARCH_MAINTENANCE_ENABLED=true` 才会注册。

停止环境：

```powershell
docker compose down
```

仅在确认不再需要本地数据时，才使用 `docker compose down -v` 删除 Compose 数据卷。

## 商品与搜索 API

创建商品：

```powershell
curl.exe -X POST 'http://localhost:8080/api/products' `
  -H 'Content-Type: application/json; charset=UTF-8' `
  --data-binary '{"name":"Java并发编程实战","subtitle":"线程与并发工具","description":"Java并发教材","categoryCode":"BOOK","categoryName":"图书","price":88.00,"status":"ON_SALE"}'
```

读取、更新和删除分别使用：

```text
GET    /api/products/{id}
PUT    /api/products/{id}                 # JSON 中必须带 expectedVersion
DELETE /api/products/{id}?expectedVersion=1
```

更新请求包含与创建相同的商品字段，并增加正数 `expectedVersion`。可写状态为 `ON_SALE` 或 `OFF_SHELF`；删除由 DELETE API 产生 `DELETED` tombstone。

SmartCN 中文搜索示例：

```powershell
curl.exe 'http://localhost:8080/api/products/search?q=并发编程&categoryCode=BOOK&minPrice=10.00&maxPrice=100.00&page=0&size=20&sort=relevance'
```

搜索只读取 Elasticsearch，并固定过滤 `ON_SALE`。`q` 对名称、副标题和描述进行 SmartCN 分词检索，名称权重最高；响应包含商品、命中高亮、总数和分类聚合。排序值为 `relevance`、`priceAsc`、`priceDesc`、`newest`。`size` 范围为 1–50，且 `(page * size) + size` 不能超过 10,000。

所有 HTTP JSON 都使用 UTF-8。未知 JSON 字段、非法枚举、非法价格/分页返回稳定错误响应；版本冲突返回 409；Elasticsearch 不可用时搜索返回 503。商品读取始终以 MySQL 为准。

## 重建、一致性检查与修复

以下接口仅在 `search.maintenance.enabled=true` 时存在：

```text
POST /api/admin/search/rebuilds
GET  /api/admin/search/rebuilds/{jobId}
POST /api/admin/search/consistency-checks
POST /api/admin/search/products/{productId}/repair
POST /api/admin/search/outbox/{eventId}/retry
```

启动重建会立即返回 202 和 `jobId`；后台单线程 executor 执行快照导入、高水位补放、验证和原子别名切换。状态查询只读取 MySQL，不等待后台任务。重建期间商品写入仍以 MySQL+Outbox 为主线，切换阶段才使用短暂 coordination 独占门禁。read/write 别名必须始终指向同一个物理索引；分裂时系统 fail closed，拒绝猜测修复。

一致性检查以 MySQL 为事实源，按批扫描 MySQL 和 Elasticsearch，报告 `missing`、`stale`、`orphan` 总数，每类最多返回 20 个样例 ID。商品修复通过 Outbox 重新排队，不直接改 Elasticsearch；失败事件重投只接受 `FAILED` 状态。

示例：

```powershell
$rebuild = curl.exe -sS -X POST 'http://localhost:8080/api/admin/search/rebuilds' | ConvertFrom-Json
curl.exe "http://localhost:8080/api/admin/search/rebuilds/$($rebuild.jobId)"
curl.exe -X POST 'http://localhost:8080/api/admin/search/consistency-checks'
curl.exe -X POST 'http://localhost:8080/api/admin/search/products/1/repair'
curl.exe -X POST 'http://localhost:8080/api/admin/search/outbox/00000000-0000-0000-0000-000000000000/retry'
```

## 指标

Actuator 暴露 `/actuator/health` 和 `/actuator/metrics`。实验使用有限的 outcome/status 标签，不把 eventId、productId 或异常文本放入标签：

- `search.sync.events`
- `search.sync.bulk.duration`
- `search.sync.outbox`
- `search.sync.oldest.age`
- `search.query.duration`
- `search.query.errors`
- `search.rebuild.duration`
- `search.rebuild.differences`

例如：

```powershell
curl.exe 'http://localhost:8080/actuator/metrics/search.sync.outbox'
curl.exe 'http://localhost:8080/actuator/metrics/search.query.duration?tag=outcome:success'
```

## 安全清理旧物理索引

重建成功不会自动删除旧物理索引。清理前必须先分别读取两个别名：

```powershell
curl.exe 'http://localhost:9200/_alias/products-read'
curl.exe 'http://localhost:9200/_alias/products-write'
```

确认 `products-read` 与 `products-write` 都只指向同一个当前物理索引，并确认待删除的旧索引未被任一别名引用。然后只删除一个人工核对过的明确物理索引名，例如：

```powershell
curl.exe -X DELETE 'http://localhost:9200/products-v0123456789abcdef0123456789abcdef'
```

禁止使用 `products-*`、`products-v*`、`_all` 或任何通配符删除。无法同时证明两个别名的唯一目标时停止操作，先按 [TROUBLESHOOTING.md](TROUBLESHOOTING.md) 处理别名问题。

## 验证

快速单元测试不会启动 Docker：

```powershell
.\mvnw.cmd test
```

完整验收会真实启动 MySQL 8.4、带 SmartCN 的 Elasticsearch 8.18.8 与 Toxiproxy 2.12.0，包含重复重建和断连恢复演练：

```powershell
.\mvnw.cmd verify
```

Windows PowerShell 调用 `mvnw.cmd` 并筛选集成测试时，必须把整个 `-Dit.test=...` 参数加双引号，避免 PowerShell 拆解：

```powershell
.\mvnw.cmd "-Dit.test=ProductSearchHttpIT,RebuildAndRecoveryDrillIT" verify
```

Unix：

```bash
mvn test
mvn verify
mvn -Dit.test=ProductSearchHttpIT,RebuildAndRecoveryDrillIT verify
```

验收要求 Surefire 和 Failsafe 均为 0 failures、0 errors、0 skipped，并且日志中没有容器停止后的异步重连或资源泄漏告警。
