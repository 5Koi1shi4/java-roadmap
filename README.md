# Java Backend Roadmap

面向 2027 届春招（如果进度够快即秋招）的 Java 后端项目化学习仓库。这里的 `main` 分支是文档中心，维护稳定的学习总览、已验收记录和复盘入口；每个实验在独立分支中保持可复跑的验证命令，方便阅读者按阶段进入。

## 从这里开始

推荐按以下顺序阅读：

1. 本页：了解路线、当前进度和目录职责。
2. 已验收实验的 README：理解目标、运行方式和验收范围。
3. 实验的排障复盘：了解真实环境问题及其边界。
4. [学习日志](notes/learning-log.md)、[面试题库](interview/question-bank.md) 与 [参考仓库阅读记录](references/README.md)：沉淀学习过程与延伸问题。

## 学习路线

| 阶段 | 项目 | 状态 | 独立分支入口 |
|---|---|---|---|
| 1 | JWT 与 RBAC 权限服务 | 已验收 | [learning/security-rbac](https://github.com/5Koi1shi4/java-roadmap/tree/learning/security-rbac/labs/01-security-rbac) |
| 2 | Redis 缓存与一致性 | 已验收 | [learning/redis-cache](https://github.com/5Koi1shi4/java-roadmap/tree/learning/redis-cache/labs/02-redis-cache) |
| 3 | 秒杀、库存与接口幂等 | 已验收 | [learning/seckill-inventory](https://github.com/5Koi1shi4/java-roadmap/tree/learning/seckill-inventory/labs/03-seckill-inventory) |
| 4 | 订单状态机与可靠消息 | 已验收 | [feat/order-mq-reliable-messaging](https://github.com/5Koi1shi4/java-roadmap/tree/feat/order-mq-reliable-messaging/labs/04-order-mq) |
| 5 | Elasticsearch 搜索 | 已验收 | [learning/elasticsearch-search/labs/05-elasticsearch-search](https://github.com/5Koi1shi4/java-roadmap/tree/learning/elasticsearch-search/labs/05-elasticsearch-search) |
| 6 | 安全文件服务与 MinIO | 已验收 | [learning/secure-file-service/labs/06-file-service](https://github.com/5Koi1shi4/java-roadmap/tree/learning/secure-file-service/labs/06-file-service) |
| 7 | 校园交易与服务平台 | 已验收 | [learning/campus-market/labs/07-campus-market](https://github.com/5Koi1shi4/java-roadmap/tree/learning/campus-market/labs/07-campus-market) |
| 8 | Spring Cloud 渐进拆分 | 进行中 | 本地分支 `learning/spring-cloud-split`（8.1 身份拆分，待验收） |
| 9 | Java AI 智能校园客服 | 未开始 | — |

状态仅使用：`未开始`、`进行中`、`已验收`。

## 已验收实验

### 实验一：JWT 与 RBAC 权限服务

覆盖 JWT 认证、RBAC 授权、可撤销 Refresh Token 轮换和 MySQL 端到端验证。

- 入口：[learning/security-rbac](https://github.com/5Koi1shi4/java-roadmap/tree/learning/security-rbac/labs/01-security-rbac)
- 验证：在实验目录使用 JDK 17 执行 `mvnw.cmd verify`。

### 实验二：Redis 缓存与一致性

覆盖 Cache Aside、正/负缓存、事务提交后失效、热点 Key 重建锁、Redisson、Actuator、k6、Prometheus 与 Grafana。

- 入口：[learning/redis-cache](https://github.com/5Koi1shi4/java-roadmap/tree/learning/redis-cache/labs/02-redis-cache)
- 验证：在 JDK 17 与 Docker Desktop 下执行 `mvnw.cmd verify`；已验收 25 个单元测试和 23 个 Testcontainers 集成测试，0 failures、0 errors、0 skipped。

### 实验三：秒杀、库存与接口幂等

覆盖 MySQL 条件扣库存、一人一单、事务回滚、接口幂等、UTF-8 响应与多实例数据库协调。

- 入口：[learning/seckill-inventory](https://github.com/5Koi1shi4/java-roadmap/tree/learning/seckill-inventory/labs/03-seckill-inventory)
- 验证：在 JDK 17 与 Docker Desktop 下执行 `mvnw.cmd verify`；已验收 32 个 Surefire 测试和 13 个 Testcontainers 集成测试。

### 实验四：订单状态机与可靠消息

覆盖订单状态条件更新、事务内 Outbox、RabbitMQ publisher confirm、租约接管、TTL 分桶、DLX、消费幂等、异常分类和有限重试。

- 入口：[feat/order-mq-reliable-messaging](https://github.com/5Koi1shi4/java-roadmap/tree/feat/order-mq-reliable-messaging/labs/04-order-mq)
- 验证：在 JDK 17 与 Docker Desktop 下执行 `mvnw.cmd verify`；已验收 59 个 Surefire 单元测试和 27 个 Failsafe/Testcontainers 集成测试，0 failures、0 errors、0 skipped。

### 实验五：Elasticsearch 商品搜索

覆盖 SmartCN 中文分词、`ON_SALE` 过滤与相关性搜索、MySQL 事务 Outbox、Elasticsearch `external_gte` 版本写入、逻辑删除 tombstone，以及带一致性快照、高水位补放、写入门禁和原子别名切换的在线重建。MySQL 是事实源，搜索索引可从商品表与 Outbox 重新构建；dispatcher 允许至少一次投递，但通过租约和 claim token 防止旧实例覆盖新租约。

- 入口：[learning/elasticsearch-search/labs/05-elasticsearch-search](https://github.com/5Koi1shi4/java-roadmap/tree/learning/elasticsearch-search/labs/05-elasticsearch-search)
- 验证：在 JDK 17、MySQL 8.4、带 SmartCN 的 Elasticsearch 8.18.8 和 Toxiproxy 2.12.0 下执行 `mvnw.cmd verify`；已验收 44 个 Surefire 测试和 52 个 Failsafe/Testcontainers 集成测试，0 failures、0 errors、0 skipped（验收提交 `758ab9fb52c3f8245b9f2aea94c30c3d33c43549`）。

### 实验六：安全文件服务与 MinIO

覆盖流式类型与 20 MiB 实际读取上限校验、随机逻辑文件 ID、全局物理 Blob 去重、私有默认 ACL、授权与撤权、统一 404、MySQL 时间租约与 token/generation fencing，以及本地/MinIO 对象存储的幂等补偿和清理。上传通过短数据库事务 A/B/C 与事务外对象存储操作协作；物理 Blob 访问必须先经过逻辑授权，管理员不自动绕过权限。

- 入口：[learning/secure-file-service/labs/06-file-service](https://github.com/5Koi1shi4/java-roadmap/tree/learning/secure-file-service/labs/06-file-service)
- 验证：实验代码只保存在独立分支 `learning/secure-file-service`，不合并到 `main`。在 JDK 17、MySQL 8.4、MinIO 和 Toxiproxy 2.12.0 下执行 fresh `mvnw.cmd clean verify`；已验收 96 个 Surefire 测试和 80 个 Failsafe/Testcontainers 集成测试，0 failures、0 errors、0 skipped（实验最终提交 `18603f1`）。

### 实验七：校园二手交易平台

覆盖校园邮箱、JWT、商品与批量库存、幂等一口价订单、模拟支付与对账、当面交付、三天验收/七天试用、部分退货退款、隔离库存、评价和结算后卖家质保。MySQL 保存交易事实，Redis 只负责验证码和限流；RabbitMQ 通过 Outbox/Inbox、publisher confirm、租约和 fencing 实现可恢复消息；SmartCN Elasticsearch 是可重建读模型；MinIO 保存受案件 ACL 保护的私有媒体和证据。三轮故障演练分别覆盖 RabbitMQ、Elasticsearch、MinIO，并复核库存、退款额度、结算、证据 ACL 和搜索集合不变量。

- 入口：[learning/campus-market/labs/07-campus-market](https://github.com/5Koi1shi4/java-roadmap/tree/learning/campus-market/labs/07-campus-market)
- 验证：实验代码只保存在独立分支 `learning/campus-market`，不合并到 `main`。JDK 17 下 `mvnw.cmd test` 为 137 项；MySQL 8.4、Redis 7.4、RabbitMQ 3.13、SmartCN Elasticsearch 8.18.8、MinIO 与 Toxiproxy 环境中的完整 `mvnw.cmd verify` 为 251 个 Failsafe/Testcontainers 集成测试；均为 0 failures、0 errors、0 skipped（验收状态提交 `4148f1e`）。
- 状态与边界：实验七主体交易闭环已验收。正式 CAS、真实支付和真实物流尚未接入；原规划的 `7.1` 聊天、`7.2` 竞价、`7.3` 跑腿/代取及真实支付适配器涉及法律与合规问题，暂不开展，不作为本实验验收前置条件。已有扩展设计和合规检查材料仅作为决策记录保留。

## 目录导航

| 目录 | 用途 |
|---|---|
| `labs/` | 实验代码保留在本地工作区，并通过独立分支入口访问。 |
| [`notes/`](notes/learning-log.md) | 每日目标、测试证据、问题复盘与技术取舍。 |
| [`interview/`](interview/question-bank.md) | 从实验提炼的面试追问与知识点。 |
| [`references/`](references/README.md) | 外部参考仓库及阅读记录。 |
| [`compose/`](compose/README.md) | 共享容器与 Compose 使用说明。 |

## 技术基线

- JDK 17、Maven Wrapper 3.9.x、Spring Boot 3.x
- MySQL 8、Redis 7、RabbitMQ、Elasticsearch 8、MinIO
- JUnit 5、Mockito、Testcontainers
- Docker Desktop 与 Docker Compose

## 学习闭环

1. 先写能失败的测试，明确行为边界。
2. 实现最小功能并让测试通过。
3. 用 Testcontainers 或真实 HTTP 流程验证外部协作。
4. 补充 README、排障记录、学习日志和面试追问。
5. 通过验收后更新本页状态，再进入下一阶段。

## 协作与安全约定

- Java 与 Maven 统一使用 JDK 17。
- 本地密码、Token 和 `.env` 不提交；`.env.example` 仅保留占位符。
- `main` 只存放文档；实验分支不会合并进 `main`，实验验收更新通过独立的文档提交完成。
- 每次改动只暂存本次相关文件；实验分支和工作树保持独立，不因调整总览而改变。
