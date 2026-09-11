package com.example.campusmarket.unit.payment;

import com.example.campusmarket.identity.application.AuthenticatedUser;
import com.example.campusmarket.order.application.IdempotentCommandService;
import com.example.campusmarket.payment.api.SettlementController;
import com.example.campusmarket.payment.application.SettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.sql.ResultSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettlementControllerAuthorizationTest {
    @Test
    void administratorCannotSettleAnotherSellersOrder() {
        UUID seller = UUID.randomUUID();
        UUID administrator = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any(Object[].class)))
            .thenAnswer(invocation -> {
                var extractor = (org.springframework.jdbc.core.ResultSetExtractor<UUID>) invocation.getArgument(1);
                ResultSet rs = mock(ResultSet.class);
                when(rs.next()).thenReturn(true);
                when(rs.getString(1)).thenReturn(seller.toString());
                return extractor.extractData(rs);
            });
        SettlementController controller = new SettlementController(mock(SettlementService.class), jdbc,
            mock(IdempotentCommandService.class));

        var response = controller.settle(UUID.randomUUID(), "key",
            new TestingAuthenticationToken(new AuthenticatedUser(administrator, Set.of("ROLE_ADMIN")), null));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }
}
