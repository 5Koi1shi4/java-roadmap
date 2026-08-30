package com.example.campusmarket.identity.api;

import com.example.campusmarket.identity.application.AuthService;
import com.example.campusmarket.identity.application.EmailVerificationService;
import com.example.campusmarket.identity.infrastructure.RedisVerificationCodeStore;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.ResponseCookie;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

@RestController
@Profile("!test")
@ConditionalOnBean({EmailVerificationService.class, AuthService.class})
@RequestMapping(path = "/api/auth", produces = "application/json; charset=UTF-8")
public class AuthController {
    private static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json; charset=UTF-8");
    private static final String DEVICE_COOKIE = "campus_device";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final EmailVerificationService verificationService;
    private final AuthService authService;
    private final Environment environment;

    public AuthController(EmailVerificationService verificationService, AuthService authService,
                           Environment environment) {
        this.verificationService = verificationService;
        this.authService = authService;
        this.environment = environment;
    }

    @PostMapping(path = "/email-verifications", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VerificationResponse> issueVerification(@RequestBody AuthRequest request,
                                                                    jakarta.servlet.http.HttpServletRequest httpRequest,
                                                                    jakarta.servlet.http.HttpServletResponse httpResponse) {
        String deviceId = deviceId(httpRequest, httpResponse);
        String code = verificationService.issue(request.email(), httpRequest.getRemoteAddr(), deviceId, request.purpose());
        return json(HttpStatus.OK, new VerificationResponse("sent", null, 600));
    }

    @PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageResponse> register(@RequestBody AuthRequest request,
                                                    jakarta.servlet.http.HttpServletRequest httpRequest,
                                                    jakarta.servlet.http.HttpServletResponse httpResponse) {
        String deviceId = deviceId(httpRequest, httpResponse);
        authService.register(request.email(), request.password(), firstNonBlank(request.code(), request.verificationCode()),
            httpRequest.getRemoteAddr(), deviceId);
        return json(HttpStatus.CREATED, new MessageResponse("registered"));
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LoginResponse> login(@RequestBody AuthRequest request) {
        AuthService.LoginResult result = authService.login(request.email(), request.password());
        return json(HttpStatus.OK, new LoginResponse(result.accessToken(), "Bearer", result.expiresIn().toSeconds(),
            result.userId().toString(), result.roles()));
    }

    @GetMapping(path = "/admin/probe")
    public ResponseEntity<MessageResponse> adminProbe() {
        return json(HttpStatus.OK, new MessageResponse("admin"));
    }

    @ExceptionHandler(AuthService.InvalidVerificationCodeException.class)
    ResponseEntity<ErrorResponse> invalidCode(AuthService.InvalidVerificationCodeException ex) {
        return error(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(AuthService.InvalidCredentialsException.class)
    ResponseEntity<ErrorResponse> invalidCredentials(AuthService.InvalidCredentialsException ex) {
        return error(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(AuthService.DuplicateEmailException.class)
    ResponseEntity<ErrorResponse> duplicate(AuthService.DuplicateEmailException ex) {
        return error(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(RedisVerificationCodeStore.TooManyVerificationRequestsException.class)
    ResponseEntity<ErrorResponse> rateLimited(RuntimeException ex) {
        return error(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorResponse> externalDependency(DataAccessException ex) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "Identity service is temporarily unavailable");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalidRequest(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    private String deviceId(jakarta.servlet.http.HttpServletRequest request,
                            jakarta.servlet.http.HttpServletResponse response) {
        if (request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                if (DEVICE_COOKIE.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
        }
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        if (response != null) {
            response.addHeader("Set-Cookie", ResponseCookie.from(DEVICE_COOKIE, value)
                .httpOnly(true).sameSite("Strict").path("/").maxAge(86400).build().toString());
        }
        return value;
    }

    private <T> ResponseEntity<T> json(HttpStatus status, T body) {
        return ResponseEntity.status(status).header(HttpHeaders.CONTENT_TYPE, "application/json; charset=UTF-8").body(body);
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String message) {
        return json(status, new ErrorResponse(message));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public record AuthRequest(String email, String password, String code, String verificationCode,
                              String purpose, String client, String clientId, String clientIp) {
    }

    public record VerificationResponse(String status, String code, long expiresIn) {
    }

    public record MessageResponse(String status) {
    }

    public record LoginResponse(String accessToken, String tokenType, long expiresIn,
                                String userId, Set<String> roles) {
    }

    public record ErrorResponse(String error) {
    }
}
