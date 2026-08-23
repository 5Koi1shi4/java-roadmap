# Reference Repositories

参考仓库只用于定向阅读，不作为个人项目代码基线。每次阅读前先完成自己的最小设计。

## 首批仓库

### macrozheng/mall

- 地址：https://github.com/macrozheng/mall
- 阅读目标：业务分层、订单、Redis、RabbitMQ、Elasticsearch。
- 状态：已浅克隆，基线提交 `0504e86`。

### YunaiV/ruoyi-vue-pro

- 地址：https://github.com/YunaiV/ruoyi-vue-pro
- 阅读目标：Spring Security、RBAC、数据权限、审计日志。
- 状态：已浅克隆，基线提交 `0418084`。

### 2026-08-23 / macrozheng/mall / 订单超时与消息幂等

- 阅读前自己的设计：订单超时由事务内 Outbox 产生全局唯一 `eventId`，RabbitMQ TTL 队列到期后经 DLX 进入取消消费者；消费者用数据库唯一键、租约和订单状态条件更新实现重复消息安全。
- 参考点：mall 的订单状态与超时处理适合观察业务状态如何驱动取消；消息消费需要把“收到消息”和“业务事务完成”分开，避免在事务提交前确认消息。阅读重点是订单超时、库存释放和消息幂等的边界，而不是直接复制其实现。
- 参考项目的实现方式：参考实现将订单、支付/超时状态和消息/定时任务放在较完整的业务系统中，通常结合 Redis、定时扫描或消息组件完成异步流程。
- 自己的实现方式：本实验只保留订单状态机、MySQL 条件更新、事务内 Outbox 和 RabbitMQ TTL/DLX；声明 10s、1m、5m 同 TTL 桶，生产默认 1m；消费记录以 `event_id` 唯一键和 `PROCESSING/COMPLETED` 租约去重，异常按可恢复/不可恢复分类并最多三次尝试后进入人工队列。
- 差异原因：本实验是可重复验证的学习闭环，不引入延迟插件、分布式事务或跨服务调用。固定 TTL 桶用于明确队头阻塞边界；Outbox 与 publisher confirm 分离数据库提交和 broker 可达性；数据库唯一键加事务保证重复超时不会重复释放库存。
- 可以验证的测试：`OrderPersistenceIT.persistsOrderAndTimeoutEventAtomically`、`OrderTimeoutIT.duplicateEventIdCancelsAndReleasesStockOnlyOnce`、`ReliableMessagingFlowIT.retryableTimeoutFailureReachesManualQueueAfterThreeAttempts`、`OutboxLeaseIT.expiredPublishingEventCanBeClaimedByNextDispatcher` 和 `RabbitTopologyConfigurationTest.cancelQueueUsesQuorumDeliveryLimitAndManualDeadLetterRoute`。

## 阅读记录模板

### 日期 / 仓库 / 模块

- 阅读前自己的设计：
- 参考点：
- 参考项目的实现方式：
- 自己的实现方式：
- 差异原因：
- 可以验证的测试：
