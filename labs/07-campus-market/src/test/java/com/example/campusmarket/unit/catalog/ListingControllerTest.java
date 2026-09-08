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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
}
