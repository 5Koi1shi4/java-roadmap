# 实验七：校园二手交易平台设计

## 1. 背景与目标

阶段七建设一个独立、可运行、可验证的校园二手交易平台。首版聚焦一口价商品交易，从校园邮箱注册、商品发布与搜索、批量库存下单、模拟支付、当面交付、售后争议、部分退货退款到交易评价形成完整闭环，并为后续真实支付和实验八的 Spring Cloud 渐进拆分保留稳定边界。

实验覆盖以下学习目标：

- 使用精简 JWT 身份层完成注册、登录、签发与解析，并以校园邮箱验证码限定校园社区成员。
- 使用模块化单体划分身份、商品库存、订单、支付结算、履约仲裁、评价和审计边界。
- 使用 MySQL 条件更新、数据库时间、版本号、租约和 claim token 处理库存、截止时间与用户命令竞态。
- 使用整数分表示人民币金额，原子约束累计退款不超过实付金额。
- 使用事务 Outbox、RabbitMQ publisher confirm 和消费者 Inbox 完成可靠异步协作。
- 使用 Elasticsearch 提供中文商品搜索，以 MySQL 为事实源并支持索引重建。
- 使用 MinIO 保存私有商品图片和仲裁证据，所有物理文件访问必须先经过逻辑权限检查。
- 使用模拟支付网关验证创建支付、异步回调、退款、重复通知、签名、重放防护和主动对账。
- 使用单轮有限仲裁处理卖家不回应、部分退货退款和可信退回证明。
- 使用单元测试、Testcontainers、Toxiproxy、真实 HTTP 和连续故障演练形成验收闭环。

首版不实现聊天、竞价、跑腿/代取、优惠券、多商品购物车、多轮申诉、AI 自动判责、视频真实性鉴定、真实物流和真实资金转移。聊天、竞价和跑腿/代取在首版验收后作为独立扩展实施；实验八只拆分已经稳定的业务模块，不同时扩大业务范围。

## 2. 仓库与技术基线

- 分支：`learning/campus-market`
- 目录：`labs/07-campus-market/`
- 活动树：只包含根 `.gitignore` 与本实验目录，不加入其他实验或 `main` 文档。
- JDK：17
- Spring Boot：3.5.x，实施计划固定精确补丁版本。
- MySQL：8.4 LTS
- Redis：7.x
- RabbitMQ：3.x
- Elasticsearch：8.18.8，安装 SmartCN 插件。
- MinIO：Compose 与 Testcontainers 使用同一固定版本。
- 测试：JUnit 5、AssertJ、Mockito、Testcontainers、Toxiproxy。

应用采用 Spring Web、Spring Security、Spring JDBC、Flyway、Spring Data Redis、Spring AMQP、Actuator、Micrometer、Elasticsearch Java Client、MinIO Java SDK、Apache Tika 和 JJWT。所有第三方依赖、容器镜像和 Maven Wrapper 版本在实施计划中固定，不使用 `latest`。

## 3. 总体架构与模块边界

实验采用模块化单体。所有模块运行在一个 Spring Boot 进程和一个 MySQL 实例中，但每个模块拥有自己的领域对象、应用用例、持久化表和公开接口。模块禁止直接使用其他模块的 Repository 或修改其他模块的表。

核心模块为：

- 身份与校园验证：用户、校园邮箱验证、密码、JWT 和未来外部身份绑定。
- 商品与库存：商品草稿、媒体、发布、搜索同步、可售/隔离库存和库存流水。
- 交易订单：订单快照、数量、截止时间、状态机、取消和幂等命令。
- 支付与结算：支付单、退款单、回调事件、金额预占、对账和净额结算。
- 当面履约与有限仲裁：交付、确认收货、争议、答辩、证据、退回和管理员裁决。
- 评价：完成并结算后的买卖双方评价。
- 审计与可观测性：安全操作审计、状态历史、指标和结构化日志。

同步业务操作通过模块公开的应用接口协作，并可在同一本地数据库事务中完成订单、库存、状态历史和 Outbox 的原子提交。外部 IO、异步通知、搜索同步和支付网关调用不在数据库事务中执行。

实验八按上述模块边界逐步拆分。首版不为尚未实现的聊天、竞价或跑腿创建空表、空接口或占位实现。

## 4. 身份与校园社区边界

### 4.1 校园邮箱注册

平台身份含注册、登录、BCrypt 密码和 15 分钟 access JWT，不实现 refresh token、Token 黑名单或独立认证服务。

允许的校园邮箱域名由配置提供并进行规范化后的精确匹配，不使用字符串后缀判断。域名比较不区分 ASCII 大小写；国际化域名先转换为规范 ASCII 形式。只检查域名不能激活账号，用户必须消费发送到该邮箱的一次性验证码。

验证码规则：

- 使用密码学安全随机源生成。
- 有效期 10 分钟。
- 持久化或缓存中只保存带服务端密钥的 HMAC，不保存明文验证码。
- 同一邮箱、IP 和设备维度执行发送频率与验证失败次数限制。
- 验证以原子消费保证同一验证码只能成功一次。
- 开发和测试使用模拟邮件发送端口；生产配置没有邮件实现时启动失败。

该机制只证明用户当前控制允许域名下的邮箱。平台称其为“经校园邮箱验证的校园社区成员”，不宣称实时在读学籍或实名身份。

### 4.2 外部统一身份认证

应用层预留 `ExternalIdentityProvider` 和内部用户绑定模型，未来可增加 OIDC、CAS 或 SAML 适配器。真实校方统一认证只有在校方登记客户端、批准回调地址与字段范围并完成个人信息合规评估后才能启用。

已通过学校 WebVPN 的公开浏览器跳转确认现有统一认证采用 CAS 或 CAS 兼容流程：登录入口携带 `service`，成功后业务回调短暂携带一次性 `ticket`，随后跳转到不含 Ticket 的地址。该观察只用于确定优先实现 `CasExternalIdentityProvider`，不保存真实学校域名、Ticket、Cookie 或用户凭证，也不代表校园交易平台已经获得接入授权。

CAS Service Ticket 与登记的 `service` 绑定且只能消费一次。校园交易平台不能复用 WebVPN 的 Ticket，必须由校方单独登记精确 HTTPS Service 回调地址，并提供允许使用的登录、Ticket 校验端点和最小用户属性范围。

首版不保存真实学校端点、client secret、学生数据或校方证书，也不启用真实 SSO。未来外部身份最终映射到内部不可预测 user ID，商品、订单、支付和仲裁模块不读取校方协议字段。

### 4.3 授权语义

- 未认证请求返回 401。
- 已认证但无权执行公开可判断的角色操作返回 403。
- 订单、争议、证据等私有资源对非参与者统一返回 404，不暴露存在性。
- 普通用户可同时作为买家和卖家；管理员只拥有仲裁和受控运维权限，不自动绕过商品图片或仲裁证据 ACL。
- 卖家不能购买自己的商品。

## 5. 商品、媒体、库存与搜索

### 5.1 商品发布

一个发布项表示一种价格、版本和成色基本一致的商品，允许正整数库存。卖家拥有多件相同教材时只需发布一次，买家单笔可购买 `1～当前可售库存`。

商品状态为 `DRAFT`、`ON_SALE`、`SOLD_OUT` 和 `OFF_SALE`。商品只有在标题、描述、分类、人民币单价、正数库存和至少一张有效图片齐全后才能发布。库存归零时进入 `SOLD_OUT`；卖家补充可售库存后可重新进入 `ON_SALE`。卖家下架不影响已创建订单。

商品图片允许 JPEG、PNG 和 WebP，每张实际读取上限 10 MiB，每个发布项最多 9 张。服务使用文件签名和 Apache Tika 检查真实类型，不信任扩展名、Multipart 声明长度或客户端 `Content-Type`。对象 Key 使用安全随机值，不包含用户 ID、商品 ID、原文件名或内容哈希。

### 5.2 库存模型

`listing` 保存 `available_quantity`、`quarantined_quantity` 和版本号；所有数量为非负整数。`inventory_movement` 以唯一业务键记录扣减、取消返还、退回隔离、重新上架和报损，重复命令不得产生第二条等效库存变化。

创建订单使用带库存条件的更新，禁止先查库存再写回：

```sql
UPDATE listing
SET available_quantity = available_quantity - :quantity,
    version = version + 1
WHERE id = :listingId
  AND status = 'ON_SALE'
  AND available_quantity >= :quantity;
```

订单创建、库存扣减、库存流水和订单 Outbox 在同一事务中提交。影响行数不是 1 时返回库存冲突，不创建订单或幂等成功记录。

未支付取消或超时关闭返还全部数量。已支付但交付前取消或卖家交付超时，在订单进入退款路径的同一事务返还全部数量并写退款 Outbox；网关退款失败不允许订单重新占用库存。

退货数量先进入隔离库存，不自动恢复可售。卖家检查后显式选择重新上架或报损。仅退款不增加任何库存。

### 5.3 搜索

MySQL 是商品事实源，Elasticsearch 只保存可重建索引。商品发布、修改、下架、售罄和补库存与 `search_outbox` 在同一事务中提交。搜索只返回 `ON_SALE` 且可售库存大于零的商品，支持 SmartCN 中文查询、分类过滤、价格范围和稳定分页。

索引更新使用聚合版本和外部版本语义收敛重复、乱序事件。在线重建沿用一致性快照、高水位补放、写入门禁和原子别名切换，不把索引当作库存判断依据。

## 6. 订单与截止时间状态机

### 6.1 状态

订单状态为：

- `PENDING_PAYMENT`：库存已扣减，等待支付。
- `AWAITING_HANDOFF`：支付成功，等待卖家当面交付。
- `AWAITING_RECEIPT`：卖家已标记交付，等待买家确认或系统确认。
- `AFTERSALE_WINDOW`：已确认收货，但仍允许在售后窗口内发起争议。
- `DISPUTED`：存在活动争议，结算被冻结。
- `REFUNDING_CANCEL`：交付前取消，等待退款结果。
- `CANCELLED`：未支付订单关闭终态。
- `REFUNDED`：订单全额退款终态。
- `SETTLED`：售后窗口结束、没有活动争议且净额完成结算的终态。

允许的主要迁移为：

```text
PENDING_PAYMENT
  → AWAITING_HANDOFF
  → CANCELLED

AWAITING_HANDOFF
  → AWAITING_RECEIPT
  → REFUNDING_CANCEL → REFUNDED

AWAITING_RECEIPT
  → AFTERSALE_WINDOW
  → DISPUTED

AFTERSALE_WINDOW
  → DISPUTED
  → SETTLED

DISPUTED
  → AFTERSALE_WINDOW（驳回或部分退款完成后仍有剩余净额）
  → REFUNDED（全额退款完成）
```

`SETTLED`、`CANCELLED` 和 `REFUNDED` 是普通业务不可逆终态。交易协议明确：`SETTLED` 后不再受理普通商品描述争议，法律规定不能排除的责任除外。

### 6.2 固定默认期限

- 待支付：15 分钟。
- 卖家交付：支付成功后 72 小时。
- 买家确认收货：卖家确认交付后 48 小时。
- 售后窗口：确认收货后 72 小时。
- 卖家确认退回：买家提交退回证据后 72 小时。
- 管理员处理 SLA：进入管理员复核后 7 天。
- 管理员硬期限：进入管理员复核后 14 天。

测试可缩短期限，但不能改变边界包含关系或安全规则。所有截止时间、领取和过期判断使用 MySQL 时间。

### 6.3 截止时间竞态

同一状态的用户命令和超时任务必须严格串行化。正确性由 MySQL 状态条件、版本号、行锁和事务保证；Redis 锁只能减少热点竞争，不是正确性前提，也不与数据库组成所谓“双锁”。

卖家确认交付要求：

```text
status = AWAITING_HANDOFF AND databaseNow < handoffDeadline
```

交付超时领取要求：

```text
status = AWAITING_HANDOFF AND handoffDeadline <= databaseNow
```

在截止时刻之前成功取得数据库条件的卖家命令获胜；到达截止时刻后超时规则获胜。调度器使用有界批量、租约、owner 和 claim token 领取；旧 owner 的迟到完成不得覆盖新租约。

买家不能在 `AWAITING_HANDOFF` 状态确认收货，因此“买家确认收货”和“卖家交付超时”不构成合法的同状态竞态。

## 7. 支付、退款与结算

### 7.1 金额模型

首版币种固定为人民币。所有金额使用整数分和数据库 `BIGINT`，不使用 `double`、`float` 或运行期临时舍入。领域层使用受约束的 `Money` 值对象，拒绝负数、溢出和非人民币金额。

首版没有运费、优惠券和跨商品订单，订单总额为：

```text
unitPriceFen × quantity
```

乘法使用溢出检查。部分退款额为：

```text
unitPriceFen × approvedQuantity
```

未来加入优惠、运费或多商品订单前，必须先定义确定性的优惠分摊、运费归属和尾差规则，不能沿用上述公式或临时采用四舍五入。

### 7.2 退款额度

支付模块分别保存成功退款金额和已预占但尚未完成的退款金额。创建退款必须以单条条件更新或锁行事务保证：

```text
successfulRefundFen + reservedRefundFen + requestedRefundFen <= paidAmountFen
```

退款请求使用唯一业务幂等键，支付服务商的 refund reference 也唯一。退款成功回调将预占额转为成功额；明确失败且确认服务商不会继续成功时才释放预占额。未知结果进入主动查询和对账，禁止盲目重发新退款请求。

订单净结算额为实付金额减成功退款金额。只有售后窗口结束、没有活动争议、没有未决退款或对账异常时才能创建结算记录。

### 7.3 PaymentGateway 边界

`PaymentGateway` 提供：

- 创建支付。
- 查询支付。
- 申请退款。
- 查询退款。
- 使用原始请求体和 Headers 验签并解析支付/退款回调。
- 对账查询所需的 provider reference。

模拟网关实现与真实适配器共享同一契约测试。模拟控制端只在 `local` 或 `test` Profile 与显式开关同时满足时启用；非允许 Profile 误开启必须启动失败。

回调先执行签名、时间戳和重放检查，再以 `(provider, providerEventId)` 原子保存事件。重复回调返回幂等成功，不能再次修改订单、金额或库存。原始回调只在确有审计必要且完成敏感字段过滤后受控保存；日志不得记录签名、密钥、完整支付凭证或用户隐私数据。

商户号、证书和密钥全部从外部秘密配置加载。未来真实适配器必须通过签名篡改、重放、重复回调、超时、未知结果、退款和对账契约测试后才能启用，订单模块不因适配器替换而修改。

## 8. 当面履约、售后与有限仲裁

### 8.1 正常履约

支付成功后卖家在 72 小时内完成当面交付并确认。超过期限仍为 `AWAITING_HANDOFF` 时，系统把订单转为 `REFUNDING_CANCEL`，原子返还一次全部库存并写退款 Outbox。

卖家确认交付后，买家可在 48 小时内确认收货；期限届满且没有活动争议时系统确认收货并进入 72 小时售后窗口。售后窗口结束且没有未决事项才进入 `SETTLED`。

### 8.2 争议范围

买家可在 `AWAITING_RECEIPT` 或 `AFTERSALE_WINDOW` 发起争议。一个订单只包含一个发布项但可购买多件；争议指定正数 `disputedQuantity`，同一订单累计已裁决数量不得超过购买数量，同一数量不能被重复退款。

管理员单轮裁决仅允许：

- 驳回争议。
- 仅退款，可为部分或全部数量。
- 退货退款，可为部分或全部数量。

首版不支持部分比例金额、自由输入退款金额、多轮申诉或 AI 自动裁决。

### 8.3 证据

双方可提交文字和私有文件证据：

- JPEG、PNG、WebP：单文件实际读取上限 10 MiB。
- PDF：单文件实际读取上限 20 MiB。
- MP4：单文件实际读取上限 100 MiB。

类型通过文件签名和 Apache Tika 验证，不信任扩展名或客户端类型。MP4 不做转码、缩略图、内容审核或真实性鉴定。证据 Object Key 随机生成，不包含案件、用户、订单或内容哈希。

证据仅案件买方、卖方和被分配管理员可访问；物理读取前重新检查案件权限。无权与不存在统一返回 404。预签名 URL 最长 2 分钟，并在 README 明确签发后的残余授权窗口；高风险证据默认通过应用 `/content` 流式读取。

### 8.4 退回确认与硬期限

买家标记已退回或提交视频、图片、PDF、单号只代表提交证据，不触发退款。退款必须具备以下任一可信退回证明：

- 卖家明确确认收到退货。
- 未来物流适配器提供已验签的签收回调。
- 管理员在审计后明确确认退回。

首版是校园当面退回，不保存或依赖物流单号。若卖家在 72 小时内不确认，案件进入管理员复核。管理员处理 SLA 为 7 天，硬期限为 14 天。

硬期限届满时：

- 已有可信退回证明：系统可按裁决自动写退款 Outbox。
- 缺少可信证明或双方证据冲突：案件进入 `ESCALATED`，资金继续冻结，触发高优先级告警和人工队列；系统不得默认向任一方转移资金。

这项政策在公平性和终局速度之间优先保护资金安全。系统通过 SLA 指标、硬期限告警和人工队列避免静默挂起，但不假装能够自动判断伪造证据或退货丢失责任。

### 8.5 部分退货库存

退货退款成功后，批准退回数量进入隔离库存，不自动增加可售库存。卖家检查后显式选择重新上架或报损。仅退款表示商品不退回，因此不改变库存。

部分退款完成后，如果订单仍有未退款净额，则回到售后窗口并在所有争议关闭后结算剩余金额；全额退款后订单进入 `REFUNDED`。

## 9. 数据模型与所有权

### 9.1 身份模块

- `campus_user`：随机 user ID、规范邮箱、BCrypt 哈希、状态和时间戳。
- `email_verification`：验证码 HMAC、用途、状态、尝试次数和过期时间。
- `external_identity`：provider、subject、内部 user ID 和绑定状态。

### 9.2 商品库存模块

- `listing`：卖家、标题、描述、分类、单价分、可售/隔离数量、状态和版本。
- `listing_media`：逻辑媒体 ID、随机 Object Key、真实类型、大小和排序。
- `inventory_movement`：业务键、原因、数量变化、关联订单/争议和时间。
- `search_outbox`：商品聚合版本、事件类型、租约和发布状态。

### 9.3 订单模块

- `trade_order`：随机订单 ID、买卖双方、商品快照、单价分、数量、总额分、状态、各截止时间和版本。
- `order_command`：用户幂等键、请求摘要、结果状态与原始 UTF-8 终态响应。
- `order_transition`：追加式状态历史、actor、原因和数据库时间。
- `order_deadline_claim`：到期任务的 owner、claim token、租约和尝试信息。

### 9.4 支付结算模块

- `payment_order`：provider、支付幂等键、订单、应付/实付金额分、provider reference 和状态。
- `payment_callback_event`：provider event ID、受控摘要、验签结果和处理状态。
- `refund_order`：退款幂等键、争议或取消来源、金额分、预占状态、provider reference 和状态。
- `settlement`：订单实付、成功退款、净结算金额和结算状态。

### 9.5 履约仲裁模块

- `handoff_record`：交付确认 actor、数据库时间和受控说明。
- `dispute_case`：争议数量、理由、状态、卖家/管理员期限、裁决和版本。
- `dispute_evidence`：逻辑证据 ID、提交者、真实类型、大小、随机 Object Key 和时间。
- `return_case`：退回状态、可信证明类型、确认 actor、批准数量和期限。

### 9.6 评价、审计与消息

- `trade_review`：订单、评价人、被评价人、评分、受控文本和唯一约束。
- `audit_event`：actor、动作、资源逻辑 ID、结果、有限失败分类和数据库时间。
- `integration_outbox`：事件 ID、类型、聚合、版本、schema version、payload、租约和发布状态。
- `consumed_event`：消费者、事件 ID、处理状态、租约和完成时间。
- `object_upload_session`：随机上传会话、提交者、用途、随机 Object Key、状态、租约和过期时间；商品图片与争议证据共用，但逻辑授权仍由各自模块决定。
- `storage_cleanup_task`：随机 Object Key、唯一业务键、状态、owner、claim token、租约、尝试次数、下次执行时间和有限失败分类。

模块通过公开接口引用逻辑 ID，不查询其他模块的内部表。Flyway 迁移为金额、数量、状态、唯一幂等键和领取索引建立检查约束与索引。

对象上传先在短事务中创建 `object_upload_session`，再在事务外流式写入 MinIO，最后在短事务中绑定 `listing_media` 或 `dispute_evidence` 并完成会话。绑定失败、客户端断开或即时删除失败时创建或保留 `storage_cleanup_task`；清理器在事务外删除对象，并以 claim token 条件完成任务。对象不存在视为幂等成功，数据库事务期间禁止执行 MinIO IO。

## 10. 可靠事件、调度与故障恢复

订单、库存、支付、退款、结算、状态历史、审计和相应 Outbox 必须在所属业务事务中原子提交。外部调用在事务提交后由 dispatcher 执行。

事件固定包含：

- `eventId`
- `eventType`
- `aggregateId`
- `aggregateVersion`
- `occurredAt`
- `schemaVersion`
- 明确类型的 payload

构造器拒绝空值、非正数版本、未知事件类型和不支持的 schema version；Jackson 边界拒绝未知字段和非法枚举。

Outbox dispatcher 以 `NEW → PUBLISHING` 条件领取，使用数据库时间、租约、owner 和 claim token。只有 RabbitMQ publisher confirm 成功才能标记发布；过期租约可接管，旧 owner 的迟到 confirm 不得覆盖新租约。

消费者以 `(consumerName, eventId)` 原子领取。业务更新、Inbox `COMPLETED` 和派生 Outbox 在同一事务提交，提交前不得 ACK。已完成事件直接确认；只有过期 `PROCESSING` 可接管。

所有期限任务从数据库截止时间领取，不依赖进程内定时器或 RabbitMQ TTL 作为事实源。调度器包括支付超时、卖家交付超时、买家确认超时、售后窗口关闭、卖家退回确认超时、管理员 SLA/硬期限、支付退款对账和搜索同步。

数据库连接、事务回滚、死锁、锁等待、网关超时、RabbitMQ/Elasticsearch/MinIO 暂时不可用属于可恢复异常，使用有界退避。签名失败、非法金额、未知版本、越权状态和损坏 Object Key 属于不可自动恢复异常，进入受控失败状态和人工告警。

## 11. HTTP API

### 11.1 身份

```text
POST /api/auth/email-verifications
POST /api/auth/register
POST /api/auth/login
```

### 11.2 商品

```text
POST   /api/listings
POST   /api/listings/{listingId}/media
POST   /api/listings/{listingId}/publish
PATCH  /api/listings/{listingId}
POST   /api/listings/{listingId}/off-sale
GET    /api/listings/{listingId}
GET    /api/listings/search
```

### 11.3 订单与支付

```text
POST /api/orders
GET  /api/orders/{orderId}
GET  /api/orders/purchases
GET  /api/orders/sales
POST /api/orders/{orderId}/cancel
POST /api/orders/{orderId}/payments
GET  /api/payments/{paymentId}
POST /api/payment-webhooks/{provider}
```

### 11.4 履约、仲裁与评价

```text
POST /api/orders/{orderId}/handoff
POST /api/orders/{orderId}/receipt
POST /api/orders/{orderId}/disputes
POST /api/disputes/{disputeId}/responses
POST /api/disputes/{disputeId}/evidence
GET  /api/disputes/{disputeId}/evidence/{evidenceId}/content
POST /api/disputes/{disputeId}/returns
POST /api/disputes/{disputeId}/return-confirmations
POST /api/admin/disputes/{disputeId}/decisions
POST /api/orders/{orderId}/reviews
```

`POST /api/orders`、取消、支付创建、交付、确认收货、争议裁决、退回确认和退款相关命令必须使用 `Idempotency-Key`。同一键和相同请求返回原始 UTF-8 终态响应；同一键配不同请求摘要返回 409。失败事务不遗留阻塞性幂等记录。

## 12. 错误、安全与隐私

- 400：JSON、字段、数量、金额、邮箱、Multipart 或文件格式非法。
- 401：JWT 缺失、无效或过期。
- 403：身份有效但无权执行公开可判断的角色操作。
- 404：私有资源不存在或当前用户不可见。
- 409：状态已变化、库存不足、金额预占冲突或幂等键请求不一致。
- 422：格式有效但违反明确业务规则，例如购买自己的商品。
- 503：数据库、支付网关、MinIO 或必须持久化的安全审计不可用。

所有 JSON 显式返回 `application/json; charset=UTF-8`。API、日志、审计和指标不得暴露密码哈希、JWT、验证码、支付签名、密钥、完整支付凭证、Object Key、预签名 URL、内容哈希、SQL、内部堆栈或其他用户信息。

指标只使用固定枚举等低基数标签，不使用 user ID、listing ID、order ID、dispute ID、邮箱、支付 reference、Object Key 或异常文本作为标签。

## 13. 测试设计

### 13.1 单元测试

- 邮箱域名精确匹配、规范化、验证码过期和单次消费。
- JWT 签发解析、过期、非法签名和角色授权。
- 商品状态、数量边界、卖家购买自己商品和库存移动不变量。
- 订单状态机、所有允许/禁止迁移和终态不可逆。
- 截止时刻前后边界和用户命令/调度命令的确定性结果。
- 人民币整数分、乘法溢出、部分退款和累计退款上限。
- 支付/退款回调签名、时间戳、重放和幂等。
- 争议数量、累计裁决数量、可信退回证明和硬期限策略。
- 图片、PDF、MP4 签名及实际读取上限。
- 401、403、统一 404、409、422 和 503 映射。
- 审计字段过滤和指标低基数标签。

### 13.2 MySQL 集成测试

- Flyway 表、约束、唯一键和领取索引。
- 多买家并发购买同一库存，成功数量不超过可售库存。
- 订单、库存扣减、流水、幂等记录和 Outbox 同事务提交或回滚。
- 支付超时、卖家交付超时与边界用户命令并发时只有一条合法路径获胜。
- 重复取消、重复退款和迟到回调不会重复返还库存或累计金额。
- 多个部分退款并发时，成功额与预占额之和不超过实付金额。
- 部分退货进入隔离库存，重新上架和报损只执行一次。
- 争议数量累计约束、卖家确认期限、管理员 SLA 和硬期限。
- 租约接管、claim token fencing 和旧 owner 迟到完成。

### 13.3 外部协作集成测试

- Redis：验证码单次消费、发送/失败限流、缓存失效；Redis 故障不能绕过身份、库存或金额规则。
- RabbitMQ：publisher confirm、重复投递、Inbox 幂等、有限重试、租约接管和迟到 confirm fencing。
- Elasticsearch：中文搜索、库存归零下架、重复/乱序事件和从 MySQL 重建。
- MinIO：商品图片和仲裁证据流式上传、私有读取、类型/大小拒绝、删除和不存在幂等。
- Toxiproxy：RabbitMQ、Elasticsearch、MinIO 和模拟支付网关断连、超时、恢复及积压追平。

### 13.4 真实 HTTP 测试

- 校园邮箱验证码、注册、登录、中文错误和 UTF-8 Content-Type。
- 非法、过期 JWT 的 401；公开角色越权 403；私有资源统一 404。
- 商品草稿、图片、发布、搜索、下架和售罄。
- 并发下单、Idempotency-Key 重放和请求摘要冲突。
- 支付/退款回调验签、重复、乱序、未知结果和主动对账。
- 支付超时、交付超时、确认收货、售后窗口和结算。
- 部分争议、仅退款、退货退款、可信退回证明和 `ESCALATED`。
- MP4 证据实际读取上限、案件 ACL 和对象存储故障。
- 完成订单双方各评价一次，非参与者不得评价。

### 13.5 契约与故障演练

同一套 `PaymentGatewayContract` 验证模拟适配器；未来任何真实适配器必须原样通过。契约覆盖金额分、幂等键、签名、回调重放、创建/查询支付、申请/查询退款和未知结果对账。

故障演练至少连续三轮执行：断开中间件或模拟网关、观察可恢复失败和积压、恢复连接、运行 dispatcher/reconciliation、验证全部状态收敛。每轮必须证明没有负库存、重复库存返还、超额退款、重复结算、未授权证据访问或未处理 Outbox/Inbox。

## 14. 可观测性与运维

Actuator/Micrometer 暴露：

- 注册验证码发送、验证成功、失败和限流。
- 商品发布、搜索失败、索引积压和最老事件延迟。
- 各订单状态数量、支付超时和卖家交付超时。
- 支付/退款回调验签结果、重复事件、未知结果和对账积压。
- 退款预占、成功、失败和最老未决退款延迟。
- 争议状态、卖家响应超时、管理员 SLA/硬期限和 `ESCALATED` 数量。
- Outbox/Inbox 各状态数量、最老延迟、租约接管和重试。
- MinIO 上传/读取结果和耗时。

README 提供启动、停止、健康检查、模拟邮箱、模拟支付、故障演练、索引重建和验证命令。`TROUBLESHOOTING.md` 记录 Docker、SmartCN、RabbitMQ confirm、Redis、MinIO、支付回调、金额预占、期限竞态和证据上传排障。

## 15. 扩展路线

首版通过完整验收后依次考虑：

1. `7.1` 聊天：WebSocket、会话权限、离线消息和未读计数。
2. `7.2` 竞价：并发出价、截止成交、反悔规则和竞价审计。
3. `7.3` 跑腿/代取：独立 `ServiceOrder`、服务发布、接单和履约策略，不复用商品订单状态机。
4. 真实支付：在具备商户资质、沙箱/生产账号和必要批准后新增 `PaymentGateway` 适配器。
5. 实验八：按身份、商品、订单、支付和履约边界渐进拆分为 Spring Cloud 服务。
6. 实验九：AI 客服读取规则、订单和售后知识，但不自动执行资金仲裁。

扩展必须单独设计、计划和验收，不以空代码提前占位。

## 16. 文档与验收标准

实验分支包含：

- 独立 Maven Wrapper、应用代码、Flyway、Compose 和固定版本镜像。
- `.env.example` 占位符，不包含真实密码、JWT 密钥、验证码密钥、支付密钥、证书或 Token。
- `README.md`：架构、身份边界、商品、库存、支付、履约、仲裁、接口、启动、故障演练和验证。
- `TROUBLESHOOTING.md`：环境、竞态、金额、消息、搜索、存储和支付排障。
- 单元、MySQL、Redis、RabbitMQ、Elasticsearch、MinIO、Toxiproxy 和真实 HTTP 测试。

最终命令为：

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
git diff --check
```

完整验收要求 `0 failures、0 errors、0 skipped`。Docker 不可用、外部协作测试被跳过、SmartCN 未实际加载、模拟支付契约未执行、竞态测试未运行或故障演练没有恢复收敛，均不算完整通过。

实验验收后在 `main` 独立更新路线状态、学习日志和面试题库；实验分支不合并回 `main`。
