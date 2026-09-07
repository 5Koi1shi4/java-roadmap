package com.example.campusmarket.integration;

import com.example.campusmarket.observability.CampusMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = MetricsIT.MetricsTestConfiguration.class)
class MetricsIT {
    @Autowired CampusMetrics metrics;
    @Autowired MeterRegistry registry;

    @Test
    void allCampusMetricsUseOnlyLowCardinalityTags() {
        metrics.recordReview("SUCCESS");
        metrics.recordOrderState("SETTLED");
        metrics.recordWarrantyState("OPEN");
        metrics.recordObligationState("AWAITING_FUNDING");
        metrics.recordObligationState("PARTIALLY_FUNDED");
        metrics.recordPaymentCallback("RECEIVED");
        metrics.recordVerification("VERIFIED");
        metrics.recordOperationDuration("READ", Duration.ofMillis(1));
        metrics.setRestrictedSellers(2);

        Set<String> forbidden = Set.of("userId", "listingId", "orderId", "disputeId", "email",
            "providerReference", "objectKey", "exception", "correlationId");
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
            .noneMatch(tag -> forbidden.contains(tag.getKey())));
        assertThat(registry.find("campus.market.review.total").counter().count()).isEqualTo(1);
        assertThat(registry.get("campus.market.seller.obligation.state").tag("state", "PARTIALLY_FUNDED").counter().count()).isEqualTo(1);
        assertThat(registry.get("campus.market.payment.callback.total").tag("result", "RECEIVED").counter().count()).isEqualTo(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MetricsTestConfiguration {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        CampusMetrics campusMetrics(MeterRegistry registry) {
            return new CampusMetrics(registry);
        }
    }
}
