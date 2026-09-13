# 排障手册

本手册保留实验七交易基线的排障路径，并记录实验八身份拆分的边界。先确认失败发生在哪个事实边界，再做恢复；不要通过跳过测试、手工篡改状态或放宽 ACL 获得表面成功。

## 实验八：本地 Compose 启动

Compose 只包含实验八四应用及其最小运行依赖：MySQL、Redis、RabbitMQ、安装 SmartCN 的 Elasticsearch 和 MinIO。Toxiproxy 由故障测试的 Testcontainers 独立创建，不需要也不应该作为本地常驻服务启动。先确认 JDK 17、Docker Engine 和各模块的 `target` JAR 已准备好，再执行：

```powershell
docker compose --env-file .env config --quiet
docker compose --env-file .env build
docker compose --env-file .env up -d
docker compose --env-file .env ps
```

`config --quiet` 只验证 Compose 语法，不把展开后的口令打印到终端。查看某个服务的启动错误时使用 `docker compose --env-file .env logs <service>`，不要把 `.env`、JWT 私钥或 Token 粘贴到排障记录。若应用镜像提示 JAR 不存在，先在实验目录执行 `.\mvnw.cmd -DskipTests package`，再重新构建对应镜像；该打包步骤不是测试验收。

Compose 只表达启动依赖顺序，应用自身仍可能需要几秒注册 Eureka。应先看 `docker compose ps` 和各应用日志，确认 `discovery-server` 已监听 8761，再确认 identity/legacy 注册，最后从 Gateway 18080 发起请求。所有服务均为 `restart: "no"`；反复重启只会掩盖首次失败，应该先保存错误边界再处理。

## 实验八：双库初始化失败

MySQL 的 `docker/mysql/01-split-databases.sh` 只在 `mysql-data` 空卷第一次初始化时执行。若日志显示数据库或账号不存在、Flyway 迁移未执行，先检查 MySQL 是否健康以及 `.env` 中五个数据库口令是否已经替换；已有卷不会自动重新运行脚本。确认只需丢弃本地实验数据后，才可执行 `docker compose --env-file .env down -v`，再重新 `up -d`。

identity 使用 `identity_db`/`identity_app`，legacy 使用 `market_db`/`market_app`；两者的 Flyway 分别使用 `identity_migrator`/`market_migrator`。运行账号只有本库 DML，迁移账号才有本库 DDL，不要通过 root、全局授权或跨库读取来绕过 `Access denied`。跨库权限被拒绝是设计边界，需修正服务连接或迁移配置。

## 实验八：密钥文件、JWKS 或 Gateway 路由

identity 启动时会读取 `.env` 指向的仓库外 PKCS#8 私钥和 X.509 公钥，并校验二者匹配。出现 `JWT ... resource is not readable` 或密钥不匹配时，检查宿主机绝对路径、Compose bind mount 和文件格式；不要把密钥内容写入仓库或日志。

固定 JWKS 路径是 `/api/auth/.well-known/jwks.json`。容器间使用 `http://identity-service:8080/api/auth/.well-known/jwks.json`，客户端诊断使用 Gateway 的 `http://localhost:18080/api/auth/.well-known/jwks.json`。Gateway 的 `/api/auth/**` 路由到 `identity-service`，其他 `/api/**` 路由到 `legacy-market-service`；不要把 `lb://` 改成固定下游端口来规避 Eureka 注册问题。核对 Gateway、identity 和 legacy 的 issuer、audience、`kid` 必须一致；不要输出 Token。

## 实验八：基础依赖健康检查

应用启动失败时先按服务边界查看：MySQL 3313、Redis 6383、RabbitMQ 5673/15673、Elasticsearch 9203、MinIO 9010/9011。SmartCN 镜像由 `docker/elasticsearch/Dockerfile` 构建，Elasticsearch 本地 Compose 使用无认证模式，legacy 的 URI 是 `http://elasticsearch:9200`；若插件或健康检查失败，先重建该镜像并检查日志。RabbitMQ 的队列声明和 MinIO bucket 会在应用首次使用时触发，凭据或 endpoint 错误应修正 `.env`/服务名，不要换成本机历史服务。

## 实验八：Eureka 与路由

路由目标不可用时先检查 Eureka 注册表是否包含 `IDENTITY-SERVICE`、`LEGACY-MARKET-SERVICE` 和 `API-GATEWAY`，实例端口是否为当前应用实际端口，以及每个客户端 `EUREKA_DEFAULT_ZONE` 是否指向同一个注册中心。注册中心地址是配置，业务实例地址由发现获得；不要把失败的 `lb://` 路由改成固定业务端口来让旅程通过。

Gateway 只配置身份与业务两条显式路由。依赖故障应返回脱敏中文 UTF-8 503，不传出主机、端口、服务 ID 或下游堆栈。Eureka 停机后短期缓存可用与冷启动无注册信息是不同情形，应分别验证。

## 实验八：JWKS 与身份失败

身份服务实际 JWKS 端点是 `/api/auth/.well-known/jwks.json`。分别检查 Gateway 和 legacy 的 `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`，并核对 issuer、固定 audience、当前 `kid` 与 RS256 公钥。不要输出 Token、私钥或密码做排障记录。

缺失或无效 Token 返回 401，普通用户访问管理员 API 返回 403。身份停机不等于已有 Token 立即失效：已缓存公钥可以验证未过期 Token，但登录仍不可用；冷启动不能跳过验签。未知 `kid` 不做递归认证重试。

## 实验八：双库权限与迁移

身份和交易分别配置本库 DataSource 与 Flyway 迁移账号。运行账号只有必要 DML 权限，迁移账号仅拥有本库 DDL 权限。跨库读取被拒绝是预期边界；不要授予全局权限或恢复跨库外键来解决启动错误。实验只支持全新环境，不能复用实验七单库 Flyway 历史冒充迁移完成。

## 实验八：截止并发与测试发现

拆分后测试无法发现启动配置时显式指定 `LegacyMarketApplication`，保持原集成测试仍由 Failsafe 执行。对截止扫描与筹资的竞争，检查是否出现“候选二级索引 → 主键”和“主键 → 二级索引”的反向锁顺序。候选扫描不加锁，逐 ID 事务按主键锁定，再用数据库时间和当前状态条件更新。

并发回归测试必须关闭上下文的后台截止调度，手动启动被测调度器，并观察目标义务的实际锁等待；不能把 `CannotAcquireLockException` 当成允许的业务终态。2026-09-14 定点 `WarrantyDeadlineRaceIT` 已运行 3 项且无失败、错误或跳过，完整实验验收仍需全套报告。

## 1. Docker 不可用或宿主机内存不足

**症状：** Testcontainers 无法连接 Docker、容器启动超时、进程被异常终止，或多个重型容器同时启动后系统持续换页。

**检查与恢复：**

```powershell
docker info
docker ps -a
```

确认 Docker Desktop 的 Linux Engine 可用，关闭无关容器和并行 Maven/IDE 构建。可用内存低于 1 GiB 时停止测试，释放资源后串行重试。`CampusMarketJourneyIT`、`RecoveryDrillIT` 不要并行执行；每个阶段退出并确认容器回收后再运行下一段。

**边界：** Docker 不可用、外部测试 skipped、用本机历史服务替代 Testcontainers 都不算通过。

## 2. SmartCN analyzer 不存在或搜索结果异常

**症状：** Elasticsearch 创建索引时报 analyzer 不存在，中文被拆成错误 token，或 MySQL 在售集合与搜索结果不一致。

**检查与恢复：** SmartCN 镜像由 `docker/elasticsearch/Dockerfile` 安装 `analysis-smartcn`。重建镜像，确认插件已加载，清理旧测试索引后重试。检查 `search_outbox` 积压、别名目标、聚合版本和 tombstone；普通失败发生在别名切换前时可重新运行重建，切换已尝试且目标不确定时必须保留 pause/intent 交给恢复器判断。

**边界：** 只在配置中写 `smartcn` 不算验证；必须由真实 `_analyze`/搜索集成测试证明中文 token、`ON_SALE` 过滤和集合收敛。不能盲目重新绑定分裂别名。

## 3. RabbitMQ confirm、Outbox 或人工失败异常

**症状：** Outbox 长期停留在 `PUBLISHING`、publisher NACK/不可路由/超时，或三次失败后未形成可追踪的人工失败事实。

**检查与恢复：** 检查 `integration_outbox.status/lease_until/claim_token/attempt_count`、Rabbit exchange/queue/binding 和人工失败表。恢复 RabbitMQ 后运行 `OutboxDispatcher.dispatchOnce`；过期租约由新 owner 接管，旧 owner 的迟到 ACK/NACK 只能更新 0 行。只有 publisher confirm 成功才允许标记 `PUBLISHED`。

**边界：** 不要手工把 Outbox 改成 `PUBLISHED`，也不要无限重试。无法自动恢复的协议或业务错误进入受控人工路径。

## 4. 单测通过但完整 `verify` 的消息断言成组错位

**症状：** `ReliableMessagingIT` 单独通过，但完整套件出现领取数量、`attempt_count`、人工失败或终态断言一起偏移。

**检查与恢复：**

- 检查 `integration_outbox` 是否残留支付、退款或质保事件；无类型过滤的 `claimBatch` 可能先领取它们。
- 检查 `consumed_event`、`manual_failure`、主事件队列和人工队列是否在每例前同步清理。
- 检查旧 Spring 上下文中的 Rabbit listener、搜索或截止调度器是否仍在抢占 Outbox、队列和租约。
- 定点运行 `.\mvnw.cmd '-Dit.test=PaymentFlowIT,ReliableMessagingIT' verify`；组合失败而单类成功通常说明跨类污染。

共享测试基类应关闭默认 Rabbit listener 与搜索调度器，并用较大的 `initial-delay-ms` 延后需要保留 Bean 的截止/对账任务。不要统一覆盖为 `enabled=false`，否则测试级动态属性可能让需直接注入调度器的上下文无法创建。

## 5. Redis 验证码失败或出现绕过风险

**症状：** 验证码接口返回 503、验证码无法消费、限流结果异常，或 Redis 故障后业务仍继续注册。

**检查与恢复：** 检查 Redis URL、Lua 脚本、验证码 HMAC 和设备 Cookie 签名。10 分钟发送/失败窗口分别受邮箱、IP 和设备上限保护。恢复 Redis 后重新发起验证码流程。

**边界：** Redis 不可用必须失败关闭，不能绕过身份验证；验证码只允许本地模拟发送器保存最近值，禁止写日志或 Git。

## 6. MinIO 上传、读取或持久清理失败

**症状：** 上传/读取返回 503、绑定失败后残留对象、清理任务反复重试，或 MP4 只看 multipart 元数据就被错误接受。

**检查与恢复：** 确认 MinIO endpoint、bucket、凭据和 Toxiproxy 路由。检查 `object_upload_session`、`storage_cleanup_task.status/lease_until/claim_token`；恢复后运行 `StorageCleanupScheduler.runOnce(100)`。对象不存在按幂等成功处理，旧 claim token 的迟到完成应更新 0 行。

**边界：** 数据库事务中禁止执行 MinIO IO；Object Key 不得写日志。图片/PDF/MP4 上限分别按实际读取字节 10/20/100 MiB 验证，不能信任文件名、扩展名或客户端类型。

## 7. 私有证据 ACL 或 404 行为不一致

**症状：** 非案件参与者能读证据、管理员角色自动读到未分配案件，或响应通过 403/错误文本泄漏资源存在性。

**检查与恢复：** 核对案件买方、卖方、已分配管理员与 evidence 绑定；每次物理读取前重新执行逻辑 ACL。对已认证调用者，不存在、已删除和无权统一返回同构 404；存储不可用返回 503。

**边界：** 管理员不自动绕过 ACL。不能用预签名 URL、Object Key 或数据库直读规避授权。

## 8. 支付/退款回调验签失败或状态为 `UNKNOWN`

**症状：** webhook 被拒绝、重复回调产生重复效果，或支付/退款停留在 `UNKNOWN`。

**检查与恢复：** 回调签名覆盖原始 UTF-8 body，并校验时间戳、一次性 nonce、受控字段、provider event ID 和金额。重放 nonce、过期时间、未知字段、非法状态或金额不匹配必须拒绝。`UNKNOWN` 时保留原 provider reference 与幂等键，运行 `PaymentReconciliationScheduler.runOnce` 查询原请求结果。

**边界：** `UNKNOWN` 不能重新创建支付/退款请求；真实支付未接入，模拟适配器只证明 `PaymentGateway` 契约。

## 9. 退款额度预占没有收敛

**症状：** 并发退款总额可能超过实付金额，退款回调成功后占额未释放，或退货退款完成但隔离库存未更新。

**检查与恢复：** 核对成功支付、`successful_refund_fen`、`reserved_refund_fen`、退款唯一业务键和回调事件。所有退款必须先在数据库事务中占额，再调用提供方；成功/失败/未知结果按同一 reference 收敛。回调后运行退回收敛器，确认批准数量只进入一次隔离库存。

**边界：** `成功退款 + 已占额 <= 实付金额` 是硬约束；不能通过先外部退款、后补数据库规避并发控制。

## 10. 截止时间边界和用户命令发生竞态

**症状：** 支付、交付、确认、争议、结算或质保命令在截止时刻出现双终态，测试偶发一条路径重复成功。

**检查与恢复：** 所有窗口使用 MySQL `CURRENT_TIMESTAMP(6)` 和左闭右开语义：支付 15 分钟、交付 72 小时、确认 48 小时、验收 72 小时、试用 7 天。卖家质保为订单快照中的 30/90/180/365 天。核对状态条件更新、订单行锁、截止 claim token 和状态历史；不要以 JVM `Clock` 或 Redis 锁替代数据库裁决。

**边界：** `databaseNow < deadline` 才允许提交，到达截止时刻即过期；并发双方只能有一条合法路径获胜。

## 11. 退回证明不足或案件进入 `ESCALATED`

**症状：** 买家上传了图片、视频或说明，但系统不退款；卖家 72 小时未确认；管理员硬期限到期后案件进入 `ESCALATED`。

**检查与恢复：** 普通证据只证明“提交了材料”，不等于可信退回。退款必须取得卖家确认、未来已验签物流签收回调或管理员明确确认。补足证明并运行 `ReturnResolutionScheduler`；证据冲突、证明不足或支付失败时保留冻结资金和高优先级人工队列。

**边界：** 当前为校园当面退回，不保存或依赖物流单号。硬期限不能自动判买方或卖方获胜。

## 12. 卖家质保筹资失败或限制未解除

**症状：** 质保裁定后卖家无法发布/提现、筹资幂等冲突、未来结算重复抵扣，或足额后限制仍存在。

**检查与恢复：** 核对 `seller_obligation` 的应履行额、已筹额、72 小时期限、唯一抵扣键、限制状态和版本。卖家使用 `/api/seller/obligations/{id}/fund` 与 `Idempotency-Key` 主动筹资；未来结算只按唯一业务键抵扣一次，足额后幂等解除限制。

**边界：** 平台不垫付结算后质保资金，订单也不能退回 `PENDING`；逾期未足额时限制必须保留。

## 13. 全量验证和最终证据

在实验目录、JDK 17 下执行：

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
git diff --check
```

常用定点命令：

```powershell
.\mvnw.cmd '-Dit.test=PaymentFlowIT,ReliableMessagingIT' verify
.\mvnw.cmd -Dit.test=CampusMarketJourneyIT verify
.\mvnw.cmd -Dit.test=RecoveryDrillIT verify
```

`CampusMarketJourneyIT` 依次运行教材旅程（MySQL、Redis、SmartCN Elasticsearch）和质保旅程（MySQL、Redis、MinIO）。`RecoveryDrillIT` 依次运行 RabbitMQ、Elasticsearch、MinIO 三个核心阶段；`RecoveryInvariantStagesIT` 补充每轮 ACL 与搜索集合阶段。每轮都必须看到故障证据，并在恢复后证明库存非负、退款未超额、单次结算、无过期未决租约、证据 ACL 未放宽、MySQL 与搜索在售集合一致。

截至 2026-09-11，最终基线为 Surefire 137 项、Failsafe/Testcontainers 251 项，0 failures、0 errors、0 skipped。任何 skipped、Docker 失联、SmartCN 未加载、模拟支付契约未执行、竞态未运行或恢复未收敛，都不能记为完整验收。
