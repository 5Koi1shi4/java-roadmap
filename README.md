# Java Backend Roadmap

面向 2027 届春招的 Java 后端项目化学习仓库。这里的 `main` 分支是文档中心，维护稳定的学习总览、已验收记录和复盘入口；每个实验在独立分支中保持可复跑的验证命令，方便阅读者按阶段进入。

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
| 5 | Elasticsearch 搜索 | 未开始 | — |
| 6 | 安全文件服务与 MinIO | 未开始 | — |
| 7 | 校园交易与服务平台 | 未开始 | — |
| 8 | Spring Cloud 渐进拆分 | 未开始 | — |
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
- 验证：在 JDK 17 与 Docker Desktop 下执行 `mvnw.cmd verify`；已验收 24 个单元测试和 22 个 Testcontainers 集成测试。

### 实验三：秒杀、库存与接口幂等

覆盖 MySQL 条件扣库存、一人一单、事务回滚、接口幂等、UTF-8 响应与多实例数据库协调。

- 入口：[learning/seckill-inventory](https://github.com/5Koi1shi4/java-roadmap/tree/learning/seckill-inventory/labs/03-seckill-inventory)
- 验证：在 JDK 17 与 Docker Desktop 下执行 `mvnw.cmd verify`；已验收 32 个 Surefire 测试和 13 个 Testcontainers 集成测试。

### 实验四：订单状态机与可靠消息

覆盖订单状态条件更新、事务内 Outbox、RabbitMQ publisher confirm、租约接管、TTL 分桶、DLX、消费幂等、异常分类和有限重试。

- 入口：[feat/order-mq-reliable-messaging](https://github.com/5Koi1shi4/java-roadmap/tree/feat/order-mq-reliable-messaging/labs/04-order-mq)
- 验证：在 JDK 17 与 Docker Desktop 下执行 `mvnw.cmd verify`；已验收 59 个 Surefire 单元测试和 26 个 Failsafe/Testcontainers 集成测试，0 failures、0 errors、0 skipped。

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
