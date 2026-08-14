# Learning Log

## 记录模板

### YYYY-MM-DD

- 今日目标：
- 完成内容：
- 测试证据：
- 遇到的问题：
- 原因与解决方式：
- 技术选择及取舍：
- 明日第一步：

## 2026-08-13

- 今日目标：建立 Java 后端学习工作区并检查环境。
- 已确认：本机存在 JDK 17；Docker CLI 与 Compose 已安装。
- 待处理：Docker Desktop 启动后引擎尚未响应；后续需完成首次启动设置或系统重启。
- Maven：全局版本为 3.8.1；项目阶段使用 Maven Wrapper 固定 3.9.x。
- Docker：重启后已使用官方 `hello-world` 容器验证 Docker Engine 29.7.2。
- Task 1：基线提交为 `bb54c17`，两个参考仓库均为干净的浅克隆。

## 2026-08-14

- 今日目标：完成实验一 JWT/RBAC 的 JDBC 持久化、认证 HTTP 接口、真实 MySQL 端到端验证，并能在本地手动复现完整认证闭环。
- 脱敏约定：本文中 `*` 表示账号、密码、哈希、token 或密钥等本地敏感值，真实值不记录在仓库。

- 完成内容：
  - 为 `UserRepository` 补充按 ID 查询；实现 `JdbcUserRepository`、`JdbcRbacRepository` 和 `JdbcRefreshTokenSessionRepository`，使用 `JdbcTemplate` 与参数化 SQL 访问 `sys_user`、RBAC 关联表和 `refresh_token`。
  - 新增 `AuthController`：`POST /api/auth/login` 校验用户名和 BCrypt 密码后签发 access/refresh token；`POST /api/auth/refresh` 轮换 refresh token；`POST /api/auth/logout` 吊销 refresh token。
  - 新增 Spring Bean 装配，提供 BCrypt `PasswordEncoder`、UTC 时钟、JWT 服务、认证服务、RBAC 服务与 refresh-token 服务；安全配置放行 `/api/auth/`，其余请求保持无状态 JWT 认证。
  - 保留管理员接口 `/api/admin/users` 的 `system:user:read` 权限约束；用户已认证但缺少权限时返回 403，缺少或无效 access token 时返回 401。

- 刷新令牌设计与并发修复：
  - access token 生命周期为 15 分钟；refresh token 生命周期为 7 天。
  - refresh token 使用 `SecureRandom` 生成，只保存 SHA-256 哈希，不保存原始 token。
  - 初版轮换逻辑是“先读会话，再撤销，再签发新 token”。并发请求可能同时读到未撤销会话，导致两个请求都刷新成功。
  - 使用真实 HTTP 并发测试复现：同一 refresh token 的两个并发请求均返回 200。
  - 修复为事务内的条件更新：`UPDATE refresh_token SET revoked = TRUE WHERE token_hash = ? AND revoked = FALSE AND expires_at > ?`；只有受影响行数为 1 的请求可以签发 successor token。修复后并发测试验证恰好一个 200、一个 401。

- 测试证据：
  - `mvnw.cmd verify` 成功：15 个测试通过、0 失败、0 跳过。
  - `AuthFlowE2ETest` 使用 Testcontainers MySQL、Flyway 迁移和随机 HTTP 端口，覆盖：登录成功、带 access token 访问管理员接口、refresh token 轮换、旧 refresh token 立即失效、注销后的刷新失败、同一 refresh token 并发时只允许一个请求成功。
  - 手动本地验证完成：登录返回 200；管理员接口返回 200；刷新返回新 token；注销返回 204；使用已注销 refresh token 刷新返回 401。

- 本地环境与排障记录：
  - 宿主机 MySQL 占用 3306 时，Compose 映射 3306 会失败；本地容器改用 3307，并让应用数据源指向 `jdbc:mysql://localhost:3307/security_rbac`。
  - Compose 的 `.env` 变量优先级高于 `compose.yaml` 中的默认值；使用 `docker compose config` 检查最终生效端口、数据库名和应用用户，避免只修改默认值却仍映射到旧端口。
  - MySQL 官方镜像中的 `MYSQL_USER` 只能是普通应用用户，不能设为 `root`；健康检查应使用 root 密码，或明确指定实际被检查的用户。
  - Windows 未将 `mysql.exe` 加入 PATH 时，使用 `docker compose exec mysql mysql -u * -p *` 进入容器自带客户端。
  - 本地初始账号必须写入 `sys_user`，密码字段只能存 BCrypt 哈希；还需写入角色、权限、`sys_user_role` 与 `sys_role_permission` 才能访问管理员接口。
  - 使用 JShell 单独调用 Spring Security 的 BCrypt 类时，需要同时在 classpath 提供 `spring-security-crypto` 与 `spring-jcl`。先执行 `encoder.matches(password, hash)` 返回 true，再把完整哈希写入数据库；不要把尖括号、空格或占位符写入哈希字段。
  - PowerShell 中优先用 `ConvertTo-Json -Compress` 构造请求体；手工拼接 JSON 容易造成字段名缺少双引号，Spring 会抛出 `HttpMessageNotReadableException`，认证逻辑不会执行。

- 认证链路复盘：
  - 登录请求进入 `AuthController`，`AuthService` 通过用户名查找用户并以 BCrypt 校验密码；成功后返回 access token 与 refresh token。
  - 访问 `/api/admin/users` 时，客户端携带 `Authorization: Bearer *`；`JwtAuthenticationFilter` 验证 JWT，读取 userId，通过 `RbacService` 查询权限码，创建 `Authentication` 放入 `SecurityContext`。
  - `SecurityConfig` 再判断当前 `Authentication` 是否具有 `system:user:read`：通过则返回 200，已认证但无权限为 403，未认证或 token 无效为 401。

- 技术选择及取舍：
  - 使用短期 access token + 可撤销、可轮换的 refresh token，而不是试图维护 JWT 黑名单；这样兼顾常规接口的无状态校验与 refresh 会话的主动注销能力。
  - 使用数据库条件更新表达“消费一次”的并发语义，而不是依赖应用层先查后写。
  - Testcontainers 覆盖真实 MySQL、Flyway 与 HTTP 调用；MockMvc 仍适合快速验证权限分支，但不能替代这条真实部署路径。

- 安全与后续改进：
  - 本地数据库密码、JWT 密钥和初始化管理员密码均不得提交到 Git，也不得写入学习日志、README 示例或聊天记录；应由环境变量或被忽略的本地配置文件提供。
  - 当前认证控制器对格式错误的 JSON 走框架默认异常路径；后续可增加统一异常处理，将非法请求稳定返回 400，并在 README 中说明错误响应。
  - 将本地管理员初始化过程整理为不纳入版本库的脚本或明确的开发 profile，避免团队成员手工复制 SQL。

- 明日第一步：完成实验一 README 与接口示例，在 `interview/question-bank.md` 写入 10 个与 JWT、RBAC、refresh token 轮换和并发条件更新相关的追问，然后复跑 `mvnw.cmd verify` 并准备提交实验一。
