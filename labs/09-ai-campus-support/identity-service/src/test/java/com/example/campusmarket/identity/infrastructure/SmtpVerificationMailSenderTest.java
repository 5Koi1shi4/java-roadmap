package com.example.campusmarket.identity.infrastructure;

import com.example.campusmarket.identity.domain.CampusEmail;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SmtpVerificationMailSenderTest {
    @Test
    void sendsCodeWithoutLoggingSensitiveValues() {
        JavaMailSender sender = mock(JavaMailSender.class);
        SmtpVerificationMailSender adapter = new SmtpVerificationMailSender(sender,
            new SmtpMailProperties("smtp.example.edu.cn", 587, "mailer", "secret", "noreply@example.edu.cn"));
        CampusEmail email = CampusEmail.parse("student@stu.example.edu.cn", Set.of("stu.example.edu.cn"));

        adapter.send(email, "123456");

        verify(sender).send((SimpleMailMessage) argThat((SimpleMailMessage mail) -> {
            return Set.of("student@stu.example.edu.cn").equals(Set.of(mail.getTo()))
                && mail.getFrom().equals("noreply@example.edu.cn")
                && mail.getText().contains("123456");
        }));
        assertThat(adapter.toString()).doesNotContain("123456", "secret", "student@stu.example.edu.cn");
    }
}
