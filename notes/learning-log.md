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
  - dispatcher 以 `search_coordination → search_rebuild_job → product → search_outbox` 固定锁顺序领取事件，默认批量 50（可配置 1–50）、默认租约 30 秒（可配置且必须大于 request timeout），并使用 claim token fencing 和有限退避；索引写入使用 Elasticsearch `external_gte`，允许重复投递但禁止旧版本覆盖。
  - 删除通过 `IndexMutation` 写入带版本的 tombstone；在线重建使用可重复读快照、Outbox 高水位补放、短暂写入门禁、最终校验和读写别名原子切换，恢复逻辑对单目标、旧目标和别名分裂分别处理。
- 测试证据：验收分支 `learning/elasticsearch-search` 的 HEAD 为 `758ab9fb52c3f8245b9f2aea94c30c3d33c43549`（追加提交 `test(search): verify Chinese SmartCN search`）。真实 MySQL 8.4、SmartCN Elasticsearch 8.18.8 与 Toxiproxy 2.12.0 环境执行 `mvnw.cmd verify`；Surefire XML 合计 44 tests、0 failures、0 errors、0 skipped，Failsafe XML 合计 52 tests、0 failures、0 errors、0 skipped，Maven BUILD SUCCESS，报告失败扫描无输出。
- 并发竞态与取舍：商品写入、重建和 dispatcher 共享协调行并遵守固定锁序；`FOR UPDATE SKIP LOCKED`、默认 30 秒且可配置（必须大于 request timeout）的租约和 token fencing 让旧 owner 的迟到完成/重排或失败不能修改新租约。采用至少一次 Outbox 投递换取故障后可恢复，重复事件由 `(product_id, product_version, event_type)` 唯一约束、`external_gte` 和幂等完成语义收敛；代价是必须接受短暂重复写入与可观测的积压。
- 重建与故障演练：`RebuildAndRecoveryDrillIT.repeatsThreeRebuildsAndTwoConnectionOutageRecoveriesWithoutRegression` 连续完成 3 次重建和 2 次 Elasticsearch 网络中断恢复；中断期间搜索返回 503、Outbox 形成积压，恢复后事件全部追平、索引版本一致且搜索恢复 200。
- 下一步：复查阶段五 README/TROUBLESHOOTING 的手工运维边界，并把高水位、别名分裂恢复和指标告警整理成面试中的故障演练回答。

## 2026-08-29

- 今日目标：完成实验六安全文件服务的独立分支验收，并把跨数据库/对象存储的一致性、安全授权和故障恢复证据沉淀到文档中心。
- 上传事务边界与补偿：事务 A 创建 `RECEIVING` session、随机 temp key、owner token 和 TTL；事务外以同一消费流写临时对象并计算实际大小、SHA-256 与真实类型；事务 B 将 session 推进为 `VALIDATED`，按内容哈希锁定或创建 `STAGING` Blob；Blob owner 在事务外提交随机 object key；事务 C 将 Blob 置为 `READY`、创建逻辑文件、增加引用、完成 session 并写上传成功审计。对象存储失败、事务 C 失败或临时对象删除失败由持久状态和幂等任务补偿，数据库事务内不执行文件/MinIO IO。当前过期恢复只领取 `VALIDATED`/`FINALIZING`；中断后仍为 `RECEIVING` 的 session 不会自动转终态或自动清理，不能靠手工改状态或删 temp 绕过这一边界。
- 去重与授权：SHA-256 唯一约束让相同内容共享物理 Blob，但每次上传都创建新的随机 `fileId` 和独立 ACL；HTTP 响应、日志、审计和指标不泄漏去重命中、hash、blob ID 或 object key。文件默认私有，owner 可向指定用户授予/撤销只读权限，grantee 不能转授权或删除，管理员角色不自动旁路；已认证调用者面对不存在、已删除或无权资源时得到同构 404。所有物理 Blob 打开都必须先从逻辑文件重新执行 ACL。
- 下载撤权边界：`/content` 在响应前完成授权、审计、对象预打开和首段预读；本地 HMAC token 兑换时也重新检查 ACL，因此撤权可即时阻断后续兑换。MinIO presigned URL 由对象存储直接处理，签发后最多仍有约 2 分钟残余窗口；高风险或要求即时撤权的场景使用 `/content`。
- 并发与清理：上传、恢复和清理的租约/过期判断使用 MySQL 时间；owner token、claim token、object key 与 generation 共同 fencing，过期租约可由新执行者接管，旧执行者迟到完成影响行数为 0。引用归零后才创建 Blob 清理任务；删除不存在对象视为成功，Blob 与 task 原子完成，失败按固定退避最多尝试 5 次，使重复执行和崩溃恢复最终收敛。
- 审计与可观测性：上传成功、文件访问与拒绝、ACL grant/revoke/delete、下载授权/完成/失败、token 拒绝和链接签发按既有 `AuditAction` 持久化；上传失败由 session 状态/分类和指标记录，cleanup/recovery 当前没有独立 `AuditRecorder` 动作。审计集中丢弃 token、签名 URL、object key、路径、hash、secret 和异常堆栈；内部 correlation ID 随机生成且不接受客户端覆盖。Micrometer 只使用固定枚举的低基数标签，不把 user/file/hash/blob/object/correlation ID 或异常消息作为标签。
- 故障与验收证据：`StorageRecoveryDrillIT` 通过 Toxiproxy 连续执行 3 轮“断开 MinIO → 观察失败/积压 → 恢复 route → 运行 recovery/cleanup → 验证收敛”。每轮递归核对测试 bucket：`tmp/` 为空，`blobs/` 的对象集合精确等于数据库 `READY` Blob 的 `object_key` 集合；该 exact-set 结论只覆盖测试 bucket 与数据库已知对象，不扩大为任意外部对象的独立盘点证明。实验分支 `learning/secure-file-service` 最终提交为 `18603f1`；fresh `mvnw.cmd clean verify` 的 Surefire 96、Failsafe 80 均为 0 failures、0 errors、0 skipped。
- 技术取舍：使用短事务、状态机、租约和补偿换取跨 MySQL/对象存储故障后的可恢复性，代价是允许可观测的中间态和最终一致窗口；去重节省存储但必须隔离逻辑授权和接口行为；presigned URL 降低应用流量却牺牲签发后的即时撤权。

## 2026-09-11

- 今日目标：完成实验七校园二手交易平台的全量验收，按前六个实验补齐运行、排障、学习复盘和面试追问，并记录扩展暂停决策。
- 完成内容：
  - 建立校园邮箱验证码、注册登录和 JWT 链路；校园邮箱只证明邮箱控制权，正式 CAS 仅保留 `ExternalIdentityProvider` 端口。
  - 完成商品草稿、私有媒体、发布/下架、批量库存与流水；订单只含一个发布项但支持多数量，金额统一使用人民币整数分，库存通过数据库条件更新防止超卖。
  - 以 `Idempotency-Key + 请求摘要` 实现命令重放；同键同参返回原始 UTF-8 终态响应，同键异参返回 409，失败事务不保留阻塞记录。
  - 模拟支付适配器覆盖签名回调、nonce 防重放、单次支付尝试、退款额度预占和 `UNKNOWN` 主动对账；不得把未知结果当失败后重新创建请求。
  - 完成支付 15 分钟、交付 72 小时、确认 48 小时、三天验收、七天试用的数据库时间边界；退货退款依赖可信证明，部分退货进入隔离库存，证据冲突在硬期限进入 `ESCALATED`。
  - 订单结算后保持 `SETTLED`，30/90/180/365 天卖家质保独立流转；裁定产生卖家义务，逾期限制发布/提现，主动筹资或未来结算按唯一业务键抵扣并幂等解限。
  - 可靠协作使用事务 Outbox、RabbitMQ publisher confirm、Inbox、数据库租约和 claim-token fencing；Elasticsearch 使用 SmartCN、外部版本、tombstone、高水位补放和原子别名切换；MinIO 对象通过随机 key、实际读取上限、案件 ACL 和持久清理任务保护。
- 测试证据：实验分支 `learning/campus-market` 的验收状态提交为 `4148f1e`。JDK 17 下最新 `mvnw.cmd test` 为 137 tests、0 failures、0 errors、0 skipped；完整 `mvnw.cmd verify` 的 Failsafe/Testcontainers 为 251 tests、0 failures、0 errors、0 skipped。`CampusMarketJourneyIT` 覆盖教材交易与质保旅程；三轮恢复分别断开 RabbitMQ、Elasticsearch、MinIO，并通过补充阶段核对证据 ACL 与 MySQL/SmartCN Elasticsearch 在售集合。
- 本轮排障：完整套件中消息测试曾被前序支付、退款或质保 Outbox 和旧 Spring 上下文调度器污染。通过关闭共享夹具的默认 Rabbit listener/搜索调度器、延后必须保留的截止任务，并用 `PaymentFlowIT,ReliableMessagingIT` 同 JVM 定点组合验证，区分跨类状态泄漏与 Docker OOM。低内存环境按重型依赖拆段串行运行，内存低于 1 GiB 时停止而不是伪造 skipped 通过。
- 技术选择及取舍：MySQL 作为交易与截止时间事实源，使并发裁决可由行锁、条件更新和受影响行数证明；代价是更多持久状态和恢复任务。异步外部协作允许短暂积压，但通过幂等键、租约、fencing 和指标收敛。案件证据把统一 404 和“管理员不自动绕过 ACL”置于操作便利之上；真实支付、物流与 CAS 在缺少资质和授权时保持端口而不虚假接入。
- 扩展决策：原始设计中的 `7.1` 聊天、`7.2` 竞价、`7.3` 跑腿/代取和真实支付适配器涉及通信内容与个人信息处理、交易平台责任、服务规则、支付资质和资金安全等法律与合规问题，当前暂不开展。已经形成的聊天设计和合规检查表只作为决策记录保留，不进入实现、测试或上线流程，也不再作为实验七验收前置条件。
- 下一步：实验七结束，进入实验八 Spring Cloud 渐进拆分；任何扩展若未来重新启动，必须先完成独立法律合规评估并获得明确授权。

## 2026-09-13

- 今日目标：继续实验八 8.1 身份服务渐进拆分，保留实验七业务基线和单实验分支边界。
- 当前进度：本地分支 `learning/spring-cloud-split` 已建立聚合工程、独立身份库、RS256/JWKS 身份服务、兼容单体独立验签和 Eureka。Eureka 的真实 HTTP 中文 JSON 与显式 UTF-8 修正在 `277dded` 完成并通过任务复核；Gateway 实现在 `cd697d0` 提交，正在进行独立规格与代码质量复核。
- 阶段测试证据：提交 `5949ccd` 前的 JDK 17 Reactor `mvnw.cmd test` 为 platform-test-support 6、discovery 4、identity 24、legacy 135、Gateway 21，合计 190 项，均为 0 failures、0 errors、0 skipped。legacy 数量包含尚未提交的本地 Dockerfile 路径守卫；此前沟通中的 170 为加总错误。以上是阶段测试，不代表实验级完整验收。
- 本轮排障：Eureka 原健康响应缺少显式 charset，使用实际生效的 `server.servlet.encoding` 并以真实 HTTP 中文 JSON 验证；Gateway 的 issuer 校验曾直接比较 String 与 URL，导致有效 Token 被拒绝，改为精确比较字符串表示；路由断开和下游 5xx 使用稳定中文 UTF-8 503，并丢弃下游异常体。
- 恢复后的验证：Gateway 任务规格与质量审查通过，五项 Minor 留待最终复核。完整 legacy 回归暴露两个搜索套件无法发现启动配置，显式指定 `LegacyMarketApplication` 后定点 40 项通过；后续完整运行 264 项为 1 failure、0 errors、0 skipped，唯一失败为质保筹资与过期处理竞争时的 MySQL 死锁。当前正在验证候选范围锁与主键行锁的顺序风险，尚未完成修正或重新验收；四应用旅程及停机恢复尚未开始。
- 待完成：四应用真实 Eureka/HTTP 旅程、JWKS 缓存与服务停机恢复、实验七完整业务回归、运行与复盘文档，以及 Reactor 完整 `verify`。覆盖清单守卫已提交 `5949ccd`，完整 legacy 验证与 Gateway 审查已恢复；实验八保持“进行中”，远端尚无实验八分支入口。
- 执行约束：按用户要求在任务节点检查额度，任一适用窗口剩余达到 10% 时停止新增实验工作，整理进度、验证与遗留问题并更新 Git。此次远端推送被自动审批要求补充具体仓库及源码外发授权而拦截，本地提交保留。

## 2026-09-14

- 今日目标：按 AGENTS.md 恢复实验八 8.1，先完成质保截止并发测试质量修正及实验七完整业务回归，再进入四应用真实旅程和故障恢复。
- 已完成节点：测试关闭后台质保调度、手动创建被测调度器，控制筹资 UPDATE 保留真实状态条件并断言一行，锁诊断按随机目标义务过滤。真实 MySQL 定点 3/3 通过，独立规格与质量复核 PASS；仅测试修正提交 `a6c477e`。
- 完整回归证据：JDK 17、Docker Desktop 29.7.2 下 `mvnw.cmd -pl legacy-market-service -am verify` 退出0、BUILD SUCCESS（23分08秒）。本轮 support 单元6、identity 单元24/IT17、legacy 单元135/IT265，全部0 failures、0 errors、0 skipped；按运行启动时间过滤XML并与Maven汇总核对，排除历史报告。测试迁移配置与路径守卫提交 `678b8a8`。
- 排障与取舍：原生产修正 `44e4441` 保留逐义务主键锁、数据库时间和条件迁移，候选扫描不持有二级索引锁；不吞掉死锁、不放宽并发断言。全套日志仍有前序上下文在容器关闭后尝试调度的连接异常，未造成断言失败，最终复核继续检查上下文隔离。
- 当前进度：四应用真实 Eureka/HTTP 旅程正在实现，可执行打包配置尚待旅程验证。最小 Compose、应用实例地址、SMTP占位符、架构、迁移边界、学习日志与面试追问已补充，阶段提交 `6d141c4`；初始化脚本在全新临时MySQL8.4项目通过特殊字符测试密码、本库权限与跨库/DDL拒绝验证，测试环境已回收。实验保持“进行中”，尚不能用业务回归替代四应用故障恢复与最终 Reactor 验收。
- 暂停与遗留：节点额度五小时剩9%，按既有10%规则停止新增工作。Task9正确定点verify退出1：Gateway单元21项出现19个DataSource启动错误，CloudJourneyIT尚未运行；三个POM和两个旅程测试保持未提交、未验证WIP。需下次先诊断单元启动错误，再完成真实旅程、JWKS/停机恢复、应用打包启动与Reactor全验收。Maven/Docker测试进程已结束（执行者报告）；未继续运行或推送远端。实验八仍为进行中，Task11完整业务回归已完成。

### 2026-09-14：整理实验八上传快照

- 按用户要求整理 GitHub 上传进度，实验分支提交 `ea5cf49` 保存三个 POM 与两个四应用旅程测试文件，并在实验 README 明确 WIP 和 Gateway DataSource 启动阻塞。
- 本次仅保存和上传进度，不重新执行 Maven；暂存差异的 `git diff --cached --check` 通过，实验八仍为进行中、待完整 Reactor 验收。
- 上传目标为既有远程 `https://github.com/5Koi1shi4/java-roadmap.git`，实验分支 `learning/spring-cloud-split` 与文档中心 `main`；自动审批要求用户明确确认具体仓库/分支后才能外发源码，故本次尚未推送。

- 上传授权补充：用户明确确认上述仓库与分支，并要求检查与替换敏感信息。已将文档中的本机路径和用户名替换为 `<workspace>`、`<user-home>`，同时清理文档中心尚未推送的 13 个提交（不改动已发布历史）。实验分支的 21 个新增提交与文档中心历史经常见凭据、私钥及本机个人路径规则扫描，无剩余命中；配置使用环境变量/占位符，测试凭据仅用于临时容器，RSA 测试密钥运行时生成。

## 2026-09-16

- 今日目标：按已通过的 8.2 设计完成独立商品读服务，交易事实和订单快照暂留兼容单体；执行新鲜实验级验收并同步独立实验分支与 main 文档中心。
- 实现与取舍：第五应用只读 `product_read_db`、独立 SmartCN 索引与两条精确 Gateway GET 搜索；市场事务写完整 `schemaVersion=2` 源快照，Rabbit confirm、保留事件 replay 完成屏障、Inbox/版本投影/index Outbox 与恢复门禁保证失效后可收敛。旧搜索重建不再阻断商品源事务，投影未收到可靠屏障或专用失败队列积压时搜索返回 503。
- 新鲜测试：JDK 17.0.12、Docker Engine 29.7.2 下 `mvnw.cmd clean test` 与 `mvnw.cmd clean verify` 均六模块 BUILD SUCCESS、退出 0；最终 142 个 XML 汇总 Surefire 228、Failsafe/Testcontainers 357，共 585 项，全部 0 failures、0 errors、0 skipped。真实五应用商品旅程、故障恢复、在线重建与原实验七交易回归均运行。
- 镜像启动：官方 JDK17 JRE 五应用和 SmartCN 镜像的隔离 Compose smoke 验证五应用 health、十个探针 UP，Eureka 4/4、Gateway JWKS HTTP 200，商品主队列和人工失败队列为 0；隔离容器、卷、网络及仓库外临时密钥已回收。
- Git：实验验收提交 `c72c7f1` 在 `learning/spring-cloud-split`，活动树仅 `.gitignore` 与 `labs/08-spring-cloud-split/**`；敏感信息与暂存差异检查通过，远端从 `ea5cf49` 快进至 `c72c7f1`。main 只更新路线、计划和验收复盘，不合并实验代码。
- 镜像路由补证：第二组仓库外临时 RSA 密钥签发短时本地 Token，经隔离 Compose Gateway 的两条精确商品搜索 GET 均 HTTP 200、空库结果；临时 Token、密钥、容器和卷已再次回收。
- 补证提交：仅更新实验验收记录与实验学习日志的 `177cd25` 从 `c72c7f1` 快进推送到远端实验分支；8.2 的最终公开入口包含该镜像路由结果。

### 2026-09-16：开始实验九全栈智能校园客服

- 本阶段范围：公开交易规则问答、本人订单/普通售后/质保状态只读查询，以及注册登录、资源选择、规则来源与状态分区展示的客服前端；不重建交易平台页面或执行资金仲裁。
- 设计与计划：已确认 [实验九设计](../docs/superpowers/specs/2026-09-16-ai-campus-support-design.md)和[实施计划](../docs/superpowers/plans/2026-09-16-ai-campus-support-fullstack.md)。AI 服务采用 Spring AI 1.1.8 与独立公开规则索引，交易服务按自身 JWT 用户身份授权，外部模型不接收私人原始问题或订单/案件 ID。
- Git：从实验八验收提交 `177cd25` 建立本地独立工作树 `learning/ai-campus-support`；实验八及其他已验收分支保持不变。实验九尚处于实现阶段，远端入口和验收计数待完整验证后填写。
- 基线证据：在迁移代码前，JDK 17 下运行实验八基线 `mvnw.cmd test` 退出 0，七个 Reactor 项目 BUILD SUCCESS。此命令只验证单元测试，不作为实验九全栈或外部协作验收。
- 平台调整：确认 Boot 3.5/Cloud 2025.0 已结束开源维护后，实验九改为在独立分支整体迁移至 Spring Boot 4.1.1、Spring Cloud 2025.1.3 和 Spring AI 2.0.1；先通过原实验八的完整交易回归，再开发 AI 能力。实验一至八的分支与验收版本保持不变。

### 2026-09-18：实验九完整验收

- AI 服务只解释已审阅公开规则，并将私人问题归一化为固定安全模板；本人订单、争议、质保由 legacy 做对象级授权后只返回最小状态。模型无交易写工具，前端 Token 只存内存，无权与不存在保持同构 404。
- 首轮 `clean verify` 发现跨模块 Cloud 夹具漏注入游标签名密钥，修复后又由真实旅程发现路由集合断言漏掉 `ai-support-answer`。两处均以既有集成测试先红后绿关闭，未放松生产安全约束。
- Maven Wrapper JDK 17.0.12、Node 22.17.1、Docker Engine 29.7.2；最终 Reactor 8 个项目 BUILD SUCCESS，157 份 fresh XML 为 Surefire 255、Failsafe/Testcontainers 384，共 639 项且全部 0 failures/errors/skipped。
- 前端 Vitest 13 项、Vite 生产构建和 Playwright 桌面/移动 10 项通过；隔离 Compose 覆盖注册邮件、本人问答、401 清会话、交易资源与模型故障恢复，并已清理容器、网络、卷和临时密钥。
- Git：实验分支提交 `af02e23` 保存验收回归修复，`9edcaed` 保存实验九验收文档；分支保持本地、尚未推送，`main` 只记录状态和学习结论。
