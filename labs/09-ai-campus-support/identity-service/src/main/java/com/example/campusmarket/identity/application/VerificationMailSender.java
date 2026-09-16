package com.example.campusmarket.identity.application;

import com.example.campusmarket.identity.domain.CampusEmail;

/** 验证码邮件端口，具体实现按 profile 装配。 */
public interface VerificationMailSender {
    void send(CampusEmail email, String code);
}
