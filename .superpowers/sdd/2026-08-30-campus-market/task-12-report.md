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
- R2 第三轮定位并修复未来结算抵扣路径的生产缺口：此前锁定义务查询未选出 `warranty_case_id`，足额抵扣触发退款 Outbox 时传入 null；现在从同一锁定行读取真实关联。IT 新增唯一 `WARRANTY_REFUND_REQUESTED` Outbox、案件/订单/金额 payload 强断言。
- R2 第四轮仅修正 IT 断言：Outbox payload 改用 Jackson JSON 结构解析，精确断言 `caseId/orderId/obligationId/amountFen`，不再依赖 JSON 空格格式；生产序列化未改动。

## R3 修复

- 注册 `WARRANTY_DECIDED` 与 `WARRANTY_REFUND_REQUESTED` 到 DomainEvent/RabbitTopology，并增加可靠 Warranty EventBusinessHandler；创建案件也写入同事务审计与 `WARRANTY_CASE_CREATED` Outbox。
- RETURN_AND_REFUND 现在只接受标记为 `RETURN_PROOF` 的参与者证据，创建/确认 warranty-backed `return_case` 并通过唯一库存业务键隔离退回商品；V27 增加来源互斥 CHECK、外键和证据用途约束。
- 足额抵扣读取真实案件关联并写退款 Outbox，SettlementResult 与 `SETTLEMENT_CREATED` payload 使用抵扣后的可用净额；抵扣审计、筹资/限制审计补齐，重复复合键返回 0 且所有更新影响数校验。
- 裁定与筹资 HTTP 命令改为强制 `Idempotency-Key`，缺失/格式错误返回 JSON 400/409；裁定支付成功记录按 `created_at DESC,id DESC` 确定并聚合退款额度。发布/提现命令使用限制行锁门禁，避免将 `canWithdraw` 布尔查询当授权。

本轮本地证据：` .\\mvnw.cmd -q -DskipTests compile`、` .\\mvnw.cmd -q -DskipTests test-compile` 成功；Docker/Testcontainers 由控制端执行真实 MySQL、Rabbit 和 HTTP 集成验收。

R3 控制端预审后的测试补强：新增 `WarrantyEventBusinessHandlerTest`（退款事件稳定幂等键）、`WarrantyControllerContractTest`（认证角色、JSON Content-Type 与 caseId/status）、DomainEvent 质保事件契约测试；扩展 `WarrantyObligationIT` 覆盖 RETURN_PROOF→return_case→库存隔离、REFUND_ONLY 义务、实际 settlement 抵扣后净额与事件；fixture 证据用途与每案 object key 唯一。测试新增后 `test-compile` 通过，定向单测待控制端低内存环境执行。
- 新增 `WarrantyOutboxDispatcherTest`：以 mock Rabbit publisher + OutboxRepository 验证 `WARRANTY_REFUND_REQUESTED` 可构造 DomainEvent、正确路由并完成 token-fenced outbox，而非永久 FAILED；该测试已定向 GREEN。
- `test-compile` 已重新通过；真实 MySQL/HTTP Failsafe 仍由控制端重跑，本机 Docker named pipe 不可用，未将错误折算为 skipped。

## 自审与关注项

## R5 最终修复轮（c4a9c2e 基线）

按 R4 规格/质量 OPEN 逐条复核并修复：

- 截止后筹资先在事务内将未足额义务 CAS 为 `CANCELLED`，写入 `SELLER_OBLIGATION_EXPIRED` 审计与 Outbox，提交后再表达 `FundingExpiredException`，避免异常触发回滚丢失终态；筹资、清除限制使用数据库义务 `version`，不再以 epoch millis 冒充 aggregate version。义务创建版本从 1 开始。
- `RefundService`/`JdbcPaymentRepository`/`WarrantyService`/`SettlementService` 全部使用同一 `created_at DESC,id DESC` 最新成功支付事实；结算资格的预占额也读取该行，消除旧成功支付退款与新支付实付混算。
- Rabbit 拆出 `campus.market.events.warranty` 专用队列，仅绑定 `WARRANTY_REFUND_REQUESTED`；订单队列配置 manual DLX。事件处理器改为显式 `supports` 注册，Inbox 遇不支持事件先落 `manual_failure=PERMANENT` 再完成可靠 ACK，避免 NACK 丢失或 PROCESSING 卡死。
- 新增低内存 `Task12RabbitMySqlContainers` 与 `WarrantyMessagingIT`（仅 MySQL+Rabbit）、`WarrantyHttpAclIT`（MySQL+InMemory 物理对象存储）和 `SellerWithdrawalConcurrencyIT`，分别覆盖消息确认/重放、真实 HTTP 认证/协议/同构 404，以及卖家级行锁、并发超提和限制后幂等重放。
- 提现命令在事务内先锁 `campus_user` 卖家行，再查询幂等记录，之后才检查 WITHDRAW 限制并计算余额。

R5 本地证据：`./mvnw.cmd -q -f pom.xml -DskipTests compile`、`-DskipTests test-compile`、`-Dtest=WarrantyPolicyTest,ListingTest,WarrantyEventBusinessHandlerTest,WarrantyOutboxDispatcherTest test` 均退出码 0。Docker 引擎在本机不可用，新增 MySQL/Rabbit Failsafe 未折算为通过，需控制端 Docker 可用时定向运行新增 IT 与既有 Warranty IT 并确认 0 skipped。

## R5 控制端失败修复追加

- `SellerWithdrawalConcurrencyIT` 不再假定哪个并发 key 获胜；记录真实获胜 key，并在插入 WITHDRAW 限制且余额不变后重放该同一 key，断言仍返回原 `withdrawalId`。另一 key 仍严格断言 `InsufficientBalanceException`，没有放宽并发超提约束。
- `WarrantyHttpAclIT` 不再手动覆盖 multipart Content-Type（由客户端生成 boundary），并新增无 boundary malformed multipart 请求必须返回 JSON 400 的断言。`WarrantyController` 增加 `MultipartException` 协议异常映射。

追加本地证据：`-DskipTests test-compile` 与 Warranty/Listing 定向单测均退出码 0；Docker 端应重跑第一批 IT，确认 multipart 201/200、malformed 400 及提现同 key 限制后重放。

## R5 控制端第二次失败修复追加

控制端确认合法 multipart 已通过，但无 boundary 请求仍为 500。根因是 multipart 解析发生在 Controller 方法/局部 `@ExceptionHandler` 之前；新增全局 `ApiProtocolExceptionHandler`（`@RestControllerAdvice`）捕获 `MultipartException`，统一返回 UTF-8 `application/json` 400 错误体。保留合法物理对象存储写入/读取与 malformed 400 断言。

追加本地证据：`-DskipTests test-compile`、`WarrantyPolicyTest,ListingTest,WarrantyControllerContractTest` 定向测试退出码 0；请控制端重跑第一批确认 malformed multipart 为 JSON 400。

已保持 Task11 订单→案件→支付锁顺序、settled 订单状态隔离、支付 provider 适配框架和退款额度硬上限。关注项：Docker 引擎不可用导致两类 IT 尚未在真实 MySQL 上完成 GREEN；应在 Docker 可用环境运行 `.\mvnw.cmd -Dit.test=WarrantyObligationIT,WarrantyDeadlineRaceIT verify` 并确认 4 cases、0 skipped。

## R4 例外轮复核与实现（基线 e9a9c93）

- RED：在 `WarrantyEventBusinessHandlerTest` 增加非质保事件测试；基线无法提供 `UnsupportedEventException`，测试先失败。GREEN：实现事件族拒绝异常及 `ReliableEventConsumer` reject-without-ACK 分支，定向测试通过。
- RED：增加 `WarrantyPolicyTest.warrantyEvidenceRequiresAnExplicitPurpose`，基线允许无用途质保证据。GREEN：上传边界强制 `REPAIR_QUOTE`/`INVOICE`/`RETURN_PROOF`，物理上传即写 `verification_status=VERIFIED`，裁定仅接受同案参与者、对应用途和 VERIFIED 证据；V28 增加约束/索引。
- 多笔成功支付事实统一为最新成功支付行（`created_at DESC,id DESC`）及其退款累计，修复裁定此前混合最新实付与所有行退款而与退款服务/结算不一致；新增真实 MySQL 集成回归待控制端执行。
- 义务创建、筹资、抵扣、过期及 PUBLISH/WITHDRAW 限制激活/清除现在各写审计和对应 Outbox；截止过期从批量裸 UPDATE 改为逐义务锁定/CAS。
- `WarrantyDeadlineScheduler.arm` 只对 `NEW` claim 保留更早固定期限，绝不清除 `PROCESSING` 的 owner/token/lease 或重置 `COMPLETED`，修复租约夺取和截止时间漂移。
- 新增 V29 `seller_withdrawal` 及 `/api/seller/withdrawals` 实际命令：事务内 WITHDRAW 行锁、余额检查、幂等落库，限制拒绝不再是空方法。
- 质保/筹资/证据/提现 HTTP 错误契约补充 JSON 400/409/422/503；可靠消息配置按事件族路由，解决测试 fixture 与 Warranty handler 多 Bean 注入歧义。

R4 定向 GREEN：`labs/07-campus-market/mvnw.cmd -q -f labs/07-campus-market/pom.xml '-Dtest=WarrantyPolicyTest,WarrantyEventBusinessHandlerTest,WarrantyOutboxDispatcherTest,WarrantyControllerContractTest' test`（通过）；`-DskipTests compile`（通过）；`-DskipTests test-compile`（通过）；`git diff --check`（通过，仅 LF/CRLF 提示）。真实 MySQL/Rabbit/HTTP Failsafe 仍需控制端 Docker 环境运行，未将环境不可用折算为 skipped 或通过。
