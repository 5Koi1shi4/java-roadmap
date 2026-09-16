# Spring AI 2.x 平台迁移说明

实验九直接采用 Spring AI 2.0.1，不保留 Spring AI 1.x 兼容层。为满足 Spring AI 2.x 的平台要求，当前实验目录统一使用以下版本：

| 组件 | 版本 | 管理方式 |
|---|---:|---|
| Java | 17 | Maven `release=17` |
| Spring Boot | 4.1.1 | 父 POM |
| Spring Framework | 7.0.9 | Spring Boot 依赖管理 |
| Spring Cloud | 2025.1.3 | Spring Cloud BOM |
| Spring AI | 2.0.1 | Spring AI BOM |
| Elasticsearch Java Client | 9.4.5 | Spring Boot 依赖管理 |

## 迁移范围

升级只发生在 `labs/09-ai-campus-support` 的隔离实验树中，实验一至实验八不回写、不重构。现有身份、交易、商品读模型与网关边界保持不变，后续新增的 AI 服务通过只读 HTTP API 获取本人订单和售后状态，不直接访问交易数据库。

Spring Boot 4 将若干测试、健康检查、自动配置与 WebFlux 类型移动到新的包。实验九同步迁移了这些类型，并把测试替身从 `@MockBean` 改为 `@MockitoBean`。Elasticsearch 自动配置已切换到 Boot 4 的 Rest5 客户端；旧实验显式钉死的 8.18.8 客户端已移除，SmartCN 服务端同步升级至 9.4.5，别名响应读取改用 9.x API 的 `aliases()`。

## 依赖策略

根 POM 只声明 Spring AI 2.0.1 BOM。各服务不得单独覆盖 Spring Framework、Spring Cloud、Spring AI 或 Elasticsearch Java Client 版本，避免出现同一运行时中 API 与自动配置不匹配。外部模型密钥和端点通过环境配置注入；测试使用本地可控 HTTP 替身，不依赖真实模型账号。

## 验证要求

平台守卫测试会断言 Spring Boot 4.1.1、Spring Framework 7.0.9 和 Spring AI 2.0.1 的有效版本。完整验收必须在 Docker 可用时执行 `mvnw.cmd clean verify`，并要求所有 Surefire、Failsafe 与 Testcontainers 用例均为零失败、零错误、零跳过。
