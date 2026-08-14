# JWT 与 RBAC 权限服务

这是一个 Spring Boot 3 / Java 17 学习实验：使用 MySQL 持久化用户、角色、权限和 refresh token；使用短期 JWT access token 访问受保护接口；使用可撤销、单次消费的 refresh token 续期。

本次实验中遇到的环境、认证、并发与 Git 发布问题，见 [TROUBLESHOOTING.md](TROUBLESHOOTING.md)。

文档中的 `*` 表示本地账号、密码、哈希、token 或密钥的脱敏值；请替换为本机值，但不要提交、截图或发送真实值。

> 阅读提示：反引号包围的内容是命令、文件名、环境变量或错误文本。若复制到纯文本后出现词语粘连，请在 VS Code 中打开本文件并按 `Ctrl+Shift+V` 查看 Markdown 预览，或直接在 GitHub 文件页面阅读渲染版本。

## 你将运行到的能力

- `POST /api/auth/login`：BCrypt 校验用户名和密码，签发 access / refresh token。
- `GET /api/admin/users`：JWT 认证后要求 `system:user:read` 权限。
- `POST /api/auth/refresh`：原 refresh token 原子撤销并签发一对新 token。
- `POST /api/auth/logout`：撤销给定 refresh token，返回 `204 No Content`。
- Flyway 自动创建 `sys_user`、RBAC 关联表和 `refresh_token`。

## 前置条件

- JDK 17 或更高版本
- Docker Desktop 已启动，且 `docker compose version` 可用
- Windows 使用 PowerShell；Linux/macOS 将 `mvnw.cmd` 换成 `./mvnw`

## 1. 创建本地配置

仓库不提交 `.env`、数据库密码或 JWT 密钥。复制模板并替换所有占位值：

```powershell
Copy-Item .env.example .env
notepad .env
```

`JWT_SECRET` 必须是 Base64 编码、解码后至少 32 字节的随机值。PowerShell 可生成一个值：

```powershell
$bytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
[Convert]::ToBase64String($bytes)
```

将输出复制到 `.env` 中 `JWT_SECRET` 的值位置。不要把 `.env`、真实密码、完整 BCrypt 哈希或 token 提交、截图或发送到聊天。

## 2. 启动 MySQL

```powershell
docker compose up -d
docker compose ps
docker compose logs mysql
```

预期 MySQL 服务最终显示为 `healthy`。本实验默认映射宿主机 `3307`，避免常见的本机 MySQL `3306` 端口冲突。

停止容器但保留数据：

```powershell
docker compose down
```

彻底清除本实验的本地数据库数据（不可恢复）：

```powershell
docker compose down -v
```

## 3. 启动应用

Compose 会自动读取 `.env`，但 Maven 不会；先把 `.env` 载入当前 PowerShell 进程：

```powershell
Get-Content .env | ForEach-Object {
  if ($_ -match '^\s*([^#=\s]+)=(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
  }
}

.\mvnw.cmd spring-boot:run
```

应用默认监听 `http://127.0.0.1:8080`。首次启动时 Flyway 会自动执行 `V1__rbac_schema.sql`。

`/api/auth/login` 是只接收 JSON 的 **POST** 接口；直接在浏览器地址栏打开它会发送 GET，并得到 401。这不是登录失败。

## 4. 创建本地演示账号

应用不会提交固定管理员账号。先生成一个 BCrypt 哈希（仅在本机操作）：

```powershell
.\mvnw.cmd -q dependency:build-classpath -Dmdep.outputFile=target\classpath.txt
$classpath = (Get-Content target\classpath.txt -Raw).Trim()
jshell --class-path $classpath
```

在 `jshell>` 中输入自己选择的本地密码，复制输出的完整哈希；`matches` 必须为 `true`：

```java
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
var encoder = new BCryptPasswordEncoder();
var password = "*";
var hash = encoder.encode(password);
encoder.matches(password, hash);
hash
```

进入容器内 MySQL（提示输入的是应用数据库密码）：

```powershell
docker compose exec mysql mysql -u $env:MYSQL_USER -p $env:MYSQL_DATABASE
```

将下面 SQL 中的 `*` 替换为刚生成的完整哈希，再执行。它会创建或更新 `admin`，并授予读取用户列表所需权限：

```sql
INSERT INTO sys_user (username, password_hash, enabled)
VALUES ('admin', '*', TRUE)
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), enabled = VALUES(enabled);

INSERT INTO sys_role (code, name)
VALUES ('ADMIN', 'Local Administrator')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO sys_permission (code, name)
VALUES ('system:user:read', 'Read users')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id FROM sys_user u CROSS JOIN sys_role r
WHERE u.username = 'admin' AND r.code = 'ADMIN';

INSERT IGNORE INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r CROSS JOIN sys_permission p
WHERE r.code = 'ADMIN' AND p.code = 'system:user:read';
```

## 5. 手动验证完整认证链路

登录。不要单独输出 `$body`，其中包含明文密码：

```powershell
$password = Read-Host '输入本地开发密码'
$body = @{ username = 'admin'; password = $password } | ConvertTo-Json -Compress
$response = Invoke-RestMethod `
  -Uri 'http://127.0.0.1:8080/api/auth/login' `
  -Method Post -ContentType 'application/json' -Body $body

$accessToken = $response.accessToken
$refreshToken = $response.refreshToken
```

带 access token 访问管理员接口：

```powershell
Invoke-RestMethod `
  -Uri 'http://127.0.0.1:8080/api/admin/users' `
  -Headers @{ Authorization = "Bearer $accessToken" }
```

刷新会返回一对新 token；旧 refresh token 会立即失效：

```powershell
$refreshBody = @{ refreshToken = $refreshToken } | ConvertTo-Json -Compress
$refreshed = Invoke-RestMethod `
  -Uri 'http://127.0.0.1:8080/api/auth/refresh' `
  -Method Post -ContentType 'application/json' -Body $refreshBody

$rotatedRefreshToken = $refreshed.refreshToken
```

注销后再次用 `$rotatedRefreshToken` 调用 refresh，PowerShell 抛出 HTTP 401 是预期结果：

```powershell
$logoutBody = @{ refreshToken = $rotatedRefreshToken } | ConvertTo-Json -Compress
Invoke-WebRequest `
  -Uri 'http://127.0.0.1:8080/api/auth/logout' `
  -Method Post -ContentType 'application/json' -Body $logoutBody
```

## 接口与状态码

| 方法 | 路径 | 成功响应 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/auth/login` | 200，`accessToken`、`refreshToken` | JSON 用户名和密码 |
| POST | `/api/auth/refresh` | 200，新的 token 对 | 旧 refresh token 随即失效 |
| POST | `/api/auth/logout` | 204，无响应体 | 撤销 refresh token |
| GET | `/api/admin/users` | 200，用户摘要列表 | 需要 `system:user:read` |
| POST | `/api/admin/users/{userId}/roles/{roleId}` | 204，无响应体 | 需要 `system:user:grant` |

- **401**：未提供有效身份凭据，例如 access token 缺失/无效，或用户名密码不匹配。
- **403**：JWT 已通过认证，但当前用户没有目标接口所需权限。

## 认证、授权与 token 轮换

登录时，`AuthController` 调用 `AuthService`，后者从 `sys_user` 查询用户并以 BCrypt 校验密码。成功后签发 15 分钟 access JWT 与 7 天 refresh token。

访问受保护接口时，`JwtAuthenticationFilter` 验证 access JWT、取出 `userId`，再通过 `RbacService` 查询权限码，构造 `Authentication` 放入 `SecurityContext`。`SecurityConfig` 根据 `hasAuthority(...)` 产生 200 或 403。

access JWT 无状态，注销不能让已经签发的 access token 立即失效；本实验以短 TTL 降低风险。refresh token 则是随机值，只存 SHA-256 哈希。刷新时使用条件更新：

```sql
UPDATE refresh_token
SET revoked = TRUE
WHERE token_hash = ? AND revoked = FALSE AND expires_at > ?
```

只有影响一行的请求才能签发 successor token。因此并发使用同一 refresh token 时恰好一个请求成功，另一个返回 401。

## 自动化验证

```powershell
.\mvnw.cmd verify
```

测试包含单元测试、MockMvc 的 401/403 授权分支，以及 Testcontainers MySQL 的真实 HTTP 登录—访问—刷新—注销流程和并发刷新测试。Docker 不可用时，标有 `disabledWithoutDocker` 的端到端测试会跳过；要获得完整验证，请先启动 Docker Desktop。
