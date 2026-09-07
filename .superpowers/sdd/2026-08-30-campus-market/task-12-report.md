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
- `docker info`：当前主机 Docker 引擎权限被拒绝，无法执行真实 MySQL/Testcontainers IT；因此两类 IT 未宣称通过。

## 自审与关注项

已保持 Task11 订单→案件→支付锁顺序、settled 订单状态隔离、支付 provider 适配框架和退款额度硬上限。关注项：Docker 引擎不可用导致 `WarrantyObligationIT` 与 `WarrantyDeadlineRaceIT` 尚未在真实 MySQL 上验收；应在 Docker 可用环境重新运行简报指定的两条 Failsafe 命令并确认 0 skipped。
