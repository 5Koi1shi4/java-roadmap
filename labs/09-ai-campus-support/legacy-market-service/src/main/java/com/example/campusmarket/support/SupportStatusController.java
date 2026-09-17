package com.example.campusmarket.support;

import com.example.campusmarket.api.ApiErrors;
import com.example.campusmarket.security.AuthenticatedUser;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.UUID;

/** 支持状态摘要的 HTTP 只读边界；所有访问控制都委托给 SQL。 */
@RestController
@Profile("!test")
@RequestMapping(path = "/api/support", produces = "application/json; charset=UTF-8")
public final class SupportStatusController {
    private static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json; charset=UTF-8");

    private final SupportStatusService service;

    public SupportStatusController(SupportStatusService service) {
        this.service = service;
    }

    @GetMapping("/{type}")
    public ResponseEntity<?> list(@PathVariable String type,
                                  @RequestParam(required = false) Integer limit,
                                  @RequestParam(required = false) String cursor,
                                  Authentication authentication) {
        try {
            SupportStatusService.StatusPage page = service.list(type, principal(authentication), limit, cursor);
            return ResponseEntity.ok().contentType(JSON_UTF8).body(page);
        } catch (IllegalArgumentException ex) {
            return ApiErrors.bytes(HttpStatus.BAD_REQUEST, "请求参数无效");
        }
    }

    @GetMapping("/{type}/{id}")
    public ResponseEntity<?> find(@PathVariable String type, @PathVariable String id,
                                  Authentication authentication) {
        try {
            UUID resourceId = UUID.fromString(id);
            SupportStatusService.StatusView view = service.find(type, resourceId, principal(authentication));
            return view == null
                ? ApiErrors.bytes(HttpStatus.NOT_FOUND, "资源不存在")
                : ResponseEntity.ok().contentType(JSON_UTF8).body(view);
        } catch (IllegalArgumentException ex) {
            return ApiErrors.bytes(HttpStatus.BAD_REQUEST, "请求参数无效");
        }
    }

    private static UUID principal(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new IllegalArgumentException("身份无效");
        }
        return user.userId();
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<byte[]> malformedRequest(Exception ignored) {
        return ApiErrors.bytes(HttpStatus.BAD_REQUEST, "请求参数无效");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<byte[]> dependencyUnavailable(Exception ignored) {
        return ApiErrors.bytes(HttpStatus.SERVICE_UNAVAILABLE, "依赖服务暂时不可用");
    }
}
