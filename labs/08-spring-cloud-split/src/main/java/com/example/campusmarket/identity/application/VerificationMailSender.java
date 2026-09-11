package com.example.campusmarket.identity.application;

import com.example.campusmarket.identity.domain.CampusEmail;

public interface VerificationMailSender {
    void send(CampusEmail email, String code);
}
