package com.example.campusmarket.product.security;

import com.example.campusmarket.product.infrastructure.JdbcProductReadinessRepository;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/** 投影 checkpoint 未完成或 index 待办未清空时 readiness 必须保持 DOWN。 */
class ProductProjectionReadinessHealthIndicatorTest {
    private static final UUID REPLAY_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private final JdbcProductReadinessRepository repository = mock(JdbcProductReadinessRepository.class);
    private final ProductProjectionReadinessHealthIndicator indicator =
        new ProductProjectionReadinessHealthIndicator(repository);

    @Test
    void waitingCheckpointIsDown() {
        when(repository.inspect()).thenReturn(status("WAITING", null, 0, 0, 0));

        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DOWN");
    }

    @Test
    void catchingUpWithPendingIndexIsDown() {
        when(repository.inspect()).thenReturn(status("CATCHING_UP", REPLAY_ID, 4, 2, 1));

        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DOWN");
    }

    @Test
    void readyCheckpointWithNoPendingIndexIsUp() {
        when(repository.inspect()).thenReturn(status("READY", REPLAY_ID, 4, 2, 0));

        assertThat(indicator.health().getStatus().getCode()).isEqualTo("UP");
        assertThat(indicator.health().getDetails()).containsEntry("state", "READY");
    }

    @Test
    void readyCheckpointWithProductDeadLettersIsDown() {
        when(repository.inspect()).thenReturn(status("READY", REPLAY_ID, 4, 2, 0));
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        when(rabbit.execute(any(ChannelCallback.class))).thenReturn(2);

        ProductProjectionReadinessHealthIndicator withRabbit =
            new ProductProjectionReadinessHealthIndicator(repository, rabbit);

        assertThat(withRabbit.health().getStatus().getCode()).isEqualTo("DOWN");
        assertThat(withRabbit.health().getDetails()).containsEntry("manualFailureCount", 2);
    }

    @Test
    void blockedCheckpointIsDownEvenWhenMarkerWasObserved() {
        when(repository.inspect()).thenReturn(status("BLOCKED", REPLAY_ID, 4, 2, 0));

        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DOWN");
    }

    @Test
    void repositoryFailureIsDownWithoutLeakingException() {
        when(repository.inspect()).thenThrow(new IllegalStateException("database unavailable"));

        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DOWN");
        assertThat(indicator.health().getDetails()).doesNotContainKey("exception");
    }

    private static JdbcProductReadinessRepository.ReadinessStatus status(
        String state, UUID replayId, long sourceHighWatermark, long indexHighWatermark,
        long pendingIndexCount) {
        return new JdbcProductReadinessRepository.ReadinessStatus(
            state, replayId, sourceHighWatermark, indexHighWatermark, pendingIndexCount);
    }
}
