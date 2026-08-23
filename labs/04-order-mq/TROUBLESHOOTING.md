# 实验四排障复盘

## Docker 或 Testcontainers 启动失败

mvnw.cmd verify 的 *IT 由 Failsafe 执行，需要 Docker Engine；先确认宿主机而不是只确认 Docker CLI：

    docker version
    docker info
    docker compose ps

named pipe 权限、Docker Desktop 未启动或镜像拉取失败属于环境问题，不应误判为 Java 业务失败。启动 Compose 后，确认 MySQL healthcheck 为 healthy，RabbitMQ 的 5672 和 15672 端口未被占用；本实验的 .env 只保存在本机。若测试需要重新执行，在 Docker 可访问的宿主权限下运行：

    $env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    ./mvnw.cmd verify

单独执行 Surefire 只能验证快速单元测试，不能替代 RabbitMQ、TTL、DLX、publisher confirm 和真实事务的集成验收。

## RabbitMQ vhost 与权限

Testcontainers 使用 rabbitmq:3.13-management，用户为 order_mq、vhost 为 /，并显式授予该 vhost 的 configure/write/read 权限。若日志出现 access refused、queue.declare 或 exchange.bind 权限错误，检查测试动态属性是否把 host、port、username、password 指向同一容器，以及容器是否真的使用 / vhost：

    docker ps
    docker exec -it <rabbit-container> rabbitmqctl list_users
    docker exec -it <rabbit-container> rabbitmqctl list_permissions -p /

不要只改应用密码而遗漏 withPermission("/", "order_mq", ".*", ".*", ".*")；Compose 本地凭据也必须和 .env 的 RABBITMQ_USERNAME、RABBITMQ_PASSWORD 一致。

## publisher confirm 没有成功

OrderEventPublisher 使用 correlated publisher confirm，并在 RabbitTemplate.invoke 中等待最多 10 秒。confirm 为 false 或抛出 AMQP 异常时，OutboxDispatcher 不标记已发布，而是按 AMQP/PUBLISH 分类释放租约，后续调度再尝试。排查顺序：

1. 查看 RabbitMQ 连接、vhost、用户名和网络端口是否一致。
2. 用管理端确认 order.timeout.exchange、对应 TTL 队列及 binding 已声明。
3. 查看消息是否进入正确 routing key：生产默认 order.timeout.1m，测试通过 order.timeout.routing-key=order.timeout.10s 覆盖。
4. 检查应用日志中的 publisher confirm nack、连接关闭和 confirm 等待超时；不要把调用 convertAndSend 返回当成 broker 已持久接受。

Outbox 的租约和 claimToken 还会阻止旧 dispatcher 的迟到 ACK/NACK 覆盖新 owner；因此看到 PUBLISHING 行时应同时检查 lease_until 和 token，而不是直接手工改状态。

## TTL 与死信路由

拓扑是：order.timeout.exchange → 固定 TTL 桶（10s/1m/5m）→ order.cancel.exchange → order.cancel.queue → OrderTimeoutConsumer。TTL 到期不是定时回调；RabbitMQ 只会在消息到期后按队列规则处理，且同一队列混合 TTL 会有队头阻塞。本实验按延迟桶分开路由，生产使用 1 分钟，集成测试使用 10 秒。

检查队列属性和死信消息：

    docker exec -it <rabbit-container> rabbitmqctl list_queues -p / name messages consumers arguments
    docker exec -it <rabbit-container> rabbitmqctl list_bindings -p / source_name destination_name routing_key

取消队列是 quorum queue，x-delivery-limit=3，超过投递限制后死信到 order.manual.exchange / order.manual.queue。不可恢复格式错误不应反复重试；可恢复数据库或连接异常由 Spring Retry 最多三次尝试后进入人工路径。人工恢复会保留原 payload、失败类别和原因；但数据库持久副本与人工 publish 同时失败时，broker-only DLX 副本可能缺少自定义 failure-category/failure-message，只保证原 payload 与 x-death/delivery metadata，需人工告警核对。

## 测试超时、消息残留与隔离

完整命令：

    ./mvnw.cmd verify

只跑某个 Failsafe 类（仍需 Docker）：

    ./mvnw.cmd -Dit.test=ReliableMessagingFlowIT verify

集成测试使用静态共享 MySQL/RabbitMQ 容器以缩短总耗时；共享不等于共享业务数据。每个测试先清除 consumed_message、manual_failure、outbox_event、orders，恢复库存，并对五个队列执行同步 rabbitAdmin.purgeQueue(queue, false)。曾经使用异步 purge 时，下一条消息可能在旧消息仍存在时发布，导致断言读到前一条事件；必须等待同步 purge 完成。

若 Awaitility 超时，先查消息到底停在哪一段：数据库 Outbox 状态、TTL 队列、cancel 队列、manual 队列和消费者日志。测试属性将生产 1m routing key 覆盖为 10s，不要把 60 秒生产延迟误认为消费失败。

## 本轮具体排障记录

### ObjectMapper 与事件边界

最初用未注册 Java 时间模块的裸 ObjectMapper 做单元夹具，Instant occurredAt 无法稳定读写。生产由 Spring Boot 提供配置好的 mapper，OrderTimeoutConsumer 的简化构造器也显式注册 JavaTimeModule；测试统一使用相同模块。随后将边界约束收紧为五字段稳定模型：构造器拒绝空 UUID、错误 event type、非正 order id、空时间和非 1 schema version；Jackson 反序列化未知字段、缺失字段或非法值立即失败，避免把协议错误带进服务层。

### Spring Retry 的 attempt / header off-by-one

Rabbit header 中已有的 x-retry-count 不能直接当作本轮 Spring Retry 次数。恢复器改为读取 RetrySynchronizationManager 的实际上下文计数；若消息已带 x-retry-exhausted，则至少保留入站计数。这样非可重试错误记录为 1 次，可重试耗尽记录为 3 次，不会把历史 header 重复累加成 4 次。OrderTimeoutConsumer 也拒绝负数或大于等于 3 的重试计数，防止继续消耗业务预算。

### JDBC TIMESTAMP 夹具与时区

数据库迁移使用 TIMESTAMP(6)，Java 侧使用 Instant / Timestamp.from(instant)。早期夹具混用本地时区字符串与 UTC Instant，在不同宿主时区会出现租约过期、排序或相等断言漂移。修复后测试固定 Instant（带 Z）和 UTC Clock，SQL 读取/写入统一转换，避免依赖宿主机默认时区。

### Rabbit vhost permission

RabbitMQ 容器能启动并不表示应用用户能声明 quorum 队列。测试容器补充了显式 vhost / 和全量 configure/write/read permission，并将动态端口和凭据注册到 Spring；这解决了“连接成功但 queue.declare 被拒绝”的假阴性。

### 同步 purge

静态共享容器让测试更快，但队列会保留前一个测试的消息。rabbitAdmin.purgeQueue(queue, false) 的 false 表示等待 broker 完成 purge；必须在下一个测试发布前完成，不能用 fire-and-forget 清理。数据库和队列清理都放在每个测试的 setup 中，测试使用独立订单与 eventId。

## 数据库幂等核对

消费幂等不是只靠消息 broker。consumed_message.event_id 唯一键 + PROCESSING/COMPLETED 租约状态，与取消订单、释放库存处于同一个事务；重复 eventId 会命中已完成记录，支付订单的条件更新也不会错误取消。若发现库存重复释放，先核对 cancelIfPending 的受影响行数、consumed_message 状态和是否绕过了 OrderTimeoutConsumer 直接调用仓储。
