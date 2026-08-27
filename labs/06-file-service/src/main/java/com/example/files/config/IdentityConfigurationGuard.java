package com.example.files.config;

import com.example.files.api.security.RequesterIdentityResolver;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/** 启动时阻止身份适配器误开或完全缺失。 */
@Component
public final class IdentityConfigurationGuard implements BeanFactoryPostProcessor {

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        Environment environment = beanFactory.getBean(Environment.class);
        boolean enabled = environment.getProperty("file.identity.trusted-header-enabled", Boolean.class, false);
        boolean allowedProfile = Arrays.stream(environment.getActiveProfiles())
            .anyMatch(profile -> profile.equals("local") || profile.equals("test"));
        if (enabled && !allowedProfile) {
            throw new IllegalStateException("trusted header identity is limited to local/test profiles");
        }
        if (beanFactory.getBeanNamesForType(RequesterIdentityResolver.class, true, false).length == 0) {
            throw new IllegalStateException("no RequesterIdentityResolver bean configured");
        }
    }
}
