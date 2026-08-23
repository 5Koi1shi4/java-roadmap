# 实验四：订单状态机与 RabbitMQ 可靠消息

本实验用一个独立的 Spring Boot 3.5.5 项目演示：订单从 `PENDING_PAYMENT` 进入 `PAID` 或超时进入 `CANCELLED`，库存释放最多发生一次；订单写入和超时事件写入同一个 MySQL 事务，消息发布、TTL/DLX 和消费幂等则分别验证消息最终可达与业务最多执行一次。

## 环境与启动

- JDK 17（项目使用 `maven.compiler.release=17`）。
- Docker Desktop / Docker Engine；Compose 启动本地 MySQL 8.4 与 RabbitMQ 3.13-management。
- Windows PowerShell；以下命令均在本目录执行。

```powershell
Copy-Item .env.example .env
# 编辑 .env，只填写本机凭据；不要提交 .env
docker compose up -d
docker compose ps
```

Compose 的 `.env` 只会自动提供给 Compose，Spring Boot 不会自动读取它。启动应用或执行需要本地配置的命令前，在当前 PowerShell 进程加载数据库和 RabbitMQ 变量（不会回显密码）：

```powershell
$envFile = Join-Path (Get-Location) '.env'
if (-not (Test-Path -LiteralPath $envFile)) { throw '请先复制 .env.example 为 .env' }
foreach ($line in Get-Content -LiteralPath $envFile) {
  if ($line -match '^\s*(DB_URL|DB_USERNAME|DB_PASSWORD|RABBITMQ_HOST|RABBITMQ_PORT|RABBITMQ_USERNAME|RABBITMQ_PASSWORD)\s*=\s*(.*?)\s*$') {
    $value = $Matches[2].Trim()
    if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
      $value = $value.Substring(1, $value.Length - 2)
    }
    Set-Item -Path ("Env:" + $Matches[1]) -Value $value
  }
}
foreach ($key in 'DB_URL','DB_USERNAME','DB_PASSWORD','RABBITMQ_USERNAME','RABBITMQ_PASSWORD') {
  if ([string]::IsNullOrWhiteSpace((Get-Item ("Env:" + $key) -ErrorAction SilentlyContinue).Value)) { throw ".env 缺少 $key" }
}
Write-Host '已加载数据库与 RabbitMQ 配置（凭据未回显）。'
```

应用默认使用 `DB_URL=jdbc:mysql://localhost:3310/order_mq`、`RABBITMQ_HOST=localhost` 和 `RABBITMQ_PORT=5672`；端口变更时同步 `.env` 与应用变量。

## 消息拓扑

生产默认发布到 1 分钟桶，集成测试通过 `order.timeout.routing-key=order.timeout.10s` 覆盖为 10 秒，因此完整测试不会等待一分钟。

```text
订单事务写入 Outbox
  → OutboxDispatcher（数据库租约、claim token、publisher confirm）
  → timeout.1m（生产；测试为 timeout.10s）
  → TTL 到期后死信到 order.cancel.exchange（DLX）
  → order.cancel.queue（quorum，x-delivery-limit=3）
  → OrderTimeoutConsumer
  → order.manual.exchange / order.manual.queue（不可恢复或重试耗尽）
```

代码还声明 `10s`、`1m`、`5m` 三个固定 TTL 桶，每个桶只承载一个 TTL。TTL 只保证“到期后可被处理”，不是精确计时器；同一个队列混合不同 TTL 时，队头消息未到期会阻塞后面的消息。本实验通过按桶路由避免混排，但仍保留 RabbitMQ TTL/DLX 的延迟语义。

## 关键可靠性边界

### Outbox 调度与接管

`OrderService.createOrder` 在扣库存、插入订单后，同一事务插入 `ORDER_TIMEOUT` Outbox 行；事务提交后由 `OutboxDispatcher` 每轮最多领取 50 行，把 `NEW` 或已过期的 `PUBLISHING` 更新为当前实例的租约并携带随机 `claimToken`。只有 publisher confirm 为正时才标记已发布；NACK、AMQP 异常和其他运行时异常会释放为可重试状态并记录分类。租约过期后下一实例可以接管，旧实例迟到的 ACK/NACK 因 claim token 不匹配不能覆盖新 owner。

### 消费幂等的边界

消费者先在 `consumed_message` 上按 `eventId` 原子领取 `PROCESSING` 记录；租约未过期时不能被第二个消费者接管，租约过期后可重新领取。随后在同一个数据库事务中执行“仅取消待支付订单”“仅在实际取消时释放库存”“标记消费 `COMPLETED`”。已经 `COMPLETED` 的消息直接确认，不再次执行释放库存；业务事务未提交时不能 ACK，失败的 `PROCESSING` 记录由重试或过期接管处理。

事件模型固定为五个字段：`eventId`、`eventType=ORDER_TIMEOUT`、正数 `orderId`、`occurredAt`、`schemaVersion=1`。`OrderTimeoutEvent` 构造器/工厂拒绝空 UUID、错误类型、非正订单号、空时间或不支持的版本；Jackson 反序列化也在消息边界快速失败未知字段和非法值，服务层只负责状态规则，不承担协议清洗。

### 异常分类与有限重试

`FailureClassifier` 将短暂连接失败、事务回滚、死锁、锁等待超时等基础设施异常分类为可恢复；JSON、字段/版本校验失败、非法状态迁移等分类为不可恢复。可恢复消息最多三次处理尝试（Spring Retry `maxAttempts=3`，含首次尝试），重试耗尽进入人工队列；不可恢复消息不消耗重试预算，直接进入人工队列，并带 `failure-category`、`failure-message`、`x-retry-count`。人工消息发布仍等待 publisher confirm；数据库 `manual_failure` 表是可补偿的持久副本，定时 dispatcher 每轮只发送有限批量并使用租约与 token 防止重复确认。

### 已知停放限制

若数据库写入人工失败副本和人工队列 publish 同时失败，只能依赖 RabbitMQ quorum 队列的 broker-only DLX 副本。该极端路径保留原始 payload 以及 RabbitMQ 的 `x-death` / delivery metadata，但不保证保留自定义 `failure-category` 和 `failure-message`；因此文档不把它描述成完全无损，生产仍应告警并人工核对原始消息。

## 验证

单元测试不启动 Spring；真实 MySQL、RabbitMQ、Flyway、TTL/DLX、publisher confirm 和消费协作由 Testcontainers 集成测试覆盖。使用 JDK 17 执行完整验收：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
./mvnw.cmd verify
```

已验证证据（提交 `d7c52ea`）：46 个 Surefire 单元测试、21 个 Failsafe/Testcontainers 集成测试，0 failures、0 errors、0 skipped，Maven `BUILD SUCCESS`。集成测试使用一对共享容器，但每个测试在 `@BeforeEach` 清空数据库并同步 purge 五个队列，保证事件、订单和队列隔离；单元测试则用固定时钟和 mock 快速覆盖边界。

## 代码导航

- `domain/OrderStateMachine`：纯领域状态迁移与幂等判断。
- `application/OrderService`：事务编排和业务规则；不解析消息协议。
- `infrastructure/persistence/JdbcOrderRepository`：条件更新、Outbox、消费记录和人工失败持久化。
- `infrastructure/mq/OutboxDispatcher`：租约领取、发布确认和 fencing。
- `infrastructure/mq/OrderTimeoutConsumer`：消息边界校验、消费领取、事务完成和异常分类。
- `infrastructure/mq/RabbitTopologyConfiguration`：TTL 桶、DLX、quorum delivery limit、Retry 与人工恢复。

遇到容器、confirm、死信路由或测试隔离问题，先看 [TROUBLESHOOTING.md](TROUBLESHOOTING.md)。
