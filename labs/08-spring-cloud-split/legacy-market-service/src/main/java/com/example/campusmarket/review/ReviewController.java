package com.example.campusmarket.review;

import com.example.campusmarket.identity.application.AuthenticatedUser;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 评价 HTTP 边界；认证与异常统一由 Spring Security/API advice 处理。 */
@RestController
@Profile("!test")
public final class ReviewController {
    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @PostMapping(path = "/api/orders/{orderId}/reviews", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = "application/json; charset=UTF-8")
    public ResponseEntity<ReviewService.Result> create(@PathVariable UUID orderId,
                                                         @RequestBody ReviewRequest request,
                                                         Authentication authentication) {
        UUID reviewer = user(authentication);
        ReviewService.Result result = reviews.create(orderId, reviewer, request.rating(), request.content());
        return ResponseEntity.status(HttpStatus.CREATED).contentType(JSON).body(result);
    }

    private static UUID user(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser current)) {
            throw new IllegalArgumentException("身份无效");
        }
        return current.userId();
    }

    private static final MediaType JSON = MediaType.parseMediaType("application/json; charset=UTF-8");

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    public record ReviewRequest(int rating, String reviewText, String content, String text) {
        public String content() {
            if (reviewText != null && !reviewText.isBlank()) return reviewText;
            if (content != null && !content.isBlank()) return content;
            return text;
        }
    }
}
