package com.example.files.unit;

import com.example.files.api.security.RequesterIdentity;
import com.example.files.api.security.RequesterIdentityResolver;
import com.example.files.api.security.TrustedHeaderIdentityResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedHeaderIdentityResolverTest {
    private final RequesterIdentityResolver resolver = new TrustedHeaderIdentityResolver();

    @Test
    void acceptsOneCanonicalPositiveDecimalLong() {
        MockHttpServletRequest request = requestWith("42");

        assertThat(resolver.resolve(request)).contains(new RequesterIdentity(42L));
    }

    @Test
    void rejectsMissingRepeatedWhitespaceSymbolOverflowAndNonCanonicalHeaders() {
        assertThat(resolver.resolve(requestWith(null))).isEmpty();
        MockHttpServletRequest repeated = new MockHttpServletRequest();
        repeated.addHeader(TrustedHeaderIdentityResolver.HEADER_NAME, "42");
        repeated.addHeader(TrustedHeaderIdentityResolver.HEADER_NAME, "43");
        assertThat(resolver.resolve(repeated)).isEmpty();
        for (String value : new String[]{"", " 42", "42 ", "+42", "0", "042", "abc",
            "9223372036854775808"}) {
            assertThat(resolver.resolve(requestWith(value))).as(value).isEmpty();
        }
    }

    private static MockHttpServletRequest requestWith(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (value != null) request.addHeader(TrustedHeaderIdentityResolver.HEADER_NAME, value);
        return request;
    }
}
