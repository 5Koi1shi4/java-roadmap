package com.example.campusmarket.unit.dispute;

import com.example.campusmarket.dispute.api.HandoffController;
import com.example.campusmarket.dispute.application.HandoffService;
import com.example.campusmarket.identity.application.AuthenticatedUser;
import com.example.campusmarket.order.application.IdempotentCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HandoffControllerTest {
    @Test
    void invalidIdempotencyKeyIsAUtf8BadRequestWithoutExecutingCommand() {
        HandoffService service = mock(HandoffService.class);
        HandoffController controller = new HandoffController(service);
        var authentication = new UsernamePasswordAuthenticationToken(
            new AuthenticatedUser(UUID.randomUUID(), Set.of("ROLE_USER")), null);

        var response = controller.handoff(UUID.randomUUID(), new HandoffController.HandoffRequest("note"), "", authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/json;charset=UTF-8");
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("参数");
    }

    @Test
    void idempotencyDigestConflictIsMappedToUtf8Conflict() {
        HandoffService service = mock(HandoffService.class);
        when(service.handoff(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenThrow(new IdempotentCommandService.IdempotencyConflictException());
        HandoffController controller = new HandoffController(service);
        var authentication = new UsernamePasswordAuthenticationToken(
            new AuthenticatedUser(UUID.randomUUID(), Set.of("ROLE_USER")), null);

        var response = controller.handoff(UUID.randomUUID(), new HandoffController.HandoffRequest("note"), "key", authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/json;charset=UTF-8");
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("幂等");
    }

    @Test
    void receiptWithMissingIdempotencyKeyIsAlsoBadRequest() {
        HandoffController controller = new HandoffController(mock(HandoffService.class));
        var authentication = new UsernamePasswordAuthenticationToken(
            new AuthenticatedUser(UUID.randomUUID(), Set.of("ROLE_USER")), null);
        var response = controller.receipt(UUID.randomUUID(), new HandoffController.ReceiptRequest(), "", authentication);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("幂等");
    }
}
