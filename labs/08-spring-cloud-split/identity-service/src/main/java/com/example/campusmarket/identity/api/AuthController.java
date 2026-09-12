package com.example.campusmarket.identity.api;

import com.example.campusmarket.identity.application.AuthService;
import com.example.campusmarket.identity.application.EmailVerificationService;
import com.example.campusmarket.identity.infrastructure.DeviceCookieSigner;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.Set;

/** 注册、登录及邮箱验证码公开 API。 */
@RestController
@Profile("!test")
@ConditionalOnBean({EmailVerificationService.class, AuthService.class})
@RequestMapping(path = "/api/auth", produces = "application/json; charset=UTF-8")
public final class AuthController {
    private static final String DEVICE_COOKIE = "campus_device";
    private static final String JSON_UTF8 = "application/json; charset=UTF-8";

    private final EmailVerificationService verificationService;
    private final AuthService authService;
    private final DeviceCookieSigner deviceCookieSigner;

    public AuthController(EmailVerificationService verificationService, AuthService authService,
                          DeviceCookieSigner deviceCookieSigner) {
        this.verificationService = Objects.requireNonNull(verificationService, "验证码服务不能为空");
        this.authService = Objects.requireNonNull(authService, "认证服务不能为空");
        this.deviceCookieSigner = Objects.requireNonNull(deviceCookieSigner, "设备 cookie 签名器不能为空");
    }

    @PostMapping(path = "/email-verifications", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VerificationResponse> issueVerification(@Valid @RequestBody AuthRequest request,
                                                                    jakarta.servlet.http.HttpServletRequest httpRequest,
                                                                    jakarta.servlet.http.HttpServletResponse httpResponse) {
        validateForEndpoint(request, ValidationPurpose.EMAIL_VERIFICATION);
        String deviceId = deviceId(httpRequest, httpResponse);
        verificationService.issue(request.email(), httpRequest.getRemoteAddr(), deviceId,
            request.purpose());
        return json(HttpStatus.OK, new VerificationResponse("sent", null, 600));
    }

    @PostMapping(path = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageResponse> register(@Valid @RequestBody AuthRequest request,
                                                    jakarta.servlet.http.HttpServletRequest httpRequest,
                                                    jakarta.servlet.http.HttpServletResponse httpResponse) {
        validateForEndpoint(request, ValidationPurpose.REGISTER);
        String deviceId = deviceId(httpRequest, httpResponse);
        authService.register(request.email(), request.password(), firstNonBlank(request.code(), request.verificationCode()),
            httpRequest.getRemoteAddr(), deviceId);
        return json(HttpStatus.CREATED, new MessageResponse("registered"));
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody AuthRequest request) {
        validateForEndpoint(request, ValidationPurpose.LOGIN);
        AuthService.LoginResult result = authService.login(request.email(), request.password());
        return json(HttpStatus.OK, new LoginResponse(result.accessToken(), "Bearer", result.expiresIn().toSeconds(),
            result.userId().toString(), result.roles()));
    }

    /** 供协议单测复用的纯边界校验，不触碰业务服务或基础设施。 */
    public static boolean validateForTest(AuthRequest request, ValidationPurpose purpose) {
        try {
            validateForEndpoint(request, purpose);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static void validateForEndpoint(AuthRequest request, ValidationPurpose purpose) {
        Objects.requireNonNull(request, "请求体不能为空");
        if (request.email() == null || request.email().isBlank()) {
            throw new IllegalArgumentException("邮箱不能为空");
        }
        switch (Objects.requireNonNull(purpose, "验证用途不能为空")) {
            case EMAIL_VERIFICATION -> {
                if (request.purpose() == null || request.purpose().isBlank()) {
                    throw new IllegalArgumentException("验证码用途不能为空");
                }
                EmailVerificationService.normalizePurpose(request.purpose());
            }
            case REGISTER -> {
                validatePassword(request.password());
                String code = firstNonBlank(request.code(), request.verificationCode());
                if (code == null || !code.matches("\\d{6}")) {
                    throw new IllegalArgumentException("注册验证码不能为空且必须为六位数字");
                }
            }
            case LOGIN -> {
                if (request.password() == null || request.password().isBlank()) {
                    throw new IllegalArgumentException("密码不能为空");
                }
                if (request.password().length() > 128) {
                    throw new IllegalArgumentException("密码长度越界");
                }
            }
        }
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new IllegalArgumentException("密码长度必须在 8 到 128 个字符之间");
        }
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

    private static <T> ResponseEntity<T> json(HttpStatus status, T body) {
        return ResponseEntity.status(status).header(HttpHeaders.CONTENT_TYPE, JSON_UTF8).body(body);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public enum ValidationPurpose {
        EMAIL_VERIFICATION, REGISTER, LOGIN
    }

    /** 单一请求模型保留兼容字段；端点按用途执行必填约束。 */
    public record AuthRequest(
        @NotBlank(message = "邮箱不能为空") String email,
        @Size(max = 128, message = "密码长度越界") String password,
        String code,
        String verificationCode,
        String purpose,
        String client,
        String clientId,
        String clientIp) {
        @Override
        public String toString() {
            return "AuthRequest{redacted=true}";
        }
    }

    public record VerificationResponse(String status, String code, long expiresIn) {
    }

    public record MessageResponse(String status) {
    }

    public record LoginResponse(String accessToken, String tokenType, long expiresIn,
                                String userId, Set<String> roles) {
        @Override
        public String toString() {
            return "LoginResponse{redacted=true}";
        }
    }
}
