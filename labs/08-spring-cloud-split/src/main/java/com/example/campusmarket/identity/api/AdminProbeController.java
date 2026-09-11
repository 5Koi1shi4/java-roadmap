package com.example.campusmarket.identity.api;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping(path = "/api/admin", produces = "application/json; charset=UTF-8")
public class AdminProbeController {
    @GetMapping("/probe")
    public ResponseEntity<AuthController.MessageResponse> probe() {
        return ResponseEntity.status(HttpStatus.OK)
            .header(HttpHeaders.CONTENT_TYPE, "application/json; charset=UTF-8")
            .body(new AuthController.MessageResponse("admin"));
    }
}
