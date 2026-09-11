# 校园二手交易平台实验

这是一个基于 Spring Boot 3、JDK 17 的模块化单体实验，完成校园邮箱注册、批量库存、一口价订单、模拟支付、当面交付、争议与部分退货退款、评价，以及结算后的卖家质保义务。实验重点不是页面功能，而是用数据库事实、幂等命令、租约 fencing、可靠消息和可恢复外部协作证明交易正确性。

当前总状态为“进行中”：主体交易闭环和当前测试基线已经通过，但设计中的 `7.1` 聊天、`7.2` 竞价、`7.3` 跑腿/代取及真实支付适配器尚未完成，因此暂不把整个实验七标记为“已验收”。

## 你将运行到的能力

- 校园邮箱验证码、注册、登录和 15 分钟 JWT；CAS 只保留适配端口，不冒充学校正式身份认证。
- 商品草稿、私有媒体、发布/下架、批量库存和库存流水；买家不能购买自己的商品。
- 单发布项、多数量订单；下单、取消等命令使用 `Idempotency-Key`，同键异参返回 409。
- 模拟支付、签名回调、一次性 nonce、`UNKNOWN` 主动对账、退款额度预占和单次支付尝试。
- 当面交付、买家确认、三天验收、七天试用、普通争议、可信退回证明和隔离库存。
- 30/90/180/365 天卖家质保、厂家质保快照、结算后卖家义务、未来结算抵扣和发布/提现限制。
- MySQL 事务 Outbox、RabbitMQ publisher confirm、Inbox 幂等、人工失败副本、租约接管和 claim-token fencing。
- SmartCN Elasticsearch 搜索、外部版本、tombstone、在线重建和失败收敛。
- MinIO 私有对象、真实类型与实际读取上限、案件 ACL、持久清理任务和故障恢复。
- 审计字段过滤、低基数 Micrometer 指标、真实 HTTP 旅程与三轮故障演练。

## 模块和事实边界

```text
身份/邮箱 ──> 商品/库存 ──> 订单 ──> 支付/退款 ──> 交付与争议
     │             │          │           │             │
     └─────────────┴──────────┴── Outbox ─┴── Inbox ────┘
                                  │
                 Elasticsearch（可重建） / MinIO（私有对象）
```

| 模块 | 主要职责 | 代码入口 |
|---|---|---|
| `identity` | 邮箱验证码、注册登录、JWT、CAS 端口 | `AuthController`、`EmailVerificationService`、`ExternalIdentityProvider` |
| `catalog` | 商品、库存、媒体、搜索与重建 | `ListingService`、`JdbcInventoryRepository`、`SearchRebuildService` |
| `order` | 幂等下单、订单状态机、截止任务 | `CreateOrderService`、`OrderLifecycleService`、`DeadlineScheduler` |
| `payment` | 模拟网关、回调、退款、对账与结算 | `PaymentService`、`RefundService`、`PaymentReconciliationScheduler` |
| `dispute` | 交付、争议、证据、退回与硬期限 | `HandoffService`、`DisputeService`、`ReturnResolutionService` |
| `warranty` | 延长质保、裁定、卖家义务与限制 | `WarrantyService`、`SellerObligationService` |
| `messaging` | Outbox/Inbox、RabbitMQ、人工失败 | `OutboxDispatcher`、`ReliableEventConsumer` |
| `storage` | 私有对象、上传绑定、清理任务 | `ObjectUploadCoordinator`、`StorageCleanupScheduler` |
| `observability` | 安全审计、指标与提交后计数 | `AuditRecorder`、`CampusMetrics` |

MySQL 是订单、库存、金额、截止时间和在售集合的事实源。Redis 只用于验证码和限流，不能参与交易正确性证明；Elasticsearch 是可重建读模型；MinIO 只保存私有对象，逻辑授权仍由 MySQL 记录决定。模块之间通过公开服务和逻辑 ID 协作，不把其他模块的内部表当作公共 API。

## 环境和启动

前置条件：

- JDK 17；使用仓库自带 Maven Wrapper，不依赖全局 Maven。
- Docker Desktop，Docker Engine 已启动。
- 完整验收前至少保留 1 GiB 可用内存，并关闭无关容器和并行构建。

复制 `.env.example` 为 `.env`，仅填写本地值，不提交 `.env`。Compose 固定提供 MySQL 8.4、Redis 7.4、RabbitMQ 3.13、安装 SmartCN 的 Elasticsearch 8.18.8、MinIO 和 Toxiproxy：

```powershell
docker info
docker compose up -d mysql redis rabbitmq elasticsearch minio toxiproxy
docker compose ps
docker compose down
```

应用可在依赖健康后启动：

```powershell
.\mvnw.cmd spring-boot:run
```

测试使用隔离的 Testcontainers，不能用本机历史服务或 skipped 结果代替真实外部协作验证。

## 主要 HTTP 接口

| 领域 | 接口 |
|---|---|
| 身份 | `POST /api/auth/email-verifications`、`POST /api/auth/register`、`POST /api/auth/login` |
| 商品 | `POST /api/listings`、`POST /api/listings/{id}/media`、`POST /api/listings/{id}/publish`、`POST /api/listings/{id}/off-sale` |
| 搜索 | `GET /api/search` 或 `GET /api/listings/search` |
| 订单 | `POST /api/orders`，以及取消、交付、确认收货等订单命令 |
| 支付 | 订单支付、支付/退款 webhook、支付对账与结算查询 |
| 争议 | 创建/响应/分配/裁决争议、上传/读取证据、退回确认 |
| 质保 | 创建/响应/裁决质保案件、证据、卖家义务筹资与限制查询 |
| 评价 | `POST /api/orders/{orderId}/reviews` |

验证码示例：

```http
POST /api/auth/email-verifications
Content-Type: application/json; charset=UTF-8

{"email":"buyer@stu.example.edu.cn"}
```

本地 `LocalVerificationMailSender` 只保存最近验证码，不写入日志。注册后使用 `/api/auth/login` 获取 JWT；业务接口携带 `Authorization: Bearer <token>`。商品搜索示例为 `GET /api/search?keyword=Java&size=20`，后续页使用响应中的 `nextSearchAfter`。

要求幂等的写命令必须带 `Idempotency-Key`。同一键和相同请求摘要重放原始 UTF-8 终态响应；同键异参返回 409，失败事务不能遗留阻塞性幂等记录。所有金额均是人民币整数分并保存为 `BIGINT`，禁止浮点金额。

## 订单、支付和售后

```text
PENDING_PAYMENT -> AWAITING_HANDOFF -> AWAITING_RECEIPT
       │                 │                    │
   CANCELLED       REFUNDING_CANCEL      AFTERSALE_WINDOW
                                              │
                                 REFUNDED / SETTLED
```

- 支付窗口 15 分钟、卖家交付 72 小时、买家确认 48 小时；确认时以数据库时间写入 `T0`。
- 验收期 72 小时；七天试用期包含验收期，采用左闭右开边界，到达截止时刻即过期。
- 支付和退款的外部结果可以是 `UNKNOWN`，此时保留 provider reference 与幂等键，通过查询原请求收敛，不能盲目重建请求。
- 退款先占额，`successful_refund_fen + reserved_refund_fen` 始终不得超过实付金额。
- 退货退款必须有卖家确认、已验签物流回调或管理员确认之一；只提交单号、图片或视频不等于已退回。
- 部分退货进入隔离库存，由卖家明确重新上架或报损；仅退款不增加库存。
- 管理员硬期限到期但证据冲突或缺少可信退回证明时进入 `ESCALATED`，资金继续冻结，系统不默认判任一方获胜。

卖家质保可选 30/90/180/365 天且包含平台七天试用期；厂家质保是独立凭证和到期日。订单结算后保持 `SETTLED`，质保案件独立流转。维修补偿或退货补偿建立卖家义务；逾期会限制发布/提现，后续结算按唯一业务键抵扣，足额筹资后幂等解除限制。

## 私有媒体和证据

- 商品媒体允许 JPEG、PNG、WebP，单文件实际读取上限 10 MiB。
- 争议/质保证据还允许 PDF 20 MiB、MP4 100 MiB。
- 文件签名和 Apache Tika 共同验证真实类型，不信任扩展名或客户端声明。
- Object Key 随机生成，不包含用户、订单、案件或内容哈希。
- 证据只对案件买方、卖方和被分配管理员可见；管理员角色不会自动绕过案件 ACL。
- 不存在与无权访问统一返回 404；存储故障映射为 503。

对象上传由短数据库事务、事务外 MinIO IO 和持久清理任务协作。绑定失败、客户端中断或即时删除失败后，由带 owner/claim token 的任务继续收敛；数据库事务期间不执行 MinIO IO。

## 可靠事件、搜索重建和恢复

业务事实与 `integration_outbox` 在同一事务提交。dispatcher 以 `NEW -> PUBLISHING`、数据库时间、owner、租约和 claim token 领取；只有 publisher confirm 成功才标记发布。消费者按 `(consumerName, eventId)` 领取，业务修改、Inbox `COMPLETED` 和派生 Outbox 在同一事务提交，提交前不 ACK。

商品搜索使用 SmartCN、`ON_SALE` 固定过滤和稳定游标。投影使用数据库聚合版本，删除写 tombstone。在线重建执行可重复读快照、高水位补放、短暂写入门禁、最终校验和读写别名原子切换；切换结果不确定时保留 pause/intent，由恢复器根据真实别名目标收敛。

`RecoveryDrillIT` 分别执行 RabbitMQ、Elasticsearch、MinIO 故障核心阶段；`RecoveryInvariantStagesIT` 为每轮补充真实 HTTP 证据 ACL 和 MySQL/SmartCN Elasticsearch 在售集合核对。Rabbit publish 是提交后的异步边界：HTTP 成功后用持久 Outbox 积压、attempt 和 retry 指标证明故障，不虚构业务 HTTP 503。

## 验证与验收证据

必须在本目录、JDK 17 下执行：

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
git diff --check
```

需要定点复跑真实旅程或故障演练时：

```powershell
.\mvnw.cmd -Dit.test=CampusMarketJourneyIT verify
.\mvnw.cmd -Dit.test=RecoveryDrillIT verify
```

低内存主机必须串行执行，等待上一条命令完全结束且容器回收后再运行下一条。共享 Testcontainers 测试默认不自动启动 Rabbit listener、搜索调度器和各业务截止任务；验证调度的测试直接调用对应 `runOnce`，需要真实 Rabbit 投递的测试使用自己的监听器容器，避免已结束上下文污染后续 Outbox、队列和租约。

截至 2026-09-11，验收基线为：Surefire 137 项；完整 `verify` 中 Failsafe/Testcontainers 251 项；均为 0 failures、0 errors、0 skipped。完整 `verify` 同时覆盖真实 HTTP 旅程和三轮故障恢复，不接受 Docker 不可用、外部测试跳过或 SmartCN 未实际加载。

## 已知边界和扩展实验

- 校园邮箱只证明邮箱控制权；正式 CAS 尚未获得校方授权，当前仅有 `ExternalIdentityProvider` 端口。
- 支付网关是签名、幂等、回调与对账契约下的模拟适配器；真实支付、商户资质和真实退款未接入。
- 首版是校园当面交付；物流单号不构成可信退回证明，真实物流验签适配器未接入。
- MP4 不做转码、缩略图、内容审核或真实性鉴定；争议保持单轮管理员裁决，不支持 AI 自动资金仲裁。

原始设计已经规划扩展路线；`7.1` 聊天正在进行架构设计，其余扩展仍停留在路线规划。当前没有任何扩展完成实现和验收：

1. `7.1` 聊天：WebSocket、会话权限、离线消息和未读计数。
2. `7.2` 竞价：并发出价、截止成交、反悔规则和竞价审计。
3. `7.3` 跑腿/代取：独立 `ServiceOrder`，不复用商品订单状态机。
4. 真实支付适配器：取得资质、沙箱/生产账号和批准后，再实现现有 `PaymentGateway` 契约。

因此实验七当前总状态为“进行中”：主体实验已通过当前验收，`7.1` 聊天处于本地架构设计阶段，其余扩展仅有路线规划，所有扩展均未完成。任何扩展都必须另行设计、计划、分支和验收，不能以空代码提前占位；扩展全部完成并验收前，不把整个实验七改为“已验收”。更多故障症状和恢复动作见 [TROUBLESHOOTING.md](TROUBLESHOOTING.md)。
