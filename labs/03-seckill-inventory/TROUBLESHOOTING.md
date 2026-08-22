# 实验三排障记录

## 幂等键重试与 5xx

重试必须复用原 `Idempotency-Key` 和完全相同的 JSON；成功请求会重放原始 UTF-8 JSON。数据库异常返回 5xx 时，幂等记录随事务回滚，修复故障后可以安全重试。

## Docker 或 Testcontainers 启动失败

`mvnw.cmd verify` 中的 `*IT` 由 Failsafe 执行，并需要 Docker Desktop Engine。先运行 `docker version` 和 `docker info` 确认引擎可访问；Windows named pipe 权限错误时，重启 Docker Desktop，或在已授权宿主 Docker 权限下重跑命令。不要把 Docker 环境错误误判为 Java 业务失败。只启动单元测试不能替代集成验收。

Compose 启动后用 `docker compose ps` 查看 MySQL 健康状态；端口冲突时修改本实验 `.env` 的 `MYSQL_PORT`，并同步 `DB_URL`。Compose 的 `.env` 仅在本机保存，不能写入 Git。

## 唯一索引冲突

`seckill_order` 的 `uk_seckill_order_user_product` 限制同一用户和商品只能有一条订单。并发请求可能先后都扣库存，但后到请求插入订单时触发唯一索引冲突；服务捕获 `DuplicateKeyException` 转为 `ALREADY_PURCHASED`。由于下单方法带 `@Transactional`，异常转换后事务回滚，冲突请求的库存扣减也会撤销。可从 IT 观察 `stock + orderCount` 保持初始库存，且重复下单后库存不减少。

## 售罄与超卖观察

库存扣减使用 `stock > 0` 的条件更新，不先读后写。并发请求中只有库存数量个请求获得受影响行，其他请求收到 `SOLD_OUT`；数据库约束 `stock >= 0` 作为额外防线。若看到库存为负数，应先检查实际运行的 SQL、是否绕过仓储层直接写表，以及是否连接到了错误数据库。

## 事务回滚观察

重复购买测试会先成功下单，再把库存恢复为 3，随后重复请求。预期第二次返回 `409 ALREADY_PURCHASED`、订单数仍为 1、库存仍为 3。这同时证明唯一索引拒绝重复订单和事务撤销扣库存是两个相互配合的保证。
