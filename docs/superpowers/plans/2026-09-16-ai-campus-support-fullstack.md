# 实验九全栈智能校园客服实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在独立实验分支交付可从浏览器复跑的规则问答、本人的订单/售后只读查询和完整故障验收。

**Architecture:** 从实验八验收提交建立单实验基线，交易服务负责按 JWT 用户 ID 过滤事实，AI 服务负责公开规则检索与受控模型解释，前端通过同源代理访问 Gateway。私人状态确定性呈现，模型仅接收公开规则与脱敏的通用询问。

**Tech Stack:** JDK 17、Spring Boot 3.5.16、Spring Cloud 2025.0.3、Spring AI 1.1.8、MySQL 8.4、Elasticsearch 8.18.8、React 19.3、TypeScript、Vite 8.3.0、JUnit 5、Testcontainers、Playwright Chromium。

## Global Constraints

- 设计来源：`docs/superpowers/specs/2026-09-16-ai-campus-support-design.md`；任何范围变更先改设计与本计划。
- `main` 只存放中心文档；`learning/ai-campus-support` 活动树只允许根 `.gitignore` 和 `labs/09-ai-campus-support/**`；`AGENTS.md` 保持忽略。
- JDK 17、Maven Wrapper、Spring Boot `3.5.16`、Spring Cloud `2025.0.3`、Spring AI `1.1.8`；React `19.3`、Vite `8.3.0`、Node `20.19+` 或 `22.12+`；npm lockfile 锁版本。
- HTTP JSON 显式 `application/json; charset=UTF-8`，中文真实 HTTP 测试核对正文与 Content-Type。
- 领域对象不依赖 Spring/数据库/模型 SDK；服务层不清洗协议数据；反序列化拒绝未知字段与非法值。
- 私人资源只由交易服务按 JWT `user_id` 授权；他人与不存在单项同构 404，故障不得伪装 404；管理员不自动绕过。
- AI 服务不连接交易/身份/商品数据库，没有写交易工具；模型不接收身份、UUID、证据、支付或私人自由文本。
- 密钥只从环境读取；测试用本地受控模型替身；不记录问题全文、提示词、Token、私人状态或模型正文。
- 测试先 RED 再 GREEN；Testcontainers 前运行 `docker info`，Docker 不可用先手动启动 Docker Desktop；skipped 不算完整验收。
- Spring Boot 3.5.x 与 Spring Cloud 2025.0.x 已结束开源维护；本计划证明固定基线可复跑，不宣称适合生产持续补丁维护。将来如需生产版本，单独规划 Boot 4.x、匹配 Cloud 发布列车和 Spring AI 2.x 的完整回归迁移。
- 每个提交只暂存该任务相关文件，保留其他工作树和本地改动；完成后执行 `git diff --check`。

## 文件职责图

| 路径 | 职责 |
|---|---|
| `labs/09-ai-campus-support/legacy-market-service/src/main/java/com/example/campusmarket/support/` | 本人订单/售后只读投影、授权 SQL、有界列表与游标 |
| `labs/09-ai-campus-support/ai-support-service/src/main/java/com/example/campusmarket/supportai/` | 严格 HTTP 边界、用例、模型端口、交易只读客户端、规则索引 |
| `labs/09-ai-campus-support/policies/` | 经审定的公开规则正文与版本清单 |
| `labs/09-ai-campus-support/support-web/src/` | 登录与注册、本人资源选择、问答、状态与来源展示 |
| `labs/09-ai-campus-support/support-web/e2e/` | 实际构建前端通过同源代理访问 Gateway 的浏览器旅程 |
| `labs/09-ai-campus-support/compose.yaml` | 实验九独立基础设施、邮件演示、AI 服务与静态前端 |

---

### Task 1: 建立隔离基线并守住单实验活动树

**Files:**
- Create: `.worktrees/ai-campus-support`（工作树，已被根 `.gitignore` 忽略）
- Move within new branch: `labs/08-spring-cloud-split/**` → `labs/09-ai-campus-support/**`
- Modify: `labs/09-ai-campus-support/pom.xml`, `labs/09-ai-campus-support/README.md`, `labs/09-ai-campus-support/compose.yaml`, affected Docker/Compose paths
- Test: `labs/09-ai-campus-support/platform-test-support/src/test/java/com/example/campusmarket/support/SingleExperimentTreeTest.java`

**Interfaces:**
- Consumes: 实验八验收提交 `177cd25`。
- Produces: 六模块 Maven 基线位于 `labs/09-ai-campus-support/`；Task 2-8 只在该目录修改。

- [ ] **Step 1: 建立工作树前检查分支与忽略规则**

```powershell
git worktree list --porcelain
git check-ignore .worktrees
git status --short --branch
git worktree add .worktrees/ai-campus-support -b learning/ai-campus-support 177cd25
```

- [ ] **Step 2: 写失败的活动树守卫测试**

```java
@Test void branchHasOnlyOneExperimentDirectory() throws Exception {
    var rootCommand = new ProcessBuilder("git", "rev-parse", "--show-toplevel").start();
    var repoRoot = Path.of(new String(rootCommand.getInputStream().readAllBytes(), UTF_8).trim());
    var tracked = new ProcessBuilder("git", "ls-files").directory(repoRoot.toFile()).start();
    var files = new String(tracked.getInputStream().readAllBytes(), UTF_8).lines().toList();
    assertThat(files).allMatch(path -> path.equals(".gitignore") || path.startsWith("labs/09-ai-campus-support/"));
}
```

Run: `git -C .worktrees/ai-campus-support ls-files | rg '^labs/08-'`；Expected: 旧目录仍在，守卫 RED。

- [ ] **Step 3: 迁移目录并调整硬编码的实验路径**

```powershell
git mv labs/08-spring-cloud-split labs/09-ai-campus-support
rg -n 'labs/08-spring-cloud-split|08-spring-cloud-split' labs/09-ai-campus-support
```

对命中的 Docker、Compose、测试启动路径逐个改为 `labs/09-ai-campus-support`；保留应用 API 与交易行为。Root POM 描述改为 `Campus AI support learning lab`。Run: `./mvnw.cmd test` from `labs/09-ai-campus-support`；Expected: 六模块 BUILD SUCCESS、0 skipped。

- [ ] **Step 4: 提交隔离基线**

```powershell
git add -- .gitignore labs/09-ai-campus-support
git diff --cached --check
git commit -m "chore(ai): isolate experiment nine baseline"
```

### Task 2: 交易服务提供本人资源列表与单项摘要

**Files:**
- Create: `labs/09-ai-campus-support/legacy-market-service/src/main/java/com/example/campusmarket/support/SupportStatusController.java`
- Create: `labs/09-ai-campus-support/legacy-market-service/src/main/java/com/example/campusmarket/support/SupportStatusService.java`
- Create: `labs/09-ai-campus-support/legacy-market-service/src/main/java/com/example/campusmarket/support/JdbcSupportStatusRepository.java`
- Create: `labs/09-ai-campus-support/legacy-market-service/src/main/java/com/example/campusmarket/support/SupportCursor.java`
- Test: `labs/09-ai-campus-support/legacy-market-service/src/test/java/com/example/campusmarket/integration/SupportStatusAclIT.java`

**Interfaces:**
- Consumes: `AuthenticatedUser.userId()`、`market_db` 的 `trade_order`、`dispute_case`、`warranty_case`。
- Produces: `GET /api/support/orders|disputes|warranties?limit=10&cursor=...` 与 `GET /api/support/{type}/{id}`；`type` 只允许三个复数常量。单项 `StatusView(UUID id,String type,String status,Instant createdAt,Instant deadline)`，列表 `StatusPage(List<StatusView> items,String nextCursor)`；AI 服务只读单项。

- [ ] **Step 1: 写真实 MySQL/HTTP 失败测试，先证明越权边界**

```java
@Test void ownOrderVisibleAndOthersIndistinguishableFromMissing() {
    var own = get("/api/support/orders/" + ownOrderId, buyerJwt);
    var other = get("/api/support/orders/" + ownOrderId, strangerJwt);
    var missing = get("/api/support/orders/" + UUID.randomUUID(), strangerJwt);
    assertThat(own.statusCode()).isEqualTo(200);
    assertThat(own.header("Content-Type")).isEqualTo("application/json; charset=UTF-8");
    assertThat(other.statusCode()).isEqualTo(404);
    assertThat(other.body()).isEqualTo(missing.body());
}
```

Run: `./mvnw.cmd -pl legacy-market-service -am verify -Dit.test=SupportStatusAclIT`；Expected: 404 for own order, RED。

- [ ] **Step 2: 实现有界 SQL 和授权查询，禁止查后在 AI 服务过滤**

```java
public StatusView order(UUID id, UUID userId) {
    return jdbc.query("SELECT id,status,created_at,payment_deadline FROM trade_order "
        + "WHERE id=? AND (buyer_id=? OR seller_id=?)",
        rs -> rs.next() ? mapOrder(rs) : null, id.toString(), userId.toString(), userId.toString());
}
public List<StatusView> orders(UUID userId, Instant before, UUID beforeId, int limit) {
    return jdbc.query("SELECT id,status,created_at,payment_deadline FROM trade_order "
        + "WHERE (buyer_id=? OR seller_id=?) AND (created_at<? OR (created_at=? AND id<?)) "
        + "ORDER BY created_at DESC,id DESC LIMIT ?", mapper,
        userId.toString(), userId.toString(), before, before, beforeId.toString(), limit + 1);
}
```

按同一 SQL 授权规则补 `dispute_case JOIN trade_order` 与 `warranty_case`；`SupportCursor` 用 HMAC 绑定 user/type/createdAt/id，大小上限 512 字节，默认 10/最大 20。Controller 由认证主体取得 user ID，单项 null 返回同一 `ApiErrors.bytes(404, "资源不存在")`，JSON 显式 UTF-8。

- [ ] **Step 3: 运行本人/他人/管理员/分页/故障测试并提交**

Run: `./mvnw.cmd -pl legacy-market-service -am verify -Dit.test=SupportStatusAclIT`；Expected: 200/404/401、中文 UTF-8、稳定分页、0 skipped。

```powershell
git add -- labs/09-ai-campus-support/legacy-market-service/src/main/java/com/example/campusmarket/support labs/09-ai-campus-support/legacy-market-service/src/test/java/com/example/campusmarket/integration/SupportStatusAclIT.java
git diff --cached --check
git commit -m "feat(ai): expose authorized trade status summaries"
```

### Task 3: 新增 AI 服务与精确 Gateway 路由

**Files:**
- Modify: `labs/09-ai-campus-support/pom.xml`, `labs/09-ai-campus-support/api-gateway/src/main/resources/application.yml`, `labs/09-ai-campus-support/api-gateway/src/main/java/com/example/campusmarket/gateway/security/GatewaySecurityConfiguration.java`
- Create: `labs/09-ai-campus-support/ai-support-service/pom.xml`
- Create: `labs/09-ai-campus-support/ai-support-service/src/main/java/com/example/campusmarket/supportai/AiSupportApplication.java`
- Create: `labs/09-ai-campus-support/ai-support-service/src/main/java/com/example/campusmarket/supportai/api/SupportAnswerController.java`
- Create: `labs/09-ai-campus-support/ai-support-service/src/main/java/com/example/campusmarket/supportai/api/AnswerRequest.java`
- Create: `labs/09-ai-campus-support/ai-support-service/src/main/java/com/example/campusmarket/supportai/security/SupportSecurityConfiguration.java`
- Test: `labs/09-ai-campus-support/api-gateway/src/test/java/com/example/campusmarket/gateway/SupportRouteIT.java`
- Test: `labs/09-ai-campus-support/ai-support-service/src/test/java/com/example/campusmarket/supportai/api/StrictAnswerRequestTest.java`

**Interfaces:**
- Consumes: Gateway RS256/JWKS 契约与 `POST /api/ai/support/answers` 请求。
- Produces: Eureka 名称 `ai-support-service`、精确 POST 路由；`AnswerRequest(String question,UUID orderId,UUID caseId,CaseType caseType)`，`CaseType={DISPUTE,WARRANTY}`；该 record 提供 `hasPrivateResource(): boolean`、`resourceId(): UUID`、`type(): String`。未知字段和超 2 KiB UTF-8 问题返回 400。

- [ ] **Step 1: 先写路由与严格协议失败测试**

```java
@Test void rejectsConflictingResourcesAndUnknownField() {
    assertThat(post("{\"question\":\"我的订单\",\"orderId\":\"" + orderId
        + "\",\"caseId\":\"" + caseId + "\",\"caseType\":\"WARRANTY\"}").status()).isEqualTo(400);
    assertThat(post("{\"question\":\"退款规则\",\"extra\":1}").status()).isEqualTo(400);
}
```

Run: `./mvnw.cmd -pl ai-support-service,api-gateway -am test`；Expected: AI 模块/路由尚不存在，RED。

- [ ] **Step 2: 加模块、JWT 自验和精确路由**

```yaml
- id: ai-support-answer
  uri: lb://ai-support-service
  predicates:
    - Path=/api/ai/support/answers
    - Method=POST
```

Gateway Security 在通用 `/api/**` 前仅允许 `POST /api/ai/support/answers` 公开访问；携带无效 Bearer 仍 401。AI 服务基于现有 `NimbusJwtDecoder` 和相同 issuer/audience/RS256 契约自验；私人字段缺身份直接 401。构造器检查互斥字段、caseType、非空问题与 UTF-8 字节长度。

- [ ] **Step 3: 执行路由/安全测试并提交**

Run: `./mvnw.cmd -pl ai-support-service,api-gateway -am verify -Dit.test=SupportRouteIT`；Expected: 精确 POST 通过，其他方法不被路由，0 skipped。

```powershell
git add -- labs/09-ai-campus-support/pom.xml labs/09-ai-campus-support/ai-support-service labs/09-ai-campus-support/api-gateway
git diff --cached --check
git commit -m "feat(ai): add isolated support service and gateway route"
```

### Task 4: 版本化公开规则与独立向量索引

**Files:**
- Create: `labs/09-ai-campus-support/policies/manifest.json`, `labs/09-ai-campus-support/policies/trade-policy-v1.md`
- Create: `labs/09-ai-campus-support/ai-support-service/src/main/java/com/example/campusmarket/supportai/policy/PolicyChunk.java`
- Create: `labs/09-ai-campus-support/ai-support-service/src/main/java/com/example/campusmarket/supportai/policy/PolicyCorpus.java`
- Create: `labs/09-ai-campus-support/ai-support-service/src/main/java/com/example/campusmarket/supportai/policy/PolicyRetriever.java`
- Create: `labs/09-ai-campus-support/ai-support-service/src/main/java/com/example/campusmarket/supportai/policy/ElasticsearchPolicyIndex.java`
- Test: `labs/09-ai-campus-support/ai-support-service/src/test/java/com/example/campusmarket/supportai/policy/PolicyCorpusTest.java`
- Test: `labs/09-ai-campus-support/ai-support-service/src/test/java/com/example/campusmarket/supportai/policy/PolicyIndexIT.java`

**Interfaces:**
- Consumes: 人工审定 Markdown、外部嵌入模型端口与独立 Elasticsearch 客户端。
- Produces: `PolicyRetriever.find(String question): List<PolicyChunk>`；`PolicyChunk(String sourceId,String title,String version,String text,double score)`；不合格/旧版本片段不进入模型上下文。

- [ ] **Step 1: 写版本、阈值与索引恢复失败测试**

```java
@Test void mismatchedIndexVersionIsUnavailable() {
    index.rebuild(corpusV1);
    assertThat(index.find("退款", corpusV2.version())).isEmpty();
    assertThat(index.readiness()).isEqualTo(Readiness.DOWN);
}
```

Run: `./mvnw.cmd -pl ai-support-service -am verify -Dit.test=PolicyIndexIT`；Expected: 新索引不存在，RED。

- [ ] **Step 2: 实现公开语料切分、索引别名和门槛**

```java
public List<PolicyChunk> find(String question) {
    if (!index.aliasVersion().equals(corpus.version())) throw new PolicyUnavailableException();
    return index.similaritySearch(question, 5).stream()
        .filter(chunk -> chunk.version().equals(corpus.version()) && chunk.score() >= 0.70)
        .toList();
}
```

`manifest.json` 逐项列明 sourceId、标题、版本、SHA-256；切分仅在标题/段落边界。只索引公开规则，索引名带版本与随机 generation，新索引经段数/hash 校验才切读别名；失败继续使用已验证旧索引，同时版本不匹配时 readiness DOWN。测试嵌入端点返回固定维度向量，不调用公网。

- [ ] **Step 3: 执行 Testcontainers 索引故障/恢复并提交**

Run: `./mvnw.cmd -pl ai-support-service -am verify -Dit.test=PolicyIndexIT`；Expected: 旧版拒绝、失败不混合、恢复后就绪，0 skipped。

```powershell
git add -- labs/09-ai-campus-support/policies labs/09-ai-campus-support/ai-support-service/src/main/java/com/example/campusmarket/supportai/policy labs/09-ai-campus-support/ai-support-service/src/test/java/com/example/campusmarket/supportai/policy
git diff --cached --check
git commit -m "feat(ai): index reviewed campus policies"
```

### Task 5: 授权状态客户端、受控回答和限流

**Files:**
- Create: `labs/09-ai-campus-support/ai-support-service/src/main/java/com/example/campusmarket/supportai/application/AnswerService.java`
- Create: `labs/09-ai-campus-support/ai-support-service/src/main/java/com/example/campusmarket/supportai/application/TradeStatusReader.java`
- Create: `labs/09-ai-campus-support/ai-support-service/src/main/java/com/example/campusmarket/supportai/application/AnswerModel.java`
- Create: `labs/09-ai-campus-support/ai-support-service/src/main/java/com/example/campusmarket/supportai/application/PrivateQuestionClassifier.java`
- Create: `labs/09-ai-campus-support/ai-support-service/src/main/java/com/example/campusmarket/supportai/infrastructure/HttpTradeStatusReader.java`
- Create: `labs/09-ai-campus-support/ai-support-service/src/main/java/com/example/campusmarket/supportai/infrastructure/SpringAiAnswerModel.java`
- Create: `labs/09-ai-campus-support/ai-support-service/src/main/java/com/example/campusmarket/supportai/infrastructure/SupportRateLimiter.java`
- Modify: `labs/09-ai-campus-support/ai-support-service/src/main/java/com/example/campusmarket/supportai/api/SupportAnswerController.java`, `labs/09-ai-campus-support/ai-support-service/pom.xml`
- Test: `labs/09-ai-campus-support/ai-support-service/src/test/java/com/example/campusmarket/supportai/application/AnswerServiceTest.java`
- Test: `labs/09-ai-campus-support/ai-support-service/src/test/java/com/example/campusmarket/supportai/integration/AnswerHttpIT.java`

**Interfaces:**
- Consumes: `PolicyRetriever.find(question)` 与 Task 2 的单项 status GET；AI 侧端口 `TradeStatusReader.read(type,id,bearerToken): StatusView`、`AnswerModel.explain(template,chunks): String`。
- Produces: `AnswerResponse(String answer,List<SourceView> sources,StatusView status)`；`SourceView(String sourceId,String title,String version)`；AI 模块自行定义只读 `StatusView` DTO，不依赖交易模块内部 Java 类。私人状态不进入模型请求。使用 Spring AI 1.1.8 BOM 和 `spring-ai-starter-model-openai`，base URL/model/API key 可配置。

- [ ] **Step 1: 写不泄漏与故障优先级失败测试**

```java
@Test void privateRequestNeverSendsRawQuestionOrUuidToModel() {
    var response = service.answer(new AnswerRequest("我的订单 " + orderId + " 为什么还没退款", orderId, null, null), buyerToken);
    assertThat(response.status().status()).isEqualTo("PAID");
    assertThat(recordedModelPrompt).doesNotContain(orderId.toString()).doesNotContain("我的订单");
    assertThat(recordedModelPrompt).contains("退款规则");
}
@Test void unauthorizedResourceStopsBeforeRetrievalAndModel() {
    assertThatThrownBy(() -> service.answer(privateRequest, strangerToken)).isInstanceOf(ResourceNotFoundException.class);
    assertThat(modelCalls).isZero();
}
```

Run: `./mvnw.cmd -pl ai-support-service -am test -Dtest=AnswerServiceTest`；Expected: 当前返回空或直接调用模型，RED。

- [ ] **Step 2: 实现固定次序：授权 → 安全分类 → 检索 → 模型 → 服务端来源**

```java
public AnswerResponse answer(AnswerRequest request, String bearer) {
    StatusView status = request.hasPrivateResource()
        ? statusReader.read(request.type(), request.resourceId(), bearer) : null;
    String safeQuestion = status == null ? request.question() : classifier.template(request.question());
    if (safeQuestion == null) return new AnswerResponse("已显示当前状态；规则问题缺少足够依据。", List.of(), status);
    List<PolicyChunk> chunks = retriever.find(safeQuestion);
    if (chunks.isEmpty()) return new AnswerResponse("没有足够的规则依据。", List.of(), status);
    String explanation = model.explain(safeQuestion, chunks);
    return new AnswerResponse(explanation, chunks.stream().map(SourceView::from).toList(), status);
}
```

`HttpTradeStatusReader` 只发 GET 到 `lb://legacy-market-service` 的单项摘要，原始 Bearer 仅在内部请求 Header 中传递，不记录；404 原样业务归类，连接/5xx 归 503。`PrivateQuestionClassifier` 只认白名单规则词（如“退款规则”“试用期限”“质保期限”），其它私人输入不调用模型。`SpringAiAnswerModel` 对上下文加不可信引用边界、禁止工具调用、固定 3 秒超时、并发上限与响应字节上限；禁用提示/响应观测属性。`SupportRateLimiter` 用 Redis 原子计数按匿名 IP 与私人 userId 分桶，每分钟固定上限 20/10，Redis 故障归 503，不放开私人无限调用。

在 Task 3 的 `AnswerRequest` record 中按构造器校验提供接口，避免服务层再次清洗协议字段：

```java
public boolean hasPrivateResource() { return orderId != null || caseId != null; }
public UUID resourceId() { return orderId != null ? orderId : caseId; }
public String type() { return orderId != null ? "orders" : caseType == CaseType.DISPUTE ? "disputes" : "warranties"; }
```

- [ ] **Step 3: 以受控 OpenAI 兼容 HTTP 替身验证正文、供应商故障、429 和 UTF-8**

```java
@Test void chineseAnswerHasExplicitUtf8AndNoPrivateDataAtProvider() {
    var response = postViaGateway("/api/ai/support/answers", privateBody, buyerJwt);
    assertThat(response.header("Content-Type")).isEqualTo("application/json; charset=UTF-8");
    assertThat(response.body()).contains("当前状态");
    assertThat(modelStub.requests()).noneMatch(body -> body.contains(orderId.toString()));
}
```

Run: `./mvnw.cmd -pl ai-support-service -am verify -Dit.test=AnswerHttpIT`；Expected: 200/401/404/429/503 均稳定中文 JSON，0 skipped。

- [ ] **Step 4: 提交回答流程**

```powershell
git add -- labs/09-ai-campus-support/ai-support-service
git diff --cached --check
git commit -m "feat(ai): answer from reviewed rules and authorized status"
```

### Task 6: 前端登录、资源选择与客服界面

**Files:**
- Create: `labs/09-ai-campus-support/support-web/package.json`, `package-lock.json`, `index.html`, `vite.config.ts`, `tsconfig.json`
- Create: `labs/09-ai-campus-support/support-web/src/api/client.ts`, `src/auth/AuthProvider.tsx`, `src/auth/LoginForm.tsx`, `src/auth/RegisterForm.tsx`
- Create: `labs/09-ai-campus-support/support-web/src/support/SupportDesk.tsx`, `src/support/ResourcePicker.tsx`, `src/support/AnswerPanel.tsx`, `src/support/StatusCard.tsx`, `src/support/SourceCards.tsx`
- Create: `labs/09-ai-campus-support/support-web/src/styles/tokens.css`, `src/styles/layout.css`, `src/main.tsx`
- Test: `labs/09-ai-campus-support/support-web/src/auth/AuthProvider.test.tsx`, `src/support/SupportDesk.test.tsx`

**Interfaces:**
- Consumes: 身份服务的邮箱验证码/注册/登录 API、Task 2 三个本人列表、Task 5 `POST /api/ai/support/answers`。
- Produces: 公开规则问答与登录后的资源选择/状态/来源分区；`AuthProvider` 只在 React 内存保留 `accessToken: string | null`。

- [ ] **Step 1: 建前端测试工具并写先失败的登录状态测试**

```tsx
test('登出后不再发送 Bearer，页面刷新不恢复 Token', async () => {
  const auth = createAuthState();
  auth.login('short-test-token');
  expect(auth.authorization()).toBe('Bearer short-test-token');
  auth.logout();
  expect(auth.authorization()).toBeNull();
  expect(localStorage.length).toBe(0);
});
```

Run: `npm test -- src/auth/AuthProvider.test.tsx`；Expected: `createAuthState` 不存在，RED。

- [ ] **Step 2: 实现明确的 API 客户端、内存身份和错误映射**

```ts
export async function askSupport(request: AnswerRequest, token: string | null): Promise<AnswerResponse> {
  const response = await fetch('/api/ai/support/answers', {
    method: 'POST', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(request), cache: 'no-store'
  });
  if (!response.ok) throw mapStatus(response.status);
  return response.json() as Promise<AnswerResponse>;
}
export type AnswerRequest = { question: string; orderId?: string; caseId?: string; caseType?: 'DISPUTE' | 'WARRANTY' };
export type AnswerResponse = { answer: string; sources: { sourceId: string; title: string; version: string }[]; status?: { id: string; type: string; status: string; createdAt: string; deadline?: string } };
```

注册页面按验证码发送、用户填写验证码、注册、登录的顺序调用既有 API；Token 仅在内存 React context。本人列表只用 `/api/support/{type}`，选择后请求携带显式 ID；HTTP 401 清空身份，其它错误显示“无权查看”“资源不存在”“请求太频繁”“服务暂不可用”及重试动作。禁止 `dangerouslySetInnerHTML`；规则来源、模型文本和状态各用普通文本节点。

- [ ] **Step 3: 写状态与来源分区测试后制作桌面/移动界面**

```tsx
test('规则解释不能覆盖确定性状态', () => {
  render(<AnswerPanel result={{ answer: '退款条件以规则为准', status: { type: 'ORDER', status: 'PAID' }, sources: [{ sourceId: 'refund-v1', title: '退款规则', version: 'v1' }] }} />);
  expect(screen.getByRole('heading', { name: '当前状态' })).toBeVisible();
  expect(screen.getByText('PAID')).toBeVisible();
  expect(screen.getByRole('heading', { name: '规则来源' })).toBeVisible();
});
```

先运行上述测试确认 RED，再依 `src/styles/tokens.css` 的颜色/字体/间距令牌制作服务台布局。主区问答、侧区来源与状态时间线；小屏单列。输入标签可见、44px 触控、正文 16px、对比 4.5:1、焦点环和 reduced-motion。

- [ ] **Step 4: 执行前端测试与构建并提交**

Run: `npm ci`、`npm test`、`npm run build` from `support-web`；Expected: 全部通过，构建无私钥/Token/内部服务 URL。

```powershell
git add -- labs/09-ai-campus-support/support-web
git diff --cached --check
git commit -m "feat(ai): build campus support web journey"
```

### Task 7: 同源静态站点、演示邮件和真实浏览器旅程

**Files:**
- Modify: `labs/09-ai-campus-support/compose.yaml`, `labs/09-ai-campus-support/identity-service/src/main/java/com/example/campusmarket/identity/infrastructure/LocalVerificationMailSender.java`, `SmtpVerificationMailSender.java`, `SmtpMailConfiguration.java`
- Create: `labs/09-ai-campus-support/docker/support-web/Dockerfile`, `labs/09-ai-campus-support/docker/support-web/nginx.conf`
- Create: `labs/09-ai-campus-support/support-web/playwright.config.ts`, `e2e/support-journey.spec.ts`, `e2e/fixtures.ts`
- Test: `labs/09-ai-campus-support/identity-service/src/test/java/com/example/campusmarket/identity/api/DemoMailProfileTest.java`

**Interfaces:**
- Consumes: Task 3 AI Eureka 注册、Task 6 `dist/`、身份 SMTP 端口、Gateway URL。
- Produces: 静态前端同源 `/api/**` 代理、显式 `demo-mail` 演示 Profile、实际构建前端的桌面/移动浏览器测试。

- [ ] **Step 1: 写失败的代理与邮件 Profile 测试**

```java
@Test void demoMailSelectsOnlyCapturedSmtpSender() {
    new ApplicationContextRunner().withPropertyValues("spring.profiles.active=local,demo-mail").run(ctx -> {
        assertThat(ctx).hasSingleBean(VerificationMailSender.class);
        assertThat(ctx.getBean(VerificationMailSender.class)).isInstanceOf(SmtpVerificationMailSender.class);
    });
}
```

Run: `./mvnw.cmd -pl identity-service -am test -Dtest=DemoMailProfileTest`；Expected: 当前 local 内存 Sender 生效，RED。

- [ ] **Step 2: 明确 Profile 与同源 nginx 代理**

```nginx
location /api/ {
  proxy_pass http://api-gateway:8080;
  add_header Cache-Control "no-store" always;
}
location / {
  try_files $uri $uri/ /index.html;
}
```

`demo-mail` 仅在 Compose 的身份应用启用；local Sender 条件改为 `local & !demo-mail`，SMTP Sender/配置条件允许 `demo-mail`；本地邮件捕获使用 `axllent/mailpit:v1.30.0`，SMTP 与 HTTP 仅绑定本机。独立 web 镜像由锁版本的 Node 构建后用 nginx 提供静态文件；API/Header 与 Bearer 原样转发，不能用 SPA 回退处理 `/api/**`。Compose 只添加 AI 服务、web 和邮件捕获，保留实验八可复跑服务。

- [ ] **Step 3: 先写浏览器 RED，再用实际 Compose/Testcontainers 环境跑桌面与移动旅程**

```ts
test('用户从登录到本人订单问答并登出', async ({ page }) => {
  const testUser = await registerCampusUserThroughTestMailFixture();
  await page.goto('/');
  await page.getByLabel('校园邮箱').fill(testUser.email);
  await page.getByLabel('密码').fill(testUser.password);
  await page.getByRole('button', { name: '登录' }).click();
  await page.getByRole('button', { name: '选择订单' }).click();
  await page.getByLabel('问题').fill('退款规则是什么');
  await page.getByRole('button', { name: '查看答复' }).click();
  await expect(page.getByRole('heading', { name: '当前状态' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '规则来源' })).toBeVisible();
  await page.getByRole('button', { name: '登出' }).click();
  await expect(page.getByText('登录后查看本人订单')).toBeVisible();
});
```

`e2e/fixtures.ts` 的 `registerCampusUserThroughTestMailFixture()` 使用随机 `@stu.example.edu.cn` 邮箱，调用同源 `/api/auth/email-verifications`，只在测试进程中通过 Mailpit 官方 `/api/v1/messages` 和单项文本 API 读取六位验证码，再调用 `/api/auth/register`；不把验证码、邮箱或密码写入 Playwright 报告。另一个浏览器测试从注册表单提交验证码，证明注册页面可用。测试夹具通过交易 API 创建本人订单/案件，浏览器只读取；资源 ID 不写入报告。

Playwright 用实际注册夹具创建两个用户及订单/售后数据；浏览器不能看见他人列表。桌面和移动视口分别执行，键盘完成表单；401 清身份、交易/模型故障中文提示与恢复后重试均有测试。Run: `npm run test:e2e`；Expected: 全部通过，零 mock-only 旅程。

- [ ] **Step 4: 提交部署与浏览器旅程**

```powershell
git add -- labs/09-ai-campus-support/compose.yaml labs/09-ai-campus-support/docker/support-web labs/09-ai-campus-support/identity-service labs/09-ai-campus-support/support-web/playwright.config.ts labs/09-ai-campus-support/support-web/e2e
git diff --cached --check
git commit -m "test(ai): verify real browser support journey"
```

### Task 8: 实验级完整验收与学习闭环

**Files:**
- Modify: `labs/09-ai-campus-support/README.md`, `labs/09-ai-campus-support/TROUBLESHOOTING.md`, `labs/09-ai-campus-support/notes/learning-log.md`, `labs/09-ai-campus-support/interview/question-bank.md`
- Create: `labs/09-ai-campus-support/docs/acceptance-20260916.md`
- Modify on `main` only after acceptance: `README.md`, `notes/learning-log.md`, `interview/question-bank.md`

**Interfaces:**
- Consumes: Task 1-7 所有验收命令和报告。
- Produces: 独立分支中可重复命令/故障证据；验收后 `main` 状态更新与独立分支入口。

- [ ] **Step 1: 校验运行环境后执行新鲜完整命令**

```powershell
java -version
node --version
docker info
./mvnw.cmd clean test
./mvnw.cmd clean verify
git diff --check
```

Run: 前四个 Maven 命令在 `labs/09-ai-campus-support`；前端在 `support-web` 执行 `npm ci`、`npm test`、`npm run build`、`npm run test:e2e`。Expected: Reactor BUILD SUCCESS、Surefire/Failsafe 0 failures/errors/skipped、浏览器桌面与移动通过。

- [ ] **Step 2: 记录事实、故障演练、边界与学习问题**

```powershell
$xmlReports = Get-ChildItem -Path labs/09-ai-campus-support -Recurse -Filter 'TEST-*.xml'
$totals = [ordered]@{ tests = 0; failures = 0; errors = 0; skipped = 0 }
foreach ($report in $xmlReports) {
  [xml]$suite = Get-Content -Raw -LiteralPath $report.FullName
  foreach ($key in @('tests','failures','errors','skipped')) { $totals[$key] += [int]$suite.testsuite.$key }
}
$totals
```

把实际计数与 XML/Playwright 报告核对后再写验收文件；记录 MySQL/ES/模型故障和恢复、他人资源同构 404、供应商请求最小化、前端无 Token 持久化。README 写明独立实验分支、全新环境限制、Compose 最小服务和模型替身/真实 API 切换方式；TROUBLESHOOTING 写明 Docker、JWKS、索引版本、SMTP 捕获与浏览器故障排查。

- [ ] **Step 3: 只提交实验目录，验收后另在 main 提交文档中心状态**

```powershell
git add -- labs/09-ai-campus-support/README.md labs/09-ai-campus-support/TROUBLESHOOTING.md labs/09-ai-campus-support/docs labs/09-ai-campus-support/notes labs/09-ai-campus-support/interview
git diff --cached --check
git commit -m "docs(ai): record full-stack experiment acceptance"
```

`main` 的 `README.md` 状态仅在上述全部通过、证据已提交后由“未开始”改为“已验收”；中心学习日志与面试题库仅追加本实验的已验证结论，不复制实验代码或提交 `AGENTS.md`。
