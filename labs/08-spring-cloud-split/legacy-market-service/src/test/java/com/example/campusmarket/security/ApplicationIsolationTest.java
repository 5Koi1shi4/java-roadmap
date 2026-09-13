package com.example.campusmarket.security;

import com.example.campusmarket.legacy.LegacyMarketApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 防止兼容服务重新扫描根包而把身份服务生产实现装回上下文。 */
@SpringBootTest(classes = LegacyMarketApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApplicationIsolationTest {
    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void applicationUsesExplicitMarketScanBoundary() {
        SpringBootApplication application = LegacyMarketApplication.class.getAnnotation(SpringBootApplication.class);

        assertThat(application.scanBasePackages()).containsExactlyInAnyOrder(
            "com.example.campusmarket.api", "com.example.campusmarket.catalog",
            "com.example.campusmarket.dispute", "com.example.campusmarket.messaging",
            "com.example.campusmarket.observability", "com.example.campusmarket.order",
            "com.example.campusmarket.payment", "com.example.campusmarket.review",
            "com.example.campusmarket.shared", "com.example.campusmarket.storage",
            "com.example.campusmarket.warranty", "com.example.campusmarket.security");
        assertThat(Set.of(application.scanBasePackages())).doesNotContain("com.example.campusmarket");
    }

    @Test
    void legacyProductionSourceContainsNoIdentityPackage() {
        Path identity = Path.of("src/main/java/com/example/campusmarket/identity");

        assertThat(Files.exists(identity)).isFalse();
    }

    @Test
    void contextContainsNoIdentityServiceBeans() {
        assertThat(identityClassIsOnTestClasspath("com.example.campusmarket.identity.api.AuthController")).isTrue();
        assertThat(identityClassIsOnTestClasspath("com.example.campusmarket.identity.application.AuthService")).isTrue();
        assertThat(identityClassIsOnTestClasspath("com.example.campusmarket.identity.security.IdentityTokenIssuer")).isTrue();
        assertThat(applicationContext.containsBean("authController")).isFalse();
        assertThat(applicationContext.containsBean("authService")).isFalse();
        assertThat(applicationContext.containsBean("identityTokenIssuer")).isFalse();
    }

    private static boolean identityClassIsOnTestClasspath(String className) {
        try {
            Class.forName(className, false, ApplicationIsolationTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
