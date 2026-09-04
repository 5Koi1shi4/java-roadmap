# Campus Market One-Shot Payment Attempt Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复实验七 Task 8 的支付/退款重复创建风险，使每个幂等业务请求最多调用一次 provider create，并使即时终态、聚合和 Outbox 严格原子提交。

**Architecture:** 在支付与退款记录上持久化不可逆的 `create_attempted_at`，以单条数据库 CAS 同时取得首次创建权和 owner/token/lease；租约只控制结果提交与对账，不再允许再次 create。支付和退款服务分别用短事务提交终态、聚合变化、原始响应与 Outbox，失败时回滚并交由只查询的 reconciliation 收敛。

**Tech Stack:** Java 17、Spring Boot 3、Spring JDBC、MySQL 8.4、Flyway、JUnit 5、AssertJ、Testcontainers、HTTP 模拟支付提供方。

## Global Constraints

- 只修改 `labs/07-campus-market`；实验分支最终树继续只保留根 `.gitignore` 与 `labs/`。
- 金额只使用整数分；禁止 `double`、`float` 与二进制浮点运算。
- 真实 CAS、学校域名、Ticket、Cookie、支付商户号、证书、密钥、验证码和 JWT 不得提交或写入日志。
- 未知支付/退款结果不盲目创建第二次请求；主动对账只能调用 provider query。
- 支付或退款终态、聚合 CAS、原始响应和 Outbox 必须在同一数据库事务中提交或回滚。
- 相同幂等键与相同原始请求重放持久化响应；相同幂等键与不同请求返回 409。
- Docker、MySQL、RabbitMQ、Redis、MinIO、Elasticsearch 等依赖保持手动启动，不新增开机自启。
- 验证必须使用 Maven Wrapper 和真实 Testcontainers；结果要求 0 failures、0 errors、0 skipped。
- Windows 可用 RAM 低于 1 GB 或 WSL 可用内存低于 500 MB 时立即提醒用户。

---

### Task 1: 持久化一次性 provider create 权

**Files:**
- Create: `labs/07-campus-market/src/main/resources/db/migration/V18__one_shot_payment_attempts.sql`
- Modify: `labs/07-campus-market/src/main/java/com/example/campusmarket/payment/infrastructure/JdbcPaymentRepository.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/PaymentFlowIT.java`

**Interfaces:**
- Consumes: 既有 `payment_order`、`refund_order`、`reconcile_owner`、`reconcile_token`、`reconcile_lease_until` 与数据库时间。
- Produces: `boolean claimInitialPaymentAttempt(UUID id, String owner, String token)`、`boolean claimInitialRefundAttempt(UUID id, String owner, String token)`；二者只在 `create_attempted_at IS NULL` 时成功一次。

- [ ] **Step 1: 写出数据库 claim 的失败测试**

在 `PaymentFlowIT` 增加两个测试。每个测试插入新的支付或退款记录，第一次 claim 必须为真；人工将 lease 置为过去时间后，第二次用新 token claim 仍必须为假；最后断言 `create_attempted_at IS NOT NULL` 且第一次 token 未被第二次覆盖。

```java
assertThat(repository.claimInitialPaymentAttempt(paymentId, "owner-a", "token-a")).isTrue();
jdbc.update("UPDATE payment_order SET reconcile_lease_until=DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 SECOND) WHERE id=?", paymentId.toString());
assertThat(repository.claimInitialPaymentAttempt(paymentId, "owner-b", "token-b")).isFalse();
assertThat(jdbc.queryForObject(
    "SELECT create_attempted_at IS NOT NULL FROM payment_order WHERE id=?",
    Boolean.class, paymentId.toString())).isTrue();
```

- [ ] **Step 2: 运行新测试并确认因字段或方法不存在而失败**

Run: `./mvnw -Dit.test=PaymentFlowIT#paymentCreateAttemptCanOnlyBeClaimedOnce+refundCreateAttemptCanOnlyBeClaimedOnce verify`

Expected: FAIL，提示 `claimInitialPaymentAttempt`/`claimInitialRefundAttempt` 或 `create_attempted_at` 不存在。

- [ ] **Step 3: 添加 V18 迁移**

迁移必须先加列，再保守回填迁移前的所有记录；迁移后新插入记录保持 `NULL`，由首次 claim 写数据库时间。

```sql
ALTER TABLE payment_order ADD COLUMN create_attempted_at DATETIME(6) NULL AFTER response_utf8;
ALTER TABLE refund_order ADD COLUMN create_attempted_at DATETIME(6) NULL AFTER response_utf8;

UPDATE payment_order SET create_attempted_at = created_at WHERE create_attempted_at IS NULL;
UPDATE refund_order SET create_attempted_at = created_at WHERE create_attempted_at IS NULL;
```

- [ ] **Step 4: 实现一次性 claim 仓储方法**

两个方法都使用单条条件更新；不能把 `UNKNOWN` 或 lease 到期作为重新创建条件。

```java
public boolean claimInitialPaymentAttempt(UUID id, String owner, String token) {
    return jdbc.update("""
        UPDATE payment_order
           SET create_attempted_at=CURRENT_TIMESTAMP(6),
               reconcile_owner=?, reconcile_token=?,
               reconcile_lease_until=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 SECOND),
               next_reconcile_at=DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 30 SECOND),
               updated_at=CURRENT_TIMESTAMP(6)
         WHERE id=? AND create_attempted_at IS NULL
           AND provider_reference IS NULL AND status IN ('PENDING','CREATED')
        """, owner, token, id.toString()) == 1;
}
```

`claimInitialRefundAttempt` 使用相同结构，状态限制为 `REQUESTED`。删除或收窄会让 `UNKNOWN` 再次 create 的旧 `claimPaymentRequest`/`claimRefundRequest` 路径；reconciliation claim 保持只授予 query 权。

- [ ] **Step 5: 运行数据库 claim 测试**

Run: `./mvnw -Dit.test=PaymentFlowIT#paymentCreateAttemptCanOnlyBeClaimedOnce+refundCreateAttemptCanOnlyBeClaimedOnce verify`

Expected: 两项通过，0 failures、0 errors、0 skipped，Flyway 到 V18。

- [ ] **Step 6: 提交 Task 1**

```bash
git add labs/07-campus-market/src/main/resources/db/migration/V18__one_shot_payment_attempts.sql \
  labs/07-campus-market/src/main/java/com/example/campusmarket/payment/infrastructure/JdbcPaymentRepository.java \
  labs/07-campus-market/src/test/java/com/example/campusmarket/integration/PaymentFlowIT.java
git commit -m "fix(campus): persist one-shot provider attempts"
```

---

### Task 2: 原子提交支付终态并禁止 UNKNOWN 重建支付

**Files:**
- Modify: `labs/07-campus-market/src/main/java/com/example/campusmarket/payment/application/PaymentService.java`
- Modify: `labs/07-campus-market/src/main/java/com/example/campusmarket/payment/infrastructure/JdbcPaymentRepository.java`
- Modify: `labs/07-campus-market/src/main/java/com/example/campusmarket/payment/infrastructure/SimulatedPaymentProviderController.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/PaymentFlowIT.java`

**Interfaces:**
- Consumes: Task 1 的 `claimInitialPaymentAttempt`、既有 provider `createPayment/queryPayment/queryPaymentByIdempotencyKey`、支付与订单 CAS。
- Produces: `createPayment` 永久只创建一次；`markPaymentSucceeded`、`markPaymentFailed`、`advanceOrderAfterPayment` 和 `savePaymentResponseAndRelease` 在调用者事务中返回明确 CAS 成功与否。

- [ ] **Step 1: 写 UNKNOWN 重试零创建测试**

让模拟 provider 的首次支付 create 在创建 provider 记录后对客户端超时，使本地保存 `UNKNOWN`。记录 `paymentCreateRequestCount()`，把本地 lease 与 `next_reconcile_at` 调整为过去，随后重复调用同一 API 并执行 `reconciliation.runOnce()`；最终 create 计数必须始终为 1，query 使本地收敛到 provider 状态。

```java
int afterFirstAttempt = provider.paymentCreateRequestCount();
assertThat(afterFirstAttempt).isEqualTo(1);
payments.createPayment(orderId, key, rawBody);
reconciliation.runOnce();
assertThat(provider.paymentCreateRequestCount()).isEqualTo(afterFirstAttempt);
```

- [ ] **Step 2: 写支付终态事务回滚测试**

增加即时 `SUCCEEDED` 测试并人为把订单状态改为非 `PENDING_PAYMENT`，使订单 CAS 影响零行。调用完成后断言支付未提交为 `SUCCEEDED`、没有 `PAYMENT_SUCCEEDED` Outbox，且 API 不返回伪成功。另保留正常即时 `SUCCEEDED`/`FAILED` 测试，分别断言订单状态、已付金额、原始响应和唯一 Outbox。

```java
assertThat(outboxCount("PAYMENT_SUCCEEDED", paymentId)).isZero();
assertThat(paymentStatus(paymentId)).isNotEqualTo("SUCCEEDED");
assertThat(orderStatus(orderId)).isNotEqualTo("AWAITING_HANDOFF");
```

- [ ] **Step 3: 运行新支付测试并确认失败**

Run: `./mvnw -Dit.test=PaymentFlowIT#unknownPaymentRetryNeverCreatesAgain+immediatePaymentSuccessRollsBackWhenOrderCasFails+immediatePaymentTerminalStatesCommitAtomically verify`

Expected: FAIL；当前 UNKNOWN 会重新 create，且订单 CAS 为零不会回滚支付终态和 Outbox。

- [ ] **Step 4: 改造 `PaymentService.createPayment`**

仅 `claimInitialPaymentAttempt` 成功者调用 provider create。claim 失败后直接读取持久化结果；若记录非终态且创建已尝试，则允许调用既有 reconcile/query 路径，但禁止 create。

```java
if (!repository.claimInitialPaymentAttempt(paymentId, owner, token)) {
    return queryPayment(paymentId);
}
PaymentGateway.PaymentCreated created = gateway.createPayment(request);
return commitInitialProviderResult(paymentId, orderId, created, owner, token);
```

provider 抛异常时只允许仍持有 fencing 的 owner 把记录置为 `UNKNOWN`、保存同一份 UTF-8 响应并释放租约；旧 owner 只返回当前数据库结果。

- [ ] **Step 5: 让订单 CAS 成为支付成功事务的硬条件**

把 `advanceOrderAfterSuccess` 改为返回影响行数或在不等于 1 时抛异常。支付终态 CAS、订单 CAS、终态响应和 Outbox 必须在同一 `TransactionTemplate` 回调中；任何失败触发整体回滚。

```java
if (!repository.markPaymentSucceeded(...)) return false;
int orderChanged = repository.advanceOrderAfterPayment(paymentId, orderId);
if (orderChanged != 1) throw new IllegalStateException("订单支付结转 CAS 失败，等待对账");
if (!repository.saveOwnedPaymentResponseAndRelease(...))
    throw new IllegalStateException("支付响应落库 CAS 失败，等待对账");
repository.insertPaymentEvent("PAYMENT_SUCCEEDED", paymentId, payload);
```

捕获该事务异常后，不得再次 create；若 fencing 仍有效，可将支付保存为 `UNKNOWN` 等待 query/reconcile。

- [ ] **Step 6: 验证支付场景与回归**

Run: `./mvnw -Dit.test=PaymentFlowIT,PaymentGatewayContractIT verify`

Expected: 所有支付/网关合同测试通过，0 failures、0 errors、0 skipped；UNKNOWN API 重试与 scheduler 后 provider create 计数仍为 1。

- [ ] **Step 7: 提交 Task 2**

```bash
git add labs/07-campus-market/src/main/java/com/example/campusmarket/payment/application/PaymentService.java \
  labs/07-campus-market/src/main/java/com/example/campusmarket/payment/infrastructure/JdbcPaymentRepository.java \
  labs/07-campus-market/src/main/java/com/example/campusmarket/payment/infrastructure/SimulatedPaymentProviderController.java \
  labs/07-campus-market/src/test/java/com/example/campusmarket/integration/PaymentFlowIT.java
git commit -m "fix(campus): atomically settle one-shot payments"
```

---

### Task 3: 对称修复退款创建与即时终态

**Files:**
- Modify: `labs/07-campus-market/src/main/java/com/example/campusmarket/payment/application/RefundService.java`
- Modify: `labs/07-campus-market/src/main/java/com/example/campusmarket/payment/infrastructure/JdbcPaymentRepository.java`
- Modify: `labs/07-campus-market/src/main/java/com/example/campusmarket/payment/infrastructure/SimulatedPaymentProviderController.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/PaymentFlowIT.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/unit/payment/RefundLimitTest.java`

**Interfaces:**
- Consumes: Task 1 的 `claimInitialRefundAttempt`、支付聚合上的 `reserved_refund_fen/refunded_fen`、退款 query/reconciliation。
- Produces: 每个退款幂等键最多一次 provider refund create；即时 `SUCCEEDED/FAILED` 与聚合、响应、Outbox 原子提交。

- [ ] **Step 1: 扩展模拟 provider 的退款可观测控制**

在现有单锁保护下增加 `nextRefundStatus`、退款 create 请求计数读取/重置方法，以及需要时的 create entered/release latch。`createRefund` 只在创建新幂等记录时消费下一状态，但每次 HTTP create 都增加请求计数。

```java
public int refundCreateRequestCount() { return refundCreateRequests.get(); }
public void nextRefundStatus(String status) { validateStatus(status); nextRefundStatus = status; }
```

- [ ] **Step 2: 写退款 UNKNOWN 零重建和即时终态测试**

增加以下确定性场景：

- 首次退款 create 超时形成 `UNKNOWN`，租约到期、重复 API 和 scheduler 后 `refundCreateRequestCount()` 仍为 1；
- 即时 `SUCCEEDED`：预占额减少、已退款额增加、终态响应与唯一 `REFUND_SUCCEEDED` Outbox 同时存在；
- 即时 `FAILED`：预占额释放、已退款额不增、终态响应与唯一 `REFUND_FAILED` Outbox 同时存在；
- 人为使额度 CAS 失败：退款终态、响应和 Outbox 全部回滚；
- provider reference 或金额错误：不推进聚合且不写终态 Outbox。

- [ ] **Step 3: 运行新退款测试并确认失败**

Run: `./mvnw -Dit.test=PaymentFlowIT#unknownRefundRetryNeverCreatesAgain+immediateRefundSuccessCommitsAtomically+immediateRefundFailureReleasesAtomically+immediateRefundRollsBackWhenAggregateCasFails verify`

Expected: FAIL；当前退款没有不可逆首次 create claim，且缺少完整即时终态断言或模拟控制。

- [ ] **Step 4: 改造退款 prepare 与 provider 调用边界**

`prepare` 事务只负责校验、占额、插入幂等退款记录并取得 `claimInitialRefundAttempt`。`RefundIntent` 携带 owner/token；只有 claim 成功者离开事务后调用 provider refund create。已存在或 claim 失败的记录直接重放，不得调用 provider。

```java
RefundIntent intent = transactions.execute(status -> prepareAndClaim(..., owner, token));
if (!intent.mayCreate()) return replay(intent.refundId());
PaymentGateway.RefundCreated created = gateway.requestRefund(request);
return transactions.execute(status -> finishOwned(intent, created, owner, token));
```

- [ ] **Step 5: 原子提交退款终态**

`finishOwned` 和 reconciliation 的 `settleTerminal` 都必须依次完成：退款终态 fenced CAS、支付聚合额度 CAS、持久化终态响应、唯一 Outbox。任一条件更新为零都抛异常回滚。UNKNOWN 只保存查询用状态和原始响应，不释放再次 create 权。

```java
if (!repository.markRefundTerminal(..., owner, token)) throw casFailure();
boolean aggregateChanged = succeeded
    ? repository.completeRefund(paymentId, amountFen)
    : repository.releaseRefund(paymentId, amountFen);
if (!aggregateChanged) throw casFailure();
repository.saveRefundResponse(refundId, terminalResponse);
repository.insertPaymentEvent(eventType, refundId, payload);
```

- [ ] **Step 6: 验证退款单测和集成测试**

Run: `./mvnw -Dtest=RefundLimitTest test`

Expected: 通过，0 failures、0 errors、0 skipped。

Run: `./mvnw -Dit.test=PaymentFlowIT,PaymentGatewayContractIT verify`

Expected: 所有场景通过，0 failures、0 errors、0 skipped；支付与退款 create 计数均满足一次性约束。

- [ ] **Step 7: 检查迁移、格式与工作树**

Run: `./mvnw test`

Expected: Surefire 全部通过且无 skipped。

Run: `git diff --check`

Expected: 无输出。

- [ ] **Step 8: 提交 Task 3**

```bash
git add labs/07-campus-market/src/main/java/com/example/campusmarket/payment/application/RefundService.java \
  labs/07-campus-market/src/main/java/com/example/campusmarket/payment/infrastructure/JdbcPaymentRepository.java \
  labs/07-campus-market/src/main/java/com/example/campusmarket/payment/infrastructure/SimulatedPaymentProviderController.java \
  labs/07-campus-market/src/test/java/com/example/campusmarket/integration/PaymentFlowIT.java \
  labs/07-campus-market/src/test/java/com/example/campusmarket/unit/payment/RefundLimitTest.java
git commit -m "fix(campus): atomically settle one-shot refunds"
```

---

### Task 4: 结构修正总验收与恢复 Task 8

**Files:**
- Modify: `E:/test/work/java-roadmap/.superpowers/sdd/2026-08-30-campus-market/task-8-report.md`
- Modify: `E:/test/work/java-roadmap/.superpowers/sdd/2026-08-30-campus-market/progress.md`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/PaymentFlowIT.java`
- Test: `labs/07-campus-market/src/test/java/com/example/campusmarket/integration/PaymentGatewayContractIT.java`

**Interfaces:**
- Consumes: Tasks 1–3 的提交和测试证据。
- Produces: Task 8 解除 `BLOCKED` 的可审查证据；不产生生产代码。

- [ ] **Step 1: 执行支付结构修正完整验证**

Run: `./mvnw -Dtest=RefundLimitTest test`

Run: `./mvnw -Dit.test=PaymentFlowIT,PaymentGatewayContractIT verify`

Expected: 每个命令均为 BUILD SUCCESS，0 failures、0 errors、0 skipped；Failsafe XML 的 completed 数等于实际测试数。

- [ ] **Step 2: 执行 Task 8 回归与静态卫生检查**

Run: `./mvnw test`

Run: `git diff --check`

Run: `git status --short`

Expected: 单测全部通过、diff check 无输出、实现提交后工作树干净。

- [ ] **Step 3: 独立 Standards/Spec 复审**

生成从 `ac5180f` 到结构修正最终 HEAD 的审查包。审查必须分别给出 Standards 与 Spec 结论，并确认没有 Critical/Important：一次性 create、UNKNOWN query-only、owner/token/lease fencing、支付订单 CAS、退款额度 CAS、响应重放、Outbox 唯一性及全部测试证据。

- [ ] **Step 4: 更新 Task 8 记录**

仅在独立复审同时批准 Standards 与 Spec 后，在累积报告写入提交范围、准确测试数和 0 skipped；在 ledger 追加：

```text
Task 8 structural payment-attempt redesign complete (final range recorded using the actual `ac5180f` base and reviewed HEAD hash) — Standards APPROVED and Spec APPROVED, both with 0 Critical/Important; controller verified payment/refund integration and unit suites with 0 failures/errors/skipped. Original BLOCKED state resolved.
Task 8: complete — one-shot provider create and atomic terminal settlement accepted; residual non-blocking Minors recorded in task-8-report.md.
```

随后恢复原校园市场计划的 Task 9，不提前宣称整个实验七已验收。
