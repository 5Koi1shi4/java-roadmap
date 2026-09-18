# Java 后端面试题库

每个回答必须能回到本仓库的代码、SQL 或测试证据，而不是背诵概念。文中的 `*` 表示脱敏后的账号、密码、哈希、token 或密钥值。

## 1. 请从一次管理员请求说明认证如何进入 `SecurityContext`。

**回答：** 客户端在 `Authorization: Bearer *` 中提交 JWT。`JwtAuthenticationFilter` 先验证签名、过期时间和 `token_use=access`，再从 JWT subject 取出 `userId`。随后调用 `RbacService.authoritiesOf(userId)` 查询该用户当前的权限码，创建带 authorities 的 `Authentication` 并放进 `SecurityContext`。最后 `SecurityConfig` 对 `GET /api/admin/users` 执行 `hasAuthority("system:user:read")` 判断。

**代码证据：** `JwtAuthenticationFilter`、`RbacService`、`SecurityConfig`；`AdminAuthorizationIT` 覆盖 401、403、200 三个分支。

**追问：** 为什么权限不直接写进 JWT？本实验选择每次请求查询数据库，权限变更能即时生效；代价是多一次查询，后续可在正确失效策略下缓存权限。

## 2. 401 和 403 的边界是什么？

**回答：** 401 表示身份未建立或凭据无效，例如没有 Bearer token、token 过期或签名错误；403 表示身份已建立，但没有目标资源的权限。普通用户带着有效 JWT 访问管理员接口必须得到 403，不能误报 401。

**代码证据：** `SecurityConfig` 的 `authenticationEntryPoint` 与 `accessDeniedHandler`；`AdminAuthorizationIT.returns401WhenAccessTokenIsMissing` 和 `returns403WhenAuthenticatedUserLacksPermission`。

## 3. 为什么 access token 与 refresh token 要分开？

**回答：** access token 高频携带、寿命短（本实验 15 分钟）、仅用于访问资源；refresh token 低频使用、寿命长（7 天）、仅用于换取新 token。这样把性能友好的无状态 JWT 与可管理的长期会话分开，降低 access token 泄露的影响窗口。

**代码证据：** `JwtTokenService.issueAccessToken`、`RefreshTokenService` 和 `SecurityBeansConfiguration` 中的 TTL。

## 4. refresh token 为什么只存 SHA-256 哈希，不存明文？

**回答：** 数据库泄露时，明文长期凭证可以被立即重放；哈希不能直接用于 `/refresh`。服务端收到 refresh token 后重新计算 SHA-256，再用哈希查询。因为 token 由 32 字节 `SecureRandom` 生成，熵足够高，攻击者无法像弱密码那样有效枚举。

**代码证据：** `RefreshTokenService.newRawToken`、`sha256` 与 `JdbcRefreshTokenSessionRepository`。

## 5. 两个并发 refresh 请求为何会有安全问题？你如何修复？

**回答：** “先查 token 可用，再写 revoked”不是原子操作：两个请求都可能在写入前读到可用状态，从而都签发新 token。修复是用带条件的单条更新消费旧 token：`revoked=false AND expires_at>?`，只有影响行数为 1 的事务可继续签发 successor token。

**代码证据：** `RefreshTokenSessionRepository.revokeIfUsable`、`JdbcRefreshTokenSessionRepository` 和 `RefreshTokenService.rotate` 的事务边界；`AuthFlowE2ETest.allowsOnlyOneConcurrentRefreshForTheSameToken` 断言一个 200、一个 401。

## 6. 用户注销后，已签发的 access JWT 为什么仍可能可用？

**回答：** access JWT 是无状态签名凭证，服务端没有每个 access token 的撤销记录；`logout` 只能撤销 refresh token。因此攻击者若已拿到 access token，在其到期前仍可能访问资源。本实验使用 15 分钟 TTL 缩短风险窗口，客户端也应删除本地 token。若业务需要立即失效，可引入 token version 或黑名单，但会增加状态存储与查询成本。

**代码证据：** `AuthController.logout` 只调用 refresh-token 撤销；端到端测试验证注销后 refresh 返回 401。

## 7. BCrypt 相比 SHA-256 为什么适合密码？

**回答：** SHA-256 很快，适合摘要，不适合人类密码；BCrypt 故意慢，cost 可调，且哈希结果中内置随机盐。验证时应使用 `PasswordEncoder.matches(rawPassword, storedHash)`，不能比较两个新生成的哈希字符串。

**代码证据：** `AuthService.authenticate` 使用 `PasswordEncoder.matches`，`SecurityBeansConfiguration` 提供 `BCryptPasswordEncoder`。

## 8. JWT 签名密钥为什么必须由配置提供？

**回答：** 同一个服务重启后必须仍能验证未过期 token；如果每次进程启动都临时生成密钥，所有旧 access token 会突然失效，也无法多实例部署。实验将 `JWT_SECRET` 绑定到 `jwt.secret`，要求使用 Base64 编码的至少 32 字节随机值，并避免提交到 Git。

**代码证据：** `SecurityBeansConfiguration.jwtTokenService`、`application.yml` 和 `.env.example`。

## 9. Flyway 在这个实验里解决了什么问题？

**回答：** RBAC 至少包含用户、角色、权限及两张关联表，还要有 refresh token 表和唯一约束。Flyway 将这些结构版本化，任何新环境启动时按相同顺序执行迁移，避免“手工建表漏索引”或环境漂移。

**代码证据：** `V1__rbac_schema.sql` 显式定义六张表与业务唯一索引；`RbacSchemaIT` 在 MySQL Testcontainers 中验证表和索引。

## 10. 为什么既保留 MockMvc 测试，又写真实 HTTP + MySQL 的端到端测试？

**回答：** MockMvc 适合快速、稳定地隔离验证 SecurityFilterChain 的 401/403 分支；真实 HTTP 测试能覆盖 Spring Boot 随机端口、控制器序列化、Flyway、JDBC 和 MySQL 事务的组合行为。两者互补，后者还复现并防止了 refresh 并发竞争。

**代码证据：** `AdminAuthorizationIT` 使用 MockMvc；`AuthFlowE2ETest` 使用 `TestRestTemplate`、Testcontainers MySQL 和并发请求。

## 实验四：订单状态机与 RabbitMQ 可靠消息

### 11. 为什么支付和取消都必须使用带原状态条件的更新？

**回答：** `PENDING_PAYMENT → PAID` 和 `PENDING_PAYMENT → CANCELLED` 是唯一允许的迁移。SQL 以原状态作为 `WHERE` 条件，受影响行数为 0 时再读取当前状态，从而避免支付和超时线程互相覆盖；已支付订单不会被超时取消，重复取消也不会再次释放库存。

**代码证据：** `JdbcOrderRepository.markPaidIfPending`、`cancelIfPending`、`OrderService.cancelExpired`；`OrderPersistenceIT.doesNotCancelPaidOrder` 和 `ReliableMessagingFlowIT.paidOrderSurvivesLateTimeoutEvent`。

### 12. Outbox 解决了什么双写问题？它为什么仍需要 publisher confirm？

**回答：** 建单事务同时写订单和 `outbox_event`，避免订单已提交但超时事件未落库；发布由异步 dispatcher 补偿，不把 Rabbit 调用放进数据库事务。Outbox 行写入不等于 broker 已接收，因此 `OrderEventPublisher` 必须等待 correlated publisher confirm，只有确认成功才把状态标为已发布。

**代码证据：** `OrderService.createOrder`、`JdbcOrderRepository.insertTimeoutOutbox`、`OutboxDispatcher.dispatchOnce`、`OrderEventPublisher.publish`；`OrderPersistenceIT.persistsOrderAndTimeoutEventAtomically` 和 `OutboxDispatcherTest.releasesEventWhenPublisherNacks`。

### 13. Outbox 租约如何控制并发，旧实例的迟到 ACK 为什么不能覆盖新实例？

**回答：** 领取使用数据库条件更新，把 `NEW` 或已过期的 `PUBLISHING` 变为当前 owner 的租约，批量最多 50 行。每次领取带随机 claim token；完成发布或释放重试时必须同时匹配 eventId 和 token，旧租约迟到的写入影响行数为 0。

**代码证据：** `JdbcOrderRepository.claimPublishable`、`markPublished`、`releaseForRetry`；`OutboxLeaseIT.expiredPublishingEventCanBeClaimedByNextDispatcher`、`lateAckFromPreviousOwnerCannotCompleteNewLease` 和 `lateNackFromPreviousOwnerCannotReleaseNewLease`。

### 14. 消费者什么时候确认消息？PROCESSING 和 COMPLETED 的边界是什么？

**回答：** 消费者先领取 `consumed_message` 为 `PROCESSING`，再在同一数据库事务中执行订单条件取消、实际库存释放并写 `COMPLETED`；事务提交之前不能 ACK。已完成记录直接确认；未完成且租约过期可以被接管，租约仍有效则不能并发执行。

**代码证据：** `OrderTimeoutConsumer.handle`、`JdbcOrderRepository.beginConsumption`、`completeConsumption`；`OrderTimeoutIT.completedFailureRecordRemainsCompleted` 和 `ReliableMessagingFlowIT.publishesTimeoutAndEventuallyCancelsOnlyPendingOrder`。

### 15. 为什么重复消息不会重复释放库存？

**回答：** 全局唯一 `eventId` 在 `consumed_message` 上有唯一键，第一次成功消费把状态提交为 `COMPLETED`；同 eventId 的后续投递命中终态并跳过业务。即使消息重复到达，`cancelIfPending` 也只会匹配待支付订单，只有受影响行数为 1 时才释放库存。

**代码证据：** `V1__order_mq_schema.sql` 的唯一索引、`JdbcOrderRepository.beginConsumption`、`OrderService.cancelExpired`；`OrderTimeoutIT.duplicateEventIdCancelsAndReleasesStockOnlyOnce` 和 `ReliableMessagingFlowIT.duplicateEventIdReleasesStockOnlyOnce`。

### 16. 哪些异常可重试，哪些异常应直接人工处理？

**回答：** 连接中断、短暂数据库异常、死锁、锁等待超时和事务回滚属于可恢复基础设施异常；JSON 解析、事件字段/版本校验、非法状态迁移属于不可恢复输入或业务错误。`FailureClassifier` 只允许前一类进入有限重试，后一类直接恢复到人工队列并记录类别与原因。

**代码证据：** `FailureClassifier.classify`、`RetryableMessageException`、`NonRetryableMessageException`；`FailureClassifierTest.classifiesBrokenJsonAndUnsupportedVersionAsNonRetryable` 与 `classifiesTransientDatabaseConnectionAsRetryable`。

### 17. 三次重试如何避免无限重试和 attempt 计数偏差？

**回答：** listener 使用 Spring Retry `SimpleRetryPolicy(maxAttempts=3)`，这里的 3 包含首次处理；重试耗尽由 recoverer 发送人工消息。恢复器读取 `RetrySynchronizationManager` 的实际 attempt，而不是盲目累加入站 `x-retry-count`，因此不可重试错误记录 1 次、可重试耗尽记录 3 次；非法或已耗尽的 header 不会继续执行业务。

**代码证据：** `RabbitTopologyConfiguration.timeoutRetryInterceptor`、`timeoutMessageRecoverer`、`OrderTimeoutConsumer.retryCount`；`RabbitTopologyConfigurationTest.manualRecoveryUsesOneAttemptForNonRetryableSpringRetryFailure`、`manualRecoveryUsesThreeAttemptsAfterRetryableSpringRetryFailures` 和 `OrderTimeoutConsumerTest.exhaustedInboundRetryCountGoesToRecovererWithoutBusinessExecution`。

### 18. TTL 队列为什么要按延迟分桶？DLX 在本实验中做什么？

**回答：** RabbitMQ TTL 是消息到期机制，不是精确定时器；同一队列混合不同 TTL 时，队头未到期消息会阻塞后面的消息。实验声明 10s、1m、5m 三个同 TTL 队列，过期消息经 `order.cancel.exchange` 路由到取消队列；取消队列超过 quorum `x-delivery-limit=3` 后再死信到人工队列。

**代码证据：** `RabbitTopologyConfiguration.timeoutQueue`、`orderCancelQueue`、bindings；`OrderTimeoutIT.declaresTtlBucketsAndDeadLetterDestination`、`RabbitTopologyConfigurationTest.cancelQueueUsesQuorumDeliveryLimitAndManualDeadLetterRoute`。

### 19. 为什么事件构造器和反序列化边界都要快速失败？服务层应该做什么？

**回答：** `OrderTimeoutEvent` 的构造器拒绝空 eventId、错误 eventType、非正 orderId、空 occurredAt 和未知 schemaVersion；Jackson 反序列化拒绝缺失/未知字段和非法类型，协议错误在消息边界分类为不可重试。这样 `OrderService` 只编排库存、状态迁移和事务业务规则，不负责清洗外部 JSON。

**代码证据：** `OrderTimeoutEvent` canonical constructor、`OrderTimeoutConsumer.consume`、`OrderService.cancelExpired`；`OrderServiceTest.rejectsMissingRequiredTimeoutEventFieldAtJacksonBoundary`、`rejectsInvalidTimeoutEventFieldsAtJacksonBoundary` 和 `rejectsMalformedTimeoutEventBeforeChangingOrder`。

### 20. 共享 Testcontainers 如何兼顾测试效率和隔离？

**回答：** 集成测试共享一对 MySQL/RabbitMQ 容器，避免每个类重复启动镜像；每个测试使用独立订单和 eventId，并在 setup 清理四张业务表、恢复库存、同步 purge 五个队列。同步 purge 必须在下一次发布前完成，否则旧消息会污染断言；单元测试则不启动 Spring，使用 mock 和固定 Clock 快速覆盖边界。

**代码证据：** `SharedContainers`、各集成测试的 `@BeforeEach`、`RabbitAdmin.purgeQueue(queue, false)`；`OrderSchemaIT.purgeQueuesCompletesBeforeTheNextMessageIsPublished`、`ReliableMessagingFlowIT` 和 `OutboxDispatcherTest`。

## 实验五：Elasticsearch 商品搜索

### 21. 为什么 MySQL 是事实源，而 Elasticsearch 只能作为可重建索引？

**回答：** 商品写入必须先在 MySQL 事务中提交；Elasticsearch 不可用时，写 API 仍可成功提交商品和 `search_outbox`，但搜索暂时不可用或落后。索引损坏时可以从 `product` 快照和 Outbox 事件重建，因此不能把搜索索引当成唯一事实源。

**代码证据：** `ProductCommandService.create/update/delete` 的 `@Transactional` 编排、`ElasticsearchProductSearchGateway.search` 只读 `products-read`；`ProductPersistenceIT.commitsProductAndOutboxInOneTransaction` 验证商品与事件同事务提交。

### 22. 事务 Outbox 如何避免商品与搜索事件的双写窗口？

**回答：** 创建、更新和逻辑删除先更新 `product`，随后在同一个事务调用 `SearchOutboxRepository.append`；事务提交后由 dispatcher 异步投递。若 Outbox 唯一约束或事务失败，商品变更也回滚，避免出现商品已提交但没有可补偿事件的状态。

**代码证据：** `ProductCommandService`、`JdbcSearchOutboxRepository.append` 的 `INSERT INTO search_outbox`；`V1__search_schema.sql` 的 `uk_search_outbox_product_version_type`；`ProductPersistenceIT.rollsProductUpdateBackWhenOutboxUniqueKeyRejectsEvent`。

### 23. Outbox dispatcher 的租约如何避免多个实例重复领取同一批事件？

**回答：** `JdbcSearchOutboxRepository.claim` 用数据库时间筛选 `NEW` 或已过期 `PROCESSING` 行，执行 `FOR UPDATE SKIP LOCKED`，再写入 owner、claim token 和租约。运行默认是 batch 50、lease 30 秒，但二者由 `SearchProperties` 配置；batch 校验为 1–50，lease 必须大于 request timeout。`OutboxDispatcher` 和 `JdbcSearchOutboxRepository` 分别接收这组运行配置，因此并发实例领取的是互斥行集，崩溃后过期租约仍可接管。

**代码证据：** `SearchProperties` 的默认值与范围/大小关系校验、`OutboxDispatcher(..., SearchProperties)` 和 `JdbcSearchOutboxRepository(..., SearchProperties)` 的运行接线，以及 `JdbcSearchOutboxRepository.claim` 的条件 SQL；`OutboxLeaseIT.claimsEachEventOnceAndRejectsLateOwner` 与 `twoIndependentTransactionsClaimDisjointEventSets`。

### 24. claim token fencing 解决了什么迟到写入竞态？

**回答：** 旧 dispatcher 可能在租约过期、事件被新 owner 接管后才收到 Elasticsearch 响应。完成、reschedule（按退避回退重排）或 fail 更新必须同时匹配 `event_id` 和旧 `claim_token`；影响行数为 0 就视为 fenced，不能覆盖新 owner 的状态。这里的 reschedule 是租约失效后的状态回退，不等同于操作员手工 retry。

**代码证据：** `OutboxDispatcher.applyResult`、`JdbcSearchOutboxRepository.complete/reschedule/fail`；`OutboxLeaseIT.oldTokenCannotCompleteRescheduleOrFailConcurrentlyAfterTakeover` 验证旧 token 三类迟到操作都被拒绝。

### 25. 为什么索引写入使用 `external_gte`，而不是依赖消息到达顺序？

**回答：** Outbox 是至少一次投递，网络重试和多个 dispatcher 会让旧版本晚到。写入请求携带商品 `sourceVersion`，Elasticsearch 以 `external_gte` 接受相同版本的幂等重复写，但拒绝更旧版本覆盖新版本，从而把数据库版本作为单调顺序依据。

**代码证据：** `ElasticsearchSearchIndexWriter.bulkWrite` 设置 `.version(mutation.sourceVersion()).versionType(VersionType.ExternalGte)`；`ExternalVersionIT.identicalPayloadAtSameSourceVersionIsIdempotentlyApplied` 与 `staleVersionCannotOverwriteOrReviveTombstone`。

### 26. 删除为什么要写 tombstone，而不是直接物理删除索引文档？

**回答：** 直接删除可能被延迟到达的旧 upsert 重新“复活”。`IndexMutation.tombstone` 保留商品 ID、删除版本和 `DELETED` 状态，并通过相同的 external version 规则压住旧事件；公开搜索仍固定过滤 `ON_SALE`，所以删除不会返回。

**代码证据：** `IndexMutation.from/tombstone`、`ProductCommandService.delete`、`ElasticsearchSearchIndexWriter`；`ReliableIndexSyncIT.synchronizesCreateUpdatesAndLogicalDeleteWithMonotonicVersions` 和 `ExternalVersionIT.staleVersionCannotOverwriteOrReviveTombstone`。

### 27. SmartCN 在中文搜索中承担什么职责，如何证明不是只配置了名称？

**回答：** 运行时由 `ElasticsearchIndexManager.create` 为 `name`、`subtitle` 和 `description` 设置 SmartCN analyzer，使中文短语按中文词法分析；同一处还使用严格 mapping 拒绝未知字段。`products-index.json` 只是契约资源，当前运行时未读取。真实集成测试通过 `_analyze` 断言 `并发`、`编程` 两个 token，且不出现单字 `并`、`发`、`编`、`程`；真实 HTTP 测试再用 UTF-8 URI 查询 `并发编程`，验证名称权重、高亮、`ON_SALE`/分类/价格 filter 和分类聚合。

**代码证据：** `ElasticsearchIndexManager.create` 的 `.analyzer("smartcn")` 与 `DynamicMapping.Strict`；`ElasticsearchIndexIT.installsSmartCnAndRejectsUnknownFields` 调用真实 `_analyze`，断言 tokens 包含 `并发`、`编程` 且不含四个单字，并继续验证未知字段被严格 mapping 拒绝。`ProductSearchHttpIT.searchesSmartCnWithWeightedNameHighlightFiltersSortAndBuckets` 通过 UTF-8 URI 查询 `并发编程`，断言名称权重的首项、名称高亮、filter 后总数/结果与分类聚合。

### 28. 相关性排序和业务 filter 如何同时实现？

**回答：** 关键词放入 `multi_match`，字段权重为 `name^4`、`subtitle^2`、`description`；业务约束放入 bool query 的 `filter`，固定要求 `status=ON_SALE`，并可叠加分类与价格范围。这样过滤不参与文本相关性评分，排序在相关性相同或无关键词时再使用更新时间和商品 ID 稳定收敛。

**代码证据：** `ElasticsearchProductSearchGateway.query/applySort`；`ProductSearchHttpIT.searchesSmartCnWithWeightedNameHighlightFiltersSortAndBuckets` 与 `appliesStablePriceAndNewestSortsAndRejectsInvalidRequests`。

### 29. 在线重建怎样保证别名切换前后读写目标一致？

**回答：** 重建先写入新物理索引并校验快照与 Outbox 补放结果，然后暂停 dispatcher、排空未过期处理中的事件，在最终事务中锁协调行、读取两个别名目标、补放最终水位并再次校验，最后一次 Elasticsearch alias 请求同时切换 `products-read` 和 `products-write`。普通 validation/replay/refresh 失败发生在 alias 请求前时，任务标记 `FAILED`、清理 active rebuild、解除 paused，两个别名保持旧目标；只有 `SplitAliasException` 或 alias swap 已尝试导致目标不确定时，才保留 pause 与 active 信号交给 recovery。

**代码证据：** `SearchRebuildCutover.cutover`、`RebuildValidator`、`ElasticsearchIndexManager.swapReadWriteAliases`；`RebuildCutoverIT.switchesBothAliasesAtomicallyAfterFinalValidation`、`validationFailureKeepsBothOldAliases` 与 `ElasticsearchIndexIT.rejectsDifferentAliasTargetsWithoutRebinding`。

### 30. 重建的高水位补放和故障恢复为什么不可省略？

**回答：** 可重复读快照只代表某一时刻的商品表；快照期间提交的商品事件必须记录起始高水位、读取准备阶段高水位，再用 `eventsBetween(start, prepared)` 补放，最终门禁后再补放到 final watermark。网络失败若发生在 alias 请求前的 replay、refresh 或 validation 阶段，按普通失败释放（`FAILED`、清 active、解除 paused）；只有 alias 请求已尝试、读写目标因此不确定时，recovery worker 才根据数据库阶段和两个别名目标判断“已切换”“未切换”或分裂状态，不能盲目清理现场。

**代码证据：** `SearchRebuildPreparer.prepare/replayCatchUp`、`SearchRebuildCutover`、`SearchRebuildRecovery.recoverInterruptedCutover`；`RebuildPreparationIT.replaysEveryEventAfterSnapshotWatermark`、`RebuildRecoveryIT.splitAliasesFailButRemainPausedAndActive` 和 `RebuildAndRecoveryDrillIT.repeatsThreeRebuildsAndTwoConnectionOutageRecoveriesWithoutRegression`。

## 实验六：安全文件服务与 MinIO

### 31. 为什么上传要拆成数据库事务 A/B/C，而不能把 MinIO 操作放进一个数据库事务？

**回答：** MySQL 事务不能原子提交文件系统或 MinIO IO；在事务中上传大文件还会长时间占用连接和行锁。事务 A 只创建 `RECEIVING` session、随机 temp key 和 owner token；事务外流式写 temp、计算实际大小/SHA-256 并检测类型；事务 B 锁定或创建 `STAGING` Blob；owner 在事务外提交物理对象；事务 C 再分别以 session/Blob ID、owner token、状态和有效租约推进 Blob、逻辑文件、引用和 session，并校验 hash、size、类型等已验证元数据。任一步失败由 session/Blob 状态与幂等清理任务补偿，而不是伪造跨资源原子事务。

**追问要点：** 事务 C 失败后如何恢复？先按最终 object key 探测对象；存在且大小匹配才允许合法 owner/接管者继续 finalize，不匹配或不可用按失败分类与任务重试处理。`RECEIVING` 中断会话当前不由过期恢复器自动领取，不能手工改状态或删除活跃 temp。

### 32. 全局内容去重为什么容易造成隐私泄漏，接口应怎样设计？

**回答：** 如果重复上传返回不同状态、耗时、标志位、hash、Blob ID 或 object key，调用者就能探测某份内容是否已由他人保存。实现可以用 SHA-256 唯一约束共享物理 Blob，但每次上传必须创建独立随机 `fileId`、owner 和 ACL，并让成功响应保持相同语义；普通日志、审计和指标也不得暴露去重命中或内容标识。

**追问要点：** 去重是存储优化，不是授权关系。即使知道内容 hash 或 object key，也必须从逻辑文件重新执行 ACL；物理 Blob 不能成为绕过授权的第二入口。

### 33. 为什么无权访问和文件不存在统一返回 404，管理员是否应该自动绕过？

**回答：** 对已认证用户区分 403 与 404 会泄漏文件存在性。实验将不存在、已删除和无权资源映射为同构 404；文件默认私有，只有 owner 和被授予只读权限的用户可读，owner 可 grant/revoke，grantee 不能转授权或删除。管理员角色不自动旁路，因为后台身份不等于数据所有权；若未来需要合规访问，应设计独立、显式、可审计的受控流程。

**追问要点：** 未认证仍返回 401；统一 404 前仍记录权限拒绝审计，但不能把 owner、hash、object key 或完整异常写入响应。

### 34. `/content` 与 MinIO presigned URL 在撤权语义上有什么差异？

**回答：** `/content` 由应用在响应前重新校验逻辑 ACL，并完成审计、对象预打开和首段预读；本地 HMAC token 兑换时也会重新授权，所以撤权能阻止后续请求。MinIO presigned URL 签发后直接由对象存储处理，应用无法逐次重查 ACL，已有 URL 在撤权后最多仍可能有效约 2 分钟。低风险大流量下载可接受短 TTL presign；高风险或要求即时撤权时使用 `/content`。

**追问要点：** presigned URL 不能写入日志或审计；签发前仍要逻辑授权和审计，TTL 必须受配置上限约束。

### 35. 租约、owner/claim token 和 generation fencing 分别解决什么问题？

**回答：** 租约允许执行者崩溃后由新实例接管；owner/claim token 区分同一资源的不同领取者；generation 与 object key 区分同一内容 Blob 删除后再创建的新一代对象。各路径使用自身的条件更新：上传领取和推进匹配 session/Blob ID、owner token、状态与租约，并校验 hash、size、类型等上传元数据；cleanup task 的完成、重试和失败匹配 task ID、`PROCESSING` 与 claim token；Blob 物理清理的原子完成还要匹配 Blob 状态、cleanup token、object key 与 generation。过期判断使用 MySQL 时间，因此旧 owner、旧 token 或旧 generation 的迟到操作只会更新 0 行，不能覆盖新执行者或误删新代对象。

**追问要点：** 应避免长时间持锁等待，用短事务条件更新和有界轮询协调；对象存储 IO 始终在事务外执行。

### 36. 引用计数归零后的物理删除如何做到幂等且不会误删新对象？

**回答：** 逻辑删除在同一事务减少引用；只有引用归零才把 Blob 推进到 `PENDING_DELETE` 并创建唯一的 `BLOB_OBJECT` 任务。清理器用 claim token 领取，使用 Blob cleanup token 将其推进到 `DELETING`，事务外删除指定 key；对象不存在按成功处理，最后以相同 token、generation、object key 原子完成 Blob 与 task。失败按固定退避重试，最多 5 次；新一代 Blob 使用新 key 和 generation，旧任务无法通过 fencing 条件。

**追问要点：** 不能先删正式对象再补数据库，也不能只完成 Blob 或 task 其中之一；引用计数、任务创建和逻辑删除必须在同一数据库事务中。

### 37. 文件服务的审计和指标怎样兼顾可追踪性、隐私与基数控制？

**回答：** 审计记录固定动作、结果和有限失败分类，并集中移除 token、JWT/HMAC、完整签名 URL、object key、路径、hash、secret、异常堆栈和换行。内部 correlation ID 由服务端安全随机生成，客户端 trace 不能覆盖它。指标只使用固定枚举的 `result/phase/action/type/status/operation` 标签，不把 user、file、hash、blob、object、correlation ID 或异常消息作为标签，避免隐私泄漏和时序库基数爆炸。

**追问要点：** 当前独立审计覆盖上传成功、文件访问/拒绝、ACL 和下载动作；上传失败依赖 session/指标，cleanup/recovery 依赖状态、任务与指标，没有独立 `AuditRecorder` 动作，回答时不能夸大范围。

### 38. 怎样证明 MinIO 故障恢复不是“单轮偶然成功”？

**回答：** 使用 Toxiproxy 连续执行 3 轮“断开 MinIO → 观察失败或积压 → 恢复 route → 运行 recovery/cleanup → 验证终态”。每轮不仅看 HTTP 成功，还递归列出测试 bucket，断言 `tmp/` 为空、`blobs/` 对象集合精确等于数据库 `READY` Blob 的 `object_key` 集合，并核对 session、Blob 和清理任务收敛。

**追问要点：** exact-set 证据只覆盖测试 bucket 与数据库已知对象，不能扩大为任意外部未登记对象的全量盘点；不能绕过 Toxiproxy 直连 MinIO、跳过数据库核对或用 local mock 代替真实故障测试。

## 实验七：校园二手交易平台

### 39. 为什么校园邮箱验证码不等于正式 CAS 身份认证？

**回答：** 验证码只能证明用户在当时控制某个允许域名的邮箱，不能证明学籍、在校状态或学校统一身份。实验将校园域名精确匹配、验证码单次消费和 JWT 登录做成可运行能力，同时只保留 `ExternalIdentityProvider` 端口；没有校方授权、协议和密钥时不能宣称已接入 CAS。

**追问要点：** 未来 CAS 适配器应把 provider subject 映射到内部随机 user ID，不能让外部标识成为业务表主键；回调、绑定和解绑仍要审计并防止账号接管。

### 40. 批量库存如何避免并发超卖，为什么不依赖 Redis 锁？

**回答：** MySQL 是库存事实源，下单在事务中以“当前可售数量不少于请求数量”为条件执行扣减，并通过受影响行数判断成功；订单、库存流水、幂等结果和 Outbox 同事务提交或回滚。Redis 故障不能改变库存正确性，因此它不承担最终互斥。

**追问要点：** 多买家竞争时成功数量之和不能超过初始库存；重试必须经过幂等命令，不能重复扣减。

### 41. `Idempotency-Key` 为什么还要绑定请求摘要和原始响应？

**回答：** 只按 key 去重无法区分客户端误用同一 key 发出不同命令。实验同时保存规范化请求摘要和 UTF-8 终态响应：同键同摘要重放原响应，同键异摘要返回 409；失败事务整体回滚，不留下永久阻塞后续重试的半成品记录。

**追问要点：** 幂等记录必须和业务写入在同一事务；摘要要稳定、避免包含不确定序列化字段，并防止记录敏感原文。

### 42. 为什么支付和退款的 `UNKNOWN` 结果不能直接重试创建？

**回答：** 超时只说明调用方不知道结果，不说明提供方没有成功。重新创建可能重复扣款或退款。实验保存 provider reference 与原幂等键，由 `PaymentReconciliationScheduler` 查询同一请求并收敛；迁移把支付尝试限制为一次，修复“超时后再创建”的双扣风险。

**追问要点：** 真实适配器必须原样通过 `PaymentGatewayContract`，覆盖金额分、幂等键、签名、回调重放、创建/查询与未知结果对账。

### 43. 并发部分退款怎样保证不会超过实付金额？

**回答：** 外部退款前先在数据库事务中预占额度，并始终校验 `successful_refund_fen + reserved_refund_fen <= paid_amount_fen`。成功回调把预占转成成功额，失败释放预占，未知结果保留并对账；唯一业务键和行锁使并发请求不能同时越过上限。

**追问要点：** 先调用支付方再记账会留下不可恢复窗口；回调重复、乱序和退回收敛都必须幂等。

### 44. 截止时间任务与用户命令同时到达时如何避免双终态？

**回答：** 截止时间、当前时间和状态裁决都来自 MySQL，窗口采用左闭右开语义。用户命令与调度任务竞争同一状态条件、订单锁或 deadline claim，只有一条条件更新能成功；旧 owner 即使迟到也会被 claim token fencing 拒绝。

**追问要点：** JVM 时间和 Redis 锁都不能作为数据库状态机的最终裁决依据；到达 deadline 时已过期，而不是仍允许一次提交。

### 45. Outbox/Inbox、publisher confirm 和 fencing 分别解决什么问题？

**回答：** Outbox 把业务事实和待发送事件放进同一数据库事务，Inbox 把消费去重和业务提交绑定；publisher confirm 证明 broker 接受了消息后才允许发布完成。租约允许崩溃后接管，owner 与 claim token fencing 阻止旧执行者的迟到 ACK、NACK、完成或重排覆盖新租约。

**追问要点：** 这仍是至少一次投递，不是神奇的恰好一次；正确性来自幂等事件、数据库唯一约束、状态条件和可恢复人工失败路径。

### 46. 什么才算可信退回证明，为什么上传单号或视频不够？

**回答：** 用户上传内容只证明“提交了材料”，系统无法自动证明物品已交还。首版只接受卖家确认、未来物流适配器的已验签签收回调或管理员审计后的明确确认；缺少证明或证据冲突时，硬期限进入 `ESCALATED` 并继续冻结资金，不能默认判任一方胜诉。

**追问要点：** 当前是校园当面交付，不把物流单号当事实；真实物流需要独立签名、重放防护和责任边界。

### 47. 部分退货为什么先进入隔离库存，而不是直接恢复可售数量？

**回答：** 已退商品可能损坏、缺件或与描述不符，退款成功只证明资金与批准数量已处理，不能证明商品可再次销售。批准退回数量进入隔离库存，卖家检查后显式选择重新上架或报损；仅退款没有实物退回，因此不改变库存。

**追问要点：** 库存移动使用唯一业务键，重复回调、重复收敛或并发操作都不能二次入库。

### 48. 为什么结算后的卖家质保不把订单从 `SETTLED` 改回争议态？

**回答：** 七天后订单资金已经结算，长达 30–365 天的质保不能长期冻结或回滚已完成交易。实验创建独立 `warranty_case` 和 `seller_obligation`；责任成立后由卖家主动筹资或未来结算抵扣，逾期限制发布/提现，资金落实后才面向买家退款或补偿。

**追问要点：** 厂家质保和卖家质保是独立承诺；普通退款、历史赔付和质保赔付累计不得超过订单实付金额。

### 49. 私有证据为什么对无权用户返回 404，管理员为何不自动绕过？

**回答：** 区分“存在但无权”的 403 会泄漏案件或证据存在性，因此已认证调用者面对不存在和无权资源得到同构 404。管理员身份不等于案件授权，只有被分配管理员、买方和卖方能访问；物理打开 MinIO 对象前还要重新检查逻辑 ACL。

**追问要点：** Object Key、预签名 URL、内容哈希和完整异常不得进入响应、日志、审计或指标标签；存储不可用才返回 503。

### 50. 怎样证明三轮故障演练真的恢复了业务不变量？

**回答：** 每轮分别断开 RabbitMQ、Elasticsearch 或 MinIO，先观察可恢复失败、503 或持久积压，再恢复 route、运行 dispatcher/reconciliation/cleanup，最后重新检查库存非负、退款成功额与预占额不超实付、单次结算、无过期未决租约、证据 ACL 未放宽，以及 MySQL 与 SmartCN Elasticsearch 在售集合一致。

**追问要点：** Rabbit publish 是 HTTP 提交后的异步边界，应以 Outbox 积压、attempt 和 retry 指标证明，不能虚构业务 HTTP 503；Docker 不可用、外部测试 skipped 或只做单轮都不算完整验收。

### 51. 为什么 AI 客服不能直接拿原始私人问题和 Token 调模型？

**回答：** 模型供应商不是授权边界，也不需要身份凭证。服务先把私人问题归一化为固定安全模板，在交易服务内用原始 Token 做对象级授权，只把最小状态保留在本地；供应商只接收公开问题或安全模板与已审阅规则片段。

**追问要点：** Token、订单/案件 ID、完整对象和任意私人描述都不应进入 prompt；公开问题仍可作为最小必要输入，不能把“最小化”误说成永远没有用户输入。

### 52. 为什么模型回答不能触发退款、争议裁决或订单修改？

**回答：** 这些操作会改变资金、库存和权利义务，必须经过原有鉴权、幂等、状态机、事务和审计边界。生成文本具有不确定性，只能解释规则；即使文字结论正确，也不能替代写命令的业务前置条件和可信证据。

**追问要点：** 工具调用不是天然安全；若未来增加工具，仍需服务端白名单、参数校验、二次确认、幂等键和独立授权，本实验明确不提供交易写工具。

### 53. 如何同时避免越权枚举和模型幻觉？

**回答：** 交易服务负责对象级权限，无权与不存在统一 404；AI 层不根据差异错误推断资源存在。回答只引用带来源版本的公开规则，检索不到、模型超时或依赖失败就明确失败，不用常识补写交易事实；结构化本人状态与生成文本分区展示。

**追问要点：** 规则索引、交易事实和模型是三种独立依赖，不能把任一故障降级为虚构成功；恢复后重试只读请求即可。

### 54. 为什么客服前端只在内存保存 Token？

**回答：** 客服站点不需要跨刷新保持登录。内存会话缩短凭证暴露窗口，401 时立即清空并回到登录态；代价是刷新后重新登录。浏览器始终走同源 Gateway 路径，不把 Token 放入 URL、日志、localStorage、sessionStorage 或测试报告。

**追问要点：** 这不能消除 XSS 风险，仍需输出编码、CSP、依赖治理和短 Token TTL；它只避免长期持久化凭证扩大风险窗口。

## 技术取舍速记

| 选择                       | 收益                              | 代价                                  |
| -------------------------- | --------------------------------- | ------------------------------------- |
| 短期 JWT access token      | 资源访问不需要服务端 session 查询 | 注销后不能立刻撤销已签发 access token |
| 数据库 refresh token       | 可撤销、可轮换、可检测重复使用    | 刷新请求需要访问数据库                |
| 条件更新消费 refresh token | 同一 token 并发时只会成功一次     | 依赖数据库事务与受影响行数判断        |
| 每次请求查询权限           | 权限变化立即生效                  | 增加一次 RBAC 查询                    |
| Flyway + Testcontainers    | 迁移与真实 MySQL 行为可复现       | 本地完整端测依赖 Docker               |
