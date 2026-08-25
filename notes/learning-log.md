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

## 2026-08-21

- 今日目标：完成实验二 Redis 缓存与一致性的最终验收，并排除测试日志中的异步 Redis 重连告警。
- 完成内容：为 `ActuatorE2EIT` 与 `ProductionCacheWiringIT` 添加类级 `@DirtiesContext(AFTER_CLASS)`，使 Spring 在 Testcontainers 关闭临时 Redis 前销毁上下文管理的 Lettuce/Redisson 客户端。
- 问题与原因：原先 Failsafe 的 22 个集成测试均通过，但 `ConnectionWatchdog` 会在对应 Redis Testcontainer 停止后，对失效动态端口异步重连并记录 WARN。异常发生在测试断言之后，因此 Maven 成功不等于资源清理日志已通过人工验收。
- 测试证据：使用 JDK 17 和 Docker Desktop 宿主权限执行 `mvnw.cmd verify`；24 个单元测试、22 个 Testcontainers 集成测试均为 0 failures、0 errors、0 skipped，且日志扫描未发现 `ConnectionWatchdog` 重连、`Connection closed prematurely` 或 `Connection refused`。
- 技术选择及取舍：采用 `@DirtiesContext` 精确关闭使用动态容器端口的 Spring 测试上下文，而非降低日志级别；这保留了真正连接故障的可见性，也使测试资源生命周期与容器一致。
- 明日第一步：开始阶段三“秒杀、库存与接口幂等”，先明确库存扣减、幂等键和并发验收测试。

## 2026-08-23

- 今日目标：完成阶段四订单状态机与 RabbitMQ 可靠消息实验，并把实现、测试、排障和面试追问闭环。
- 完成内容：
  - 用条件更新实现 `PENDING_PAYMENT → PAID/CANCELLED`；创建订单时在同一事务写入订单、扣减库存和 `ORDER_TIMEOUT` Outbox。
  - Outbox dispatcher 以 `NEW → PUBLISHING` 数据库租约和 claim token 控制并发，publisher confirm 成功后才标记已发布；过期租约可被下一实例接管，旧 owner 的迟到 ACK/NACK 会被 fencing。
  - RabbitMQ 使用 10s、1m、5m 固定 TTL 桶和 DLX；生产默认 1m，集成测试用 `order.timeout.routing-key=order.timeout.10s` 覆盖为 10s。取消 quorum 队列设置三次投递限制，失败后进入人工处理队列。
  - 消费记录以 eventId 唯一键和 `PROCESSING/COMPLETED` 租约去重；取消订单、实际释放库存和完成记录在同一事务中提交，重复消息不会再次释放库存。
  - 事件模型固定为五字段；构造器拒绝非法值，Jackson 在反序列化边界快速失败，服务层只处理状态和库存等业务规则。
- 测试证据：使用 JDK 17、MySQL 8.4 与 RabbitMQ 3.13 Testcontainers 完整执行 `mvnw.cmd verify`，当前验收提交 `ad55717` 的结果为 59 个 Surefire 单元测试、26 个 Failsafe 集成测试，0 failures、0 errors、0 skipped，Maven `BUILD SUCCESS`（耗时 1:46）。集成测试用共享容器提升效率，但每个测试独立清理数据库和同步 purge 队列。
- 本轮排障：
  - ObjectMapper 夹具补充 `JavaTimeModule`，解决 `Instant` 事件时间字段读写问题，并用稳定五字段模型验证未知字段、缺失字段和非法值的快速失败。
  - Testcontainers RabbitMQ 显式声明 `/` vhost 与 order_mq 的 configure/write/read 权限，解决连接成功但声明队列被拒绝的问题。
  - 共享容器的队列清理改用同步 `rabbitAdmin.purgeQueue(queue, false)`，避免旧消息在下一测试发布后被误消费。
  - Spring Retry 恢复器改读实际 Retry 上下文次数，修正入站 `x-retry-count` 与本轮 attempt 的 off-by-one；TIMESTAMP 夹具统一使用 UTC `Instant`，消除宿主时区差异。
- 技术选择及取舍：消费幂等使用数据库唯一键加事务，而不是只依赖 RabbitMQ 的投递语义。唯一键把同一 eventId 的并发竞争收敛到数据库；事务把订单条件取消、库存释放和 `COMPLETED` 记录绑定，业务提交前不 ACK，崩溃后可重试或接管。代价是消费路径依赖数据库可用性，因此对死锁、锁等待超时和连接异常仅做有限重试。
- 已知边界：数据库人工失败副本和人工 publish 同时失败的极端路径只能依赖 broker-only DLX；该副本保留原 payload 与 Rabbit `x-death` / delivery metadata，但可能缺少自定义 `failure-category` / `failure-message`，不能宣称完全无损。
- 明日第一步：进入阶段五前，先复查实验四的 Outbox、消费幂等和人工失败监控指标设计。

## 2026-08-25

- 今日目标：完成阶段五 Elasticsearch 商品搜索实验的真实环境验收，并把可靠同步、在线重建和故障恢复证据沉淀到文档中心。
- 完成内容：
  - 以 MySQL 商品表为事实源，在同一事务写入不可变 `search_outbox` 事件；搜索侧使用 SmartCN、`ON_SALE` filter、相关性排序和分类聚合。
  - dispatcher 以 `search_coordination → search_rebuild_job → product → search_outbox` 固定锁顺序领取事件，批量上限 50、租约 30 秒、claim token fencing 和有限退避；索引写入使用 Elasticsearch `external_gte`，允许重复投递但禁止旧版本覆盖。
  - 删除通过 `IndexMutation` 写入带版本的 tombstone；在线重建使用可重复读快照、Outbox 高水位补放、短暂写入门禁、最终校验和读写别名原子切换，恢复逻辑对单目标、旧目标和别名分裂分别处理。
- 测试证据：验收分支 `learning/elasticsearch-search` 的 HEAD 为 `f2d10f6c5d3b7a6fd1ca8455187aa5cfa488d612`。真实 MySQL 8.4、SmartCN Elasticsearch 8.18.8 与 Toxiproxy 2.12.0 环境执行 `mvnw.cmd verify`；Surefire XML 合计 44 tests、0 failures、0 errors、0 skipped，Failsafe XML 合计 52 tests、0 failures、0 errors、0 skipped，Maven BUILD SUCCESS，报告失败扫描无输出。
- 并发竞态与取舍：商品写入、重建和 dispatcher 共享协调行并遵守固定锁序；`FOR UPDATE SKIP LOCKED`、30 秒租约和 token fencing 让旧 owner 的迟到完成/重试不能修改新租约。采用至少一次 Outbox 投递换取故障后可恢复，重复事件由 `(product_id, product_version, event_type)` 唯一约束、`external_gte` 和幂等完成语义收敛；代价是必须接受短暂重复写入与可观测的积压。
- 重建与故障演练：`RebuildAndRecoveryDrillIT.repeatsThreeRebuildsAndTwoConnectionOutageRecoveriesWithoutRegression` 连续完成 3 次重建和 2 次 Elasticsearch 网络中断恢复；中断期间搜索返回 503、Outbox 形成积压，恢复后事件全部追平、索引版本一致且搜索恢复 200。
- 下一步：复查阶段五 README/TROUBLESHOOTING 的手工运维边界，并把高水位、别名分裂恢复和指标告警整理成面试中的故障演练回答。
