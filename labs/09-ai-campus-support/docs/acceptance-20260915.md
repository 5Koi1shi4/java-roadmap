# 8.1 身份拆分验收记录（2026-09-15）

状态：已验收。此记录只证明全新环境的 8.1 身份拆分，不覆盖生产不停机迁移或暂停中的扩展。

## 新鲜验证

在 JDK 17 和 Docker Desktop 29.7.2 下，先确认 `docker info` 成功，再串行执行 `mvnw.cmd clean test` 和 `mvnw.cmd clean verify`。两条命令均 BUILD SUCCESS；后者六个 Reactor 模块全部成功，用 `clean` 清除旧报告后产生 121 份 `TEST-*.xml`。

| 模块 | Surefire | Failsafe/Testcontainers | 失败/错误/跳过 |
|---|---:|---:|---:|
| platform-test-support | 6 | 0 | 0/0/0 |
| discovery-server | 4 | 0 | 0/0/0 |
| identity-service | 24 | 17 | 0/0/0 |
| legacy-market-service | 137 | 267 | 0/0/0 |
| api-gateway | 25 | 9 | 0/0/0 |
| 合计 | 196 | 293 | 0/0/0 |

真实 `CloudJourneyIT` 只经 Gateway 完成注册与交易 HTTP 旅程；`CloudFailureRecoveryIT` 4 项覆盖身份、交易目标及 Eureka 停机恢复。Gateway/legacy 的冷启动 readiness、JWKS 刷新、未知 `kid`、数据库账号跨库拒绝和实验七原业务回归也在完整 `verify` 中执行。原 16 个必要业务集成套件由测试清单守卫保留，没有新增 `@Disabled`、assumption 或外部测试跳过。

## 运行镜像与文档核对

从 Dockerfile 指定的官方 `eclipse-temurin:17-jre` 构建四应用运行镜像和 SmartCN 镜像；隔离 Compose 项目九个服务启动后，README 的两分钟脚本验证四应用 `/actuator/health`、liveness、readiness 全部 UP，并确认 Eureka 中 `IDENTITY-SERVICE`、`LEGACY-MARKET-SERVICE`、`API-GATEWAY` 3/3 注册。Gateway JWKS HTTP 200、RSA 公钥 1 把。隔离容器、网络、临时卷及仓库外随机口令/密钥已经删除。

| 人工验收项 | 对应章节或文件 |
|---|---|
| JDK 17、Spring Boot 3.5.16、Spring Cloud 2025.0.3 | [README 开头与运行条件](../README.md) |
| `docker info`、`mvnw.cmd test`、`mvnw.cmd verify` | [README 验收入口](../README.md)、[排障全量验证](../TROUBLESHOOTING.md) |
| `identity_db`、`market_db`、RS256、JWKS、Eureka | [README 边界与 Compose](../README.md)、[架构](architecture.md) |
| 仅支持全新环境，不支持生产不停机迁移 | [迁移边界](migration-boundary.md) |
| 启动顺序、端口、占位符和密钥约束 | [Compose](../compose.yaml)、[.env.example](../.env.example)、[README 启动](../README.md) |
| 故障恢复、学习与面试复盘 | [排障](../TROUBLESHOOTING.md)、[学习日志](../notes/learning-log.md)、[面试题库](../interview/question-bank.md) |

`.env.example` 仅含 `<replace-me>` 占位符，不含 PEM；Compose 服务均为 `restart: "no"`。`docker compose --env-file .env.example config --quiet`、`git diff --check` 与活动树路径检查均通过。源码扫描没有私钥 PEM 或旧共享 JWT 密钥；测试中保留的模拟 Bearer 串与测试数据库口令不是运行凭据，运行产物扫描无私钥或 Bearer 命中。

JWKS 探针与 JWT decoder 的密钥缓存相互独立。最近成功探测的 30 秒 readiness 缓冲不能保证一个此前未验签 Token 的首次请求成功；Eureka 缓冲为 45 秒，必需实例为空立即 DOWN。冷启动受保护请求及热缓存身份停机场景分别由真实测试覆盖。
