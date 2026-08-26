# 安全文件服务与 MinIO Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 创建独立、可复跑的安全文件服务实验，以流式类型校验、随机逻辑文件 ID、MySQL ACL、SHA-256 物理去重和可恢复的本地/MinIO 对象存储协作保护文件上传与下载。

**Architecture:** 上传先进入随机临时对象，在事务外流式计算大小、哈希和真实类型；短 MySQL 事务以 Blob 状态机、租约和 owner token 协调物理提交、逻辑文件创建与引用计数。所有读取先经过逻辑文件 ACL；本地与 MinIO 只实现 `ObjectStorage` 端口，清理任务用 claim token 和数据库时间实现幂等重试与旧执行者 fencing。

**Tech Stack:** JDK 17、Maven Wrapper 3.9.11、Spring Boot 3.5.16、Spring Web、Spring JDBC、Flyway、MySQL 8.4、MinIO Server `RELEASE.2025-10-15T17-29-55Z`、MinIO Java SDK 8.6.0、Apache Tika 3.3.2、Actuator、Micrometer、JUnit 5、AssertJ、Awaitility、Testcontainers 1.21.4、Toxiproxy。

## Global Constraints

- 实验分支固定为 `learning/secure-file-service`，活动树只允许根 `.gitignore` 与 `labs/06-file-service/`；不得提交 `AGENTS.md`、其他实验或 `main` 文档。
- 执行前必须使用 `using-git-worktrees` 建立隔离工作树；新实验分支使用无父提交的独立历史，不从 `main` 携带文档中心文件。
- JDK 固定为 17；Maven Wrapper 固定为 Maven 3.9.11、wrapper 脚本 3.3.4。
- Spring Boot 固定为 3.5.16、MinIO Java SDK 固定为 8.6.0、Apache Tika 固定为 3.3.2、Testcontainers 固定为 1.21.4。
- MySQL 与 Compose 固定为 8.4；MinIO 服务端与测试容器固定为 `quay.io/minio/minio:RELEASE.2025-10-15T17-29-55Z`。
- 文件上限固定为 20 MiB；仅允许 JPEG、PNG、WebP、PDF；上限按实际读取字节执行。
- 原始文件名、用户 ID、逻辑 fileId 和 SHA-256 不得组成 tempKey 或 objectKey；所有存储 Key 由加密安全随机源生成。
- 相同内容全局只保留一个物理 Blob；每次上传仍创建独立 fileId、所有者和 ACL，API 不返回哈希、Blob ID、Object Key 或去重命中信息。
- 所有权与只读授权是唯一访问依据；管理员不自动绕过；不存在、已删除和无权资源对已认证调用者统一返回相同 404。
- Header 身份适配器必须同时满足 `local/test` Profile 与显式开关；默认和生产环境禁止启用。
- 数据库事务中禁止执行文件系统或 MinIO IO；跨资源一致性必须通过持久状态、任务、租约、token 和补偿完成。
- 租约与可用时间统一使用 MySQL 时间。测试可缩短时间配置，但不得扩大文件上限、类型白名单或权限。
- 所有 HTTP JSON 显式返回 `application/json; charset=UTF-8`，未知字段和非法值快速失败。
- 单元测试不得启动 Spring；MySQL、MinIO、Flyway、真实 HTTP、并发和故障恢复使用 Testcontainers 集成测试。
- 每个行为严格执行红—绿循环：先写失败测试并确认失败原因，再写最小实现，再运行同一测试通过，最后只提交本任务文件。
- 除 Git 工作树命令和最终 main 文档命令外，所有 Maven、文件和 Git 命令都从隔离工作树的 `labs/06-file-service/` 目录执行；计划中的路径仍写完整相对路径。

---

## 文件结构

| 路径 | 职责 |
| --- | --- |
| `labs/06-file-service/pom.xml` | 固定依赖、Surefire/Failsafe 分层和构建插件。 |
| `labs/06-file-service/compose.yaml`、`.env.example` | MySQL、MinIO 与本地占位配置。 |
| `labs/06-file-service/src/main/resources/db/migration/V1__file_schema.sql` | 会话、Blob、逻辑文件、ACL、清理和审计表。 |
| `labs/06-file-service/src/main/java/com/example/files/domain/*` | 无框架依赖的状态、文件类型、Blob 和逻辑文件规则。 |
| `labs/06-file-service/src/main/java/com/example/files/application/upload/*` | 流式检查、上传编排、恢复和上传端口。 |
| `labs/06-file-service/src/main/java/com/example/files/application/access/*` | 元数据、ACL、删除、流式下载和签名链接用例。 |
| `labs/06-file-service/src/main/java/com/example/files/application/cleanup/*` | 临时对象与 Blob 清理的领取、重试和 fencing。 |
| `labs/06-file-service/src/main/java/com/example/files/application/audit/*` | 审计端口、动作和安全字段。 |
| `labs/06-file-service/src/main/java/com/example/files/infrastructure/persistence/*` | 所有条件 SQL、事务内引用计数和数据库时间。 |
| `labs/06-file-service/src/main/java/com/example/files/infrastructure/storage/*` | 本地与 MinIO 的临时写入、提交、读取、删除和签名。 |
| `labs/06-file-service/src/main/java/com/example/files/api/*` | Multipart、身份、ACL HTTP 接口、UTF-8 错误和安全响应头。 |
| `labs/06-file-service/src/main/java/com/example/files/observability/*` | 调度器、Micrometer 指标和结构化关联 ID。 |
| `labs/06-file-service/src/test/java/com/example/files/unit/*` | 纯 Java 单元测试。 |
| `labs/06-file-service/src/test/java/com/example/files/integration/*` | 共享 MySQL、MinIO、Toxiproxy、本地目录与真实 HTTP 测试。 |
| `labs/06-file-service/README.md`、`TROUBLESHOOTING.md` | 安全边界、运行、恢复、验收和排障。 |

### Task 1: 建立隔离实验、依赖基线和数据库架构

**Files:**
- Create: `labs/06-file-service/pom.xml`
- Create: `labs/06-file-service/mvnw.cmd`
- Create: `labs/06-file-service/.mvn/wrapper/maven-wrapper.properties`
- Create: `labs/06-file-service/.env.example`
- Create: `labs/06-file-service/compose.yaml`
- Create: `labs/06-file-service/src/main/java/com/example/files/FileServiceApplication.java`
- Create: `labs/06-file-service/src/main/resources/application.yml`
- Create: `labs/06-file-service/src/main/resources/db/migration/V1__file_schema.sql`
- Create: `labs/06-file-service/src/test/java/com/example/files/integration/SharedMySqlContainer.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/integration/FileSchemaIT.java`

**Interfaces:**
- Consumes: JDK 17、Docker Engine、仓库根 `.gitignore`。
- Produces: 独立实验分支；六张业务表；类型安全的 `FileServiceProperties` 配置前缀预留。

- [ ] **Step 1: 创建无父历史的隔离工作树**

先按 `using-git-worktrees` 检查工作树，再执行：

```powershell
git worktree add --detach .worktrees/secure-file-service main
git -C .worktrees/secure-file-service switch --orphan learning/secure-file-service
git -C .worktrees/secure-file-service restore --source=learning/elasticsearch-search --staged --worktree -- .gitignore
```

Expected: `.worktrees/secure-file-service` 位于仓库内；`git rev-parse --verify HEAD` 对 unborn branch 失败；活动树没有 `README.md`、`docs/`、`notes/`、`interview/` 或其他 `labs/*`。

- [ ] **Step 2: 写数据库架构失败测试**

```java
@Test
void createsFileSecurityTablesAndClaimIndexes() {
    assertThat(tableCount("upload_session")).isOne();
    assertThat(tableCount("stored_blob")).isOne();
    assertThat(tableCount("stored_file")).isOne();
    assertThat(tableCount("file_grant")).isOne();
    assertThat(tableCount("storage_cleanup_task")).isOne();
    assertThat(tableCount("file_audit_event")).isOne();
    assertThat(indexCount("stored_blob", "uk_blob_content_hash")).isOne();
    assertThat(indexCount("storage_cleanup_task", "idx_cleanup_claim")).isOne();
}
```

- [ ] **Step 3: 运行测试确认实验尚不存在**

Run: `.\mvnw.cmd -Dit.test=FileSchemaIT verify`

Expected: FAIL，提示 `pom.xml`、wrapper 或测试类不存在；不能把 Docker 跳过当作预期失败。

- [ ] **Step 4: 创建 Maven、Wrapper、应用和 Compose 基线**

`pom.xml` 固定属性：

```xml
<properties>
  <java.version>17</java.version>
  <maven.compiler.release>17</maven.compiler.release>
  <minio.version>8.6.0</minio.version>
  <tika.version>3.3.2</tika.version>
  <testcontainers.version>1.21.4</testcontainers.version>
</properties>
```

依赖加入 Web、JDBC、Validation、Actuator、Flyway Core/MySQL、MySQL driver、`io.minio:minio`、`org.apache.tika:tika-core`、Boot Test、Testcontainers MySQL/Toxiproxy/JUnit Jupiter 和 Awaitility。Surefire 排除 `*IT`，Failsafe 在 `integration-test`/`verify` 执行 `*IT`。只使用 `tika-core` 的魔数检测，不引入全文解析器。

`FileServiceApplication` 使用 `@ConfigurationPropertiesScan`。基础 `application.yml` 同时设置 `spring.jackson.deserialization.fail-on-unknown-properties=true`、`spring.servlet.multipart.max-file-size=21MB`、`max-request-size=22MB`，以及强制 UTF-8 Servlet 编码；21/22 MiB 只允许请求进入应用，业务读取器仍在第 20 MiB + 1 字节处拒绝。

从已验收实验复制同版本 `mvnw.cmd`，并逐字创建 wrapper 属性；复制后用 `Get-FileHash` 对比来源和目标脚本，哈希必须相同：

```powershell
Copy-Item -LiteralPath 'E:\test\work\java-roadmap\.worktrees\elasticsearch-search\labs\05-elasticsearch-search\mvnw.cmd' -Destination 'E:\test\work\java-roadmap\.worktrees\secure-file-service\labs\06-file-service\mvnw.cmd'
Get-FileHash 'E:\test\work\java-roadmap\.worktrees\elasticsearch-search\labs\05-elasticsearch-search\mvnw.cmd','E:\test\work\java-roadmap\.worktrees\secure-file-service\labs\06-file-service\mvnw.cmd'
```

Wrapper 属性固定为：

```properties
wrapperVersion=3.3.4
distributionType=only-script
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip
```

Compose 固定：

```yaml
services:
  mysql:
    image: mysql:8.4
    environment:
      MYSQL_DATABASE: secure_files
      MYSQL_USER: secure_files
      MYSQL_PASSWORD: ${DB_PASSWORD:-secure_files_local}
      MYSQL_ROOT_PASSWORD: ${DB_ROOT_PASSWORD:-root_local}
    ports: ["3312:3306"]
  minio:
    image: quay.io/minio/minio:RELEASE.2025-10-15T17-29-55Z
    command: server /data --console-address :9001
    environment:
      MINIO_ROOT_USER: ${MINIO_ACCESS_KEY:-minioadmin}
      MINIO_ROOT_PASSWORD: ${MINIO_SECRET_KEY:-minioadmin-local}
    ports: ["9000:9000", "9001:9001"]
```

`.env.example` 只保留变量名和占位值，不写真实秘密。

- [ ] **Step 5: 创建精确 Flyway 架构**

迁移必须包含以下列、约束和索引；时间列均使用 `TIMESTAMP(6)`：

```sql
CREATE TABLE upload_session (
  session_id CHAR(36) PRIMARY KEY,
  uploader_id BIGINT NOT NULL,
  temp_key VARCHAR(255) NOT NULL,
  owner_token CHAR(36) NOT NULL,
  status VARCHAR(16) NOT NULL,
  lease_until TIMESTAMP(6) NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  original_name VARCHAR(255) NOT NULL,
  declared_type VARCHAR(127) NULL,
  actual_size BIGINT NULL,
  detected_type VARCHAR(64) NULL,
  content_hash CHAR(64) NULL,
  blob_id BIGINT NULL,
  file_id CHAR(36) NULL,
  failure_code VARCHAR(64) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_upload_temp_key (temp_key),
  KEY idx_upload_expiry (status, expires_at, lease_until),
  CONSTRAINT chk_upload_status CHECK
    (status IN ('RECEIVING','VALIDATED','FINALIZING','COMPLETED','FAILED','EXPIRED')),
  CONSTRAINT chk_upload_user CHECK (uploader_id > 0),
  CONSTRAINT chk_upload_size CHECK (actual_size IS NULL OR actual_size >= 0)
);

CREATE TABLE stored_blob (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  content_hash CHAR(64) NOT NULL,
  object_key VARCHAR(255) NOT NULL,
  size_bytes BIGINT NOT NULL,
  media_type VARCHAR(64) NOT NULL,
  reference_count BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  generation BIGINT NOT NULL,
  staging_session_id CHAR(36) NULL,
  staging_owner_token CHAR(36) NULL,
  staging_lease_until TIMESTAMP(6) NULL,
  cleanup_token CHAR(36) NULL,
  cleanup_lease_until TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_blob_content_hash (content_hash),
  UNIQUE KEY uk_blob_object_key (object_key),
  KEY idx_blob_staging (status, staging_lease_until),
  CONSTRAINT chk_blob_status CHECK
    (status IN ('STAGING','READY','PENDING_DELETE','DELETING','DELETED')),
  CONSTRAINT chk_blob_size CHECK (size_bytes >= 0),
  CONSTRAINT chk_blob_refs CHECK (reference_count >= 0),
  CONSTRAINT chk_blob_generation CHECK (generation > 0)
);

CREATE TABLE stored_file (
  file_id CHAR(36) PRIMARY KEY,
  owner_id BIGINT NOT NULL,
  blob_id BIGINT NOT NULL,
  display_name VARCHAR(255) NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  deleted_at TIMESTAMP(6) NULL,
  KEY idx_file_owner (owner_id, status, created_at),
  KEY idx_file_blob (blob_id, status),
  CONSTRAINT fk_file_blob FOREIGN KEY (blob_id) REFERENCES stored_blob(id),
  CONSTRAINT chk_file_owner CHECK (owner_id > 0),
  CONSTRAINT chk_file_status CHECK (status IN ('ACTIVE','DELETED'))
);

CREATE TABLE file_grant (
  file_id CHAR(36) NOT NULL,
  grantee_user_id BIGINT NOT NULL,
  granted_by BIGINT NOT NULL,
  granted_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY (file_id, grantee_user_id),
  CONSTRAINT fk_grant_file FOREIGN KEY (file_id) REFERENCES stored_file(file_id),
  CONSTRAINT chk_grant_users CHECK (grantee_user_id > 0 AND granted_by > 0)
);

CREATE TABLE storage_cleanup_task (
  task_id CHAR(36) PRIMARY KEY,
  task_type VARCHAR(20) NOT NULL,
  target_id VARCHAR(64) NOT NULL,
  target_generation BIGINT NOT NULL,
  object_key VARCHAR(255) NOT NULL,
  source_session_id CHAR(36) NULL,
  status VARCHAR(16) NOT NULL,
  owner VARCHAR(128) NULL,
  claim_token CHAR(36) NULL,
  lease_until TIMESTAMP(6) NULL,
  available_at TIMESTAMP(6) NOT NULL,
  attempt_count INT NOT NULL,
  last_error VARCHAR(512) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  completed_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_cleanup_target (task_type, target_id, target_generation),
  KEY idx_cleanup_claim (status, available_at, lease_until, task_id),
  CONSTRAINT chk_cleanup_type CHECK (task_type IN ('TEMP_OBJECT','BLOB_OBJECT')),
  CONSTRAINT chk_cleanup_status CHECK (status IN ('NEW','PROCESSING','COMPLETED','FAILED')),
  CONSTRAINT chk_cleanup_generation CHECK (target_generation > 0),
  CONSTRAINT chk_cleanup_attempt CHECK (attempt_count >= 0)
);

CREATE TABLE file_audit_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  correlation_id CHAR(36) NOT NULL,
  actor_id BIGINT NOT NULL,
  action VARCHAR(32) NOT NULL,
  file_id CHAR(36) NULL,
  target_user_id BIGINT NULL,
  result VARCHAR(24) NOT NULL,
  failure_code VARCHAR(64) NULL,
  client_trace_id VARCHAR(128) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  KEY idx_audit_file_time (file_id, created_at),
  KEY idx_audit_actor_time (actor_id, created_at),
  CONSTRAINT chk_audit_actor CHECK (actor_id > 0)
);
```

- [ ] **Step 6: 运行架构测试并提交**

Run: `.\mvnw.cmd -Dit.test=FileSchemaIT verify`

Expected: PASS，Failsafe 启动 MySQL 8.4 且 Flyway 创建全部约束。

```powershell
git add -- .gitignore labs/06-file-service/pom.xml labs/06-file-service/mvnw.cmd labs/06-file-service/.mvn/wrapper/maven-wrapper.properties labs/06-file-service/.env.example labs/06-file-service/compose.yaml labs/06-file-service/src/main/java/com/example/files/FileServiceApplication.java labs/06-file-service/src/main/resources/application.yml labs/06-file-service/src/main/resources/db/migration/V1__file_schema.sql labs/06-file-service/src/test/java/com/example/files/integration/SharedMySqlContainer.java labs/06-file-service/src/test/java/com/example/files/integration/FileSchemaIT.java
git commit -m "build: initialize secure file service lab"
```

### Task 2: 建立领域状态、配置边界和安全文件元数据

**Files:**
- Create: `labs/06-file-service/src/main/java/com/example/files/config/FileServiceProperties.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/domain/DetectedFileType.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/domain/UploadSessionStatus.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/domain/BlobStatus.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/domain/StoredFileStatus.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/domain/UploadSession.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/domain/StoredBlob.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/domain/StoredFile.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/domain/ReferenceBecameZero.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/domain/SafeDisplayName.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/unit/FileServicePropertiesTest.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/unit/SafeDisplayNameTest.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/unit/StoredBlobTest.java`

**Interfaces:**
- Consumes: Java 17 records、Spring `@ConfigurationProperties` validation。
- Produces: `SafeDisplayName.from(String)`、`StoredBlob.beginStaging(...)`、`StoredBlob.ready()`、`FileServiceProperties`。

- [ ] **Step 1: 写配置和领域失败测试**

```java
@Test
void rejectsUnsafeNamesAndInvalidBlobTransitions() {
    assertThat(SafeDisplayName.from("../报告\r\nX-Test: yes.pdf").value())
        .isEqualTo("报告 X-Test_ yes.pdf");
    StoredBlob blob = StoredBlob.beginStaging(7L, "a".repeat(64), "blobs/random", 12L,
        DetectedFileType.PDF, 1L, UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(60));
    assertThatThrownBy(blob::beginDeleting).isInstanceOf(IllegalStateException.class);
}

@Test
void rejectsConfigurationThatWeakensSecurityBounds() {
    assertThatThrownBy(() -> propertiesWithMaxSize(DataSize.ofMegabytes(21)))
        .isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: 运行测试确认类型不存在**

Run: `.\mvnw.cmd -Dtest=FileServicePropertiesTest,SafeDisplayNameTest,StoredBlobTest test`

Expected: FAIL，编译器报告上述领域类型不存在。

- [ ] **Step 3: 实现最小领域类型和配置**

`DetectedFileType` 固定映射：

```java
public enum DetectedFileType {
    JPEG("image/jpeg", Set.of("jpg", "jpeg")),
    PNG("image/png", Set.of("png")),
    WEBP("image/webp", Set.of("webp")),
    PDF("application/pdf", Set.of("pdf"));
}
```

`StoredBlob` 只允许：`STAGING -> READY`、`READY -> PENDING_DELETE`、`PENDING_DELETE -> DELETING`、`DELETING -> DELETED`、`DELETED -> STAGING`。引用只能在 READY 中增加；从 1 减到 0 时返回 `ReferenceBecameZero`，0 以下立即失败。

`FileServiceProperties` 使用嵌套记录并在构造器校验：

```java
public record FileServiceProperties(
    DataSize maxSize,
    Duration uploadSessionTtl,
    Duration stagingLease,
    Cleanup cleanup,
    Download download,
    Identity identity,
    Storage storage) {
    public static final DataSize SECURITY_MAX_SIZE = DataSize.ofMegabytes(20);
}
```

生产默认值按规格写入 `application.yml`；测试只可缩短 Duration，不可让 `maxSize` 大于 20 MiB。

```yaml
file:
  max-size: 20MB
  upload-session-ttl: 1h
  staging-lease: 2m
  staging-wait-timeout: 5s
  staging-poll-interval: 100ms
  cleanup:
    batch-size: 50
    lease: 30s
    retry-delays: [5s, 30s, 2m, 10m]
    max-attempts: 5
    temporary-object-fallback-age: 24h
  download:
    max-link-ttl: 2m
    local-hmac-secret: ${FILE_LOCAL_HMAC_SECRET:}
  identity:
    trusted-header-enabled: false
  maintenance:
    enabled: false
  storage:
    type: ${FILE_STORAGE_TYPE:local}
    local-root: ${FILE_LOCAL_ROOT:./data/files}
    minio-endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
    minio-access-key: ${MINIO_ACCESS_KEY:}
    minio-secret-key: ${MINIO_SECRET_KEY:}
    minio-bucket: ${MINIO_BUCKET:secure-files}
```

- [ ] **Step 4: 运行领域测试并提交**

Run: `.\mvnw.cmd -Dtest=FileServicePropertiesTest,SafeDisplayNameTest,StoredBlobTest test`

Expected: PASS，且单元测试不启动 Spring。

```powershell
git add -- labs/06-file-service/src/main/java/com/example/files/config/FileServiceProperties.java labs/06-file-service/src/main/java/com/example/files/domain labs/06-file-service/src/main/resources/application.yml labs/06-file-service/src/test/java/com/example/files/unit/FileServicePropertiesTest.java labs/06-file-service/src/test/java/com/example/files/unit/SafeDisplayNameTest.java labs/06-file-service/src/test/java/com/example/files/unit/StoredBlobTest.java
git commit -m "feat: define secure file domain boundaries"
```

### Task 3: 实现受限流、文件检测和本地对象存储

**Files:**
- Create: `labs/06-file-service/src/main/java/com/example/files/application/upload/ObjectStorage.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/upload/TemporaryObject.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/upload/UploadInspection.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/upload/InspectedUpload.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/upload/UploadInspector.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/upload/UploadRejectedException.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/infrastructure/storage/SecureStorageKeyFactory.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/infrastructure/storage/LocalObjectStorage.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/unit/UploadInspectorTest.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/integration/LocalObjectStorageIT.java`

**Interfaces:**
- Consumes: `InputStream`、`SafeDisplayName`、20 MiB 上限。
- Produces: `UploadInspection open(InputStream, String, String, long)`；`ObjectStorage.writeTemporary/commit/open/delete/createPresignedGet`。

- [ ] **Step 1: 写超限、伪造类型和路径越界失败测试**

```java
@Test
void rejectsBytesBeyondLimitEvenWhenDeclaredSizeIsSmall() {
    InputStream body = new ByteArrayInputStream(new byte[21 * 1024 * 1024]);
    assertThatThrownBy(() -> fullyInspect(body, "a.png", "image/png", 1L))
        .isInstanceOf(UploadRejectedException.class)
        .hasMessageContaining("FILE_TOO_LARGE");
}

@Test
void rejectsPdfBytesDeclaredAsPng() {
    assertThatThrownBy(() -> fullyInspect(new ByteArrayInputStream(pdfBytes()),
        "a.png", "image/png", pdfBytes().length))
        .isInstanceOf(UploadRejectedException.class)
        .hasMessageContaining("TYPE_MISMATCH");
}

@Test
void localStorageNeverResolvesOutsideConfiguredRoot() {
    assertThatThrownBy(() -> storage.open("../secret"))
        .isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: 运行测试确认检测与存储端口不存在**

Run: `.\mvnw.cmd -Dtest=UploadInspectorTest -Dit.test=LocalObjectStorageIT verify`

Expected: FAIL，编译器报告 `UploadInspector` 和 `LocalObjectStorage` 不存在。

- [ ] **Step 3: 实现一次流式检查和存储端口**

端口固定为：

```java
public interface ObjectStorage {
    TemporaryObject writeTemporary(String tempKey, InputStream source, long maxBytes);
    void commit(String tempKey, String objectKey);
    InputStream open(String objectKey);
    void delete(String objectKey);
    Optional<URI> createPresignedGet(String objectKey, Duration ttl,
                                     Map<String, String> responseHeaders);
}

public interface UploadInspection {
    InputStream stream();
    InspectedUpload finish(TemporaryObject temporaryObject);
}
```

`UploadInspector.open` 先从源流读取有界检测前缀，使用 Tika `MimeTypes.getDefaultMimeTypes().detect(markSupportedPrefix, metadata)` 做魔数识别，并显式确认 WebP 的 `RIFF....WEBP`、JPEG、PNG、PDF 签名；随后返回由“已读取前缀 + 剩余源流”组成的单一 `UploadInspection.stream()`。该流以 `DigestInputStream` 计算 SHA-256，并在读取第 `maxBytes + 1` 字节时抛出 `FILE_TOO_LARGE`；对象存储消费完成后调用 `inspection.finish(TemporaryObject)` 生成 `InspectedUpload`。整个文件只经过这一条流，不进行第二次完整读取，也不解析 PDF 内容。

`LocalObjectStorage.resolveInsideRoot(key)` 必须执行 `root.resolve(key).normalize()` 并验证结果以 `root` 开头；临时文件写入 `tmp/<随机值>`，正式对象写入 `blobs/<随机值>`。`commit` 在同一文件系统内使用原子移动，不支持时使用目标不存在检查后的普通移动。它的 `createPresignedGet` 返回 `Optional.empty()`；本地链接由 Task 8 的身份绑定 HMAC 服务生成。

- [ ] **Step 4: 运行测试并提交**

Run: `.\mvnw.cmd -Dtest=UploadInspectorTest -Dit.test=LocalObjectStorageIT verify`

Expected: PASS；测试验证 20 MiB 边界、四种允许签名、伪造声明、路径越界、临时提交和流式读取。

```powershell
git add -- labs/06-file-service/src/main/java/com/example/files/application/upload labs/06-file-service/src/main/java/com/example/files/infrastructure/storage/SecureStorageKeyFactory.java labs/06-file-service/src/main/java/com/example/files/infrastructure/storage/LocalObjectStorage.java labs/06-file-service/src/test/java/com/example/files/unit/UploadInspectorTest.java labs/06-file-service/src/test/java/com/example/files/integration/LocalObjectStorageIT.java
git commit -m "feat: add bounded upload inspection and local storage"
```

### Task 4: 实现 JDBC 上传会话、Blob 领取和原子终结

**Files:**
- Create: `labs/06-file-service/src/main/java/com/example/files/application/upload/UploadSessionRepository.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/upload/BlobRepository.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/upload/FileRepository.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/upload/BlobReservation.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/upload/UploadResult.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/audit/AuditAction.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/audit/AuditEvent.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/audit/AuditRecorder.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/audit/CorrelationId.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/infrastructure/persistence/JdbcUploadSessionRepository.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/infrastructure/persistence/JdbcBlobRepository.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/infrastructure/persistence/JdbcFileRepository.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/infrastructure/persistence/JdbcAuditRecorder.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/upload/UploadTransactionService.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/integration/UploadPersistenceIT.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/integration/BlobLeaseIT.java`

**Interfaces:**
- Consumes: `InspectedUpload`、随机 session/token/file/object Key。
- Produces: `UploadSession begin(...)`、`BlobReservation reserve(...)`、`UploadResult finalizeUpload(...)`、条件接管与 fencing。

- [ ] **Step 1: 写事务原子性和租约失败测试**

```java
@Test
void finalizesBlobFileReferenceAndAuditAtomically() {
    BlobReservation r = transactions.reserve(validatedSession());
    UploadResult file = transactions.finalizeUpload(r.sessionId(), r.ownerToken(), r.blobId());
    assertThat(fileRepository.findActive(file.fileId())).isPresent();
    assertThat(blobRepository.get(r.blobId()).referenceCount()).isOne();
    assertThat(auditCount(file.fileId(), "UPLOAD_COMPLETED")).isOne();
}

@Test
void lateOwnerCannotFinalizeAfterLeaseTakeover() {
    BlobReservation first = reserveWithExpiredLease();
    BlobReservation second = transactions.takeOver(first.blobId(), "new-owner");
    assertThat(transactions.tryFinalize(first.sessionId(), first.ownerToken(), first.blobId())).isFalse();
    assertThat(transactions.tryFinalize(second.sessionId(), second.ownerToken(), second.blobId())).isTrue();
}
```

- [ ] **Step 2: 运行测试确认仓储接口缺失**

Run: `.\mvnw.cmd -Dit.test=UploadPersistenceIT,BlobLeaseIT verify`

Expected: FAIL，编译器报告事务服务和 JDBC 仓储不存在。

- [ ] **Step 3: 实现精确仓储签名和条件 SQL**

```java
public interface UploadSessionRepository {
    UploadSession create(long uploaderId, String tempKey, UUID ownerToken,
                         SafeDisplayName name, String declaredType, Duration ttl, Duration lease);
    boolean markValidated(UUID sessionId, UUID ownerToken, InspectedUpload inspected);
    boolean markFinalizing(UUID sessionId, UUID ownerToken, long blobId);
    boolean markCompleted(UUID sessionId, UUID ownerToken, UUID fileId);
    Optional<UploadSession> find(UUID sessionId);
}

public interface BlobRepository {
    BlobReservation reserve(UUID sessionId, UUID ownerToken, InspectedUpload upload, Duration lease);
    Optional<StoredBlob> findByHash(String sha256);
    boolean markReady(long blobId, UUID sessionId, UUID ownerToken);
    boolean takeOverExpiredStaging(long blobId, UUID sessionId, UUID newToken, Duration lease);
}

public record BlobReservation(
    UUID sessionId, UUID ownerToken, long blobId, String objectKey,
    Mode mode) {
    public enum Mode { NEW_STAGING, OWNED_STAGING, REUSE_READY }
    public boolean reusesReadyBlob() { return mode == Mode.REUSE_READY; }
}

public record UploadResult(
    UUID fileId, String displayName, String mediaType, long size, Instant createdAt) { }
```

所有条件更新包含当前状态、session ID、token 和 `lease_until`。租约使用 `CURRENT_TIMESTAMP(6)` 与 `TIMESTAMPADD(MICROSECOND, ?, CURRENT_TIMESTAMP(6))`。`reserve` 捕获内容哈希唯一键竞争后重新读取现有行，不把重复键当作 500。

`finalizeUpload` 在一个 `@Transactional` 方法中按 `upload_session -> stored_blob -> stored_file` 顺序锁定，执行 Blob READY、创建随机逻辑文件、引用从 0 增至 1、会话 COMPLETED 和 `UPLOAD_COMPLETED` 审计。已有 READY Blob 的复用路径在同一事务创建文件并把引用加一。

- [ ] **Step 4: 运行集成测试并提交**

Run: `.\mvnw.cmd -Dit.test=UploadPersistenceIT,BlobLeaseIT verify`

Expected: PASS；回滚测试确认文件、引用和审计全有或全无；旧 token 更新行为为 0 行。

```powershell
git add -- labs/06-file-service/src/main/java/com/example/files/application/audit labs/06-file-service/src/main/java/com/example/files/application/upload/UploadSessionRepository.java labs/06-file-service/src/main/java/com/example/files/application/upload/BlobRepository.java labs/06-file-service/src/main/java/com/example/files/application/upload/FileRepository.java labs/06-file-service/src/main/java/com/example/files/application/upload/UploadTransactionService.java labs/06-file-service/src/main/java/com/example/files/infrastructure/persistence labs/06-file-service/src/test/java/com/example/files/integration/UploadPersistenceIT.java labs/06-file-service/src/test/java/com/example/files/integration/BlobLeaseIT.java
git commit -m "feat: persist upload sessions and blob leases"
```

### Task 5: 完成上传编排、并发去重和 STAGING 恢复

**Files:**
- Create: `labs/06-file-service/src/main/java/com/example/files/application/upload/UploadCommand.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/upload/UploadService.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/upload/StagingRecoveryService.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/upload/StagingWaitPolicy.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/upload/StorageCoordinationUnavailableException.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/upload/UploadFailureClassifier.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/cleanup/CleanupTaskRepository.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/infrastructure/persistence/JdbcCleanupTaskRepository.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/unit/UploadServiceTest.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/integration/ConcurrentDeduplicationIT.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/integration/StagingRecoveryIT.java`

**Interfaces:**
- Consumes: `UploadCommand(long actorId, String originalName, String declaredType, long declaredSize, InputStream body, CorrelationId correlationId)`。
- Produces: `UploadResult upload(UploadCommand)`；`int recoverExpired(int batchSize, String owner)`。

- [ ] **Step 1: 写编排顺序和跨用户并发失败测试**

```java
@Test
void neverRunsStorageIoInsideTransaction() {
    service.upload(command(pdfBytes()));
    inOrder.verify(sessions).begin(any());
    inOrder.verify(storage).writeTemporary(anyString(), any(), eq(MAX_BYTES));
    inOrder.verify(transactions).reserve(any());
    inOrder.verify(storage).commit(anyString(), anyString());
    inOrder.verify(transactions).finalizeUpload(any(), any(), anyLong());
}

@Test
void concurrentSameContentCreatesOneBlobAndSeparateLogicalFiles() throws Exception {
    List<UploadResult> results = runConcurrentUploads(20, samePdfBytes(), users(1, 20));
    assertThat(results).extracting(UploadResult::fileId).doesNotHaveDuplicates();
    assertThat(countReadyBlobs()).isOne();
    assertThat(blobReferenceCount()).isEqualTo(20);
}
```

- [ ] **Step 2: 运行测试确认上传服务不存在**

Run: `.\mvnw.cmd -Dtest=UploadServiceTest -Dit.test=ConcurrentDeduplicationIT,StagingRecoveryIT verify`

Expected: FAIL，编译器报告 `UploadService` 和恢复服务不存在。

- [ ] **Step 3: 实现上传状态编排**

`UploadService.upload` 的固定顺序：

```java
UploadSession session = transactions.begin(command);
try {
    UploadInspection inspection = inspector.open(command.body(), command.originalName(),
        command.declaredType(), properties.maxBytes());
    TemporaryObject temp = storage.writeTemporary(session.tempKey(), inspection.stream(), properties.maxBytes());
    InspectedUpload inspected = inspection.finish(temp);
    BlobReservation reservation = transactions.reserve(session.sessionId(), session.ownerToken(), inspected);
    if (reservation.reusesReadyBlob()) {
        UploadResult result = transactions.attachReadyBlob(reservation);
        cleanupTasks.enqueueTemp(session, temp.key());
        return result;
    }
    waitPolicy.awaitOwnershipOrReady(reservation);
    storage.commit(temp.key(), reservation.objectKey());
    UploadResult result = transactions.finalizeUpload(reservation);
    cleanupTasks.enqueueTemp(session, temp.key());
    return result;
} catch (RuntimeException ex) {
    transactions.recordFailure(session.sessionId(), session.ownerToken(), classifier.classify(ex));
    cleanupTasks.enqueueTempIfEligible(session.sessionId());
    throw ex;
}
```

实现时 `UploadInspector` 直接包装写入流；不得先完整写入后再次读取 20 MiB 文件。若 SDK 需要已知长度，则使用 Multipart 声明长度仅作为上传提示，受限流仍决定实际接受字节。

`StagingWaitPolicy` 每次查询后关闭连接，不开启事务；默认总等待 5 秒、间隔 100 ms，测试配置可缩短。观察到 READY 时复用；观察到过期 STAGING 时尝试条件接管；观察到 PENDING_DELETE/DELETING 时等待 DELETED；超时抛 `StorageCoordinationUnavailableException` 映射 503。

`StagingRecoveryService` 领取过期会话后：若正式对象存在且校验大小一致则重试事务 C；若对象不存在则把 Blob/会话安全回收并创建临时清理任务；任何更新必须匹配新 owner token。

- [ ] **Step 4: 运行并发与恢复测试并提交**

Run: `.\mvnw.cmd -Dtest=UploadServiceTest -Dit.test=ConcurrentDeduplicationIT,StagingRecoveryIT verify`

Expected: PASS；并发测试至少循环 3 轮，每轮 20 个上传；故障测试覆盖事务 B、对象提交、事务 C 三个注入点。

```powershell
git add -- labs/06-file-service/src/main/java/com/example/files/application/upload labs/06-file-service/src/main/java/com/example/files/application/cleanup/CleanupTaskRepository.java labs/06-file-service/src/main/java/com/example/files/infrastructure/persistence/JdbcCleanupTaskRepository.java labs/06-file-service/src/test/java/com/example/files/unit/UploadServiceTest.java labs/06-file-service/src/test/java/com/example/files/integration/ConcurrentDeduplicationIT.java labs/06-file-service/src/test/java/com/example/files/integration/StagingRecoveryIT.java
git commit -m "feat: orchestrate recoverable deduplicated uploads"
```

### Task 6: 接入双重门禁身份和安全上传 HTTP

**Files:**
- Create: `labs/06-file-service/src/main/java/com/example/files/api/security/RequesterIdentity.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/api/security/RequesterIdentityResolver.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/api/security/TrustedHeaderIdentityResolver.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/config/TrustedHeaderIdentityConfiguration.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/api/FileController.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/api/FileResponse.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/api/ApiError.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/api/ApiExceptionHandler.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/api/CorrelationIdFilter.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/unit/TrustedHeaderIdentityResolverTest.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/integration/IdentityConfigurationIT.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/integration/FileUploadHttpIT.java`

**Interfaces:**
- Consumes: `POST /api/files` multipart 字段 `file` 和可信 Header。
- Produces: 201 `FileResponse(fileId, displayName, mediaType, size, createdAt)`；401/400/503 UTF-8 错误。

- [ ] **Step 1: 写身份门禁和真实上传失败测试**

```java
@Test
void rejectsHeaderAdapterOutsideAllowedProfile() {
    new ApplicationContextRunner()
        .withPropertyValues("file.identity.trusted-header-enabled=true")
        .run(context -> assertThat(context.getStartupFailure())
            .hasMessageContaining("trusted header identity is limited to local/test"));
}

@Test
void uploadsChineseNamedPdfWithoutExposingDeduplication() {
    ResponseEntity<String> response = postMultipart("课程资料.pdf", "application/pdf", pdfBytes(), "42");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getHeaders().getContentType().toString())
        .isEqualTo("application/json;charset=UTF-8");
    assertThat(response.getBody()).doesNotContain("hash", "blob", "objectKey", "dedup");
}
```

- [ ] **Step 2: 运行测试确认 HTTP 与身份实现缺失**

Run: `.\mvnw.cmd -Dtest=TrustedHeaderIdentityResolverTest -Dit.test=IdentityConfigurationIT,FileUploadHttpIT verify`

Expected: FAIL，编译器报告身份和 Controller 类型不存在。

- [ ] **Step 3: 实现双重门禁和稳定 HTTP 边界**

`TrustedHeaderIdentityConfiguration` 同时检查：

```java
@Configuration
@Profile({"local", "test"})
@ConditionalOnProperty(prefix = "file.identity", name = "trusted-header-enabled", havingValue = "true")
class TrustedHeaderIdentityConfiguration { }
```

另建启动验证器：当开关为 true 且活动 Profile 不包含 local/test 时抛 `IllegalStateException`；当没有任何 `RequesterIdentityResolver` Bean 时也启动失败。解析器使用 `request.getHeaders("X-Trusted-User-Id")`，要求恰好一个仅含十进制数字的正 `long`。

`CorrelationIdFilter` 忽略客户端对内部 ID 的覆盖，每请求用 `SecureRandom` 生成 16 个随机字节并作 Base64 URL-safe 无填充编码，得到 128 位随机熵的 `CorrelationId`，再放入 request attribute/MDC；客户端 `X-Client-Trace-Id` 只接受 `[A-Za-z0-9._:-]{1,128}`。

Controller 将 Multipart 流直接交给 `UploadService`，不得调用 `getBytes()`。全局异常处理器返回：

```java
public record ApiError(String code, String message, String correlationId) { }
```

响应显式设置 UTF-8；异常消息使用固定中文文案，不回显原始异常、路径或哈希。

- [ ] **Step 4: 运行身份和 HTTP 测试并提交**

Run: `.\mvnw.cmd -Dtest=TrustedHeaderIdentityResolverTest -Dit.test=IdentityConfigurationIT,FileUploadHttpIT verify`

Expected: PASS；真实 HTTP 覆盖 401、重复 Header、四种允许文件、伪造类型、20 MiB 边界、中文名称与字段不泄露。

```powershell
git add -- labs/06-file-service/src/main/java/com/example/files/api labs/06-file-service/src/main/java/com/example/files/config/TrustedHeaderIdentityConfiguration.java labs/06-file-service/src/test/java/com/example/files/unit/TrustedHeaderIdentityResolverTest.java labs/06-file-service/src/test/java/com/example/files/integration/IdentityConfigurationIT.java labs/06-file-service/src/test/java/com/example/files/integration/FileUploadHttpIT.java
git commit -m "feat: expose guarded secure upload api"
```

### Task 7: 实现逻辑文件 ACL、统一 404 和原子删除

**Files:**
- Create: `labs/06-file-service/src/main/java/com/example/files/application/access/FileAccessService.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/access/FileAccessRepository.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/access/FileView.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/access/AccessDecision.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/access/ResourceHiddenException.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/infrastructure/persistence/JdbcFileAccessRepository.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/api/FileGrantController.java`
- Modify: `labs/06-file-service/src/main/java/com/example/files/api/FileController.java`
- Modify: `labs/06-file-service/src/main/java/com/example/files/api/ApiExceptionHandler.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/unit/FileAccessServiceTest.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/integration/FileAclIT.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/integration/FileAclHttpIT.java`

**Interfaces:**
- Consumes: owner、grantee、unrelated actor 与随机 fileId。
- Produces: `getMetadata`、`grantRead`、`revokeRead`、`delete`；GET/PUT/DELETE API。

- [ ] **Step 1: 写 ACL、404 等价和重复删除失败测试**

```java
@Test
void granteeCanReadButCannotGrantOrDelete() {
    service.grantRead(owner, fileId, reader, correlationId);
    assertThat(service.getMetadata(reader, fileId, correlationId)).isNotNull();
    assertThatThrownBy(() -> service.grantRead(reader, fileId, third, correlationId))
        .isInstanceOf(ResourceHiddenException.class);
    assertThatThrownBy(() -> service.delete(reader, fileId, correlationId))
        .isInstanceOf(ResourceHiddenException.class);
}

@Test
void missingUnauthorizedAndDeletedReturnIdenticalHttpError() {
    assertSameHiddenResponse(get(missingId, user), get(privateId, stranger), get(deletedId, owner));
}

@Test
void repeatedDeleteDecrementsReferenceOnlyOnce() {
    deleteAsOwner(fileId);
    deleteAsOwner(fileId);
    assertThat(blobReferenceCount(blobId)).isZero();
    assertThat(cleanupTaskCount(blobId, generation)).isOne();
}
```

- [ ] **Step 2: 运行测试确认 ACL 用例不存在**

Run: `.\mvnw.cmd -Dtest=FileAccessServiceTest -Dit.test=FileAclIT,FileAclHttpIT verify`

Expected: FAIL，编译器报告 `FileAccessService` 不存在。

- [ ] **Step 3: 实现授权查询和事务内状态结果**

仓储用单条授权查询避免先暴露存在性：

```sql
SELECT f.file_id, f.owner_id, f.blob_id, f.display_name, b.media_type, b.size_bytes,
       (f.owner_id = ? OR EXISTS (
          SELECT 1 FROM file_grant g WHERE g.file_id = f.file_id AND g.grantee_user_id = ?
       )) AS readable
FROM stored_file f JOIN stored_blob b ON b.id = f.blob_id
WHERE f.file_id = ? AND f.status = 'ACTIVE' AND b.status = 'READY'
```

不存在或 `readable=false` 均转为同一个 `ResourceHiddenException`。授权、撤权、读取决策和拒绝审计在短事务内提交；审计失败统一返回 503。

删除事务按 `stored_file -> stored_blob -> file_grant -> storage_cleanup_task` 顺序锁定。条件更新 `ACTIVE -> DELETED` 成功时才减引用；引用变为 0 时执行 `READY -> PENDING_DELETE`、生成 cleanup token/generation 并插入唯一 Blob 清理任务。

接口：

```text
GET    /api/files/{fileId}
PUT    /api/files/{fileId}/grants/{userId}
DELETE /api/files/{fileId}/grants/{userId}
DELETE /api/files/{fileId}
```

自身授权返回 400；重复授权/撤权返回 204；无权、缺失、删除均返回相同 404 body。

- [ ] **Step 4: 运行 ACL 测试并提交**

Run: `.\mvnw.cmd -Dtest=FileAccessServiceTest -Dit.test=FileAclIT,FileAclHttpIT verify`

Expected: PASS；验证管理员身份没有旁路、读授权不能转授权、审计失败关闭和统一 404。

```powershell
git add -- labs/06-file-service/src/main/java/com/example/files/application/access labs/06-file-service/src/main/java/com/example/files/infrastructure/persistence/JdbcFileAccessRepository.java labs/06-file-service/src/main/java/com/example/files/api/FileController.java labs/06-file-service/src/main/java/com/example/files/api/FileGrantController.java labs/06-file-service/src/main/java/com/example/files/api/ApiExceptionHandler.java labs/06-file-service/src/test/java/com/example/files/unit/FileAccessServiceTest.java labs/06-file-service/src/test/java/com/example/files/integration/FileAclIT.java labs/06-file-service/src/test/java/com/example/files/integration/FileAclHttpIT.java
git commit -m "feat: enforce private file acl and hidden resources"
```

### Task 8: 实现流式下载和身份绑定的本地签名链接

**Files:**
- Create: `labs/06-file-service/src/main/java/com/example/files/application/access/DownloadDescriptor.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/access/DownloadService.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/access/LocalDownloadTokenService.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/api/DownloadController.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/api/DownloadLinkResponse.java`
- Modify: `labs/06-file-service/src/main/java/com/example/files/application/upload/ObjectStorage.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/unit/LocalDownloadTokenServiceTest.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/integration/LocalDownloadHttpIT.java`

**Interfaces:**
- Consumes: 已认证 actor、fileId、2 分钟以内 TTL。
- Produces: `/content` 流式响应；`POST /download-links`；本地 `/api/local-downloads/{token}` 兑换。

- [ ] **Step 1: 写撤权即时失效和审计失败失败测试**

```java
@Test
void localLinkRequiresSameActorAndCurrentPermission() {
    URI link = links.issue(reader, fileId, Duration.ofMinutes(2), correlationId);
    revoke(owner, fileId, reader);
    assertThat(exchange(link, reader).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(exchange(link, otherUser).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
}

@Test
void auditFailurePreventsResponseBodyFromStarting() {
    audit.failNextInsert();
    ResponseEntity<byte[]> response = download(fileId, owner);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).isEmpty();
}
```

- [ ] **Step 2: 运行测试确认下载类型不存在**

Run: `.\mvnw.cmd -Dtest=LocalDownloadTokenServiceTest -Dit.test=LocalDownloadHttpIT verify`

Expected: FAIL，编译器报告下载服务和 Token 服务不存在。

- [ ] **Step 3: 实现流式授权和 HMAC Token**

`DownloadDescriptor` 固定为：

```java
public record DownloadDescriptor(
    UUID fileId, long actorId, String objectKey, String displayName,
    String mediaType, long size, InputStream content) implements AutoCloseable { }
```

授权查询和 `DOWNLOAD_AUTHORIZED` 审计先在短事务完成，再打开存储流并读取首段缓冲；任何一步失败都不提交 200。Controller 使用 `StreamingResponseBody` 分块复制，并在成功/异常后分别写 `DOWNLOAD_COMPLETED` 或 `DOWNLOAD_FAILED`；若响应已提交后失败则关闭流并记录失败指标。

本地 Token 负载固定为 `version=1|fileId|actorId|expiresEpochSecond|nonce`，使用至少 32 字节配置密钥和 HmacSHA256，Base64 URL-safe 无填充编码。验证使用常量时间比较，拒绝未知版本、篡改、过期、错误 actor；兑换后再次调用当前 ACL，不信任签发时权限。

Content-Disposition 通过 `SafeDisplayName` 输出 ASCII fallback 与 `filename*=UTF-8''...`，并设置 `X-Content-Type-Options: nosniff`、`Cache-Control: private, no-store`。

- [ ] **Step 4: 运行下载测试并提交**

Run: `.\mvnw.cmd -Dtest=LocalDownloadTokenServiceTest -Dit.test=LocalDownloadHttpIT verify`

Expected: PASS；验证大于缓冲区的文件以多段读取、中文名称、撤权/删除即时失效、错误 actor、Token 篡改与过期。

```powershell
git add -- labs/06-file-service/src/main/java/com/example/files/application/access labs/06-file-service/src/main/java/com/example/files/application/upload/ObjectStorage.java labs/06-file-service/src/main/java/com/example/files/api/DownloadController.java labs/06-file-service/src/main/java/com/example/files/api/DownloadLinkResponse.java labs/06-file-service/src/test/java/com/example/files/unit/LocalDownloadTokenServiceTest.java labs/06-file-service/src/test/java/com/example/files/integration/LocalDownloadHttpIT.java
git commit -m "feat: stream authorized local file downloads"
```

### Task 9: 接入 MinIO、预签名 URL 和故障分类

**Files:**
- Create: `labs/06-file-service/src/main/java/com/example/files/config/ObjectStorageConfiguration.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/infrastructure/storage/MinioObjectStorage.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/infrastructure/storage/StorageFailureClassifier.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/infrastructure/storage/StorageUnavailableException.java`
- Create: `labs/06-file-service/src/test/java/com/example/files/integration/SharedStorageContainers.java`
- Create: `labs/06-file-service/src/test/java/com/example/files/integration/MinioObjectStorageIT.java`
- Create: `labs/06-file-service/src/test/java/com/example/files/integration/MinioDownloadHttpIT.java`
- Create: `labs/06-file-service/src/test/java/com/example/files/integration/MinioFaultRecoveryIT.java`
- Modify: `labs/06-file-service/src/main/resources/application.yml`
- Modify: `labs/06-file-service/compose.yaml`

**Interfaces:**
- Consumes: `ObjectStorage`、固定 Bucket、Toxiproxy Endpoint。
- Produces: 与本地适配器相同的临时写入/提交/读取/删除语义；最长 2 分钟 MinIO 预签名 GET URL。

- [ ] **Step 1: 写真实 MinIO 与故障失败测试**

```java
@Test
void commitsTemporaryObjectAndCreatesShortPresignedGet() throws Exception {
    storage.writeTemporary(tempKey, new ByteArrayInputStream(pdf), MAX_BYTES);
    storage.commit(tempKey, objectKey);
    URI uri = storage.createPresignedGet(objectKey, Duration.ofMinutes(2),
        downloadHeaders("资料.pdf")).orElseThrow();
    assertThat(httpGet(uri)).containsExactly(pdf);
    assertThat(storage.exists(tempKey)).isFalse();
}

@Test
void timeoutDoesNotCreateLogicalFileOrReference() {
    toxiproxy.setConnectionCut(true);
    assertThatThrownBy(() -> upload(samePdf())).isInstanceOf(StorageUnavailableException.class);
    assertThat(countActiveFiles()).isZero();
    assertThat(totalReferences()).isZero();
}
```

`DownloadService.issueLink` 先完成 ACL 与审计；若 `ObjectStorage.createPresignedGet` 返回 URL，则返回 MinIO bearer URL，否则调用 `LocalDownloadTokenService.issue` 生成必须再次认证的本地应用链接。两条路径都拒绝超过配置上限的 TTL。

- [ ] **Step 2: 运行测试确认 MinIO 适配器不存在**

Run: `.\mvnw.cmd -Dit.test=MinioObjectStorageIT,MinioDownloadHttpIT,MinioFaultRecoveryIT verify`

Expected: FAIL，编译器报告 `MinioObjectStorage` 不存在；测试不得退回本地适配器。

- [ ] **Step 3: 实现 MinIO 端口和 Profile 切换**

使用 SDK 调用：

```java
client.putObject(PutObjectArgs.builder().bucket(bucket).object(tempKey)
    .stream(source, -1, 10 * 1024 * 1024).contentType(mediaType).build());
client.copyObject(CopyObjectArgs.builder().bucket(bucket).object(objectKey)
    .source(CopySource.builder().bucket(bucket).object(tempKey).build()).build());
client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(tempKey).build());
client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
    .method(Method.GET).bucket(bucket).object(objectKey)
    .expiry((int) ttl.toSeconds()).extraQueryParams(responseHeaders).build());
```

`commit` 必须先确认正式 Key 不存在，再 copy，然后删除临时对象；copy 成功而删除 temp 失败时正式提交仍可继续，temp 交给清理任务。所有 Key 先通过统一语法验证，只允许 `tmp/<UUID>` 或 `blobs/<UUID>`。

`StorageFailureClassifier` 把连接中断、超时、429、5xx 归为 `RETRYABLE`；无效 Key、403、签名配置、未知 Bucket 和状态损坏归为 `PERMANENT`。异常消息不得包含 Secret Key 或完整预签名 URL。

`ObjectStorageConfiguration` 根据 `file.storage.type=local|minio` 只注册一个适配器。MinIO 启动初始化固定 Bucket，并设置仅针对 `tmp/`、24 小时过期的生命周期规则；正式 `blobs/` 不匹配。

- [ ] **Step 4: 运行 MinIO 测试并提交**

Run: `.\mvnw.cmd -Dit.test=MinioObjectStorageIT,MinioDownloadHttpIT,MinioFaultRecoveryIT verify`

Expected: PASS；Toxiproxy 断开后恢复，积压状态最终追平；预签名 URL 过期测试使用短测试 TTL。

```powershell
git add -- labs/06-file-service/src/main/java/com/example/files/config/ObjectStorageConfiguration.java labs/06-file-service/src/main/java/com/example/files/infrastructure/storage labs/06-file-service/src/main/resources/application.yml labs/06-file-service/src/test/java/com/example/files/integration/SharedStorageContainers.java labs/06-file-service/src/test/java/com/example/files/integration/MinioObjectStorageIT.java labs/06-file-service/src/test/java/com/example/files/integration/MinioDownloadHttpIT.java labs/06-file-service/src/test/java/com/example/files/integration/MinioFaultRecoveryIT.java labs/06-file-service/compose.yaml
git commit -m "feat: add recoverable minio object storage"
```

### Task 10: 实现临时对象与 Blob 清理调度

**Files:**
- Create: `labs/06-file-service/src/main/java/com/example/files/application/cleanup/CleanupTask.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/cleanup/CleanupTaskType.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/cleanup/ClaimedCleanup.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/cleanup/CleanupSummary.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/cleanup/CleanupFailureClassifier.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/cleanup/CleanupRetrySchedule.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/cleanup/StorageCleanupService.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/cleanup/ExpiredUploadService.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/cleanup/LocalTemporaryFallbackCleaner.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/observability/CleanupScheduler.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/config/CleanupMaintenanceConfiguration.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/api/CleanupMaintenanceController.java`
- Modify: `labs/06-file-service/src/main/java/com/example/files/infrastructure/persistence/JdbcCleanupTaskRepository.java`
- Modify: `labs/06-file-service/src/main/java/com/example/files/infrastructure/persistence/JdbcBlobRepository.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/unit/CleanupRetryScheduleTest.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/unit/StorageCleanupServiceTest.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/integration/CleanupLeaseIT.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/integration/CleanupRaceIT.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/integration/LocalFallbackCleanupIT.java`
- Test: `labs/06-file-service/src/test/java/com/example/files/integration/CleanupMaintenanceIT.java`

**Interfaces:**
- Consumes: NEW/过期 PROCESSING 清理任务、过期上传会话。
- Produces: `CleanupSummary runBatch(String owner)`；`int expireUploads(String owner)`。

- [ ] **Step 1: 写幂等删除、租约接管和重传竞争失败测试**

```java
@Test
void oldCleanerCannotCompleteAfterLeaseTakeover() {
    ClaimedCleanup first = claimExpiredTask("worker-a");
    ClaimedCleanup second = repository.claimOne("worker-b", Duration.ofSeconds(30));
    assertThat(repository.complete(first.taskId(), first.claimToken())).isFalse();
    assertThat(repository.complete(second.taskId(), second.claimToken())).isTrue();
}

@Test
void uploadWaitsForDeletingGenerationAndUsesNewObjectKey() throws Exception {
    String oldKey = deletingBlobObjectKey();
    UploadResult uploaded = uploadWhileCleanupCompletes(sameContent());
    String newKey = objectKeyForFile(uploaded.fileId());
    assertThat(newKey).isNotEqualTo(oldKey);
    assertThat(storage.exists(newKey)).isTrue();
}

@Test
void activeReceivingSessionTempObjectIsNeverDeleted() {
    runExpirationScan(activeSessionWithFutureLease());
    assertThat(storage.exists(activeTempKey)).isTrue();
}
```

- [ ] **Step 2: 运行测试确认清理服务不存在**

Run: `.\mvnw.cmd -Dtest=CleanupRetryScheduleTest,StorageCleanupServiceTest -Dit.test=CleanupLeaseIT,CleanupRaceIT,LocalFallbackCleanupIT,CleanupMaintenanceIT verify`

Expected: FAIL，编译器报告清理服务和重试计划不存在。

- [ ] **Step 3: 实现领取、删除和 token fencing**

领取批量最多 50，状态条件为 `NEW` 且 `available_at <= CURRENT_TIMESTAMP(6)`，或租约过期的 `PROCESSING`。领取写入随机 claim token、owner 和 30 秒租约。完成、重排和失败必须匹配 task ID、`PROCESSING` 和 claim token。

固定退避：

```java
private static final List<Duration> DELAYS = List.of(
    Duration.ofSeconds(5), Duration.ofSeconds(30),
    Duration.ofMinutes(2), Duration.ofMinutes(10));

public record ClaimedCleanup(
    UUID taskId, CleanupTaskType type, String targetId, long targetGeneration,
    String objectKey, UUID claimToken, int attemptCount) { }

public record CleanupSummary(int claimed, int completed, int retried, int failed) { }
```

第 5 次失败进入 FAILED。对象不存在按成功完成。TEMP_OBJECT 删除前重新读取 upload session，只允许 `COMPLETED/FAILED/EXPIRED` 或会话已过期且当前 token 接管成功；活跃 RECEIVING 禁止删除。

BLOB_OBJECT 路径先以 cleanup token 把 `PENDING_DELETE -> DELETING`，事务外删除对象，再以相同 token 标记 `DELETED` 和任务 COMPLETED。新上传观察到 `PENDING_DELETE/DELETING` 只轮询；直到 DELETED 后才执行 `generation + 1`、新 objectKey、`DELETED -> STAGING`。

`LocalTemporaryFallbackCleaner` 只解析配置的本地根目录和固定 `tmp` 子目录，拒绝符号链接与越界路径，并只删除文件系统修改时间早于 24 小时的临时文件；正式 `blobs` 目录永不扫描。集成测试同时放置 25 小时临时文件、1 分钟临时文件和正式 Blob，只允许第一项被删除。

受控人工重试接口 `POST /api/admin/storage-cleanups/{taskId}/retry` 仅在 `local/test` Profile、`file.maintenance.enabled=true` 且 Header 身份有效时注册；请求不接受 Object Key、状态或次数，只允许把指定 `FAILED` 任务条件重置为 `NEW`、attempt 归零、available_at 设为数据库当前时间并清空 owner/token/lease。默认配置下端点不存在。

- [ ] **Step 4: 运行清理测试并提交**

Run: `.\mvnw.cmd -Dtest=CleanupRetryScheduleTest,StorageCleanupServiceTest -Dit.test=CleanupLeaseIT,CleanupRaceIT,LocalFallbackCleanupIT,CleanupMaintenanceIT verify`

Expected: PASS；竞争测试循环 10 次，确认旧 Key 被删、新 Key 保留、引用为 1、只有一个 READY Blob。

```powershell
git add -- labs/06-file-service/src/main/java/com/example/files/application/cleanup labs/06-file-service/src/main/java/com/example/files/observability/CleanupScheduler.java labs/06-file-service/src/main/java/com/example/files/config/CleanupMaintenanceConfiguration.java labs/06-file-service/src/main/java/com/example/files/api/CleanupMaintenanceController.java labs/06-file-service/src/main/java/com/example/files/infrastructure/persistence/JdbcCleanupTaskRepository.java labs/06-file-service/src/main/java/com/example/files/infrastructure/persistence/JdbcBlobRepository.java labs/06-file-service/src/test/java/com/example/files/unit/CleanupRetryScheduleTest.java labs/06-file-service/src/test/java/com/example/files/unit/StorageCleanupServiceTest.java labs/06-file-service/src/test/java/com/example/files/integration/CleanupLeaseIT.java labs/06-file-service/src/test/java/com/example/files/integration/CleanupRaceIT.java labs/06-file-service/src/test/java/com/example/files/integration/LocalFallbackCleanupIT.java labs/06-file-service/src/test/java/com/example/files/integration/CleanupMaintenanceIT.java
git commit -m "feat: clean storage with leased idempotent tasks"
```

### Task 11: 完成审计、指标和端到端安全故障演练

**Files:**
- Create: `labs/06-file-service/src/main/java/com/example/files/application/audit/AuditSanitizer.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/application/audit/FileServiceMetrics.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/observability/MicrometerFileServiceMetrics.java`
- Create: `labs/06-file-service/src/main/java/com/example/files/observability/UploadRecoveryScheduler.java`
- Create: `labs/06-file-service/src/test/java/com/example/files/unit/AuditSanitizerTest.java`
- Create: `labs/06-file-service/src/test/java/com/example/files/unit/MicrometerFileServiceMetricsTest.java`
- Create: `labs/06-file-service/src/test/java/com/example/files/integration/ActuatorFileMetricsIT.java`
- Create: `labs/06-file-service/src/test/java/com/example/files/integration/SecureFileWorkflowIT.java`
- Create: `labs/06-file-service/src/test/java/com/example/files/integration/StorageRecoveryDrillIT.java`

**Interfaces:**
- Consumes: 所有上传、ACL、下载、清理和恢复结果。
- Produces: 低基数 Micrometer 指标、脱敏审计、可重复的故障恢复证据。

- [ ] **Step 1: 写敏感字段和完整工作流失败测试**

```java
@Test
void metricsNeverUseResourceOrIdentityLabels() {
    metrics.recordUpload("success", Duration.ofMillis(10));
    assertThat(registry.getMeters()).allSatisfy(meter ->
        assertThat(meter.getId().getTags())
            .noneMatch(tag -> Set.of("userId", "fileId", "hash", "objectKey", "correlationId")
                .contains(tag.getKey())));
}

@Test
void auditRowsNeverContainStorageSecrets() {
    exerciseUploadGrantDownloadDelete();
    assertThat(allAuditText()).doesNotContain("tmp/", "blobs/", "X-Amz-Signature", sha256, minioSecret);
}
```

- [ ] **Step 2: 运行测试确认指标实现和演练缺失**

Run: `.\mvnw.cmd -Dtest=AuditSanitizerTest,MicrometerFileServiceMetricsTest -Dit.test=ActuatorFileMetricsIT,SecureFileWorkflowIT,StorageRecoveryDrillIT verify`

Expected: FAIL，编译器报告指标适配器或测试所需调度器不存在。

- [ ] **Step 3: 实现低基数指标和恢复调度**

指标固定为：

```text
file.upload.total{result}
file.upload.duration
file.session.count{status}
file.blob.count{status}
file.staging.oldest.seconds
file.cleanup.pending{type}
file.cleanup.retry.total{type,result}
file.download.total{phase,result}
file.acl.total{action,result}
file.storage.operation.duration{operation,result}
```

允许标签值必须来自固定枚举；禁止把异常类名或消息直接作为标签。内部 correlation ID 只进入 MDC 和审计。调度器使用可配置延迟调用恢复/清理服务，`scheduling.enabled=false` 时测试可以直接调用服务。

`SecureFileWorkflowIT` 使用真实 HTTP、MySQL 和 MinIO 完成：上传 -> 跨用户同内容上传 -> 授权 -> 流式下载 -> MinIO 链接 -> 撤权 -> 统一 404 -> 删除全部引用 -> 清理。`StorageRecoveryDrillIT` 在同一组容器中连续执行 3 次：MinIO 断开 -> 上传失败/积压 -> 恢复 -> 会话和清理最终终态 -> 无孤儿 READY/STAGING 对象。

- [ ] **Step 4: 运行观测与故障演练并提交**

Run: `.\mvnw.cmd -Dtest=AuditSanitizerTest,MicrometerFileServiceMetricsTest -Dit.test=ActuatorFileMetricsIT,SecureFileWorkflowIT,StorageRecoveryDrillIT verify`

Expected: PASS，0 skipped；Actuator 指标不存在高基数标签，审计文本不含禁止字段。

```powershell
git add -- labs/06-file-service/src/main/java/com/example/files/application/audit labs/06-file-service/src/main/java/com/example/files/observability labs/06-file-service/src/test/java/com/example/files/unit/AuditSanitizerTest.java labs/06-file-service/src/test/java/com/example/files/unit/MicrometerFileServiceMetricsTest.java labs/06-file-service/src/test/java/com/example/files/integration/ActuatorFileMetricsIT.java labs/06-file-service/src/test/java/com/example/files/integration/SecureFileWorkflowIT.java labs/06-file-service/src/test/java/com/example/files/integration/StorageRecoveryDrillIT.java
git commit -m "feat: observe and verify secure file workflows"
```

### Task 12: 完成实验文档、全量验收和 main 记录

**Files:**
- Create: `labs/06-file-service/README.md`
- Create: `labs/06-file-service/TROUBLESHOOTING.md`
- Modify after experiment verification on `main`: `README.md`
- Modify after experiment verification on `main`: `notes/learning-log.md`
- Modify after experiment verification on `main`: `interview/question-bank.md`

**Interfaces:**
- Consumes: 已通过的单元、MySQL、MinIO、Toxiproxy、HTTP 和恢复测试证据。
- Produces: 可复跑实验说明、排障复盘、验收计数和独立分支入口。

- [ ] **Step 1: 写 README 和排障文档验收检查**

README 必须包含：

```text
架构与四层边界
上传事务 A/B/C 与补偿时序
六张表和 Blob 状态机
local/test Header 双重门禁及可信网关要求
JPEG/PNG/WebP/PDF 与 20 MiB 限制
本地与 MinIO Profile 启动命令
ACL、统一 404 和管理员不绕过
/content 与预签名 URL 的安全差异
MinIO 撤权后最长 2 分钟残余窗口
临时对象 1 小时会话与 24 小时兜底
清理租约、5 次尝试和人工重试
完整 test/verify 命令
```

TROUBLESHOOTING 必须覆盖：Tika 类型冲突、Multipart 中断、STAGING 超时、临时对象残留、MinIO Bucket/签名错误、Toxiproxy 未恢复、Docker 资源清理、审计失败关闭和本地路径越界。

- [ ] **Step 2: 运行快速单元测试**

Run: `.\mvnw.cmd test`

Expected: BUILD SUCCESS，0 failures、0 errors、0 skipped；确认 Surefire 没有启动 Testcontainers。

- [ ] **Step 3: 运行完整集成验收**

Run: `.\mvnw.cmd verify`

Expected: BUILD SUCCESS，Surefire 与 Failsafe 均 0 failures、0 errors、0 skipped；日志明确启动 MySQL 8.4、固定 MinIO 镜像和 Toxiproxy，并完成三轮恢复演练。

- [ ] **Step 4: 检查活动树、安全字段和格式**

```powershell
git ls-files
git grep -n -E "(MINIO_SECRET_KEY=.{8,}|X-Amz-Signature|BEGIN (RSA|PRIVATE) KEY)" -- . ':!labs/06-file-service/.env.example'
git diff --check
```

Expected: 受跟踪文件只有 `.gitignore` 与 `labs/06-file-service/**`；秘密扫描无输出；`git diff --check` 无输出。

- [ ] **Step 5: 提交实验文档并记录验收提交**

```powershell
git add -- labs/06-file-service/README.md labs/06-file-service/TROUBLESHOOTING.md
git commit -m "docs: document secure file service lab"
git status --short --branch
git log -1 --oneline
```

Expected: 实验分支 clean，并记录最终提交哈希与实际 Surefire/Failsafe 测试数。

- [ ] **Step 6: 在 main 独立更新路线文档**

回到 main 工作树，仅修改：

```text
README.md：实验六改为已验收，加入 learning/secure-file-service/labs/06-file-service 入口和真实测试计数
notes/learning-log.md：记录事务边界、并发/故障证据、MinIO 残余 URL 窗口和排障结论
interview/question-bank.md：补充对象存储事务、去重隐私、ACL、预签名 URL、引用计数和幂等清理追问
```

验证并提交：

```powershell
git diff --check
git add -- README.md notes/learning-log.md interview/question-bank.md
git commit -m "docs: record verified secure file service lab"
```

Expected: main 不跟踪 `labs/`，实验代码仍只存在于 `learning/secure-file-service`。

## 计划自检映射

| 规格要求 | 实施任务 |
| --- | --- |
| 独立分支、固定依赖、六张表 | Task 1 |
| 类型、名称、状态和配置边界 | Task 2 |
| 流式 20 MiB、Tika/魔数、本地存储 | Task 3 |
| 会话、Blob token、原子终结 | Task 4 |
| 上传 A/B/C、并发去重、STAGING 恢复 | Task 5 |
| Header 双重门禁、UTF-8 上传 API | Task 6 |
| 私有 ACL、统一 404、原子删除 | Task 7 |
| `/content`、本地身份绑定 HMAC | Task 8 |
| MinIO、2 分钟预签名、Toxiproxy | Task 9 |
| 临时/Blob 清理、重试与 fencing | Task 10 |
| 审计、低基数指标、三轮故障演练 | Task 11 |
| README、排障、完整验收和 main 记录 | Task 12 |

## 官方参考

- Spring Boot 3.5.16 System Requirements: `https://docs.spring.io/spring-boot/3.5/system-requirements.html`
- Apache Tika 3.3.2: `https://tika.apache.org/3.3.2/`
- Apache Tika Detector API: `https://tika.apache.org/3.3.2/api/org/apache/tika/detect/Detector.html`
- MinIO Java SDK 8.6.0 artifact: `https://repo1.maven.org/maven2/io/minio/minio/8.6.0/`
- MinIO security release: `https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z`
