# 实验二排障复盘：Redis Cache Aside 与一致性

本文按“现象—原因—解决—预防”记录本实验中的环境、缓存、分布式锁与压测问题。密码、连接串等敏感值只应放在本地 `.env` 或环境变量中，不能提交到 Git。

## 阅读提示

- 反引号包围的内容是命令、环境变量、端口、异常文本或指标名。
- 在 VS Code 中打开本文件后按 `Ctrl+Shift+V` 可查看完整预览；在 GitHub 中打开本文件可直接阅读渲染版本。
- 下文的“现象—原因—解决—预防”分别对应：看到什么、为什么发生、如何操作、以后如何避免。

## 1. Maven 使用了错误的 JDK

**现象：** `java -version` 显示 JDK 25，或编译/测试结果与本实验基线不一致。

**原因：** Windows 的 PATH 或已打开的终端仍指向旧 JDK；项目的编译目标是 Java 17，但 Maven 实际使用的运行时取决于当前进程环境。

**解决：** 在每个 PowerShell 会话中先设置并确认：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
.\mvnw.cmd verify
```

**预防：** 所有手动验证和 CI 都固定 JDK 17；不要把 JDK 25 的结果写入验收证据。

## 2. Testcontainers 找不到 Docker named pipe

**现象：** `mvnw.cmd verify` 在集成测试阶段报 `docker_engine` 或 `dockerDesktopLinuxEngine` named pipe 不可访问。

**原因：** Docker Desktop 未启动、尚未完成初始化，或当前终端没有访问 Docker daemon 的权限；这不是 Redis/MySQL 断言失败。

**解决：** 启动 Docker Desktop 后确认：

```powershell
docker version
docker ps
.\mvnw.cmd verify
```

**预防：** 先确认 `docker ps` 正常，再运行 Failsafe/Testcontainers 集成测试；Docker 不可用时只把单元测试结果视为部分证据。

## 3. MySQL Testcontainer 报 JDBC Driver 类不存在

**现象：** 集成测试在创建 MySQL 容器前报 `ClassNotFoundException: com.mysql.jdbc.Driver` 或 `com.mysql.cj.jdbc.Driver`。

**原因：** `org.testcontainers:mysql` 负责容器生命周期，不携带 JDBC 驱动本身。

**解决：** 在 `pom.xml` 中保留由 Spring Boot BOM 管理版本的 `com.mysql:mysql-connector-j`，然后重新执行：

```powershell
.\mvnw.cmd verify
```

**预防：** 数据库容器测试除了 Testcontainers 模块，还要检查运行期 JDBC 驱动是否在测试类路径中。

## 4. PowerShell 运行指定集成测试时报 Unknown lifecycle phase

**现象：** 执行 `mvnw.cmd -Dit.test=RedisProductCacheIT verify` 后，Maven 报 `Unknown lifecycle phase ".test=RedisProductCacheIT"`。

**原因：** PowerShell 会把未正确传递的参数拆分，导致 Maven 收到错误的 lifecycle phase。

**解决：** 将整个 `-D` 参数用单引号包住：

```powershell
.\mvnw.cmd '-Dit.test=RedisProductCacheIT' verify
.\mvnw.cmd '-Dit.test=ProductQueryServiceRedissonRebuildLockIT' verify
```

**预防：** 在 PowerShell 中执行带点号的 Maven 系统属性时始终使用单引号。

## 5. Docker Compose 的 MySQL 端口冲突

**现象：** `docker compose up -d` 报 `ports are not available`，本机默认 MySQL 端口 `3308` 已被占用。

**原因：** 本地 MySQL 或其他实验容器已绑定同一宿主机端口。

**解决：** `3308` 是仓库默认值。先查看占用；仅当它冲突时，再显式设置一个空闲的 `MYSQL_PORT`，例如 3310：

```powershell
netstat -ano | findstr :3308
$env:MYSQL_PORT = '3310'
docker compose up -d
```

随后以同一个 `MYSQL_PORT` 启动应用。不要提交本地覆盖或 `.env`。

**预防：** 每次启动前执行 `docker compose config` 检查最终端口映射。

## 6. k6 已安装但 PowerShell 找不到命令

**现象：** `k6 version` 提示不是可识别的命令。

**原因：** Windows 的 PATH 没有包含 k6 安装目录，或当前 PowerShell 在安装前已打开。

**解决：** 使用绝对路径验证并运行脚本：

```powershell
& 'C:\Program Files\k6\k6.exe' version
& 'C:\Program Files\k6\k6.exe' run .\k6\hot-product.js
```

**预防：** 重开终端后再检查 PATH；README 中保留绝对路径作为 Windows 兜底命令。

## 7. 原生 Redis 锁不能直接 DEL

**现象：** 持锁请求耗时超过 3 秒后，旧请求的 `DEL` 删除了新请求已获得的锁。

**原因：** 原生锁的 TTL 到期后，其他请求可取得同一个锁键；不比对持有者 token 的 `DEL` 无法识别锁所有权。

**解决：** 使用每次获取时生成的 UUID token，并以 Lua 原子执行“值仍等于 token 才删除”。

**预防：** 原生 `SET NX PX` 必须配合唯一 token 与 compare-and-delete；固定 TTL 不能保证慢回源只发生一次。

## 8. 200ms 锁等待不能在截止时间后再次抢锁

**现象：** 锁等待刚好跨过截止时间时，竞争请求仍可能在超时后成功获取锁。

**原因：** 仅在失败后检查 deadline，`sleep` 返回后可能已经超过预算，但下一轮仍先执行了一次 `SET NX`。

**解决：** 使用 `System.nanoTime()` 计算单调 deadline，并在每一次 Redis 获取尝试**之前**检查是否超时。

**预防：** 为“sleep 恰好推进至 deadline”的边界写受控时钟单元测试；外部时间不用于计算等待预算。

## 最终验收证据

- JDK 17 + Docker Desktop 下，`mvnw.cmd verify` 覆盖 23 个单元测试与 19 个 Testcontainers 集成测试，均为 0 failures、0 errors；
- 两个独立 Redisson 客户端、100 个并发请求同一失效热点 Key，仓储仅回源 1 次；
- k6 v2.2.0 以 50 VU 持续 60 秒实测：574,675 请求、P95 7.18ms、失败率 0%、业务 checks 100%；
- 缓存写入、提交后失效、缓存重建锁和 HTTP 指标的手动命令均记录在 [README.md](README.md)。
