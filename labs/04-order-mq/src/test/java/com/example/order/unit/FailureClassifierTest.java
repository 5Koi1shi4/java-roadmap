package com.example.order.unit;

import com.example.order.application.InvalidOrderEventException;
import com.example.order.infrastructure.mq.FailureClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;

import static com.example.order.infrastructure.mq.FailureClassifier.NON_RETRYABLE;
import static com.example.order.infrastructure.mq.FailureClassifier.RETRYABLE;
import static org.assertj.core.api.Assertions.assertThat;

class FailureClassifierTest {
    private final FailureClassifier classifier = new FailureClassifier();

    @Test
    void classifiesBrokenJsonAndUnsupportedVersionAsNonRetryable() {
        assertThat(classifier.classify(new InvalidOrderEventException("version"))).isEqualTo(NON_RETRYABLE);
    }

    @Test
    void classifiesTransientDatabaseConnectionAsRetryable() {
        assertThat(classifier.classify(new TransientDataAccessResourceException("db down"))).isEqualTo(RETRYABLE);
    }
}
