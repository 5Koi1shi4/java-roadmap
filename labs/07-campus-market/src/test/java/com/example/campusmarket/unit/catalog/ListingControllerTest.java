package com.example.campusmarket.unit.catalog;

import com.example.campusmarket.catalog.api.ListingController;
import com.example.campusmarket.catalog.application.ListingService;
import com.example.campusmarket.identity.application.AuthenticatedUser;
import com.example.campusmarket.storage.PrivateObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;
import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class ListingControllerTest {
    @Mock private ListingService service;
    @Mock private PrivateObjectStorage storage;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new ListingController(service, storage)).build();
    }

    @Test
    void restrictedPublishReturnsConflict() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID listing = UUID.randomUUID();
        when(service.publish(seller, listing)).thenThrow(new ListingService.RestrictionException());

        mvc.perform(post("/api/listings/{listingId}/publish", listing)
                .principal(new TestingAuthenticationToken(
                    new AuthenticatedUser(seller, Set.of("ROLE_USER")), null)))
            .andExpect(status().isConflict());
    }

    @Test
    void createResponseDoesNotExposeManufacturerWarrantyProofDigest() throws Exception {
        UUID seller = UUID.randomUUID();
        UUID listing = UUID.randomUUID();
        var saved = com.example.campusmarket.catalog.domain.Listing.reconstitute(listing, seller, "键盘", "二手键盘", "电子",
            com.example.campusmarket.shared.Money.ofFen(1000), 1, 0,
            com.example.campusmarket.catalog.domain.WarrantyTerm.none(), "sha256-secret-proof",
            Instant.parse("2030-01-01T00:00:00Z"), com.example.campusmarket.catalog.domain.ListingStatus.DRAFT, 1, Set.of());
        when(service.createDraft(org.mockito.ArgumentMatchers.eq(seller), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any())).thenReturn(saved);

        mvc.perform(post("/api/listings").contentType("application/json")
                .content("{\"title\":\"键盘\",\"description\":\"二手键盘\",\"category\":\"电子\",\"unitPriceFen\":1000,\"availableQuantity\":1,\"manufacturerWarrantyProofSnapshot\":\"sha256-secret-proof\",\"manufacturerWarrantyExpiresAt\":\"2030-01-01T00:00:00Z\"}")
                .principal(new TestingAuthenticationToken(new AuthenticatedUser(seller, Set.of("ROLE_USER")), null)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.manufacturerWarrantyProofSnapshot").doesNotExist());
    }
}
