# 任务 4 验证报告

## 验证范围

- 使用 JDK 17、真实 Testcontainers MySQL 8.4 和随机端口 Spring Boot HTTP 服务。
- 新增 `SeckillOrderHttpIT`：库存为 3 时 5 个不同用户并发恰好 3 个 201；同一用户 4 个并发请求恰好 1 个 201；重复购买异常回滚库存。
- 每个场景清空订单并重置商品 1 库存；断言订单数、库存及 `initialStock == stock + orderCount` 不变量。
- 断言 HTTP JSON 响应为 UTF-8，并断言重复购买返回 409 `ALREADY_PURCHASED`。
- `SeckillSchemaIT` 额外验证种子库存为 10；Maven Failsafe 配置确保 `verify` 执行全部 `*IT`。

## 测试证据

1. 受限沙箱首次执行 Testcontainers 因 Windows Docker named pipe `AccessDeniedException` 失败；未将该环境错误判定为代码失败。
2. 获准宿主 Docker 权限后，先运行目标集成测试：3 tests passed。
3. JDK 17 执行 `mvnw.cmd verify`：单元测试 7 passed；Failsafe 集成测试 4 passed（HTTP 3 + schema 1）；构建成功。

## 疑虑

- Maven 输出包含既有 `@MockBean` 弃用警告，不影响本任务测试结果。
- Flyway 对 MySQL 8.4 有版本支持提示警告，不影响迁移和测试结果。
