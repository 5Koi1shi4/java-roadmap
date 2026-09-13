# 实验八学习日志

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
