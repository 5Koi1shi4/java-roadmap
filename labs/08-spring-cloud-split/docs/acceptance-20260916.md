# 8.2 商品读服务验收记录（2026-09-16）

状态：已验收。范围是全新实验环境中的独立商品读取边界；`market_db` 仍拥有商品、库存、订单、支付和售后交易事实。此记录不宣称生产不停机迁移，也不替代 [8.1 身份拆分历史验收](acceptance-20260915.md)。

## 新鲜构建与测试

在 JDK 17.0.12、Docker Desktop Engine 29.7.2 下，先确认 `docker info`，随后在实验根目录串行执行 `mvnw.cmd clean test` 与 `mvnw.cmd clean verify`。两条命令均退出 0、六模块 BUILD SUCCESS；最终 `clean verify` 用时 34 分 30 秒，结束于 2026-09-16 08:38:56（Asia/Shanghai）。`clean` 后生成的 142 个 Surefire/Failsafe XML 汇总如下，所有模块均为 0 failures、0 errors、0 skipped：

| 模块 | Surefire | Failsafe/Testcontainers |
|---|---:|---:|
| platform-test-support | 6 | 0 |
| discovery-server | 4 | 0 |
| identity-service | 24 | 17 |
| legacy-market-service | 138 | 280 |
| product-read-service | 31 | 47 |
| api-gateway | 25 | 13 |
| **合计** | **228** | **357** |

共 585 项，覆盖原实验七交易回归以及 8.2 的完整快照事务、源 Rabbit confirm/replay、三库权限、商品 Inbox/版本投影、索引待办与永久/短暂错误区分、专用人工失败队列、readiness、SmartCN 搜索、在线重建租约恢复和旧索引清理。源端启动回放只在完成屏障经 confirm 且 `IDLE` 已提交后终止；旧搜索重建关闭期间商品交易和源快照仍可提交。读侧零源事件也必须收到高水位 0 屏障，索引待办与商品人工失败队列清空后才就绪；搜索请求使用相同健康状态。ES 503、网络中断等短暂故障保留索引待办和 DOWN readiness，恢复后自动重投；非法投影与永久 ES 4xx 留在可诊断 `FAILED`。

真实 Gateway 五应用测试覆盖精确两条 GET 搜索路由、Eureka 注册、直接服务验签、草稿/媒体/发布、中文搜索、订单扣减导致售罄删失及订单快照不变。产品故障恢复 3 项与原 Cloud 故障恢复 4 项都在同一次完整 Failsafe 中运行；Rabbit、读服务、ES 停机期间交易写入保留，恢复后搜索和积压收敛。原实验七 Rabbit/搜索/对象存储三轮恢复、六组跨阶段不变量与旧搜索重建 35 项也全部通过。

## 官方镜像与隔离 Compose

使用官方 `eclipse-temurin:17-jre` 五应用 Dockerfile 与安装 SmartCN 的 Elasticsearch 镜像，以仓库外随机口令和 PKCS#8/X.509 RSA 密钥运行独立 `campus82smoke` Compose 项目。`docker compose config --quiet`、build、up 均退出 0；五个应用 `/actuator/health` 为 UP，十个 liveness/readiness 探针为 UP，Eureka 的 identity、legacy、product、Gateway 为 4/4，Gateway JWKS HTTP 200 且只有一把公钥。商品投影 readiness 及 `db/eureka/jwks/productSearch/projection/rabbit` 组件均 UP；`campus.product.read` 和专用 `campus.product.manual.failure` 队列各有 0 条消息。

另以新的仓库外临时 RSA 密钥和随机凭据重复隔离启动，等待商品读服务与 Gateway readiness UP 后，本机签发符合 RS256/15 分钟契约的临时测试 Token，直接经镜像 Gateway 请求两条精确路由：`GET /api/search` 与 `GET /api/listings/search` 均 HTTP 200、`application/json`、空库 `total=0`。Token 只用于这两次本地请求，没有输出或保存到仓库。

两次验证后均对独立项目执行 `docker compose down -v`，再核对无活动容器、项目或 `campus82smoke` 卷；临时 Token、密钥和随机口令目录在仓库外删除。Compose smoke 证明镜像和首次启动就绪，注册/业务/故障结论仍由上述真实 Testcontainers 测试承担。

人工复核入口：[商品读服务与恢复边界](product-read-service.md)、[架构](architecture.md)、[迁移边界](migration-boundary.md)、[排障](../TROUBLESHOOTING.md)、[学习日志](../notes/learning-log.md)、[面试追问](../interview/question-bank.md)。
