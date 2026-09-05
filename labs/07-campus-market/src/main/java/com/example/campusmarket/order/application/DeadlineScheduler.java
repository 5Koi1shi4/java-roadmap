package com.example.campusmarket.order.application;

import com.example.campusmarket.order.infrastructure.JdbcOrderLifecycleRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** 订单截止任务的有界领取器；owner/token/lease 由 MySQL 负责 fencing。 */
@Component
@Profile("!test")
@EnableScheduling
public final class DeadlineScheduler {
    private final JdbcOrderLifecycleRepository repository;
    private final OrderLifecycleService lifecycle;
    private final String owner = "order-deadline-" + UUID.randomUUID();

    public DeadlineScheduler(JdbcOrderLifecycleRepository repository, OrderLifecycleService lifecycle) {
        this.repository = Objects.requireNonNull(repository, "订单截止仓储不能为空");
        this.lifecycle = Objects.requireNonNull(lifecycle, "订单生命周期服务不能为空");
    }

    @Scheduled(fixedDelayString = "${campus.market.order.deadline.fixed-delay-ms:1000}")
    public void dispatch() { runOnce(50); }

    public int runOnce(int limit) {
        if (limit <= 0 || limit > 1000) throw new IllegalArgumentException("截止任务批量大小必须在1到1000之间");
        int processed = 0;
        for (var claim : repository.claimBatch(owner, limit, Duration.ofSeconds(30))) {
            try {
                boolean handled = switch (claim.type()) {
                    case "PAYMENT" -> lifecycle.expirePayment(claim.orderId(), claim);
                    case "HANDOFF" -> lifecycle.expireHandoff(claim.orderId(), claim);
                    case "RECEIPT" -> lifecycle.autoConfirmReceipt(claim.orderId(), claim);
                    case "TRIAL" -> lifecycle.closeTrial(claim.orderId(), claim);
                    default -> false;
                };
                processed++;
            } catch (RuntimeException ignored) {
                // 租约到期后可由下一 owner 接管；错误不会让同批其他订单饥饿。
            }
        }
        return processed;
    }
}
