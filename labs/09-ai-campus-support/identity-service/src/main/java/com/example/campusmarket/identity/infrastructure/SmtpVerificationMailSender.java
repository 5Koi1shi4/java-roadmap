package com.example.campusmarket.identity.infrastructure;

import com.example.campusmarket.identity.application.VerificationMailSender;
import com.example.campusmarket.identity.domain.CampusEmail;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 生产验证码邮件适配器；不记录验证码、密码或收件人。 */
@Component
@Profile({"demo-mail", "!local & !test"})
public final class SmtpVerificationMailSender implements VerificationMailSender {
    private final JavaMailSender mailSender;
    private final SmtpMailProperties properties;

    public SmtpVerificationMailSender(JavaMailSender mailSender, SmtpMailProperties properties) {
        this.mailSender = Objects.requireNonNull(mailSender, "JavaMailSender is required");
        this.properties = Objects.requireNonNull(properties, "SMTP properties are required");
    }

    @Override
    public void send(CampusEmail email, String code) {
        Objects.requireNonNull(email, "邮箱不能为空");
        if (code == null || !code.matches("\\d{6}")) {
            throw new IllegalArgumentException("验证码格式无效");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(email.value());
        message.setSubject("校园市场邮箱验证码");
        message.setText("你的校园市场验证码为：" + code + "。验证码十分钟内有效。");
        mailSender.send(message);
    }

    @Override
    public String toString() {
        return "SmtpVerificationMailSender{configured=true}";
    }
}
