# Task 11 实施报告：可信退回、部分退款、隔离库存和硬期限

## RED / GREEN 记录

1. `.\mvnw.cmd -Dtest=ReturnResolutionTest test`（新增测试后）——RED。测试编译失败，原因是 `ReturnCase` 和 `ReturnProofType` 尚不存在；不是测试通过或环境跳过。
2. 新增 `ReturnProofType`、`ReturnCase` 后再次运行同命令——GREEN，`ReturnResolutionTest` 1/1 通过。
3. `.\mvnw.cmd '-Dtest=ReturnResolutionTest,DisputeCaseTest,RefundLimitTest' test`——GREEN，11 tests、0 failures、0 errors、0 skipped。
4. `.\mvnw.cmd '-DskipTests' test-compile`——GREEN，主代码及 44 个测试源编译成功（仅仓库既有 `MockBean` 弃用告警）。
5. `docker info`——未通过：Docker client 可用，但 server 连接 `npipe:////./pipe/dockerDesktopLinuxEngine` 被 permission denied；故未将真实 MySQL/Testcontainers IT 记为验收通过。
6. Windows `Win32_OperatingSystem.FreePhysicalMemory` 与 `wsl --status`——受本机权限/WSL enumerate 拒绝，无法取得可靠内存数值；已列为 concern。

## 实现与数据库设计

- `ReturnProofType` 只将 `SELLER_CONFIRMED`、`PROVIDER_DELIVERED`、`ADMIN_CONFIRMED` 标记为可信；`BUYER_EVIDENCE` 永远不能自动退款。
- `ReturnCase` 和 `DisputeCase` 提供硬期限证明门禁，退款额使用 `unitPriceFen * approvedQuantity` 的溢出保护。
- `ReturnResolutionService` 在短数据库事务中锁订单/争议并写唯一 `return_case`，事务外调用已有 `RefundService`，成功后以唯一库存业务键调用 `InventoryPort.quarantine`；`REFUND_ONLY` 不写库存。支付已有 MySQL 条件更新保证成功、预占和请求额不超过实付。
- `DisputeDeadlineScheduler` 使用 MySQL `CURRENT_TIMESTAMP(6)`、owner、claim token 和租约。72h 无卖家响应进入 `UNDER_REVIEW`；7d 只写 SLA 告警 Outbox；14d 可信证明写唯一 `REFUND_REQUESTED` Outbox，无可信证明进入 `ESCALATED`，不默认转账。
- `SettlementService` 锁订单后仅在 `AFTERSALE_WINDOW`、`T0+7d`、无活动争议、无 `REQUESTED/PROCESSING/UNKNOWN` 退款及无预占时按 `(order_id)` 唯一 settlement 写净额；不读取 warranty deadline 作为普通结算门禁。
- `V21__return_resolution_deadlines.sql` 补充证明/硬期限/告警字段、return refund 状态、dispute deadline claim、settlement 审计字段及必要索引/FK/check constraints。

## 自审与 concerns

- `git diff --check` 通过。
- Docker 权限不足阻止真实 MySQL/Flyway/并发/网关 IT；请主控在 Docker 权限恢复后运行 `PartialReturnRefundIT,DisputeDeadlineIT` 及 Task8-10 回归。
- 新增 IT 已按真实容器和已有 simulated provider 接口编写，但本机未能执行，不能宣称零 skipped。
- 运行时应重点检查 Flyway V21 在 MySQL 8.4 的约束顺序、ReturnResolutionService 与现有 RefundService 的 provider 回调收敛，以及并发裁决下的订单锁顺序。
