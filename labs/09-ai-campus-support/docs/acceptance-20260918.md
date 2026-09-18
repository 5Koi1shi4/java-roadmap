# 实验九 AI 校园交易客服验收记录（2026-09-18）

状态：已验收。范围是全新实验环境中的只读 AI 客服、经授权的本人交易状态查询和浏览器全栈旅程；不支持生产不停机迁移，不允许模型执行退款、争议裁决、订单修改或其他交易写操作。实验八 8.2 的历史结果保留在 [原验收记录](acceptance-20260916.md)，不被本记录覆盖。

## 环境与新鲜命令

Windows 主机的系统默认 `java` 为 25，但仓库 Maven Wrapper 明确使用 JDK 17.0.12；Node 为 22.17.1、npm 为 10.9.2，Docker Desktop Engine 为 29.7.2。确认 `docker info` 成功后，在实验根目录执行 `mvnw.cmd clean test` 与 `mvnw.cmd clean verify`，在 `support-web` 执行 `npm ci`、`npm test`、`npm run build`、`npm run test:e2e`。最终 `clean verify` 于 2026-09-18 08:06:49（Asia/Shanghai）结束，Reactor 八个项目（聚合根加七个子模块）全部 BUILD SUCCESS，用时 33 分 43 秒。

`clean verify` 清理旧报告后生成 157 份 Surefire/Failsafe XML，汇总如下；所有模块均为 0 failures、0 errors、0 skipped：

| 模块 | Surefire | Failsafe/Testcontainers |
|---|---:|---:|
| platform-test-support | 8 | 0 |
| discovery-server | 4 | 0 |
| identity-service | 25 | 17 |
| legacy-market-service | 138 | 286 |
| product-read-service | 31 | 47 |
| api-gateway | 25 | 15 |
| ai-support-service | 24 | 19 |
| **合计** | **255** | **384** |

后端共 639 项。前端 `npm ci` 安装 118 个锁定包且审计为 0 vulnerabilities；Vitest 4 个文件 13 项通过，TypeScript/Vite 生产构建通过。Playwright 在隔离 Compose 中分别使用桌面 Chromium 与 Pixel 7 视口运行 5 条旅程，共 10 项全部通过，用时约 3 分钟。

## 验收中发现并关闭的缺口

首轮完整 `clean verify` 不是绿灯：四个网关 Cloud 集成测试在启动 legacy 上下文时缺少 `campus.market.support.cursor-secret`。根因是共享夹具显式加载各模块生产 `application.yml`，不会继承 legacy 的测试资源；修复只在 `CloudApplicationCluster.legacyProperties` 注入测试专用密钥，没有放松生产 `SupportCursor` 的必填约束。

该启动缺口关闭后，`CloudJourneyIT` 暴露第二个红灯：精确路由集合仍只列实验八的四条路由，遗漏已实现的 `ai-support-answer -> lb://ai-support-service`。更新契约断言后，定向真实五应用 `CloudJourneyIT` 1 项通过；随后完整八模块 `clean verify` 通过。两处均由既有真实旅程先红后绿，不以跳过外部测试或字符串扫描代替。

## 安全、故障与浏览器证据

- 规则问答只使用已审阅、带来源版本的公开规则片段；私人问题先归一化为固定模板。模型适配器有三秒超时、并发和响应大小上限，不提供工具，不记录 prompt。
- 本人订单、争议和质保由 legacy 按原始 Bearer Token 做对象级授权，只返回 `id/type/status/createdAt/deadline` 最小状态；无权与不存在保持同构 404。Token、原始私人描述和完整交易对象不进入模型请求。
- MySQL、RabbitMQ、Elasticsearch、MinIO、Eureka/JWKS 及商品投影的继承故障演练全部实际运行；AI 规则索引、交易状态下游和模型异常分别收敛为脱敏失败，不生成虚构成功。
- 浏览器旅程覆盖 Mailpit 注册邮件、登录、本人订单问答、登出、401 清除内存会话、交易资源故障恢复和模型故障恢复。前端不使用 localStorage/sessionStorage 持久化 Token，所有业务请求经静态站点同源 `/api/**` 进入 Gateway。

E2E 使用仓库外临时随机口令、PKCS#8/X.509 RSA 密钥和本地模型替身构建并启动完整 Compose；结束后全局 teardown 删除容器、网络、MySQL/MinIO 卷与临时密钥。测试日志保存在忽略的 `support-web/test-results`，不包含 Token、验证码或私钥。

人工复核入口：[架构](architecture.md)、[迁移边界](migration-boundary.md)、[排障](../TROUBLESHOOTING.md)、[学习日志](../notes/learning-log.md)、[面试追问](../interview/question-bank.md)。
