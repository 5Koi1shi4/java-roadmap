# Java 后端面试题库

> 题目由 AI 基于仓库实现与测试生成，仅供参考；回答应结合实际代码验证，不能替代对实现和测试的阅读。

## 实验一：JWT 与 RBAC 权限服务

### 1. 一次受保护请求如何建立 `SecurityContext`？
**参考回答：** 过滤器验证 Bearer access JWT 的签名、过期时间和用途，从 subject 取得用户标识，再查询当前权限并构造带 authorities 的 Authentication 放入 SecurityContext。
**代码/测试证据：** `labs/01-security-rbac` 的 `JwtAuthenticationFilter`、`RbacService`、`SecurityConfig`；`AdminAuthorizationIT`。

### 2. 401 与 403 应如何区分？
**参考回答：** 凭据缺失、无效或认证失败是 401；身份已建立但不具备目标权限是 403。有效普通用户访问管理员接口不能被错误归类为 401。
**代码/测试证据：** `SecurityConfig` 的 entry point 与 access-denied handler；`AdminAuthorizationIT` 的 401、403 分支。

### 3. 为什么 access token 与 refresh token 要分离？
**参考回答：** access token 面向资源访问且寿命较短；refresh token 只用于续期、寿命较长并由服务端管理。分离能限制 access token 泄露窗口，并允许长期会话撤销和轮换。
**代码/测试证据：** `JwtTokenService.issueAccessToken`、`RefreshTokenService`、`SecurityBeansConfiguration`。

### 4. refresh token 为什么只保存哈希？
**参考回答：** 数据库存储原始长期凭据会使泄露直接可重放。服务端生成高熵随机值，只保存其 SHA-256 摘要；刷新时重新摘要输入值后查询。
**代码/测试证据：** `RefreshTokenService.newRawToken`、`sha256`、`JdbcRefreshTokenSessionRepository`。

### 5. 并发刷新同一 refresh token 时如何保证只成功一次？
**参考回答：** 不能先读取再撤销，因为两个请求都可能读到可用。以 `revoked=false` 和未过期条件执行单条条件更新，只有影响一行的事务能签发 successor token。
**代码/测试证据：** `RefreshTokenSessionRepository.revokeIfUsable`、`JdbcRefreshTokenSessionRepository`；`AuthFlowE2ETest.allowsOnlyOneConcurrentRefreshForTheSameToken`。

### 6. logout 后 access JWT 为什么仍可能有效？
**参考回答：** 无状态 access JWT 没有逐个撤销记录；logout 撤销的是 refresh token。因此已签发 access JWT 可使用到自然过期，短 TTL 仅缩小该窗口。
**代码/测试证据：** `AuthController.logout`、`RefreshTokenService`；`AuthFlowE2ETest` 的注销后刷新断言。

### 7. 为什么密码使用 BCrypt 而不是 SHA-256？
**参考回答：** 密码是低熵的人类输入，需要有盐且可调成本的慢哈希；SHA-256 更适合摘要。校验应调用 `PasswordEncoder.matches`，不能比较两次生成的哈希字符串。
**代码/测试证据：** `AuthService.authenticate`、`SecurityBeansConfiguration` 中的 `BCryptPasswordEncoder`。

### 8. JWT 签名密钥为何必须配置化？
**参考回答：** 重启或多实例都必须验证仍未过期的 token；临时生成密钥会让旧 token 立即失效。密钥应从环境配置读取，采用足够长度的随机 Base64 值且不提交到仓库。
**代码/测试证据：** `SecurityBeansConfiguration.jwtTokenService`、`application.yml`、`.env.example`。

### 9. Flyway 在 RBAC 实验中解决什么问题？
**参考回答：** 用户、角色、权限、关联和 refresh-session 表及其约束需要可重复建立。版本化迁移让新环境获得一致结构，避免手工建表漂移。
**代码/测试证据：** `V1__rbac_schema.sql`；`RbacSchemaIT`。

### 10. 为什么同时需要 MockMvc 与真实 HTTP/MySQL 测试？
**参考回答：** MockMvc 快速覆盖安全链分支；真实 HTTP 测试覆盖随机端口、序列化、Flyway、JDBC 与 MySQL 事务的组合，并可验证并发刷新竞态。
**代码/测试证据：** `AdminAuthorizationIT`；`AuthFlowE2ETest` 使用 `TestRestTemplate` 与 MySQL Testcontainers。

## 实验二：Redis Cache Aside 与更新一致性

### 11. Cache Aside 的读取主流程是什么？
**参考回答：** 先读缓存，命中直接返回；未命中才读取 MySQL，并把存在值或受控空值写回缓存。缓存或数据库故障不能伪装成不存在。
**代码/测试证据：** `ProductQueryService`、`ProductCache`；`ProductQueryServiceTest`。

### 12. 为什么缓存必须在数据库提交后失效？
**参考回答：** 提交前删除会让其他读请求把旧数据库值回填；回滚时也会无谓丢掉正确缓存。注册 afterCommit 后删除才能令失效与已提交事实对齐。
**代码/测试证据：** `ProductUpdateService` 的 `TransactionSynchronization.afterCommit`；`ProductUpdateServiceTest`。

### 13. 数据库回滚时缓存应怎样处理？
**参考回答：** 未提交写入不应改变对外可见缓存，因此回滚不会触发提交后失效，旧缓存保留，读者仍看到未提交写入之前的一致状态。
**代码/测试证据：** `ProductUpdateService`；`ProductUpdateServiceTest` 的回滚断言。

### 14. 空值缓存解决什么问题？
**参考回答：** 对不存在商品缓存短 TTL 的空结果，防止大量重复请求不断回源。它只表达已确认不存在；Redis 或 MySQL 异常仍应单独报错。
**代码/测试证据：** `ProductCache`、`ProductQueryService`；`ProductQueryServiceTest`。

### 15. 热点 key 重建为什么需要分布式锁？
**参考回答：** 热点失效后，多实例同时回源会形成击穿。Redis 上按商品 ID 的锁使同一时刻仅一个持锁者重建，其他请求等待或走受控失败路径。
**代码/测试证据：** `RedissonRebuildLock`、`CacheConfiguration`；`ProductQueryServiceRedissonRebuildLockIT.twoIndependentRedissonClientsRebuildOneExpiredHotKeyOnlyOnceForOneHundredConcurrentRequests`。

### 16. 为什么获得锁后还要二次检查，并设定锁等待上限？
**参考回答：** 等锁期间前一个请求可能已回填缓存，二次检查避免重复查询。等待上限防止请求无限堆积；超时后最后再读缓存，仍未命中才以受控忙碌失败。
**代码/测试证据：** `RedissonRebuildLock`、`ProductQueryService`；`ProductQueryServiceTest.rebuildsAnExpiredHotKeyWithExactlyOneRepositoryLookup`。

### 17. Redis 删除在提交后失败时，事务语义是什么？
**参考回答：** 数据库已提交，Redis 删除失败不能回滚它；应暴露失败并依赖重试或观测处理缓存滞后。它不是把 Redis 与数据库包装成分布式事务。
**代码/测试证据：** `ProductUpdateService`；`ProductUpdateServiceTest.keepsUpdatedProductWhenCacheEvictionFailsAfterCommit`。

### 18. 如何用指标和 k6 解释热点缓存效果？
**参考回答：** 同时观察命中、未命中、实际回源、锁忙和锁等待。预热后固定热点压测的回源增量应为零；若依赖或锁等待异常，指标和阈值会揭示问题，不能虚构结果。
**代码/测试证据：** `CacheMetrics`、`k6/hot-product.js`、`ProductQueryServiceRedissonRebuildLockIT`。

## 实验三：秒杀、库存与接口幂等

### 19. 条件扣库存如何防止超卖？
**参考回答：** 用 `stock > 0` 作为 UPDATE 条件并依据影响行数判断是否成功，避免先读库存再写的竞态。影响行数为零即表示已售罄或目标不满足扣减条件。
**代码/测试证据：** `JdbcProductRepository` 的条件更新；`SeckillOrderHttpIT`。

### 20. 扣库存与创建订单为何必须在同一事务？
**参考回答：** 扣减成功后订单写入失败必须回滚扣减；反之订单不能脱离库存事实存在。单一事务将两个状态变化作为一个原子结果提交。
**代码/测试证据：** `SeckillOrderService` 的 `@Transactional` 编排；`SeckillOrderHttpIT`。

### 21. 用户/商品唯一约束解决什么并发问题？
**参考回答：** `(user_id, product_id)` 唯一约束把每人每商品只能成交一次交给共享数据库裁决。冲突请求的事务回滚，已执行的库存扣减也不会留下。
**代码/测试证据：** `V1__seckill_schema.sql`；`SeckillSchemaIT`、`SeckillOrderHttpIT`。

### 22. 幂等键为何还要绑定请求摘要？
**参考回答：** 同一 key 的重试必须携带相同语义；保存请求摘要可拒绝同 key 不同参数，避免调用方复用 key 意外得到另一条命令的结果。
**代码/测试证据：** `IdempotencyService`、`idempotency_record`；`SeckillOrderHttpIT`。

### 23. 重试怎样重放原始响应？
**参考回答：** 终态记录保存状态码、原始 UTF-8 JSON 与内容类型；相同 key 和摘要命中终态时原样返回，而不是重新执行业务或重新序列化不同响应。
**代码/测试证据：** `JdbcIdempotencyRepository`；`SeckillOrderHttpIT` 的 UTF-8 重放测试。

### 24. 多实例下 PROCESSING 记录怎样领取和接管？
**参考回答：** 实例竞争共享 MySQL 的唯一键，失败者读取并短暂轮询终态。仅处理超过超时窗口的记录可接管，窗口需要与最长业务耗时匹配，不能用进程内锁代替。
**代码/测试证据：** `JdbcIdempotencyRepository`、`IdempotencyService`；`SeckillOrderHttpIT` 的双实例与首事务回滚场景。

### 25. 为什么固定锁顺序能降低死锁？
**参考回答：** 所有路径按幂等记录、商品库存、订单唯一索引的顺序取得数据库锁，避免两条路径互相等待对方先持有的锁。真实死锁或锁超时才映射可重试冲突。
**代码/测试证据：** `SeckillOrderService`、`JdbcIdempotencyRepository`；`SeckillOrderHttpIT` 的锁冲突覆盖。

### 26. 为什么需要真实 MySQL 并发测试？
**参考回答：** 内存替身无法证明唯一索引、行锁、隔离级别和回滚行为。真实 HTTP 与 MySQL Testcontainers 可同时验证售罄、重复购买、并发扣减和跨实例重放。
**代码/测试证据：** `SeckillOrderHttpIT`、`SeckillSchemaIT`。

## 实验四：订单状态机与 RabbitMQ 可靠消息

### 27. 支付与取消为什么使用带原状态的条件更新？
**参考回答：** `PENDING_PAYMENT` 是支付和超时取消的共同前置状态。以旧状态写 WHERE 条件后，只有一方能迁移；受影响行数为零时读取当前状态而非覆盖它。
**代码/测试证据：** `JdbcOrderRepository.markPaidIfPending`、`cancelIfPending`；`OrderPersistenceIT.doesNotCancelPaidOrder`。

### 28. Outbox 如何解决订单与消息的双写窗口？
**参考回答：** 建单事务内同时写订单和超时 Outbox 行，提交后由独立 dispatcher 发布。数据库提交不等于 broker 收到消息，因此发布完成还必须等待 publisher confirm。
**代码/测试证据：** `OrderService.createOrder`、`OutboxDispatcher`、`OrderEventPublisher`；`OrderPersistenceIT.persistsOrderAndTimeoutEventAtomically`。

### 29. Outbox 租约和 claim token 分别解决什么？
**参考回答：** 租约让崩溃实例持有的 `PUBLISHING` 事件可被接管；claim token 使旧 owner 的迟到 ACK、NACK 或失败写入无法覆盖新 owner 的领取结果。
**代码/测试证据：** `JdbcOrderRepository.claimPublishable`、`markPublished`、`releaseForRetry`；`OutboxLeaseIT`。

### 30. 消费者何时 ACK，PROCESSING 与 COMPLETED 如何划分？
**参考回答：** 先领取消费记录，再在同一数据库事务完成条件取消、实际库存释放和 `COMPLETED` 标记；事务提交前不得 ACK。已完成消息可确认但不再执行业务。
**代码/测试证据：** `OrderTimeoutConsumer.handle`、`JdbcOrderRepository.beginConsumption`、`completeConsumption`；`ReliableMessagingFlowIT`。

### 31. 重复投递为何不会重复释放库存？
**参考回答：** `eventId` 的消费记录唯一，终态 `COMPLETED` 直接跳过；同时取消操作也只匹配待支付订单。两个幂等边界共同保证库存最多释放一次。
**代码/测试证据：** `consumed_message` 迁移、`OrderService.cancelExpired`；`OrderTimeoutIT.duplicateEventIdCancelsAndReleasesStockOnlyOnce`。

### 32. 如何区分可重试与不可重试失败？
**参考回答：** 连接、死锁、锁等待和事务回滚等瞬态基础设施异常可重试；JSON、字段或版本校验以及非法状态迁移是不可恢复输入或业务错误，应直接停放并记录分类。
**代码/测试证据：** `FailureClassifier`；`FailureClassifierTest.classifiesBrokenJsonAndUnsupportedVersionAsNonRetryable`。

### 33. 有界重试怎样避免次数偏差？
**参考回答：** Spring Retry 的 `maxAttempts=3` 包含首次处理。恢复器读取实际 attempt；不可重试错误只记录一次，可重试耗尽才记录三次，非法重试头不能绕过预算。
**代码/测试证据：** `RabbitTopologyConfiguration.timeoutRetryInterceptor`、`timeoutMessageRecoverer`；`RabbitTopologyConfigurationTest`。

### 34. TTL 桶与 DLX 的职责是什么？
**参考回答：** RabbitMQ TTL 只表示到期后可处理而非精确定时；不同延迟放入固定 TTL 桶避免队头阻塞。到期消息经 DLX 路由到取消队列，超出 delivery limit 再进入人工路径。
**代码/测试证据：** `RabbitTopologyConfiguration`；`OrderTimeoutIT.declaresTtlBucketsAndDeadLetterDestination`。

### 35. 为什么事件构造与反序列化边界要快速失败？
**参考回答：** 消息边界验证 eventId、类型、订单号、时间和 schema version，未知字段或非法值在进入领域服务前失败。服务层因而只处理状态和事务规则，不承担 JSON 清洗。
**代码/测试证据：** `OrderTimeoutEvent`、`OrderTimeoutConsumer.consume`；`OrderServiceTest.rejectsMalformedTimeoutEventBeforeChangingOrder`。

### 36. 共享 Testcontainers 如何同时保障效率与隔离？
**参考回答：** 容器可在测试类间共享以降低启动成本，但每个测试在 setup 清理业务表、恢复库存并同步 purge 队列；事件 ID 与订单数据仍独立，避免旧消息污染断言。
**代码/测试证据：** `SharedContainers`、`OrderSchemaIT`；`ReliableMessagingFlowIT` 的 `@BeforeEach`。

## 实验五：Elasticsearch 商品搜索

### 37. 为什么 MySQL 是事实源而 Elasticsearch 只是读模型？
**参考回答：** 商品写入先提交 MySQL 与不可变 Outbox；搜索不可用不会让已提交商品事实消失。索引可由商品快照和事件重建，因此不能承担唯一事实源职责。
**代码/测试证据：** `ProductCommandService`、`ElasticsearchProductSearchGateway`；`ProductPersistenceIT.commitsProductAndOutboxInOneTransaction`。

### 38. 搜索 Outbox 如何避免商品与索引事件的双写丢失？
**参考回答：** 创建、更新和删除在同一 MySQL 事务内写 `product` 与 `search_outbox`。若 Outbox 写入或约束失败，商品变更一起回滚，异步 dispatcher 才负责之后的索引同步。
**代码/测试证据：** `ProductCommandService`、`JdbcSearchOutboxRepository.append`；`ProductPersistenceIT.rollsProductUpdateBackWhenOutboxUniqueKeyRejectsEvent`。

### 39. 调度租约如何保证不同 dispatcher 领取互斥事件？
**参考回答：** 使用数据库时间筛选新事件或过期处理事件，`FOR UPDATE SKIP LOCKED` 领取后写 owner、token 和 lease。崩溃后的过期租约可接管，批量和租约受运行配置约束。
**代码/测试证据：** `JdbcSearchOutboxRepository.claim`、`SearchProperties`；`OutboxLeaseIT.twoIndependentTransactionsClaimDisjointEventSets`。

### 40. claim-token fencing 如何阻止迟到 owner 写入？
**参考回答：** complete、reschedule 和 fail 都同时匹配 event ID 与当前 claim token。旧 owner 在租约过期后即使拿到迟到响应，更新影响行数为零，不能覆盖接管者。
**代码/测试证据：** `OutboxDispatcher.applyResult`、`JdbcSearchOutboxRepository.complete/reschedule/fail`；`OutboxLeaseIT.oldTokenCannotCompleteRescheduleOrFailConcurrentlyAfterTakeover`。

### 41. 为什么索引写使用 `external_gte`？
**参考回答：** 至少一次投递可能乱序或重复。携带 MySQL 商品版本并采用 `external_gte`，相同版本可幂等写入，旧版本不能覆盖新版本。
**代码/测试证据：** `ElasticsearchSearchIndexWriter.bulkWrite`；`ExternalVersionIT.identicalPayloadAtSameSourceVersionIsIdempotentlyApplied`。

### 42. 删除为何使用 tombstone？
**参考回答：** 直接物理删除会让迟到的旧 upsert 复活文档。tombstone 保留删除版本和状态，在相同版本规则下拒绝旧事件；公开查询仍过滤 `ON_SALE`。
**代码/测试证据：** `IndexMutation.tombstone`、`ProductCommandService.delete`；`ExternalVersionIT.staleVersionCannotOverwriteOrReviveTombstone`。

### 43. SmartCN 如何被实际验证？
**参考回答：** 索引字段配置 SmartCN analyzer，测试调用真实 `_analyze` 检查中文词元，而不只检查配置名字。严格 mapping 也拒绝未知字段，防止索引契约悄然漂移。
**代码/测试证据：** `ElasticsearchIndexManager.create`；`ElasticsearchIndexIT.installsSmartCnAndRejectsUnknownFields`。

### 44. 相关性和过滤条件为何要分离？
**参考回答：** 文本关键词通过带权重的 `multi_match` 参与评分；在售状态、分类和价格在 bool filter 中约束，不影响文本评分。无关键词或同分时用稳定排序收敛。
**代码/测试证据：** `ElasticsearchProductSearchGateway.query`、`applySort`；`ProductSearchHttpIT.searchesSmartCnWithWeightedNameHighlightFiltersSortAndBuckets`。

### 45. 在线重建怎样原子切换读写别名？
**参考回答：** 先建立新物理索引、导入快照并补放高水位后的事件；最终校验后在一个 alias 请求中同时切换 read/write。别名状态不确定时 fail closed，交给恢复流程核对真实目标。
**代码/测试证据：** `SearchRebuildService`、`ElasticsearchIndexManager`；`RebuildAndRecoveryDrillIT`。

### 46. 高水位恢复为何必不可少？
**参考回答：** 快照建立期间仍可能有商品写入。记录快照高水位并补放之后的 Outbox 事件，才能避免切换到遗漏新写入的索引；失败可由事实源和事件再次收敛。
**代码/测试证据：** `SearchRebuildService`、`JdbcSearchOutboxRepository`；`RebuildAndRecoveryDrillIT`。

## 实验六：安全文件服务与 MinIO

### 47. 为什么上传流程要拆成短事务与事务外对象写入？
**参考回答：** 文件流和对象存储 IO 不应长时间占据数据库事务。流程分别创建会话、事务外写临时对象、短事务确认 Blob、事务外提交正式对象，再以状态和补偿任务收敛。
**代码/测试证据：** `UploadService`、`UploadTransactionService`、`StagingRecoveryService`。

### 48. 如何做到去重而不泄漏内容是否已存在？
**参考回答：** 内容哈希唯一约束可复用一个物理 Blob，但每次上传创建独立逻辑文件和 ACL。响应、日志和指标不暴露命中信息、哈希、Blob ID 或 object key。
**代码/测试证据：** `BlobRepository`、`SecureStorageKeyFactory`、`JdbcAuditRecorder`；`AuditSanitizerTest`。

### 49. 为什么无权访问私有文件应返回同构 404？
**参考回答：** 对已认证调用者，文件不存在、已删除和无权使用同一结构的 404，避免泄漏资源存在性。管理员角色也没有绕过文件 ACL 的旁路。
**代码/测试证据：** `FileAccessService`、`ApiExceptionHandler`；`FileAccessServiceTest`。

### 50. `/content` 与预签名 URL 的安全边界有什么不同？
**参考回答：** `/content` 在流式传输前重查 ACL，可支持即时撤权；预签名 URL 在签发时授权，存储服务随后直接处理，撤权存在最多 TTL 的残余窗口。两者都不记录完整链接。
**代码/测试证据：** `DownloadService`、`MinioObjectStorage`、`DownloadController`；下载相关集成测试。

### 51. 租约、token 与 generation 如何保护清理和恢复？
**参考回答：** 领取任务写入新的 owner、claim token 与 lease；状态推进、接管和完成同时匹配目标状态、token、对象 key 和 generation。迟到执行者影响行数为零，不能覆盖新一代 Blob。
**代码/测试证据：** `StorageCleanupService`、`StagingRecoveryService`、`CleanupTaskRepository`。

### 52. 为什么物理删除需要幂等？
**参考回答：** 对象存储删除可能已完成但确认丢失，重试时不存在应按删除成功处理。Blob 与清理任务的最终完成要在同一显式事务里以条件更新写入。
**代码/测试证据：** `StorageCleanupService`、`ObjectStorage`；清理集成测试。

### 53. 文件服务的可观测性怎样避免泄漏隐私？
**参考回答：** 审计和指标只采用固定 action、result、phase 等低基数枚举，过滤 token、URL、路径、哈希、对象 key、异常文本和用户/文件 ID。内部 correlation ID 也不能由客户端覆盖。
**代码/测试证据：** `JdbcAuditRecorder`、`AuditSanitizerTest`、`FileMetrics`。

### 54. MinIO 重复故障恢复应验证哪些不变量？
**参考回答：** 每轮先观察失败或积压，恢复后检查会话和清理任务终态、临时对象清空，以及数据库 READY object key 与 bucket 已知 Blob 集合一致。该检查不等于宣称能枚举任意外部对象。
**代码/测试证据：** `StagingRecoveryService`、`StorageCleanupService`；恢复演练集成测试。

## 实验七：校园二手交易平台

### 55. 校园邮箱验证的信任边界是什么？
**参考回答：** 验证码只证明对校园邮箱的控制，不等同于学校正式身份认证。验证码不得写入日志；CAS 在实验中仅是适配端口，不能冒充已接入的校方系统。
**代码/测试证据：** `EmailVerificationService`、`LocalVerificationMailSender`、`ExternalIdentityProvider`。

### 56. 批量库存下单如何维持一致性？
**参考回答：** 库存以 MySQL 为事实源，扣减与订单创建在本地事务中按业务规则执行；库存流水记录变化。Redis 只服务验证码和限流，不能证明交易扣减正确性。
**代码/测试证据：** `ListingService`、`JdbcInventoryRepository`、`CreateOrderService`；`CampusMarketJourneyIT`。

### 57. 交易命令如何提供幂等结果？
**参考回答：** 必填 `Idempotency-Key` 绑定请求摘要；同键同参重放原始 UTF-8 终态响应，同键异参返回 409。失败事务不能留下永久阻塞的幂等记录。
**代码/测试证据：** `CreateOrderService`、幂等记录持久化；`CampusMarketJourneyIT`。

### 58. 支付或退款返回 UNKNOWN 时为什么不能重发？
**参考回答：** 连接中断不代表外部操作未成功。保留 provider reference 和幂等键后查询原请求结果来收敛，盲目重发可能形成重复扣款或退款。
**代码/测试证据：** `PaymentService`、`RefundService`、`PaymentReconciliationScheduler`。

### 59. 部分退款为何先预留额度？
**参考回答：** 并发退款前先预留，保证成功退款额与预留额之和不超过实付金额。预留、回调收敛和失败释放均必须由事务状态机处理。
**代码/测试证据：** `RefundService`、退款持久化；支付/退款集成测试。

### 60. 截止时间竞争如何以数据库时间裁决？
**参考回答：** 截止扫描只取候选，逐项事务按主键锁定后以当前状态、金额和数据库时间条件更新；到达截止时即过期。应用时钟和扫描列表只作提示，不能取代最终裁决。
**代码/测试证据：** `DeadlineScheduler`、`OrderLifecycleService`；截止时间竞争测试。

### 61. Outbox、Inbox、confirm 与 fencing 各自解决什么问题？
**参考回答：** Outbox 将业务事实与待发事件同事务保存；Inbox 将消费去重与业务提交绑定；confirm 证明 broker 已接收；租约及 claim token 防止旧 owner 的迟到完成覆盖接管者。它们共同实现至少一次下的可恢复幂等。
**代码/测试证据：** `OutboxDispatcher`、`ReliableEventConsumer`、`integration_outbox`；`RecoveryDrillIT`。

### 62. 什么是可信退回证明？
**参考回答：** 可接受卖家确认、已验签物流签收回调或管理员明确确认。单号、图片和视频仅是材料，不自动证明已退回；缺证或冲突在硬期限后进入 `ESCALATED` 并继续冻结资金。
**代码/测试证据：** `ReturnResolutionService`、`DisputeService`；售后旅程测试。

### 63. 部分退货为什么进入隔离库存？
**参考回答：** 退款成功不等于商品已可再次销售。批准退回数量先隔离，卖家检查后显式重新上架或报损；仅退款不改变库存。
**代码/测试证据：** `ReturnResolutionService`、`JdbcInventoryRepository`；售后库存测试。

### 64. 结算后的卖家质保为何独立于订单状态？
**参考回答：** 已结算订单保持 `SETTLED`，长期质保由独立案件和卖家义务流转。义务逾期可限制发布/提现，未来结算按唯一业务键抵扣，不能回滚既有订单结算。
**代码/测试证据：** `WarrantyService`、`SellerObligationService`；质保用例测试。

### 65. 证据 ACL 为什么不让管理员角色自动绕过？
**参考回答：** 证据只对案件买卖双方及被分配管理员可见；角色本身不证明已获案件授权。打开 MinIO 对象前仍须复查逻辑 ACL，无权和不存在统一 404。
**代码/测试证据：** `DisputeService`、`ObjectUploadCoordinator`；`RecoveryInvariantStagesIT` 的 ACL 阶段。

### 66. 三轮恢复演练要证明什么？
**参考回答：** 分别注入 RabbitMQ、Elasticsearch、MinIO 故障，恢复后验证库存非负、退款界限、一次结算、租约收敛、ACL 未放宽及 MySQL 与 SmartCN 在售集合一致。Rabbit 发布为提交后异步边界，应看 Outbox 积压和重试而非虚构 HTTP 失败。
**代码/测试证据：** `RecoveryDrillIT`、`RecoveryInvariantStagesIT`。

## 实验八：Spring Cloud 渐进拆分

### 67. 为什么要 identity-first 拆分？
**参考回答：** 身份验证、密码校验和 token 签发有清晰边界，可先独立部署；库存、订单和退款仍保留在交易事务边界内，避免同时引入分布式资金事务。
**代码/测试证据：** `identity-service` 的 `AuthController`、`EmailVerificationService`；`CloudJourneyIT`。

### 68. Gateway 已认证后，业务服务为何仍要复验？
**参考回答：** 业务服务可能被直接访问，客户端 Header 不能当身份事实。Gateway 与资源服务均验证签名及 `kid/iss/aud/sub/roles/iat/exp` 契约，Gateway 清理伪造 Header 只是入口防护。
**代码/测试证据：** `api-gateway` 安全配置、资源服务 JWT decoder；`CloudJourneyIT`。

### 69. RS256 与 JWKS 带来什么密钥边界？
**参考回答：** 身份服务独占私钥，验证服务只获取公钥；JWKS 以 `kid` 选择密钥，未知 kid 不能放行。轮换还需处理获取、缓存和冷启动失败语义。
**代码/测试证据：** `identity-service` JWKS endpoint、`JwksKeyProvider`；JWKS 集成测试。

### 70. 冷热 JWKS 缓存故障应如何处理？
**参考回答：** 已缓存公钥且 token 未过期时可继续验签；冷启动没有可用密钥时必须拒绝。未知 kid 触发受控刷新，但刷新失败不能用旧错误密钥放行。
**代码/测试证据：** JWKS 缓存组件；`CloudJourneyIT` 的身份停机、冷启动与刷新场景。

### 71. liveness 与 readiness 为什么不能混为一谈？
**参考回答：** liveness 表示进程仍能响应；readiness 还检查可用 JWKS、注册中心和必需实例。短暂探测缓存只缓冲依赖波动，不证明所有首次验签或下游请求都能成功。
**代码/测试证据：** 各服务 Actuator health contributor；Cloud 集成测试的 health/liveness/readiness 断言。

### 72. 为什么 Gateway 不自动重试写请求？
**参考回答：** 断连不能说明下游事务未提交；自动重试注册、验证码或交易命令可能重复副作用。写命令由显式幂等键定义重放语义，Gateway 不猜测成功。
**代码/测试证据：** `api-gateway` 路由/重试配置；`CloudJourneyIT`。

### 73. 数据库所有权怎样落到可验证约束？
**参考回答：** identity、交易和商品读模型各有数据库、迁移账号与最小运行权限；模块通过公开接口和逻辑 ID 协作，不依赖跨库查询、跨库外键或对方领域模型。
**代码/测试证据：** 三个模块的 Flyway 与 datasource 配置；数据库所有权集成测试。

### 74. 截止调度与筹资为何可能死锁，如何规避？
**参考回答：** 一个路径先锁二级索引、另一路先锁主键会形成反向等待。候选扫描不持锁，逐项事务统一按主键锁定并用当前状态和数据库截止时间条件更新。
**代码/测试证据：** `DeadlineScheduler`、`SellerObligationService`；死锁回归测试。

### 75. 为什么聚合旅程会触发 classpath/自动配置隔离问题？
**参考回答：** 同一测试 JVM 中不同应用的依赖都可见，JDBC 或 servlet 自动配置可能被错误装入 Gateway 或 Discovery。每个应用需显式限定自动配置、web 类型、配置文件和注册名。
**代码/测试证据：** 各应用启动类与自动配置排除；五应用 `CloudJourneyIT`。

### 76. `.gitignore` 为什么不能代替 `.dockerignore`？
**参考回答：** Git 与 Docker 构建上下文使用不同忽略规则。即使本地密钥未被 Git 跟踪，仍可能被发送到 Docker daemon；`.dockerignore` 要只放行构建所需内容并经实际构建验证。
**代码/测试证据：** 根 `.dockerignore`、各模块 `Dockerfile`；隔离 Compose smoke 测试。

### 77. 为什么商品投影的事实所有权留在交易服务？
**参考回答：** 商品读服务是可重建投影，交易服务仍拥有商品、库存和订单事实。源端在交易事务内写不可变快照 Outbox，读侧不得把后来查询到的状态伪装为历史事件。
**代码/测试证据：** `ProductSnapshotPublisherDispatcher`、`ProductEventConsumer`；商品读旅程测试。

### 78. Inbox 与 aggregate version 如何配合处理重复和乱序？
**参考回答：** Inbox 以 event ID 去掉同一事件的重复投递；不同事件仍可能乱序，因此投影用 aggregate version 条件更新，拒绝旧版本覆盖新版本。索引 Outbox 继续将投影与 ES 同步解耦。
**代码/测试证据：** `ProductEventConsumer`、投影仓储、索引 Outbox；商品读集成测试。

### 79. 在线重建 fencing 如何保护读模型切换？
**参考回答：** 重建门禁保存 generation、owner、token 和 lease；旧 owner 不能在新任务接管后完成切换。快照、高水位补放、最终校验和 read/write alias 原子切换共同避免丢事件。
**代码/测试证据：** `ProductSearchRebuildRecoveryService`、重建门禁仓储；在线重建恢复测试。

### 80. 零事件 replay 为什么仍需完成屏障？
**参考回答：** 零事件不表示源端已回放完成。读侧要记录 replay ID 和高水位，等源端完成屏障、索引待办清空且人工失败队列为空后才 READY，否则搜索应返回 503。
**代码/测试证据：** `ProductEventConsumer`、replay 状态组件；商品读恢复集成测试。

## 实验九：AI 校园客服

### 81. 私人提问为何要最小化 prompt 与 token？
**参考回答：** 模型供应商不是授权边界，也不需要用户 token。服务先在本地完成对象授权，将私人问题归一为固定安全模板，只发送最少公开问题/模板和已审阅规则片段。
**代码/测试证据：** `ai-support-service` 的 `AnswerService`、`PolicyRetriever`；AI 支持集成测试。

### 82. 为什么 AI 服务必须只读？
**参考回答：** 退款、裁决和订单修改会改变资金、库存和权利义务，必须通过既有授权、幂等、状态机和审计边界。生成文本只能解释规则，不能成为交易写命令。
**代码/测试证据：** `AnswerService`、AI 服务路由定义；AI 支持服务测试。

### 83. 如何同时防止越权枚举与模型幻觉？
**参考回答：** 对象授权由交易服务完成，无权与不存在统一 404；AI 层不从错误差异推断资源存在。回答仅依据有版本来源的规则和结构化本人状态，缺依据时明确失败而不补写事实。
**代码/测试证据：** `HttpTradeStatusReader`、`PolicyRetriever`、`AnswerService`；授权与回答边界测试。

### 84. 为什么交易状态、规则索引和模型需要三类失败语义？
**参考回答：** 交易状态失败不能伪装成没有订单，规则索引失败意味着没有可引用依据，模型失败意味着不能生成文本。前端应显示脱敏且可诊断的重试提示，恢复后只重试读取。
**代码/测试证据：** `HttpTradeStatusReader`、`PolicyRetriever`、模型客户端异常映射；AI 故障恢复测试。

### 85. 前端为什么只在内存保存 token？
**参考回答：** 客服页面不要求跨刷新保持登录；内存会话缩小持久化暴露面，401 时清空并回登录态。token 不进入 URL、日志、localStorage、sessionStorage 或测试报告。
**代码/测试证据：** `support-web` 会话状态实现；Playwright 会话清理测试。

### 86. 版本化政策引用如何约束生成回答？
**参考回答：** 检索结果必须携带已审阅规则片段和版本来源，回答引用这些受控事实而非模型常识。没有匹配规则或版本不可用时返回明确边界，不把猜测写成政策。
**代码/测试证据：** `PolicyRetriever`、规则索引资源；AI 支持单元测试。

### 87. 提示注入的边界应放在哪里？
**参考回答：** 用户文本是非可信输入，不能改变服务端固定模板、授权范围、规则选择或工具能力。AI 服务不外发 token 和完整交易对象，也不提供可写交易工具。
**代码/测试证据：** `AnswerService` 的模板编排、模型请求 DTO；提示注入边界测试。

### 88. Redis 限流为什么是固定分钟桶且 fail-closed？
**参考回答：** 实现按固定分钟键计数，到下一个分钟边界重置；不保留跨边界历史，也不维护补充额度。Redis 不可用时拒绝 AI 请求，避免故障时失去成本与滥用控制。
**代码/测试证据：** AI 服务的 Redis rate limiter；限流与 Redis 故障测试。

### 89. 为什么要分离结构化事实与生成文本？
**参考回答：** 本人订单等事实由受权的交易服务以结构化字段提供，生成文本只解释这些事实和公开政策。分离使前端能识别事实来源，也避免模型输出被误当作交易状态。
**代码/测试证据：** `HttpTradeStatusReader`、`AnswerService` 响应模型；AI 支持集成测试。

### 90. 桌面和移动端全栈 E2E 应证明哪些边界？
**参考回答：** E2E 要经同源入口验证登录、会话清理、授权后的本人状态、规则回答及模型/依赖失败恢复，并覆盖桌面与移动视口。它补足服务测试的浏览器状态与界面集成，而不替代后端外部依赖测试。
**代码/测试证据：** `support-web/e2e` 的 Playwright 用例、`npm run test:e2e`；实验九验收记录。
