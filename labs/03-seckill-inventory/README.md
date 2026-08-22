# 实验三：秒杀、库存与接口幂等

## 幂等运行说明

重试时保持 `Idempotency-Key` 与请求体不变；同 key 并发只创建一笔订单并重放相同 UTF-8 JSON。不同 key 由 `(user_id, product_id)` 唯一索引保证只成交一次。系统异常返回 5xx 时幂等记录随事务回滚，修复后可安全复用同 key 重试。

多实例时幂等记录存放在共享 MySQL，而不是进程内锁。请求先竞争 `idempotency_record` 唯一键，再读取该 key；竞争失败的实例短暂轮询，直到持有者提交终态响应，因此两个端口会返回完全相同的状态、原始 JSON 字节和 `application/json;charset=UTF-8`。处理超时后才允许接管 `PROCESSING` 记录，接管窗口应结合最长业务耗时设置。

```powershell
$key = [guid]::NewGuid().ToString()
$body = @{ userId = 101; productId = 1 } | ConvertTo-Json -Compress
Invoke-WebRequest -Uri http://localhost:8080/api/seckill/orders -Method Post -Headers @{ 'Idempotency-Key' = $key } -ContentType 'application/json; charset=UTF-8' -Body $body
```

本实验用一个最小的 Spring Boot 服务演示秒杀下单的库存一致性：数据库条件更新防止超卖，事务保证扣库存与写订单的原子性，`(user_id, product_id)` 唯一索引保证同一用户只能购买一次。Flyway 会创建表并写入产品 1（初始库存 10）。幂等主流程使用 `READ_COMMITTED`；先竞争 `idempotency_record` 唯一键，再用 `SELECT ... FOR UPDATE` 读取终态并重放响应，不依赖 `Thread.sleep`，也没有 `REQUEST_IN_PROGRESS` 业务状态。

## 环境

- JDK 17（禁止使用 JDK 25）
- Docker Desktop（`mvnw.cmd verify` 的 Testcontainers 集成测试需要 Docker Engine）
- Windows PowerShell；命令均在本目录执行

## 启动 MySQL

```powershell
Copy-Item .env.example .env
# 编辑 .env，仅填写本机凭据；不要提交 .env
docker compose up -d
docker compose ps
```

Compose 只启动实验三所需的 MySQL，不复用其他实验的服务或数据卷。默认宿主端口是 3309；若本机 3309 已占用，修改 `.env` 的 `MYSQL_PORT`，并把下面检查中的 `3309` 与 `DB_URL` 端口一起改成同一个值。Compose 和应用必须使用同一份 `.env` 中的数据库用户名与密码。

启动 Spring Boot 前，必须在当前 PowerShell 进程中加载本地 `.env`。Maven 不会自动读取 `.env`，因此只把文件放在项目目录中并不足以让 `application.yml` 获得 `DB_URL`、`DB_USERNAME` 和 `DB_PASSWORD`。下面的步骤不会回显密码，也不会修改 `.env`：

```powershell
$envFile = Join-Path (Get-Location) '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
  throw '未找到 .env，请先执行 Copy-Item .env.example .env 并填写本机凭据。'
}

$dotenv = @{}
foreach ($line in Get-Content -LiteralPath $envFile) {
  if ($line -match '^\s*(MYSQL_PORT|MYSQL_USERNAME|MYSQL_PASSWORD|DB_URL|DB_USERNAME|DB_PASSWORD)\s*=\s*(.*?)\s*$') {
    $key = $Matches[1]
    $value = $Matches[2].Trim()
    if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
        ($value.StartsWith("'") -and $value.EndsWith("'"))) {
      $value = $value.Substring(1, $value.Length - 2)
    }
    $dotenv[$key] = $value
  }
}

foreach ($key in 'MYSQL_PORT', 'MYSQL_USERNAME', 'MYSQL_PASSWORD', 'DB_URL', 'DB_USERNAME', 'DB_PASSWORD') {
  if (-not $dotenv.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($dotenv[$key])) {
    throw ".env 缺少 $key 或其值为空。"
  }
}
if ($dotenv['MYSQL_PORT'] -notmatch '^\d+$') {
  throw 'MYSQL_PORT 必须是数字。'
}
if ($dotenv['DB_URL'] -notmatch '^jdbc:mysql://localhost:(\d+)/') {
  throw 'DB_URL 必须使用 jdbc:mysql://localhost:<MYSQL_PORT>/... 格式。'
}
if ([int]$Matches[1] -ne [int]$dotenv['MYSQL_PORT']) {
  throw 'DB_URL 的端口必须与 MYSQL_PORT 一致。'
}
if ($dotenv['MYSQL_USERNAME'] -cne $dotenv['DB_USERNAME']) {
  throw 'MYSQL_USERNAME 必须与 DB_USERNAME 一致。'
}
if ($dotenv['MYSQL_PASSWORD'] -cne $dotenv['DB_PASSWORD']) {
  throw 'MYSQL_PASSWORD 必须与 DB_PASSWORD 一致。'
}

$env:DB_URL = $dotenv['DB_URL']
$env:DB_USERNAME = $dotenv['DB_USERNAME']
$env:DB_PASSWORD = $dotenv['DB_PASSWORD']
Write-Host '已将 DB_URL、DB_USERNAME、DB_PASSWORD 加载到当前 PowerShell 进程（凭据未回显）。'
```

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
./mvnw.cmd spring-boot:run
```

## HTTP 下单

产品 ID 为 `1`。请求体中的 `userId` 和 `productId` 必须为正数。

```powershell
$body = @{ userId = 101; productId = 1 } | ConvertTo-Json -Compress
Invoke-WebRequest -Uri http://localhost:8080/api/seckill/orders -Method Post `
  -ContentType 'application/json' -Body $body
```

成功返回 HTTP `201 Created` 和订单 JSON。对同一产品库存耗尽后继续下单返回 `409`、错误码 `SOLD_OUT`；同一 `userId` 再次购买返回 `409`、错误码 `ALREADY_PURCHASED`。重复下单即使并发发生，也会回滚本次已经执行的库存扣减，因此库存不会被重复购买消耗。

## 验证

必须使用 JDK 17 执行完整验证（Surefire 单元测试和 Failsafe Testcontainers 集成测试都会运行）：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
./mvnw.cmd verify
```

30 个 Surefire 单元测试覆盖服务、控制器和锁冲突分支；13 个 Failsafe Testcontainers 集成测试中，`SeckillOrderHttpIT` 使用真实 HTTP 和 MySQL 验证并发库存、重复购买、售罄、双实例幂等重放、首事务回滚后重新争抢及 UTF-8 响应，`SeckillSchemaIT` 验证 Flyway 表结构、唯一索引和种子商品。Docker 不可用时，集成测试会失败，不能只把单元测试通过当作完整验收。

## 关键实现

`UPDATE seckill_product SET stock = stock - 1 WHERE id = ? AND stock > 0` 以受影响行数表达库存是否可扣；成功后在同一 `@Transactional` 方法内插入订单。唯一索引处理并发重复请求，捕获冲突并转成业务错误；事务回滚会撤销冲突请求的库存更新。

数据库锁顺序固定为“幂等键记录 → 商品库存 → 订单唯一索引”，避免不同代码路径先锁商品再锁幂等记录造成死锁。幂等响应以原始 JSON 字符串写入 `LONGTEXT`（`utf8mb4`）并按 UTF-8 原样返回。首个持有者事务回滚后，唯一键不再被占用，竞争请求会重新争抢并继续处理；流程不靠 `Thread.sleep` 或 `REQUEST_IN_PROGRESS`。只有确认是死锁、锁等待超时等真实锁冲突才映射 UTF-8 的 `503 RETRYABLE_DATABASE_CONFLICT`，普通数据库异常仍返回 500；客户端应使用原幂等键重试。

更多环境问题见 [TROUBLESHOOTING.md](TROUBLESHOOTING.md)。
