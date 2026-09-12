package com.example.campusmarket.identity.api;

import com.example.campusmarket.identity.application.AuthService;
import com.example.campusmarket.identity.infrastructure.RedisVerificationCodeStore;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 身份服务最后一道协议边界；内部异常不泄露堆栈或敏感配置。 */
@RestControllerAdvice
public final class IdentityApiExceptionHandler {
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
        MissingServletRequestParameterException.class, ConstraintViolationException.class,
        IllegalArgumentException.class})
    public ResponseEntity<ApiError> badRequest(Exception ignored) {
        return ApiErrors.entity(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数无效");
    }

    @ExceptionHandler(AuthService.InvalidVerificationCodeException.class)
    public ResponseEntity<ApiError> invalidCode(AuthService.InvalidVerificationCodeException ignored) {
        return ApiErrors.entity(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "验证码无效或已过期");
    }

    @ExceptionHandler(AuthService.InvalidCredentialsException.class)
    public ResponseEntity<ApiError> invalidCredentials(AuthService.InvalidCredentialsException ignored) {
        return ApiErrors.entity(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "邮箱或密码错误");
    }

    @ExceptionHandler(AuthService.DuplicateEmailException.class)
    public ResponseEntity<ApiError> duplicate(AuthService.DuplicateEmailException ignored) {
        return ApiErrors.entity(HttpStatus.CONFLICT, "CONFLICT", "邮箱已注册");
    }

    @ExceptionHandler(RedisVerificationCodeStore.TooManyVerificationRequestsException.class)
    public ResponseEntity<ApiError> rateLimited(RuntimeException ignored) {
        return ApiErrors.entity(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "验证码请求过于频繁");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> dependencyUnavailable(DataAccessException ignored) {
        return ApiErrors.entity(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "身份服务暂时不可用");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> notFound(NoResourceFoundException ignored) {
        return ApiErrors.entity(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> internalFailure(Exception ignored) {
        return ApiErrors.entity(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务暂时不可用");
    }
}
