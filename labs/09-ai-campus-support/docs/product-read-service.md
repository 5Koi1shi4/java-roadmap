# 8.2 商品读服务与恢复边界

8.2 在已验收的 8.1 身份拆分上增加第五个应用 `product-read-service`。商品、库存、订单、支付和售后的交易事实仍由 `legacy-market-service` 的 `market_db` 管理；新服务只拥有 `product_read_db` 的投影、Inbox、索引待办、重建门禁与清理任务。它不查询交易库，也不接收商品或库存写命令。实验仍只支持全新环境。

客户端仅经 Gateway 使用 `GET /api/search` 和 `GET /api/listings/search`。这两条精确 GET 路由通过 Eureka 指向商品读服务，其他 `/api/**` 仍去兼容交易单体。商品读服务直连时也验证身份服务的 RS256/JWKS Token，不接受身份 Header；无效 Token 返回 401，搜索依赖故障返回中文 UTF-8 503。Gateway 不自动重试写请求。

## 从交易事实到搜索结果

兼容单体的旧搜索重建门禁只暂停旧索引领取与切换，不阻止商品发布、库存变更或源快照 Outbox 入库。源启动回放调度器只在完成屏障得到 broker confirm 且 replay claim 的 `IDLE` 已提交时停止；broker 检查竞态、锁竞争和其他 owner 持有时下一轮继续尝试。

商品发布、下架、库存归零等交易在原事务内写入 `market_db.search_outbox`。`schemaVersion=2` 的事件包含该事务看到的完整、不可变商品快照，`eventId` 取 Outbox 行 ID，`aggregateVersion` 保证同一商品单调前进。事件中没有私有对象键、预签名链接、JWT、密钥或校方认证字段；订单原有快照仍保留在交易库，不由搜索结果反推。

独立 publisher 领取源 Outbox 并等待 RabbitMQ correlated publisher confirm。NACK、不可路由或 broker 故障不能标记为 `PUBLISHED`；旧 owner 的迟到确认受租约与 claim token 拦截。源行一直保留。`ProductReplayService.replayOnce(limit)` 是本机运维服务方法，不暴露 HTTP，单次固定 `MAX(sequence_no)` 高水位，以 1～1000 条有界重发已保留事件；Rabbit confirm 后前进游标。重发可能与正常发布交错，读侧按事件 ID 和商品版本幂等收敛。源服务启动时也执行一次有界 bootstrap；高水位内快照确认入队后，同一队列收到 `PRODUCT_REPLAY_COMPLETE` 屏障，空源库仍发送高水位 0。屏障的 replay ID 留在源 claim 中，确认成功但完成 SQL 中断时可幂等重发。

Rabbit 的 `campus.product.snapshot` 持久交换机把消息送到 `campus.product.read` 持久队列。单消费者预取 1 条；每条快照先在 `product_read_db` 的一个事务中写 Inbox、条件更新投影并创建 index Outbox，然后手动 ACK。重复 eventId 不重写；较旧商品版本不能覆盖较新投影。完成屏障在同一消费顺序中记录读库 `product_projection_readiness` 的 replay/source/index 高水位；屏障前索引待办清空后才 READY。协议错误或三次运行失败进入独立持久 `campus.product.manual.failure` 队列，队列非空时投影 readiness 为 DOWN，需人工检查并重新回放；不能放宽解码或无限热重投。

索引调度器只领取本库 index Outbox，使用数据库时间、owner、claim token、租约和重建 generation fencing。非法投影或永久 ES 4xx 留在可诊断的 `FAILED`，连接失败、503、408 和 429 继续重试；生产清理调度不会在短暂 ES 停机后永久放弃旧索引。`campus-product-read` 与 `campus-product-write` 别名属于新服务，兼容交易单体继续使用自己的 `campus-listing-*` 别名。正常在售且数量大于零按商品版本写 ES；售罄或下架按外部版本 tombstone，旧消息不能复活结果。SmartCN、分类、价格和 PIT/search_after 查询由真实 Elasticsearch 测试验证。

## 在线重建和故障排查

快照在同一个可重复读事务中按 listing ID 每批最多 500 行读取，并在每批后以独立提交的事务续租；这样长快照读事务仍保持一致，租约也能被其他连接观察到。

在线重建只读取 `product_projection`，在一致性快照中固定 index Outbox 的 `sequence_no` 高水位，先持久记录确定的目标名称再建立新物理索引，并补放快照后到切换点的变化。单行 `product_rebuild_gate` 暂停普通索引领取；generation、owner、token 与数据库租约防止过期 owner 切换别名。读写别名必须在一次 ES 更新中一起切换，切换意图和旧索引清理任务留在读库；清理器先检查 live 别名，绝不删除正在提供请求的索引。过期租约不能被下一次重建直接覆盖：恢复器核对实际读写别名；双别名均在目标上才完成 CUTOVER，目标不在线则先持久入队清理再开门禁，部分切换保持关闭等待处理。旧代清理任务在后来门禁 OPEN 时仍可执行，但删除前复查 live 别名。失败后先查看 gate 的 mode/intent、别名实际目标、index Outbox 积压和 cleanup task，不能手工强行把门禁改回 OPEN。

先查看商品服务 `/actuator/health/readiness` 的 `jwks`、`eureka`、`db`、`rabbit`、`productSearch` 和 `projection` 组件。`projection` 为 WAITING/CATCHING_UP/BLOCKED、屏障前 index Outbox 未清空或商品人工失败队列非空时，搜索请求返回安全 503；投影库重建后需从保留源事件重新 replay，不能以 ES 重建代替事实恢复。冷启动没有身份公钥、Eureka 或 ES 时不可宣称就绪；身份和注册中心探针只在最近成功探测的有限窗口内缓冲短停机。Rabbit 停机时源交易与 Outbox 仍可提交，恢复后 publisher/replay 和消费者应使积压收敛；产品进程停机时交易命令仍由兼容单体承担，搜索返回安全 503。恢复成功要同时核对搜索集合、订单快照和积压状态，不能以单个 HTTP 200 代替。

本地 Compose 在 8.1 的四应用基础上增加 product-read-service，使用第三组最小权限账号 `product_app`/`product_migrator`；迁移账号仅有本库 DDL/DML，运行账号仅有本库必要 DML。`market_app`、`identity_app` 与 `product_app` 的跨库 SELECT 均应被 MySQL 拒绝。 `.env`、JWT 密钥文件和凭据只留本机仓库外，不提交或打印展开后的 Compose 配置。

8.2 的验收以 fresh `clean test`、`clean verify` 的 Surefire/Failsafe XML、五应用真实 HTTP/Rabbit/ES/MySQL 故障旅程及隔离 Compose smoke 为准。定点测试只说明对应行为，不代表整模块已验收。
