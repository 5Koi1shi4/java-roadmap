# 实验二：Redis Cache Aside 与更新一致性

本实验以商品详情为例实现 Cache Aside，并把“更新数据库后删除缓存”限定为**数据库事务提交后**才执行。缓存键为 `product:v1:{id}`；正缓存与空值缓存都通过同一个 `evict(id)` 失效。

## 更新时序

```text
请求更新商品
  -> ProductUpdateService.updateProduct(command)
  -> ProductRepository.update(product)                 （仍在数据库事务内）
  -> 注册 TransactionSynchronization.afterCommit 回调
  -> 数据库事务提交成功
  -> ProductCache.evict(productId)
```

若 repository 更新抛异常或事务回滚，`afterCommit` 不会执行，旧缓存保留；读取方继续看到与未提交数据库状态一致的旧数据。

不能在更新数据库**之前**先删缓存：删除后到数据库提交前，其他请求可能发生缓存未命中并从数据库读回旧数据，再把旧值写入缓存。随后数据库即使成功提交，缓存仍可能是过期旧值；而数据库最终回滚时，预先删除还会无谓丢失原本正确的缓存。因此先更新数据库、只在提交完成后删除缓存，才把缓存失效与已提交状态对齐。

## 失败边界

缓存删除发生在 `afterCommit()`，因此 Redis 删除失败不能回滚已提交的数据库事务。`ProductUpdateServiceTest.keepsUpdatedProductWhenCacheEvictionFailsAfterCommit` 让 `ProductCache.evict` 抛出 `redis unavailable`，断言提交回调抛错后仓储中仍是更新后的 `Effective Java` 商品。这是“数据库已提交、缓存删除需走观测与重试”的单元测试证据，而不是分布式事务实现。

## 验证

需要 JDK 17、正在运行的 Docker Desktop，以及可拉取的 `mysql:8.4`、`redis:7.4-alpine` 镜像。在 PowerShell 中执行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\mvnw.cmd verify
```

2026-08-15 的复跑结果为 `BUILD SUCCESS`：Surefire 单元测试 10 个，`Failures: 0, Errors: 0, Skipped: 0`；Failsafe/Testcontainers 集成测试 5 个，`Failures: 0, Errors: 0, Skipped: 0`。集成测试启动 MySQL 8.4 与 Redis 7.4-alpine 容器，覆盖提交后删除缓存、回滚保留数据库与缓存、以及正/空值缓存删除。
