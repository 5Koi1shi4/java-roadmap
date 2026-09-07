# Task 12 实施报告

## RED 证据

先新增 `WarrantyPolicyTest`，执行 `.\mvnw.cmd -Dtest=WarrantyPolicyTest test`。测试编译阶段按预期失败：`com.example.campusmarket.warranty.domain` 与 `WarrantyCase` 不存在（7 个编译错误）。随后补充领域类型与最小实现，重复执行同命令得到 2 tests、0 failures、0 errors、0 skipped。

## 设计与迁移

- `WarrantyCase` 固定支持 30/90/180/365 天卖家快照质保；无卖家质保时仅允许平台 7 天窗口，期限采用左闭右开；第 7 天后的案件只更新质保案件，不触碰订单或结算终态。
- `WarrantyDecision` 支持 `REJECT`、`REPAIR_COMPENSATION`、`REFUND_ONLY`、`RETURN_AND_REFUND`；维修补偿使用整数分和 `min(verifiedQuote, paid-successfulRefund-reservedRefund)`。
- `SellerObligationService` 通过条件更新累计筹资，使用 `(settlementId, obligationId)` 唯一键做未来结算抵扣；足额时幂等清除发布/提现限制，资金不足不垫付。
- `EvidenceStorage` / `EvidenceCaseAccess` 复用物理存储和参与者/管理员同构 404 ACL，并加入 WARRANTY 案件路径。
- V25 增加截止任务租约/token fencing、账户限制、结算义务抵扣唯一键及高频索引/外键/CHECK。

## 验证

- `.\mvnw.cmd -Dtest=WarrantyPolicyTest test`：2 tests、0 failures、0 errors、0 skipped。
- `.\mvnw.cmd '-Dtest=WarrantyPolicyTest,RefundLimitTest' test`：4 tests、0 failures、0 errors、0 skipped。
- `.\mvnw.cmd -DskipTests compile`：BUILD SUCCESS。
- `git diff --check`：通过（仅 CRLF 转换提示）。
- `.\mvnw.cmd test`：81 tests、0 failures、0 errors、0 skipped。
- 真实 Docker 验收暴露 `ListingService` 有两个 `@Autowired` 构造器（3 参数与 4 参数），导致 ApplicationContext 报 `Invalid autowire-marked constructor`；已移除 3 参数构造器的 `@Autowired`，保留 4 参数为唯一 Spring 注入路径，同时保留 3 参数兼容手动/单测构造。
- 修复后 ` .\mvnw.cmd '-Dtest=WarrantyPolicyTest,ListingTest' test`：9 tests、0 failures、0 errors、0 skipped；`test-compile` 通过。
- 新增 `WarrantyObligationIT`（2 cases）与 `WarrantyDeadlineRaceIT`（2 cases），均使用 `Task11MySqlContainers` 的真实 MySQL 8.4/Testcontainers，覆盖结算后义务、筹资幂等、限制、复合抵扣幂等、ACL、seller/admin deadline、过期 claim takeover、owner/token/lease fencing 与筹资/过期竞态。
- `.\mvnw.cmd '-Dit.test=WarrantyObligationIT' failsafe:integration-test failsafe:verify`：实际启动 Failsafe，测试在 Testcontainers beforeAll 因 `AccessDeniedException \\.\pipe\docker_engine` / `Could not find a valid Docker environment` 失败（1 test class error, 0 skipped）；不是以 skipped 冒充通过。
- `.\mvnw.cmd test`：81 tests、0 failures、0 errors、0 skipped；新增 IT 已完成 `test-compile`。

## 自审与关注项

已保持 Task11 订单→案件→支付锁顺序、settled 订单状态隔离、支付 provider 适配框架和退款额度硬上限。关注项：Docker 引擎不可用导致两类 IT 尚未在真实 MySQL 上完成 GREEN；应在 Docker 可用环境运行 `.\mvnw.cmd -Dit.test=WarrantyObligationIT,WarrantyDeadlineRaceIT verify` 并确认 4 cases、0 skipped。
