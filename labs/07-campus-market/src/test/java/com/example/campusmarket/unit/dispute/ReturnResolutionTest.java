package com.example.campusmarket.unit.dispute;

import com.example.campusmarket.dispute.domain.ReturnCase;
import com.example.campusmarket.dispute.domain.ReturnProofType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 可信退回的最小领域契约：买家上传证据不能直接触发退款。 */
class ReturnResolutionTest {
    @Test
    void buyerEvidenceAloneNeverTriggersRefund() {
        Instant t0 = Instant.parse("2026-09-01T00:00:00Z");
        Instant hardDeadline = t0.plusSeconds(14 * 24 * 3600L);
        ReturnCase returned = ReturnCase.open(3, 1, 100L, t0, hardDeadline);
        returned.recordProof(ReturnProofType.BUYER_EVIDENCE, "buyer-video");

        assertThat(returned.mayAutoRefundAt(hardDeadline.plusSeconds(1))).isFalse();
    }

    @Test
    void onlyThreePlatformProofsCanAutoRefundAtHardDeadline() {
        Instant t0 = Instant.parse("2026-09-01T00:00:00Z");
        Instant hardDeadline = t0.plusSeconds(14 * 24 * 3600L);
        for (ReturnProofType proof : new ReturnProofType[] {ReturnProofType.SELLER_CONFIRMED,
            ReturnProofType.PROVIDER_DELIVERED, ReturnProofType.ADMIN_CONFIRMED}) {
            ReturnCase returned = ReturnCase.open(3, 1, 100L, t0, hardDeadline);
            returned.recordProof(proof, proof.name());
            assertThat(returned.mayAutoRefundAt(hardDeadline.plusSeconds(1))).isTrue();
        }
    }

    @Test
    void refundIsExactlyUnitPriceTimesApprovedQuantity() {
        ReturnCase returned = ReturnCase.open(3, 1, 125L, Instant.EPOCH, Instant.EPOCH);
        assertThat(returned.refundAmountFen()).isEqualTo(125L);
    }
}
