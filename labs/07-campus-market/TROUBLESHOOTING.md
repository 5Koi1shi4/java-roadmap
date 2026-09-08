# 排障手册

## Docker 和 SmartCN

先执行 `docker info`，确认引擎可用且可用内存至少 1 GiB，再执行单个 IT。不要把 skipped 当作通过。SmartCN 镜像由 `docker/elasticsearch/Dockerfile` 安装；若启动时报 analyzer 不存在，重建镜像并确认 `analysis-smartcn` 已安装，清理旧的同名索引后重试。

## RabbitMQ confirm 和 Outbox

发布必须拿到 publisher confirm；NACK、不可路由和超时会释放租约并按固定批量重试，三次后写入人工失败副本。检查 `integration_outbox.status/lease_until/claim_token`，恢复后运行 `OutboxDispatcher.dispatchOnce`。不要直接把状态改成 `PUBLISHED`，否则会丢失事件。

## Redis 验证码

Redis 不可用时验证码接口返回 503，不能绕过验证。检查 Redis URL、Lua 脚本和设备 Cookie；10 分钟发送/失败窗口分别受邮箱、IP、设备上限保护。验证码只在本地模拟邮箱中显示，不写入日志。

## MinIO 上传和清理

上传报 503 时先确认 MinIO endpoint、bucket 和 Toxiproxy 状态；对象 key 是随机值，不能从日志推断。恢复后运行 `StorageCleanupScheduler.runOnce(100)`，检查任务 lease/token 和 `storage_cleanup_task.status`。MP4 证据上限按实际读取字节为 100 MiB，不能仅依赖 multipart 元数据。

## 回调验签和 UNKNOWN 支付

回调需要时间戳、一次性 nonce、签名和固定 JSON 字段；签名覆盖原始 UTF-8 body。重放 nonce、过期时间、未知字段或金额不匹配会被拒绝。支付/退款出现 `UNKNOWN` 时返回 503 并保留原 provider reference 和幂等键，运行 `PaymentReconciliationScheduler.runOnce` 查询结果，绝不重新创建请求。

退款先占额再调用提供方；`successful_refund_fen + reserved_refund_fen` 不得超过同一成功支付的 `paid_amount_fen`。检查退款回调后是否清除占额、写入 Outbox，并再次运行退回收敛器完成隔离库存。

## 时间边界和竞态

所有窗口左闭右开，以 MySQL `CURRENT_TIMESTAMP(6)` 判断：支付 15 分钟、交付 72 小时、确认 48 小时、验收 72 小时、试用 7 天。三天内普通缺陷可争议，试用期内功能缺陷仍可争议；七天后订单才可结算。卖家质保 30/90/180/365 天并独立于已结算订单。并发测试应观察条件更新、订单行锁和 claim token，而不是依赖 Redis 锁。

## 卖家筹资和 ESCALATED

质保裁定会建立卖家义务并限制发布/提现。卖家 72 小时内可通过 `/api/seller/obligations/{id}/fund` 幂等筹资；逾期筹资失败时保留限制。未来结算只按唯一抵扣键扣除，不把订单退回 `PENDING`。管理员硬期限遇到缺少可信退回证明、证据冲突或支付失败时进入 `ESCALATED` 并冻结资金，不能默认判任一方胜诉；先补充可验证证据，再运行对应恢复任务。

## 常用验证

```powershell
.\mvnw.cmd test
.\mvnw.cmd -Dit.test=CampusMarketJourneyIT verify
.\mvnw.cmd -Dit.test=RecoveryDrillIT verify
git diff --check
```

测试中的每轮故障都应看到积压/503，恢复后无过期未决租约、库存非负、搜索与 MySQL 在售集合一致，且证据 ACL 没有放宽。

低内存环境只允许串行验收：

```powershell
.\mvnw.cmd -Dit.test=CampusMarketJourneyIT verify
.\mvnw.cmd -Dit.test=RecoveryDrillIT verify
```

第一条命令的容器集合固定为 MySQL、Redis、SmartCN Elasticsearch、MinIO，应用内 HTTP 支付 provider 不需要额外容器；第二条命令是三个嵌套轮次，依次为 MySQL+Redis+RabbitMQ+Toxiproxy、MySQL+Redis+Elasticsearch+Toxiproxy、MySQL+Redis+MinIO+Toxiproxy。每轮测试类结束会回收自己的容器，且所有轮次都不启动 SSHD、Python 或其他未参与该轮的外部系统。不要并行执行两条命令，也不要在它们之间保留上一轮的容器。
