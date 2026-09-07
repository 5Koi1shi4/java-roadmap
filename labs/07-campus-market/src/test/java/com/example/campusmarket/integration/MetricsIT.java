package com.example.campusmarket.integration;

import com.example.campusmarket.CampusMarketApplication;
import com.example.campusmarket.observability.CampusMetrics;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = CampusMarketApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.task.scheduling.enabled=false")
class MetricsIT {
    @Autowired CampusMetrics metrics;
    @Autowired MeterRegistry registry;

    @Test
    void allCampusMetricsUseOnlyLowCardinalityTags() {
        metrics.recordReview("SUCCESS");
        metrics.recordOrderState("SETTLED");
        metrics.recordWarrantyState("OPEN");
        metrics.recordObligationState("AWAITING_FUNDING");
        metrics.recordOperationDuration("READ", Duration.ofMillis(1));
        metrics.setRestrictedSellers(2);

        Set<String> forbidden = Set.of("userId", "listingId", "orderId", "disputeId", "email",
            "providerReference", "objectKey", "exception", "correlationId");
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
            .noneMatch(tag -> forbidden.contains(tag.getKey())));
        assertThat(registry.find("campus.market.review.total").counter().count()).isEqualTo(1);
    }
}
