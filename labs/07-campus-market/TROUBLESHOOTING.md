# 排障手册

本手册按“症状—检查—恢复—不可越过的边界”记录实验七的真实排障路径。先确认失败发生在哪个事实边界，再做恢复；不要通过跳过测试、手工篡改状态或放宽 ACL 获得表面成功。

## 1. Docker 不可用或宿主机内存不足

**症状：** Testcontainers 无法连接 Docker、容器启动超时、进程被异常终止，或多个重型容器同时启动后系统持续换页。

**检查与恢复：**

```powershell
docker info
docker ps -a
```

确认 Docker Desktop 的 Linux Engine 可用，关闭无关容器和并行 Maven/IDE 构建。可用内存低于 1 GiB 时停止测试，释放资源后串行重试。`CampusMarketJourneyIT`、`RecoveryDrillIT` 不要并行执行；每个阶段退出并确认容器回收后再运行下一段。

**边界：** Docker 不可用、外部测试 skipped、用本机历史服务替代 Testcontainers 都不算通过。

## 2. SmartCN analyzer 不存在或搜索结果异常

**症状：** Elasticsearch 创建索引时报 analyzer 不存在，中文被拆成错误 token，或 MySQL 在售集合与搜索结果不一致。

**检查与恢复：** SmartCN 镜像由 `docker/elasticsearch/Dockerfile` 安装 `analysis-smartcn`。重建镜像，确认插件已加载，清理旧测试索引后重试。检查 `search_outbox` 积压、别名目标、聚合版本和 tombstone；普通失败发生在别名切换前时可重新运行重建，切换已尝试且目标不确定时必须保留 pause/intent 交给恢复器判断。

**边界：** 只在配置中写 `smartcn` 不算验证；必须由真实 `_analyze`/搜索集成测试证明中文 token、`ON_SALE` 过滤和集合收敛。不能盲目重新绑定分裂别名。

## 3. RabbitMQ confirm、Outbox 或人工失败异常

**症状：** Outbox 长期停留在 `PUBLISHING`、publisher NACK/不可路由/超时，或三次失败后未形成可追踪的人工失败事实。

**检查与恢复：** 检查 `integration_outbox.status/lease_until/claim_token/attempt_count`、Rabbit exchange/queue/binding 和人工失败表。恢复 RabbitMQ 后运行 `OutboxDispatcher.dispatchOnce`；过期租约由新 owner 接管，旧 owner 的迟到 ACK/NACK 只能更新 0 行。只有 publisher confirm 成功才允许标记 `PUBLISHED`。

**边界：** 不要手工把 Outbox 改成 `PUBLISHED`，也不要无限重试。无法自动恢复的协议或业务错误进入受控人工路径。

## 4. 单测通过但完整 `verify` 的消息断言成组错位

**症状：** `ReliableMessagingIT` 单独通过，但完整套件出现领取数量、`attempt_count`、人工失败或终态断言一起偏移。

**检查与恢复：**

- 检查 `integration_outbox` 是否残留支付、退款或质保事件；无类型过滤的 `claimBatch` 可能先领取它们。
- 检查 `consumed_event`、`manual_failure`、主事件队列和人工队列是否在每例前同步清理。
- 检查旧 Spring 上下文中的 Rabbit listener、搜索或截止调度器是否仍在抢占 Outbox、队列和租约。
- 定点运行 `.\mvnw.cmd '-Dit.test=PaymentFlowIT,ReliableMessagingIT' verify`；组合失败而单类成功通常说明跨类污染。

共享测试基类应关闭默认 Rabbit listener 与搜索调度器，并用较大的 `initial-delay-ms` 延后需要保留 Bean 的截止/对账任务。不要统一覆盖为 `enabled=false`，否则测试级动态属性可能让需直接注入调度器的上下文无法创建。

## 5. Redis 验证码失败或出现绕过风险

**症状：** 验证码接口返回 503、验证码无法消费、限流结果异常，或 Redis 故障后业务仍继续注册。

**检查与恢复：** 检查 Redis URL、Lua 脚本、验证码 HMAC 和设备 Cookie 签名。10 分钟发送/失败窗口分别受邮箱、IP 和设备上限保护。恢复 Redis 后重新发起验证码流程。

**边界：** Redis 不可用必须失败关闭，不能绕过身份验证；验证码只允许本地模拟发送器保存最近值，禁止写日志或 Git。

## 6. MinIO 上传、读取或持久清理失败

**症状：** 上传/读取返回 503、绑定失败后残留对象、清理任务反复重试，或 MP4 只看 multipart 元数据就被错误接受。

**检查与恢复：** 确认 MinIO endpoint、bucket、凭据和 Toxiproxy 路由。检查 `object_upload_session`、`storage_cleanup_task.status/lease_until/claim_token`；恢复后运行 `StorageCleanupScheduler.runOnce(100)`。对象不存在按幂等成功处理，旧 claim token 的迟到完成应更新 0 行。

**边界：** 数据库事务中禁止执行 MinIO IO；Object Key 不得写日志。图片/PDF/MP4 上限分别按实际读取字节 10/20/100 MiB 验证，不能信任文件名、扩展名或客户端类型。

## 7. 私有证据 ACL 或 404 行为不一致

**症状：** 非案件参与者能读证据、管理员角色自动读到未分配案件，或响应通过 403/错误文本泄漏资源存在性。

**检查与恢复：** 核对案件买方、卖方、已分配管理员与 evidence 绑定；每次物理读取前重新执行逻辑 ACL。对已认证调用者，不存在、已删除和无权统一返回同构 404；存储不可用返回 503。

**边界：** 管理员不自动绕过 ACL。不能用预签名 URL、Object Key 或数据库直读规避授权。

## 8. 支付/退款回调验签失败或状态为 `UNKNOWN`

**症状：** webhook 被拒绝、重复回调产生重复效果，或支付/退款停留在 `UNKNOWN`。

**检查与恢复：** 回调签名覆盖原始 UTF-8 body，并校验时间戳、一次性 nonce、受控字段、provider event ID 和金额。重放 nonce、过期时间、未知字段、非法状态或金额不匹配必须拒绝。`UNKNOWN` 时保留原 provider reference 与幂等键，运行 `PaymentReconciliationScheduler.runOnce` 查询原请求结果。

**边界：** `UNKNOWN` 不能重新创建支付/退款请求；真实支付未接入，模拟适配器只证明 `PaymentGateway` 契约。

## 9. 退款额度预占没有收敛

**症状：** 并发退款总额可能超过实付金额，退款回调成功后占额未释放，或退货退款完成但隔离库存未更新。

**检查与恢复：** 核对成功支付、`successful_refund_fen`、`reserved_refund_fen`、退款唯一业务键和回调事件。所有退款必须先在数据库事务中占额，再调用提供方；成功/失败/未知结果按同一 reference 收敛。回调后运行退回收敛器，确认批准数量只进入一次隔离库存。

**边界：** `成功退款 + 已占额 <= 实付金额` 是硬约束；不能通过先外部退款、后补数据库规避并发控制。

## 10. 截止时间边界和用户命令发生竞态

**症状：** 支付、交付、确认、争议、结算或质保命令在截止时刻出现双终态，测试偶发一条路径重复成功。

**检查与恢复：** 所有窗口使用 MySQL `CURRENT_TIMESTAMP(6)` 和左闭右开语义：支付 15 分钟、交付 72 小时、确认 48 小时、验收 72 小时、试用 7 天。卖家质保为订单快照中的 30/90/180/365 天。核对状态条件更新、订单行锁、截止 claim token 和状态历史；不要以 JVM `Clock` 或 Redis 锁替代数据库裁决。

**边界：** `databaseNow < deadline` 才允许提交，到达截止时刻即过期；并发双方只能有一条合法路径获胜。

## 11. 退回证明不足或案件进入 `ESCALATED`

**症状：** 买家上传了图片、视频或说明，但系统不退款；卖家 72 小时未确认；管理员硬期限到期后案件进入 `ESCALATED`。

**检查与恢复：** 普通证据只证明“提交了材料”，不等于可信退回。退款必须取得卖家确认、未来已验签物流签收回调或管理员明确确认。补足证明并运行 `ReturnResolutionScheduler`；证据冲突、证明不足或支付失败时保留冻结资金和高优先级人工队列。

**边界：** 当前为校园当面退回，不保存或依赖物流单号。硬期限不能自动判买方或卖方获胜。

## 12. 卖家质保筹资失败或限制未解除

**症状：** 质保裁定后卖家无法发布/提现、筹资幂等冲突、未来结算重复抵扣，或足额后限制仍存在。

**检查与恢复：** 核对 `seller_obligation` 的应履行额、已筹额、72 小时期限、唯一抵扣键、限制状态和版本。卖家使用 `/api/seller/obligations/{id}/fund` 与 `Idempotency-Key` 主动筹资；未来结算只按唯一业务键抵扣一次，足额后幂等解除限制。

**边界：** 平台不垫付结算后质保资金，订单也不能退回 `PENDING`；逾期未足额时限制必须保留。

## 13. 全量验证和最终证据

在实验目录、JDK 17 下执行：

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
git diff --check
```

常用定点命令：

```powershell
.\mvnw.cmd '-Dit.test=PaymentFlowIT,ReliableMessagingIT' verify
.\mvnw.cmd -Dit.test=CampusMarketJourneyIT verify
.\mvnw.cmd -Dit.test=RecoveryDrillIT verify
```

`CampusMarketJourneyIT` 依次运行教材旅程（MySQL、Redis、SmartCN Elasticsearch）和质保旅程（MySQL、Redis、MinIO）。`RecoveryDrillIT` 依次运行 RabbitMQ、Elasticsearch、MinIO 三个核心阶段；`RecoveryInvariantStagesIT` 补充每轮 ACL 与搜索集合阶段。每轮都必须看到故障证据，并在恢复后证明库存非负、退款未超额、单次结算、无过期未决租约、证据 ACL 未放宽、MySQL 与搜索在售集合一致。

截至 2026-09-11，最终基线为 Surefire 137 项、Failsafe/Testcontainers 251 项，0 failures、0 errors、0 skipped。任何 skipped、Docker 失联、SmartCN 未加载、模拟支付契约未执行、竞态未运行或恢复未收敛，都不能记为完整验收。
