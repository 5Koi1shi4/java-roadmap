# 实验六：安全文件服务与 MinIO

这是一个独立的 Spring Boot 3.5.16 / JDK 17 学习实验，演示受限流上传、真实文件类型检查、全局物理去重、逻辑 ACL、流式下载，以及本地文件系统和 MinIO 之间不可避免的补偿边界。本实验不实现用户中心，也不把演示用的可信 Header 当作生产认证。

## 1. 环境与运行

项目固定使用 JDK 17、MySQL 8.4、Apache Tika 3.3.2、MinIO Java SDK 8.6.0、Testcontainers 1.21.4。Compose 中的镜像和端口如下：

| 服务 | 镜像 | 宿主机端口 |
| --- | --- | --- |
| MySQL | `mysql:8.4` | `3312 -> 3306` |
| MinIO | `quay.io/minio/minio:RELEASE.2025-04-22T22-12-26Z` | `9000 -> 9000`、`9001 -> 9001` |
| Toxiproxy | `ghcr.io/shopify/toxiproxy:2.12.0` | `8666`、管理端 `8474` |

请勿使用不存在的 2025-10 MinIO tag。复制 `.env.example` 为本地未跟踪的 `.env` 后填写凭据；不要把 `.env` 或真实密钥写进命令记录、日志或 Git。

```powershell
Copy-Item .env.example .env
docker compose up -d
docker compose ps
```

Compose 只提供依赖。宿主机运行应用时，MinIO endpoint 通过 `http://localhost:8666` 访问 Toxiproxy；Compose 内的应用应使用 `http://toxiproxy:8666`。直接访问 MinIO 时使用 `http://localhost:9000`。应用还需要按部署环境提供 Spring datasource 配置。

本地对象存储运行时显式使用 `local`：

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local" "-Dspring-boot.run.arguments=--file.storage.type=local,--file.identity.trusted-header-enabled=true"
```

MinIO 运行时改用 `minio`，并按 `.env.example` 提供 endpoint、bucket、region 和凭据：

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local" "-Dspring-boot.run.arguments=--file.storage.type=minio,--file.identity.trusted-header-enabled=true"
```

上述命令只适用于受控的 local/test 演示；应用启动仍需可用的 Spring datasource。生产应采用 JWT/OIDC resolver，不应通过命令行启用可信 Header。

默认配置是 `file.storage.type=local`、`file.upload-session-ttl=1h`、清理批量 50、清理租约 30 秒、失败最多 5 次、临时对象本地兜底年龄 24 小时，下载链接上限 2 分钟。MinIO profile 不是另一个认证模式，而是把同一个 `ObjectStorage` 端口切换到 MinIO；配置 `file.storage.type=minio` 并提供合法 endpoint、bucket、region 和凭据即可。

可信 Header 演示必须同时激活 `local` 或 `test` Profile，并显式设置 `file.identity.trusted-header-enabled=true`；生产环境不应启用它。没有身份适配器时应用启动失败，避免静默降级。

## 2. 分层与代码导航

四层边界保持单向依赖：

- `domain/`：`UploadSession`、`StoredBlob`、`StoredFile`、状态枚举和 `SafeDisplayName`；只表达状态和不变量，不依赖 Spring、JDBC 或对象存储。
- `application/`：`UploadService` / `UploadTransactionService` 编排上传，`FileAccessService` 编排 ACL，`DownloadService` 编排授权与下载，`StagingRecoveryService`、`StorageCleanupService` 负责恢复和清理；事务内只做短数据库操作。
- `infrastructure/`：`Jdbc*Repository`、`LocalObjectStorage`、`MinioObjectStorage`、`SecureStorageKeyFactory`、审计和 Micrometer 实现；这里连接 MySQL 和对象存储。
- `api/`：`FileController`、`FileGrantController`、`DownloadController`、`ApiExceptionHandler`、`CorrelationIdFilter`；这里处理 multipart、HTTP 映射和 UTF-8 JSON。

关键入口和接口是 `RequesterIdentityResolver`、`ObjectStorage`、`FileRepository`、`BlobRepository`、`UploadSessionRepository`、`CleanupTaskRepository`、`AuditRecorder`。数据库迁移位于 `src/main/resources/db/migration/`，Compose 与配置分别位于 `compose.yaml`、`src/main/resources/application.yml`。

## 3. 上传时序、表和状态

上传不会在长数据库事务中读取文件流，时序为：

```text
事务 A：创建 RECEIVING session、随机 tempKey、owner token、1 小时 TTL
  -> 事务外：同一消费流写临时对象，实际计数、SHA-256 和 Tika/签名检查同步完成
  -> 事务 B：session -> VALIDATED；按哈希锁定或创建 STAGING Blob
  -> 事务外：由 Blob owner 将临时对象提交到随机 blobs/<UUID>
  -> 事务 C：Blob -> READY，创建逻辑文件、引用计数 +1，session -> COMPLETED，写成功审计
  -> 事务后：删除 tmp 对象；删除失败或中途失败时建立幂等补偿任务
```

六张表及用途：

1. `upload_session`：上传者、`tmp/<UUID>`、状态、owner token、lease、TTL、实际大小、类型、哈希和关联记录。
2. `stored_blob`：唯一内容哈希、随机正式 object key、大小/媒体类型、引用计数、状态、generation、staging/cleanup token 与 lease。
3. `stored_file`：随机逻辑 `file_id`、owner、Blob 引用、安全展示名和 `ACTIVE/DELETED` 状态。
4. `file_grant`：`(file_id, grantee_user_id)` 主键、授权者与授权时间；只表示只读授权。
5. `storage_cleanup_task`：`TEMP_OBJECT` 或 `BLOB_OBJECT`、目标代次/key、状态、owner、claim token、lease、尝试次数和有限错误分类。
6. `file_audit_event`：内部 correlation ID、actor、动作、逻辑 file ID、目标用户、结果、有限失败分类、客户端 trace 摘要和时间。

`upload_session` 状态为 `RECEIVING -> VALIDATED -> FINALIZING -> COMPLETED`，失败/过期进入 `FAILED`/`EXPIRED`。`stored_blob` 状态为 `STAGING -> READY -> PENDING_DELETE -> DELETING -> DELETED`；删除代次只有在引用归零并完成物理删除后才是 `DELETED`，之后可使用新的随机 object key 进入下一代 `STAGING`。每次领取会生成新的 owner/claim/cleanup token。续租、推进、接管、完成和重排都用目标 ID、状态、token、lease、object key 与 generation 条件更新；迟到 owner 的影响行数为 0。`CURRENT_TIMESTAMP(6)` 是 JDBC 持久化和租约判断的 MySQL 时间源，应用实例时钟不负责数据库 fencing。

## 4. 文件边界与隐私

只接受 JPEG、PNG、WebP、PDF。上限是实际读取的 20 MiB；不能以 multipart 声明长度代替读取计数。Tika 和文件签名用于识别真实类型，客户端 `Content-Type` 不能单独建立信任。无扩展名可以上传；存在扩展名但与真实类型冲突时拒绝。原始文件名只作展示输入，保存前会移除控制字符、换行、路径分隔符和危险名称，并生成安全 ASCII fallback。

`fileId`、`tempKey` 和 `objectKey` 都由加密安全随机源产生。物理 key 只允许 `tmp/<UUID>` 或 `blobs/<UUID>`，不包含文件名、用户 ID 或内容哈希。相同内容会由 `stored_blob.content_hash` 唯一约束共享一个活动物理 Blob，但每次上传仍创建独立逻辑文件和各自 ACL。API 响应、普通日志、指标标签和审计不会告诉调用者是否命中去重，也不会返回哈希、Blob ID、object key 或对象 URL。物理 Blob 只能在逻辑 ACL 授权后打开；知道 object key 不是访问授权。

## 5. 身份、ACL 与 HTTP

`RequesterIdentityResolver` 是应用层唯一的身份入口，应用层只接收正数用户 ID。当前演示适配器 `TrustedHeaderIdentityResolver` 仅在 `local`/`test` Profile 且显式开关同时满足时注册，Header 固定为 `X-Trusted-User-Id`，必须是单个规范正十进制整数。缺失、重复、空白、前导零、非数字或越界均按未认证处理。可信网关必须在转发前剥离外部请求中的同名 Header，再注入网关已经认证的身份；本地演示服务应只绑定受控网络。这套 Header 方案不是生产认证。

生产接入 JWT/OIDC 时，实现一个验证签名、issuer、audience、过期时间和权限映射的 `RequesterIdentityResolver` Bean，替换 Header 适配器；上传、ACL、下载、审计和恢复用例不需要改动。生产配置不得打开 trusted-header 开关，且应保留“没有合法 resolver 就启动失败”的 guard。

文件默认私有。owner 可读取元数据、授权指定用户只读、撤权和逻辑删除；grantee 只能读取/下载或申请链接，不能转授权、撤权或删除。管理员角色没有绕过 ACL 的旁路。自身授权/撤权是 400；重复授权由唯一键保护，重复撤权保持幂等。未认证是 401；对已认证请求，文件不存在、已删除和无权访问统一为相同结构的 404，不返回存在性、去重或 owner 信号。

主要 API：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/files` | multipart 上传 |
| `GET` | `/api/files/{fileId}` | ACL 保护的元数据 |
| `DELETE` | `/api/files/{fileId}` | owner 逻辑删除 |
| `PUT/DELETE` | `/api/files/{fileId}/grants/{userId}` | owner 授权/撤权只读 |
| `GET` | `/api/files/{fileId}/content` | 重新检查 ACL 后流式下载 |
| `POST` | `/api/files/{fileId}/download-links` | 重新检查 ACL 后申请链接 |
| `GET` | `/api/local-downloads/{token}` | 本地 HMAC 链接兑换 |

## 6. `/content` 与下载链接

`/content` 在响应开始前完成 ACL 审计、物理对象预打开和首段预读，然后用 `StreamingResponseBody` 分块传输；响应设置私有、禁止缓存、nosniff 和真实长度。下载 token 使用至少 32 字节密钥、HMAC-SHA256、随机 nonce 和常量时间 MAC 比较，兑换时再次解析身份并重查 ACL；撤权后本地 token 不能继续下载。

MinIO 链接是在 ACL 和签发审计成功后创建的 presigned GET，TTL 必须为整秒且不超过 2 分钟。它由对象存储直接处理，服务端无法在每个字节传输前重查 ACL；因此撤权后已有 MinIO URL 最多可能保留约 2 分钟的残余窗口。高风险或需要即时撤权的下载使用 `/content`。两种链接都不把完整签名 URL 写入审计或普通日志，响应头参数只允许安全的 content type/disposition。

## 7. 清理、恢复与 fencing

TEMP 对象受上传会话 1 小时 TTL 约束；本地数据库不可用时，fallback 只检查配置根目录下固定 `tmp` 子目录，拒绝符号链接和越界路径，只删除文件系统修改时间早于 24 小时的普通文件，永不扫描正式 `blobs`。MinIO bucket 生命周期同样只匹配 `tmp/`、24 小时，不匹配 `blobs/`。

清理器每批最多 50 个任务，领取 `NEW` 或 lease 已过期的 `PROCESSING`，写入新 claim token、owner 和 30 秒 lease。失败退避为 5 秒、30 秒、2 分钟、10 分钟；第 5 次失败进入 `FAILED`。不存在的对象按删除成功处理。BLOB 清理先以 Blob cleanup token 将 `PENDING_DELETE -> DELETING`，事务外删除，再用相同 token、generation、object key 原子完成 Blob 与 task；任务和 Blob 的完成必须同一显式事务。临时对象删除前重查 session，只允许终态或已过期且接管成功的会话。

维护重试端点 `POST /api/admin/storage-cleanups/{taskId}/retry` 只有在 `local/test`、`file.maintenance.enabled=true` 且可信身份配置有效时注册；请求不接受 object key、状态或次数，只能条件重置指定 `FAILED` 任务。人工重试因此有三重门禁，且仍受 token/状态条件约束。不要把维护端点当作生产管理员旁路。

## 8. 审计与指标

成功和拒绝的上传、访问、授权、撤权、删除、下载、签发链接、清理和恢复结果都会进入审计边界；权限拒绝在统一 404 之前提交。`JdbcAuditRecorder` 写入前集中脱敏：只接受固定结果和失败分类，丢弃 token、签名链接、object key、路径、哈希、secret、JWT、HMAC、异常堆栈和换行。内部 correlation ID 由服务端 `SecureRandom` 生成（16 字节、Base64URL 22 字符），客户端 trace 只能作为受限的独立字段，不能覆盖内部 ID。

固定指标名称为：`file.upload.total{result}`、`file.upload.duration`、`file.session.count{status}`、`file.blob.count{status}`、`file.staging.oldest.seconds`、`file.cleanup.pending{type}`、`file.cleanup.retry.total{type,result}`、`file.download.total{phase,result}`、`file.acl.total{action,result}`、`file.storage.operation.duration{operation,result}`。标签值只来自固定枚举；不得使用 user/file/hash/blob/object/temp/correlation ID、异常类名或异常消息作标签。默认 Actuator 只暴露 health/info；指标测试会显式开启 metrics。

## 9. 验证与已知边界

在本目录执行：

```powershell
java -version                 # 应为 JDK 17
.\mvnw.cmd test               # 仅 Surefire 单元测试，不启动 Testcontainers
.\mvnw.cmd clean verify       # Surefire + Failsafe，需 Docker Desktop
git ls-files
git grep -n -E "(MINIO_SECRET_KEY=.{8,}|X-Amz-Signature|BEGIN (RSA|PRIVATE) KEY)" -- . ':!labs/06-file-service/.env.example'
git diff --check
```

Task 11 最终独立验收（基线 `bc57db8`）记录为 Surefire 96、Failsafe 80；两者均 0 failures、0 errors、0 skipped，使用真实 MySQL 8.4、固定 MinIO 镜像和 Toxiproxy，恢复演练连续执行 3 轮。Task 12 的 fresh `clean verify` 结果应以终端和 `target/surefire-reports`/`target/failsafe-reports` 为准；若与上述历史证据不同，不得照抄历史计数。

三轮恢复测试在每轮断开 MinIO 后验证失败/积压、恢复后的会话和清理终态，并递归列出 bucket，要求 `tmp/` 为空，`blobs/` 对象集合与数据库 READY 的 `object_key` 集合一致。该证据覆盖数据库记录与已知 bucket 对象的一致性，不等于对任意外部未登记对象的独立清单证明。

安全限制：本实验没有病毒扫描、分片/断点续传、CDN、完整认证中心或通用工作流；20 MiB 是硬读取上限；预签名撤权有残余窗口；任何物理访问都必须先经过逻辑 ACL；排障不得直接改状态、删正式 Blob 或绕过 resolver/ACL。
