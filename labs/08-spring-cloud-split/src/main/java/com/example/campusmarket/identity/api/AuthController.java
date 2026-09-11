package com.example.campusmarket.identity.api;

import com.example.campusmarket.identity.application.AuthService;
import com.example.campusmarket.identity.application.EmailVerificationService;
import com.example.campusmarket.identity.infrastructure.RedisVerificationCodeStore;
import com.example.campusmarket.identity.infrastructure.DeviceCookieSigner;
import com.example.campusmarket.api.ApiError;
import com.example.campusmarket.api.ApiErrors;
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
import org.springframework.http.ResponseCookie;

import java.util.Set;

@RestController
@Profile("!test")
@ConditionalOnBean({EmailVerificationService.class, AuthService.class})
@RequestMapping(path = "/api/auth", produces = "application/json; charset=UTF-8")
public class AuthController {
    private static final String DEVICE_COOKIE = "campus_device";
    private final EmailVerificationService verificationService;
    private final AuthService authService;
    private final DeviceCookieSigner deviceCookieSigner;

    public AuthController(EmailVerificationService verificationService, AuthService authService,
                           DeviceCookieSigner deviceCookieSigner) {
        this.verificationService = verificationService;
        this.authService = authService;
        this.deviceCookieSigner = deviceCookieSigner;
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

    @ExceptionHandler(AuthService.InvalidVerificationCodeException.class)
    ResponseEntity<ApiError> invalidCode(AuthService.InvalidVerificationCodeException ex) {
        return error(HttpStatus.UNAUTHORIZED, "验证码无效或已过期");
    }

    @ExceptionHandler(AuthService.InvalidCredentialsException.class)
    ResponseEntity<ApiError> invalidCredentials(AuthService.InvalidCredentialsException ex) {
        return error(HttpStatus.UNAUTHORIZED, "邮箱或密码错误");
    }

    @ExceptionHandler(AuthService.DuplicateEmailException.class)
    ResponseEntity<ApiError> duplicate(AuthService.DuplicateEmailException ex) {
        return error(HttpStatus.CONFLICT, "邮箱已注册");
    }

    @ExceptionHandler(RedisVerificationCodeStore.TooManyVerificationRequestsException.class)
    ResponseEntity<ApiError> rateLimited(RuntimeException ex) {
        return error(HttpStatus.TOO_MANY_REQUESTS, "验证码请求过于频繁");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> externalDependency(DataAccessException ex) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "身份服务暂时不可用");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> invalidRequest(IllegalArgumentException ex) {
        return error(HttpStatus.BAD_REQUEST, "请求参数无效");
    }

    private String deviceId(jakarta.servlet.http.HttpServletRequest request,
                            jakarta.servlet.http.HttpServletResponse response) {
        if (request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                if (DEVICE_COOKIE.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    if (deviceCookieSigner.verify(cookie.getValue())) {
                        return cookie.getValue();
                    }
                    issueDeviceCookie(response);
                    return deviceCookieSigner.fallback(request.getRemoteAddr());
                }
            }
        }
        String value = deviceCookieSigner.issue();
        if (response != null) {
            response.addHeader("Set-Cookie", ResponseCookie.from(DEVICE_COOKIE, value)
                .httpOnly(true).sameSite("Strict").path("/").maxAge(86400).build().toString());
        }
        return value;
    }

    private void issueDeviceCookie(jakarta.servlet.http.HttpServletResponse response) {
        if (response != null) {
            String value = deviceCookieSigner.issue();
            response.addHeader("Set-Cookie", ResponseCookie.from(DEVICE_COOKIE, value)
                .httpOnly(true).sameSite("Strict").path("/").maxAge(86400).build().toString());
        }
    }

    private <T> ResponseEntity<T> json(HttpStatus status, T body) {
        return ResponseEntity.status(status).header(HttpHeaders.CONTENT_TYPE, "application/json; charset=UTF-8").body(body);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String message) {
        return ApiErrors.entity(status, message);
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

}
