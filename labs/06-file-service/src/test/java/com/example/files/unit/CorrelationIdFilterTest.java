package com.example.files.unit;

import com.example.files.api.CorrelationIdFilter;
import com.example.files.application.audit.CorrelationId;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {
    @Test
    void createsFreshBase64Url128BitIdAndIgnoresClientOverride() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest first = requestWith("attacker-id");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, new MockFilterChain());
        MockHttpServletRequest second = requestWith("attacker-id");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, new MockFilterChain());

        CorrelationId firstCorrelation = (CorrelationId) first.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        CorrelationId secondCorrelation = (CorrelationId) second.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        String firstId = firstCorrelation.value();
        String secondId = secondCorrelation.value();
        assertThat(firstId).hasSize(22).matches("[A-Za-z0-9_-]{22}");
        assertThat(secondId).hasSize(22).isNotEqualTo(firstId);
        assertThat(Base64.getUrlDecoder().decode(firstId)).hasSize(16);
        assertThat(firstResponse.getHeader("X-Correlation-Id")).isEqualTo(firstId);
        assertThat(firstId).isNotEqualTo("attacker-id");
    }

    @Test
    void onlyRetainsWhitelistedClientTraceId() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest request = requestWith("trace:ok_1");
        final String[] observed = new String[1];
        filter.doFilter(request, new MockHttpServletResponse(), (req, response) ->
            observed[0] = (String) req.getAttribute(CorrelationIdFilter.CLIENT_TRACE_ID_ATTRIBUTE));
        assertThat(observed[0]).isEqualTo("trace:ok_1");

        MockHttpServletRequest invalid = requestWith("trace value");
        filter.doFilter(invalid, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(invalid.getAttribute(CorrelationIdFilter.CLIENT_TRACE_ID_ATTRIBUTE)).isNull();
    }

    @Test
    void clearsClientTraceAndMdcAfterRequestCompletes() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest request = requestWith("trace:ok_1");
        final String[] observed = new String[1];
        filter.doFilter(request, new MockHttpServletResponse(), (req, response) -> {
            observed[0] = (String) req.getAttribute(CorrelationIdFilter.CLIENT_TRACE_ID_ATTRIBUTE);
            assertThat(org.slf4j.MDC.get(CorrelationIdFilter.CLIENT_TRACE_MDC_KEY)).isEqualTo("trace:ok_1");
        });
        assertThat(observed[0]).isEqualTo("trace:ok_1");
        assertThat(request.getAttribute(CorrelationIdFilter.CLIENT_TRACE_ID_ATTRIBUTE)).isNull();
        assertThat(org.slf4j.MDC.get(CorrelationIdFilter.CLIENT_TRACE_MDC_KEY)).isNull();
    }

    private static MockHttpServletRequest requestWith(String trace) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", "attacker-id");
        request.addHeader("X-Client-Trace-Id", trace);
        return request;
    }
}
