# 实验八学习日志

## 2026-09-15：8.2 商品读服务实施中

- 用户通过“独立商品读服务、交易事实暂留兼容单体”设计并批准实施。实验分支新增第五应用、精确 Gateway GET 路由、`product_read_db` 第三库及最小权限，市场事务产生 `schemaVersion=2` 完整快照，Rabbit publisher confirm 与本机保留事件 replay，读侧 Inbox/版本条件投影/index Outbox。
- 定点真实 MySQL、Rabbit、SmartCN Elasticsearch 测试已分别覆盖事务回滚、publisher confirm/fencing、三库权限、幂等/乱序投影、搜索删失与 index 待办门禁。商品直连 HTTP/JWKS 安全 4/4，JWKS/Eureka 冷启动 readiness 1/1 通过；就绪探针红灯先表现为缺少 health contributor，补上后商品服务返回可检查的 503 组件状态。
- 五应用 Gateway 旅程、故障恢复、在线重建、隔离 Compose smoke、fresh 全量 `clean test`/`clean verify` 仍待完成；旧 8.1 的 489 项不能代表 8.2 验收。当前状态为“实施中”。

## 2026-09-15

- 目标：按 AGENTS.md 恢复实验八 8.1，继续现有独立工作树和 `learning/spring-cloud-split`，不修改实验七、不将实验分支合并回 main。
- Docker 启动排障：日志显示 Secrets Engine 的失效 `engine.sock` 阻碍启动。正常停止 Docker Desktop 后，确认目录仅含一个零字节运行 socket，改名目录保留备份后手动重启；fresh `docker info` 确认引擎 29.7.2。未重置数据库/对象卷或修改自启动。
- Gateway 红绿：原测试 21 项中 19 errors；新增启动回归因 DataSource 无驱动配置失败。根因是旅程的跨服务 test-classpath 引入 JDBC/Hikari/Flyway/MySQL，触发 Gateway 不需要的数据库自动配置。入口显式排除 `DataSourceAutoConfiguration` 后，定向回归和完整 Gateway Surefire 22 项均为 0 failures、0 errors、0 skipped；独立 `luna_worker` Spec/Quality 复核 PASS，修复提交 `8a1de78`。
- 四应用旅程首次 fresh `mvnw.cmd -pl api-gateway -am -Dit.test=CloudJourneyIT -Dfailsafe.failIfNoSpecifiedTests=false verify` 在 JDK 17 下退出 1：上游模块与 Gateway 单元通过，CloudJourneyIT 1 error、0 skipped。Discovery Servlet 上下文误装配 Gateway，触发 `MvcFoundOnClasspathException`。按实际 4.3.5 JAR 的 `spring.cloud.gateway.server.webflux.enabled` 条件分别关闭三个 Servlet 上下文、开启 Reactive Gateway 后，再复跑暴露 Discovery 的 DataSource 无驱动问题。旅程尚未取得 GREEN，不记为验收通过。
- 运行文档修正：验证码示例补 `purpose=REGISTER`；明确 local Compose 无公开验证码读取端点，完整注册旅程由测试夹具验证；历史 137/251 项只表示实验七基线。补 SMTP/签名密钥约束、有界探针轮询、MinIO 持久卷和仅放行四个运行 JAR 的 `.dockerignore`。Compose 配置解析和 diff 检查已通过，镜像构建/应用启动尚未验证。
- Task10：故障测试草稿暂存在忽略目录，未运行，避免未补齐的恢复夹具方法阻碍 Task9 编译；JWKS/Eureka 冷启动探针、缓存停机恢复与指标尚待实现和真实验证。
- 暂停前最终定点结果：四应用测试夹具仅按上下文关闭不属于该应用的自动配置，并明确 Eureka transport；最终 `mvnw.cmd -Dmaven.repo.local=E:/test/work/maven-cache -pl api-gateway -am -Dit.test=CloudJourneyIT -Dfailsafe.failIfNoSpecifiedTests=false verify` 退出 0、BUILD SUCCESS（02:45）。Surefire 为 support 6、discovery 4、identity 24、legacy 135、gateway 22，合计 191；Failsafe CloudJourneyIT 为 1，全部 0 failures、0 errors、0 skipped。真实 Eureka 注册 204、发现 200；客户端旅程只访问 Gateway，真实注册端口、lb 路由、UTF-8 和伪造 Header 断言通过。夹具修改仍待独立复核，未提交。
- 文档复核：初次独立审查发现整体两分钟轮询可能超界、Eureka只检查一次会误判异步注册；修正后 PowerShell parse_errors=0，不可达地址短预算验证 exit=1、elapsed_ms=2761。延迟注册受控夹具未完成验证，辅助进程和临时文件已清理；文档配置保持 WIP，未将复核记为通过。
- 暂停节点：fresh usage 五小时已用 90%、剩 10%，周剩 20%，达到用户既有暂停线；停止新增实现、修复和验收命令，只等待已运行 Maven session 63681 结束并保存交接。root 只读确认无残留 Java 测试进程及 Testcontainers 容器。仅 `8a1de78` 已提交，未推送；其余相关改动保留。
- 下次入口：独立复核 Task9 夹具及其真实注册日志中的默认 peer 重试噪声；完成 Task12 延迟 Eureka 注册脚本验证和 scoped 复核；再恢复忽略目录中的 Task10 草稿，补 JWKS/Eureka readiness、单次未知 kid 刷新及停机恢复，最后执行镜像启动和 fresh Reactor 全量验收。
- 当前结论：实验八继续“进行中”。本次定点旅程已 GREEN；故障恢复、最终镜像启动和 fresh Reactor 全量 verify 均不得由定点或历史结果替代。

## 2026-09-14

- 目标：恢复 8.1 身份拆分，先收敛截止并发测试质量问题，再验证四应用真实 Eureka/HTTP 旅程和故障恢复。
- 当前基线：`learning/spring-cloud-split` 的生产截止修正提交为 `44e4441`；身份库、RS256/JWKS、兼容单体验签、Eureka 与 Gateway 已实现。实验仍在进行中。
- 新鲜定点证据：JDK 17、Docker Desktop 引擎 29.7.2、Testcontainers MySQL 8.4 下执行 `mvnw.cmd -pl legacy-market-service -am -Dit.test=WarrantyDeadlineRaceIT -Dfailsafe.failIfNoSpecifiedTests=false verify`，退出码 0、BUILD SUCCESS；截止并发 IT 为 3 项，0 failures、0 errors、0 skipped。该命令只选择这一个集成测试类，不代表全量验收。
- 验证方法：自动质保调度在测试上下文中关闭，测试手动创建调度器；筹资控制 UPDATE 保留真实状态条件并断言命中一行；锁诊断过滤目标义务 ID，防止观察到其他测试记录的锁。
- 复核与提交：独立 `luna_worker` 复核 Spec/Quality PASS，无阻塞项；测试修正单独提交 `a6c477e`，不覆盖其余本地工作。
- 完整业务回归：`mvnw.cmd -pl legacy-market-service -am verify` 退出码0、BUILD SUCCESS（23分08秒）。本轮新报告中 support单元6、identity单元24/IT17、legacy单元135/IT265，全部0 failures、0 errors、0 skipped；Maven汇总与按本次启动时间过滤的XML一致，排除历史报告。迁移配置与路径守卫提交 `678b8a8`。
- 本地环境验证：最小Compose共9个服务，全部 `restart: "no"`，宿主端口仅回环绑定；纯占位符 `.env.example` 包含SMTP配置。`docker compose --env-file .env.example config --quiet` 与bootstrap `bash -n` 通过。另创建独立临时Compose项目仅启动MySQL8.4（宿主3313，用于验证新初始化脚本），真实验证含反斜杠/单引号的测试密码登录、本库迁移/读取成功，runtime跨库/DDL及migrator跨库均被MySQL权限错误拒绝。核对项目标签后已删除该临时容器、网络和测试卷；没有启动其余应用或历史服务。
- 技术取舍：候选扫描只提供提示，每条义务在独立事务中按主键锁定和条件更新，保留数据库时间与单次业务迁移。修复锁顺序，不通过吞掉死锁、放宽断言或新增重试状态机掩盖问题。
- 待验收：四应用旅程、JWKS 缓存与服务停机恢复、实验七完整业务回归、Reactor 全量 verify 和最终文档复核。没有完成这些项目前不将实验标记为已验收。
- 暂停节点：五小时额度已用91%、剩9%，周剩35%、reserve剩91%，达到用户约定10%阈值，停止新增实现、修复与验证命令。
- Task9本轮正确定点命令 `mvnw.cmd -pl api-gateway -am -Dit.test=CloudJourneyIT -Dfailsafe.failIfNoSpecifiedTests=false verify` 退出1：上游单元通过；Gateway Surefire为21项、0failures、19errors、0skipped（GatewaySecurityTest18、ExplicitRoutesTest1，默认DataSource无驱动），Failsafe CloudJourneyIT尚未运行。fresh报告位于api-gateway/target/surefire-reports；不能记录为旅程GREEN。具体原因须下次按失败证据诊断，不能直接跳过单元错误宣称验收。
- Task9保留未提交WIP：api-gateway/discovery-server/legacy-market-service三个POM，以及CloudJourneyIT与CloudApplicationCluster两测试。夹具已改为真实四应用YAML、高优先级动态配置、端口/路由及伪造Header断言；尚未取得旅程GREEN、独立最终复核或应用镜像启动证据。
- 收尾状态：本轮Maven/Docker测试进程已结束（夹具执行者报告），未启动下一条命令。diff --check通过；仅保存进度文档，不提交未验证POM/测试、不推送远端。
- 下次入口：先诊断Gateway默认DataSource启动错误并获得有效修复红绿，再验证四应用旅程及打包、独立复核；随后Task10 JWKS/停机恢复、Task12应用启动与Reactor完整验收。Task11完整业务回归已完成。

## 2026-09-15：8.1 身份拆分最终验收

- 四应用夹具完成真实 Eureka 注册、Gateway 显式路由、注册与交易 HTTP 旅程。Gateway 和兼容交易单体的 JWKS/Eureka readiness 在冷启动失败时 DOWN，最近成功探测的短缓冲分别在 30/45 秒后到期；身份、交易目标和 Eureka 停机恢复、未知 `kid` 单次刷新与固定指标均有真实测试。`CloudFailureRecoveryIT` 4 项、`CloudJourneyIT` 1 项全部通过。
- 首次全量 `verify` 运行 22 分 25 秒，在 legacy 模块的 267 项集成测试中出现 5 个上下文启动错误：两个刻意禁用 Web 的测试仍继承生产 readiness 组，却没有 Servlet 安全配置提供的 `jwks`/`eureka` 指标。仅这两个测试上下文改为校验 `readinessState`，定点重跑 5 项全绿；生产 Web 服务继续检查两项依赖。
- 最终 JDK 17、Docker Desktop 29.7.2 下的 `clean test` 和 `clean verify` 均 BUILD SUCCESS。六模块 fresh XML 共 121 份、Surefire 196 项、Failsafe/Testcontainers 293 项，总计 489 项，全部 0 failures、0 errors、0 skipped。identity IT17、legacy IT267、Gateway IT9 实际运行；实验七业务回归、双库权限、SmartCN、支付契约和三轮故障恢复没有被删除或静默跳过。
- Compose 从官方 `eclipse-temurin:17-jre` 构建四应用运行镜像和 SmartCN 镜像，九个服务启动成功；README 有界脚本在四应用的 health、liveness、readiness 与 Eureka 3/3 注册上通过，Gateway JWKS HTTP 200、RSA 公钥 1 把。首次脚本误报超时是 PowerShell 7 把无字符集 JSON 的 `Invoke-WebRequest.Content` 返回为字节数组，改为 UTF-8 解码后通过。隔离 smoke 容器、网络、临时卷和仓库外随机凭据/密钥已清理。
- 8.1 身份拆分标记已验收；这是全新环境实验，不代表生产不停机迁移，也没有启动 7.1 聊天、7.2 竞价、7.3 跑腿或真实支付适配器。
