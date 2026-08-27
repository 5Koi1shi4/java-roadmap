package com.example.files.config;

import com.example.files.api.security.RequesterIdentityResolver;
import com.example.files.api.security.TrustedHeaderIdentityResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 可信用户 Header 的双重门禁：显式开关和 local/test Profile 缺一不可。 */
@Configuration(proxyBeanMethods = false)
@Profile({"local", "test"})
@ConditionalOnProperty(prefix = "file.identity", name = "trusted-header-enabled", havingValue = "true")
public class TrustedHeaderIdentityConfiguration {
    @Bean
    public RequesterIdentityResolver trustedHeaderIdentityResolver() {
        return new TrustedHeaderIdentityResolver();
    }
}
