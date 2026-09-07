package com.example.campusmarket.unit.review;

import com.example.campusmarket.observability.AuditRecorder;
import com.example.campusmarket.observability.CampusMetrics;
import com.example.campusmarket.review.ReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ReviewServiceTest {
    private final ReviewService service = new ReviewService(mock(JdbcTemplate.class),
        mock(PlatformTransactionManager.class), mock(AuditRecorder.class), mock(CampusMetrics.class));

    @Test
    void rejectsScoresOutsideThePublicOneToFiveRange() {
        assertThatThrownBy(() -> service.create(UUID.randomUUID(), UUID.randomUUID(), 0, "内容"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(UUID.randomUUID(), UUID.randomUUID(), 6, "内容"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankAndOverlongReviewText() {
        assertThatThrownBy(() -> service.create(UUID.randomUUID(), UUID.randomUUID(), 5, "  "))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(UUID.randomUUID(), UUID.randomUUID(), 5, "字".repeat(2001)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
