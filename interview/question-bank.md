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

## 技术取舍速记

| 选择                       | 收益                              | 代价                                  |
| -------------------------- | --------------------------------- | ------------------------------------- |
| 短期 JWT access token      | 资源访问不需要服务端 session 查询 | 注销后不能立刻撤销已签发 access token |
| 数据库 refresh token       | 可撤销、可轮换、可检测重复使用    | 刷新请求需要访问数据库                |
| 条件更新消费 refresh token | 同一 token 并发时只会成功一次     | 依赖数据库事务与受影响行数判断        |
| 每次请求查询权限           | 权限变化立即生效                  | 增加一次 RBAC 查询                    |
| Flyway + Testcontainers    | 迁移与真实 MySQL 行为可复现       | 本地完整端测依赖 Docker               |
