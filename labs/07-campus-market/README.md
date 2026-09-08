# 校园二手交易平台实验

这是一个 Spring Boot 模块化单体实验，演示校园邮箱注册、批量库存、一口价订单、模拟支付、当面交付、争议退货退款、评价和结算后的卖家质保义务。

## 模块边界

```text
身份/邮箱 ──> 商品/库存 ──> 订单 ──> 支付/退款 ──> 交付与争议
     │             │          │           │             │
     └─────────────┴──────────┴── Outbox ─┴── Inbox ────┘
                                  │
                 Elasticsearch（可重建） / MinIO（私有对象）
```

MySQL 是订单、库存、金额、截止时间和在售集合的事实源；Redis 只用于验证码和限流，不能参与正确性证明。搜索由 SmartCN Elasticsearch 投影，文件通过 MinIO 私有对象存储。CAS 仅保留 `ExternalIdentityProvider` 端口：校园邮箱验证码证明邮箱控制权，不代表学校正式身份认证，未获得校方授权前不接入 CAS。

## 快速开始

需要 JDK 17、Maven Wrapper 和 Docker Desktop。复制 `.env.example` 为 `.env` 并替换占位符后，可按需启动固定端口的 Compose 服务：

```powershell
docker compose up -d mysql redis rabbitmq elasticsearch minio toxiproxy
docker compose down
```

测试会使用隔离的 Testcontainers；不要用本机历史服务冒充测试容器。完整验收：

```powershell
.\mvnw.cmd test
.\mvnw.cmd clean verify
.\mvnw.cmd -Dit.test=CampusMarketJourneyIT verify
.\mvnw.cmd -Dit.test=RecoveryDrillIT verify
```

外部依赖不可用或测试被跳过都不算验收通过。

## API 示例

```http
POST /api/auth/email-verifications
Content-Type: application/json; charset=UTF-8

{"email":"buyer@stu.example.edu.cn"}
```

本地 `LocalVerificationMailSender` 仅保存最近验证码，模拟支付提供方位于 `/simulated-provider`。注册后使用 `/api/auth/login` 获取 15 分钟 JWT；发布商品、下单、交付、争议、质保和评价均需携带 `Authorization: Bearer <token>`。商品搜索为 `GET /api/search?keyword=Java&size=20`，游标使用返回的 `nextSearchAfter`。

所有金额是人民币整数分并以数据库 `BIGINT` 保存，禁止浮点数。订单只含一个发布项但数量可大于 1，库存使用带数量条件的更新。

## 订单和售后窗口

```text
PENDING_PAYMENT -> AWAITING_HANDOFF -> AWAITING_RECEIPT
       │                 │                    │
   CANCELLED       REFUNDING_CANCEL      AFTERSALE_WINDOW
                                              │
                                 REFUNDED / SETTLED
```

支付窗口 15 分钟，卖家交付 72 小时，买家确认 48 小时；确认时写入 `T0`。验收期 72 小时，试用期 7 天且包含验收期。第 7 天以后普通订单才能结算。退货支持部分数量：成功退款额与退款占额之和始终不超过实付金额，退回数量进入隔离库存。

卖家质保可选 30/90/180/365 天，且包含平台 7 天试用期；厂家质保是发布项提供的独立证明和到期时间，不会被卖家质保替代。订单结算后保持 `SETTLED`，质保案件独立流转。维修补偿或退货补偿会建立卖家义务；逾期会限制发布/提现，未来结算按唯一业务键抵扣，筹资完成后解除限制。

证据允许图片、PDF 和 MP4（图片 10 MiB、PDF 20 MiB、MP4 100 MiB），实际读取字节执行上限。证据按案件参与者 ACL 访问，管理员也不会自动绕过 ACL。真实支付、真实物流和正式 CAS 没有接入；支付网关保留签名、幂等和对账适配器契约。

## 搜索重建和故障演练

商品变更先写 MySQL 与搜索 Outbox；`SearchProjector` 使用外部版本，删除写 tombstone。在线重建流程是 RR 快照、高水位补放、写入门禁和原子别名切换；重建失败可由意图收敛器继续处理。

`RecoveryDrillIT` 明确执行三轮：RabbitMQ 断连后恢复 Outbox/Inbox，Elasticsearch 断连后恢复搜索投影，MinIO 断连后恢复清理任务。每轮均执行 dispatcher/projector/cleanup 并检查库存非负、返还业务键唯一、退款额度、单结算、租约、证据 ACL 和搜索最终一致。

更多故障症状与边界见 [TROUBLESHOOTING.md](TROUBLESHOOTING.md)。
