# 实验六排障手册

所有恢复都先保留数据库和对象存储证据，再走应用已有的恢复/清理服务。禁止直接更新状态、手工删除正式 `blobs/`、复制签名 URL、绕过 ACL，或用测试 Header 伪装生产认证。

## 1. Tika 类型或扩展名冲突

**症状 →** 返回 400，错误码为文件内容/名称无效；扩展名与客户端声明看似正确但仍被拒绝。

**检查 →** 保留请求的安全展示名、声明类型和失败分类；确认内容是 JPEG、PNG、WebP 或 PDF，并检查文件签名和 Tika 检测结果。不要只看 multipart 的 `Content-Type` 或文件名后缀。

**安全恢复 →** 用真实白名单格式重新导出文件并重新上传；若是合法无扩展名文件，可不补后缀直接测试。记录 correlation ID，查询脱敏审计。

**禁止操作 →** 不要改白名单、关闭 Tika/签名检查、把错误后缀改到数据库，或上传未识别内容试图“让它通过”。

## 2. Multipart 中断或超过 20 MiB

**症状 →** 返回 413，或连接中断后出现 `RECEIVING`（也可能已进入 `VALIDATED`）session 与临时对象。

**检查 →** 查看请求/上传进程是否仍在运行，确认客户端是否主动断开；再查看 session 的 TTL/lease 与对应 `tmp/<UUID>`，不要把客户端声明长度当作实际大小。确认 `application.yml` 的 multipart 上限未被本地覆盖得更宽。`ExpiredUploadService` 只扫描 `VALIDATED`/`FINALIZING` 的过期会话；`StorageCleanupService` 不会删除仍处于 `RECEIVING` 的会话临时对象。

**安全恢复 →** 对仍为 `RECEIVING` 的会话，先确认请求/进程已经结束，等待会话 TTL 到期；当前实现不会自动领取或把接收中断会话转为可清理终态，若业务需要此能力必须另行设计、评审并部署专门的接收中断策略。对已进入 `VALIDATED`/`FINALIZING` 且 lease 过期的会话，才运行 `ExpiredUploadService`/`StagingRecoveryService`；临时任务随后按 session 终态条件清理，本地 fallback 只清理足够老的临时普通文件。重新上传时确保流完整且不超过 20 MiB。

**禁止操作 →** 不要将上限调大、复用旧 temp key、直接把 `RECEIVING` 改成 `FAILED/EXPIRED/COMPLETED`、删除仍为 `RECEIVING` 的临时对象，或绕过专门中断策略手工清理。

## 3. STAGING/FINALIZING 超时

**症状 →** session 停在 `VALIDATED`/`FINALIZING`，Blob 停在 `STAGING`，或上传等待返回服务暂不可用。

**检查 →** 用 MySQL `CURRENT_TIMESTAMP(6)` 对照 session/Blob lease、generation、object key 和 token；检查 MinIO/local storage 中对应对象的大小，不要用应用主机时间判断 lease。确认等待预算（5 秒，100 ms 轮询）和 staging lease 配置仍满足启动校验。

**安全恢复 →** 运行 `UploadRecoveryScheduler`/`StagingRecoveryService`。过期 lease 只能由新随机 token 条件接管；恢复会核对大小、哈希、类型和对象存在性，再完成 C 或创建补偿任务。旧 owner 的迟到更新应为 0 行。

**禁止操作 →** 不要直接改 `status`、延长旧 owner 的 lease、复用旧 token/generation、把未核验对象标为 READY，或在数据库事务内手工调用对象存储。

## 4. 临时对象残留

**症状 →** 上传已返回失败，`tmp/` 仍有对象，或 MinIO 生命周期/本地 fallback 后仍有少量文件。

**检查 →** 查询 `storage_cleanup_task` 的 task type、状态、attempt、claim lease 与有限错误分类；对照 upload session 的状态、TTL、temp key 和 token。MinIO 只应检查固定 bucket 的 `tmp/` 前缀。

**安全恢复 →** 让幂等 `TEMP_OBJECT` 任务按 lease 接管；任务最多 5 次、按固定退避重试。数据库短暂不可用时使用仅限本地根/tmp 且拒绝符号链接和越界的 fallback。

**禁止操作 →** 不要直接 `rm`/对象删除、扫描正式 `blobs/`、删除活跃 session 的临时对象，或把临时对象移动成正式 key。

## 5. 引用计数或清理任务卡住

**症状 →** 逻辑文件已删除但 Blob 仍为 `PENDING_DELETE/DELETING`，引用计数异常，或 task 长期 `PROCESSING`。

**检查 →** 在只读事务中核对 `stored_file` ACTIVE 引用数、Blob `reference_count`、generation/object key、cleanup token/lease，以及唯一 `(task_type,target_id,target_generation)` 任务。确认是否有 lease 过期和旧 worker 迟到结果。

**安全恢复 →** 运行清理批处理，让新 worker 用新 claim/cleanup token 接管；BLOB 删除完成后由同一事务原子推进 Blob 与 task。若失败达到第 5 次，走受控维护端点的三重门禁进行人工重试。

**禁止操作 →** 不要手动减引用、改成 `DELETED`、删除正式对象后再补数据库，或接受没有 token/generation/object key 条件的更新。

## 6. MinIO bucket、签名、403 或 NoSuchBucket

**症状 →** 启动失败、上传/读取返回 503，presign 失败，或者 MinIO 报 403/NoSuchBucket。

**检查 →** 核对 `file.storage.type=minio`、endpoint 是绝对 `http(s)` URL、bucket/region/凭据来自本机未跟踪配置；检查容器健康和启动日志。未知 bucket 是存储不可用/永久分类，不应被当作逻辑文件 404。确认 MinIO 初始化已创建 bucket 和只匹配 `tmp/` 的 24 小时生命周期。

**安全恢复 →** 先恢复正确的容器和配置，再让应用启动初始化 bucket；用同一个合法 bucket 和 Toxiproxy route 重试。访问 API 时先确认逻辑 ACL；presigned URL 只用于已授权低风险下载。

**禁止操作 →** 不要把 secret 写入日志、把 403 映射成资源不存在、手工拼签名 query、放宽 bucket policy、把 `blobs/` 加入生命周期，或用完整 URL 写审计。

## 7. Toxiproxy proxy populate、端口或恢复

**症状 →** `/proxies` 没有 `minio` route，宿主机 8666/8474 冲突，断开后测试挂住，或恢复后积压不收敛。

**检查 →** 依次确认 `docker compose ps`、管理端 `/version`、`/proxies` 中 route 为监听 8666、上游为 `minio:9000` 且 enabled；宿主机应用使用 `localhost:8666`，容器内使用 `toxiproxy:8666`。确认 connect/read/write timeout 为有限值（测试共享 client 使用 5 秒连接/读/写约束）。

**安全恢复 →** 只通过 Toxiproxy 管理 API 恢复 route，等待 MinIO health ready，再运行恢复 scheduler；重复三轮演练并同时核对 MySQL 状态和 bucket 集合。

**禁止操作 →** 不要把应用 endpoint 直接改回真实 MinIO 来绕过故障、无限延长超时、删除 route 后宣称恢复，或用 local storage 结果替代 MinIO/Toxiproxy 证据。

## 8. Docker/Testcontainers named pipe

**症状 →** Failsafe 初始化报 `docker_engine`/`dockerDesktopLinuxEngine` named pipe 无权限，或容器根本未启动。

**检查 →** 运行 `docker version`、`docker ps`，确认 Docker Desktop 已启动且当前终端有 daemon 权限；查看失败是环境连接错误还是测试断言错误。未启动的 Testcontainers 不是“跳过通过”。

**安全恢复 →** 修复 Docker Desktop/npipe 权限后，从干净目标执行：

```powershell
docker version
docker ps
.\mvnw.cmd clean verify
```

单独执行 `.\mvnw.cmd test` 只能证明 Surefire 单元测试，不能替代 MySQL/MinIO/Toxiproxy 集成证据。

**禁止操作 →** 不要改测试为 `@Disabled`、把 Failsafe errors 记为 skipped、用 local mock 冒充容器，或在 Docker 不可用时声称 full verify 通过。

## 9. 审计失败或指标异常

**症状 →** 审计写入失败导致下载空体 503/业务回滚；Actuator 指标为 0、缺少指标，或出现未知标签。

**检查 →** 以 correlation ID 查数据库和应用日志，确认审计边界是否提交；检查 Actuator metrics 是否按测试显式开启，检查指标名和标签是否来自固定 allow-list。数据库 gauge 由 scrape 时采样，数据库不可用时返回安全的 0，下一次 scrape 会重试。

**安全恢复 →** 保持 fail-close：修复数据库连接或迁移后重试业务。用固定的 `result/phase/action/type/status/operation` 标签重新抓取，分别查看授权、传输和签发链接阶段。

**禁止操作 →** 不要关闭审计以换取 200、把异常消息/user/file/correlation ID 放进标签、把指标 0 当作真实无积压，或直接补写成功审计。

## 10. 本地路径越界或符号链接

**症状 →** local storage 拒绝 key/root，fallback 清理计数为 0，或日志出现路径安全错误。

**检查 →** 确认 local root 是配置目录且不是符号链接；只检查固定 `tmp/` 子目录、`tmp/<UUID>` 和 `blobs/<UUID>` 规范 key，检查真实路径仍以 root 开头。不要跟随链接查看目标内容。

**安全恢复 →** 创建新的受控本地根目录，移除配置中的越界/符号链接设置后重启；让应用通过 `SecureStorageKeyFactory` 和 `LocalTemporaryFallbackCleaner` 重新创建结构。

**禁止操作 →** 不要把 root 改成磁盘根/仓库根、手工拼 `..` 路径、跟随 symlink 删除文件，或把 blobs 目录交给 fallback 扫描。

## 11. 三轮恢复演练定位

**症状 →** 三轮“断开 MinIO -> 失败/积压 -> 恢复”中某一轮未收敛，或者 bucket 与数据库记录不一致。

**检查 →** 逐轮保存原始 session/temp/blob/task 标识的脱敏关联（不保存 token、哈希或完整 URL），核对 session 最终状态、Blob 是否仍 STAGING、引用是否为零、清理 task 是否排空。递归列出固定 bucket，比较 `tmp/` 是否为空以及 `blobs/` 集合是否精确等于数据库 READY 的 object key 集合。

**安全恢复 →** 先修复 route/容器，再运行既有 recovery/cleanup scheduler；用 lease/token/generation 条件接管并重复完整三轮。若某一轮到达正式提交窗口，按最终合法 session/READY 记录与物理对象一致性判定，不把一次故障中的中间状态当作孤儿结论。

**禁止操作 →** 不要直接清空 bucket、删除正式 blobs、把单轮成功当作三轮证据、跳过数据库核对，或把“数据库已知对象一致”扩大成任意未登记对象全量扫描证明。
