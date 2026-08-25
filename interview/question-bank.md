# Java 后端面试题库

每个回答必须能回到本仓库的代码、SQL 或测试证据，而不是背诵概念。以下 10 题来自实验一：JWT 与 RBAC 权限服务。文中的 `*` 表示脱敏后的账号、密码、哈希、token 或密钥值。

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

## 技术取舍速记

| 选择                       | 收益                              | 代价                                  |
| -------------------------- | --------------------------------- | ------------------------------------- |
| 短期 JWT access token      | 资源访问不需要服务端 session 查询 | 注销后不能立刻撤销已签发 access token |
| 数据库 refresh token       | 可撤销、可轮换、可检测重复使用    | 刷新请求需要访问数据库                |
| 条件更新消费 refresh token | 同一 token 并发时只会成功一次     | 依赖数据库事务与受影响行数判断        |
| 每次请求查询权限           | 权限变化立即生效                  | 增加一次 RBAC 查询                    |
| Flyway + Testcontainers    | 迁移与真实 MySQL 行为可复现       | 本地完整端测依赖 Docker               |
