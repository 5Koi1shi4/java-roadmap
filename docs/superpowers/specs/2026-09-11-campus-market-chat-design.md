# 实验七扩展 7.1：校园交易聊天设计

## 1. 目标

在已验收的校园二手交易平台上增加独立的 `7.1` 聊天扩展，为买家和卖家提供围绕具体商品及其后续订单的站内文本、图片、离线补拉、未读计数和多设备已读能力。同时提供经过双方明确同意的微信或 QQ 联系方式交换，但不读取、同步或代理站外聊天。

本扩展继续采用模块化单体。MySQL 保存消息、顺序、参与者游标、授权和证据等全部持久事实；Redis 只负责跨实例实时通知与分布式限流；MinIO 保存私有聊天图片；STOMP/WebSocket 提供低延迟收发，HTTP 提供创建会话、列表、增量补拉、图片上传及 WebSocket 不可用时的发送路径。

## 2. 实施边界

- 文档中心位于 `main`；实现分支固定为 `learning/campus-market-chat`，从已验收的 `learning/campus-market` 创建。
- 实现仍位于 `labs/07-campus-market/`，新增 `chat` 模块，不复制实验七其他模块。
- 实现分支活动树只保留根 `.gitignore` 和 `labs/07-campus-market/`。
- 使用 JDK 17、Spring Boot 3.5.x、Spring WebSocket/STOMP、Spring JDBC、Redis、MinIO、Flyway、JUnit 5 与 Testcontainers。
- 使用现有 JWT 身份，不接入微信登录、QQ 登录，也不接入微信或 QQ 私聊 API。
- 首版不支持群聊、任意用户私信、语音、视频、文件、表情商店、消息编辑、消息撤回、精确在线状态和“正在输入”。
- 首版不做端到端加密。服务端需要为授权读取、举报和争议取证解密内容；正文和联系方式采用应用层静态加密保护。

## 3. 架构

新增 `com.example.campusmarket.chat`，内部继续区分领域、应用、API 和基础设施职责：

- `chat.domain`：会话状态、消息内容、已读游标、联系方式交换、拉黑和保留期规则，不依赖 Spring、数据库、Redis 或 MinIO。
- `chat.application`：创建会话、发送消息、推进已读、交换联系方式、拉黑、举报、冻结争议证据和清理到期数据，定义事务边界。
- `chat.api`：HTTP 控制器、STOMP 命令处理器、连接鉴权和稳定的错误协议。
- `chat.infrastructure`：JDBC 仓储、AES-GCM 字段加密、Redis Pub/Sub、Redis 限流、MinIO 图片存储和清理领取。

发送事务提交后，当前实例向本机连接推送，并向 Redis 发布不含正文的轻量通知。其他实例收到通知后按 `messageId` 从 MySQL 读取并解密消息，只向本机属于目标用户的连接推送。Redis 通知可以重复或丢失；客户端始终以 MySQL 中的会话序号为准，并在连接建立、重连和应用恢复前台时执行增量补拉。

## 4. 会话与权限

### 4.1 创建和唯一性

只有已认证且状态为 `ACTIVE` 的用户可以从 `ON_SALE` 商品发起会话。发起人必须不是卖家。会话固定包含该买家和该商品卖家，唯一键为 `(listing_id, buyer_id)`；并发创建或重复创建返回同一个会话，不产生重复记录。

卖家不能主动为陌生买家建立会话，但可以回复已有会话。会话可以关联该买家购买该商品产生的一个或多个订单；订单关联只提供上下文和关闭判断，不复制订单状态机。

### 4.2 状态

会话状态固定为：

- `ACTIVE`：允许双方发送消息、图片、已读回执和联系方式交换。
- `READ_ONLY`：只允许双方查看历史和已冻结证据，禁止新消息、图片绑定和新联系方式交换。

商品仍在售时，会话保持 `ACTIVE`。商品不可售后，只要存在尚未结束履约或售后的关联订单，会话仍保持 `ACTIVE`；当商品不可售且全部关联订单均进入不可继续履约或售后的终态时，会话转为 `READ_ONLY`。状态转换由领域规则计算，数据库条件更新保证并发下只向前迁移。

### 4.3 不可枚举

除创建会话时对在售商品的合法查询外，补拉消息、读取图片、推进已读、查看联系方式及举报时，资源不存在、已删除和当前用户不是参与者统一表现为 `404`。管理员角色不会自动获得私聊访问权。

## 5. 数据模型

### 5.1 `chat_conversation`

保存随机 UUID、`listing_id`、`buyer_id`、`seller_id`、`status`、`next_sequence`、`read_only_at`、`retention_until`、版本和数据库时间。`next_sequence` 初始为 `1`。发送时锁定会话行，消息获得当前值后将其加一。

约束包括：`buyer_id <> seller_id`、状态只能为 `ACTIVE/READ_ONLY`、`next_sequence > 0`、`(listing_id, buyer_id)` 唯一。卖家取自商品事实，不接受客户端传入。

### 5.2 `chat_participant`

每个会话恰好保存买家和卖家两行，字段包括 `conversation_id`、`user_id`、`participant_role`、`last_read_sequence`、`muted_at`、`hidden_at` 和时间戳。唯一键为 `(conversation_id, user_id)`；`last_read_sequence >= 0`，只能通过 `GREATEST` 单调推进。

未读数定义为当前用户 `last_read_sequence` 之后、发送人不是当前用户且尚未到期删除的消息数。WebSocket 推送成功不等于已读；客户端真正显示消息后才提交 `readSequence`。会话列表允许按索引聚合未读数，首版不维护容易并发漂移的独立未读计数器。

### 5.3 `chat_conversation_order`

保存 `conversation_id`、`order_id` 和关联时间，二者组合唯一。只有订单的商品、买家和卖家与会话完全一致时才允许关联。

### 5.4 `chat_message`

保存随机 UUID、会话、会话内 `sequence`、发送人、`client_message_id`、消息类型、正文密文、正文摘要、加密 key 版本、创建时间和普通保留期限。

- 消息类型固定为 `TEXT`、`IMAGE`、`MIXED`、`SYSTEM`。
- `(conversation_id, sequence)` 唯一并形成严格递增顺序。
- `(sender_id, client_message_id)` 唯一，用于客户端安全重试。
- `TEXT` 必须有正文且没有图片；`IMAGE` 必须没有正文且包含图片；`MIXED` 同时包含正文和图片；`SYSTEM` 只能由受控服务创建。
- 正文摘要只用于判断同一 `client_message_id` 是否异参，不用于搜索、去重或对外展示。
- 用户消息不可编辑或撤回；隐藏会话只改变当前参与者视图，不删除对方数据。

相同发送人和 `client_message_id` 重试时，正文规范摘要、图片上传 ID 集合和会话均相同则返回原 `messageId/sequence`；任一项不同则返回冲突，绝不生成第二条消息。

### 5.5 图片上传和绑定

`chat_upload_session` 保存上传所有者、会话、随机临时 Object Key、媒体类型、实际大小、状态、generation、领取 owner、claim token、租约、过期时间和数据库时间。状态固定为 `UPLOADING → READY → BOUND`，失败或超时进入 `EXPIRED`；每条路径使用条件更新和 generation/claim-token fencing。

`chat_message_image` 保存消息、顺序位置、最终随机 Object Key、类型和实际大小。每条消息最多四张，仅接受 JPEG、PNG 和 WebP，单张按实际读取限制 10 MiB。文件签名与 Apache Tika 必须同时验证真实类型，不信任扩展名、Multipart 声明长度或客户端 `Content-Type`。

文件流和 MinIO 操作位于数据库事务外。上传成功后记录 `READY`；发送消息事务校验上传属于发送人和当前会话，并原子绑定消息。发送失败不绑定；过期 `READY/UPLOADING` 对象由清理任务领取并幂等删除。Object Key 使用安全随机值，不包含用户、会话、商品、订单、原文件名或内容哈希。

图片读取每次重新检查当前用户状态、会话参与者和消息保留状态。服务端流式读取 MinIO 对象，不向客户端返回可绕过撤权检查的长期直链。

## 6. 文本和协议边界

用户消息正文必须包含 1 至 2,000 个 Unicode code point。允许正常空格、制表符和换行；去除前后空白后为空、包含 NUL 或非必要控制字符时快速失败。服务层接收已经通过协议校验的值，不承担 JSON 清洗。

所有 HTTP JSON 显式返回 `application/json; charset=UTF-8`。DTO 对未知字段和非法枚举快速失败。分页 `limit` 默认为 50、最大 100；`afterSequence` 必须大于等于 0。服务端返回升序消息以及 `latestSequence`，客户端按 `messageId` 和 `sequence` 去重。

## 7. HTTP API

固定接口为：

```text
POST /api/chat/conversations
GET  /api/chat/conversations
GET  /api/chat/conversations/{conversationId}/messages
POST /api/chat/conversations/{conversationId}/messages
POST /api/chat/conversations/{conversationId}/images
GET  /api/chat/conversations/{conversationId}/images/{imageId}
POST /api/chat/conversations/{conversationId}/read

POST /api/chat/conversations/{conversationId}/contact-exchanges
POST /api/chat/contact-exchanges/{exchangeId}/accept
POST /api/chat/contact-exchanges/{exchangeId}/decline
POST /api/chat/contact-exchanges/{exchangeId}/revoke
GET  /api/chat/contact-exchanges/{exchangeId}

POST   /api/chat/blocks
DELETE /api/chat/blocks/{blockedUserId}
POST   /api/chat/reports
POST   /api/disputes/{caseId}/chat-evidence
```

创建会话请求只包含 `listingId`。HTTP 发送和 STOMP 发送使用相同命令：`conversationId`、客户端生成的 UUID `clientMessageId`、可选 `text` 和最多四个 `uploadIds`。至少提供正文或一张已就绪图片。

会话列表使用不透明游标稳定分页，返回商品摘要、对方受控展示名、最后消息摘要、最后序号、当前用户未读数、静音/隐藏状态和关联订单摘要。接口不返回邮箱、微信号、QQ号或内部 Object Key。

## 8. STOMP/WebSocket 协议

WebSocket Endpoint 固定为 `/ws/chat`。浏览器在 STOMP `CONNECT` Header 中提交 `Authorization: Bearer <JWT>`；JWT 不允许放入 URL、Cookie 或日志。连接拦截器使用现有 `JwtService` 验证 Token，并查询用户仍为 `ACTIVE`。未在限定时间内完成合法 `CONNECT`、Origin 不在白名单、Token 无效或帧超限时立即关闭连接。

客户端只允许：

```text
SEND      /app/chat.send
SEND      /app/chat.read
SUBSCRIBE /user/queue/chat.events
SUBSCRIBE /user/queue/chat.acks
```

不暴露可由客户端拼接的会话广播 Topic。服务端根据已认证用户使用 user destination 定向推送；每个连接仍在命令执行时重新检查会话 ACL，不能把握手时身份当作永久授权。

`chat.send` 成功 ACK 携带 `clientMessageId`、`messageId` 和 `sequence`；失败 NACK 携带稳定错误码和受控中文消息。`chat.read` ACK 返回实际推进后的 `lastReadSequence`。新消息、对方已读、会话只读、联系方式状态改变和举报处理只以受控事件类型推送。

同一账号允许多个在线连接。消息和已读事件推送到全部本机连接；客户端对重复事件去重。心跳、空闲关闭、每用户连接数和最大 STOMP 帧大小均显式配置。

## 9. Redis 实时通知与限流

Redis Pub/Sub 载荷只包含事件 ID、事件类型、会话 ID、消息 ID、最新序号、目标用户 ID 和发生时间，不包含正文、联系方式、图片 Key、JWT 或异常信息。

数据库事务提交后才发布通知。发布失败不得回滚或伪装消息发送失败；当前实例仍向本机连接推送，其他实例依靠重连、应用恢复前台或主动增量补拉恢复。订阅端按事件 ID 去重，读取 MySQL 后再次检查目标用户和会话权限。

限流固定为：

- 每用户最多发送 20 条消息/10 秒、200 条/10 分钟。
- 每用户最多创建 10 个新商品会话/小时；返回既有会话不消耗新建额度。
- 每用户最多上传 30 张聊天图片/小时，累计实际读取不超过 100 MiB/小时。
- 每用户最多发起 10 次联系方式交换/日；同一会话同一时刻只能有一个待处理请求。

Redis 正常时执行分布式限流。Redis 不可用时使用实例内更保守的令牌桶，不能无限放行；此降级会失去跨实例全局精度，但不参与消息持久化、顺序、授权或已读正确性。

## 10. 外部联系方式交换

平台只提供可选的结构化交换，不尝试读取、发送或同步微信、QQ 私聊。

发起方在请求中选择 `WECHAT` 或 `QQ` 并提交自己的联系方式；服务端加密保存，创建 `REQUESTED` 记录并设置七天过期。接收方接受时也必须选择类型并提交自己的联系方式；只有接受事务成功后状态才变为 `ACCEPTED`，双方才可读取彼此所提交的值。

状态固定为：

```text
REQUESTED → ACCEPTED
REQUESTED → DECLINED
REQUESTED → EXPIRED
ACCEPTED  → REVOKED
```

拒绝和过期不会泄露接收方联系方式。任一方可以撤回已接受交换；撤回后平台立即停止展示双方联系方式，但必须明确提示已经被对方查看、截图或复制的信息无法远程收回。会话转为只读后不能新建交换；既有已接受交换仍可撤回。

联系方式不是公开资料，不出现在搜索、会话列表、审计详情、指标或管理员普通界面。处理目的、双方可见范围、保存期限和撤回方式必须在提交与接受界面清晰告知。

## 11. 拉黑、静音和举报

拉黑关系以 `(blocker_id, blocked_id)` 唯一。拉黑后，对方不能再从任何商品创建面向拉黑人的新会话。没有进行中订单的既有会话立即转为当前双方不可发送的只读状态；存在进行中履约或售后的关联订单时，拉黑只自动静音，聊天继续可用，避免被用于逃避履约责任。解除拉黑不自动恢复已经按正常关闭规则进入只读的会话。

静音只影响当前用户的通知，不影响消息保存、未读计算、补拉或对方发送。

举报必须由会话参与者发起，并明确选择有限数量的消息或图片。创建举报时生成不可变 `chat_evidence_snapshot`；管理员只能读取分配给自己的举报快照，不能借管理员角色浏览整个会话。举报状态、分配、查看和处理均记录安全审计。

## 12. 争议证据

站内聊天可以作为交易争议的辅助证据，但系统不得根据聊天内容自动判责。争议参与者只能从与该订单正确关联的会话中明确选择消息；服务端验证案件参与者、会话、订单和消息关系后，在单事务中创建不可变证据快照。

快照保存来源消息 ID、会话序号、发送人、数据库发送时间、正文密文、图片私有副本引用、加密 key 版本、案件 ID 和冻结时间。快照不依赖普通消息继续存在。只有案件买方、卖方和被分配管理员可以读取；无权与不存在统一返回 `404`。

微信或 QQ 中的站外聊天不会自动成为平台事实。用户若另行提交站外截图，只按现有普通证据规则处理，不赋予其平台签名或真实性保证。

## 13. 加密与密钥

正文、联系方式和证据文本采用 AES-256-GCM 应用层加密，每条记录使用新的安全随机 nonce，并把 `key_version` 与密文一起保存。附加认证数据至少绑定资源类型、资源 ID、会话或案件 ID，防止密文跨记录替换。

密钥通过环境配置或可替换的密钥提供端口注入；仓库只提供占位配置。读取按记录版本选择旧密钥；新写入只使用当前版本。轮换任务通过条件更新重新加密，旧 owner 的迟到写入不能覆盖已经轮换的新版本。解密失败返回受控服务错误并记录不含正文、密文、nonce 或堆栈的安全事件。

## 14. 保留与清理

- `ACTIVE` 会话的普通消息与图片持续保存。
- 会话进入 `READ_ONLY` 时，以 MySQL 时间设置普通数据 `retention_until = read_only_at + 180 天`。
- 用户可以提前隐藏会话，但不能删除对方记录或改变保留期限。
- 举报或争议快照保存到对应案件结束后 1 年；案件未结束时不得清理。
- 已接受或撤回的联系方式记录最迟在会话普通保留期结束时删除；拒绝、过期请求在终态后 30 天删除。

清理任务按 MySQL 时间、有界批量、租约和 claim token 领取。删除顺序先使逻辑记录不可读，再删除 MinIO 对象，最后幂等完成清理任务；对象存储失败保留任务重试，不恢复已经到期的数据访问。过期上传和普通聊天清理使用不同任务类型和 fencing 路径。

## 15. 错误协议

- `401 UNAUTHENTICATED`：缺失、无效或过期身份。
- `404 NOT_FOUND`：资源不存在、已删除或已认证用户无权访问。
- `409 CONFLICT`：会话只读、拉黑阻止创建、幂等键异参、联系方式状态冲突或图片已被其他消息绑定。
- `400 INVALID_REQUEST`：文本、游标、UUID、未知字段或状态值非法。
- `413 PAYLOAD_TOO_LARGE`：图片实际读取超限。
- `415 UNSUPPORTED_MEDIA_TYPE`：图片真实类型不支持或声明与检测结果不一致。
- `429 RATE_LIMITED`：命中限流，返回受控重试时间。
- `503 STORAGE_UNAVAILABLE`：MinIO 当前不可用。
- `500 CHAT_CONTENT_UNAVAILABLE`：服务端无法解密已授权内容；不泄露密钥、密文或内部异常。

HTTP 和 STOMP 使用同一稳定业务错误码。HTTP JSON 必须为 UTF-8；STOMP NACK 必须回显对应 `clientMessageId`，使客户端能确定哪条发送失败。

## 16. 审计与指标

审计覆盖：创建会话、发送成功/拒绝、图片读取成功/拒绝、联系方式请求/接受/拒绝/撤回、拉黑/解除、举报、证据冻结、管理员查看快照和清理结果。

审计不得保存正文、联系方式、密文、nonce、图片 Object Key、文件路径、内容哈希、JWT、STOMP Header 或异常堆栈。指标标签保持低基数，只使用操作类型、结果、消息类型、连接结果和受控错误码，不使用用户、会话、消息、商品或订单 ID。

核心指标包括 WebSocket 当前连接数、连接拒绝数、发送成功/冲突/限流数、发送事务耗时、Redis 通知成功/失败数、补拉消息量、未读聚合耗时、图片上传结果、清理积压与最老任务年龄。

## 17. 验收测试

### 17.1 单元测试

- 会话创建与关闭规则、商品/订单匹配和拉黑例外。
- 文本 Unicode code point 上限、空白和控制字符校验。
- 消息类型组合、客户端幂等摘要和非法构造快速失败。
- 已读游标只前进，推送不自动已读，多设备迟到回执不能倒退。
- 联系方式状态机、七天过期、撤回语义和非参与者拒绝。
- AES-GCM 随机 nonce、附加认证数据、防篡改、旧版本读取和新版本写入。
- 普通消息、举报和争议快照的保留期计算。

### 17.2 MySQL/Testcontainers 集成测试

- 并发创建同一商品会话只产生一行和两个参与者。
- 同一会话并发发送获得无重复、严格递增的唯一序号。
- 同一 `clientMessageId` 并发重试只产生一条消息；异参重试返回冲突。
- 消息、图片绑定和会话序号在同一事务提交，回滚不消耗可见消息序号、不遗留绑定。
- 已读游标与消息发送竞态保持单调，未读数与事实查询一致。
- 会话关闭、拉黑和订单终态竞态不允许关闭后继续写入。
- 联系方式并发接受/拒绝/过期只能有一个终态。
- 清理租约过期可接管，旧 owner 迟到结果不能覆盖新 owner。

### 17.3 HTTP/STOMP 集成测试

- 真实 JWT 连接、非法 Token、严格 Origin、非法订阅和帧大小限制。
- 中文消息内容与 `application/json; charset=UTF-8`。
- HTTP 与 STOMP 发送产生相同领域结果和错误码。
- 同一账号多连接收到新消息与已读事件，其他用户连接无法收到。
- 断线后按序号补拉无遗漏；重复在线事件不会产生重复消息。
- 非参与者读取会话、消息、图片和联系方式统一 `404`。
- 管理员不能浏览私聊，只能读取被分配的举报或案件快照。

### 17.4 Redis 与 MinIO 故障测试

- 两个应用实例通过 Redis 通知各自本机连接。
- 发送提交后断开 Redis，消息仍成功保存；远端客户端重连补拉后完整恢复。
- Redis 不可用时实例内保守限流生效。
- JPEG、PNG、WebP 正常上传；伪造类型、超过实际读取上限和第五张图片被拒绝。
- MinIO 上传失败不产生 `READY` 绑定；数据库提交失败后的临时对象可被回收。
- MinIO 读取故障返回 `503`，不伪装成 `404`。
- 普通图片到期后不可访问，删除失败任务可重试；证据快照图片不被普通清理误删。

### 17.5 完整验收

真实旅程固定覆盖：买家从在售商品创建会话 → 中文文本和两张图片 → 卖家另一应用实例实时收到 → 卖家回复 → 买家两台设备同步 → 一台设备推进已读 → 双方完成微信/QQ双向交换 → 买家下单并关联会话 → 拉黑只触发静音而不中断履约聊天 → 争议中选择消息和图片冻结 → 被分配管理员只读取快照 → Redis 故障期间继续发送 → 重连补拉恢复 → 案件结束和会话只读后的保留清理。

验收命令在 `labs/07-campus-market` 使用 JDK 17 执行：

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
git diff --check
```

`verify` 必须实际运行 MySQL、Redis、MinIO 及多实例协作测试，结果为 0 failures、0 errors、0 skipped。Docker 不可用、容器测试跳过或仅通过单元测试均不算完整验收。

## 18. 外部平台边界

微信和 QQ 仅作为用户自愿交换的站外联系方式。平台不宣称具有腾讯私聊接口权限，不代发消息、不抓取聊天记录、不校验账号归属，也不把站外送达或已读状态映射为站内事实。

公开能力与个人信息边界参考：

- 腾讯开放平台：<https://open.tencent.com/>
- 《中华人民共和国个人信息保护法》：<https://www.cac.gov.cn/2021-08/20/c_1631050028355286.htm>

未来若接入微信登录、QQ 登录、公众号、小程序或其他腾讯能力，必须另行核对当时的官方接口、资质、审核、用户授权和数据处理规则，并单独设计、计划和验收。
