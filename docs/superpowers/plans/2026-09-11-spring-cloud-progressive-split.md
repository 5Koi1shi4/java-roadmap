# 实验八 8.1 身份服务渐进拆分 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保持实验七公开 API 和全部交易不变量的前提下，交付可独立验收的 Eureka、API Gateway、身份服务、独立身份库与兼容业务单体。

**Architecture:** 从实验七验收提交 `4148f1e` 创建单实验分支，把原模块化单体先迁入 Maven 聚合工程，再沿身份边界拆出独立进程和数据库。客户端只访问显式路由的 Gateway；Gateway 与兼容单体分别验证身份服务签发的 RS256 JWT，MySQL 最小权限账号和故障测试共同证明边界。

**Tech Stack:** JDK 17、Maven Wrapper、Spring Boot 3.5.16、Spring Cloud 2025.0.3、Spring Cloud Gateway WebFlux、Netflix Eureka、Spring Security OAuth2 Resource Server/Jose、MySQL 8.4、Redis 7.4、RabbitMQ 3.13、Elasticsearch 8.18.8、MinIO、Flyway、JUnit 5、AssertJ、Testcontainers。

## Global Constraints

- 实验分支固定为 `learning/spring-cloud-split`，从实验七验收提交 `4148f1e` 创建；活动树只保留根 `.gitignore` 和 `labs/08-spring-cloud-split/`。
- Java 与 Maven 固定使用 JDK 17；Spring Boot 固定 `3.5.16`，Spring Cloud BOM 固定 `2025.0.3`。
- 全程中文沟通、中文学习文档和中文代码注释；代码、API、指标和标准协议名可保留英文。
- HTTP JSON 必须显式返回 `application/json; charset=UTF-8`；真实 HTTP 集成测试必须验证中文内容和 charset。
- 身份库与交易库使用独立 database/schema 和最小权限账号，任何服务不得跨库查询。
- Access Token 只使用 RS256，固定 15 分钟，严格验证 `kid`、`iss`、`aud=campus-market-api`、`sub`、`roles`、`iat`、`exp`。
- 缺失或无效身份返回 401，认证成功但权限不足返回 403，服务发现或路由目标不可用返回不泄密的 503。
- Gateway 使用显式 `lb://identity-service` 和 `lb://legacy-market-service` 路由，禁止自动暴露 `/{serviceId}/**`。
- 注册、登录、验证码和所有写请求不自动重试；不得用降级数据掩盖依赖故障。
- 不提交真实密码、Token、验证码、私钥、`.env` 或本地配置；测试 RSA 密钥只存在于测试资源。
- 先写失败测试，确认失败原因，再写最小实现；每个任务只提交本任务文件，不做无关重构。
- 完整验收前先执行只读 `docker info`；外部测试 skipped 不算通过。

---

## File Structure

最终目录职责如下：

```text
labs/08-spring-cloud-split/
├── pom.xml                         # 聚合父 POM 与 Spring Cloud BOM
├── mvnw.cmd / .mvn/                # 固定 Maven Wrapper
├── compose.yaml                    # 仅启动本实验需要的本地依赖和四个应用
├── discovery-server/               # Eureka Server
├── api-gateway/                    # WebFlux Gateway、路由、边界认证和错误
├── identity-service/               # 身份领域、身份 HTTP、identity_db Flyway、RS256/JWKS
├── legacy-market-service/          # 除身份外的实验七业务、market_db Flyway、资源服务器
├── platform-test-support/          # 仅测试依赖的 RSA/JWT/容器/HTTP 夹具
├── README.md
└── TROUBLESHOOTING.md
```

生产模块之间不建立 Java 领域模型依赖。`platform-test-support` 可以被其他模块以 test scope 使用，但任何生产模块不得在 compile/runtime scope 依赖它。

---

### Task 1: 创建实验八独立工作树并保留实验七基线

**Files:**
- Move: `labs/07-campus-market/` → `labs/08-spring-cloud-split/`
- Verify: `.gitignore`

**Interfaces:**
- Consumes: 实验七验收提交 `4148f1e`。
- Produces: `learning/spring-cloud-split` 分支和隔离工作树 `<workspace>/java-roadmap/.worktrees/spring-cloud-split`。

- [ ] **Step 1: 验证工作树目录被忽略且实验七基线干净**

Run:

```powershell
git check-ignore .worktrees
git -c safe.directory='<workspace>/java-roadmap/.worktrees/campus-market' status --short --branch
git -c safe.directory='<workspace>/java-roadmap/.worktrees/campus-market' rev-parse HEAD
```

Expected: `.worktrees` 被忽略；实验七无未提交文件；HEAD 为 `4148f1eae3eed130c108c9cf8cb00ff8dff289b2`。

- [ ] **Step 2: 创建分支和工作树**

Run:

```powershell
git worktree add '.worktrees/spring-cloud-split' -b learning/spring-cloud-split 4148f1eae3eed130c108c9cf8cb00ff8dff289b2
git -C '.worktrees/spring-cloud-split' mv 'labs/07-campus-market' 'labs/08-spring-cloud-split'
```

- [ ] **Step 3: 运行未改行为的快速基线**

Run:

```powershell
Set-Location '<workspace>/java-roadmap/.worktrees/spring-cloud-split/labs/08-spring-cloud-split'
.\mvnw.cmd test
```

Expected: 实验七原有 137 个 Surefire 测试通过，0 failures、0 errors、0 skipped。

- [ ] **Step 4: 验证活动树边界并提交**

Run:

```powershell
Set-Location '<workspace>/java-roadmap/.worktrees/spring-cloud-split'
git add -- '.gitignore' 'labs/08-spring-cloud-split'
git commit -m "chore(cloud): seed experiment eight from campus market"
git ls-tree -r --name-only HEAD | Select-String -NotMatch '^(\.gitignore|labs/08-spring-cloud-split/)'
```

Expected: 最后一条命令无输出；提交后树中只有 `.gitignore` 与 `labs/08-spring-cloud-split/**`。

---

### Task 2: 改造成保持行为不变的 Maven 聚合工程

**Files:**
- Modify: `labs/08-spring-cloud-split/pom.xml`
- Create: `labs/08-spring-cloud-split/legacy-market-service/pom.xml`
- Move: `labs/08-spring-cloud-split/src/` → `labs/08-spring-cloud-split/legacy-market-service/src/`
- Modify: `labs/08-spring-cloud-split/README.md`

**Interfaces:**
- Consumes: 原实验七单模块源码与测试。
- Produces: 父 artifact `campus-market-cloud` 和可独立启动的 `legacy-market-service`；此任务不改变 HTTP 或数据库行为。

- [ ] **Step 1: 记录聚合前的测试清单**

Run:

```powershell
rg -n '<testsuite ' target/surefire-reports/TEST-*.xml
rg --files src/main/java src/test/java | Measure-Object
```

Expected: 保存测试数和源码文件数，作为移动后等价性对照。

- [ ] **Step 2: 移动源码并编写父 POM**

Run:

```powershell
New-Item -ItemType Directory -Path 'legacy-market-service' | Out-Null
git mv 'src' 'legacy-market-service/src'
```

创建目录本身不产生仓库文件；父 POM、子 POM 和空模块 POM 一律使用 `apply_patch` 写入，禁止用 shell 重定向生成文件。

父 `pom.xml` 固定核心结构：

```xml
<packaging>pom</packaging>
<modules>
  <module>platform-test-support</module>
  <module>discovery-server</module>
  <module>identity-service</module>
  <module>legacy-market-service</module>
  <module>api-gateway</module>
</modules>
<properties>
  <java.version>17</java.version>
  <spring-cloud.version>2025.0.3</spring-cloud.version>
</properties>
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-dependencies</artifactId>
      <version>${spring-cloud.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

先为尚未实现的四个模块创建只有合法 `pom.xml` 的空 jar，使 Reactor 能解析；不得创建业务类或占位接口。

- [ ] **Step 3: 让 legacy 子 POM 继承父版本和原依赖**

`legacy-market-service/pom.xml` 使用：

```xml
<parent>
  <groupId>com.example</groupId>
  <artifactId>campus-market-cloud</artifactId>
  <version>0.0.1-SNAPSHOT</version>
  <relativePath>../pom.xml</relativePath>
</parent>
<artifactId>legacy-market-service</artifactId>
```

保留原 Surefire/Failsafe 配置和全部依赖，不在本任务迁出身份代码。

- [ ] **Step 4: 验证移动前后测试集合相同**

Run:

```powershell
.\mvnw.cmd -pl legacy-market-service test
.\mvnw.cmd test
```

Expected: legacy 的 137 个测试通过；Reactor 成功；没有测试因路径移动而消失。

- [ ] **Step 5: 提交聚合结构**

```powershell
git add -- 'labs/08-spring-cloud-split'
git commit -m "build(cloud): introduce multi-module reactor"
```

---

### Task 3: 建立可复用但仅测试可见的 RSA/JWT 夹具

**Files:**
- Modify: `labs/08-spring-cloud-split/platform-test-support/pom.xml`
- Create: `labs/08-spring-cloud-split/platform-test-support/src/test/java/com/example/campusmarket/testsupport/TestRsaKeys.java`
- Create: `labs/08-spring-cloud-split/platform-test-support/src/test/java/com/example/campusmarket/testsupport/TestJwtFactory.java`
- Create: `labs/08-spring-cloud-split/platform-test-support/src/test/java/com/example/campusmarket/testsupport/HttpAssertions.java`
- Create: `labs/08-spring-cloud-split/platform-test-support/src/test/java/com/example/campusmarket/testsupport/TestJwtFactoryTest.java`

**Interfaces:**
- Produces: `TestJwtFactory.issue(UUID userId, Set<String> roles, Instant issuedAt, Duration ttl, String issuer, String audience, String kid)` 和 `HttpAssertions.assertJsonUtf8(HttpResponse<?>)`。
- Consumed by: identity、legacy、Gateway 和平台旅程测试，全部以 test scope 引用。

- [ ] **Step 1: 写失败的 JWT 契约测试**

核心测试：

```java
@Test
void issuesRs256TokenWithExactIdentityContract() {
    UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    String token = TestJwtFactory.issue(userId, Set.of("ROLE_USER"),
        Instant.parse("2026-09-11T00:00:00Z"), Duration.ofMinutes(15),
        "http://gateway.test", "campus-market-api", "test-key-1");

    SignedJWT jwt = SignedJWT.parse(token);
    assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
    assertThat(jwt.getHeader().getKeyID()).isEqualTo("test-key-1");
    assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(userId.toString());
    assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("campus-market-api");
    assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant())
        .isEqualTo(Instant.parse("2026-09-11T00:15:00Z"));
}
```

- [ ] **Step 2: 运行测试并确认因类不存在而失败**

Run: `.\mvnw.cmd -pl platform-test-support -Dtest=TestJwtFactoryTest test`

Expected: FAIL，原因是 `TestJwtFactory`/`TestRsaKeys` 尚不存在，不是 POM 或语法错误。

- [ ] **Step 3: 实现最小测试夹具**

`TestJwtFactory` 固定公开签名：

```java
public final class TestJwtFactory {
    public static String issue(UUID userId, Set<String> roles, Instant issuedAt,
                               Duration ttl, String issuer, String audience, String kid) {
        if (!Duration.ofMinutes(15).equals(ttl) || roles == null || roles.isEmpty()
                || !Set.of("ROLE_USER", "ROLE_ADMIN").containsAll(roles)) {
            throw new IllegalArgumentException("测试 JWT 契约无效");
        }
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject(Objects.requireNonNull(userId).toString())
            .issuer(Objects.requireNonNull(issuer))
            .audience(Objects.requireNonNull(audience))
            .issueTime(Date.from(Objects.requireNonNull(issuedAt)))
            .expirationTime(Date.from(issuedAt.plus(ttl)))
            .claim("roles", roles.stream().sorted().toList())
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
            .keyID(Objects.requireNonNull(kid)).type(JOSEObjectType.JWT).build(), claims);
        try {
            jwt.sign(new RSASSASigner(TestRsaKeys.privateKey()));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException("测试 JWT 签发失败", ex);
        }
    }
    public static RSAKey publicJwk(String kid) {
        return new RSAKey.Builder(TestRsaKeys.publicKey())
            .keyID(Objects.requireNonNull(kid))
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .build();
    }
    private TestJwtFactory() { }
}
```

`TestRsaKeys` 使用 `KeyPairGenerator.getInstance("RSA")` 在测试 JVM 启动时生成一次 2048 位临时密钥，并提供 `privateKey()`、`publicKey()` 和 `writePemPair(Path directory)`；仓库不保存 PEM。构造器/方法拒绝空值、非 15 分钟 TTL、非法角色与空 `kid`。`maven-jar-plugin` 只为该模块附加 classifier 为 `tests` 的 test-jar，其他模块仅以 test scope、`type=test-jar`、`classifier=tests` 依赖；任何测试密钥都不进入普通运行 jar。

- [ ] **Step 4: 运行夹具测试和密钥泄漏扫描**

Run:

```powershell
.\mvnw.cmd -pl platform-test-support test
rg -n "BEGIN (RSA )?PRIVATE KEY" .
```

Expected: 测试通过；扫描无其他私钥。

- [ ] **Step 5: 提交测试支持**

```powershell
git add -- 'labs/08-spring-cloud-split/platform-test-support' 'labs/08-spring-cloud-split/pom.xml'
git commit -m "test(cloud): add isolated RSA contract fixtures"
```

---

### Task 4: 建立身份库 Flyway 与数据库权限边界

**Files:**
- Modify: `labs/08-spring-cloud-split/identity-service/pom.xml`
- Create: `labs/08-spring-cloud-split/identity-service/src/main/resources/db/migration/V1__identity.sql`
- Create: `labs/08-spring-cloud-split/identity-service/src/test/java/com/example/campusmarket/identity/IdentitySchemaIT.java`
- Create: `labs/08-spring-cloud-split/platform-test-support/src/test/java/com/example/campusmarket/testsupport/SplitDatabaseContainer.java`
- Create: `labs/08-spring-cloud-split/legacy-market-service/src/test/java/com/example/campusmarket/integration/DatabaseOwnershipIT.java`

**Interfaces:**
- Produces: `identity_db` 中三张身份表、账号 `identity_app`；`market_db` 和账号 `market_app`；测试方法 `SplitDatabaseContainer.identityProperties(...)` 与 `marketProperties(...)`。
- Consumed by: 后续两个应用和跨服务测试。

- [ ] **Step 1: 写失败的数据所有权集成测试**

关键断言：

```java
@Test
void databaseAccountsCannotCrossServiceBoundary() {
    assertThatThrownBy(() -> identityJdbc.queryForObject(
        "SELECT COUNT(*) FROM market_db.listing", Integer.class))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> marketJdbc.queryForObject(
        "SELECT COUNT(*) FROM identity_db.campus_user", Integer.class))
        .isInstanceOf(DataAccessException.class);
}
```

`IdentitySchemaIT` 同时断言只有 `campus_user`、`email_verification`、`external_identity` 三张业务表，并保留原 CHECK、唯一键与身份内部外键。

- [ ] **Step 2: 运行并确认权限/表尚不存在**

Run: `.\mvnw.cmd -pl identity-service,legacy-market-service -Dit.test=IdentitySchemaIT,DatabaseOwnershipIT verify`

Expected: FAIL，原因是数据库初始化器和身份迁移尚不存在。

- [ ] **Step 3: 实现双库容器初始化和身份迁移**

`SplitDatabaseContainer` 通过 MySQL 管理连接执行固定 SQL：

```sql
CREATE DATABASE identity_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE market_db CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'identity_app'@'%' IDENTIFIED BY 'identity_test_password';
CREATE USER 'market_app'@'%' IDENTIFIED BY 'market_test_password';
GRANT ALL PRIVILEGES ON identity_db.* TO 'identity_app'@'%';
GRANT ALL PRIVILEGES ON market_db.* TO 'market_app'@'%';
```

不要授予全局权限或跨库 `SELECT`。`V1__identity.sql` 从实验七 `V1__identity_catalog.sql` 精确提取前三张表。

- [ ] **Step 4: 运行边界测试**

Run: `.\mvnw.cmd -pl identity-service,legacy-market-service -Dit.test=IdentitySchemaIT,DatabaseOwnershipIT verify`

Expected: PASS；两次跨库访问均由 MySQL 拒绝。

- [ ] **Step 5: 提交数据边界**

```powershell
git add -- 'labs/08-spring-cloud-split/identity-service' 'labs/08-spring-cloud-split/legacy-market-service/src/test' 'labs/08-spring-cloud-split/platform-test-support'
git commit -m "feat(identity): isolate identity database ownership"
```

---

### Task 5: 用 RS256 和 JWKS 实现身份服务

**Files:**
- Create: `labs/08-spring-cloud-split/identity-service/src/main/java/com/example/campusmarket/identity/IdentityServiceApplication.java`
- Copy/adapt: `legacy-market-service/src/main/java/com/example/campusmarket/identity/**` → `identity-service/src/main/java/com/example/campusmarket/identity/**`
- Create: `labs/08-spring-cloud-split/identity-service/src/main/java/com/example/campusmarket/identity/security/IdentityTokenIssuer.java`
- Create: `labs/08-spring-cloud-split/identity-service/src/main/java/com/example/campusmarket/identity/security/RsaKeyProperties.java`
- Create: `labs/08-spring-cloud-split/identity-service/src/main/java/com/example/campusmarket/identity/api/JwksController.java`
- Create: `labs/08-spring-cloud-split/identity-service/src/main/java/com/example/campusmarket/identity/api/IdentityApiExceptionHandler.java`
- Create: `labs/08-spring-cloud-split/identity-service/src/main/java/com/example/campusmarket/identity/observability/IdentityMetrics.java`
- Create: `labs/08-spring-cloud-split/identity-service/src/main/resources/application.yml`
- Copy/adapt: identity unit tests and `AuthFlowIT` into `identity-service/src/test/java/`；legacy 中的旧身份实现与测试保留到 Task 6，保证 Task 5 结束时 Reactor 仍然通过
- Create: `labs/08-spring-cloud-split/identity-service/src/test/java/com/example/campusmarket/identity/security/IdentityTokenIssuerTest.java`
- Create: `labs/08-spring-cloud-split/identity-service/src/test/java/com/example/campusmarket/identity/api/StrictAuthJsonIT.java`
- Create: `labs/08-spring-cloud-split/identity-service/src/test/java/com/example/campusmarket/identity/api/JwksIT.java`

**Interfaces:**
- Produces: 原三个 `/api/auth` 接口；`GET /api/auth/.well-known/jwks.json`；`IdentityTokenIssuer.issue(AuthenticatedUser)`。
- Token: RS256、15 分钟、`kid`、`iss`、`aud=campus-market-api`、UUID `sub`、非空白名单 `roles`、`iat`、`exp`。

- [ ] **Step 1: 写失败的 Token 与 JWKS 测试**

核心测试：

```java
@Test
void signsExactFifteenMinuteRs256Token() {
    String token = issuer.issue(new AuthenticatedUser(USER_ID, Set.of("ROLE_USER")));
    SignedJWT jwt = SignedJWT.parse(token);
    assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
    assertThat(jwt.getHeader().getKeyID()).isEqualTo("identity-key-1");
    assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("http://gateway.test");
    assertThat(jwt.getJWTClaimsSet().getAudience()).containsExactly("campus-market-api");
    assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant())
        .isEqualTo(jwt.getJWTClaimsSet().getIssueTime().toInstant().plusSeconds(900));
}
```

`JwksIT` 断言响应只含公钥 `kty`、`kid`、`use`、`alg`、`n`、`e`，不含 `d`、`p`、`q` 或 PEM。

- [ ] **Step 2: 运行并确认旧 HS256 实现不满足契约**

Run: `.\mvnw.cmd -pl identity-service -Dtest=IdentityTokenIssuerTest -Dit.test=JwksIT verify`

Expected: FAIL，原因是 `IdentityTokenIssuer`/JWKS 未实现或算法仍不是 RS256。

- [ ] **Step 3: 实现 TokenIssuer 和严格密钥配置**

固定公开接口：

```java
public final class IdentityTokenIssuer {
    public String issue(AuthenticatedUser user);
    public Duration ttl(); // 恒为 PT15M
}

@ConfigurationProperties("campus.market.jwt")
public record RsaKeyProperties(String issuer, String audience, String keyId,
                               Resource privateKey, Resource publicKey) { }
```

`RsaKeyProperties` 构造时拒绝空值、非 `campus-market-api` audience、空 `kid`、不可读密钥和不匹配的公私钥。删除生产 HS256 `JwtService` 和 `campus.market.jwt.secret`。

- [ ] **Step 4: 迁入身份领域并解耦跨领域指标**

移动 `CampusEmail`、验证码、注册、登录、设备 Cookie 和 CAS 端口。把 `CampusMetrics` 依赖替换为只含固定低基数计数器的 `IdentityMetrics`；身份服务不得依赖 legacy 模块。

`AuthRequest` 使用 Bean Validation 和严格 Jackson：未知字段、错误类型、缺失 email/password/code、非法 purpose 和越界密码返回 400。公开登录只生成 `ROLE_USER`。

- [ ] **Step 5: 运行身份单元与真实 MySQL/Redis HTTP 测试**

Run:

```powershell
.\mvnw.cmd -pl identity-service test
.\mvnw.cmd -pl identity-service -Dit.test=AuthFlowIT,StrictAuthJsonIT,JwksIT verify
```

Expected: 原身份流程、限流、一次消费、中文 UTF-8、RS256 和 JWKS 全部通过，0 skipped。

- [ ] **Step 6: 验证依赖方向并提交**

Run: `rg -n "legacy-market-service|com\.example\.campusmarket\.(catalog|order|payment|dispute|warranty|storage|messaging)" identity-service`

Expected: 无生产代码命中。

```powershell
git add -- 'labs/08-spring-cloud-split/identity-service' 'labs/08-spring-cloud-split/legacy-market-service' 'labs/08-spring-cloud-split/pom.xml'
git commit -m "feat(identity): extract RS256 identity service"
```

---

### Task 6: 将兼容单体改为独立资源服务器和交易库

**Files:**
- Modify: `labs/08-spring-cloud-split/legacy-market-service/pom.xml`
- Delete: `legacy-market-service/src/main/java/com/example/campusmarket/identity/**`
- Move/modify: `legacy-market-service/src/main/java/com/example/campusmarket/CampusMarketApplication.java` → `legacy-market-service/src/main/java/com/example/campusmarket/legacy/LegacyMarketApplication.java`
- Create: `legacy-market-service/src/main/java/com/example/campusmarket/security/AuthenticatedUser.java`
- Create: `legacy-market-service/src/main/java/com/example/campusmarket/security/JwtPrincipalConverter.java`
- Create: `legacy-market-service/src/main/java/com/example/campusmarket/security/ResourceServerConfiguration.java`
- Modify: controllers importing `identity.application.AuthenticatedUser`
- Modify: `legacy-market-service/src/main/resources/db/migration/V1__identity_catalog.sql`
- Modify: migrations `V2__order_payment.sql`, `V3__dispute_review.sql`, `V4__messaging_audit.sql`, `V19__dispute_assignment.sql`, `V25__seller_warranty_obligations.sql`, `V29__seller_withdrawal_commands.sql`
- Modify: `legacy-market-service/src/main/resources/application.yml`
- Move/adapt: all legacy tests using `JwtService` to `TestJwtFactory`
- Create: `legacy-market-service/src/test/java/com/example/campusmarket/security/ResourceServerSecurityIT.java`
- Create: `legacy-market-service/src/test/java/com/example/campusmarket/security/ApplicationIsolationTest.java`
- Create: `legacy-market-service/src/test/java/com/example/campusmarket/integration/MarketSchemaBoundaryIT.java`

**Interfaces:**
- Consumes: 身份服务 JWKS URL 和 JWT 契约。
- Produces: 无身份表的 `market_db`、独立 Bearer Token 验证、与实验七一致的业务 API。

- [ ] **Step 1: 写失败的资源服务器测试**

核心用例：

```java
@Test
void directCallRejectsForgedInternalHeadersWithoutBearerToken() throws Exception {
    HttpResponse<String> response = get("/api/listings/mine", Map.of(
        "X-User-Id", USER_ID.toString(), "X-User-Roles", "ROLE_ADMIN"));
    assertThat(response.statusCode()).isEqualTo(401);
    HttpAssertions.assertJsonUtf8(response);
}

@Test
void validUserTokenCannotAccessAdminRoute() throws Exception {
    HttpResponse<String> response = get("/api/admin/probe", bearer(userToken));
    assertThat(response.statusCode()).isEqualTo(403);
}
```

再分别生成过期、错误 `iss`、错误 `aud`、非法角色、未知 `kid`、HS256 和篡改 Token，全部断言 401。

- [ ] **Step 2: 写失败的交易 schema 测试**

```java
@Test
void marketSchemaContainsNoIdentityTablesOrCrossSchemaForeignKeys() {
    assertThat(tableNames()).doesNotContain("campus_user", "email_verification", "external_identity");
    assertThat(importedKeyTargets()).noneMatch(name -> name.startsWith("identity_db."));
}
```

`ApplicationIsolationTest` 把 identity-service jar 放在 test classpath 后启动 legacy 上下文，并断言不存在 `AuthController`、`AuthService`、`IdentityTokenIssuer` Bean，防止根包扫描把身份服务重新装回兼容单体。

- [ ] **Step 3: 运行并确认旧身份过滤器和 schema 导致失败**

Run: `.\mvnw.cmd -pl legacy-market-service -Dtest=ResourceServerSecurityIT,ApplicationIsolationTest -Dit.test=MarketSchemaBoundaryIT verify`

Expected: FAIL，原因分别是资源服务器未实现、身份表/外键仍存在。

- [ ] **Step 4: 实现资源服务器 Principal 转换**

固定类型：

```java
public record AuthenticatedUser(UUID userId, Set<String> roles) {
    public AuthenticatedUser {
        Objects.requireNonNull(userId, "用户 ID 不能为空");
        roles = Set.copyOf(roles);
    }
}

public final class JwtPrincipalConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {
    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        List<String> rawRoles = jwt.getClaimAsStringList("roles");
        if (rawRoles == null || rawRoles.isEmpty()
                || !Set.of("ROLE_USER", "ROLE_ADMIN").containsAll(rawRoles)) {
            throw new JwtException("JWT 角色非法");
        }
        Set<String> roles = Set.copyOf(rawRoles);
        List<GrantedAuthority> authorities = roles.stream()
            .map(SimpleGrantedAuthority::new)
            .map(GrantedAuthority.class::cast)
            .toList();
        return new UsernamePasswordAuthenticationToken(
            new AuthenticatedUser(userId, roles), jwt.getTokenValue(), authorities);
    }
}
```

`LegacyMarketApplication` 使用显式 `scanBasePackages`，只列出 `api`、`catalog`、`dispute`、`messaging`、`observability`、`order`、`payment`、`review`、`shared`、`storage`、`warranty`、`security`；禁止扫描 `com.example.campusmarket` 根包。所有 legacy 测试的启动类引用同步改为 `LegacyMarketApplication`。

使用 `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`、`issuer-uri` 和 audience validator。保留原 401/403 UTF-8 JSON；删除 HMAC 验证和身份服务 Bean。

- [ ] **Step 5: 重建 market 初始迁移而不保留跨库外键**

`V1__identity_catalog.sql` 重命名为 `V1__catalog.sql` 并删除身份三表及 `fk_listing_seller`。从列出文件中只删除引用 `campus_user` 的外键子句，保留用户 ID 列、非空、索引、业务 CHECK 与其他领域内外键。由于 8.1 只支持全新库，不新增伪装成在线迁移的 `DROP FOREIGN KEY` 补丁。

用户于 2026-09-13 确认的边界例外：允许在 `market_db` 新增最小、每个卖家唯一且持久的锁表，替代迁出身份表后的卖家互斥。提现及改变可提现结算事实的事务使用同一卖家锁，明确固定锁序，并以真实 MySQL 测试验证零/多结算及结算与提现交错；不得引入余额投影、账户状态机或跨身份库协作。

- [ ] **Step 6: 将所有 legacy 测试 Token 改为测试夹具**

生产代码不包含签发器。测试通过 test-scope `platform-test-support` 的 `TestJwtFactory` 签发用户/管理员 Token；测试 JWKS HTTP stub 只返回 `TestJwtFactory.publicJwk("test-key-1")`。

- [ ] **Step 7: 运行安全、schema 和原业务快速回归**

Run:

```powershell
.\mvnw.cmd -pl legacy-market-service -Dtest=ResourceServerSecurityIT,ApplicationIsolationTest test
.\mvnw.cmd -pl legacy-market-service -Dit.test=MarketSchemaBoundaryIT,InventoryIT,PaymentFlowIT verify
.\mvnw.cmd -pl legacy-market-service test
```

Expected: 所有命令通过，0 skipped；legacy 单元测试数不低于身份测试迁出后的准确基线记录。

- [ ] **Step 8: 提交兼容单体边界**

```powershell
git add -- 'labs/08-spring-cloud-split/legacy-market-service'
git commit -m "feat(cloud): secure legacy service with identity JWKS"
```

---

### Task 7: 建立 Eureka 服务发现

**Files:**
- Modify: `labs/08-spring-cloud-split/discovery-server/pom.xml`
- Create: `discovery-server/src/main/java/com/example/campusmarket/discovery/DiscoveryServerApplication.java`
- Create: `discovery-server/src/main/resources/application.yml`
- Create: `discovery-server/src/test/java/com/example/campusmarket/discovery/DiscoveryServerTest.java`
- Modify: `identity-service/pom.xml`, `identity-service/src/main/resources/application.yml`
- Modify: `legacy-market-service/pom.xml`, `legacy-market-service/src/main/resources/application.yml`

**Interfaces:**
- Produces: Eureka endpoint `http://localhost:8761/eureka/`；服务 ID 固定 `identity-service`、`legacy-market-service`。
- Consumed by: Gateway 和平台旅程。

- [ ] **Step 1: 写失败的发现中心配置测试**

```java
@Test
void serverDoesNotRegisterWithItself() {
    assertThat(environment.getProperty("eureka.client.register-with-eureka", Boolean.class)).isFalse();
    assertThat(environment.getProperty("eureka.client.fetch-registry", Boolean.class)).isFalse();
}
```

另用 `ApplicationContextRunner` 断言两个业务应用的 `spring.application.name` 精确匹配固定服务 ID。

- [ ] **Step 2: 运行并确认 Eureka 尚不存在**

Run: `.\mvnw.cmd -pl discovery-server,identity-service,legacy-market-service test`

Expected: FAIL，原因是 Eureka Server/Client Bean 或固定服务名不存在。

- [ ] **Step 3: 实现最小 Eureka Server 与客户端注册**

```java
@SpringBootApplication
@EnableEurekaServer
public class DiscoveryServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(DiscoveryServerApplication.class, args);
    }
}
```

Server 固定端口 8761，本地单节点关闭自注册和拉取；业务服务通过 `EUREKA_DEFAULT_ZONE` 配置地址，不在 Java 代码写死端口。只暴露 health/info。

- [ ] **Step 4: 运行测试并提交**

Run: `.\mvnw.cmd -pl discovery-server,identity-service,legacy-market-service test`

```powershell
git add -- 'labs/08-spring-cloud-split/discovery-server' 'labs/08-spring-cloud-split/identity-service' 'labs/08-spring-cloud-split/legacy-market-service'
git commit -m "feat(cloud): add Eureka service discovery"
```

---

### Task 8: 建立显式、安全的 API Gateway

**Files:**
- Modify: `labs/08-spring-cloud-split/api-gateway/pom.xml`
- Create: `api-gateway/src/main/java/com/example/campusmarket/gateway/ApiGatewayApplication.java`
- Create: `api-gateway/src/main/java/com/example/campusmarket/gateway/security/GatewaySecurityConfiguration.java`
- Create: `api-gateway/src/main/java/com/example/campusmarket/gateway/filter/TrustedHeaderFilter.java`
- Create: `api-gateway/src/main/java/com/example/campusmarket/gateway/error/GatewayErrorWriter.java`
- Create: `api-gateway/src/main/java/com/example/campusmarket/gateway/observability/GatewayMetrics.java`
- Create: `api-gateway/src/main/resources/application.yml`
- Create: `api-gateway/src/test/java/com/example/campusmarket/gateway/ExplicitRoutesTest.java`
- Create: `api-gateway/src/test/java/com/example/campusmarket/gateway/TrustedHeaderFilterTest.java`
- Create: `api-gateway/src/test/java/com/example/campusmarket/gateway/GatewaySecurityTest.java`

**Interfaces:**
- Consumes: Eureka 服务 ID、身份 JWKS 和固定 JWT 契约。
- Produces: `/api/auth/**` → identity；其余 `/api/**` → legacy；稳定的 401/403/503 `ApiError`。

- [ ] **Step 1: 写失败的路由与 Header 测试**

```java
@Test
void exposesOnlyTwoExplicitServiceRoutes() {
    assertThat(routes()).extracting(RouteDefinition::getId)
        .containsExactlyInAnyOrder("identity-api", "legacy-api");
    assertThat(discoveryLocatorEnabled()).isFalse();
}

@Test
void removesSpoofableHeadersAndCreatesNewCorrelationId() {
    MockServerHttpRequest request = MockServerHttpRequest.get("/api/listings")
        .header("X-User-Id", "attacker")
        .header("X-Internal-Role", "ROLE_ADMIN")
        .header("X-Correlation-Id", "chosen-by-client").build();
    ServerHttpRequest filtered = filter(request);
    assertThat(filtered.getHeaders()).doesNotContainKeys("X-User-Id", "X-Internal-Role");
    assertThat(filtered.getHeaders().getFirst("X-Correlation-Id"))
        .isNotEqualTo("chosen-by-client").matches("[0-9a-f-]{36}");
}
```

- [ ] **Step 2: 写失败的边界认证测试**

使用 `WebTestClient` 验证匿名业务请求 401、有效普通用户访问管理员路径 403、匿名 auth/JWKS 路径可转发、错误响应为中文 UTF-8。

- [ ] **Step 3: 运行并确认 Gateway 尚未实现**

Run: `.\mvnw.cmd -pl api-gateway test`

Expected: FAIL，原因是路由、过滤器和安全配置不存在。

- [ ] **Step 4: 实现显式路由和 WebFlux Resource Server**

`application.yml` 的核心必须为：

```yaml
spring:
  cloud:
    discovery:
      enabled: true
    gateway:
      discovery:
        locator:
          enabled: false
      server:
        webflux:
          routes:
            - id: identity-api
              uri: lb://identity-service
              predicates: [ Path=/api/auth/** ]
            - id: legacy-api
              uri: lb://legacy-market-service
              predicates: [ Path=/api/** ]
```

确保更具体的 identity 路由先匹配。Resource Server 精确校验 issuer/audience/roles；匿名白名单只含身份三个接口、JWKS、OPTIONS 和最小 health。

- [ ] **Step 5: 实现安全 503 和低基数指标**

`GatewayErrorWriter.write(ServerWebExchange, HttpStatus, String code, String message)` 直接写 UTF-8 bytes；异常体只含 `code`、固定中文 `message`、随机 correlation ID。指标标签只允许固定 route ID 和结果枚举，拒绝 URL、用户、Token、实例或异常文本。

- [ ] **Step 6: 运行 Gateway 测试并提交**

Run: `.\mvnw.cmd -pl api-gateway test`

```powershell
git add -- 'labs/08-spring-cloud-split/api-gateway'
git commit -m "feat(gateway): add explicit authenticated service routes"
```

---

### Task 9: 打通四进程真实 HTTP 旅程

**Files:**
- Create: `api-gateway/src/test/java/com/example/campusmarket/gateway/support/CloudApplicationCluster.java`
- Create: `api-gateway/src/test/java/com/example/campusmarket/gateway/CloudJourneyIT.java`
- Modify: `identity-service/src/main/java/com/example/campusmarket/identity/infrastructure/LocalVerificationMailSender.java`
- Modify: `legacy-market-service/src/test/java/com/example/campusmarket/integration/JourneyDependencyProperties.java`

**Interfaces:**
- Produces: Gateway 集成测试专用集群句柄 `CloudApplicationCluster.start()`、`gatewayBaseUri()`、`stopIdentity()`、`stopLegacy()`、`stopDiscovery()` 和 `registryApplications()`；Gateway 模块以 test scope 依赖其他三个应用 jar，不反向依赖测试支持模块。
- Proves: Gateway → Eureka → identity/legacy 的真实网络路径。

- [ ] **Step 1: 写失败的端到端旅程**

```java
@Test
void registersLogsInAndCreatesListingThroughGateway() throws Exception {
    HttpResponse<String> issued = post("/api/auth/email-verifications", verificationJson(EMAIL));
    HttpAssertions.assertStatusAndJsonUtf8(issued, 200);
    String code = cluster.latestVerificationCode(EMAIL);

    HttpAssertions.assertStatusAndJsonUtf8(
        post("/api/auth/register", registerJson(EMAIL, PASSWORD, code)), 201);
    HttpResponse<String> login = post("/api/auth/login", loginJson(EMAIL, PASSWORD));
    String token = json(login.body()).get("accessToken").asText();

    HttpResponse<String> listing = postBearer("/api/listings", token,
        "{\"title\":\"Java 并发编程\",\"description\":\"九成新\","
            + "\"category\":\"教材\",\"unitPriceFen\":5600,\"quantity\":1}");
    HttpAssertions.assertStatusAndJsonUtf8(listing, 201);
    assertThat(cluster.registryApplications())
        .contains("IDENTITY-SERVICE", "LEGACY-MARKET-SERVICE", "API-GATEWAY");
}
```

- [ ] **Step 2: 运行并确认多进程夹具尚不存在**

Run: `.\mvnw.cmd -pl api-gateway -Dit.test=CloudJourneyIT verify`

Expected: FAIL，原因是 `CloudApplicationCluster` 或真实路由尚未启动。

- [ ] **Step 3: 实现独立应用上下文集群夹具**

使用 `SpringApplicationBuilder` 以随机端口依次启动 discovery、identity、legacy、Gateway，每个上下文有独立 `spring.application.name`、DataSource 和 WebServer。夹具不得把四个应用合并进同一个 component scan；不得用 MockWebServer 替代 Eureka 或业务服务。

验证码只通过 test-profile `LocalVerificationMailSender` 的受控测试端点/夹具读取，不写日志、不暴露在 Gateway 公开路由。

- [ ] **Step 4: 增加路由真实性和 Header 伪造断言**

旅程读取 Eureka 注册表确认实际随机端口，并断言 Gateway 配置文件不含这些端口。带伪造 `X-User-Id`/`X-Internal-Role` 的请求仍以 Token 的 `sub` 和 `roles` 执行。

- [ ] **Step 5: 运行旅程并提交**

Run: `.\mvnw.cmd -pl api-gateway -Dit.test=CloudJourneyIT verify`

Expected: PASS，0 skipped；全部客户端调用只使用 Gateway URI。

```powershell
git add -- 'labs/08-spring-cloud-split/api-gateway' 'labs/08-spring-cloud-split/identity-service' 'labs/08-spring-cloud-split/legacy-market-service'
git commit -m "test(cloud): prove gateway identity journey"
```

---

### Task 10: 验证 JWKS 缓存、服务中断和恢复

**Files:**
- Create: `api-gateway/src/test/java/com/example/campusmarket/gateway/CloudFailureRecoveryIT.java`
- Create: `api-gateway/src/main/java/com/example/campusmarket/gateway/security/JwtFailureMetrics.java`
- Modify: `api-gateway/src/main/java/com/example/campusmarket/gateway/security/GatewaySecurityConfiguration.java`
- Modify: `legacy-market-service/src/main/java/com/example/campusmarket/security/ResourceServerConfiguration.java`
- Modify: `api-gateway/src/test/java/com/example/campusmarket/gateway/support/CloudApplicationCluster.java`

**Interfaces:**
- Produces: 有界恢复测试；固定指标 `campus.gateway.jwt{result=valid|invalid|unknown_kid}`、`campus.gateway.route{route,result}`。
- Proves: 身份停机不撤销已缓存密钥的 Token，冷启动不绕过验签，目标恢复无需改配置。

- [ ] **Step 1: 写失败的身份服务中断测试**

```java
@Test
void cachedTokenStillReachesLegacyWhileIdentityIsDown() throws Exception {
    String token = registerAndLoginThroughGateway();
    assertThat(getBearer("/api/listings/mine", token).statusCode()).isEqualTo(200);
    cluster.stopIdentity();

    HttpAssertions.assertStatusAndJsonUtf8(post("/api/auth/login", loginJson()), 503);
    assertThat(getBearer("/api/listings/mine", token).statusCode()).isEqualTo(200);
}
```

- [ ] **Step 2: 写失败的 legacy、Eureka 和冷启动测试**

- 停止 legacy 后业务请求为 503，响应不含 `localhost`、端口、服务 ID、堆栈；
- 恢复 legacy 后 Awaitility 在 30 秒内观察到 200；
- 停止 Eureka 后已缓存注册信息在测试规定窗口内可用；
- 在 Eureka/JWKS 都不可达时新建 Gateway/legacy 上下文，readiness 为 DOWN，受保护 API 绝不成功；
- 未知 `kid` 只触发一次刷新并返回 401。

- [ ] **Step 3: 运行并确认当前错误/缓存行为不完整**

Run: `.\mvnw.cmd -pl api-gateway -Dit.test=CloudFailureRecoveryIT verify`

Expected: 至少一个 503、缓存或 readiness 断言失败。

- [ ] **Step 4: 实现有界超时、错误映射和失败分类指标**

连接超时固定 1 秒，响应超时固定 3 秒；测试恢复窗口固定 30 秒。JWT 失败分类只解析 Header 的 `kid` 和验证异常类型，不记录原 Token。未知 `kid` 的刷新行为由底层 Nimbus JWKS 缓存验证，外层只允许一次认证尝试，不做递归重试。

- [ ] **Step 5: 运行故障测试和敏感输出扫描**

Run:

```powershell
.\mvnw.cmd -pl api-gateway -Dit.test=CloudFailureRecoveryIT verify
rg -n "BEGIN.*PRIVATE KEY|Bearer ey|password_hash|verification code|localhost:[0-9]{2,5}" api-gateway/target identity-service/target legacy-market-service/target
```

Expected: 故障测试通过；扫描不出现运行时敏感数据或对外错误泄漏。测试私钥资源本身不在扫描目录。

- [ ] **Step 6: 提交故障语义**

```powershell
git add -- 'labs/08-spring-cloud-split/api-gateway' 'labs/08-spring-cloud-split/legacy-market-service' 'labs/08-spring-cloud-split/platform-test-support'
git commit -m "test(cloud): verify discovery and identity failure recovery"
```

---

### Task 11: 恢复实验七全部业务验证

**Files:**
- Modify: `legacy-market-service/src/test/java/com/example/campusmarket/integration/**`
- Modify: `legacy-market-service/src/test/java/com/example/campusmarket/unit/**`
- Modify: `legacy-market-service/pom.xml`
- Create: `legacy-market-service/src/test/java/com/example/campusmarket/integration/ExperimentSevenCoverageTest.java`
- Create: `legacy-market-service/src/test/resources/experiment-seven-required-tests.txt`

**Interfaces:**
- Consumes: `TestJwtFactory`、market_db、原实验七全部容器夹具。
- Produces: 不低于迁移前的业务行为覆盖；身份流程测试由 identity 模块承接，不在 legacy 重复。

- [ ] **Step 1: 写失败的测试清单守卫**

`ExperimentSevenCoverageTest` 读取固定资源 `experiment-seven-required-tests.txt`，逐项检查以下集成套件仍可由 Failsafe 发现：

```text
InventoryIT
ConcurrentOrderIT
PaymentFlowIT
PaymentGatewayContractIT
HandoffHttpIT
PartialReturnRefundIT
DisputeEvidenceIT
WarrantyHttpAclIT
WarrantyMessagingIT
ReliableMessagingIT
ProductSearchIT
SearchRebuildIT
StorageCleanupIT
CampusMarketJourneyIT
RecoveryDrillIT
RecoveryInvariantStagesIT
```

身份 `AuthFlowIT` 必须存在于 identity 模块；不能通过复制空类满足清单。

- [ ] **Step 2: 运行清单守卫并确认资源尚不存在**

Run: `.\mvnw.cmd -pl legacy-market-service -Dtest=ExperimentSevenCoverageTest test`

Expected: FAIL，原因是 `experiment-seven-required-tests.txt` 尚不存在，而不是测试框架错误。

- [ ] **Step 3: 写入固定测试清单并完成剩余夹具对账**

将上方 16 个类名逐行写入 `experiment-seven-required-tests.txt`。复核所有测试已统一使用 `TestJwtFactory` 和 JWKS stub，且没有仅为满足已删除身份外键而创建 `campus_user` 的夹具 SQL；保留真实 MySQL、Redis、RabbitMQ、Elasticsearch、MinIO、Toxiproxy 协作。任何原 IT 不得改名为非 `*IT`、添加 `@Disabled`、assumption 或 Docker 不可用时的成功分支。

- [ ] **Step 4: 运行完整 legacy 验证**

Run:

```powershell
.\mvnw.cmd -pl legacy-market-service test
.\mvnw.cmd -pl legacy-market-service verify
```

Expected: 0 failures、0 errors、0 skipped；业务 Surefire/Failsafe 数量与 Task 2 记录的实验七清单逐项对账，差额仅允许是已迁至 identity 模块的身份测试，并在执行记录列明。

- [ ] **Step 5: 提交回归迁移**

```powershell
git add -- 'labs/08-spring-cloud-split/legacy-market-service'
git commit -m "test(cloud): preserve campus market regression suite"
```

---

### Task 12: 补齐运行、排障、学习与验收文档

**Files:**
- Modify: `labs/08-spring-cloud-split/README.md`
- Modify: `labs/08-spring-cloud-split/TROUBLESHOOTING.md`
- Create: `labs/08-spring-cloud-split/docs/architecture.md`
- Create: `labs/08-spring-cloud-split/docs/migration-boundary.md`
- Create: `labs/08-spring-cloud-split/notes/learning-log.md`
- Create: `labs/08-spring-cloud-split/interview/question-bank.md`
- Modify: `labs/08-spring-cloud-split/compose.yaml`
- Modify: `labs/08-spring-cloud-split/.env.example`

**Interfaces:**
- Consumes: 全部实现、测试数、服务端口、镜像版本和真实故障结果。
- Produces: 从零运行、验证、排障和面试复盘闭环。

- [ ] **Step 1: 建立人工文档验收清单**

逐项核对 README 明确包含以下运行与边界信息：

```text
JDK 17
Spring Boot 3.5.16
Spring Cloud 2025.0.3
docker info
mvnw.cmd test
mvnw.cmd verify
identity_db
market_db
RS256
JWKS
Eureka
不支持生产不停机迁移
```

同时人工检查 `.env.example` 只有占位符，不含测试/本地真实密码或 PEM。面向人的文档不添加源码关键词匹配测试；缺失项直接记录在本任务报告中并在下一步补齐。

- [ ] **Step 2: 编写最终文档和最小 Compose**

README 给出四应用启动顺序、端口、环境变量、Gateway 示例和验收命令。`architecture.md` 记录路由与信任边界；`migration-boundary.md` 明确只支持全新环境，不能冒充线上迁移；TROUBLESHOOTING 记录 Eureka、JWKS、双库权限、Docker 内存和依赖故障判定。

Compose 只声明实验明确依赖，所有服务 `restart: "no"`，不修改 Windows 服务启动类型或 Docker 全局重启策略。`.env.example` 只用 `<replace-me>` 一类占位符。

- [ ] **Step 3: 完成人工文档复核**

按 Step 1 的固定清单逐项阅读 README、架构说明、迁移边界、排障记录、学习日志、面试题库、Compose 和 `.env.example`。在任务报告中记录每项对应文件与章节；发现缺失或矛盾时直接修正文档后重新复核。

- [ ] **Step 4: 完成实验级全量验收**

Run:

```powershell
docker info
.\mvnw.cmd test
.\mvnw.cmd verify
git diff --check
```

Expected: Reactor 所有模块 BUILD SUCCESS；Surefire/Failsafe 0 failures、0 errors、0 skipped；外部协作测试实际运行。

- [ ] **Step 5: 汇总 XML 测试数并检查泄漏/边界**

Run:

```powershell
$reports = Get-ChildItem -Recurse -Filter 'TEST-*.xml' | ForEach-Object { [xml](Get-Content -LiteralPath $_.FullName -Raw) }
$reports.testsuite | Measure-Object -Property tests,failures,errors,skipped -Sum
rg -n "BEGIN.*PRIVATE KEY|Bearer ey|local-only-jwt-secret|campus_market_local" .
git ls-files | Select-String -NotMatch '^(\.gitignore|labs/08-spring-cloud-split/)'
```

Expected: 测试报告数非零；无真实秘密命中；活动树无越界路径。

- [ ] **Step 6: 提交文档与验收证据**

```powershell
git add -- 'labs/08-spring-cloud-split'
git commit -m "docs(cloud): document experiment eight milestone"
git status --short --branch
```

Expected: 工作树干净，分支为 `learning/spring-cloud-split`。

---

## Final Acceptance Gate

完成 Task 12 后再次核对：

- [ ] `docker info` 明确成功；
- [ ] Reactor `test` 和 `verify` 均成功且没有 skipped；
- [ ] 真实 HTTP 旅程只访问 Gateway；
- [ ] 两个 MySQL 账号的跨库访问被真实数据库拒绝；
- [ ] 身份、legacy、Eureka 的停机与恢复语义符合规格；
- [ ] 实验七业务回归没有被删除、Mock 化或静默跳过；
- [ ] `git diff --check` 无输出；
- [ ] `git status --short` 无输出；
- [ ] 分支活动树只含 `.gitignore` 和 `labs/08-spring-cloud-split/**`。
