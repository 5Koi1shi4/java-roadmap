package com.example.campusmarket.unit.dispute;

import com.example.campusmarket.dispute.domain.DisputeCase;
import com.example.campusmarket.dispute.domain.DisputeDecision;
import com.example.campusmarket.dispute.domain.DisputeReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisputeCaseTest {
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID BUYER = UUID.randomUUID();
    private static final UUID SELLER = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void cumulativeApprovedQuantityCannotExceedPurchasedQuantity() {
        DisputeCase first = DisputeCase.open(UUID.randomUUID(), ORDER, BUYER, SELLER, 3, 2,
            DisputeReason.FUNCTIONAL_DEFECT, T0, T0.plusSeconds(1));
        first.decide(DisputeDecision.REFUND_ONLY, 2);

        assertThatThrownBy(() -> DisputeCase.ensureAvailableQuantity(3, java.util.List.of(first), 2))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void earlyAndLateReasonsUseLeftClosedRightOpenWindows() {
        assertThat(DisputeCase.isReasonAllowed(DisputeReason.APPEARANCE, T0, T0.plusSeconds(72 * 3600L - 1))).isTrue();
        assertThat(DisputeCase.isReasonAllowed(DisputeReason.APPEARANCE, T0, T0.plusSeconds(72 * 3600L))).isFalse();
        assertThat(DisputeCase.isReasonAllowed(DisputeReason.FUNCTIONAL_DEFECT, T0, T0.plusSeconds(7 * 86400L - 1))).isTrue();
        assertThat(DisputeCase.isReasonAllowed(DisputeReason.FUNCTIONAL_DEFECT, T0, T0.plusSeconds(7 * 86400L))).isFalse();
    }

    @Test
    void exclusionReasonsAreFixedAndCannotBeDecidedAsSellerLiability() {
        DisputeCase caseFile = DisputeCase.open(UUID.randomUUID(), ORDER, BUYER, SELLER, 1, 1,
            DisputeReason.WATER_DAMAGE, T0, T0.plusSeconds(1));
        assertThat(caseFile.reason().isExclusion()).isTrue();
        assertThatThrownBy(() -> caseFile.decide(DisputeDecision.REFUND_ONLY, 1))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void administratorCanChooseOnlyFixedDecisionAndApprovedQuantity() {
        DisputeCase caseFile = DisputeCase.open(UUID.randomUUID(), ORDER, BUYER, SELLER, 3, 2,
            DisputeReason.QUANTITY, T0, T0.plusSeconds(1));
        DisputeCase.Decision result = caseFile.decide(DisputeDecision.RETURN_AND_REFUND, 1);
        assertThat(result.decision()).isEqualTo(DisputeDecision.RETURN_AND_REFUND);
        assertThat(result.approvedQuantity()).isEqualTo(1);
        assertThat(caseFile.isResolved()).isTrue();
    }

    @Test
    void rejectsNonPositiveQuantityAndUnknownReason() {
        assertThatThrownBy(() -> DisputeCase.open(UUID.randomUUID(), ORDER, BUYER, SELLER, 1, 0,
            DisputeReason.QUANTITY, T0, T0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DisputeReason.valueOf("not-a-reason"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
