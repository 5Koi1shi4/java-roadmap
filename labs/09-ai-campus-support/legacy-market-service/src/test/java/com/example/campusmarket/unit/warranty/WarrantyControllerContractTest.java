package com.example.campusmarket.unit.warranty;

import com.example.campusmarket.security.AuthenticatedUser;
import com.example.campusmarket.warranty.api.WarrantyController;
import com.example.campusmarket.warranty.application.WarrantyService;
import com.example.campusmarket.warranty.domain.WarrantyDecision;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WarrantyControllerContractTest {
    @Test
    void authenticatedBuyerReceivesJsonCaseIdAndStatus() {
        WarrantyService service = mock(WarrantyService.class);
        UUID caseId = UUID.randomUUID(), buyer = UUID.randomUUID(), order = UUID.randomUUID();
        when(service.openWarrantyCase(order, 1, "FUNCTIONAL_DEFECT", "key", buyer)).thenReturn(new WarrantyService.Result(caseId, "OPEN"));
        WarrantyController controller = new WarrantyController(service);
        var auth = new UsernamePasswordAuthenticationToken(new AuthenticatedUser(buyer, Set.of("ROLE_USER")), null);
        var response = controller.open(order, new WarrantyController.OpenRequest(1, "FUNCTIONAL_DEFECT"), "key", auth);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/json;charset=UTF-8");
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains(caseId.toString()).contains("OPEN");
    }

    @Test
    void nonAdminCannotDecideAndResponseIsJsonForbidden() {
        WarrantyController controller = new WarrantyController(mock(WarrantyService.class));
        var auth = new UsernamePasswordAuthenticationToken(new AuthenticatedUser(UUID.randomUUID(), Set.of("ROLE_USER")), null);
        var response = controller.decide(UUID.randomUUID(), new WarrantyController.DecisionRequest(WarrantyDecision.REJECT, 0, null), "key", auth);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/json;charset=UTF-8");
    }
}
