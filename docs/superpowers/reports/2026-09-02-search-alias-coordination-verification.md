# 搜索别名协调协议重设计验证报告

日期：2026-09-04
分支：`learning/campus-market`
设计基线：`8e29c80`
计划基线：`5b09cb1`

## 结论边界

结构性实现与要求的可执行验收均已通过；本报告提交时，独立 Standards/Spec 整分支终审仍待执行，因此尚不宣告实验七完成。

## 实现提交

- `3e0399135e92581c8c792999c37a8b8cff436846`：固定同一物理连接获取、执行与释放 MySQL 命名锁。
- `ae88d38fbd5693e4577d10ac4db992a846431ebf`：统一协调边界异常并保留释放失败信息。
- `9a708056e90ee37e4c2006813fd5c759c4b98539`：将别名切换与协调点线性化。
- `fe016ba01e2ce989771f2f01faaf730e9df7037c`：将协调前的 reconciliation 初始化纳入围栏。
- `4f770c4108e6ca9f4be55e257aecfcb6a7180d23`：消除命名锁内的递归协调。
- `74e3afe591f475dae4017183c97ed1106c6e26b6`：在成功获取重建门禁后初始化索引。
- `1047330f6ab0fe7f933a04980d4693c05ddabc32`：在切换前持久化完整别名成员清理元数据。
- `ae6cc0ab4a35ae131b51f45984af496556757b1e`：在门禁内创建重建意图并重新保护既有 `DONE` 行。
- `2f5eb43de0b1ea4f40c933fc6e77675c38ec7f3f`：串行化清理的存活检查、删除与带令牌完成。

## TDD RED 证据摘要

- Task 1：新增协调器用例最初因 `SearchAliasCoordinator` 不存在产生 7 个编译错误；异常归一化回归最初收到原始 checked `Exception`，而非 `SearchCoordinationException`。
- Task 2：冷启动 reconciliation 最初在 `REBUILDING` 门禁下仍创建别名；OPEN 冷启动在已持有命名锁时递归获取锁并约 30 秒超时；重建在获取门禁前初始化别名；切换失败后缺少完整清理元数据；新鲜重建意图可被 reconciler 抢占，且既有 `DONE` 清理行未重新武装。
- Task 3：三个清理协议测试先因 `SearchIndexCleanupRepository`、`SearchIndexCleanupWorker` 不存在产生 10 个编译错误。

上述 RED 均在对应任务报告中保留精确命令、断言与修复后的 GREEN 结果。

## 协议与故障语义

- `SearchAliasCoordinator` 使用同一 auto-commit JDBC 连接完成 `GET_LOCK`、临界区 SQL/ES 协调决策与 `RELEASE_LOCK`，支持最大连接池大小为 1，不发生自等待。
- 获取锁超时、连接/SQL checked 失败统一为 `SearchCoordinationException`；运行时异常与 `Error` 保持身份。释放在 `finally` 中执行，释放失败在已有主异常时作为 suppressed 异常保留。
- 别名切换、reconciliation、清理的“存活判断 + 删除/替换”共用该线性化点。安全决策读取 read/write 两个别名的完整成员集合，并受 gate owner/token/generation 与 intent owner/token 围栏保护。
- 清理 claim/complete/fail 使用短 `REQUIRES_NEW` 事务；worker 为 `NOT_SUPPORTED`，不会在 Elasticsearch I/O 期间持有调用者数据库事务。过期所有者不能完成新所有者的 claim。
- 切换前先将所有旧成员持久化为受保护清理行；切换后再武装非 live 成员。崩溃窗口由 reconciliation 根据持久化 intent/cleanup 元数据收敛。

## 三次独立 `SearchRebuildIT` 验收

命令：`./mvnw.cmd '-Dit.test=SearchRebuildIT' verify`，每次均为新的 Maven 与 Testcontainers 生命周期。

| 次数 | 完成时间（Asia/Shanghai） | 结果 | 套件耗时 |
|---|---|---|---|
| 1 | 2026-09-04 10:55 | 27/27，失败 0，错误 0，跳过 0 | 250.8 s |
| 2 | 2026-09-04 11:42:04 | 27/27，失败 0，错误 0，跳过 0 | 256.7 s |
| 3 | 2026-09-04 11:47:01 | 27/27，失败 0，错误 0，跳过 0 | 268.5 s |

三次均 `BUILD SUCCESS`；未出现命名锁超时或泄漏断言失败，完整套件中的并发切换/清理断言确认 read/write aliases 最终收敛到同一个唯一目标。

## 组合回归

命令：

```powershell
.\mvnw.cmd '-Dit.test=ProductSearchIT,SearchRebuildIT,SchemaIT,InventoryIT,ListingMediaIT' verify
```

完成时间：2026-09-04 11:52:19（Asia/Shanghai），`BUILD SUCCESS`，总耗时 05:04。

| 套件 | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `InventoryIT` | 6 | 0 | 0 | 0 |
| `ListingMediaIT` | 8 | 0 | 0 | 0 |
| `ProductSearchIT` | 5 | 0 | 0 | 0 |
| `SearchRebuildIT` | 27 | 0 | 0 | 0 |
| `SchemaIT` | 5 | 0 | 0 | 0 |
| **合计** | **51** | **0** | **0** | **0** |

同一命令的 unit phase 为 44/44，通过且 0 skipped。Flyway 校验并应用 V1–V12。

## 验证环境与仓库卫生

- Windows 11 amd64；Oracle JDK 17.0.12；Maven Wrapper 3.9.11。
- Docker Desktop client/server 29.7.2，Server API 1.55。
- Testcontainers 使用 MySQL 8.4 与 `campus-market/elasticsearch:8.18.8-smartcn`；组合测试同时启动 Redis 7.4.2、RabbitMQ 3.13.7、MinIO 与 Toxiproxy 2.12.0。
- 验收前 `git diff --check` 无输出，`git status --short` 为空。

## 遗留关注点

- Task 1 质量审查的 Minor：临界区已有主 `Error` 时，极端的释放侧 JVM `Error` 仍可能覆盖主 `Error`。这不影响通常的 RuntimeException/checked/SQL 失败语义。
- Task 3 质量审查的 Minor：一个负向并发断言使用 200 ms `await`，在极端慢机器上理论上可能假通过；三次完整回归与确定性正向屏障未发现功能故障。
- Flyway 对 MySQL 8.4 输出“高于已测试 8.1”的升级提示，但 12 个迁移均校验并成功运行；后续依赖升级时应重新验证。
- 最终是否可关闭原 Task 7 BLOCKED 状态，以独立 Standards/Spec 审查无 Critical/Important 为准。

## 独立终审修复补充

首次整分支终审发现协调锁超时会消耗 cleanup 业务 attempt、可观测性不完整、别名切换前未显式校验 target、关键并发证据不足、职责交叉及英文注释等问题，因此 verdict 为 `NOT APPROVED`。随后以严格 TDD 完成三次聚焦修复提交：

- `5082772`：分类协调超时、无损返还 cleanup attempt、显式校验目标索引并接入基础指标。
- `d6bfc5f`：补齐 alias/reconciliation/cleanup 指标与真实并发证据，将 cleanup SQL 收敛到专用仓储。
- `0c33aa0`：修复 connection-scoped cleanup 路径、共享指标注册表、异常身份与 owner/wait 日志，并显式注入 cleanup 仓储。

最终修复后验证（2026-09-04 13:54–13:59，Asia/Shanghai）：

- unit phase：51/51，失败 0、错误 0、跳过 0。
- `SearchRebuildIT`：35/35，失败 0、错误 0、跳过 0。
- 五套组合集成回归：59/59，失败 0、错误 0、跳过 0；其中 InventoryIT 6、ListingMediaIT 8、ProductSearchIT 5、SearchRebuildIT 35、SchemaIT 5。
- Failsafe summary：completed 59、failures 0、errors 0、skipped 0。

本补充仍不替代第二次独立 Standards/Spec 复审；只有复审无 Critical/Important，Task 7 才可解除 BLOCKED。
