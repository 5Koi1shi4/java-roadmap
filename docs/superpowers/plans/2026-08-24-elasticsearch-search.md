# Elasticsearch 商品搜索 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 创建独立、可复跑的商品搜索实验，以 MySQL 事务 Outbox 可靠同步 Elasticsearch，并提供 SmartCN 中文检索、在线重建与故障恢复证据。

**Architecture:** 商品和不可变搜索事件在同一 MySQL 事务提交；dispatcher 通过固定锁顺序、租约与 claim token 领取事件，并使用 Elasticsearch 外部版本执行至少一次幂等写入。搜索只读 Elasticsearch；重建通过一致性快照、Outbox 高水位补放、短暂写入门禁和原子别名切换得到可恢复的新索引。

**Tech Stack:** JDK 17、Maven Wrapper 3.9.11、Spring Boot 3.5.16、Spring Web、Spring JDBC、Flyway、MySQL 8.4、Elasticsearch/Java Client 8.18.8、`analysis-smartcn`、Actuator、Micrometer、JUnit 5、Mockito、AssertJ、Awaitility、Testcontainers 1.21.4。

## Global Constraints

- 实验分支固定为 `learning/elasticsearch-search`，活动树只允许根 `.gitignore` 与 `labs/05-elasticsearch-search/`；不得提交 `AGENTS.md`、其他实验或 `main` 文档。
- 执行前必须使用 `using-git-worktrees` 建立隔离工作树；新实验分支使用无父提交的独立历史，不从 `main` 携带文档中心文件。
- JDK 固定为 17；Maven Wrapper 固定为 Maven 3.9.11、wrapper 脚本 3.3.4。
- Spring Boot 固定为 3.5.16；Elasticsearch Java API Client 与服务端固定为 8.18.8。
- MySQL 是事实源，Elasticsearch 是可重建索引；写 API 成功只表示商品与 Outbox 同事务提交。
- 数据库加锁顺序固定为 `search_coordination → search_rebuild_job → product → search_outbox`；路径可以跳过无关表，禁止逆序。
- dispatcher 批量上限 50、租约 30 秒、最多尝试 5 次；失败退避固定为 1 秒、5 秒、30 秒、2 分钟。
- Elasticsearch 使用 `external_gte` 商品版本；允许重复请求，不允许旧版本覆盖、删除商品复活或旧 owner 修改新租约状态。
- 搜索固定过滤 `ON_SALE`；公开分页大小为 1–50，且 `(page * size) + size <= 10_000`。
- 运维接口默认关闭，只在 `search.maintenance.enabled=true` 时注册；不建设鉴权系统、管理前端或通用任务平台。
- 所有 HTTP JSON 显式返回 `application/json; charset=UTF-8`，未知字段和非法枚举快速失败。
- 单元测试不得启动 Spring；MySQL、Elasticsearch、Flyway、SmartCN、真实 HTTP、重建和故障恢复必须由 Testcontainers 集成测试验证。
- 集成测试共享容器但不共享业务状态：每个测试清空商品、Outbox、重建任务，重置 coordination 单例行，并删除测试创建的物理索引/别名；使用动态容器地址的 Spring 上下文必须在容器停止前关闭。
- 每个行为严格执行红—绿循环：先写失败测试并确认失败原因，再写最小实现，再运行同一测试通过，最后只提交本任务文件。
- 除 Git 工作树命令和 Task 12 的 main 文档命令外，所有 Maven、文件和 Git 命令都从隔离工作树的 `labs/05-elasticsearch-search/` 目录执行；计划中的仓库路径仍写完整相对路径。

---

## 文件结构

| 路径 | 职责 |
| --- | --- |
| `labs/05-elasticsearch-search/pom.xml` | 固定依赖、Surefire/Failsafe 分层和构建插件。 |
| `labs/05-elasticsearch-search/docker/elasticsearch/Dockerfile`、`labs/05-elasticsearch-search/compose.yaml` | 安装 SmartCN 的 Elasticsearch 8.18.8 与 MySQL 8.4 本地环境。 |
| `labs/05-elasticsearch-search/src/main/resources/db/migration/V1__search_schema.sql` | 商品、Outbox、协调行和重建任务表。 |
| `labs/05-elasticsearch-search/src/main/resources/elasticsearch/products-index.json` | 严格映射、SmartCN 分析器和单节点索引设置。 |
| `labs/05-elasticsearch-search/src/main/java/com/example/search/domain/*` | 无框架依赖的商品、状态和索引快照。 |
| `labs/05-elasticsearch-search/src/main/java/com/example/search/application/product/*` | 商品创建、更新、删除用例与端口。 |
| `labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/*` | Outbox 领取、dispatcher、重试和索引写入端口。 |
| `labs/05-elasticsearch-search/src/main/java/com/example/search/application/search/*` | 搜索条件、排序、结果和搜索端口。 |
| `labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/*` | 重建、高水位、切换、一致性检查和修复用例。 |
| `labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/persistence/*` | 固定锁顺序、条件 SQL、租约和任务状态的 JDBC 实现。 |
| `labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/elasticsearch/*` | 索引创建、bulk 外部版本写入、查询、扫描和别名操作。 |
| `labs/05-elasticsearch-search/src/main/java/com/example/search/api/*` | 商品、搜索、运维 HTTP 接口与稳定错误结构。 |
| `labs/05-elasticsearch-search/src/main/java/com/example/search/observability/*` | Micrometer 指标适配和调度器。 |
| `labs/05-elasticsearch-search/src/test/java/com/example/search/unit/*` | 纯 Java 单元测试。 |
| `labs/05-elasticsearch-search/src/test/java/com/example/search/integration/*` | 共享真实 MySQL、SmartCN Elasticsearch 和 HTTP 测试。 |
| `labs/05-elasticsearch-search/README.md`、`labs/05-elasticsearch-search/TROUBLESHOOTING.md` | 启动、接口、可靠性边界、重建和排障说明。 |

### Task 1: 建立隔离实验、容器基线和数据库架构

**Files:**
- Create: `labs/05-elasticsearch-search/pom.xml`
- Create: `labs/05-elasticsearch-search/mvnw.cmd`
- Create: `labs/05-elasticsearch-search/.mvn/wrapper/maven-wrapper.properties`
- Create: `labs/05-elasticsearch-search/.env.example`
- Create: `labs/05-elasticsearch-search/compose.yaml`
- Create: `labs/05-elasticsearch-search/docker/elasticsearch/Dockerfile`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/SearchApplication.java`
- Create: `labs/05-elasticsearch-search/src/main/resources/application.yml`
- Create: `labs/05-elasticsearch-search/src/main/resources/db/migration/V1__search_schema.sql`
- Create: `labs/05-elasticsearch-search/src/test/java/com/example/search/integration/SharedMySqlContainer.java`
- Test: `labs/05-elasticsearch-search/src/test/java/com/example/search/integration/SearchSchemaIT.java`

**Interfaces:**
- Consumes: JDK 17、Docker Engine、仓库根 `.gitignore`。
- Produces: 独立实验分支；`product`、`search_outbox`、`search_coordination`、`search_rebuild_job` 四张表及固定索引。

- [ ] **Step 1: 创建无父历史的隔离工作树**

先按 `using-git-worktrees` 检查现有工作树，再执行等价的安全步骤：

```powershell
git worktree add --detach .worktrees/elasticsearch-search main
git -C .worktrees/elasticsearch-search switch --orphan learning/elasticsearch-search
git -C .worktrees/elasticsearch-search restore --source=feat/order-mq-reliable-messaging --staged --worktree -- .gitignore
```

Expected: 新工作树位于 `.worktrees/elasticsearch-search`，`git rev-parse --verify HEAD` 因 unborn branch 失败，活动树没有 `README.md`、`docs/`、`notes/`、`interview/` 或其他 `labs/*`。

- [ ] **Step 2: 写数据库架构失败测试**

```java
@Test
void createsSearchTablesAndClaimIndexes() {
    assertThat(tableCount("product")).isOne();
    assertThat(tableCount("search_outbox")).isOne();
    assertThat(indexCount("search_outbox", "uk_search_outbox_product_version_type")).isOne();
    assertThat(indexCount("search_outbox", "idx_search_outbox_claim")).isOne();
    assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM search_coordination WHERE id = 1", Integer.class)).isOne();
}
```

- [ ] **Step 3: 运行测试确认项目尚不存在**

Run: `./mvnw.cmd -Dit.test=SearchSchemaIT verify`

Expected: FAIL，提示 `pom.xml`、wrapper 或 `SearchSchemaIT` 不存在；不能以 Docker 跳过作为预期失败。

- [ ] **Step 4: 创建 Maven、应用配置和容器文件**

`pom.xml` 加入 `spring-boot-starter-web`、`spring-boot-starter-jdbc`、`spring-boot-starter-validation`、`spring-boot-starter-actuator`、`co.elastic.clients:elasticsearch-java`、`org.elasticsearch.client:elasticsearch-rest-client`、Flyway、MySQL driver、`spring-boot-starter-test`、Testcontainers `mysql`、`elasticsearch`、`toxiproxy` 与 `junit-jupiter`。Surefire 排除 `*IT`，Failsafe 在 `integration-test`/`verify` 执行 `*IT`。

wrapper 属性固定为：

```properties
wrapperVersion=3.3.4
distributionType=only-script
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip
```

`application.yml` 的初始外部配置为：

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:mysql://localhost:3311/product_search}
    username: ${DB_USERNAME:product_search}
    password: ${DB_PASSWORD:}
  elasticsearch:
    uris: ${ELASTICSEARCH_URIS:http://localhost:9200}
  jackson:
    deserialization:
      fail-on-unknown-properties: true
server:
  servlet:
    encoding:
      charset: UTF-8
      enabled: true
      force: true
search:
  batch-size: 50
  lease-duration: 30s
  request-timeout: 10s
  dispatch-delay: 1s
  maintenance:
    enabled: false
management:
  endpoints:
    web:
      exposure:
        include: health,metrics
```

自定义镜像文件固定为：

```dockerfile
FROM docker.elastic.co/elasticsearch/elasticsearch:8.18.8
RUN bin/elasticsearch-plugin install --batch analysis-smartcn
```

`compose.yaml` 使用 `mysql:8.4` 和该 Dockerfile，Elasticsearch 设置 `discovery.type=single-node`、`xpack.security.enabled=false`、`ES_JAVA_OPTS=-Xms512m -Xmx512m`，端口默认 3311 与 9200；`.env.example` 只写占位符。

- [ ] **Step 5: 创建精确数据库迁移**

```sql
CREATE TABLE search_coordination (
  id TINYINT PRIMARY KEY,
  dispatcher_paused BOOLEAN NOT NULL DEFAULT FALSE,
  active_rebuild_id CHAR(36) NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT chk_search_coordination_singleton CHECK (id = 1)
);
INSERT INTO search_coordination(id, dispatcher_paused) VALUES (1, FALSE);

CREATE TABLE product (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(120) NOT NULL,
  subtitle VARCHAR(255) NULL,
  description TEXT NOT NULL,
  category_code VARCHAR(64) NOT NULL,
  category_name VARCHAR(120) NOT NULL,
  price DECIMAL(12,2) NOT NULL,
  status VARCHAR(16) NOT NULL,
  version BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT chk_product_price CHECK (price >= 0),
  CONSTRAINT chk_product_status CHECK (status IN ('ON_SALE','OFF_SHELF','DELETED')),
  CONSTRAINT chk_product_version CHECK (version > 0)
);

CREATE TABLE search_outbox (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id CHAR(36) NOT NULL,
  product_id BIGINT NOT NULL,
  product_version BIGINT NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  payload JSON NOT NULL,
  status VARCHAR(16) NOT NULL,
  owner VARCHAR(128) NULL,
  claim_token CHAR(36) NULL,
  lease_until TIMESTAMP(6) NULL,
  available_at TIMESTAMP(6) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  last_error VARCHAR(1024) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  completed_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_search_outbox_event_id (event_id),
  UNIQUE KEY uk_search_outbox_product_version_type
    (product_id, product_version, event_type),
  KEY idx_search_outbox_claim (status, available_at, lease_until, id),
  CONSTRAINT chk_search_outbox_status
    CHECK (status IN ('NEW','PROCESSING','COMPLETED','FAILED')),
  CONSTRAINT chk_search_outbox_type
    CHECK (event_type IN ('PRODUCT_UPSERT','PRODUCT_DELETE'))
);

CREATE TABLE search_rebuild_job (
  job_id CHAR(36) PRIMARY KEY,
  target_index VARCHAR(255) NOT NULL,
  status VARCHAR(16) NOT NULL,
  phase VARCHAR(32) NOT NULL,
  owner VARCHAR(128) NOT NULL,
  lease_until TIMESTAMP(6) NOT NULL,
  start_watermark BIGINT NULL,
  final_watermark BIGINT NULL,
  imported_count BIGINT NOT NULL DEFAULT 0,
  difference_count BIGINT NOT NULL DEFAULT 0,
  last_error VARCHAR(1024) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  completed_at TIMESTAMP(6) NULL,
  CONSTRAINT chk_search_rebuild_status
    CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED'))
);
```

- [ ] **Step 6: 运行真实 MySQL 架构测试**

Run: `./mvnw.cmd -Dit.test=SearchSchemaIT verify`

`SharedMySqlContainer` 只启动一个静态 `MySQLContainer("mysql:8.4")`，注册 datasource 动态属性并提供按外键安全顺序清理四张表、重置 coordination 的方法。

Expected: PASS；Flyway 在 `mysql:8.4` 创建四张表、组合唯一键、领取索引和单例协调行，0 skipped。

- [ ] **Step 7: 提交独立实验骨架**

```powershell
git add -- .gitignore labs/05-elasticsearch-search
git commit -m "chore(search): initialize Elasticsearch product search lab"
git ls-tree -r --name-only HEAD
```

Expected: 提交树只列出 `.gitignore` 与 `labs/05-elasticsearch-search/**`。

### Task 2: 实现商品领域校验和事务内 Outbox

**Files:**
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/domain/ProductStatus.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/domain/Product.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/domain/ProductDetails.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/domain/ProductSearchSnapshot.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/product/CreateProductCommand.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/product/UpdateProductCommand.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/product/ProductView.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/product/ProductRepository.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/product/ProductCommandService.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/product/ProductNotFoundException.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/product/ProductVersionConflictException.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/SearchOutboxRepository.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/SearchCoordinationRepository.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/OutboxEventType.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/persistence/JdbcProductRepository.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/persistence/JdbcSearchCoordinationRepository.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/persistence/JdbcSearchOutboxRepository.java`
- Test: `labs/05-elasticsearch-search/src/test/java/com/example/search/unit/ProductTest.java`
- Test: `labs/05-elasticsearch-search/src/test/java/com/example/search/integration/ProductPersistenceIT.java`

**Interfaces:**
- Produces: `ProductView create(CreateProductCommand)`、`ProductView update(long, UpdateProductCommand)`、`void delete(long, long expectedVersion)`。
- Produces: `SearchCoordinationRepository.lockShared()`；`SearchOutboxRepository.append(ProductSearchSnapshot, OutboxEventType)`。
- Invariant: 每个成功商品版本与唯一不可变 Outbox JSON 快照同事务提交。

- [ ] **Step 1: 写领域和事务失败测试**

```java
@Test
void rejectsInvalidPriceAndDeletedCreationState() {
    assertThatThrownBy(() -> new ProductDetails("书", null, "教材", "BOOK", "图书",
            new BigDecimal("-0.01"), ProductStatus.ON_SALE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ProductDetails("书", null, "教材", "BOOK", "图书",
            BigDecimal.TEN, ProductStatus.DELETED))
        .isInstanceOf(IllegalArgumentException.class);
}

@Test
void commitsProductAndOutboxInOneTransaction() {
    ProductView created = service.create(validCreateCommand());
    assertThat(created.version()).isEqualTo(1);
    assertThat(outbox(created.id())).singleElement()
        .satisfies(row -> assertThat(row.get("product_version")).isEqualTo(1L));
}
```

- [ ] **Step 2: 运行测试确认类型和事务用例缺失**

Run: `./mvnw.cmd -Dtest=ProductTest test; ./mvnw.cmd -Dit.test=ProductPersistenceIT verify`

Expected: 两条命令均 FAIL，分别提示领域类型和商品服务不存在。

- [ ] **Step 3: 实现无框架领域记录**

```java
public record ProductDetails(String name, String subtitle, String description,
        String categoryCode, String categoryName, BigDecimal price, ProductStatus status) {
    public ProductDetails {
        name = requireText(name, "name");
        description = requireText(description, "description");
        categoryCode = requireText(categoryCode, "categoryCode");
        categoryName = requireText(categoryName, "categoryName");
        if (price == null || price.signum() < 0 || price.scale() > 2) {
            throw new IllegalArgumentException("price must be non-negative with at most 2 decimals");
        }
        if (status == null || status == ProductStatus.DELETED) {
            throw new IllegalArgumentException("creation status must be ON_SALE or OFF_SHELF");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
```

`ProductSearchSnapshot` 固定保存商品全部索引字段和 `sourceVersion`；构造时拒绝非正 ID/版本、空时间和字段缺失。

字符串边界固定为 name 1–120、subtitle 0–255、categoryCode 1–64、categoryName 1–120；description 非空，price 不超过两位小数。`Product` 允许从数据库读取 `DELETED`，但 `ProductDetails` 永远拒绝客户端直接提交 `DELETED`。

- [ ] **Step 4: 实现固定锁顺序的商品事务**

```java
@Transactional
public ProductView create(CreateProductCommand command) {
    coordinationRepository.lockShared();
    Product product = productRepository.insert(command.details(), clock.instant());
    outboxRepository.append(ProductSearchSnapshot.from(product), OutboxEventType.PRODUCT_UPSERT);
    return ProductView.from(product);
}

@Transactional
public ProductView update(long id, UpdateProductCommand command) {
    coordinationRepository.lockShared();
    int changed = productRepository.updateIfVersionMatches(id, command.expectedVersion(),
            command.details(), clock.instant());
    if (changed == 0) throw resolveMutationFailure(id, command.expectedVersion());
    Product product = productRepository.findById(id).orElseThrow();
    outboxRepository.append(ProductSearchSnapshot.from(product), OutboxEventType.PRODUCT_UPSERT);
    return ProductView.from(product);
}
```

删除按相同顺序执行 `status='DELETED', version=version+1` 条件更新，再追加 `PRODUCT_DELETE` tombstone 快照。`lockShared()` 使用：

```sql
SELECT id FROM search_coordination WHERE id = 1 FOR SHARE;
```

- [ ] **Step 5: 补充并发版本和回滚测试并运行**

测试两个事务使用同一 `expectedVersion` 更新，断言一个成功、一个 409 对应异常。原子回滚用例先为现有商品人工插入 `(product_id, version=2, PRODUCT_UPSERT)` 冲突事件，再调用 version 1 更新；Outbox 组合唯一键抛错后断言商品仍为 version 1，证明前置商品 UPDATE 同事务回滚。

Run: `./mvnw.cmd -Dtest=ProductTest test; ./mvnw.cmd -Dit.test=ProductPersistenceIT verify`

Expected: PASS；创建、更新、逻辑删除、版本冲突和事务回滚全部通过，0 skipped。

- [ ] **Step 6: 提交商品事务切片**

```powershell
git add -- labs/05-elasticsearch-search/src/main/java/com/example/search/domain labs/05-elasticsearch-search/src/main/java/com/example/search/application/product labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/persistence labs/05-elasticsearch-search/src/test/java/com/example/search/unit/ProductTest.java labs/05-elasticsearch-search/src/test/java/com/example/search/integration/ProductPersistenceIT.java
git commit -m "feat(search): persist products and search outbox atomically"
```

### Task 3: 钉住 Outbox 租约、claim token 和数据库锁顺序

**Files:**
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/OutboxStatus.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/SearchOutboxEvent.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/ClaimedOutboxEvent.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/OutboxClaimService.java`
- Modify: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/SearchCoordinationRepository.java`
- Modify: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/SearchOutboxRepository.java`
- Modify: `labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/persistence/JdbcSearchOutboxRepository.java`
- Modify: `labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/persistence/JdbcSearchCoordinationRepository.java`
- Test: `labs/05-elasticsearch-search/src/test/java/com/example/search/integration/OutboxLeaseIT.java`
- Test: `labs/05-elasticsearch-search/src/test/java/com/example/search/integration/DatabaseLockOrderIT.java`

**Interfaces:**
- Produces: `List<ClaimedOutboxEvent> claim(String owner, int limit)`。
- Produces: `boolean complete(UUID eventId, UUID token)`、`boolean reschedule(UUID eventId, UUID token, Duration delay, String reason)`、`boolean fail(UUID eventId, UUID token, String reason)`。
- Produces: `boolean lockSharedAndReadDispatcherPaused()`，在当前事务取得 coordination 共享锁并返回 pause 状态。
- `ClaimedOutboxEvent` 固定携带数据库序号、事件 UUID、商品版本、快照、attempt 和 claim token。
- `SearchOutboxEvent` 签名固定为 `SearchOutboxEvent(long id, UUID eventId, long productId, long productVersion, OutboxEventType eventType, ProductSearchSnapshot snapshot, int attemptCount)`；`ClaimedOutboxEvent` 在这些字段后增加 owner、claimToken 和 leaseUntil，并直接提供同名访问器。

- [ ] **Step 1: 写双实例领取和迟到 owner 失败测试**

```java
@Test
void claimsEachEventOnceAndRejectsLateOwner() throws Exception {
    List<ClaimedOutboxEvent> first = claimInConnection("node-a", 50);
    expire(first.getFirst().eventId());
    ClaimedOutboxEvent second = claimInConnection("node-b", 50).getFirst();
    assertThat(repository.complete(first.getFirst().eventId(), first.getFirst().claimToken())).isFalse();
    assertThat(repository.complete(second.eventId(), second.claimToken())).isTrue();
}
```

`DatabaseLockOrderIT` 使用两个 executor 和两个真实事务，断言商品写入、pause 更新和 Outbox 领取在 5 秒内结束且未抛 `DeadlockLoserDataAccessException`。

- [ ] **Step 2: 运行测试确认领取实现不完整**

Run: `./mvnw.cmd -Dit.test=OutboxLeaseIT,DatabaseLockOrderIT verify`

Expected: FAIL，提示领取接口、token 条件完成或 coordination pause 读取缺失。

- [ ] **Step 3: 实现短事务条件领取**

`OutboxClaimService.claim` 标注 `@Transactional`，先取得 coordination 共享锁并读取 pause；暂停时返回空列表。未暂停时执行：

```sql
SELECT id FROM search_outbox
WHERE available_at <= UTC_TIMESTAMP(6)
  AND (status = 'NEW'
       OR (status = 'PROCESSING' AND lease_until < UTC_TIMESTAMP(6)))
ORDER BY id
LIMIT ?
FOR UPDATE SKIP LOCKED;
```

为每行生成独立 UUID token，并在同一事务更新 `status='PROCESSING'`、owner、token、`lease_until=TIMESTAMPADD(SECOND,30,UTC_TIMESTAMP(6))`、`attempt_count=attempt_count+1`。limit 必须校验为 `1..50`。

- [ ] **Step 4: 实现 token fencing 状态更新**

```sql
UPDATE search_outbox
SET status='COMPLETED', owner=NULL, claim_token=NULL, lease_until=NULL,
    completed_at=UTC_TIMESTAMP(6), last_error=NULL
WHERE event_id=? AND status='PROCESSING' AND claim_token=?;
```

重排和失败 SQL 使用相同 `event_id + status + claim_token` 条件；重排设置 `NEW` 与 `available_at=TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6))`，Duration 先转换为微秒，失败设置 `FAILED`。所有原因先去除换行并截断到 1024 字符。

- [ ] **Step 5: 运行真实并发测试**

Run: `./mvnw.cmd -Dit.test=OutboxLeaseIT,DatabaseLockOrderIT verify`

Expected: PASS；两个领取者所得事件集合无交集，过期可接管，旧 token 三类更新均影响 0 行，pause 与 claim 没有竞态或死锁。

- [ ] **Step 6: 提交数据库并发基线**

```powershell
git add -- labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/persistence labs/05-elasticsearch-search/src/test/java/com/example/search/integration/OutboxLeaseIT.java labs/05-elasticsearch-search/src/test/java/com/example/search/integration/DatabaseLockOrderIT.java
git commit -m "feat(search): fence search outbox claims"
```

### Task 4: 建立 SmartCN 索引和外部版本幂等写入

**Files:**
- Create: `labs/05-elasticsearch-search/src/main/resources/elasticsearch/products-index.json`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/IndexMutation.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/IndexWriteResult.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/SearchIndexWriter.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/AliasTargets.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/SearchIndexBootstrap.java`
- Modify: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/SearchCoordinationRepository.java`
- Modify: `labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/persistence/JdbcSearchCoordinationRepository.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/elasticsearch/ElasticsearchIndexManager.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/elasticsearch/ElasticsearchSearchIndexWriter.java`
- Create: `labs/05-elasticsearch-search/src/test/java/com/example/search/integration/SharedSearchContainers.java`
- Test: `labs/05-elasticsearch-search/src/test/java/com/example/search/integration/ElasticsearchIndexIT.java`
- Test: `labs/05-elasticsearch-search/src/test/java/com/example/search/integration/ExternalVersionIT.java`

**Interfaces:**
- Produces: `SearchIndexBootstrap.ensureInitialized()`；`SearchCoordinationRepository.lockExclusive()`；`ElasticsearchIndexManager.createPhysicalIndex(UUID jobId)`、`refresh(String index)`、`aliasTargets()`。
- Produces: `List<IndexWriteResult> bulkWrite(String target, List<IndexMutation> mutations)`。
- `IndexWriteResult.Outcome`: `APPLIED`、`SUPERSEDED`、`RETRYABLE_FAILURE`、`PERMANENT_FAILURE`。

- [ ] **Step 1: 写 SmartCN、严格映射和版本失败测试**

```java
@Test
void installsSmartCnAndRejectsUnknownFields() {
    assertThat(pluginNames()).contains("analysis-smartcn");
    assertThatThrownBy(() -> indexRaw("{\"productId\":1,\"unknown\":true}"))
        .hasMessageContaining("strict_dynamic_mapping_exception");
}

@Test
void staleVersionCannotOverwriteOrReviveTombstone() {
    writer.bulkWrite(index, List.of(upsert(9L, 2L, "新名称")));
    writer.bulkWrite(index, List.of(tombstone(9L, 3L)));
    IndexWriteResult stale = writer.bulkWrite(index, List.of(upsert(9L, 2L, "旧名称"))).getFirst();
    assertThat(stale.outcome()).isEqualTo(SUPERSEDED);
    assertThat(read(index, 9L).status()).isEqualTo("DELETED");
}
```

- [ ] **Step 2: 运行测试确认索引管理器缺失**

Run: `./mvnw.cmd -Dit.test=ElasticsearchIndexIT,ExternalVersionIT verify`

Expected: FAIL，提示共享 Elasticsearch 容器、索引定义或 writer 不存在。

- [ ] **Step 3: 创建真实 SmartCN Testcontainer**

```java
static final GenericContainer<?> ELASTICSEARCH = new GenericContainer<>(
        new ImageFromDockerfile("java-roadmap/elasticsearch-smartcn:8.18.8", false)
            .withFileFromPath("Dockerfile", Path.of("docker/elasticsearch/Dockerfile")))
    .withEnv("discovery.type", "single-node")
    .withEnv("xpack.security.enabled", "false")
    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
    .withExposedPorts(9200)
    .waitingFor(Wait.forHttp("/_cluster/health").forPort(9200).forStatusCode(200));
```

容器静态共享并在 JVM shutdown hook 中关闭；MySQL 直接复用 `SharedMySqlContainer.MYSQL`。所有搜索集成测试通过 `@DynamicPropertySource` 注册 `spring.elasticsearch.uris`。

- [ ] **Step 4: 写固定索引定义并实现初始化**

`products-index.json` 设置 `number_of_shards=1`、`number_of_replicas=0`、`dynamic=strict`；`productId/sourceVersion` 为 long，`name`、`subtitle`、`description` 使用 `smartcn`，`name.raw` 为 keyword，`categoryCode/status` 为 keyword，`categoryName` 主字段为 keyword 且 `categoryName.text` 使用 smartcn，`price` 为 `scaled_float` 100，`createdAt/updatedAt` 为 date。

`SearchIndexBootstrap.ensureInitialized()` 在 coordination 排他锁内再次检查别名；缺失时创建 `products-vbootstrap`，再用一个 aliases 请求同时添加 `products-read` 和 `products-write`。并发初始化者取得锁后读取现有别名并返回同一物理索引；`ElasticsearchIndexManager` 只负责 ES API，不直接访问 JDBC。

- [ ] **Step 5: 实现 external_gte bulk writer**

```java
new BulkOperation.Builder().index(i -> i
    .index(target)
    .id(Long.toString(mutation.productId()))
    .version(mutation.sourceVersion())
    .versionType(VersionType.ExternalGte)
    .document(mutation.document())).build();
```

逐 item 将 2xx 映射为 `APPLIED`，`version_conflict_engine_exception` 映射为 `SUPERSEDED`，429/5xx 映射为 `RETRYABLE_FAILURE`，严格映射/解析错误映射为 `PERMANENT_FAILURE`。

- [ ] **Step 6: 运行真实 Elasticsearch 测试**

Run: `./mvnw.cmd -Dit.test=ElasticsearchIndexIT,ExternalVersionIT verify`

Expected: PASS；插件真实加载、未知字段被拒绝、相同版本可重复、旧版本无法覆盖新值或复活 tombstone，0 skipped。

- [ ] **Step 7: 提交索引与版本写入**

```powershell
git add -- labs/05-elasticsearch-search/src/main/resources/elasticsearch labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/AliasTargets.java labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/SearchIndexBootstrap.java labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/elasticsearch labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/persistence/JdbcSearchCoordinationRepository.java labs/05-elasticsearch-search/src/test/java/com/example/search/integration/SharedSearchContainers.java labs/05-elasticsearch-search/src/test/java/com/example/search/integration/ElasticsearchIndexIT.java labs/05-elasticsearch-search/src/test/java/com/example/search/integration/ExternalVersionIT.java
git commit -m "feat(search): index products with external versions"
```

### Task 5: 完成 dispatcher、部分失败和有界重试

**Files:**
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/RetrySchedule.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/SyncFailureClassifier.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/DispatchSummary.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/OutboxDispatcher.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/SearchSyncMetrics.java`
- Test: `labs/05-elasticsearch-search/src/test/java/com/example/search/unit/OutboxDispatcherTest.java`
- Test: `labs/05-elasticsearch-search/src/test/java/com/example/search/integration/ReliableIndexSyncIT.java`

**Interfaces:**
- Consumes: `OutboxClaimService.claim(owner, 50)` 与 `SearchIndexWriter.bulkWrite("products-write", mutations)`。
- Produces: `DispatchSummary dispatchOnce()`，包含 claimed、completed、rescheduled、failed、fenced 数量。
- Produces: `Optional<Duration> delayAfterFailure(int attempt)`；attempt 1–4 返回固定退避，attempt 5 返回 empty。
- Produces: `SearchSyncMetrics.recordDispatch(IndexWriteResult.Outcome outcome, int count, Duration elapsed)`、`recordQuery(boolean success, Duration elapsed)`、`recordRebuild(boolean success, Duration elapsed, long differences)`；Task 10 提供 Micrometer 实现，单元测试使用 mock。

- [ ] **Step 1: 写部分失败、退避和 fencing 失败测试**

```java
@Test
void completesSuccessfulItemsAndRetriesOnlyTransientFailures() {
    when(writer.bulkWrite(eq("products-write"), anyList())).thenReturn(List.of(
        applied(eventA), retryable(eventB, "429"), permanent(eventC, "mapping")));
    DispatchSummary summary = dispatcher.dispatchOnce();
    assertThat(summary).isEqualTo(new DispatchSummary(3, 1, 1, 1, 0));
    verify(repository).complete(eventA.eventId(), eventA.claimToken());
    verify(repository).reschedule(eventB.eventId(), eventB.claimToken(), Duration.ofSeconds(1), "429");
    verify(repository).fail(eventC.eventId(), eventC.claimToken(), "mapping");
}

@Test
void synchronizesCreateUpdateAndDeleteToTheirHighestVersion() {
    ProductView created = productService.create(createCommand("初始名称"));
    dispatcher.dispatchOnce();
    productService.update(created.id(), updateCommand(created.version(), "更新名称"));
    dispatcher.dispatchOnce();
    productService.delete(created.id(), 2L);
    dispatcher.dispatchOnce();
    indexManager.refresh("products-write");
    assertThat(indexed(created.id()).sourceVersion()).isEqualTo(3L);
    assertThat(indexed(created.id()).status()).isEqualTo("DELETED");
}
```

- [ ] **Step 2: 运行单元和端到端同步测试确认失败**

Run: `./mvnw.cmd -Dtest=OutboxDispatcherTest test; ./mvnw.cmd -Dit.test=ReliableIndexSyncIT verify`

Expected: FAIL，提示 dispatcher 和 retry schedule 缺失。

- [ ] **Step 3: 实现固定重试表**

```java
public Optional<Duration> delayAfterFailure(int attempt) {
    return switch (attempt) {
        case 1 -> Optional.of(Duration.ofSeconds(1));
        case 2 -> Optional.of(Duration.ofSeconds(5));
        case 3 -> Optional.of(Duration.ofSeconds(30));
        case 4 -> Optional.of(Duration.ofMinutes(2));
        default -> Optional.empty();
    };
}
```

网络级 bulk 异常由 `SyncFailureClassifier` 分类：连接、超时、429、5xx 可恢复；序列化、严格映射和非法快照不可恢复。原因必须清洗并截断。

- [ ] **Step 4: 实现逐 item dispatcher**

dispatcher 只对每条结果调用一次 token 条件更新。`APPLIED/SUPERSEDED` 完成；可恢复且 attempt 小于 5 时按数据库当前时间加固定退避重排；attempt 5 或永久失败进入 `FAILED`。token 更新返回 false 时计入 fenced，不再次覆盖。

- [ ] **Step 5: 验证真实最终同步**

`ReliableIndexSyncIT` 创建、连续更新和逻辑删除商品，显式调用 dispatcher 并 refresh；断言索引最终版本单调、重复 dispatch 无副作用，搜索别名中的删除文档状态为 `DELETED`。

Run: `./mvnw.cmd -Dtest=OutboxDispatcherTest test; ./mvnw.cmd -Dit.test=ReliableIndexSyncIT verify`

Expected: PASS；部分失败不污染同批成功项，attempt 5 进入 FAILED，真实 MySQL 到 Elasticsearch 链路通过且无 skipped。

- [ ] **Step 6: 提交可靠同步链路**

```powershell
git add -- labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync labs/05-elasticsearch-search/src/test/java/com/example/search/unit/OutboxDispatcherTest.java labs/05-elasticsearch-search/src/test/java/com/example/search/integration/ReliableIndexSyncIT.java
git commit -m "feat(search): dispatch outbox with bounded retries"
```

### Task 6: 构建一致性快照和高水位追平

**Files:**
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/RebuildStatus.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/RebuildPhase.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/RebuildJob.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/PreparedRebuild.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/RebuildProgressListener.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/RebuildAlreadyRunningException.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/RebuildLeaseLostException.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/RebuildJobRepository.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/SearchRebuildPreparer.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/persistence/JdbcRebuildJobRepository.java`
- Modify: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/product/ProductRepository.java`
- Modify: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/SearchCoordinationRepository.java`
- Modify: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/SearchOutboxRepository.java`
- Modify: `labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/persistence/JdbcProductRepository.java`
- Modify: `labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/persistence/JdbcSearchCoordinationRepository.java`
- Modify: `labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/persistence/JdbcSearchOutboxRepository.java`
- Test: `labs/05-elasticsearch-search/src/test/java/com/example/search/integration/RebuildPreparationIT.java`

**Interfaces:**
- Produces: `UUID start(String owner)`、`PreparedRebuild prepare(UUID jobId)`。
- Produces: `List<Product> findPageAfter(long lastId, int size)`、`long highWatermark()`、`List<SearchOutboxEvent> eventsBetween(long exclusiveStart, long inclusiveEnd, int size)`。
- Produces: `Optional<UUID> activeRebuildId()`、`void setActiveRebuildId(UUID)`、`boolean clearActiveRebuildId(UUID)`，均要求调用者已按顺序锁定 coordination。
- `PreparedRebuild` 固定包含 jobId、targetIndex、startWatermark、preparedWatermark、importedCount。
- `RebuildPhase` 固定为 `CREATED`、`SNAPSHOT`、`CATCH_UP`、`CUTOVER`、`FINISHED`；对外状态仍只使用 `PENDING/RUNNING/COMPLETED/FAILED`。

- [ ] **Step 1: 写快照期间并发变更失败测试**

```java
@Test
void replaysEveryEventAfterSnapshotWatermark() {
    UUID job = preparer.start("rebuild-a");
    hook.afterStartWatermark(() -> service.update(productId, updateAtVersion(1, "并发新名称")));
    PreparedRebuild prepared = preparer.prepare(job);
    indexManager.refresh(prepared.targetIndex());
    assertThat(indexed(prepared.targetIndex(), productId).sourceVersion()).isEqualTo(2);
    assertThat(prepared.preparedWatermark()).isGreaterThan(prepared.startWatermark());
}
```

测试 hook 仅由测试构造器注入 `RebuildProgressListener.afterStartWatermark(UUID jobId, long watermark)`，生产 Bean 注入 `RebuildProgressListener.NOOP`；不得在生产类暴露静态测试开关。

- [ ] **Step 2: 运行测试确认重建端口缺失**

Run: `./mvnw.cmd -Dit.test=RebuildPreparationIT verify`

Expected: FAIL，提示任务仓储、快照分页或目标索引导入不存在。

- [ ] **Step 3: 实现单实例任务领取**

短事务按 `coordination → rebuild_job` 顺序取得锁；`active_rebuild_id` 非空且对应任务租约未过期时抛 `RebuildAlreadyRunningException`。否则插入 `PENDING/CREATED` 任务、写 active ID，并条件更新为 `RUNNING/SNAPSHOT` 与 30 秒租约。每完成一页快照或事件补放都以 owner 条件续租 30 秒；续租影响 0 行立即停止旧 runner。

- [ ] **Step 4: 实现一致性只读快照导入**

`prepare` 先记录 `MAX(search_outbox.id)` 为 start watermark，再在一个 `REPEATABLE_READ, readOnly=true` 事务中按 `product.id > lastId ORDER BY id LIMIT 500` 扫描。每页转换为 `IndexMutation`，直接写目标物理索引；失败立即把任务标记 FAILED，原读写别名不变。

- [ ] **Step 5: 实现高水位补放**

快照结束后记录 prepared watermark，并按 `id > start AND id <= prepared ORDER BY id LIMIT 500` 读取不可变 Outbox，使用相同 external_gte writer 写目标索引。更新 job phase 为 `CATCH_UP`、起始水位、导入数和当前水位。

- [ ] **Step 6: 运行高水位集成测试**

Run: `./mvnw.cmd -Dit.test=RebuildPreparationIT verify`

Expected: PASS；快照前、快照中和快照后的更新都在目标物理索引得到最高商品版本，当前别名保持不变，0 skipped。

- [ ] **Step 7: 提交重建准备阶段**

```powershell
git add -- labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance labs/05-elasticsearch-search/src/main/java/com/example/search/application/product/ProductRepository.java labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/SearchCoordinationRepository.java labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/SearchOutboxRepository.java labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/persistence labs/05-elasticsearch-search/src/test/java/com/example/search/integration/RebuildPreparationIT.java
git commit -m "feat(search): rebuild from snapshot and outbox watermark"
```

### Task 7: 完成 pause、排他切换和崩溃恢复

**Files:**
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/IndexedProductVersion.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/RebuildValidation.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/RebuildValidator.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/RebuildValidationException.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/SplitAliasException.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/SearchRebuildCutover.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/SearchRebuildRecovery.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/SearchRebuildRunner.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/elasticsearch/ElasticsearchIndexScanner.java`
- Modify: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/SearchCoordinationRepository.java`
- Modify: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/SearchOutboxRepository.java`
- Modify: `labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/persistence/JdbcSearchCoordinationRepository.java`
- Modify: `labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/persistence/JdbcRebuildJobRepository.java`
- Modify: `labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/elasticsearch/ElasticsearchIndexManager.java`
- Test: `labs/05-elasticsearch-search/src/test/java/com/example/search/integration/RebuildCutoverIT.java`
- Test: `labs/05-elasticsearch-search/src/test/java/com/example/search/integration/RebuildRecoveryIT.java`

**Interfaces:**
- Produces: `RebuildJob cutover(PreparedRebuild prepared)`、`void recoverInterruptedCutover()`。
- Produces: `SearchCoordinationRepository.setDispatcherPaused(boolean)`、`lockExclusive()`；`SearchOutboxRepository.hasUnexpiredProcessing()`。
- Produces: `AliasTargets aliasTargets()`、`void swapReadWriteAliases(String expectedOld, String target)`。
- Produces: `RebuildValidation validate(String targetIndex, long finalWatermark)` 和 `List<IndexedProductVersion> scanAllVersions(String index, int batchSize)`；扫描固定 PIT + search_after、batch 500。

- [ ] **Step 1: 写切换互斥和失败保留测试**

```java
@Test
void blocksWritesDuringFinalWatermarkAndSwitchesBothAliasesAtomically() {
    PreparedRebuild prepared = prepareWithConcurrentWrites();
    RebuildJob completed = cutover.cutover(prepared);
    assertThat(completed.status()).isEqualTo(COMPLETED);
    assertThat(indexManager.aliasTargets()).isEqualTo(
        new AliasTargets(prepared.targetIndex(), prepared.targetIndex()));
    assertThat(validator.validate(prepared.targetIndex(), completed.finalWatermark()).consistent()).isTrue();
}

@Test
void validationFailureKeepsOldAliases() {
    PreparedRebuild prepared = prepareCorruptedTarget();
    AliasTargets before = indexManager.aliasTargets();
    assertThatThrownBy(() -> cutover.cutover(prepared)).isInstanceOf(RebuildValidationException.class);
    assertThat(indexManager.aliasTargets()).isEqualTo(before);
}
```

- [ ] **Step 2: 运行测试确认 cutover 未实现**

Run: `./mvnw.cmd -Dit.test=RebuildCutoverIT,RebuildRecoveryIT verify`

Expected: FAIL，提示 pause、排他锁、别名交换或恢复器缺失。

- [ ] **Step 3: 实现暂停和 drain**

短事务把 `dispatcher_paused=true`；后续 claim 在 coordination 共享锁内观察该值并返回空。cutover 最多等待 30 秒，直到没有未过期 `PROCESSING`；超时标记任务 FAILED、恢复 pause 并保留旧别名。

- [ ] **Step 4: 实现最终排他窗口**

通过 `TransactionTemplate` 设置 30 秒超时并对 coordination 执行 `SELECT ... FOR UPDATE`。锁内按固定顺序读取 rebuild job、阻止新商品共享锁、记录 final watermark、补放 `(prepared, final]` 事件、refresh，并用 `RebuildValidator` 比较截至 final watermark 的 ID/版本/状态，然后发送单个 `_aliases` 请求同时移除旧别名并添加新别名。

- [ ] **Step 5: 实现切换崩溃恢复**

别名交换前把 job phase 持久化为 `CUTOVER`。启动恢复器读取所有 `RUNNING/CUTOVER` 任务：两个别名都指向 target 时完成任务并解除 pause；仍指向旧索引时标记 FAILED 并解除 pause；两个别名分裂时保持 pause、记录 FAILED 并拒绝自动写入，防止静默损坏。

`SearchRebuildRunner.run(jobId)` 只负责编排 `prepare(jobId)` 与 `cutover(prepared)`；任一步异常都通过 token/owner 条件把任务标为 FAILED、清理 `active_rebuild_id` 并在别名未分裂时解除 pause，不复制快照或切换算法。

- [ ] **Step 6: 运行切换与恢复测试**

Run: `./mvnw.cmd -Dit.test=RebuildCutoverIT,RebuildRecoveryIT verify`

Expected: PASS；商品写入只在最终窗口短暂等待，失败不切别名，成功同时切读写别名，三个恢复分支均可复现，0 skipped。

- [ ] **Step 7: 提交原子切换**

```powershell
git add -- labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/SearchCoordinationRepository.java labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/SearchOutboxRepository.java labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/persistence labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/elasticsearch/ElasticsearchIndexManager.java labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/elasticsearch/ElasticsearchIndexScanner.java labs/05-elasticsearch-search/src/test/java/com/example/search/integration/RebuildCutoverIT.java labs/05-elasticsearch-search/src/test/java/com/example/search/integration/RebuildRecoveryIT.java
git commit -m "feat(search): fence online index cutover"
```

### Task 8: 实现商品 HTTP 与核心中文搜索主线

**Files:**
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/search/ProductSort.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/search/ProductSearchCriteria.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/search/SearchProductHit.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/search/CategoryBucket.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/search/ProductSearchResult.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/search/ProductSearchGateway.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/search/ProductSearchService.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/search/SearchUnavailableException.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/elasticsearch/ElasticsearchProductSearchGateway.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/api/CreateProductRequest.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/api/UpdateProductRequest.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/api/ProductResponse.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/api/ProductSearchResponse.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/api/ProductController.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/api/ProductSearchController.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/api/ApiError.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/api/ApiExceptionHandler.java`
- Test: `labs/05-elasticsearch-search/src/test/java/com/example/search/unit/ProductSearchCriteriaTest.java`
- Test: `labs/05-elasticsearch-search/src/test/java/com/example/search/integration/ProductSearchHttpIT.java`

**Interfaces:**
- Produces: `ProductSearchResult search(ProductSearchCriteria criteria)`。
- HTTP: `POST /api/products`、`PUT /api/products/{id}`、`DELETE /api/products/{id}?expectedVersion=`、`GET /api/products/search`。
- Error status: 非法请求 400、不存在 404、版本冲突 409、Elasticsearch 故障 503。

- [ ] **Step 1: 写条件边界和真实中文搜索失败测试**

```java
@Test
void rejectsOverflowingWindowAndInvertedPriceRange() {
    assertThatThrownBy(() -> criteria(200, 50)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> criteria(new BigDecimal("20"), new BigDecimal("10")))
        .isInstanceOf(IllegalArgumentException.class);
}

@Test
void ranksNameMatchAndReturnsHighlightAndCategoryBuckets() {
    seedAndSynchronize("Java 并发编程", "后端教材", "BOOK", "图书");
    seedAndSynchronize("闲置台灯", "适合阅读 Java 教材", "LIFE", "生活");
    ResponseEntity<String> response = rest.getForEntity("/api/products/search?q=Java&page=0&size=10", String.class);
    assertThat(response.getHeaders().getContentType().toString())
        .isEqualTo("application/json;charset=UTF-8");
    JsonNode body = objectMapper.readTree(response.getBody());
    assertThat(body.at("/items/0/name").asText()).isEqualTo("Java 并发编程");
    assertThat(response.getBody()).contains("<em>Java</em>", "categoryCode");
}
```

- [ ] **Step 2: 运行单元和 HTTP 测试确认失败**

Run: `./mvnw.cmd -Dtest=ProductSearchCriteriaTest test; ./mvnw.cmd -Dit.test=ProductSearchHttpIT verify`

Expected: FAIL，提示 criteria、gateway 或 controller 不存在。

- [ ] **Step 3: 实现严格查询条件**

`ProductSearchCriteria` 将空白 q 归一为空 Optional，限制最多 100 Unicode code point，验证 size 1–50、page 非负、窗口不超过 10,000、价格非负且 min<=max；排序枚举只接受 `relevance/priceAsc/priceDesc/newest`。

- [ ] **Step 4: 构建精确 Elasticsearch 查询**

```java
Query text = criteria.keyword().<Query>map(value -> MultiMatchQuery.of(m -> m
        .query(value).fields("name^4", "subtitle^2", "description"))._toQuery())
    .orElseGet(() -> MatchAllQuery.of(m -> m)._toQuery());

SearchRequest.Builder request = new SearchRequest.Builder()
    .index("products-read")
    .query(q -> q.bool(b -> b.must(text).filter(filters(criteria))))
    .from(criteria.page() * criteria.size())
    .size(criteria.size())
    .trackTotalHits(t -> t.enabled(true))
    .highlight(h -> h.fields("name", f -> f)
        .fields("subtitle", f -> f).fields("description", f -> f))
    .aggregations("categories", a -> a
        .terms(t -> t.field("categoryCode").size(100))
        .aggregations("name", sub -> sub.terms(t -> t.field("categoryName").size(1))));
```

始终加入 `status=ON_SALE` filter。排序固定为：relevance 使用 `_score DESC, updatedAt DESC, productId ASC`，无关键词 relevance 使用 `updatedAt DESC, productId ASC`，价格排序使用 `price ASC|DESC, productId ASC`，newest 使用 `updatedAt DESC, productId ASC`。映射 hits、每字段高亮和分类 bucket，不把 Elasticsearch 类型泄露到 API 层。

- [ ] **Step 5: 实现 UTF-8 HTTP 与快速失败**

控制器显式 `produces="application/json;charset=UTF-8"`。Jackson 配置 `FAIL_ON_UNKNOWN_PROPERTIES=true`；异常处理器返回 `ApiError(code, message, timestamp)`，不返回 SQL、索引地址或堆栈。

- [ ] **Step 6: 运行搜索测试并补齐所有核心组合**

```java
@ParameterizedTest
@CsvSource({"priceAsc,低价商品", "priceDesc,高价商品", "newest,最新商品"})
void appliesStableSortModes(String sort, String expectedFirstName) {
    JsonNode body = getSearch("?sort=" + sort + "&page=0&size=10");
    assertThat(body.at("/items/0/name").asText()).isEqualTo(expectedFirstName);
}

@Test
void filtersByCategoryPriceAndOnSaleStatus() {
    JsonNode body = getSearch("?categoryCode=BOOK&minPrice=10.00&maxPrice=50.00");
    for (JsonNode item : body.withArray("items")) {
        assertThat(item.get("categoryCode").asText()).isEqualTo("BOOK");
        assertThat(item.get("status").asText()).isEqualTo("ON_SALE");
    }
}
```

Run: `./mvnw.cmd -Dtest=ProductSearchCriteriaTest test; ./mvnw.cmd -Dit.test=ProductSearchHttpIT verify`

Expected: PASS；中文分词、名称权重、分类/价格/状态过滤、四种排序、稳定分页、高亮、分类聚合、400/404/409 和 UTF-8 全部通过，0 skipped；真实 Elasticsearch 断连时的 503 留给 Task 10 的 Toxiproxy 验收。

- [ ] **Step 7: 提交搜索主线**

```powershell
git add -- labs/05-elasticsearch-search/src/main/java/com/example/search/application/search labs/05-elasticsearch-search/src/main/java/com/example/search/infrastructure/elasticsearch/ElasticsearchProductSearchGateway.java labs/05-elasticsearch-search/src/main/java/com/example/search/api labs/05-elasticsearch-search/src/test/java/com/example/search/unit/ProductSearchCriteriaTest.java labs/05-elasticsearch-search/src/test/java/com/example/search/integration/ProductSearchHttpIT.java
git commit -m "feat(search): add SmartCN product search API"
```

### Task 9: 增加最小一致性检查、修复和运维接口

**Files:**
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/ConsistencyReport.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/SearchConsistencyService.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/SearchRebuildService.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/api/SearchMaintenanceController.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/config/SearchMaintenanceConfiguration.java`
- Test: `labs/05-elasticsearch-search/src/test/java/com/example/search/integration/SearchMaintenanceIT.java`

**Interfaces:**
- Produces: `ConsistencyReport check()`、`void repairProduct(long productId)`、`void retryFailed(UUID eventId)`。
- Produces: `List<IndexedProductVersion> scanAllVersions(String alias, int batchSize)`，内部固定 PIT + search_after、batch 500。
- Produces: `UUID startRebuild()`、`RebuildJob getRebuild(UUID jobId)`；后台 executor 调用 `SearchRebuildRunner.run(jobId)`，runner 依次调用 prepare 和 cutover。
- 运维 HTTP 只在配置开启时注册，路径与规格完全一致。

- [ ] **Step 1: 写缺失、落后、多余和禁用接口失败测试**

```java
@Test
void reportsThreeDifferenceKindsAndRepairsThroughOutbox() {
    corruptIndexWithMissingStaleAndOrphanDocuments();
    ConsistencyReport report = service.check();
    assertThat(report.missingCount()).isEqualTo(1);
    assertThat(report.staleCount()).isEqualTo(1);
    assertThat(report.orphanCount()).isEqualTo(1);
    service.repairProduct(missingProductId);
    dispatcher.dispatchOnce();
    assertThat(service.check().missingCount()).isZero();
}
```

- [ ] **Step 2: 运行测试确认扫描和修复缺失**

Run: `./mvnw.cmd -Dit.test=SearchMaintenanceIT verify`

Expected: FAIL，提示 consistency service、PIT scanner 或 maintenance controller 不存在。

- [ ] **Step 3: 实现批量扫描和差异合并**

Elasticsearch 打开 PIT，按 `productId ASC` 与 `_shard_doc` 排序，每批 500 并携带上一页 sort values；finally 必须关闭 PIT。MySQL 按 ID keyset 每批 500。服务比较 ID、`sourceVersion`、status，计数全部差异但每类只保留前 20 个 ID 样例。

- [ ] **Step 4: 实现可靠修复和失败重投**

商品修复在 coordination 共享锁事务中读取当前 Product，按 `(product_id, version, event_type)` 查找 Outbox：存在则重置 NEW，不存在则插入新 UUID 事件。FAILED 重投验证事件确实为 FAILED，再清空 owner/token/lease、attempt 归零、available_at 使用数据库当前时间，保留 last_error 到成功完成。

- [ ] **Step 5: 实现条件注册的最小运维控制器**

`@ConditionalOnProperty(prefix="search.maintenance", name="enabled", havingValue="true")` 注册：

- `POST /api/admin/search/rebuilds`
- `GET /api/admin/search/rebuilds/{jobId}`
- `POST /api/admin/search/consistency-checks`
- `POST /api/admin/search/products/{productId}/repair`
- `POST /api/admin/search/outbox/{eventId}/retry`

`SearchMaintenanceConfiguration` 同时提供名称固定的单线程 `searchRebuildExecutor`。关闭配置的上下文请求 `/api/admin/search/**` 必须为 404。请求不得接收物理索引名或原始 DSL。

`SearchRebuildService.startRebuild()` 先同步创建并领取任务，再把 `runner.run(jobId)` 提交给名称固定为 `searchRebuildExecutor` 的单线程 executor，立即返回 jobId；状态查询只读取 `search_rebuild_job`，不等待后台任务。

- [ ] **Step 6: 运行运维测试**

Run: `./mvnw.cmd -Dit.test=SearchMaintenanceIT verify`

Expected: PASS；三类差异、20 条样例上限、Outbox 修复、FAILED 重投、接口开关和非法 ID 均通过，0 skipped。

- [ ] **Step 7: 提交最小运维支撑**

```powershell
git add -- labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance labs/05-elasticsearch-search/src/main/java/com/example/search/api/SearchMaintenanceController.java labs/05-elasticsearch-search/src/main/java/com/example/search/config/SearchMaintenanceConfiguration.java labs/05-elasticsearch-search/src/test/java/com/example/search/integration/SearchMaintenanceIT.java
git commit -m "feat(search): add consistency checks and repair"
```

### Task 10: 接通调度、指标和重复故障演练

**Files:**
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/config/SearchProperties.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/config/SearchSchedulingConfiguration.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/observability/MicrometerSearchSyncMetrics.java`
- Create: `labs/05-elasticsearch-search/src/main/java/com/example/search/observability/OutboxScheduler.java`
- Modify: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/SearchOutboxRepository.java`
- Modify: `labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/RebuildJobRepository.java`
- Modify: `labs/05-elasticsearch-search/src/main/resources/application.yml`
- Modify: `labs/05-elasticsearch-search/src/test/java/com/example/search/integration/SharedSearchContainers.java`
- Test: `labs/05-elasticsearch-search/src/test/java/com/example/search/unit/SearchPropertiesTest.java`
- Test: `labs/05-elasticsearch-search/src/test/java/com/example/search/integration/ActuatorSearchMetricsIT.java`
- Test: `labs/05-elasticsearch-search/src/test/java/com/example/search/integration/RebuildAndRecoveryDrillIT.java`

**Interfaces:**
- Produces: 定时 `OutboxScheduler.dispatch()`、应用启动索引初始化与中断切换恢复。
- Metrics: Outbox 各状态数量、最老未完成延迟、bulk 结果/耗时、搜索错误/耗时、重建状态/耗时/差异。
- Test fault control: Toxiproxy 切断并恢复应用到真实 Elasticsearch 容器的 TCP 连接。

- [ ] **Step 1: 写配置校验、指标和连续演练失败测试**

```java
@Test
void rejectsLeaseShorterThanDispatchTimeout() {
    assertThatThrownBy(() -> new SearchProperties(50, Duration.ofSeconds(5),
            Duration.ofSeconds(10), Duration.ofSeconds(1),
            new SearchProperties.Maintenance(false)))
        .isInstanceOf(IllegalArgumentException.class);
}

@Test
void repeatsRebuildAndOutageRecoveryWithoutStateRegression() {
    repeat(3, this::mutateDuringRebuildAndAssertConsistent);
    repeat(2, this::cutElasticConnectionAccumulateRestoreAndAssertCaughtUp);
}
```

- [ ] **Step 2: 运行测试确认调度和指标未接线**

Run: `./mvnw.cmd -Dtest=SearchPropertiesTest test; ./mvnw.cmd -Dit.test=ActuatorSearchMetricsIT,RebuildAndRecoveryDrillIT verify`

Expected: FAIL，提示 properties、scheduler、metrics 或 Toxiproxy 路由缺失。

- [ ] **Step 3: 实现配置与安全调度**

`SearchProperties(int batchSize, Duration leaseDuration, Duration requestTimeout, Duration dispatchDelay, Maintenance maintenance)` 固定默认 batch 50、lease 30 秒、Elasticsearch timeout 10 秒、dispatcher 间隔 1 秒、maintenance false，并验证 batch 1–50、lease 大于 requestTimeout。scheduler 使用 `fixedDelayString`，每次只调用一次 `dispatchOnce`，不在调度线程中循环清空全部积压。

`SearchSchedulingConfiguration` 注册启动 runner，严格按 `recovery.recoverInterruptedCutover()` 后 `bootstrap.ensureInitialized()` 的顺序执行；分裂别名会让启动失败，不能被 bootstrap 自动覆盖。启动 runner 成功后才允许 scheduler 开始领取 Outbox。

- [ ] **Step 4: 实现 Micrometer 适配**

使用 `Counter`/`Timer` 记录 bulk、搜索和重建；Outbox 状态与最老延迟使用从 JDBC 查询的 gauge provider。指标固定为 `search.sync.events`（有限 outcome tag）、`search.sync.bulk.duration`、`search.sync.outbox`（四个 status tag）、`search.sync.oldest.age`、`search.query.duration`、`search.query.errors`、`search.rebuild.duration`、`search.rebuild.differences`；不把 eventId、productId 或异常消息作为 tag。

- [ ] **Step 5: 用 Toxiproxy 真实切断 Elasticsearch**

`SharedSearchContainers` 增加 `new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"))`，应用的 `spring.elasticsearch.uris` 指向 `getProxy(ELASTICSEARCH, 9200)`；测试准备和断言客户端可直接连 Elasticsearch。故障阶段调用 `proxy.setConnectionCut(true)`，恢复阶段在 finally 中调用 `false`，避免污染后续测试。

每次断连期间还要调用 `GET /api/products/search?q=故障演练` 并断言 503，再创建至少两个商品版本形成 Outbox 积压；恢复后用 Awaitility 等待所有事件 COMPLETED、索引版本追平且搜索重新返回 200。

- [ ] **Step 6: 运行连续三次重建和两次故障恢复**

Run: `./mvnw.cmd -Dtest=SearchPropertiesTest test; ./mvnw.cmd -Dit.test=ActuatorSearchMetricsIT,RebuildAndRecoveryDrillIT verify`

Expected: PASS；3 次并发重建每次全量一致，2 次网络中断均形成可见积压并自动追平，Actuator 暴露规定指标，0 failures/errors/skipped。

- [ ] **Step 7: 提交调度与演练**

```powershell
git add -- labs/05-elasticsearch-search/src/main/java/com/example/search/config labs/05-elasticsearch-search/src/main/java/com/example/search/observability labs/05-elasticsearch-search/src/main/java/com/example/search/application/sync/SearchOutboxRepository.java labs/05-elasticsearch-search/src/main/java/com/example/search/application/maintenance/RebuildJobRepository.java labs/05-elasticsearch-search/src/main/resources/application.yml labs/05-elasticsearch-search/src/test/java/com/example/search/unit/SearchPropertiesTest.java labs/05-elasticsearch-search/src/test/java/com/example/search/integration/SharedSearchContainers.java labs/05-elasticsearch-search/src/test/java/com/example/search/integration/ActuatorSearchMetricsIT.java labs/05-elasticsearch-search/src/test/java/com/example/search/integration/RebuildAndRecoveryDrillIT.java
git commit -m "feat(search): observe and rehearse search recovery"
```

### Task 11: 完成实验文档和分支级验收

**Files:**
- Create: `labs/05-elasticsearch-search/README.md`
- Create: `labs/05-elasticsearch-search/TROUBLESHOOTING.md`
- Modify: `labs/05-elasticsearch-search/.env.example`
- Test: entire `labs/05-elasticsearch-search/`

**Interfaces:**
- Consumes: Tasks 1–10 的可运行实验。
- Produces: 无秘密、可手工运行、可解释一致性边界并完整通过 verify 的独立实验分支。

- [ ] **Step 1: 写 README 与排障文档**

README 必须包含：四层结构、MySQL/Outbox/ES 数据流、至少一次边界、固定锁顺序、API 示例、SmartCN 查询、Compose 启动、重建/检查/修复、指标和验证命令；旧索引清理必须要求先读取两个别名目标，再手工删除一个明确物理索引名，禁止通配符删除。TROUBLESHOOTING 必须包含：插件版本必须与 ES 完全一致、Docker 内存、单节点 yellow/replica 处理、严格映射冲突、alias 分裂恢复、Toxiproxy 清理和异步客户端关闭。

- [ ] **Step 2: 执行快速单元测试**

Run: `./mvnw.cmd test`

Expected: BUILD SUCCESS；所有 Surefire 测试 0 failures、0 errors、0 skipped，不启动 Docker 容器。

- [ ] **Step 3: 执行完整真实环境验收**

Run: `./mvnw.cmd verify`

Expected: BUILD SUCCESS；MySQL 8.4 与带 SmartCN 的 Elasticsearch 8.18.8 真实启动，Failsafe 0 failures、0 errors、0 skipped；日志中没有容器停止后的异步重连或资源泄漏告警。

- [ ] **Step 4: 扫描 XML 报告和差异**

```powershell
Get-ChildItem -Path target/surefire-reports,target/failsafe-reports -Filter 'TEST-*.xml' |
  Select-String -Pattern 'failures="[1-9]|errors="[1-9]|skipped="[1-9]'
git diff --check
git status --short
```

Expected: XML 扫描无输出，`git diff --check` 无输出；status 只显示本任务三份文档修改。

- [ ] **Step 5: 验证独立分支允许列表**

```powershell
git ls-tree -r --name-only HEAD
git log --format='%H' -- AGENTS.md
```

Expected: 活动树只包含根 `.gitignore` 和 `labs/05-elasticsearch-search/**`；AGENTS 历史查询无输出。

- [ ] **Step 6: 提交实验文档**

```powershell
git add -- labs/05-elasticsearch-search/README.md labs/05-elasticsearch-search/TROUBLESHOOTING.md labs/05-elasticsearch-search/.env.example
git commit -m "docs(search): complete Elasticsearch search lab"
```

- [ ] **Step 7: 使用完成前验证流程复跑证据**

重新执行 `./mvnw.cmd test`、`./mvnw.cmd verify`、XML 扫描、`git diff --check`、`git status --short --branch`、`git ls-tree -r --name-only HEAD` 和 `git log --format='%H' -- AGENTS.md`。只有最新输出全部满足预期，才可称实验已验收。

### Task 12: 在 main 记录已验收结果

**Files:**
- Modify in main worktree: `README.md`
- Modify in main worktree: `notes/learning-log.md`
- Modify in main worktree: `interview/question-bank.md`

**Interfaces:**
- Consumes: Task 11 的最终提交 SHA、Surefire/Failsafe XML 实际统计和干净实验工作树。
- Produces: 文档中心中的阶段五“已验收”状态、可核对测试证据和面试追问；不把实验代码合并到 main。

- [ ] **Step 1: 从最终报告计算实际测试数字**

在实验目录读取所有 `TEST-*.xml`，分别累加 Surefire 与 Failsafe 的 tests、failures、errors、skipped，并记录实验分支 HEAD：

```powershell
git rev-parse HEAD
Get-ChildItem target/surefire-reports -Filter 'TEST-*.xml' | ForEach-Object { [xml](Get-Content $_.FullName) } | ForEach-Object { $_.testsuite } | Measure-Object tests,failures,errors,skipped -Sum
Get-ChildItem target/failsafe-reports -Filter 'TEST-*.xml' | ForEach-Object { [xml](Get-Content $_.FullName) } | ForEach-Object { $_.testsuite } | Measure-Object tests,failures,errors,skipped -Sum
```

Expected: 两组 failures/errors/skipped 合计均为 0；任何非零值阻止 main 更新。

- [ ] **Step 2: 更新路线和已验收实验说明**

在 main 的 `README.md` 把阶段五改为“已验收”，分支入口固定为 `learning/elasticsearch-search/labs/05-elasticsearch-search`；新增实验五段落，列出 SmartCN、核心搜索、事务 Outbox、外部版本、在线重建与实际测试数字。

- [ ] **Step 3: 写学习日志和十个可回到代码的面试题**

学习日志记录目标、完成内容、真实测试证据、并发竞态、至少一次取舍、重建/故障演练和下一步。题库新增 21–30：事实源与索引、事务 Outbox、租约、token fencing、external_gte、tombstone、SmartCN、相关性与 filter、别名切换、高水位与故障恢复；每题必须引用具体类、SQL 或测试名。

- [ ] **Step 4: 验证 main 仍是文档中心**

```powershell
git diff --check
git status --short
git ls-tree -r --name-only HEAD
```

Expected: 暂存前只修改 `README.md`、`notes/learning-log.md`、`interview/question-bank.md`；main 不跟踪 `labs/` 或 `AGENTS.md`。

- [ ] **Step 5: 提交验收记录**

```powershell
git add -- README.md notes/learning-log.md interview/question-bank.md
git commit -m "docs: record verified Elasticsearch search lab"
```

Expected: 实验分支保持独立、main 只新增文档提交；不执行 merge 或 push。
