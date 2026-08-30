# 校园二手交易平台 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 创建一个独立、可复跑的校园二手交易平台实验，完成校园邮箱身份、批量库存、一口价订单、模拟支付、当面履约、三天验收、七天试用、卖家延长质保、有限仲裁、部分退货退款和评价闭环，并为 CAS、真实支付及 Spring Cloud 拆分保留稳定端口。

**Architecture:** 使用模块化单体，各模块拥有自己的领域模型、应用接口和数据库表；MySQL 是交易与搜索事实源，Redis 只承担验证码、限流和缓存优化，RabbitMQ 通过事务 Outbox/Inbox 可靠传递事件，Elasticsearch 与 MinIO 均为可恢复外部适配器。所有资金使用整数分；截止时间与并发正确性依赖 MySQL 时间、条件更新、租约和 claim token，不依赖 Redis 分布式锁。

**Tech Stack:** JDK 17、Maven Wrapper 3.9.11、Spring Boot 3.5.16、Spring Security、Spring JDBC、Spring Data Redis、Spring AMQP、MySQL 8.4、Redis 7.4.2、RabbitMQ 3.13.7、Elasticsearch 8.18.8 + SmartCN、MinIO Server `RELEASE.2025-04-22T22-12-26Z`、MinIO Java SDK 8.6.0、Apache Tika 3.3.2、JJWT 0.12.6、Testcontainers 1.21.4、Toxiproxy 2.12.0、JUnit 5、AssertJ、Awaitility。

## Global Constraints

- 规格来源固定为 `docs/superpowers/specs/2026-08-30-campus-market-design.md`；规格与计划冲突时先停止实施并修正规格/计划，不在代码中自行改变业务规则。
- 实验分支固定为 `learning/campus-market`，目录固定为 `labs/07-campus-market/`；活动树只允许根 `.gitignore` 与本实验目录，不提交 `AGENTS.md`、其他实验或 `main` 文档。
- 执行前必须使用 `using-git-worktrees` 创建隔离工作树；新实验使用无父提交的独立历史，不从 `main` 带入文档中心。
- JDK 固定为 17；Maven 固定为 3.9.11、wrapper 脚本固定为 3.3.4；Spring Boot 固定为 3.5.16。
- 金额固定为人民币整数分与数据库 `BIGINT`；禁止 `double`、`float` 和未定义舍入。退款成功额、预占额和本次请求额之和不得超过实付金额。
- 一个订单只包含一个发布项但数量可大于 1；卖家不能购买自己的商品；库存扣减使用带数量条件的 SQL，禁止先查后写。
- MySQL 是状态、截止时间和搜索事实源；Redis 锁不参与正确性证明。所有截止时间、领取和租约使用 MySQL `TIMESTAMP(6)` 与数据库时间。
- 待支付 15 分钟、卖家交付 72 小时、买家确认 48 小时；从买家确认或系统自动确认的数据库时间 `T0` 起，验收期 72 小时、试用期 7 天且包含验收期；卖家延长质保只能为 30/90/180/365 天且包含平台试用期；卖家确认退回 72 小时、质保裁定后筹资 72 小时、管理员 SLA 7 天、管理员硬期限 14 天。
- 所有业务窗口左闭右开：`databaseNow < deadline` 时允许命令，到达截止时刻即过期。七天后普通订单正常结算；延长质保案件独立存在，不回退 `SETTLED`，平台不垫付卖家义务。
- 管理员硬期限到达后只有可信退回证明可自动退款；缺少可信证明或证据冲突进入 `ESCALATED` 并冻结资金，不能默认判任一方胜诉。
- 商品图片只允许 JPEG/PNG/WebP，单文件 10 MiB、每商品最多 9 张；证据额外允许 PDF 20 MiB 和 MP4 100 MiB。上限均按实际读取字节执行。
- 所有 JSON 显式返回 `application/json; charset=UTF-8`；未知字段、非法枚举、空值、非正数、金额溢出和不支持版本快速失败。
- 私有订单、争议和证据对非参与者统一返回 404；管理员不自动绕过证据 ACL。
- 真实 CAS、学校域名、Ticket、Cookie、支付商户号、证书、密钥、验证码和 JWT 不得提交或写入日志。
- 每个行为严格执行红—绿—重构：先写失败测试并观察预期失败，再写最小实现，再运行同一测试通过，最后运行本任务回归测试。
- 单元测试不启动 Spring；MySQL、Redis、RabbitMQ、Elasticsearch、MinIO、Flyway、真实 HTTP 和故障恢复使用 Testcontainers。
- Surefire 排除 `*IT`；Failsafe 在 `integration-test`/`verify` 运行全部 `*IT`。任何外部测试 skipped 都不算完整验收。

---

## 文件结构

| 路径 | 职责 |
| --- | --- |
| `labs/07-campus-market/pom.xml` | 固定依赖、Surefire/Failsafe 和构建版本。 |
| `labs/07-campus-market/compose.yaml`、`docker/elasticsearch/Dockerfile` | MySQL、Redis、RabbitMQ、SmartCN Elasticsearch、MinIO 和 Toxiproxy。 |
| `labs/07-campus-market/src/main/java/com/example/campusmarket/identity/*` | 校园邮箱、用户、JWT、CAS 端口和身份 HTTP。 |
| `labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/*` | 商品、媒体、库存流水、搜索端口和索引同步。 |
| `labs/07-campus-market/src/main/java/com/example/campusmarket/order/*` | 订单、命令幂等、状态迁移和截止时间。 |
| `labs/07-campus-market/src/main/java/com/example/campusmarket/payment/*` | `Money`、支付、退款、结算、模拟网关和对账。 |
| `labs/07-campus-market/src/main/java/com/example/campusmarket/dispute/*` | 交付、争议、证据、退回和管理员裁决。 |
| `labs/07-campus-market/src/main/java/com/example/campusmarket/messaging/*` | 事务 Outbox、Rabbit publisher confirm、Inbox 和租约。 |
| `labs/07-campus-market/src/main/java/com/example/campusmarket/api/*` | UTF-8 错误、幂等请求摘要和跨模块 HTTP 装配。 |
| `labs/07-campus-market/src/main/java/com/example/campusmarket/storage/*` | 通用私有对象上传会话、MinIO 适配器和持久清理任务。 |
| `labs/07-campus-market/src/main/java/com/example/campusmarket/observability/*` | 调度、指标、审计过滤和故障分类。 |
| `labs/07-campus-market/src/main/resources/db/migration/*` | 按模块拆分的 Flyway 表、约束和索引。 |
| `labs/07-campus-market/src/test/java/com/example/campusmarket/unit/*` | 纯 Java 领域与协议单元测试。 |
| `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/*` | 共享 Testcontainers、真实 HTTP、并发和故障演练。 |
| `labs/07-campus-market/README.md`、`TROUBLESHOOTING.md` | 架构、运行、验证、边界和排障。 |

### Task 1: 创建隔离实验、构建基线与基础架构

**Files:**
- Create: `labs/07-campus-market/pom.xml`
- Create: `labs/07-campus-market/mvnw.cmd`
- Create: `labs/07-campus-market/.mvn/wrapper/maven-wrapper.properties`
- Create: `labs/07-campus-market/compose.yaml`
- Create: `labs/07-campus-market/docker/elasticsearch/Dockerfile`
- Create: `labs/07-campus-market/.env.example`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/CampusMarketApplication.java`
- Create: `labs/07-campus-market/src/main/resources/application.yml`
- Create: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/SharedContainers.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/ApplicationBaselineIT.java`

**Interfaces:**
- Consumes: 仓库根 `.gitignore`、JDK 17、Docker Engine。
- Produces: 无父提交实验分支、固定构建、六个外部依赖容器和可启动空应用。

- [ ] **Step 1: 创建无父历史隔离工作树**

先按 `using-git-worktrees` 检查当前环境，再执行：

```powershell
git worktree add --detach .worktrees/campus-market main
git -C .worktrees/campus-market switch --orphan learning/campus-market
git -C .worktrees/campus-market restore --source=learning/secure-file-service --staged --worktree -- .gitignore
```

Expected: `git -C .worktrees/campus-market rev-parse --verify HEAD` 对 unborn branch 失败；活动树没有 `README.md`、`docs/`、`notes/`、`interview/` 或其他实验目录。

- [ ] **Step 2: 写启动失败测试**

```java
@Test
void startsWithTestProfileAndAllExternalAdaptersDisabled() {
    new ApplicationContextRunner()
        .withUserConfiguration(CampusMarketApplication.class)
        .withPropertyValues("spring.profiles.active=test")
        .run(context -> assertThat(context).hasNotFailed());
}
```

- [ ] **Step 3: 创建完整 POM、Wrapper 和测试骨架并确认 RED**

`pom.xml` 一次加入 Web、Security、JDBC、Validation、Redis、AMQP、Actuator、Flyway MySQL、Elasticsearch Java/REST、MinIO、OkHttp JVM、Tika Core、JJWT API/impl/Jackson、Boot Test、Security Test、Testcontainers MySQL/Redis-compatible GenericContainer/RabbitMQ/Elasticsearch/MinIO/Toxiproxy、Awaitility。固定属性：

```xml
<java.version>17</java.version>
<maven.compiler.release>17</maven.compiler.release>
<elasticsearch.version>8.18.8</elasticsearch.version>
<minio.version>8.6.0</minio.version>
<okhttp.version>5.1.0</okhttp.version>
<tika.version>3.3.2</tika.version>
<jjwt.version>0.12.6</jjwt.version>
<testcontainers.version>1.21.4</testcontainers.version>
```

复制已验收实验的 `mvnw.cmd` 并校验哈希相同。Wrapper 属性固定：

```properties
wrapperVersion=3.3.4
distributionType=only-script
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip
```

Run: `.\mvnw.cmd -Dit.test=ApplicationBaselineIT verify`

Expected: FAIL，因为 `CampusMarketApplication` 尚不存在；不能以依赖下载、Docker 不可用或测试 skipped 作为 RED。

- [ ] **Step 4: 写最小启动类与安全默认配置**

```java
@SpringBootApplication
@ConfigurationPropertiesScan
public class CampusMarketApplication {
    public static void main(String[] args) {
        SpringApplication.run(CampusMarketApplication.class, args);
    }
}
```

`application.yml` 固定未知字段快速失败、Servlet UTF-8、multipart 请求上限 101 MiB、所有外部调度默认关闭；业务流读取器仍按各文件类型精确限制。

- [ ] **Step 5: 固定 Compose 与健康检查**

使用 `mysql:8.4`、`redis:7.4.2-alpine`、`rabbitmq:3.13.7-management`、`docker.elastic.co/elasticsearch/elasticsearch:8.18.8`、`quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z`、`ghcr.io/shopify/toxiproxy:2.12.0`。Elasticsearch Dockerfile 内容：

```dockerfile
FROM docker.elastic.co/elasticsearch/elasticsearch:8.18.8
RUN bin/elasticsearch-plugin install --batch analysis-smartcn
```

Compose 端口固定为 MySQL 3313、Redis 6383、RabbitMQ 5673/15673、Elasticsearch 9203、MinIO 9010/9011、Toxiproxy 控制面 8475；密码只从 `.env` 读取，`.env.example` 只写占位符。

- [ ] **Step 6: 验证 GREEN 并提交**

Run: `.\mvnw.cmd -Dit.test=ApplicationBaselineIT verify`

Expected: PASS，真实启动最小 Spring Context，0 skipped。

```powershell
git add .gitignore labs/07-campus-market
git commit -m "build(campus): initialize campus market lab"
```

### Task 2: 建立数据库所有权、公共值对象和事件契约

**Files:**
- Create: `labs/07-campus-market/src/main/resources/db/migration/V1__identity_catalog.sql`
- Create: `labs/07-campus-market/src/main/resources/db/migration/V2__order_payment.sql`
- Create: `labs/07-campus-market/src/main/resources/db/migration/V3__dispute_review.sql`
- Create: `labs/07-campus-market/src/main/resources/db/migration/V4__messaging_audit.sql`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/shared/Money.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/shared/DomainEvent.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/unit/shared/MoneyTest.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/unit/shared/DomainEventTest.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/SchemaIT.java`

**Interfaces:**
- Consumes: Task 1 的 MySQL/Flyway 基线。
- Produces: `Money.ofFen(long)`、`Money.multiply(int)`、稳定七字段 `DomainEvent` 和规格第 9 节全部 27 张表约束。

- [ ] **Step 1: 写 Money 与事件 RED 测试**

```java
@Test void rejectsNegativeAndOverflow() {
    assertThatThrownBy(() -> Money.ofFen(-1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Money.ofFen(Long.MAX_VALUE).multiply(2))
        .isInstanceOf(ArithmeticException.class);
}

@Test void rejectsUnsupportedSchemaVersion() {
    assertThatThrownBy(() -> new DomainEvent(UUID.randomUUID(), "ORDER_CREATED", "o1", 1,
        Instant.now(), 2, Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
}
```

Run: `.\mvnw.cmd -Dtest=MoneyTest,DomainEventTest test`

Expected: FAIL，因为类型不存在。

- [ ] **Step 2: 写最小值对象**

```java
public record Money(long fen) {
    public Money {
        if (fen < 0) throw new IllegalArgumentException("金额不能为负数");
    }
    public static Money ofFen(long fen) { return new Money(fen); }
    public Money multiply(int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("数量必须为正数");
        return new Money(Math.multiplyExact(fen, quantity));
    }
}
```

`DomainEvent` 固定 `eventId/eventType/aggregateId/aggregateVersion/occurredAt/schemaVersion/payload`，只接受 `schemaVersion=1`、正数版本和非空值。

- [ ] **Step 3: 写 Schema RED 测试**

断言 27 张核心表、金额 `BIGINT`、数量 CHECK、幂等唯一键和领取索引存在：

```java
assertThat(tableNames()).contains("campus_user", "listing", "trade_order", "payment_order",
    "refund_order", "dispute_case", "integration_outbox", "consumed_event",
    "object_upload_session", "storage_cleanup_task", "warranty_case", "seller_obligation");
assertThat(columnType("trade_order", "total_amount_fen")).isEqualTo("bigint");
assertThat(indexNames("refund_order")).contains("uk_refund_idempotency", "idx_refund_reconcile");
```

Run: `.\mvnw.cmd -Dit.test=SchemaIT verify`

Expected: FAIL，Flyway 尚无迁移。

- [ ] **Step 4: 写四个迁移**

迁移必须精确创建规格第 9 节所有表。核心约束至少包括：

```sql
CHECK (unit_price_fen >= 0),
CHECK (available_quantity >= 0),
CHECK (quarantined_quantity >= 0),
CHECK (quantity > 0),
CHECK (paid_amount_fen >= 0),
CHECK (successful_refund_fen >= 0),
CHECK (reserved_refund_fen >= 0),
UNIQUE (provider, provider_event_id),
UNIQUE (consumer_name, event_id),
UNIQUE (order_id, reviewer_id),
UNIQUE (object_key),
UNIQUE (cleanup_business_key),
UNIQUE (warranty_case_id),
CHECK (warranty_days IN (30, 90, 180, 365) OR warranty_days IS NULL),
CHECK (funded_amount_fen >= 0 AND funded_amount_fen <= obligation_amount_fen)
```

所有业务时间使用 `TIMESTAMP(6)`；状态字段使用有限 `VARCHAR` 加 CHECK；每张租约表包含 `owner_id`、`claim_token`、`lease_until`、`attempt_count` 和适用索引。

- [ ] **Step 5: 验证、检查模块表归属并提交**

Run: `.\mvnw.cmd test`

Run: `.\mvnw.cmd -Dit.test=SchemaIT verify`

Expected: 单元与 SchemaIT 全部 PASS，0 skipped。

```powershell
git add labs/07-campus-market/src
git commit -m "feat(campus): define schema and shared contracts"
```

### Task 3: 实现校园邮箱、注册登录、JWT 与 CAS 端口

**Files:**
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/identity/domain/CampusEmail.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/identity/application/EmailVerificationService.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/identity/application/AuthService.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/identity/application/ExternalIdentityProvider.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/identity/infrastructure/JwtService.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/identity/infrastructure/RedisVerificationCodeStore.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/identity/api/AuthController.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/identity/api/JwtAuthenticationFilter.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/unit/identity/CampusEmailTest.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/AuthFlowIT.java`

**Interfaces:**
- Consumes: `campus_user`、`email_verification`、Redis、BCrypt、JJWT。
- Produces: `AuthenticatedUser(userId, roles)`、15 分钟 JWT、未实现真实外部登录的 `ExternalIdentityProvider`。

- [ ] **Step 1: 写邮箱与验证码 RED 测试**

```java
@ParameterizedTest
@ValueSource(strings = {"a@stu.example.edu.cn.evil.com", "a@fake-stu.example.edu.cn", "a@"})
void rejectsSuffixTricks(String email) {
    assertThatThrownBy(() -> CampusEmail.parse(email, Set.of("stu.example.edu.cn")))
        .isInstanceOf(IllegalArgumentException.class);
}

@Test void consumesOneCodeOnlyOnce() {
    String code = verificationService.issue("a@stu.example.edu.cn", client);
    assertThat(verificationService.verify(email, code)).isTrue();
    assertThat(verificationService.verify(email, code)).isFalse();
}
```

Run: `.\mvnw.cmd -Dtest=CampusEmailTest test`

Expected: FAIL，因为邮箱类型不存在。

- [ ] **Step 2: 实现精确域名与验证码端口**

`CampusEmail` 用 `InternetDomainName` 之外的标准 Java IDN/ASCII 规范化，拆分最后一个 `@` 后进行完整域名集合匹配。验证码用 `SecureRandom`，Redis Lua 原子消费；MySQL 记录发送/验证审计，不保存明文。Redis 不可用返回 503，不回退为绕过验证。

- [ ] **Step 3: 写真实 HTTP RED 测试**

覆盖发送验证码、注册、BCrypt、登录、中文 UTF-8、过期 JWT、非法签名、重复验证码和限流：

```java
assertThat(loginResponse.headers().firstValue("Content-Type").orElseThrow())
    .isEqualTo("application/json; charset=UTF-8");
assertThat(accessTokenClaims.getExpiration().toInstant())
    .isEqualTo(accessTokenClaims.getIssuedAt().toInstant().plus(Duration.ofMinutes(15)));
```

Run: `.\mvnw.cmd -Dit.test=AuthFlowIT verify`

Expected: FAIL，接口返回 404。

- [ ] **Step 4: 实现 JWT 安全链和 CAS 占位端口**

`ExternalIdentityProvider` 只定义：

```java
public interface ExternalIdentityProvider {
    URI authorizationUri(URI callback, String state);
    ExternalIdentity verifyCallback(Map<String, String> parameters);
}
```

不得创建假 CAS 实现或真实学校配置。JWT 密钥少于 256 位、生产邮件发送器缺失或模拟发送器在非 local/test Profile 启用时启动失败。

- [ ] **Step 5: 验证并提交**

Run: `.\mvnw.cmd -Dtest=CampusEmailTest test`

Run: `.\mvnw.cmd -Dit.test=AuthFlowIT verify`

Expected: 全部 PASS，Redis 容器真实参与，0 skipped。

```powershell
git add labs/07-campus-market/src
git commit -m "feat(campus): add campus identity and JWT"
```

### Task 4: 实现商品、私有媒体和库存流水

**Files:**
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/domain/Listing.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/domain/ListingStatus.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/domain/WarrantyTerm.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/application/ListingService.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/application/MediaStorage.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/storage/PrivateObjectStorage.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/storage/MinioPrivateObjectStorage.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/storage/ObjectUploadCoordinator.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/storage/StorageCleanupScheduler.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/infrastructure/JdbcListingRepository.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/api/ListingController.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/unit/catalog/ListingTest.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/ListingMediaIT.java`

**Interfaces:**
- Consumes: `Money`、当前用户、`listing/listing_media/inventory_movement`、MinIO。
- Produces: 商品草稿/发布/下架用例、标准卖家/厂家质保声明、`MediaStorage.put/open/delete`、库存条件更新端口。

- [ ] **Step 1: 写商品状态 RED 测试**

```java
@Test void publishesOnlyCompleteListing() {
    Listing draft = Listing.draft(sellerId, "二手机械键盘", Money.ofFen(3500), 6,
        WarrantyTerm.sellerWarrantyDays(90));
    assertThatThrownBy(draft::publish).isInstanceOf(IllegalStateException.class);
    draft.addMedia(mediaId);
    assertThat(draft.publish().status()).isEqualTo(ListingStatus.ON_SALE);
}

@ParameterizedTest
@ValueSource(ints = {8, 29, 31, 366})
void rejectsNonStandardSellerWarrantyDays(int days) {
    assertThatThrownBy(() -> WarrantyTerm.sellerWarrantyDays(days))
        .isInstanceOf(IllegalArgumentException.class);
}
```

Run: `.\mvnw.cmd -Dtest=ListingTest test`

Expected: FAIL，因为 Listing 不存在。

- [ ] **Step 2: 实现最小领域与 JDBC**

商品构造器拒绝空标题、负金额、非正库存和超过 9 个媒体。卖家延长质保只接受无额外质保或 30/90/180/365 天；厂家质保独立保存凭证摘要与到期日，不能映射为卖家质保。库存更新只暴露：

```java
boolean deduct(long listingId, int quantity, String businessKey);
boolean restore(long listingId, int quantity, String businessKey);
boolean quarantine(long listingId, int quantity, String businessKey);
```

每个实现同时写唯一 `inventory_movement.business_key`。

- [ ] **Step 3: 写媒体 HTTP RED 测试**

覆盖 JPEG/PNG/WebP、伪造类型、10 MiB 边界、10 MiB+1、随机 Object Key、最多 9 张、非 owner 404 和 MinIO 断连 503。

Run: `.\mvnw.cmd -Dit.test=ListingMediaIT verify`

Expected: FAIL，接口未实现。

- [ ] **Step 4: 实现流式检测与 MinIO 私有媒体**

业务读取器以 `BoundedInputStream` 风格在第 `limit+1` 字节失败；Tika 只接受固定 MIME。Object Key 使用 `SecureRandom` 生成 128 位以上随机值。`ObjectUploadCoordinator` 先以短事务创建 `object_upload_session`，事务外写 MinIO，再以短事务绑定媒体；失败时创建唯一 `storage_cleanup_task`。`StorageCleanupScheduler` 在事务外删除对象，并以 claim token 完成任务；对象不存在视为成功。

- [ ] **Step 5: 验证并提交**

Run: `.\mvnw.cmd -Dtest=ListingTest test`

Run: `.\mvnw.cmd -Dit.test=ListingMediaIT verify`

```powershell
git add labs/07-campus-market/src
git commit -m "feat(campus): add listings media and inventory"
```

### Task 5: 实现幂等下单、条件扣库存和订单快照

**Files:**
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/order/domain/TradeOrder.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/order/domain/OrderStatus.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/order/application/CreateOrderService.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/order/application/IdempotentCommandService.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/order/infrastructure/JdbcOrderRepository.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/order/api/OrderController.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/unit/order/TradeOrderTest.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/ConcurrentOrderIT.java`

**Interfaces:**
- Consumes: 包含卖家/厂家质保声明的商品快照、库存 `deduct`、当前买家、`Idempotency-Key`。
- Produces: `CreateOrderCommand(listingId, quantity)`、原始 UTF-8 终态重放、`ORDER_CREATED` Outbox。

- [ ] **Step 1: 写订单状态与自购 RED 测试**

```java
@Test void rejectsSellerBuyingOwnListing() {
    assertThatThrownBy(() -> TradeOrder.create(id, sellerId, sellerId, snapshot, 2, now))
        .isInstanceOf(IllegalArgumentException.class);
}
```

Run: `.\mvnw.cmd -Dtest=TradeOrderTest test`

Expected: FAIL，因为订单类型不存在。

- [ ] **Step 2: 实现最小订单领域**

订单创建计算 `unitPrice.multiply(quantity)`，状态为 `PENDING_PAYMENT`，支付截止为数据库时间加 15 分钟；构造器拒绝非正数量、同一买卖方和快照缺失。订单不可变快照必须包含卖家质保标准天数、固定保障范围、厂家质保凭证摘要与到期日，后续商品修改不得影响既有订单。

- [ ] **Step 3: 写并发与幂等 RED 测试**

以库存 6 并发发起 20 个数量 1 的真实 HTTP 请求，断言恰好 6 个成功、可售库存为 0、6 个订单、6 条扣减流水。同一键同请求重放原始响应；同一键不同数量返回 409；注入事务回滚后同一键可重试。另建 90 天质保订单后把商品改为无质保，断言订单快照仍为 90 天。

Run: `.\mvnw.cmd -Dit.test=ConcurrentOrderIT verify`

Expected: FAIL，没有下单接口。

- [ ] **Step 4: 实现单事务下单**

锁顺序固定为 `order_command → listing → trade_order → integration_outbox`。库存 SQL 使用规格中的 `available_quantity >= :quantity` 条件。幂等请求摘要采用稳定规范 JSON 的 SHA-256；仅保存摘要，不保存 Token。

- [ ] **Step 5: 验证并提交**

Run: `.\mvnw.cmd -Dtest=TradeOrderTest test`

Run: `.\mvnw.cmd -Dit.test=ConcurrentOrderIT verify`

```powershell
git add labs/07-campus-market/src
git commit -m "feat(campus): add idempotent inventory orders"
```

### Task 6: 实现可靠 Outbox/Inbox 与 RabbitMQ 协作

**Files:**
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/messaging/OutboxRepository.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/messaging/OutboxDispatcher.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/messaging/InboxRepository.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/messaging/RabbitTopology.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/messaging/EventEnvelopeCodec.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/unit/messaging/EventEnvelopeCodecTest.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/ReliableMessagingIT.java`

**Interfaces:**
- Consumes: `DomainEvent`、RabbitTemplate publisher confirm、MySQL 租约。
- Produces: `claimBatch(owner, limit, lease)`、`complete(eventId, token)`、消费者 `claim/complete`。

- [ ] **Step 1: 写协议 RED 测试**

未知字段、缺字段、schemaVersion 非 1、非法 UUID 和非正 aggregateVersion 必须反序列化失败。

Run: `.\mvnw.cmd -Dtest=EventEnvelopeCodecTest test`

Expected: FAIL，Codec 不存在。

- [ ] **Step 2: 实现严格 Codec**

使用专用 ObjectMapper，注册 JavaTimeModule 并启用未知字段失败；业务服务不清洗事件输入。

- [ ] **Step 3: 写可靠发布 RED 测试**

覆盖并发 dispatcher 只领取一次、publisher confirm 后完成、NACK 保持可重试、租约过期接管、旧 token 迟到 confirm 影响 0 行、消费者重复事件直接 ACK、事务回滚前不 ACK。

Run: `.\mvnw.cmd -Dit.test=ReliableMessagingIT verify`

Expected: FAIL，dispatcher 不存在。

- [ ] **Step 4: 实现租约与 fencing**

领取使用 `NEW → PUBLISHING` 条件更新、MySQL 时间和随机 claim token；完成 SQL 必须匹配 event ID、状态、owner 和 token。Inbox 的业务事务与 `COMPLETED` 同事务提交。

- [ ] **Step 5: 验证并提交**

Run: `.\mvnw.cmd -Dtest=EventEnvelopeCodecTest test`

Run: `.\mvnw.cmd -Dit.test=ReliableMessagingIT verify`

```powershell
git add labs/07-campus-market/src
git commit -m "feat(campus): add reliable integration events"
```

### Task 7: 实现 Elasticsearch 商品同步与重建

**Files:**
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/search/ProductSearchPort.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/search/ElasticsearchProductSearch.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/search/SearchProjector.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/search/SearchRebuildService.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/ProductSearchIT.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/SearchRebuildIT.java`

**Interfaces:**
- Consumes: 商品事件、MySQL 商品快照、Elasticsearch 8.18.8。
- Produces: 中文搜索、分类/价格过滤、稳定分页、别名 `campus-listing-read/write`。

- [ ] **Step 1: 写中文搜索 RED 测试**

创建“Java 并发编程实战”与下架/售罄商品，搜索“并发编程”只返回在售且库存大于零结果，并验证 SmartCN analyzer 实际分词。

Run: `.\mvnw.cmd -Dit.test=ProductSearchIT verify`

Expected: FAIL，索引和搜索端口不存在。

- [ ] **Step 2: 实现外部版本投影**

索引文档保存 listing ID、标题、分类、单价分、可售状态和 aggregateVersion。写入使用 `external_gte`；下架/售罄写 tombstone 或删除事件，搜索端仍强制状态过滤。

- [ ] **Step 3: 写三轮重建 RED 测试**

测试一致性快照、高水位补放、门禁、别名原子切换、连接中断后恢复；连续运行 3 次重建，结果集合和版本不回退。

Run: `.\mvnw.cmd -Dit.test=SearchRebuildIT verify`

Expected: FAIL，重建服务不存在。

- [ ] **Step 4: 实现重建并验证**

Run: `.\mvnw.cmd -Dit.test=ProductSearchIT,SearchRebuildIT verify`

```powershell
git add labs/07-campus-market/src labs/07-campus-market/docker
git commit -m "feat(campus): add resilient product search"
```

### Task 8: 实现模拟支付、验签回调与退款额度预占

**Files:**
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/payment/application/PaymentGateway.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/payment/application/PaymentService.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/payment/application/RefundService.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/payment/infrastructure/SimulatedPaymentGateway.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/payment/infrastructure/SimulatedPaymentProviderController.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/payment/infrastructure/JdbcPaymentRepository.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/payment/api/PaymentWebhookController.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/unit/payment/RefundLimitTest.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/PaymentGatewayContractIT.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/PaymentFlowIT.java`

**Interfaces:**
- Consumes: `PaymentGateway` 规格、订单金额、Outbox、原始回调体。
- Produces: `create/queryPayment`、`request/queryRefund`、`verifyCallback`、支付/退款事件。

- [ ] **Step 1: 写退款上限 RED 测试**

```java
@Test void reservesConcurrentPartialRefundsWithoutExceedingPaidAmount() {
    PaymentAggregate payment = paid(Money.ofFen(10_000));
    assertThat(payment.reserveRefund("r1", Money.ofFen(6_000))).isTrue();
    assertThat(payment.reserveRefund("r2", Money.ofFen(5_000))).isFalse();
}
```

Run: `.\mvnw.cmd -Dtest=RefundLimitTest test`

Expected: FAIL，支付聚合不存在。

- [ ] **Step 2: 实现金额占额与网关端口**

`PaymentGateway` 精确签名：

```java
PaymentCreated createPayment(CreatePaymentRequest request);
PaymentStatus queryPayment(String providerReference);
RefundCreated requestRefund(CreateRefundRequest request);
RefundStatus queryRefund(String providerReference);
VerifiedCallback verifyAndParse(byte[] rawBody, HttpHeaders headers);
```

JDBC 预占使用单条条件 SQL，唯一幂等键和 provider reference。

- [ ] **Step 3: 写网关契约与 HTTP RED 测试**

覆盖创建/查询、HMAC 签名、5 分钟时间窗、nonce 重放、重复 provider event、未知结果、并发部分退款和 UTF-8 回调错误。`SimulatedPaymentProviderController` 只在 local/test 与显式开关同时满足时提供独立 HTTP provider 端点；`SimulatedPaymentGateway` 必须通过 HTTP 调用该端点，使 Toxiproxy 能注入超时和断连。模拟控制端在非 local/test Profile 启用必须启动失败。

Run: `.\mvnw.cmd -Dit.test=PaymentGatewayContractIT,PaymentFlowIT verify`

Expected: FAIL，网关不存在。

- [ ] **Step 4: 实现模拟网关与主动对账**

未知结果不盲目创建第二支付/退款；对账调度调用 query 接口并以 provider reference 条件完成。支付成功将订单从 `PENDING_PAYMENT` 推进到 `AWAITING_HANDOFF`；重复/乱序回调影响 0 行但返回幂等成功。

- [ ] **Step 5: 验证并提交**

Run: `.\mvnw.cmd -Dtest=RefundLimitTest test`

Run: `.\mvnw.cmd -Dit.test=PaymentGatewayContractIT,PaymentFlowIT verify`

```powershell
git add labs/07-campus-market/src
git commit -m "feat(campus): add simulated payments and refunds"
```

### Task 9: 实现交付、收货、三天验收、七天试用与截止时间竞态

**Files:**
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/order/application/OrderLifecycleService.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/order/application/DeadlineScheduler.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/dispute/application/HandoffService.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/dispute/api/HandoffController.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/unit/order/OrderLifecycleTest.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/DeadlineRaceIT.java`

**Interfaces:**
- Consumes: 订单状态、数据库时间、库存 restore、退款 Outbox。
- Produces: handoff、receipt、`T0`、payment/handoff/receipt/acceptance/trial deadlines。

- [ ] **Step 1: 写完整状态机 RED 测试**

枚举测试规格第 6.1 节所有允许迁移，并断言 `SETTLED/CANCELLED/REFUNDED` 不可逆；`AWAITING_HANDOFF` 下买家确认收货必须失败。

Run: `.\mvnw.cmd -Dtest=OrderLifecycleTest test`

Expected: FAIL，生命周期服务不存在。

- [ ] **Step 2: 实现最小状态命令**

每个更新匹配 `order_id/status/version`，写 `order_transition` 和 Outbox。卖家交付要求 `databaseNow < handoffDeadline`；超时要求 `handoffDeadline <= databaseNow`。

- [ ] **Step 3: 写边界并发 RED 测试**

使用 MySQL 时间夹具并发执行卖家交付与交付超时，断言只出现：

```text
AWAITING_RECEIPT + 库存不返还
或
REFUNDING_CANCEL + 库存恰好返还一次 + 一条退款 Outbox
```

同样覆盖支付超时、收货超时、三天理由切换、七天试用关闭与用户命令竞态。买家确认或系统自动确认必须只写一次 `T0`；`databaseNow < T0 + 72h` 允许数量/型号/外观/缺件/描述/功能理由，到达 `T0 + 72h` 后只允许非人为功能故障；`databaseNow < T0 + 7d` 允许普通试用争议，到达七天截止时才允许结算。

Run: `.\mvnw.cmd -Dit.test=DeadlineRaceIT verify`

Expected: FAIL，调度器不存在。

- [ ] **Step 4: 实现租约调度并验证**

领取使用有界批量、owner、claim token 和数据库时间；Redis 完全断开时竞态测试仍必须正确。窗口判断使用同一条数据库时间，禁止应用服务器时钟分别计算三天和七天边界。

Run: `.\mvnw.cmd -Dtest=OrderLifecycleTest test`

Run: `.\mvnw.cmd -Dit.test=DeadlineRaceIT verify`

```powershell
git add labs/07-campus-market/src
git commit -m "feat(campus): add handoff and deadline lifecycle"
```

### Task 10: 实现争议、私有证据和单轮裁决

**Files:**
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/dispute/domain/DisputeCase.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/dispute/domain/DisputeDecision.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/dispute/domain/DisputeReason.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/dispute/application/DisputeService.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/dispute/application/EvidenceStorage.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/dispute/api/DisputeController.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/unit/dispute/DisputeCaseTest.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/DisputeEvidenceIT.java`

**Interfaces:**
- Consumes: 订单参与者、剩余可争议数量、MinIO、管理员分配。
- Produces: 分阶段 `DisputeReason`、`REJECT/REFUND_ONLY/RETURN_AND_REFUND`、可供普通争议或质保案件授权绑定的逻辑 evidence ID、私有 `/content`。

- [ ] **Step 1: 写争议数量 RED 测试**

```java
@Test void cumulativeApprovedQuantityCannotExceedPurchasedQuantity() {
    DisputeCase first = open(orderOf(3), 2);
    first.decideRefundOnly(2);
    assertThatThrownBy(() -> openRemaining(orderOf(3), List.of(first), 2))
        .isInstanceOf(IllegalArgumentException.class);
}
```

Run: `.\mvnw.cmd -Dtest=DisputeCaseTest test`

Expected: FAIL，争议类型不存在。

- [ ] **Step 2: 实现单轮领域与管理员权限**

只有 `AWAITING_RECEIPT/AFTERSALE_WINDOW` 可创建普通争议；活动争议把订单置 `DISPUTED` 并冻结结算。`T0 + 72h` 前允许 `QUANTITY/MODEL/APPEARANCE/MISSING_PARTS/NOT_AS_DESCRIBED/FUNCTIONAL_DEFECT`，此后至 `T0 + 7d` 只允许 `FUNCTIONAL_DEFECT`。进水、摔落、错误供电、擅自拆修、正常耗损和已披露问题必须作为固定排除原因记录，不能自动判卖家责任。管理员只能选择固定裁决和批准数量，不能自由输入退款金额。

- [ ] **Step 3: 写证据 RED 测试**

真实 HTTP 覆盖 JPEG/PNG/WebP 10 MiB、PDF 20 MiB、MP4 100 MiB 边界和 `limit+1`，伪造类型、案件双方读取、非参与者/未分配管理员统一 404、MinIO 断连 503、Object Key/签名 URL 不出现在响应和日志。

Run: `.\mvnw.cmd -Dit.test=DisputeEvidenceIT verify`

Expected: FAIL，证据接口不存在。

- [ ] **Step 4: 实现流式证据和 ACL**

`EvidenceStorage` 复用 Task 4 的 `ObjectUploadCoordinator` 与 `PrivateObjectStorage`，但绑定前必须通过 `EvidenceCaseAccess.canAttach(caseType, caseId, actorId)` 验证案件参与者；Task 10 先提供普通争议实现，Task 12 增加质保案件实现。MP4 只检查容器签名和 Tika 类型，不转码、不缩略、不判断真实性。所有 `/content` 在打开 MinIO 对象前重新查询对应案件 ACL；预签名 TTL 固定不超过 2 分钟。

- [ ] **Step 5: 验证并提交**

Run: `.\mvnw.cmd -Dtest=DisputeCaseTest test`

Run: `.\mvnw.cmd -Dit.test=DisputeEvidenceIT verify`

```powershell
git add labs/07-campus-market/src
git commit -m "feat(campus): add disputes and private evidence"
```

### Task 11: 实现可信退回、部分退款、隔离库存和硬期限

**Files:**
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/dispute/domain/ReturnProofType.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/dispute/application/ReturnResolutionService.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/dispute/application/DisputeDeadlineScheduler.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/payment/application/SettlementService.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/unit/dispute/ReturnResolutionTest.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/PartialReturnRefundIT.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/DisputeDeadlineIT.java`

**Interfaces:**
- Consumes: `SELLER_CONFIRMED/PROVIDER_DELIVERED/ADMIN_CONFIRMED` 可信证明、退款占额、库存 quarantine。
- Produces: 部分/全额退款、隔离库存、`ESCALATED`、净结算。

- [ ] **Step 1: 写可信证明 RED 测试**

```java
@Test void buyerEvidenceAloneNeverTriggersRefund() {
    ReturnCase returned = caseWithBuyerEvidenceOnly();
    assertThat(returned.mayAutoRefundAt(hardDeadline.plusSeconds(1))).isFalse();
}
```

Run: `.\mvnw.cmd -Dtest=ReturnResolutionTest test`

Expected: FAIL，ReturnCase 不存在。

- [ ] **Step 2: 实现证明策略与退款计算**

可信证明仅三种固定枚举。退款额严格为 `unitPriceFen × approvedQuantity`；成功退回数量通过唯一业务键进入 `quarantined_quantity`，仅退款不写库存流水。

- [ ] **Step 3: 写部分退款与期限 RED 测试**

一单 3 件、批准退 1 件：断言退款 1 件金额、隔离 1 件、剩余 2 件净额在 `T0 + 7d` 后可结算；并发两次裁决不超购、不超退。卖家 72 小时不确认进入管理员复核；7 天产生 SLA 告警；14 天时可信证明自动退款、无可信证明进入 `ESCALATED` 且资金冻结。

Run: `.\mvnw.cmd -Dit.test=PartialReturnRefundIT,DisputeDeadlineIT verify`

Expected: FAIL，解析服务和调度不存在。

- [ ] **Step 4: 实现调度、净结算与库存后处理**

`SettlementService` 只在 `databaseNow >= T0 + 7d`、无活动普通争议、无预占退款、无未知网关结果时写唯一 settlement；卖家 30/90/180/365 天延长质保不延后这次结算。卖家显式重新上架或报损隔离库存，任何自动路径都不得直接增加可售数量。

- [ ] **Step 5: 验证并提交**

Run: `.\mvnw.cmd -Dtest=ReturnResolutionTest test`

Run: `.\mvnw.cmd -Dit.test=PartialReturnRefundIT,DisputeDeadlineIT verify`

```powershell
git add labs/07-campus-market/src
git commit -m "feat(campus): add partial return resolution"
```

### Task 12: 实现卖家延长质保、结算后义务与账户限制

**Files:**
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/warranty/domain/WarrantyCase.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/warranty/domain/WarrantyDecision.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/warranty/domain/SellerObligation.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/warranty/application/WarrantyService.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/warranty/application/SellerObligationService.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/warranty/application/WarrantyDeadlineScheduler.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/warranty/api/WarrantyController.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/warranty/api/SellerObligationController.java`
- Modify: `labs/07-campus-market/src/main/java/com/example/campusmarket/catalog/application/ListingService.java`
- Modify: `labs/07-campus-market/src/main/java/com/example/campusmarket/dispute/application/EvidenceStorage.java`
- Modify: `labs/07-campus-market/src/main/java/com/example/campusmarket/payment/application/RefundService.java`
- Modify: `labs/07-campus-market/src/main/java/com/example/campusmarket/payment/application/SettlementService.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/unit/warranty/WarrantyPolicyTest.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/WarrantyObligationIT.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/WarrantyDeadlineRaceIT.java`

**Interfaces:**
- Consumes: `SETTLED` 订单的不可变质保快照、Task 10 `EvidenceCaseAccess`、退款额度预占、未来 settlement、Outbox 和数据库时间。
- Produces: `openWarrantyCase(orderId, quantity, reason, idempotencyKey)`、`REJECT/REPAIR_COMPENSATION/REFUND_ONLY/RETURN_AND_REFUND`、`fundObligation`、未来结算幂等抵扣、发布/提现限制查询。

- [ ] **Step 1: 写质保期限与终态隔离 RED 测试**

```java
@Test void standardWarrantyUsesLeftClosedRightOpenDeadline() {
    TradeOrder settled = settledOrderWithSellerWarranty(90, t0);
    assertThat(WarrantyCase.open(settled, 1, FUNCTIONAL_DEFECT,
        t0.plus(89, DAYS))).isNotNull();
    assertThatThrownBy(() -> WarrantyCase.open(settled, 1, FUNCTIONAL_DEFECT,
        t0.plus(90, DAYS))).isInstanceOf(IllegalStateException.class);
    assertThat(settled.status()).isEqualTo(OrderStatus.SETTLED);
}

@Test void platformOnlyWarrantyRejectsDayEightClaim() {
    TradeOrder settled = settledOrderWithoutSellerWarranty(t0);
    assertThatThrownBy(() -> WarrantyCase.open(settled, 1, FUNCTIONAL_DEFECT,
        t0.plus(8, DAYS))).isInstanceOf(IllegalStateException.class);
}
```

Run: `.\mvnw.cmd -Dtest=WarrantyPolicyTest test`

Expected: FAIL，因为质保领域类型不存在。

- [ ] **Step 2: 实现质保领域与受控裁定**

卖家质保只读取订单快照中的 30/90/180/365 天；第七天后的案件不修改 `trade_order.status` 或 `settlement`。`FUNCTIONAL_DEFECT` 排除进水、摔落、错误供电、擅自拆修、正常耗损和已披露问题；`INVALID_MANUFACTURER_WARRANTY_PROOF` 只校验订单快照中的厂家凭证承诺。卖家响应期限固定 72 小时，之后进入管理员复核；管理员 SLA/硬期限仍为 7/14 天。

维修补偿额使用：

```java
approvedCompensationFen = min(verifiedQuoteFen, paidAmountFen - successfulRefundFen - reservedRefundFen)
```

全部运算使用 `Money`/`Math` 精确整数，报价必须为正数且有证据 ID；其他退款仍为 `unitPriceFen × approvedQuantity`。

- [ ] **Step 3: 写义务、证据与抵扣 RED 集成测试**

真实 HTTP 创建一个 90 天质保、已经 `SETTLED` 的键盘订单，在第 30 天提交功能故障和维修报价 1200 分。管理员裁定维修补偿后断言：创建唯一 `seller_obligation`、资金期限为数据库时间加 72 小时、订单仍为 `SETTLED`、平台尚未创建买家退款。非参与者读取质保证据返回同构 404。

随后覆盖：卖家主动筹资 1200 分只执行一次；资金齐备后只创建一个退款 Outbox；卖家逾期未筹资后发布与提现均被拒绝；未来 settlement 抵扣同一业务键两次只记一次；足额清偿后限制解除。并发普通退款、维修赔付和质保退款时必须满足：

```text
successful_refund_fen + reserved_refund_fen + requested_compensation_fen <= paid_amount_fen
funded_amount_fen <= obligation_amount_fen
```

Run: `.\mvnw.cmd -Dit.test=WarrantyObligationIT verify`

Expected: FAIL，质保 API、义务和限制尚不存在。

- [ ] **Step 4: 实现应用服务、ACL、筹资和未来款抵扣**

`WarrantyService` 使用 `(orderId, idempotencyKey)` 唯一键创建案件，复用 Task 10 的流式证据存储但在物理读取前查询 `warranty_case` 参与者/被分配管理员 ACL。裁定与 `seller_obligation`、审计和 Outbox 同事务写入。`SellerObligationService` 以条件更新累计筹资；资金不足时保持 `AWAITING_FUNDING`，禁止平台垫付或提前请求退款。未来 settlement 先按最早到期义务抵扣，抵扣业务键固定为 `(settlementId, obligationId)`，义务全部清偿后才幂等解除发布/提现限制并触发买家退款或补偿。

- [ ] **Step 5: 写截止时间竞态并实现租约调度**

在卖家筹资截止时刻并发执行最后一笔筹资与 `WarrantyDeadlineScheduler`，只允许以下结果之一：义务足额且限制解除，或义务逾期且限制生效；禁止同时退款两次或丢失已筹金额。相同方式覆盖卖家响应期限和管理员 7/14 天期限，领取必须使用 owner、claim token、租约和数据库时间。

Run: `.\mvnw.cmd -Dit.test=WarrantyDeadlineRaceIT verify`

Expected: PASS，竞态严格串行化且 0 skipped。

- [ ] **Step 6: 验证并提交**

Run: `.\mvnw.cmd -Dtest=WarrantyPolicyTest test`

Run: `.\mvnw.cmd -Dit.test=WarrantyObligationIT,WarrantyDeadlineRaceIT verify`

```powershell
git add labs/07-campus-market/src
git commit -m "feat(campus): add extended seller warranty"
```

### Task 13: 完成评价、统一错误、安全审计与可观测性

**Files:**
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/review/ReviewService.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/review/ReviewController.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/api/ApiExceptionHandler.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/api/SecurityConfiguration.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/observability/AuditRecorder.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/observability/SafeAuditEvent.java`
- Create: `labs/07-campus-market/src/main/java/com/example/campusmarket/observability/CampusMetrics.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/unit/observability/SafeAuditEventTest.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/ApiSecurityAndReviewIT.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/MetricsIT.java`

**Interfaces:**
- Consumes: 已结算订单、未清偿卖家义务与发布/提现限制、Spring Security、Micrometer。
- Produces: 双方一次评价、400/401/403/404/409/422/503、低基数指标和脱敏审计。

- [ ] **Step 1: 写审计与错误 RED 测试**

断言 Token、验证码、签名、Object Key、预签名 URL、邮箱和异常堆栈被拒绝进入审计；未知字段为 400；私有资源越权为与不存在同构的 404；中文 Content-Type 精确为 UTF-8。

Run: `.\mvnw.cmd -Dtest=SafeAuditEventTest test`

Expected: FAIL，安全审计类型不存在。

- [ ] **Step 2: 实现固定错误和审计动作**

错误体固定：

```java
public record ApiError(String code, String message, String correlationId) {}
```

correlationId 由服务端安全随机生成，不接受客户端覆盖。错误 message 使用有限中文枚举，不拼接底层异常。

- [ ] **Step 3: 写评价与指标 RED 测试**

只有 `SETTLED` 订单参与者可各评价一次；重复评价 409，非参与者 404。指标覆盖规格第 14 节，包括质保案件状态、卖家响应/筹资超时、未清偿义务和受限卖家数量；Meter ID tags 不含任何业务 ID、邮箱、provider reference 或异常文本。

Run: `.\mvnw.cmd -Dit.test=ApiSecurityAndReviewIT,MetricsIT verify`

Expected: FAIL，评价与指标不存在。

- [ ] **Step 4: 实现并验证**

Run: `.\mvnw.cmd -Dtest=SafeAuditEventTest test`

Run: `.\mvnw.cmd -Dit.test=ApiSecurityAndReviewIT,MetricsIT verify`

```powershell
git add labs/07-campus-market/src
git commit -m "feat(campus): add reviews security and metrics"
```

### Task 14: 完成连续故障演练、文档和全量验收

**Files:**
- Create: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/CampusMarketJourneyIT.java`
- Create: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/RecoveryDrillIT.java`
- Create: `labs/07-campus-market/README.md`
- Create: `labs/07-campus-market/TROUBLESHOOTING.md`
- Modify after branch verification: `README.md`
- Modify after branch verification: `notes/learning-log.md`
- Modify after branch verification: `interview/question-bank.md`

**Interfaces:**
- Consumes: Tasks 1–13 全部行为和外部适配器。
- Produces: 可手工演示闭环、三轮故障恢复证据、实验分支验收提交和 main 文档记录。

- [ ] **Step 1: 写端到端旅程 RED 测试**

真实 HTTP 主旅程固定覆盖：校园邮箱注册买卖双方 → JWT 登录 → 发布 6 本教材 → 搜索 → 买家购买 3 本 → 模拟支付回调 → 卖家交付 → 三天内买家争议 1 本 → 管理员裁决退货退款 → 卖家确认退回 → 部分退款回调 → 1 本进入隔离 → 七天试用窗口关闭 → 剩余净额结算 → 双方评价。

同一测试另走电子商品质保旅程：发布带 90 天卖家质保的二手键盘 → 下单时保存质保快照 → 七天后正常结算 → 第 30 天创建功能故障质保案件 → 管理员裁定维修补偿 → 卖家逾期被限制发布/提现 → 未来结算款幂等抵扣 → 买家补偿成功 → 卖家限制解除；订单始终保持 `SETTLED`。

Run: `.\mvnw.cmd -Dit.test=CampusMarketJourneyIT verify`

Expected: 若任何闭环行为缺失则 FAIL；不能通过 mock 外部系统替代真实容器。

- [ ] **Step 2: 写三轮恢复演练 RED 测试**

每轮通过 Toxiproxy 分别中断 RabbitMQ、Elasticsearch、MinIO 或模拟网关，断言积压和 503 可观察；恢复后运行 dispatcher/reconciliation 并核对：

```text
available_quantity >= 0
库存返还业务键无重复
successful_refund + reserved_refund <= paid_amount
每订单最多一个 settlement
Outbox/Inbox 无过期未决项
证据 ACL 始终有效
搜索最终与 MySQL 在售集合一致
```

Run: `.\mvnw.cmd -Dit.test=RecoveryDrillIT verify`

Expected: 初次因缺少完整恢复或指标断言而 FAIL。

- [ ] **Step 3: 实现恢复收敛并验证专项测试**

为 RabbitMQ 恢复调用 `OutboxDispatcher.dispatchOnce` 和过期 Inbox 接管；为 Elasticsearch 恢复调用 `SearchProjector.catchUp` 并核对 MySQL 高水位；为 MinIO 恢复调用 `StorageCleanupScheduler.runOnce`；为支付未知结果调用 payment/refund reconciliation。每个 `runOnce` 都使用数据库租约、固定批量和 claim token，不引入测试专用生产分支。

Run: `.\mvnw.cmd -Dit.test=CampusMarketJourneyIT,RecoveryDrillIT verify`

Expected: 两个 IT 全部 PASS、0 skipped，RecoveryDrillIT 明确记录 3 轮。

- [ ] **Step 4: 写 README 与 TROUBLESHOOTING**

README 必须包含模块图、CAS 只预留不接入的边界、校园邮箱含义、API 示例、模拟邮箱/支付、整数分、订单状态机、三天验收/七天试用/30-365 天卖家质保、厂家质保区分、结算后义务与限制、证据类型、真实支付适配器契约、Compose 启停、索引重建、故障演练和验证命令。TROUBLESHOOTING 必须覆盖 Docker、SmartCN、Rabbit confirm、Redis、MinIO、回调验签、未知支付结果、退款占额、三天/七天/质保边界竞态、卖家筹资失败、MP4 上限和 `ESCALATED`。

- [ ] **Step 5: 运行分支完整验收**

Run: `.\mvnw.cmd test`

Run: `.\mvnw.cmd clean verify`

Run: `git diff --check`

Expected: `0 failures、0 errors、0 skipped`；Surefire/Failsafe XML 失败扫描为空；Docker 外部测试、SmartCN、模拟支付契约和三轮恢复实际运行。

- [ ] **Step 6: 提交实验分支最终文档**

```powershell
git add labs/07-campus-market/README.md labs/07-campus-market/TROUBLESHOOTING.md labs/07-campus-market/src
git commit -m "docs(campus): document verified campus market lab"
```

- [ ] **Step 7: 返回 main 写验收记录并再次提交**

只在实验分支完整验收后更新 `main`：路线状态改为“已验收”，记录准确分支提交、测试计数、0 skipped、三轮故障演练和已知边界；面试题覆盖 CAS Service、库存条件更新、截止时间竞态、整数分、退款占额、Outbox/Inbox、可信退回证明、隔离库存和真实支付适配器。

```powershell
git add README.md notes/learning-log.md interview/question-bank.md
git commit -m "docs: record verified campus market lab"
```

## 计划自检映射

| 规格要求 | 实施任务 |
| --- | --- |
| 独立分支、固定依赖与 Compose | Task 1 |
| 金额、事件、数据库约束 | Task 2 |
| 校园邮箱、JWT、CAS 端口 | Task 3 |
| 商品、媒体、批量库存 | Task 4 |
| 幂等下单、条件扣库存 | Task 5 |
| Outbox/Inbox、租约与 fencing | Task 6 |
| SmartCN 搜索与在线重建 | Task 7 |
| 模拟支付、回调、退款占额、对账 | Task 8 |
| 交付、收货、三天验收、七天试用和竞态 | Task 9 |
| 分阶段争议理由、证据 ACL 和单轮裁决 | Task 10 |
| 可信退回、部分退款、七天后结算、隔离库存和硬期限 | Task 11 |
| 30/90/180/365 天质保、结算后义务、抵扣与账户限制 | Task 12 |
| 评价、错误、安全审计和指标 | Task 13 |
| E2E、三轮故障、文档和 main 验收记录 | Task 14 |

## 执行纪律

- 每个 Task 开始前重新读取该 Task 的 Files、Interfaces 和 Global Constraints。
- 每个 RED 必须因目标行为缺失而失败，不得把编译错误、容器未启动或测试 skipped 当作预期失败。
- 每个 GREEN 只实现当前测试需要的最小行为；跨任务重构先记录，等相关测试全绿后单独处理。
- 每次提交只暂存当前 Task 文件；提交前运行当前专项测试与 `git diff --check`。
- Task 14 完整验收前不得把路线状态写成“已验收”，不得宣称真实 CAS、真实物流或真实支付已经接入。
