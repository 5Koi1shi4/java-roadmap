# Task 4 报告：SmartCN 索引与 external_gte 幂等写入

## RED / GREEN

- RED：先写入 `ElasticsearchIndexIT`、`ExternalVersionIT` 及共享容器骨架，再运行 `cmd /c mvnw.cmd "-DskipTests" test-compile`；编译按预期因索引管理器、mutation、writer 类型不存在而失败。
- GREEN（可在当前环境验证部分）：`cmd /c mvnw.cmd "-DskipTests" test-compile`：30 个生产源文件、9 个测试源文件编译成功；`cmd /c mvnw.cmd test`：2 tests、0 failures、0 errors、0 skipped。
- 真实 ES IT：`cmd /c mvnw.cmd "-Dit.test=ElasticsearchIndexIT,ExternalVersionIT" verify` 已进入 Failsafe，但当前沙箱 Docker Engine named pipe 不可用（`AccessDeniedException \\.\pipe\docker_engine`），因此 3 tests 均为环境错误，未用 skip 掩盖。宿主控制器需用 Docker 运行同一命令取得 0 skipped 的真实证据。

## 实现与证据

- `products-index.json` 固定 1 shard/0 replica、`dynamic=strict`；SmartCN 用于 `name`、`subtitle`、`description` 与 `categoryName.text`；keyword、long、scaled_float(100)、date 字段按简报定义。
- `docker/elasticsearch/Dockerfile` 已固定 ES 8.18.8 并安装 `analysis-smartcn`；共享 Testcontainers 镜像从该 Dockerfile 真构建，IT 查询节点插件列表并提交未知字段。
- bootstrap 在 `@Transactional` 的数据库 `FOR UPDATE` 排他锁内读取两个别名；两者均缺失时只创建 `products-vbootstrap`，并用一个 aliases 请求同时安装 `products-read`/`products-write`。缺失一侧或分裂目标不会静默修复。
- writer 使用 `BulkOperation.index(...).version(sourceVersion).versionType(ExternalGte)`；逐 item 映射 2xx、版本冲突、429/5xx 与严格映射错误为四类 `Outcome`。tombstone 是 `status=DELETED` 的版本化 index 文档，旧 upsert 不会复活。
- JDBC coordination 新增 `lockExclusive()`，ES 访问类型均限制在 infrastructure，application 端口仅使用 transport-neutral mutation/result 类型。

## 变更

- 新增严格索引定义、application sync/maintenance 端口与 bootstrap、ES client 配置/索引管理器/writer、共享 ES 容器与两个 IT。
- 修改 `SearchCoordinationRepository`、`JdbcSearchCoordinationRepository` 增加排他锁。

## 自审 / 顾虑

- `git diff --check` 无输出；提交：`4598280 feat(search): index products with external versions`。
- 当前唯一未完成证据是宿主 Docker 真实 IT；沙箱日志明确是 Docker 不可用而非测试 skip 或编译失败。`List` 测试代码使用 Java 17 兼容的 `get(0)`。
