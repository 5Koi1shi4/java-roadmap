# Java Backend Roadmap

面向 2027 届春招的 Java 后端项目化学习工作区。当前基础为能够使用 Spring Boot 完成基础 CRUD，每天计划投入 3–5 小时。

## 技术基线

- JDK 17
- Maven Wrapper 3.9.x（各项目生成后固定版本）
- Spring Boot 3.x
- MySQL 8、Redis 7、RabbitMQ、Elasticsearch 8、MinIO
- JUnit 5、Mockito、Testcontainers
- Docker Desktop 与 Docker Compose

## 进度

| 阶段 | 项目 | 状态 |
|---|---|---|
| 1 | JWT 与 RBAC 权限服务 | 已验收 |
| 2 | Redis 缓存与一致性 | 已验收 |
| 3 | 秒杀、库存与接口幂等 | 未开始 |
| 4 | 订单状态机与可靠消息 | 未开始 |
| 5 | Elasticsearch 搜索 | 未开始 |
| 6 | 安全文件服务与 MinIO | 未开始 |
| 7 | 校园交易与服务平台 | 未开始 |
| 8 | Spring Cloud 渐进拆分 | 未开始 |
| 9 | Java AI 智能校园客服 | 未开始 |

状态只使用：`未开始`、`进行中`、`已验收`。

## 当前周

- 周次：第 1 周
- 已完成任务：JWT 与 RBAC 权限服务、Redis 缓存与一致性。
- 验收证据：实验二在 JDK 17 与 Docker Desktop 下执行 `labs/02-redis-cache` 的 `mvnw.cmd verify`，24 个单元测试和 22 个 Testcontainers 集成测试均为 0 failures、0 errors、0 skipped；同时确认测试关闭临时 Redis 后不存在客户端重连告警。
- 下一任务：秒杀、库存与接口幂等。

## 每周闭环

1. 先写失败测试。
2. 实现最小功能并使测试通过。
3. 补充集成测试、README 与运行命令。
4. 记录问题、技术选择和面试追问。
5. 通过验收后再进入下一项目。
