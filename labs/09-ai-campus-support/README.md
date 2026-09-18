# 实验九：AI 校园交易客服

本目录从已验收的实验八提交建立隔离基线，平台已整体升级到 JDK 17、Spring Boot 4.1.1、Spring Cloud 2025.1.3 与 Spring AI 2.0.1。实验九在此基础上实现规则知识问答和本人订单、争议、质保状态的只读查询，并增加独立 AI 服务和客服前端；这里保留的实验八说明用于核对升级前行为，不能把自动退款、仲裁或其他交易写操作交给模型。平台升级细节见 [Spring AI 2.x 平台迁移说明](docs/platform-migration.md)。

## 迁入基线

身份服务独立签发 RS256 Token，Gateway、兼容交易单体和商品读服务分别通过 JWKS 验签，Eureka 提供实例发现。`identity_db`、`market_db` 与 `product_read_db` 使用独立账号，交易不变量继续由兼容单体维护。

当前 8.1 身份拆分状态为“已验收”。2026-09-15 在 JDK 17、Docker Desktop 29.7.2 下，完整 `clean test` 与 `clean verify` 均 BUILD SUCCESS；fresh XML 汇总 196 项 Surefire、293 项 Failsafe/Testcontainers，共 489 项，全部 0 failures、0 errors、0 skipped。真实四应用旅程、身份/交易/Eureka 停机恢复、冷启动与 JWKS 刷新均在完整验收中运行。用 Dockerfile 指定的官方 `eclipse-temurin:17-jre` 重新构建并启动 Compose 后，四应用的 health、liveness、readiness 全部 UP，Eureka 3 项注册完成，Gateway 的 JWKS 返回 HTTP 200 与 1 把 RSA 公钥。实验七的已验收结果只作为迁入基线；原计划的 `7.1` 聊天、`7.2` 竞价、`7.3` 跑腿/代取及真实支付适配器继续暂停。

8.2 独立商品读服务已验收：交易事实与订单快照仍在兼容单体，商品完整快照经确认式 Rabbit 发布，读侧拥有第三库和独立 SmartCN 索引。2026-09-16 在 JDK 17、Docker Desktop 29.7.2 下 fresh `clean test` 与 `clean verify` 均 BUILD SUCCESS；Surefire 228 项、Failsafe/Testcontainers 357 项，共 585 项，全部 0 failures、0 errors、0 skipped。真实五应用商品旅程、故障恢复、在线重建和官方 JRE 镜像的隔离 Compose smoke 均完成；Eureka 4/4、五应用十五个健康/探针结果 UP，Gateway JWKS HTTP 200。8.1 的 489 项仍是历史基线。详见 [商品读服务说明](docs/product-read-service.md)与 [8.2 验收记录](docs/acceptance-20260916.md)。

实验九 AI 客服已验收：新增只读 `ai-support-service` 和 React 客服站点，只用已审阅公开规则回答，并在服务端完成本人订单、争议、质保的授权查询；模型不接收 Token、任意私人问题或交易写工具。2026-09-18 使用 Maven Wrapper 的 JDK 17.0.12、Node 22.17.1 与 Docker Desktop Engine 29.7.2 完成 fresh `clean test`、`clean verify`、前端单测/构建和桌面/移动浏览器旅程。最终 157 份 XML 为 Surefire 255 项、Failsafe/Testcontainers 384 项，共 639 项；Playwright 10 项，全部无失败、错误或跳过。详见 [实验九验收记录](docs/acceptance-20260918.md)。

## 边界与验收入口

本实验只支持全新环境，不支持生产不停机迁移。请先阅读 [架构](docs/architecture.md)、[迁移边界](docs/migration-boundary.md)、[排障](TROUBLESHOOTING.md)、[学习日志](notes/learning-log.md) 与 [面试追问](interview/question-bank.md)。身份服务实际 JWKS 地址为 `/api/auth/.well-known/jwks.json`；客户端只访问 Gateway 的 `/api/auth/**` 与 `/api/**`，不使用服务实例地址。

在本目录使用 JDK 17 串行执行：

```powershell
docker info
.\mvnw.cmd clean test
.\mvnw.cmd clean verify
git diff --check

Set-Location support-web
npm ci
npm test
npm run build
npm run test:e2e
```

必须等 `docker info` 成功再执行完整验收；Docker 不可用或外部测试 skipped 均不算通过。当前验收基线是 2026-09-18 fresh `clean verify` 的 639 项；2026-09-16 的 585 项与旧 8.1 结果只作为迁入历史，不能替代实验九证据。

## 你将运行到的能力

- 校园邮箱验证码、注册、登录和 15 分钟 JWT；CAS 只保留适配端口，不冒充学校正式身份认证。
- 商品草稿、私有媒体、发布/下架、批量库存和库存流水；买家不能购买自己的商品。
- 单发布项、多数量订单；下单、取消等命令使用 `Idempotency-Key`，同键异参返回 409。
- 模拟支付、签名回调、一次性 nonce、`UNKNOWN` 主动对账、退款额度预占和单次支付尝试。
- 当面交付、买家确认、三天验收、七天试用、普通争议、可信退回证明和隔离库存。
- 30/90/180/365 天卖家质保、厂家质保快照、结算后卖家义务、未来结算抵扣和发布/提现限制。
- MySQL 事务 Outbox、RabbitMQ publisher confirm、Inbox 幂等、人工失败副本、租约接管和 claim-token fencing。
- SmartCN Elasticsearch 搜索、外部版本、tombstone、在线重建和失败收敛。
- AI 客服只解释版本化公开规则；本人订单、争议、质保状态先由交易服务授权读取，再以固定安全模板回答，不允许模型执行交易写操作。
- MinIO 私有对象、真实类型与实际读取上限、案件 ACL、持久清理任务和故障恢复。
- 审计字段过滤、低基数 Micrometer 指标、真实 HTTP 旅程与三轮故障演练。

## 模块和事实边界

Reactor 共八个项目：一个聚合根和七个 Maven 子模块。`discovery-server`、`identity-service`、`legacy-market-service`、`product-read-service`、`api-gateway`、`ai-support-service` 是六个独立应用；`platform-test-support` 只提供 test scope 夹具。身份代码和身份表已迁入身份服务；商品读服务拥有独立投影库与索引，兼容单体继续拥有交易事实；AI 服务只读公开规则和经授权的最小状态，不签发 Token、不直连交易库。

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
| `catalog` | 商品、库存、媒体的交易事实与源快照 Outbox | `ListingService`、`JdbcInventoryRepository`、`ProductSnapshotPublisherDispatcher` |
| `product-read` | 独立商品投影、搜索、replay 屏障与索引恢复 | `ProductEventConsumer`、`SearchController`、`ProductSearchRebuildRecoveryService` |
| `order` | 幂等下单、订单状态机、截止任务 | `CreateOrderService`、`OrderLifecycleService`、`DeadlineScheduler` |
| `payment` | 模拟网关、回调、退款、对账与结算 | `PaymentService`、`RefundService`、`PaymentReconciliationScheduler` |
| `dispute` | 交付、争议、证据、退回与硬期限 | `HandoffService`、`DisputeService`、`ReturnResolutionService` |
| `warranty` | 延长质保、裁定、卖家义务与限制 | `WarrantyService`、`SellerObligationService` |
| `messaging` | Outbox/Inbox、RabbitMQ、人工失败 | `OutboxDispatcher`、`ReliableEventConsumer` |
| `storage` | 私有对象、上传绑定、清理任务 | `ObjectUploadCoordinator`、`StorageCleanupScheduler` |
| `observability` | 安全审计、指标与提交后计数 | `AuditRecorder`、`CampusMetrics` |
| `ai-support` | 公开规则检索、本人状态编排、受控模型回答 | `AnswerService`、`PolicyRetriever`、`HttpTradeStatusReader` |

MySQL 是订单、库存、金额、截止时间和在售集合的事实源。Redis 只用于验证码和限流，不能参与交易正确性证明；Elasticsearch 是可重建读模型；MinIO 只保存私有对象，逻辑授权仍由 MySQL 记录决定。模块之间通过公开服务和逻辑 ID 协作，不把其他模块的内部表当作公共 API。

## 环境和启动

前置条件：

- JDK 17；使用仓库自带 Maven Wrapper，不依赖全局 Maven。
- Docker Desktop，Docker Engine 已启动。
- 完整验收前至少保留 1 GiB 可用内存，并关闭无关容器和并行构建。

复制 `.env.example` 为 `.env`，仅填写本地值，不提交 `.env`。其中数据库口令、RabbitMQ/MinIO 凭据、验证码签名和模拟支付签名必须替换为本机值；`CAMPUS_MARKET_IDENTITY_VERIFICATION_SECRET` 至少为 32 字节（UTF-8）。JWT 密钥文件放在仓库外，并在 `.env` 中填写两个绝对路径。`CAMPUS_MARKET_JWT_ISSUER` 使用本地约定值 `http://gateway.test`，`CAMPUS_MARKET_JWT_AUDIENCE` 必须填写固定值 `campus-market-api`。示例文件只有占位符，不能直接启动。

本地 Compose 显式启用 `local,demo-mail`，由 SMTP 适配器把验证码发送到仅绑定本机的 Mailpit；手动演示可在 `http://localhost:8026` 查看邮件，自动浏览器验收只通过 Mailpit 官方 API 读取测试邮件。其他单独启用 `local` 的运行方式仍使用受控内存适配器。非 local/test 的身份服务部署必须提供真实 SMTP 配置。

可用 OpenSSL 在仓库外生成一对仅供本地实验的 RSA 密钥。私钥必须是 PKCS#8，公钥必须是 X.509：

```powershell
$keyDir = Join-Path $env:TEMP 'campus-market-cloud-keys'
New-Item -ItemType Directory -Force $keyDir | Out-Null
openssl genrsa -out (Join-Path $keyDir 'jwt-private-rsa.pem') 2048
openssl pkcs8 -topk8 -nocrypt -in (Join-Path $keyDir 'jwt-private-rsa.pem') -out (Join-Path $keyDir 'jwt-private-key.pem')
openssl rsa -in (Join-Path $keyDir 'jwt-private-rsa.pem') -pubout -out (Join-Path $keyDir 'jwt-public-key.pem')
```

在本目录构建并启动六个 JVM 应用（已包含 AI 客服服务）及静态前端。第一次启动前必须先打包，Compose 只使用各模块的 `target` JAR；不再使用旧的单体 `spring-boot:run` 命令。下面是本地 Compose 的基础启动 smoke；完整浏览器验收使用 `support-web` 的 `npm run test:e2e`：

```powershell
docker info
Copy-Item .env.example .env
# 编辑 .env，填入本机口令和密钥绝对路径
.\mvnw.cmd -DskipTests package
docker compose --env-file .env config --quiet
docker compose --env-file .env build
docker compose --env-file .env up -d
docker compose --env-file .env ps
```

Compose 的 `depends_on: service_started` 只表达启动顺序，不代表应用已就绪。启动后可用以下有界 PowerShell 在同一个 2 分钟 deadline 内轮询五个应用的 `/actuator/health`、liveness/readiness 和 Eureka 四项注册；每次请求和休眠都会先计算剩余时间，超时即失败：

```powershell
$services = [ordered]@{
  discovery = 'http://localhost:8761'
  identity  = 'http://localhost:18081'
  legacy    = 'http://localhost:18082'
  product   = 'http://localhost:18083'
  gateway   = 'http://localhost:18080'
}
$registryNames = @('IDENTITY-SERVICE', 'LEGACY-MARKET-SERVICE', 'PRODUCT-READ-SERVICE', 'API-GATEWAY')
$deadline = (Get-Date).AddMinutes(2)

function Get-RemainingSeconds {
  [int][Math]::Floor(($deadline - (Get-Date)).TotalSeconds)
}

function Invoke-BoundedWebRequest {
  param(
    [Parameter(Mandatory)][string]$Uri,
    [hashtable]$Headers
  )
  $remaining = Get-RemainingSeconds
  if ($remaining -lt 1) { return $null }
  $timeout = [int][Math]::Min(5, $remaining)
  try {
    if ($null -ne $Headers) {
      return Invoke-WebRequest -Uri $Uri -Headers $Headers -TimeoutSec $timeout -UseBasicParsing
    }
    return Invoke-WebRequest -Uri $Uri -TimeoutSec $timeout -UseBasicParsing
  } catch {
    return $null
  }
}

function Get-WebResponseText {
  param([Parameter(Mandatory)]$Response)
  if ($Response.Content -is [byte[]]) {
    return [System.Text.Encoding]::UTF8.GetString($Response.Content)
  }
  return [string]$Response.Content
}

$healthy = @{}
$readyProbes = @{}
$registered = @()
do {
  $healthy = @{}
  $readyProbes = @{}
  foreach ($entry in $services.GetEnumerator()) {
    if ((Get-RemainingSeconds) -lt 1) { break }
    $response = Invoke-BoundedWebRequest -Uri "$($entry.Value)/actuator/health"
    if ($null -ne $response -and $response.StatusCode -eq 200) {
      $healthy[$entry.Key] = $true
    }
    foreach ($probe in @('liveness', 'readiness')) {
      if ((Get-RemainingSeconds) -lt 1) { break }
      $probeResponse = Invoke-BoundedWebRequest -Uri "$($entry.Value)/actuator/health/$probe"
      if ($null -ne $probeResponse -and $probeResponse.StatusCode -eq 200 -and
          (Get-WebResponseText -Response $probeResponse) -match '"status"\s*:\s*"UP"') {
        $readyProbes["$($entry.Key)/$probe"] = $true
      }
    }
  }

  $registryResponse = Invoke-BoundedWebRequest `
    -Uri 'http://localhost:8761/eureka/apps' `
    -Headers @{ Accept = 'application/json' }
  $registered = @()
  if ($null -ne $registryResponse -and $registryResponse.StatusCode -eq 200) {
    $registryBody = Get-WebResponseText -Response $registryResponse
    foreach ($name in $registryNames) {
      if ($registryBody -match [regex]::Escape($name)) {
        $registered += $name
      }
    }
  }

  if ($healthy.Count -eq $services.Count -and $readyProbes.Count -eq ($services.Count * 2) -and
      $registered.Count -eq $registryNames.Count) {
    break
  }

  $remainingMilliseconds = ($deadline - (Get-Date)).TotalMilliseconds
  if ($remainingMilliseconds -lt 1) { break }
  Start-Sleep -Milliseconds ([int][Math]::Min(2000, [Math]::Floor($remainingMilliseconds)))
} while ((Get-Date) -lt $deadline)

$missingHealth = @($services.Keys | Where-Object { -not $healthy.ContainsKey($_) })
$missingProbes = @($services.Keys | ForEach-Object { $service = $_; @('liveness', 'readiness') |
    Where-Object { -not $readyProbes.ContainsKey("$service/$_") } |
    ForEach-Object { "$service/$_" } })
$missingRegistry = @($registryNames | Where-Object { $_ -notin $registered })
if ($missingHealth.Count -gt 0 -or $missingProbes.Count -gt 0 -or $missingRegistry.Count -gt 0) {
  throw "Timed out before health/probes/Eureka became ready. Health: $($missingHealth -join ', '); Probes: $($missingProbes -join ', '); Eureka: $($missingRegistry -join ', ')"
}
$services.Keys | ForEach-Object { "$_ : health, liveness, readiness UP" }
```

Compose 的依赖顺序是 discovery（8761）先启动，identity（18081）、legacy（18082）与 product（18083）在各自基础设施健康后启动，AI（18084）完成策略索引后注册，Gateway（18080）和静态站点（18000）随后可用。宿主机端口如下：

| 服务 | 容器端口 | 宿主机端口 | 用途 |
|---|---:|---:|---|
| discovery-server | 8761 | 8761 | Eureka 注册中心 |
| api-gateway | 8080 | 18080 | 客户端唯一入口 |
| identity-service | 8080 | 18081 | 身份服务诊断入口 |
| legacy-market-service | 8080 | 18082 | 兼容交易服务诊断入口 |
| product-read-service | 8080 | 18083 | 商品读服务诊断入口 |
| ai-support-service | 8080 | 18084 | AI 客服诊断入口 |
| support-web | 8080 | 18000 | 浏览器同源入口与 `/api/**` 代理 |
| Mailpit | 1025/8025 | 1026/8026 | 演示 SMTP 与本地邮件界面 |
| MySQL | 3306 | 3313 | `identity_db`、`market_db`、`product_read_db` |
| Redis | 6379 | 6383 | 验证码和限流 |
| RabbitMQ | 5672/15672 | 5673/15673 | 事件与管理界面 |
| Elasticsearch | 9200 | 9203 | SmartCN 搜索读模型 |
| MinIO | 9000/9001 | 9010/9011 | 私有对象和控制台 |

应用的关键环境变量已在 Compose 中显式配置：identity 使用 `SPRING_PROFILES_ACTIVE=local,demo-mail` 并连接 Mailpit；legacy、product、Gateway 保留实验八的数据库、消息、搜索、JWT 与 Eureka 边界；AI 使用 Redis、Elasticsearch、JWKS、模型地址和只读规则目录。静态站点只代理 Gateway，不接触内部服务密钥。所有口令和宿主机密钥路径只从 `.env` 读取。

模型切换只改服务端环境变量。完整 `npm run test:e2e` 会自动启动 `support-web/e2e/model-stub.mjs`，Compose 默认通过 `http://host.docker.internal:18089/v1` 使用该 OpenAI 兼容替身；手动演示替身时须先在另一终端运行 `node support-web/e2e/model-stub.mjs`。连接获准的真实 OpenAI 兼容 API 时，在未提交的 `.env` 中显式设置 `CAMPUS_MARKET_AI_BASE_URL`、`CAMPUS_MARKET_AI_API_KEY` 和 `CAMPUS_MARKET_AI_MODEL` 后重建 AI 服务。无论使用哪种适配器，供应商请求都只包含公开问题或私人问题的固定安全模板，以及已审阅公开规则片段；Token 和完整交易对象不外发。

应用间只使用 Compose 服务名：Eureka 为 `http://discovery-server:8761/eureka/`，identity 的固定 JWKS 为 `http://identity-service:8080/api/auth/.well-known/jwks.json`，legacy、product 和 Gateway 均通过该地址验签。客户端只访问 Gateway；例如先检查 Gateway 暴露的 JWKS：

```powershell
Invoke-WebRequest -Uri http://localhost:18080/api/auth/.well-known/jwks.json
```

停止本地实验：

```powershell
docker compose --env-file .env down
```

MySQL 初始化脚本只在 `mysql-data` 空卷第一次创建数据库和账号；MinIO 对象保存在独立的 `minio-data` 命名卷。普通 `down` 保留这两个卷，需要丢弃本地实验数据并重新初始化时才使用 `down -v`；该命令会删除这个 Compose 项目的数据库和 MinIO 数据。Toxiproxy 不属于本地运行 Compose，故障测试由 Testcontainers 按测试需要独立创建。测试使用隔离的 Testcontainers，不能用本机历史服务或 skipped 结果代替真实外部协作验证。

## 主要 HTTP 接口

8.2 的两个搜索 GET 经 `lb://product-read-service`；商品、库存和订单写命令仍经 `lb://legacy-market-service`。读服务根据事件投影和自己的 ES 别名返回搜索结果，可售库存仍以交易库为准。

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

{"email":"buyer@stu.example.edu.cn","purpose":"REGISTER"}
```

Compose 的 `demo-mail` Profile 把验证码发送到 Mailpit，但仍不增加生产验证码读取端点，也不把验证码写入日志。手动注册从 Mailpit 本地界面读取验证码；自动 Playwright 夹具通过 Mailpit 官方 API 读取且不写入报告。注册后使用 `/api/auth/login` 获取 JWT；业务接口携带 `Authorization: Bearer <token>`。

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
.\mvnw.cmd clean test
.\mvnw.cmd clean verify
git diff --check

Set-Location support-web
npm ci
npm test
npm run build
npm run test:e2e
```

需要定点复跑真实旅程或故障演练时：

```powershell
.\mvnw.cmd -Dit.test=CampusMarketJourneyIT verify
.\mvnw.cmd -Dit.test=RecoveryDrillIT verify
```

低内存主机必须串行执行，等待上一条命令完全结束且容器回收后再运行下一条。共享 Testcontainers 测试默认不自动启动 Rabbit listener、搜索调度器和各业务截止任务；验证调度的测试直接调用对应 `runOnce`，需要真实 Rabbit 投递的测试使用自己的监听器容器，避免已结束上下文污染后续 Outbox、队列和租约。

截至 2026-09-11 的 137/251 项、8.1 的 196/293 项与 8.2 的 228/357 项都只是历史基线。实验九 2026-09-18 的同一次 fresh `clean verify` 生成 157 份 XML：Surefire 255 项、Failsafe/Testcontainers 384 项，共 639 项，全部 0 failures、0 errors、0 skipped。分模块为 support 8、discovery 4、identity 25+17、legacy 138+286、product 31+47、Gateway 25+15、AI 24+19。前端 Vitest 13 项、生产构建和 Playwright 桌面/移动 10 项同样通过；完整验收包含继承交易回归、真实外部依赖恢复、规则索引、本人状态 ACL、模型故障恢复和浏览器会话清理，不接受 Docker 不可用或外部测试跳过。

实验九的模块计数、隔离 Compose 浏览器结果与安全边界见 [实验九验收记录](docs/acceptance-20260918.md)；8.2 历史基线见 [8.2 验收记录](docs/acceptance-20260916.md)。

## 已知边界和扩展决策

- 校园邮箱只证明邮箱控制权；正式 CAS 尚未获得校方授权，当前仅有 `ExternalIdentityProvider` 端口。
- 支付网关是签名、幂等、回调与对账契约下的模拟适配器；真实支付、商户资质和真实退款未接入。
- 首版是校园当面交付；物流单号不构成可信退回证明，真实物流验签适配器未接入。
- MP4 不做转码、缩略图、内容审核或真实性鉴定；争议保持单轮管理员裁决，不支持 AI 自动资金仲裁。

原始设计曾规划以下扩展路线：

1. `7.1` 聊天：WebSocket、会话权限、离线消息和未读计数。
2. `7.2` 竞价：并发出价、截止成交、反悔规则和竞价审计。
3. `7.3` 跑腿/代取：独立 `ServiceOrder`，不复用商品订单状态机。
4. 真实支付适配器：取得资质、沙箱/生产账号和批准后，再实现现有 `PaymentGateway` 契约。

上述扩展涉及通信内容与个人信息处理、交易平台责任、竞价与跑腿服务规则、支付资质及资金安全等法律与合规问题。相关设计只作为历史评估材料保留，当前不进入实现、测试或上线流程；如未来重新启动，必须先完成独立法律合规评估和明确授权。实验七主体不依赖这些扩展，其迁入基线已验收；实验八的 8.1 身份拆分已验收。更多故障症状和恢复动作见 [TROUBLESHOOTING.md](TROUBLESHOOTING.md)。
