package com.example.campusmarket.dispute.application;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 物流提供方证明的受控落库边界；业务查询只接受该表中的已验证事实。 */
@Service
@Profile("!test")
public final class ReturnProofAttestationService {
    private final JdbcTemplate jdbc;

    public ReturnProofAttestationService(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
    }

    public void recordDelivered(UUID orderId, String provider, String proofReference, int deliveredQuantity, Instant verifiedAt) {
        Objects.requireNonNull(orderId, "订单ID不能为空");
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("物流提供方不能为空");
        if (proofReference == null || proofReference.isBlank()) throw new IllegalArgumentException("物流证明引用不能为空");
        if (deliveredQuantity <= 0) throw new IllegalArgumentException("签收数量必须为正数");
        Objects.requireNonNull(verifiedAt, "验证时间不能为空");
        if (verifiedAt.isAfter(Instant.now())) throw new IllegalArgumentException("验证时间不能在未来");
        jdbc.update("INSERT INTO return_proof_attestation (id,proof_reference,order_id,provider,delivered_quantity,status,verified_at,created_at) VALUES (?,?,?,?,?,'DELIVERED',?,CURRENT_TIMESTAMP(6))",
            UUID.randomUUID().toString(), proofReference, orderId.toString(), provider, deliveredQuantity,
            java.sql.Timestamp.from(verifiedAt));
    }
}
