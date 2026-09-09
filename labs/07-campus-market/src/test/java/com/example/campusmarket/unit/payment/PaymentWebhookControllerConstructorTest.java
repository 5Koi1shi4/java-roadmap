package com.example.campusmarket.unit.payment;

import com.example.campusmarket.observability.CampusMetrics;
import com.example.campusmarket.payment.api.PaymentWebhookController;
import com.example.campusmarket.payment.application.PaymentGateway;
import com.example.campusmarket.payment.application.PaymentService;
import com.example.campusmarket.payment.application.RefundService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class PaymentWebhookControllerConstructorTest {
    @Test
    void retainsFiveArgumentPublicConstructor() {
        assertThatCode(() -> PaymentWebhookController.class
            .getConstructor(PaymentGateway.class, PaymentService.class, RefundService.class, ObjectMapper.class, CampusMetrics.class))
            .doesNotThrowAnyException();
    }

    @Test
    void fiveArgumentConstructorRemainsUsable() {
        assertThatCode(() -> PaymentWebhookController.class
            .getConstructor(PaymentGateway.class, PaymentService.class, RefundService.class, ObjectMapper.class, CampusMetrics.class)
            .newInstance(mock(PaymentGateway.class), mock(PaymentService.class), mock(RefundService.class),
                new ObjectMapper(), mock(CampusMetrics.class)))
            .doesNotThrowAnyException();
    }
}
