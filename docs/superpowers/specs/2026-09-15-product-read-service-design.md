# 实验八 8.2 商品读取服务设计

## 目标与边界

8.1 身份服务已验收。8.2 在同一个 `learning/spring-cloud-split` 单实验分支上增加独立商品读取服务，承接现有 `GET /api/search` 和 `GET /api/listings/search`，不增加商品或交易功能。客户端仍只访问 Gateway，仍使用 8.1 的 RS256 Bearer Token。商品草稿、发布、下架、媒体上传与读取、可售及隔离库存、库存流水、订单、支付和售后命令继续由 `legacy-market-service` 持有。

这是读取边界的渐进拆分，不宣称商品库存事实已经迁出。`market_db` 仍是商品、库存和交易的事实源；新服务独立拥有 `product_read_db` 中的搜索投影与处理进度，以及自己的 Elasticsearch 索引。新服务账号不能访问 `market_db` 或 `identity_db`，兼容单体账号不能访问 `product_read_db`。8.2 只支持全新实验环境，不提供生产不停机切流或存量数据迁移工具。

这条边界保留实验七的硬约束：下单、条件库存扣减、库存流水、命令幂等和订单 Outbox 在同一个 `market_db` 事务中提交或回滚；订单取消、退货隔离、重新上架及报损继续以唯一业务键防止重复变化。卖家发布限制和媒体授权也继续在原交易事务和权限边界内执行。

## 选择与理由

推荐并已确认的方案是先拆读取能力。它让读侧具有独立进程和数据所有权，写侧不等待搜索可用，从而可以单独验证事件追赶、索引重建和服务故障语义。一次迁走商品及库存会把当前的原子下单、取消返还和售后库存事务改成跨库预留与补偿，需要重新设计订单边界和完整正确性证明，属于另一里程碑。让两个服务共享 `market_db` 虽容易启动，但无法证明独立数据所有权，因此不采用。

## 组件与路由

Maven Reactor 增加 `product-read-service`，作为第五个独立 Spring Boot 应用接入 Eureka。Gateway 增加优先于 `/api/**` 的两条显式 `lb://product-read-service` 路由，仅匹配 `GET /api/search` 与 `GET /api/listings/search`；`/api/listings/**` 的写入与媒体路径继续指向 `lb://legacy-market-service`。关闭 discovery locator，禁止服务 ID 自动暴露。读服务独立验证 JWT 的签名、`kid`、`iss`、`aud`、`sub`、`roles`、`iat`、`exp`；无效身份为 401，已认证但无权限为 403。

现有搜索请求、响应字段、中文错误和 `application/json; charset=UTF-8` 不变。新服务只返回 `ON_SALE` 且 `available_quantity > 0` 的索引文档，继续支持 SmartCN 中文查询、分类、价格范围、稳定分页和 `searchAfter`。搜索结果是可滞后的发现视图，不能用于库存扣减或下单授权。读服务或 Elasticsearch 不可用时搜索返回不泄露内部地址、异常堆栈和对象 Key 的 UTF-8 JSON 503；发布、下架、订单和售后命令不自动重试，也不返回伪造的降级搜索结果。

## 数据与事件契约

兼容单体在商品事实变化的原事务内写 `search_outbox`。8.2 事件固定包含不可变 `eventId`、`listingId`、正数 `aggregateVersion`、`eventType`、`occurredAt`、`schemaVersion=2`，以及事务内采集的标题、描述、分类、单价分、可售数量和状态快照。草稿不进入公开搜索；发布、下架、库存扣减与返还、退货隔离、重新上架和报损都必须产生与事实提交同生共死的事件。未知事件类型、版本或非法字段在反序列化边界快速失败，不由服务层清洗。

兼容单体的 publisher 以现有有界领取、数据库时间租约、owner/claim token fencing 和 RabbitMQ publisher confirm 发布持久消息。只有 broker confirm 成功且领取 token 仍有效，才能标记源 Outbox 已发布。读服务停止不会阻塞交易写入；RabbitMQ 停止时源 Outbox 留待恢复发布，不把连接失败误记为商品不存在。短暂外部故障可重试，无法解析的事件进入可检查的失败终态，不能静默 ACK。

读服务消费消息时，以 `eventId` 建立 Inbox 幂等记录，并按商品 `aggregateVersion` 条件更新 `product_read_db` 投影。投影保留最后版本与下架、售罄 tombstone，旧事件或重复事件不能覆盖新事实；投影更新、Inbox 完成和读侧索引 Outbox 在同一个本地事务提交，提交后才 ACK。索引 worker 从读侧 Outbox 向 Elasticsearch 写外部版本或 tombstone；索引失败留待重试，不能让已提交的投影无修复路径。生产模块间不共享 Java 领域模型，只共享明确版本化的消息契约；测试夹具只能以 test scope 引入。

## 重建与恢复

Elasticsearch 可从 `product_read_db` 重建。重建采用读库一致性快照、索引 Outbox 高水位、补放、写入门禁、带租约与 token 的 owner fencing、原子别名切换以及旧索引幂等清理；消费投影在重建期间继续推进，切换前必须补齐高水位以后已提交的变更。索引删失使用外部版本 tombstone，避免晚到旧事件使已下架商品重新出现。

全新环境从空投影启动；源 Outbox 和持久队列保证上线后的事件可追赶。源 `search_outbox` 在本实验保留已发布的完整快照事件，不按发布时间删除。对于消息缺失或投影库丢失，不把单纯 ES 重建称为事实恢复：兼容单体通过只在本机运维入口调用的 replay 命令，先固定源 Outbox `sequence_no` 高水位，再按序以 publisher confirm 重发高水位内的保留事件；正常发布继续运行，读服务以版本条件更新收敛旧事件与并发新事件。replay 进度由 `market_db` 中的租约和 claim token 持久记录，中断后可接管，不经 Gateway 提供公开接口，也不让读服务跨库 SQL。测试需证明中断接管、并发商品变化和高水位补放收敛。

读服务停机期间，搜索经 Gateway 返回安全 503，交易写入继续，消息在持久队列或源 Outbox 中积压；恢复后在有界时间内追赶。Eureka 停机与 JWKS 不可达遵循 8.1 的缓存窗口和冷启动 readiness 规则；新服务冷启动未拿到验证公钥或尚未建立可靠投影时不得报告就绪。故障恢复不允许跳过身份验证、直接查询市场库或把搜索失败当成零结果。

## 验收

先写失败测试，再作最小实现。单元测试覆盖路由优先级、JWT 严格验证、事件字段与版本校验、重复及乱序消费、状态 tombstone、外部版本、领取 fencing、UTF-8 错误。Testcontainers 使用真实 MySQL、RabbitMQ、SmartCN Elasticsearch、Eureka 与独立应用端口，证明三库账号互相拒绝跨库访问，以及发布、售罄、下架、返还、隔离、重新上架和报损事件与源事务同生共死。真实 HTTP 旅程从 Gateway 完成登录、发布、搜索、下单和库存归零，再验证搜索收敛与订单快照；直接访问读服务仍需有效 Token。

故障测试至少覆盖 RabbitMQ、读服务和 Elasticsearch 停机与恢复、重复消息、旧 owner 迟到确认、索引重建期间并发变更、丢失投影后的保留事件 replay。完整实验七迁入回归不得删除或降级为 Mock。JDK 17 与 Docker Engine 可用时在实验根目录执行 `mvnw.cmd test`、`mvnw.cmd verify` 和 `git diff --check`；Surefire/Failsafe 全部 0 failures、0 errors、0 skipped，外部协作 skipped 不算验收。README、架构、迁移边界、排障、学习日志、面试追问与 fresh 测试数量共同记录 8.2 结论。单实验分支活动树仍只保留根 `.gitignore` 和 `labs/08-spring-cloud-split/`，设计与路线状态只更新 `main` 文档中心。

## 后续边界

8.2 不移动库存或订单事实。后续 8.3 订单拆分必须重新设计商品库存预留、订单提交、取消返还、售后库存与故障补偿，并以新的真实并发及事务测试证明正确性；不得把读侧投影当作可售库存的决策源。
