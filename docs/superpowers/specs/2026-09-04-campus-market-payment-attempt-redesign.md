# 校园二手平台支付尝试协议结构修正规格

## 背景与目标

实验七 Task 8 已具备模拟支付、退款、回调和主动对账，但现有实现允许无 provider reference 的 `UNKNOWN` 支付在首次请求租约过期后再次进入 provider `createPayment`。这违反“未知结果不盲目创建第二支付/退款”的既定约束，并可能在真实支付适配器接入后造成重复扣款。

本次修正只收紧支付尝试协议、终态事务和覆盖测试，不增加 refresh token、真实支付、真实物流、竞价、聊天或代取功能。

## 核心不变量

1. 同一 `(provider, idempotency_key)` 对应的本地支付记录最多执行一次 provider create HTTP 调用。
2. “是否已尝试创建”是持久化事实，不由临时租约、进程内存或 `provider_reference` 是否为空推断。
3. 首次 create 已开始后，即使超时、断连、进程崩溃、租约过期或没有取得 provider reference，所有后续处理都只能 query/reconcile，不能再次 create。
4. owner、token 与 lease 只决定哪个工作者可以提交当前结果，不能授予第二次 create 权限。
5. 支付或退款终态、对应订单/额度聚合变化和 Outbox 事件必须在同一数据库事务中全部成功或全部回滚。
6. 终态响应不可被迟到的 `PENDING`、`UNKNOWN` 或旧 owner 响应覆盖；相同幂等请求重放持久化的原始 UTF-8 HTTP 响应。
7. 金额继续使用整数分；provider reference、金额、owner、token 和当前状态都参加必要的条件更新。

## 数据模型

在 `payment_order` 与 `refund_order` 分别增加不可逆字段：

- `create_attempted_at DATETIME(6) NULL`：第一次取得对应 provider create 权时由数据库时间写入；一旦非空不得清除或覆盖。

不新增第二张支付尝试表。既有唯一键 `(provider, idempotency_key)` 继续标识同一次业务支付，既有 `reconcile_owner`、`reconcile_token`、`reconcile_lease_until` 继续承担短期执行权 fencing。

数据库迁移必须保守处理现有实验数据：迁移执行前已经存在的支付和退款记录统一以 `created_at` 回填 `create_attempted_at`，避免无法证明“从未调用 provider”时发生第二次创建。迁移后的新记录初始保持空值，只有成功执行 claim 的工作者可写入首次尝试时间。

## 首次创建协议

`claimInitialPaymentAttempt(paymentId, owner, token)` 使用单条条件更新同时完成：

- 要求 `create_attempted_at IS NULL`；
- 要求记录处于允许首次创建的非终态；
- 写入数据库当前时间到 `create_attempted_at`；
- 写入 owner、token、lease 和下一次可对账时间。

只有更新影响一行的调用者可以执行 provider create。更新为零时，服务读取并重放当前持久化结果；无论租约是否过期，都不得调用 provider create。

provider 返回后，只有仍持有有效 owner/token/lease 的调用者可以绑定 reference、推进状态和保存响应。若旧 owner 迟到，返回数据库当前结果，不执行任何覆盖写。

provider 抛出异常或返回不可判定状态时，在仍持有 fencing 的前提下写入 `UNKNOWN` 与原始响应并释放租约。随后由主动对账按 provider reference 查询；reference 为空时按同一 idempotency key 查询。

## 支付终态事务

provider create 或 reconciliation 得到 `SUCCEEDED` 时，在同一个事务中：

1. 以当前状态、金额、reference 及 fencing 条件将支付记录 CAS 为 `SUCCEEDED`；
2. 将订单从 `PENDING_PAYMENT` CAS 为 `AWAITING_HANDOFF` 并写入已付金额，必须恰好影响一行；
3. 保存终态原始响应；
4. 写入唯一的 `PAYMENT_SUCCEEDED` Outbox 事件。

任一步骤失败均回滚。如果外部 provider 已成功但本地事务无法提交，本地不得伪造成功；保留或恢复为可查询对账的非终态，由 query/reconcile 收敛。

provider 返回 `FAILED` 时，以相同 fencing 原子写支付失败、终态响应和唯一 Outbox；不得改变订单为待交付。

重复或乱序 callback 保持幂等成功响应，但条件更新影响零行时不得生成重复 Outbox 或覆盖终态响应。

## 退款终态事务

退款 create 仍遵守“最多一次 provider refund create；未知结果只 query”的同类不变量。即时或对账得到：

- `SUCCEEDED`：原子推进退款终态、把已预占额度结转为已退款额度、写终态响应和唯一 `REFUND_SUCCEEDED` Outbox；
- `FAILED`：原子推进退款终态、释放预占额度、写终态响应和唯一 `REFUND_FAILED` Outbox。

provider reference 或金额不匹配时不推进任何聚合，不写终态 Outbox，保留为可调查/重试查询的状态。

## API 与错误语义

- 同一幂等键、相同请求体：返回已经持久化的原始状态和 UTF-8 响应，不触发第二次 create。
- 同一幂等键、不同请求体：返回 409。
- `UNKNOWN`：返回已持久化的 UNKNOWN 响应；后台 query/reconcile 负责收敛。
- provider 或依赖不可用且结果未知：返回 503；不能借此重新创建。
- 缺失请求体或空白幂等键：保持固定 UTF-8 JSON 400。

## 验收测试

测试必须使用真实 MySQL 8.4、HTTP 模拟 provider 和 Testcontainers，不允许跳过，并至少证明：

1. 首次 provider create 超时后记录为 `UNKNOWN`；租约到期后的 API 重试与 scheduler 接管均使 provider create 调用总数保持 1。
2. 首次慢请求与 scheduler 交叠时，迟到响应不能覆盖 scheduler 已提交的终态响应。
3. 并发相同幂等请求只产生一次 provider create HTTP 调用并重放相同响应字节。
4. provider 即时 `SUCCEEDED` 与 `FAILED` 的支付聚合、订单状态、响应和 Outbox 全部正确；强制订单 CAS 为零时整个终态事务回滚。
5. provider 即时 `SUCCEEDED` 与 `FAILED` 的退款额度结转/释放、响应和 Outbox 全部正确；强制聚合 CAS 为零时整个事务回滚。
6. 退款错误 provider reference 或金额不推进聚合且不产生终态 Outbox。
7. 包含中文、引号、反斜杠和 Unicode 转义的原始 JSON 参与指纹；同字节重放、不同字节冲突。
8. `PaymentFlowIT` 与 `PaymentGatewayContractIT` 合计结果为 0 failures、0 errors、0 skipped，并运行相关单元测试与 `git diff --check`。

## 非目标与兼容边界

- 不接入真实支付账户、商户证书或密钥。
- 不实现支付撤销、refresh token、分布式事务协调器或新消息中间件。
- 保持现有 `PaymentGateway` 端口，使未来真实支付适配器复用相同 create/query/callback 契约。
- 不改变三天验收、七天试用、部分退货、卖家质保和有限仲裁的业务规则。
