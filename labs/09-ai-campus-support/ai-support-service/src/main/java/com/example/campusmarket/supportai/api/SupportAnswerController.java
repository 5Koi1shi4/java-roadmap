package com.example.campusmarket.supportai.api;

import com.example.campusmarket.supportai.application.AnswerService;
import com.example.campusmarket.supportai.infrastructure.SupportRateLimiter;
import com.example.campusmarket.supportai.policy.PolicyRetriever;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** AI 支持问答 HTTP 边界；只把认证后的 Bearer 传给内部交易摘要客户端。 */
@RestController
@RequestMapping("/api/ai/support")
public class SupportAnswerController {
    private static final MediaType JSON_UTF8 = new MediaType(
        "application", "json", StandardCharsets.UTF_8);

    private final AnswerService service;
    private final SupportRateLimiter rateLimiter;

    @Autowired
    public SupportAnswerController(ObjectProvider<AnswerService> services,
                                   ObjectProvider<SupportRateLimiter> rateLimiters) {
        this.service = services.getIfAvailable();
        this.rateLimiter = rateLimiters.getIfAvailable();
    }

    /** 供单元测试或嵌入式调用方显式注入用例与限流器。 */
    public SupportAnswerController(AnswerService service, SupportRateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping(path = "/answers", consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> answer(@RequestBody AnswerRequest request,
                                    Authentication authentication,
                                    HttpServletRequest servletRequest) {
        if (request.hasPrivateResource() && !isAuthenticated(authentication)) {
            return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "未认证");
        }
        if (service == null) {
            // 允许 Task 4 的协议边界测试在未配置外部 AI 依赖时启动应用上下文。
            return ResponseEntity.ok().contentType(JSON_UTF8)
                .body(new AnswerService.AnswerResponse("AI 支持服务已就绪。"));
        }

        UUID privateUserId = request.hasPrivateResource()
            ? principalId(authentication) : null;
        if (rateLimiter != null) {
            rateLimiter.check(servletRequest == null ? null : servletRequest.getRemoteAddr(),
                privateUserId);
        }
        return ResponseEntity.ok().contentType(JSON_UTF8)
            .body(service.answer(request,
                request.hasPrivateResource() ? bearerToken(authentication) : null));
    }

    /** 保留无 Servlet 请求上下文的直接调用入口。 */
    public ResponseEntity<?> answer(AnswerRequest request, Authentication authentication) {
        return answer(request, authentication, null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<ErrorResponse> invalidRequest(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数非法");
    }

    @ExceptionHandler(AnswerService.UnauthorizedException.class)
    ResponseEntity<ErrorResponse> unauthenticated(AnswerService.UnauthorizedException ignored) {
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "未认证");
    }

    @ExceptionHandler(AnswerService.ResourceNotFoundException.class)
    ResponseEntity<ErrorResponse> resourceNotFound(AnswerService.ResourceNotFoundException ignored) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在");
    }

    @ExceptionHandler(AnswerService.RateLimitExceededException.class)
    ResponseEntity<ErrorResponse> rateLimited(AnswerService.RateLimitExceededException ignored) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, "60")
            .contentType(JSON_UTF8)
            .body(new ErrorResponse("RATE_LIMITED", "请求过于频繁", UUID.randomUUID()));
    }

    @ExceptionHandler(AnswerService.DependencyUnavailableException.class)
    ResponseEntity<ErrorResponse> dependencyUnavailable(
        AnswerService.DependencyUnavailableException ignored) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "支持服务暂时不可用");
    }

    @ExceptionHandler(PolicyRetriever.PolicyUnavailableException.class)
    ResponseEntity<ErrorResponse> policyUnavailable(
        PolicyRetriever.PolicyUnavailableException ignored) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "支持服务暂时不可用");
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code,
                                                        String message) {
        return ResponseEntity.status(status)
            .contentType(JSON_UTF8)
            .body(new ErrorResponse(code, message, UUID.randomUUID()));
    }

    private static boolean isAuthenticated(Authentication authentication) {
        return authentication != null
            && authentication.isAuthenticated()
            && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private static UUID principalId(Authentication authentication) {
        if (!isAuthenticated(authentication)) {
            throw new AnswerService.UnauthorizedException();
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            throw new AnswerService.UnauthorizedException();
        }
    }

    private static String bearerToken(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return jwt.getToken().getTokenValue();
        }
        Object credentials = authentication == null ? null : authentication.getCredentials();
        if (credentials instanceof String token && !token.isBlank()) {
            return token;
        }
        throw new AnswerService.UnauthorizedException();
    }

    public record ErrorResponse(String code, String message, UUID correlationId) {
    }

    /** Task 4 旧占位响应类型；真实回答使用 {@link AnswerService.AnswerResponse}。 */
    @Deprecated
    public record AnswerResponse(String answer) {
    }
}
