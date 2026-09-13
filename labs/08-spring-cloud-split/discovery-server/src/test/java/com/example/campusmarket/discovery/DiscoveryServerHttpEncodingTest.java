package com.example.campusmarket.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = {DiscoveryServerApplication.class, DiscoveryServerHttpEncodingTest.TestEndpointConfiguration.class},
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "server.port=0")
@ActiveProfiles("test")
class DiscoveryServerHttpEncodingTest {
    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void actuatorHealthReturnsUtf8JsonWhenJsonIsAccepted() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = restTemplate.exchange(
            "/actuator/health", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(
            new MediaType("application", "json", StandardCharsets.UTF_8));
    }

    @Test
    void testJsonEndpointPreservesChineseTextAsUtf8() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = restTemplate.exchange(
            "/test/utf8", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(
            new MediaType("application", "json", StandardCharsets.UTF_8));
        assertThat(response.getBody()).isEqualTo("{\"message\":\"发现中心正常\"}");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestEndpointConfiguration {
        @Bean
        TestJsonEndpoint testJsonEndpoint() {
            return new TestJsonEndpoint();
        }
    }

    @RestController
    static class TestJsonEndpoint {
        @GetMapping(path = "/test/utf8", produces = MediaType.APPLICATION_JSON_VALUE)
        Map<String, String> response() {
            return Map.of("message", "发现中心正常");
        }
    }
}
