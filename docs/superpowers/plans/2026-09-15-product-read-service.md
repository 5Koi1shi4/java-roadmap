# 实验八 8.2 商品读取服务 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增独立商品读服务与读库，通过版本化完整快照事件保持搜索收敛，同时保留兼容单体内订单和库存的原子事务。

**Architecture:** `legacy-market-service` 只在市场事务内采集商品快照并写源 Outbox，提交后以 RabbitMQ confirm 发布。`product-read-service` 通过 Inbox 和商品版本条件写独立 MySQL 投影，再通过读侧 Outbox 构建自己持有的 Elasticsearch 索引。Gateway 只把两条现有搜索 GET 路由到新服务，写命令与媒体继续进入兼容单体。

**Tech Stack:** JDK 17、Maven Wrapper、Spring Boot 3.5.16、Spring Cloud 2025.0.3、MySQL 8.4、RabbitMQ 3.13、SmartCN Elasticsearch 8.18.8、Testcontainers、JUnit 5。

## Global Constraints

- 设计依据：`docs/superpowers/specs/2026-09-15-product-read-service-design.md`；仅实施 8.2 读取边界，不移动商品、库存、订单、媒体或售后事实。
- 实验代码只在 `learning/spring-cloud-split` 的 `labs/08-spring-cloud-split/`；`main` 只保存设计、计划和路线文档。两棵树都不得暂存无关修改，`AGENTS.md` 保持忽略。
- Java 与 Maven 固定 JDK 17，Boot 固定 3.5.16，Cloud BOM 固定 2025.0.3；全程中文文档、注释和错误，HTTP JSON 显式 `application/json; charset=UTF-8`。
- JWT 只认 RS256、已知 `kid`、精确 `iss`、`aud=campus-market-api`、UUID `sub`、白名单 `roles`、`iat` 和严格 15 分钟 `exp`。Gateway 和读服务各自验签。
- `market_db` 是交易事实源；`product_read_db` 是可恢复读模型。最小权限账号互不跨库；生产模块不共享 Java 领域模型，测试夹具只用 test scope。
- 订单、条件库存扣减、库存流水、幂等与订单 Outbox 继续在同一市场事务提交。搜索不能作为下单判断。源事件与事实同事务，读侧 Inbox、投影和索引 Outbox 同事务。
- 先写失败测试并确认预期失败，再写最小实现。MySQL、RabbitMQ、Elasticsearch、Flyway、真实 HTTP 和恢复演练必须用 Testcontainers；完整验收前先执行只读 `docker info`，外部测试 skipped 不算通过。
- 源事件 `schemaVersion=2`、完整不可变商品快照、`eventId`、`listingId`、正数 `aggregateVersion`、`eventType` 与 `occurredAt`；未知字段、类型、版本和非法值快速失败。

---

## File Structure

```text
labs/08-spring-cloud-split/
├── pom.xml                           # 将新应用加入 Reactor
├── api-gateway/                       # 两条 GET 显式路由与五应用旅程
├── legacy-market-service/            # 市场事实、快照源 Outbox、publisher 与 replay
├── product-read-service/             # 独立 JWT/HTTP、product_read_db、Inbox、ES 索引与重建
├── platform-test-support/            # 三库及 Rabbit/ES 测试夹具，仅 test scope
├── docker/mysql/                      # 本地三库最小权限初始化
├── compose.yaml / .env.example        # 第五应用及本地依赖
└── README.md / docs/ / notes/         # 运行、迁移边界与验收证据
```

## Task 1: 基线、Reactor 与显式搜索路由

**Files:** Modify `labs/08-spring-cloud-split/pom.xml`, `api-gateway/src/main/resources/application.yml`, `api-gateway/src/test/java/com/example/campusmarket/gateway/ExplicitRoutesTest.java`; create `product-read-service/pom.xml`.

**Interfaces:** Produce `product-read-service` Maven module and `GET /api/search`, `GET /api/listings/search` routes with `lb://product-read-service`; existing auth and legacy routes remain.

- [x] **Step 1:** 在已有 linked worktree 检查 `git-dir != git-common-dir`、无 submodule、分支与干净状态；在 JDK 17 下运行 `mvnw.cmd test` 保存 fresh 基线 XML。`docker info` 只读检查留给外部测试。
- [x] **Step 2:** 先把路由测试改成如下预期并运行 `mvnw.cmd -pl api-gateway -am '-Dtest=ExplicitRoutesTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`，确认因只有两条路由而失败：

```java
assertThat(routes).extracting(RouteDefinition::getId)
    .containsExactly("identity-api", "product-search", "product-listing-search", "legacy-api");
assertThat(routes.get(1).getUri().toString()).isEqualTo("lb://product-read-service");
assertThat(routes.get(1).getPredicates()).extracting(PredicateDefinition::getName)
    .contains("Path", "Method");
```

- [x] **Step 3:** 父 POM 添加 `<module>product-read-service</module>`；子 POM 继承父版本并只加入 Web、Security、OAuth2 Resource Server、JDBC、AMQP、Eureka、Flyway/MySQL、Actuator、Elasticsearch 与必要 test scope 依赖。Gateway 在 legacy 路由之前加入两条 `Path` + `Method=GET`，不得使用 discovery locator；不建立生产模块依赖。
- [x] **Step 4:** 重跑定点测试和 Reactor `test`，检查 `git diff --check`；只暂存上述文件并提交 `build(cloud): add explicit product read module and routes`。执行证据：实验分支 `52cb5e0`，Gateway 定点 1 项 0 failures/errors/skipped，全 Reactor BUILD SUCCESS。

## Task 2: 源事件完整快照与事务证明

**Files:** Modify `legacy-market-service/src/main/java/com/example/campusmarket/catalog/search/SearchOutboxRepository.java`, `catalog/infrastructure/JdbcInventoryRepository.java`, `catalog/application/ListingService.java`; create `legacy-market-service/src/main/resources/db/migration/V31__product_snapshot_outbox.sql`, `legacy-market-service/src/main/java/com/example/campusmarket/catalog/search/ProductSnapshotEvent.java`, `legacy-market-service/src/test/java/com/example/campusmarket/integration/ProductSnapshotOutboxIT.java`.

**Interfaces:** Produce `ProductSnapshotEvent(UUID eventId, UUID listingId, long aggregateVersion, String eventType, Instant occurredAt, int schemaVersion, ProductSnapshot snapshot)` and `SearchOutboxRepository.enqueue(UUID listingId, long version, String eventType)`; all event payload fields are read from `listing` inside caller transaction.

- [x] **Step 1:** 写真实 MySQL 失败测试：事务回滚后 `search_outbox` 无新行；发布和库存归零后事件分别包含 `ON_SALE`/`SOLD_OUT` 及精确版本；隔离、重新上架和报损保持数量非负且各有事件。运行 `mvnw.cmd -pl legacy-market-service -am '-Dit.test=ProductSnapshotOutboxIT' '-Dfailsafe.failIfNoSpecifiedTests=false' verify`，确认事件缺字段或缺行的预期失败。红灯因 `schema_version` 列缺失。
- [x] **Step 2:** `V31` 增加 `schema_version INT NOT NULL DEFAULT 2` 与 `ck_search_outbox_schema_version CHECK (schema_version=2)`；保留 `id` 作 event ID，`sequence_no` 作 replay 高水位。`ProductSnapshotEvent` 构造器拒绝 null、非法 UUID/范围、未知状态/类型、非 2 版本。源 repository 在市场事务内 `SELECT` 商品快照并把完整 JSON 与版本写 Outbox，不在 publisher 阶段重新读取商品；`JdbcInventoryRepository` 的条件 UPDATE 与原事务仍不变。

```sql
INSERT INTO search_outbox(id,listing_id,aggregate_version,event_type,payload,schema_version,
                          status,attempt_count,available_at,created_at)
VALUES (?,?,?,?,CAST(? AS JSON),2,'NEW',0,CURRENT_TIMESTAMP(6),DEFAULT);
```

- [x] **Step 3:** 定点 IT 和全部 legacy 单元 `test` 通过后检查源事件没有 object key、签名 URL、Token 或校方字段；只暂存任务文件并提交 `feat(cloud): capture immutable product snapshots in market outbox`。执行证据：实验分支 `c3f7482`，父代理复验真实 MySQL 定点 IT 4 项 0 failures/errors/skipped，Reactor BUILD SUCCESS。

## Task 3: 确认式发布与保留事件 replay

**Files:** Keep legacy `SearchOutboxDispatcher.java`/`SearchOutboxScheduler.java` and their experiment-seven integration tests intact; create `legacy-market-service/src/main/java/com/example/campusmarket/catalog/search/ProductSnapshotPublisherDispatcher.java`, `ProductSnapshotPublisherScheduler.java`, `ProductSnapshotOutboxClaimer.java`, `ProductEventPublisher.java`, `ProductReplayService.java`, `legacy-market-service/src/main/resources/db/migration/V32__product_replay_claim.sql`, `legacy-market-service/src/test/java/com/example/campusmarket/integration/ProductPublisherIT.java`, `ProductReplayIT.java`; modify `legacy-market-service/src/main/resources/application.yml` to opt in only the new production publisher.

**Interfaces:** Produce `ProductEventPublisher.publish(ProductSnapshotEvent)` and `ProductReplayService.replayOnce(int limit)`; Rabbit topology is durable exchange `campus.product.snapshot`, durable queue `campus.product.read`, persistent delivery, no auto retry of HTTP writes.

- [x] **Step 1:** 先写 Rabbit/MySQL IT：无 confirm 不标记 `PUBLISHED`；broker 断开时交易事实及 NEW Outbox 仍提交；恢复后投递持久消息；旧 owner 迟到 confirm 被 token/租约条件拒绝。replay 测试固定 `MAX(sequence_no)` 后模拟并发新事件与 owner 接管，证明已发布旧事件可重发且新事件照常发布。运行两个定点 IT 见预期失败。
- [x] **Step 2:** 新增生产 `ProductSnapshotPublisherDispatcher`，从源 Outbox 领取快照并用 `RabbitTemplate` correlated confirm 发布；旧 `SearchOutboxDispatcher` 保持实验七真实搜索回归入口，且生产配置明确只启用新 scheduler，不同时领取源 Outbox。完成 SQL 固定 `WHERE id=? AND status='PUBLISHING' AND owner_id=? AND claim_token=? AND lease_until>CURRENT_TIMESTAMP(6)`；broker 未就绪时暂停领取，实际投递失败最多三次并转可检查失败终态，保留行由本机 replay 接管，不丢掉唯一恢复路径。`V32` 创建单行 replay claim 进度与高水位表，`replayOnce` 通过数据库时间领取、有界批量、token fencing 和 confirm 重新发布保留的完整事件；只允许本机运维调用，不添加 HTTP 路由。
- [x] **Step 3:** 两个定点 IT、legacy `test` 与 `git diff --check` 通过后提交 `feat(cloud): publish and replay product snapshots reliably`。实验分支 `755725d`，真实 ProductPublisherIT 5/5、ProductReplayIT 2/2，legacy 单元 137/137、Flyway 到 v32，均 0 failures/errors/skipped、BUILD SUCCESS。

## Task 4: 读库与三库最小权限

**Files:** Create `product-read-service/src/main/resources/db/migration/V1__product_projection.sql`; modify `platform-test-support/src/test/java/com/example/campusmarket/testsupport/SplitDatabaseContainer.java`, `docker/mysql/01-split-databases.sh`, `.env.example`, `compose.yaml`; create `product-read-service/src/test/java/com/example/campusmarket/product/integration/ProductDatabaseOwnershipIT.java`.

**Interfaces:** Produce `product_read_db` app/migrator accounts, `product_projection`, `product_inbox`, `product_index_outbox`, `product_rebuild_gate`, `product_index_cleanup_task`; `SplitDatabaseContainer.productProperties(): Properties` and `productProperties(DynamicPropertyRegistry registry): void` follow the existing identity/market fixture pattern and expose only product DB credentials.

- [x] **Step 1:** 写三库权限失败 IT，使用真实 MySQL 分别执行 `SELECT` 对方 schema 并断言 `DataAccessException`，验证 Flyway 只创建读侧业务表、投影版本 CHECK 与 Inbox event ID 唯一键。运行定点 `verify` 确认因产品库/迁移缺失而失败。红灯为 product_migrator 缺失、MySQL 1045。
- [x] **Step 2:** 迁移固定 `product_projection(listing_id CHAR(36) PRIMARY KEY, aggregate_version BIGINT NOT NULL, ... status VARCHAR(20) NOT NULL)`，`product_inbox(event_id CHAR(36) PRIMARY KEY, completed_at TIMESTAMP(6) NOT NULL)`，`product_index_outbox(id CHAR(36) PRIMARY KEY, listing_id CHAR(36), aggregate_version BIGINT, status VARCHAR(20), owner_id, claim_token, lease_until, available_at)`；版本正数、数量非负、状态白名单及领取字段 CHECK。脚本用现有 `escape_sql_literal` 处理 `PRODUCT_APP_PASSWORD` 与 `PRODUCT_MIGRATOR_PASSWORD`，并只授予 product app 本库 DML、migrator 本库 DDL；Compose 不公开新库访问给应用外网络。
- [x] **Step 3:** 真实权限 IT、现有 identity/market 权限 IT 与 `git diff --check` 通过后提交 `feat(cloud): isolate product read database`。实验分支 `cc3dd49`，ProductDatabaseOwnershipIT 6/6、IdentitySchemaIT 4/4，父代理在 Task3 import 修复后复验 DatabaseOwnershipIT 3/3，均为真实 MySQL、0 failures/errors/skipped，BUILD SUCCESS。

## Task 5: 消费幂等与索引待办

**Files:** Create `product-read-service/src/main/java/com/example/campusmarket/product/ProductReadApplication.java`, `event/ProductSnapshotDecoder.java`, `event/ProductEventConsumer.java`, `event/ProductRabbitListener.java`, `event/ProductRabbitTopology.java`, `infrastructure/JdbcProductProjection.java`, `infrastructure/JdbcProductInbox.java`, `infrastructure/JdbcProductIndexOutbox.java`, `src/main/resources/application.yml`; create `product-read-service/src/test/java/com/example/campusmarket/product/ProductEventContractTest.java`, `event/ProductRabbitListenerTest.java`, `integration/ProductProjectionIT.java`, `integration/ProductRabbitConsumerIT.java`.

**Interfaces:** Produce `ProductSnapshotDecoder.decode(byte[]): ProductSnapshotEvent`, `ProductEventConsumer.accept(byte[])`, `JdbcProductProjection.apply(ProductSnapshotEvent): boolean`; return false for same/lower aggregate version and create index Outbox only for true.

- [x] **Step 1:** 写失败契约测试：缺字段、错误类型、未知字段/版本/状态被 decoder 拒绝；真实 MySQL/Rabbit 测试同 eventId 两次仅一条 Inbox、乱序版本 3 后版本 2 不回退，合法版本的投影、Inbox 与 index Outbox 同事务提交，异常回滚时不 ACK。运行定点 `test`/`verify` 确认行为缺失。红灯依次为缺 decoder、缺消费者/JDBC 类、读服务独立启动时 Rabbit 队列 404。
- [x] **Step 2:** decoder 使用 Jackson `FAIL_ON_UNKNOWN_PROPERTIES`、严格 record 构造器和字段类型；消费使用手动 ACK、`@Transactional` 的应用用例；先条件 `UPDATE ... WHERE aggregate_version < ?`，并发首次投影时 `INSERT` 冲突后重试条件 UPDATE。Inbox eventId 唯一，索引 Outbox 与投影条件写同事务。读服务自行声明与源发布端一致的持久队列和死信拓扑。

```sql
UPDATE product_projection SET title=?, description=?, category=?, unit_price_fen=?,
    available_quantity=?, status=?, aggregate_version=?
WHERE listing_id=? AND aggregate_version < ?;
```

- [x] **Step 3:** 运行定点测试与产品模块 `test`，检查源与消费者无生产 Java 依赖；提交 `feat(cloud): consume versioned product projections`。实验分支 `7ae5ca9`、`304f804`、`71a23cd`、`e3aa3ae`、`ff53247`、`d347f2d`；产品单元 18/18、真实 MySQL 投影 3/3、真实 Rabbit/MySQL 消费 2/2、直连 HTTP/JWKS 安全 4/4，均 0 failures/errors/skipped、BUILD SUCCESS。

## Task 6: 独立搜索、外部版本与在线索引重建

**Files:** Create `product-read-service/src/main/java/com/example/campusmarket/product/search/ProductSearchPort.java`, `ElasticsearchProductSearch.java`, `ProductIndexDispatcher.java`, `ProductSearchRebuildService.java`, `api/SearchController.java`, `security/ProductResourceServerConfiguration.java`; create `product-read-service/src/test/java/com/example/campusmarket/product/integration/ProductSearchIT.java`, `ProductRebuildIT.java`, `ProductHttpSecurityIT.java`.

**Interfaces:** Produce existing `GET /api/search`/`GET /api/listings/search` request and `SearchPage` JSON contract; dispatcher indexes versioned `ON_SALE && quantity>0`, otherwise external-version tombstone; rebuild from product DB with snapshot/high-water/alias switch.

- [x] **Step 1:** 先写失败 ES/Testcontainers IT，再验证 SmartCN 中文结果、分类/价格/searchAfter、售罄/下架删失、旧事件不复活、快照高水位补放与原子别名切换；无效 Token/未知 kid 为 401，依赖故障为中文 UTF-8 503，直连也验签。
- [x] **Step 2:** 产品读侧实现 `ProductSearchPort`、`ElasticsearchProductSearch`、index Outbox dispatcher、数据库重建门禁/恢复器和持久清理调度；仅查询 `product_projection` 与 `product_index_outbox`，以本库时间、owner/token/generation fencing、外部版本和 tombstone 收敛。快照在同一 RR 事务内分页，续租用独立事务提交；Controller 保留现有 JSON/charset，使用同一 projection health 门禁，资源服务器沿用 8.1 JWT/JWKS 契约。
- [x] **Step 3:** 产品定点真实 MySQL/ES/Rabbit/JWT 测试与完整模块回归通过，`git diff --check` 通过；索引/重建阶段提交 `97ff832`、`8390829`，最终恢复与验收提交 `c72c7f1`。

## Task 7: 五应用真实旅程与故障恢复

**Files:** Modify `api-gateway/pom.xml`, `api-gateway/src/test/java/com/example/campusmarket/gateway/support/CloudApplicationCluster.java`, `CloudJourneyIT.java`, `CloudFailureRecoveryIT.java`; create `api-gateway/src/test/java/com/example/campusmarket/gateway/ProductReadJourneyIT.java`, `ProductReadFailureRecoveryIT.java`.

**Interfaces:** Cluster starts Eureka, identity, legacy, product read and Gateway on distinct random ports with real MySQL/Rabbit/SmartCN ES; client requests only Gateway.

- [x] **Step 1:** 先写失败真实 HTTP 旅程，后验证登录、草稿/媒体/发布、中文搜索、下单售罄删失且订单快照不变；Eureka 与两条精确 GET 路由、产品直连 401、Rabbit/产品进程/ES 故障期间写入保留、搜索 503 与恢复收敛。
- [x] **Step 2:** Cluster 启动五个独立 Spring 上下文与真实 MySQL/Rabbit/SmartCN ES，产品读库单独迁移；测试端口采用有界独立随机端口，受控停机/重启与 UTF-8 中文错误断言均通过，未跳过外部协作测试。
- [x] **Step 3:** Gateway 定点五应用旅程/故障恢复、原四应用旅程通过，完整 `clean verify` 中 Gateway 13 项 Failsafe 为 0 failures/errors/skipped；旅程提交 `4f18ac2`、`f89eed9`，最终复核 `c72c7f1`。

## Task 8: 本地运行、文档与完整验收

**Files:** Modify `compose.yaml`, `.env.example`, `README.md`, `docs/architecture.md`, `docs/migration-boundary.md`, `TROUBLESHOOTING.md`, `notes/learning-log.md`, `interview/question-bank.md`; main 文档中心在验收后更新 `README.md`、`notes/learning-log.md` 与本计划的完成状态。

**Interfaces:** Compose 启动 Eureka、Gateway、identity、legacy、product read 五应用和当前所需固定依赖；文档明确 8.2 只拆搜索读取及 replay 操作。

- [x] **Step 1:** 第五应用官方 JDK17 JRE Dockerfile、jar、环境与 readiness 已配置；示例只保留占位符。隔离 Compose config/build/up 后五应用 health 和十个探针 UP、Eureka 4/4、JWKS 200；第二组仓库外临时签名 Token 经镜像 Gateway 请求两条精确 GET 搜索均 200。
- [x] **Step 2:** 中文运行、排障、事实/投影与 replay 边界、学习日志和面试追问已更新；仓库内私钥/常见 Token/个人路径扫描及 `git diff --check` 通过，文档/Compose 阶段提交 `a17e0bf`，最终验收记录见 `c72c7f1`。
- [x] **Step 3:** `docker info` 确认 Engine 29.7.2；JDK 17.0.12 串行 fresh `mvnw.cmd clean test` 与 `mvnw.cmd clean verify` 均六模块 BUILD SUCCESS、退出 0。142 个 fresh XML 为 Surefire 228、Failsafe/Testcontainers 357，共 585 项，全部 0 failures/errors/skipped；实验七回归和新五应用 IT 均运行。
- [x] **Step 4:** 实验 HEAD 的 419 个文件仅在根 `.gitignore` 与 `labs/08-spring-cloud-split/**`，暂存差异与敏感信息扫描通过；验收提交 `c72c7f1` 从远端 `ea5cf49` 快进推送，镜像路由补证 `177cd25` 再次快进推送。main 仅更新路线、此计划和复盘文档，不合并实验代码。
