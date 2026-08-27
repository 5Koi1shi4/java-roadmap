package com.example.files.api;

import com.example.files.api.security.RequesterIdentity;
import com.example.files.api.security.RequesterIdentityResolver;
import com.example.files.api.security.RequesterUnauthenticatedException;
import com.example.files.application.access.FileAccessService;
import com.example.files.application.audit.CorrelationId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

/** 文件只读授权边界；身份只来自已配置的认证解析器。 */
@RestController
@RequestMapping(path = "/api/files/{fileId}/grants")
public final class FileGrantController {
    private final FileAccessService accessService;
    private final RequesterIdentityResolver identityResolver;

    public FileGrantController(FileAccessService accessService, RequesterIdentityResolver identityResolver) {
        this.accessService = java.util.Objects.requireNonNull(accessService, "accessService");
        this.identityResolver = java.util.Objects.requireNonNull(identityResolver, "identityResolver");
    }

    @PutMapping(path = "/{userId}")
    public ResponseEntity<Void> grant(@PathVariable String fileId, @PathVariable long userId,
                                      HttpServletRequest request) {
        RequesterIdentity identity = identity(request);
        accessService.grantRead(identity.userId(), UUID.fromString(fileId), userId, correlationId(request));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping(path = "/{userId}")
    public ResponseEntity<Void> revoke(@PathVariable String fileId, @PathVariable long userId,
                                       HttpServletRequest request) {
        RequesterIdentity identity = identity(request);
        accessService.revokeRead(identity.userId(), UUID.fromString(fileId), userId, correlationId(request));
        return ResponseEntity.noContent().build();
    }

    private RequesterIdentity identity(HttpServletRequest request) {
        return identityResolver.resolve(request).orElseThrow(RequesterUnauthenticatedException::new);
    }

    private static CorrelationId correlationId(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        return value instanceof CorrelationId id ? id : CorrelationId.random();
    }
}
