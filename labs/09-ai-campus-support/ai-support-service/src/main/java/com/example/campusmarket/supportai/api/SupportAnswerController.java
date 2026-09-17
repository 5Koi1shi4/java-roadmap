package com.example.campusmarket.supportai.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 精确暴露 AI 支持问答入口；后续回答编排在此接口上扩展。 */
@RestController
@RequestMapping("/api/ai/support")
public class SupportAnswerController {
    private static final MediaType JSON_UTF8 = new MediaType(
        "application", "json", StandardCharsets.UTF_8);

    @PostMapping(path = "/answers", consumes = "application/json", produces = "application/json")
    public ResponseEntity<AnswerResponse> answer(@RequestBody AnswerRequest request,
                                                  Authentication authentication) {
        if (request.hasPrivateResource() && !isAuthenticated(authentication)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(JSON_UTF8)
                .body(new AnswerResponse("未认证"));
        }
        return ResponseEntity.ok()
            .contentType(JSON_UTF8)
            .body(new AnswerResponse("AI 支持服务已就绪。"));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<ErrorResponse> invalidRequest(Exception ignored) {
        return ResponseEntity.badRequest()
            .contentType(JSON_UTF8)
            .body(new ErrorResponse("INVALID_REQUEST", "请求参数非法", UUID.randomUUID()));
    }

    private static boolean isAuthenticated(Authentication authentication) {
        return authentication != null
            && authentication.isAuthenticated()
            && !(authentication instanceof AnonymousAuthenticationToken);
    }

    public record AnswerResponse(String answer) {
    }

    public record ErrorResponse(String code, String message, UUID correlationId) {
    }
}
