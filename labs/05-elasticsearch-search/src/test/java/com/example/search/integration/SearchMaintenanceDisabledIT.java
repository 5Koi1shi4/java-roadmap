package com.example.search.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "search.maintenance.enabled=false")
class SearchMaintenanceDisabledIT extends SharedSearchContainers {
    @Autowired TestRestTemplate rest;

    @Test
    void maintenanceRoutesAreAbsentWhenDisabled() {
        assertThat(rest.postForEntity("/api/admin/search/consistency-checks", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.postForEntity("/api/admin/search/products/1/repair", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
