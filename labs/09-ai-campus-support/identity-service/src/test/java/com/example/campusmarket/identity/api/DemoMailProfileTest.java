package com.example.campusmarket.identity.api;

import com.example.campusmarket.identity.application.VerificationMailSender;
import com.example.campusmarket.identity.infrastructure.LocalVerificationMailSender;
import com.example.campusmarket.identity.infrastructure.SmtpMailConfiguration;
import com.example.campusmarket.identity.infrastructure.SmtpVerificationMailSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DemoMailProfileTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(
            LocalVerificationMailSender.class,
            SmtpVerificationMailSender.class,
            SmtpMailConfiguration.class)
        .withPropertyValues(
            "spring.profiles.active=local,demo-mail",
            "campus.market.mail.smtp.host=mailpit",
            "campus.market.mail.smtp.port=1025",
            "campus.market.mail.smtp.from=no-reply@stu.example.edu.cn");

    @Test
    void demoMailSelectsOnlyCapturedSmtpSender() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(VerificationMailSender.class);
            assertThat(context.getBean(VerificationMailSender.class))
                .isInstanceOf(SmtpVerificationMailSender.class);
        });
    }
}
