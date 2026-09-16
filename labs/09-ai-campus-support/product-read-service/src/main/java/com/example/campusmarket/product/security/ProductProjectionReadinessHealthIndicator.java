package com.example.campusmarket.product.security;

import com.example.campusmarket.product.infrastructure.JdbcProductReadinessRepository;
import com.example.campusmarket.product.event.ProductRabbitTopology;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 只有 replay 屏障已提交且屏障前 index outbox 已清空时，投影才可对外宣称就绪。 */
@Component("projection")
public final class ProductProjectionReadinessHealthIndicator implements HealthIndicator {
    private final JdbcProductReadinessRepository repository;
    private final RabbitTemplate rabbitTemplate;

    /** 保留无 Rabbit 单元测试构造路径；生产 bean 使用带 RabbitTemplate 的构造器。 */
    public ProductProjectionReadinessHealthIndicator(JdbcProductReadinessRepository repository) {
        this(repository, null);
    }

    @Autowired
    public ProductProjectionReadinessHealthIndicator(JdbcProductReadinessRepository repository,
                                                     RabbitTemplate rabbitTemplate) {
        this.repository = Objects.requireNonNull(repository, "投影 readiness 仓储不能为空");
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public Health health() {
        try {
            JdbcProductReadinessRepository.ReadinessStatus status =
                Objects.requireNonNull(repository.inspect(), "投影 readiness 状态为空");
            Health.Builder health = Health.down();
            if (isReady(status)) {
                int manualFailureCount = manualFailureCount();
                health = manualFailureCount == 0 ? Health.up() : Health.down();
                health.withDetail("manualFailureCount", manualFailureCount);
            }
            return health.withDetail("state", status.state())
                .withDetail("sourceHighWatermark", status.sourceHighWatermark())
                .withDetail("indexHighWatermark", status.indexHighWatermark())
                .withDetail("pendingIndexCount", status.pendingIndexCount())
                .build();
        } catch (RuntimeException unavailable) {
            // readiness 响应只表达不可用，不把数据库异常类或消息泄露给调用方。
            return Health.down().withDetail("state", "UNAVAILABLE").build();
        }
    }

    private static boolean isReady(JdbcProductReadinessRepository.ReadinessStatus status) {
        return "READY".equals(status.state()) && status.pendingIndexCount() == 0;
    }

    private int manualFailureCount() {
        if (rabbitTemplate == null) {
            return 0;
        }
        Integer count = rabbitTemplate.execute(channel ->
            channel.queueDeclarePassive(ProductRabbitTopology.MANUAL_QUEUE).getMessageCount());
        if (count == null || count < 0) {
            throw new IllegalStateException("商品失败队列深度无效");
        }
        return count;
    }
}
