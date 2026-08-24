# 实验五：Elasticsearch 商品搜索设计

## 1. 背景与目标

阶段五建设一个独立、可运行、可验证的商品搜索实验。MySQL 是商品事实源，Elasticsearch 是可重建的搜索索引；商品写入成功只承诺 MySQL 数据与 Outbox 事件在同一事务中提交，搜索结果允许短暂的最终一致性延迟。

实验覆盖以下学习目标：

- 使用 Elasticsearch 8 和官方 `analysis-smartcn` 插件完成中文商品检索。
- 实现多字段权重、过滤、排序、分页、高亮和分类聚合。
- 使用事务 Outbox 可靠同步 MySQL 与 Elasticsearch。
- 使用租约、claim token 和外部版本保证多实例并发、重复执行与乱序事件安全。
- 支持可恢复的失败处理、在线全量重建、原子别名切换和一致性检查。
- 用单元测试、MySQL Testcontainers、Elasticsearch Testcontainers 和真实 HTTP 测试形成验收闭环。

本实验不复用前四个实验的代码、数据库或运行环境，只复用已经验证过的分层、条件领取、租约和 token fencing 思路。阶段五不引入 Kafka、Debezium、自动补全、同义词或面向客户端的深分页。

## 2. 仓库与技术基线

- 分支：`learning/elasticsearch-search`
- 目录：`labs/05-elasticsearch-search/`
- 活动树：只包含根 `.gitignore` 与本实验目录，不加入其他实验或 `main` 文档。
- JDK：17
- Spring Boot：3.5.16
- Elasticsearch Java API Client：8.18.8
- Elasticsearch：8.18.8
- MySQL：8.4 LTS
- Maven Wrapper：3.9.11（wrapper 脚本版本 3.3.4）

Spring Boot 3.5.16 管理的 `co.elastic.clients:elasticsearch-java` 版本为 8.18.8，因此服务端固定为相同版本。Elasticsearch 自定义镜像在构建时执行 `elasticsearch-plugin install --batch analysis-smartcn`；运行与集成测试均使用该镜像，避免测试环境和手工环境的分析器差异。

## 3. 总体架构

项目采用四层结构：

- 领域层：定义 `Product`、商品状态、价格、版本和领域校验，不依赖 Spring、MySQL 或 Elasticsearch。
- 应用层：定义商品增删改、搜索、Outbox 分发、重建、一致性检查和修复用例及事务边界。
- 基础设施层：实现 Spring JDBC、Flyway、Elasticsearch Java Client、调度器、时钟和指标适配。
- API 层：提供商品管理、商品搜索和受控运维接口，负责协议校验和错误映射。

核心数据流为：

```text
HTTP 商品变更
  → MySQL 商品表与 Outbox 同事务提交
  → 调度器按租约批量领取 Outbox
  → Elasticsearch 使用外部版本幂等写入
  → claim token 条件完成 Outbox
```

搜索请求只读取 Elasticsearch。Elasticsearch 连接失败、超时或查询执行失败时返回 503，不能把基础设施故障伪装成空结果或商品不存在。

## 4. 领域模型与持久化

### 4.1 商品

`product` 表包含：

- `id`：正数 `BIGINT`，创建后不复用。
- `name`：必填商品名。
- `subtitle`：可选短描述。
- `description`：必填详情文本。
- `category_code`、`category_name`：必填分类编码与显示名。
- `price`：大于等于 0 的 `DECIMAL(12,2)`。
- `status`：`ON_SALE`、`OFF_SHELF` 或内部终态 `DELETED`。
- `version`：从 1 开始的正数业务版本，每次修改严格递增 1。
- `created_at`、`updated_at`：UTC 时间。

创建接口只接受 `ON_SALE` 或 `OFF_SHELF`。更新使用 `WHERE id = ? AND version = ? AND status <> 'DELETED'` 条件写入；影响行数为 0 时区分不存在、已删除和版本冲突。删除为逻辑删除，将状态改为 `DELETED` 并递增版本，不允许恢复或复用 ID。

构造器与工厂方法拒绝空值、空白分类、非法价格、非法版本和不支持的状态。API 反序列化拒绝未知字段和非法枚举，应用层不清洗协议数据。

### 4.2 Outbox

`search_outbox` 表包含：

- 自增序号、随机 UUID `event_id`、商品 ID 和商品业务版本。
- 事件类型 `PRODUCT_UPSERT` 或 `PRODUCT_DELETE`。
- 不可变 JSON 快照；删除事件也携带形成 tombstone 所需的 ID、状态和版本。
- 状态 `NEW`、`PROCESSING`、`COMPLETED`、`FAILED`。
- owner、claim token、租约到期时间、下次可尝试时间和尝试次数。
- 创建、完成时间以及长度受限、已脱敏的最近失败原因。

表上同时约束 `event_id` 唯一，以及 `(product_id, product_version, event_type)` 唯一，因此同一业务版本只能存在一条对应变更事件。商品数据与事件必须在同一 MySQL 事务提交或回滚。修复同一商品版本时不创建重复事件，而是把对应事件安全地重新置为 `NEW`；若事件因外部破坏确实缺失，修复用例以新的 UUID 补建，组合唯一键仍防止并发重复补建。

事件快照一经创建不得修改。调度时不回查“当前商品”替换快照，避免把旧事件悄然变成新事件；乱序由 Elasticsearch 外部版本处理。

已完成事件在本实验中不自动清理。在线重建需要按 Outbox 高水位重放不可变事件；事件归档和保留期策略留到后续平台化阶段。

### 4.3 重建与协调状态

`search_rebuild_job` 持久化任务 ID、目标物理索引、状态 `PENDING/RUNNING/COMPLETED/FAILED`、租约、起止高水位、导入数量、校验结果和长度受限的失败原因。

`search_coordination` 保存单例协调行和 dispatcher pause 标志。商品变更事务在修改商品前对该行取得共享锁；重建最终切换使用排他锁形成有界写入窗口。Outbox 领取事务必须先读取 pause 标志，为 `true` 时不得领取新事件。

## 5. Elasticsearch 索引模型

物理索引使用版本化名称，例如 `products-v20260824153000`；应用只访问：

- `products-read`：搜索读取别名。
- `products-write`：Outbox 分发写入别名。

正常运行时两个别名指向同一物理索引。索引映射启用 `dynamic: strict`，包含：

- `productId`：`long`。
- `name`：`text`，使用 `smartcn`，并带 `keyword` 子字段。
- `subtitle`、`description`：`text`，使用 `smartcn`。
- `categoryCode`：`keyword`。
- `categoryName`：用于显示和聚合的 `keyword`，并带使用 `smartcn` 的文本子字段。
- `price`：`scaled_float`，`scaling_factor=100`。
- `status`：`keyword`。
- `sourceVersion`：正数 `long`。
- `createdAt`、`updatedAt`：`date`。

`DELETED` 商品保留 tombstone 文档，搜索固定过滤 `status=ON_SALE`。保留 tombstone 是为了让删除版本持续存在，防止长时间延迟的旧 UPSERT 事件重新创建已删除商品。

首次启动发现别名不存在时，索引初始化器使用同一重建租约创建初始物理索引并原子绑定两个别名。并发启动的其他实例只能等待并复用已完成的初始化结果。

## 6. HTTP 接口与搜索语义

### 6.1 商品管理

- `POST /api/products`：创建商品，返回商品 ID、版本和状态。
- `PUT /api/products/{id}`：请求体携带 `expectedVersion`，成功后返回递增后的版本。
- `DELETE /api/products/{id}?expectedVersion={version}`：执行逻辑删除。

写接口返回成功只表示 MySQL 商品与 Outbox 已提交，不等待 Elasticsearch 刷新。不存在返回 404，业务版本冲突返回 409，协议和范围错误返回 400。

### 6.2 商品搜索

`GET /api/products/search` 支持参数 `q`、`categoryCode`、`minPrice`、`maxPrice`、`sort`、`page`、`size`：

- 可选关键词 `q`。
- 可选分类编码、最低价和最高价。
- 排序：`relevance`、`priceAsc`、`priceDesc`、`newest`。
- 从 0 开始的页码与 `1–50` 的页大小。
- 名称、副标题和描述高亮。
- 按分类编码聚合并返回分类名称与数量。

关键词在去除首尾空白后最多 100 个 Unicode code point；空白关键词按未提供处理。有关键词时使用多字段查询，默认权重为名称 4、副标题 2、描述 1；无关键词时使用过滤后的 `match_all`。分类、价格和 `ON_SALE` 状态进入 filter context，不参与相关性评分。最低价不得大于最高价。

排序使用以下稳定规则：

- `relevance`：`_score DESC, updatedAt DESC, productId ASC`；无关键词时退化为 `updatedAt DESC, productId ASC`。
- `priceAsc`：`price ASC, productId ASC`。
- `priceDesc`：`price DESC, productId ASC`。
- `newest`：`updatedAt DESC, productId ASC`。

普通分页按 `(page * size) + size <= 10,000` 做防溢出校验，超出返回 400。公开搜索 API 不提供 `search_after`；维护任务内部固定使用 PIT + `search_after`，每批 500 条，直到扫描完成。

响应包含总命中数、当前页商品、相关性分数、字段级高亮和分类聚合。所有 HTTP JSON 显式返回 `application/json; charset=UTF-8`，真实中文 HTTP 测试必须断言内容和媒体类型。

### 6.3 运维接口

运维接口默认为关闭，只能通过 `search.maintenance.enabled=true` 在本地或测试环境显式启用；鉴权体系不属于本独立实验范围。启用后提供：

- `POST /api/admin/search/rebuilds`：启动一次全量重建并返回任务 ID。
- `GET /api/admin/search/rebuilds/{jobId}`：查询重建状态与失败原因。
- `POST /api/admin/search/consistency-checks`：执行一致性检查并返回缺失、落后、多余的数量和每类最多 20 个样例。
- `POST /api/admin/search/products/{productId}/repair`：重新投递指定商品的当前版本事件。
- `POST /api/admin/search/outbox/{eventId}/retry`：显式重新投递 `FAILED` 事件。

同一时刻只允许一个重建任务运行。运维接口不暴露任意索引名、原始 Elasticsearch DSL 或未经边界限制的批量参数。

## 7. Outbox 并发、幂等与失败处理

### 7.1 领取与 fencing

状态流转为：

```text
NEW → PROCESSING → COMPLETED
          └──────→ NEW（可恢复失败且未耗尽）
          └──────→ FAILED（不可恢复或尝试耗尽）
```

调度器每批最多领取 50 条可处理事件。领取以数据库条件更新写入 owner、随机 claim token 和默认 30 秒租约；`NEW` 或租约已过期的 `PROCESSING` 才可领取。完成、重排和失败更新必须同时匹配事件 ID、`PROCESSING` 状态和 claim token。

租约过期后其他实例可以接管。旧 owner 的迟到成功、失败或超时结果影响行数必须为 0，不能覆盖新 owner。领取和状态修改均使用数据库时间判断租约，避免实例时钟漂移决定所有权。

### 7.2 Elasticsearch 写入

每个 bulk item 使用商品业务版本作为 Elasticsearch 外部版本，并采用允许相同版本重复执行但拒绝旧版本覆盖新版本的语义。相同版本的重复 UPSERT、重复 tombstone 和重放修复均为幂等成功；低于索引当前版本的事件视为已经被新状态超越，不再重试。

bulk 响应按 item 处理，不能因为一个商品失败而把整批全部标记成功或失败：

- 成功项独立完成。
- 连接中断、超时、429 和 5xx 属于可恢复异常，按 1 秒、5 秒、30 秒、2 分钟的固定退避重新置为 `NEW`。
- 严格映射错误、非法快照和不支持的版本属于不可恢复异常，直接进入 `FAILED`。
- 默认最多尝试 5 次；失败记录始终保留，可通过受控操作再次投递。

显式重新投递把 `FAILED` 事件改为 `NEW`，尝试次数归零、`available_at` 设为当前数据库时间，并清空 owner、token 和租约；最近失败原因保留到下一次成功完成，便于审计。

## 8. 在线全量重建与别名切换

全量重建必须通过数据库租约保证单实例执行：

1. 创建新物理索引并验证 `smartcn` 分析器、严格映射和索引设置。
2. 记录重建起始 Outbox 高水位，在 MySQL 一致性只读快照中按商品 ID keyset 分页导入全部商品，包括 `DELETED` tombstone。
3. 把起始高水位之后的事件按序重放到新物理索引，外部版本负责消除重复和乱序影响。
4. 设置数据库中的 dispatcher pause 标志，停止新领取并等待现有 `PROCESSING` 租约完成或过期。
5. 对 `search_coordination` 取得排他锁，等待已有商品变更事务结束并阻止新变更；记录最终高水位，将剩余事件补放到新索引并刷新。切换窗口最长 30 秒，超时立即放弃切换并保留原别名。
6. 比较截至最终高水位的商品 ID、版本和状态；通过后用单次 aliases API 原子切换 `products-read` 与 `products-write`。
7. 解除商品写入和 dispatcher 暂停。切换窗口之后产生的 Outbox 事件继续写入新别名。

任何校验或别名切换失败都不得改变当前读写别名；新索引记录为失败产物，等待运维人员显式清理。应用不自动删除旧索引，README 提供先核对别名目标、再使用 Elasticsearch API 手工删除明确物理索引的步骤。

## 9. 一致性检查与修复

一致性检查以 MySQL 为基准，MySQL 使用按 ID 的 keyset 分页，Elasticsearch 使用 PIT + `search_after`，两侧每批 500 条并扫描至结束。检查内容为：

- MySQL 存在但 Elasticsearch 缺失。
- Elasticsearch `sourceVersion` 或状态落后于 MySQL。
- Elasticsearch 存在但 MySQL 没有对应商品。

报告包含各类数量、每类最多 20 个样例、检查时间和目标物理索引，不返回完整商品描述或敏感配置。修复指定商品时读取 MySQL 当前快照，并重新投递该版本对应的 Outbox 事件；修复仍走相同租约、版本和失败处理链路，不绕过可靠同步模型直接修改索引。

## 10. 可观测性与错误边界

Actuator/Micrometer 暴露：

- `NEW`、`PROCESSING`、`FAILED` 事件数量。
- 最老未完成事件延迟。
- 领取批量大小、同步成功/失败计数和 bulk 耗时。
- 搜索请求耗时与错误计数。
- 重建状态、耗时、导入数量和校验差异数。

日志使用事件 ID、商品 ID、版本、claim token 摘要和异常分类建立关联，不记录完整商品描述、认证信息或连接密码。搜索基础设施故障映射为 503；非法查询映射为 400；未预期异常返回稳定的通用错误结构。

## 11. 测试设计

### 11.1 单元测试

- 商品构造、更新、删除、价格、状态与版本校验。
- API 未知字段、非法枚举、价格区间、分页窗口和排序校验。
- 多字段权重、filter context、高亮和聚合查询构造。
- Outbox 状态机、批量边界、异常分类、退避与最大尝试次数。
- claim token fencing、重复事件和旧版本结果处理。
- 重建状态机、失败保留和一致性差异分类。

### 11.2 MySQL 集成测试

- Flyway 创建表、约束、唯一键和领取索引。
- 商品与 Outbox 同事务提交；失败事务不遗留商品或事件。
- `id + version` 条件更新防止并发覆盖。
- 两个实例不会同时完成同一事件。
- 过期租约可接管，旧 owner 的迟到结果不能覆盖新 owner。
- 逻辑删除生成递增版本和唯一 tombstone 事件。

### 11.3 MySQL、Elasticsearch 与真实 HTTP 集成测试

- 自定义镜像已安装 `analysis-smartcn`，索引拒绝未知字段。
- 中文关键词可以命中商品，名称命中的排名高于仅描述命中。
- 分类、价格、状态过滤以及相关性、价格、时间排序正确。
- 分页、高亮、分类聚合和 UTF-8 媒体类型正确。
- 创建、更新、删除最终同步；重复事件幂等；旧版本不能覆盖新版本或复活已删除商品。
- Elasticsearch 不可用时 Outbox 保持可恢复，恢复后最终同步；搜索返回 503 而不是空结果。
- bulk 部分失败只重试失败项。
- 重建期间并发修改不会遗漏或版本回退，读写别名原子切换。
- 一致性检查识别缺失、落后和多余文档，指定修复后重新一致。

Testcontainers 测试必须真实启动 MySQL 8 与安装对应版本 `analysis-smartcn` 的 Elasticsearch 8.18.8 镜像。Docker 不可用或外部协作测试被跳过不算完整验收。

## 12. 文档与验收标准

实验分支包含：

- 可独立运行的 Maven Wrapper、应用代码、Flyway、Compose 和自定义 Elasticsearch Dockerfile。
- `.env.example` 占位符，不包含真实密码或 Token。
- `README.md`：架构、接口、启动、重建、一致性检查和验证命令。
- `TROUBLESHOOTING.md`：插件版本、容器内存、黄色集群、映射冲突、别名切换和测试资源清理。

实验验收后，在 `main` 单独更新路线状态、学习日志和面试题库；实验分支不合并回 `main`。

目标实验目录中的最终命令为：

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
git diff --check
```

验收要求 0 failures、0 errors、0 skipped，并人工确认测试日志中没有被忽略的容器连接、插件加载、异步重连或资源清理异常。

## 13. 参考资料

- [Spring Boot 3.5.16 管理依赖清单](https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html)
- [Elasticsearch Java API Client 与服务端兼容策略](https://www.elastic.co/docs/reference/elasticsearch/clients/java)
- [Elasticsearch Smart Chinese analysis plugin](https://www.elastic.co/docs/reference/elasticsearch/plugins/analysis-smartcn)
