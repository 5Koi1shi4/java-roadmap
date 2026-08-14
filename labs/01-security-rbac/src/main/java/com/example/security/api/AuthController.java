package com.example.security.api;

import com.example.security.application.AuthService;
import com.example.security.application.InvalidCredentialsException;
import com.example.security.application.InvalidTokenException;
import com.example.security.application.RefreshTokenService;
import com.example.security.application.UserDisabledException;
import com.example.security.domain.User;
import com.example.security.domain.UserRepository;
import com.example.security.infrastructure.security.JwtTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public final class AuthController {

    private final AuthService auth;
    private final RefreshTokenService refreshTokens;
    private final JwtTokenService jwtTokens;
    private final UserRepository users;

    public AuthController(
            AuthService auth,
            RefreshTokenService refreshTokens,
            JwtTokenService jwtTokens,
            UserRepository users
    ) {
        this.auth = auth;
        this.refreshTokens = refreshTokens;
        this.jwtTokens = jwtTokens;
        this.users = users;
    }

    @PostMapping("/login")
    public TokenPair login(@RequestBody Credentials credentials) {
        if (credentials == null || isBlank(credentials.username()) || isBlank(credentials.password())) {
            throw new InvalidCredentialsException();
        }
        User user = auth.authenticate(credentials.username(), credentials.password());
        return issueTokens(user);
    }

    @PostMapping("/refresh")
    public TokenPair refresh(@RequestBody RefreshRequest request) {
        RefreshTokenService.RotatedRefreshToken rotated = refreshTokens.rotate(requireRefreshToken(request));
        User user = users.findById(rotated.userId())
                .filter(User::enabled)
                .orElseThrow(() -> new InvalidTokenException(
                        new IllegalArgumentException("refresh token user is unavailable")));
        return new TokenPair(jwtTokens.issueAccessToken(user), rotated.rawToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody RefreshRequest request) {
        refreshTokens.revoke(requireRefreshToken(request));
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler({InvalidCredentialsException.class, UserDisabledException.class, InvalidTokenException.class})
    ResponseEntity<Void> rejectAuthentication() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    private TokenPair issueTokens(User user) {
        return new TokenPair(jwtTokens.issueAccessToken(user), refreshTokens.issue(user.id()));
    }

    private String requireRefreshToken(RefreshRequest request) {
        if (request == null || isBlank(request.refreshToken())) {
            throw new InvalidTokenException(new IllegalArgumentException("refresh token must not be blank"));
        }
        return request.refreshToken();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record Credentials(String username, String password) {
    }

    public record RefreshRequest(String refreshToken) {
    }

    public record TokenPair(String accessToken, String refreshToken) {
    }
}
