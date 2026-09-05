package com.example.campusmarket.order.application;

import com.example.campusmarket.order.infrastructure.JdbcOrderLifecycleRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 订单截止任务的有界领取器；owner/token/lease 由 MySQL 负责 fencing。 */
@Component
@Profile("!test")
@EnableScheduling
public final class DeadlineScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(DeadlineScheduler.class);
    private final JdbcOrderLifecycleRepository repository;
    private final OrderLifecycleService lifecycle;
    private final String owner = "order-deadline-" + UUID.randomUUID();

    public DeadlineScheduler(JdbcOrderLifecycleRepository repository, OrderLifecycleService lifecycle) {
        this.repository = Objects.requireNonNull(repository, "订单截止仓储不能为空");
        this.lifecycle = Objects.requireNonNull(lifecycle, "订单生命周期服务不能为空");
    }

    @Scheduled(initialDelayString = "${campus.market.order.deadline.initial-delay-ms:0}", fixedDelayString = "${campus.market.order.deadline.fixed-delay-ms:1000}")
    public void dispatch() { runOnce(50); }

    public int runOnce(int limit) {
        if (limit <= 0 || limit > 1000) throw new IllegalArgumentException("截止任务批量大小必须在1到1000之间");
        int processed = 0;
        for (var claim : repository.claimBatch(owner, limit, Duration.ofSeconds(30))) {
            try {
                dispatchClaim(claim);
                processed++;
            } catch (RuntimeException failure) {
                try {
                    repository.retryOrFailClaim(claim, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
                } catch (RuntimeException fencingFailure) {
                    LOG.warn("截止任务失败事实落库失败，等待租约接管 orderId={} claimId={} failureClass={}",
                        claim.orderId(), claim.id(), fencingFailure.getClass().getSimpleName());
                }
            }
        }
        return processed;
    }

    /** 供对账/故障测试按 ID 可靠触发，避免全库 due 行占用批次。 */
    public int runOne(UUID orderId) {
        Objects.requireNonNull(orderId, "订单ID不能为空");
        var claim = repository.claimOne(orderId, owner, Duration.ofSeconds(30));
        if (claim == null) return 0;
        try { dispatchClaim(claim); }
        catch (RuntimeException failure) {
            repository.retryOrFailClaim(claim, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }
        return 1;
    }

    private void dispatchClaim(JdbcOrderLifecycleRepository.DeadlineClaim claim) {
        switch (claim.type()) {
            case "PAYMENT" -> lifecycle.expirePayment(claim.orderId(), claim);
            case "HANDOFF" -> lifecycle.expireHandoff(claim.orderId(), claim);
            case "RECEIPT" -> lifecycle.autoConfirmReceipt(claim.orderId(), claim);
            case "TRIAL" -> lifecycle.closeTrial(claim.orderId(), claim);
            default -> throw new IllegalArgumentException("未知截止任务类型: " + claim.type());
        }
    }
}
