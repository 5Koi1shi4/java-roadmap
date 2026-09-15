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

- [ ] **Step 1:** 写真实 MySQL 失败测试：事务回滚后 `search_outbox` 无新行；发布和库存归零后事件分别包含 `ON_SALE`/`SOLD_OUT` 及精确版本；隔离、重新上架和报损保持数量非负且各有事件。运行 `mvnw.cmd -pl legacy-market-service -am '-Dit.test=ProductSnapshotOutboxIT' verify`，确认事件缺字段或缺行的预期失败。
- [ ] **Step 2:** `V31` 增加 `schema_version INT NOT NULL DEFAULT 2` 与 `ck_search_outbox_schema_version CHECK (schema_version=2)`；保留 `id` 作 event ID，`sequence_no` 作 replay 高水位。`ProductSnapshotEvent` 构造器拒绝 null、非法 UUID/范围、未知状态/类型、非 2 版本。源 repository 在市场事务内 `SELECT` 商品快照并把完整 JSON 与版本写 Outbox，不在 publisher 阶段重新读取商品；`JdbcInventoryRepository` 的条件 UPDATE 与原事务仍不变。

```sql
INSERT INTO search_outbox(id,listing_id,aggregate_version,event_type,payload,schema_version,
                          status,attempt_count,available_at,created_at)
VALUES (?,?,?,?,CAST(? AS JSON),2,'NEW',0,CURRENT_TIMESTAMP(6),DEFAULT);
```

- [ ] **Step 3:** 定点 IT 和全部 legacy 单元 `test` 通过后检查源事件没有 object key、签名 URL、Token 或校方字段；只暂存任务文件并提交 `feat(cloud): capture immutable product snapshots in market outbox`。

## Task 3: 确认式发布与保留事件 replay

**Files:** Modify `legacy-market-service/src/main/java/com/example/campusmarket/catalog/search/SearchOutboxDispatcher.java`, `SearchOutboxScheduler.java`, `SearchOutboxClaimer.java`; create `ProductEventPublisher.java`, `ProductReplayService.java`, `legacy-market-service/src/main/resources/db/migration/V32__product_replay_claim.sql`, `legacy-market-service/src/test/java/com/example/campusmarket/integration/ProductPublisherIT.java`, `ProductReplayIT.java`.

**Interfaces:** Produce `ProductEventPublisher.publish(ProductSnapshotEvent)` and `ProductReplayService.replayOnce(int limit)`; Rabbit topology is durable exchange `campus.product.snapshot`, durable queue `campus.product.read`, persistent delivery, no auto retry of HTTP writes.

- [ ] **Step 1:** 先写 Rabbit/MySQL IT：无 confirm 不标记 `PUBLISHED`；broker 断开时交易事实及 NEW Outbox 仍提交；恢复后投递持久消息；旧 owner 迟到 confirm 被 token/租约条件拒绝。replay 测试固定 `MAX(sequence_no)` 后模拟并发新事件与 owner 接管，证明已发布旧事件可重发且新事件照常发布。运行两个定点 IT 见预期失败。
- [ ] **Step 2:** 把现有 projector 调用替换成 `RabbitTemplate` correlated confirm。完成 SQL 固定 `WHERE id=? AND status='PUBLISHING' AND owner_id=? AND claim_token=? AND lease_until>CURRENT_TIMESTAMP(6)`；短暂故障释放为 NEW 并延迟，不在三次之后丢掉唯一恢复路径。`V32` 创建单行 replay claim 进度与高水位表，`replayOnce` 通过数据库时间领取、有界批量、token fencing 和 confirm 重新发布保留的完整事件；只允许本机运维调用，不添加 HTTP 路由。
- [ ] **Step 3:** 两个定点 IT、legacy `test` 与 `git diff --check` 通过后提交 `feat(cloud): publish and replay product snapshots reliably`。

## Task 4: 读库与三库最小权限

**Files:** Create `product-read-service/src/main/resources/db/migration/V1__product_projection.sql`; modify `platform-test-support/src/test/java/com/example/campusmarket/testsupport/SplitDatabaseContainer.java`, `docker/mysql/01-split-databases.sh`, `.env.example`, `compose.yaml`; create `product-read-service/src/test/java/com/example/campusmarket/product/integration/ProductDatabaseOwnershipIT.java`.

**Interfaces:** Produce `product_read_db` app/migrator accounts, `product_projection`, `product_inbox`, `product_index_outbox`, `product_rebuild_gate`, `product_index_cleanup_task`; `SplitDatabaseContainer.productProperties(MySQLContainer<?> mysql): Map<String,String>` returns Spring datasource/Flyway properties with only product DB credentials.

- [ ] **Step 1:** 写三库权限失败 IT，使用真实 MySQL 分别执行 `SELECT` 对方 schema 并断言 `DataAccessException`，验证 Flyway 只创建读侧业务表、投影版本 CHECK 与 Inbox event ID 唯一键。运行定点 `verify` 确认因产品库/迁移缺失而失败。
- [ ] **Step 2:** 迁移固定 `product_projection(listing_id CHAR(36) PRIMARY KEY, aggregate_version BIGINT NOT NULL, ... status VARCHAR(20) NOT NULL)`，`product_inbox(event_id CHAR(36) PRIMARY KEY, completed_at TIMESTAMP(6) NOT NULL)`，`product_index_outbox(id CHAR(36) PRIMARY KEY, listing_id CHAR(36), aggregate_version BIGINT, status VARCHAR(20), owner_id, claim_token, lease_until, available_at)`；版本正数、数量非负、状态白名单及领取字段 CHECK。脚本用现有 `escape_sql_literal` 处理五个新环境变量，并只授予 product app 本库 DML、migrator 本库 DDL；Compose 不公开新库访问给应用外网络。
- [ ] **Step 3:** 真实权限 IT、现有 identity/market 权限 IT 与 `git diff --check` 通过后提交 `feat(cloud): isolate product read database`。

## Task 5: 消费幂等与索引待办

**Files:** Create `product-read-service/src/main/java/com/example/campusmarket/product/ProductReadApplication.java`, `event/ProductSnapshotDecoder.java`, `event/ProductEventConsumer.java`, `infrastructure/JdbcProductProjection.java`, `infrastructure/JdbcProductInbox.java`, `infrastructure/JdbcProductIndexOutbox.java`, `src/main/resources/application.yml`; create `product-read-service/src/test/java/com/example/campusmarket/product/ProductEventContractTest.java`, `integration/ProductProjectionIT.java`.

**Interfaces:** Produce `ProductSnapshotDecoder.decode(byte[]): ProductSnapshotEvent`, `ProductEventConsumer.accept(byte[])`, `JdbcProductProjection.apply(ProductSnapshotEvent): boolean`; return false for same/lower aggregate version and create index Outbox only for true.

- [ ] **Step 1:** 写失败契约测试：缺字段、错误类型、未知字段/版本/状态被 decoder 拒绝；真实 MySQL/Rabbit 测试同 eventId 两次仅一条 Inbox、乱序版本 3 后版本 2 不回退，合法版本的投影、Inbox 与 index Outbox 同事务提交，异常回滚时不 ACK。运行定点 `test`/`verify` 确认行为缺失。
- [ ] **Step 2:** decoder 使用 Jackson `FAIL_ON_UNKNOWN_PROPERTIES`、严格 record 构造器和字段类型；消费使用手动 ACK、`@Transactional` 的应用用例；投影 SQL 使用 `INSERT ... ON DUPLICATE KEY UPDATE` 且每列仅在 `VALUES(aggregate_version)>aggregate_version` 时更新，Inbox eventId 唯一，索引 Outbox 与投影条件写同事务。

```sql
UPDATE product_projection SET title=?, description=?, category=?, unit_price_fen=?,
    available_quantity=?, status=?, aggregate_version=?
WHERE listing_id=? AND aggregate_version < ?;
```

- [ ] **Step 3:** 运行定点测试与产品模块 `test`，检查源与消费者无生产 Java 依赖；提交 `feat(cloud): consume versioned product projections`。

## Task 6: 独立搜索、外部版本与在线索引重建

**Files:** Create `product-read-service/src/main/java/com/example/campusmarket/product/search/ProductSearchPort.java`, `ElasticsearchProductSearch.java`, `ProductIndexDispatcher.java`, `ProductSearchRebuildService.java`, `api/SearchController.java`, `security/ProductResourceServerConfiguration.java`; create `product-read-service/src/test/java/com/example/campusmarket/product/integration/ProductSearchIT.java`, `ProductRebuildIT.java`, `ProductHttpSecurityIT.java`.

**Interfaces:** Produce existing `GET /api/search`/`GET /api/listings/search` request and `SearchPage` JSON contract; dispatcher indexes versioned `ON_SALE && quantity>0`, otherwise external-version tombstone; rebuild from product DB with snapshot/high-water/alias switch.

- [ ] **Step 1:** 写失败 ES/Testcontainers IT：SmartCN 中文结果、分类/价格/searchAfter、库存零与下架删失、旧事件不能复活；重建时消费版本前进、补放后原子切换；HTTP 无效/未知 kid 返回 401、读服务/ES 失败为中文 UTF-8 503，直接访问读服务也验签。运行定点 IT 见缺少行为的失败。
- [ ] **Step 2:** 从 legacy 中复制并按产品读库改造 `ProductSearchPort`、`ElasticsearchProductSearch`、`SearchAliasCoordinator` 与重建算法；重建只读 `product_projection` 和 `product_index_outbox`，不查询 `market_db`。读侧 dispatcher 使用本库时间、owner/claim token fencing、外部版本和持久清理任务。Controller 保持现有请求/响应字段及显式 charset；资源服务器沿用 8.1 JWT 验证契约和 JWKS 缓存，不接受用户 Header。
- [ ] **Step 3:** 定点 IT、产品模块 `test`、`git diff --check` 通过后提交 `feat(cloud): serve and rebuild independent product search`。

## Task 7: 五应用真实旅程与故障恢复

**Files:** Modify `api-gateway/pom.xml`, `api-gateway/src/test/java/com/example/campusmarket/gateway/support/CloudApplicationCluster.java`, `CloudJourneyIT.java`, `CloudFailureRecoveryIT.java`; create `api-gateway/src/test/java/com/example/campusmarket/gateway/ProductReadJourneyIT.java`, `ProductReadFailureRecoveryIT.java`.

**Interfaces:** Cluster starts Eureka, identity, legacy, product read and Gateway on distinct random ports with real MySQL/Rabbit/SmartCN ES; client requests only Gateway.

- [ ] **Step 1:** 写真实 HTTP 失败旅程：登录、创建草稿/媒体、发布、等待搜索中文结果、下单使库存归零、验证搜索删失且订单快照未改变；Eureka 注册新服务、两条 GET 经 `lb://product-read-service`，直接产品端口无 Token 为 401。故障 IT 停 Rabbit、产品服务、ES 后恢复，检查交易写入不中断、搜索安全 503、积压最终清零、readiness 和未过期 Token 行为。运行定点 `verify` 见预期失败。
- [ ] **Step 2:** 将 Cluster 加入读库迁移、Rabbit/ES 容器及产品应用上下文、随机端口和受控停机/重启；避免加载别的模块 application.yml 或在产品服务启动时扫描市场 DataSource。测试断言真实 `application/json; charset=UTF-8` 与中文错误，所有外部协作测试保持未 skipped。
- [ ] **Step 3:** 运行 Gateway 定点旅程和现有四应用旅程，再运行 `mvnw.cmd -pl api-gateway -am verify`，只提交任务文件 `test(cloud): verify five-application product recovery`。

## Task 8: 本地运行、文档与完整验收

**Files:** Modify `compose.yaml`, `.env.example`, `README.md`, `docs/architecture.md`, `docs/migration-boundary.md`, `TROUBLESHOOTING.md`, `notes/learning-log.md`, `interview/question-bank.md`; main 文档中心只在验收后修改 `README.md` 与 `notes/learning-log.md`。

**Interfaces:** Compose 启动 Eureka、Gateway、identity、legacy、product read 五应用和当前所需固定依赖；文档明确 8.2 只拆搜索读取及 replay 操作。

- [ ] **Step 1:** 更新第五应用 Dockerfile jar 配置、环境变量、最小启动依赖与健康探针；示例凭据保持 `<replace-me>`，不加入真实密钥、Token、`.env`。运行 `docker compose config` 与隔离 Compose smoke，确认五应用 readiness、Eureka 4/4 注册及两个搜索路由。
- [ ] **Step 2:** 写中文运行、排障、事实/投影边界、replay 操作与故障记录，补学习日志和面试追问；用 `rg` 扫描凭据、私钥和本机路径；`git diff --check` 后只提交文档/Compose 文件。
- [ ] **Step 3:** 只读 `docker info` 成功后在 JDK 17 下串行运行 fresh `mvnw.cmd clean test` 和 `mvnw.cmd clean verify`；核对新产生的 Surefire/Failsafe XML 数量及 0 failures/errors/skipped、全部实验七交易回归与新五应用 IT。若失败按 systematic-debugging 找根因、补失败测试并修复，不把 skipped 当成功。
- [ ] **Step 4:** 复查活动树仅 `.gitignore` 与 `labs/08-spring-cloud-split/**`，`git diff --check`；将验收证据提交实验分支，并在 `main` 文档中心更新路线状态与数量。远端推送沿用用户已给的具体仓库/分支授权，提交前扫描敏感信息。
