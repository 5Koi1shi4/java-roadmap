# Task 12 实施报告

## RED 证据

先新增 `WarrantyPolicyTest`，执行 `.\mvnw.cmd -Dtest=WarrantyPolicyTest test`。测试编译阶段按预期失败：`com.example.campusmarket.warranty.domain` 与 `WarrantyCase` 不存在（7 个编译错误）。随后补充领域类型与最小实现，重复执行同命令得到 2 tests、0 failures、0 errors、0 skipped。

## 设计与迁移

- `WarrantyCase` 固定支持 30/90/180/365 天卖家快照质保；无卖家质保时仅允许平台 7 天窗口，期限采用左闭右开；第 7 天后的案件只更新质保案件，不触碰订单或结算终态。
- `WarrantyDecision` 支持 `REJECT`、`REPAIR_COMPENSATION`、`REFUND_ONLY`、`RETURN_AND_REFUND`；维修补偿使用整数分和 `min(verifiedQuote, paid-successfulRefund-reservedRefund)`。
- `SellerObligationService` 通过条件更新累计筹资，使用 `(settlementId, obligationId)` 唯一键做未来结算抵扣；足额时幂等清除发布/提现限制，资金不足不垫付。
- `EvidenceStorage` / `EvidenceCaseAccess` 复用物理存储和参与者/管理员同构 404 ACL，并加入 WARRANTY 案件路径。
- V25 增加截止任务租约/token fencing、账户限制、结算义务抵扣唯一键及高频索引/外键/CHECK；V26 补充平台 7 天 CHECK、请求指纹、筹资命令唯一键和卖家优先索引。

## 验证

- `.\mvnw.cmd -Dtest=WarrantyPolicyTest test`：2 tests、0 failures、0 errors、0 skipped。
- `.\mvnw.cmd '-Dtest=WarrantyPolicyTest,RefundLimitTest' test`：4 tests、0 failures、0 errors、0 skipped。
- `.\mvnw.cmd -DskipTests compile`：BUILD SUCCESS。
- `git diff --check`：通过（仅 CRLF 转换提示）。
- `.\mvnw.cmd test`：81 tests、0 failures、0 errors、0 skipped。
- 控制端真实 MySQL 复跑发现 `WarrantyDeadlineRaceIT.expiredSellerClaimIsTakenOverWithFreshTokenAndAdminDeadlinesUseDatabaseTime` 的管理员期限断言为 0：fixture 只插入过期 seller claim，而调度器 `arm` 仅 UPDATE，不会创建缺失的 admin/hard claim。已改为 INSERT ... ON DUPLICATE KEY UPDATE，以数据库时间创建并重置两类管理员 claim；同时保留旧 owner/token fencing。
- 修复后 `.\mvnw.cmd '-Dtest=WarrantyPolicyTest,ListingTest' test`：9 tests、0 failures、0 errors、0 skipped；IT 已重新 test-compile。
- 真实 Docker 验收暴露 `ListingService` 有两个 `@Autowired` 构造器（3 参数与 4 参数），导致 ApplicationContext 报 `Invalid autowire-marked constructor`；已移除 3 参数构造器的 `@Autowired`，保留 4 参数为唯一 Spring 注入路径，同时保留 3 参数兼容手动/单测构造。
- 修复后 ` .\mvnw.cmd '-Dtest=WarrantyPolicyTest,ListingTest' test`：9 tests、0 failures、0 errors、0 skipped；`test-compile` 通过。
- 新增 `WarrantyObligationIT`（2 cases）与 `WarrantyDeadlineRaceIT`（2 cases），均使用 `Task11MySqlContainers` 的真实 MySQL 8.4/Testcontainers，覆盖结算后义务、筹资幂等、限制、复合抵扣幂等、ACL、seller/admin deadline、过期 claim takeover、owner/token/lease fencing 与筹资/过期竞态。
- `.\mvnw.cmd '-Dit.test=WarrantyObligationIT' failsafe:integration-test failsafe:verify`：实际启动 Failsafe，测试在 Testcontainers beforeAll 因 `AccessDeniedException \\.\pipe\docker_engine` / `Could not find a valid Docker environment` 失败（1 test class error, 0 skipped）；不是以 skipped 冒充通过。
- `.\mvnw.cmd test`：81 tests、0 failures、0 errors、0 skipped；新增 IT 已完成 `test-compile`。

## R1 审查修复（本轮）

- `V26__warranty_review_hardening.sql` 放宽 `warranty_case` 的持久化 CHECK 至 `7/30/90/180/365`，增加创建/裁定请求指纹字段与卖家义务高频索引。
- WARRANTY 证据查询改为显式 `AS case_id/order_id/buyer_id/seller_id/assigned_admin_id`，裁定只接受数据库中同案件、`case_type='WARRANTY'` 的真实证据；ACL 仍复用参与者/管理员同构 404。
- 裁定执行版本 CAS 的更新行数，终态拒绝二次裁定；所有非拒绝裁定（维修、仅退款、退货退款）建立同一义务/限制闭环，退款金额使用 `unit_price_fen * disputed_quantity` 并受实付、成功退款、预占退款硬上限约束；决策审计与 outbox 和状态写入同事务。
- 创建/裁定支持请求指纹幂等与摘要冲突；新增规范 API 别名和卖家义务查询；调度器管理员期限用同一数据库时间值创建 claim，过期 claim 重新领取并以 owner/token/lease fencing 完成。
- 未来结算抵扣校验 `SETTLED` 状态、结算/订单/卖家归属和可用净额，纳入逾期义务，复合键重复抵扣返回 0；足额抵扣与筹资在事务内写 `WARRANTY_REFUND_REQUESTED` outbox，并幂等清除限制。

本地证据：` .\\mvnw.cmd -q -DskipTests compile`、` .\\mvnw.cmd -q -Dtest=WarrantyPolicyTest test`、` .\\mvnw.cmd -q -DskipTests test-compile` 均成功；本机 Testcontainers 再次执行仍因 `AccessDeniedException \\.\\pipe\\docker_engine` 无 Docker 引擎而无法进入 MySQL（0 skipped，测试类 beforeAll error）。控制端应在 Docker 可用环境重跑两类 IT，并核对 4 cases/0 skipped 及 Outbox、退款、四种裁定边界。

## R2 控制端反馈修复

- 竞态 IT 现在只将 `FundingExpiredException` 作为截止赢家允许的业务结果，`Future.get` 的其它异常继续使测试失败；随后仍强断言最终状态只能是 `FUNDED` 或 `CANCELLED`，分别对应限制解除或保持两条限制。
- WarrantyObligationIT 的 WARRANTY 证据 fixture 改为每个案件唯一 `object_key`（仍是真实 `dispute_evidence` 行并可走读取 ACL），避免跨用例唯一键冲突。
- 竞态获胜/失败 Future 的异常分类已收紧为唯一允许的业务截止异常；补充 `GET /api/warranty-cases/{caseId}`（及兼容路径）参与者/管理员 ACL 查询，返回显式 JSON `caseId/orderId/status/decision/compensationAmountFen`，便于真实 HTTP 测试先断言状态与 Content-Type 再解析。
- `test-compile` 已重新通过；真实 MySQL/HTTP Failsafe 仍由控制端重跑，本机 Docker named pipe 不可用，未将错误折算为 skipped。

## 自审与关注项

已保持 Task11 订单→案件→支付锁顺序、settled 订单状态隔离、支付 provider 适配框架和退款额度硬上限。关注项：Docker 引擎不可用导致两类 IT 尚未在真实 MySQL 上完成 GREEN；应在 Docker 可用环境运行 `.\mvnw.cmd -Dit.test=WarrantyObligationIT,WarrantyDeadlineRaceIT verify` 并确认 4 cases、0 skipped。
