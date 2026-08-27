package com.example.files.api;

import com.example.files.api.security.RequesterIdentity;
import com.example.files.api.security.RequesterIdentityResolver;
import com.example.files.api.security.RequesterUnauthenticatedException;
import com.example.files.application.audit.CorrelationId;
import com.example.files.application.upload.UploadCommand;
import com.example.files.application.upload.UploadService;
import com.example.files.application.access.FileAccessService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 安全上传 HTTP 边界；协议层只把流和已解析身份传给应用服务。 */
@RestController
@RequestMapping(path = "/api/files")
public final class FileController {
    private final UploadService uploadService;
    private final RequesterIdentityResolver identityResolver;
    private final FileAccessService accessService;

    @org.springframework.beans.factory.annotation.Autowired
    public FileController(UploadService uploadService, RequesterIdentityResolver identityResolver,
                          FileAccessService accessService) {
        this.uploadService = java.util.Objects.requireNonNull(uploadService, "uploadService");
        this.identityResolver = java.util.Objects.requireNonNull(identityResolver, "identityResolver");
        this.accessService = java.util.Objects.requireNonNull(accessService, "accessService");
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "application/json;charset=UTF-8")
    public ResponseEntity<FileResponse> upload(@RequestPart("file") MultipartFile file,
                                                HttpServletRequest request) {
        RequesterIdentity identity = identityResolver.resolve(request)
            .orElseThrow(RequesterUnauthenticatedException::new);
        if (file == null || file.isEmpty()) {
            throw new com.example.files.application.upload.UploadRejectedException("EMPTY_FILE");
        }
        String originalName = file.getOriginalFilename();
        String declaredType = file.getContentType();
        if (declaredType == null || declaredType.isBlank()) declaredType = "application/octet-stream";
        CorrelationId correlationId = correlationId(request);
        try {
            UploadCommand command = new UploadCommand(identity.userId(), originalName, declaredType,
                file.getSize(), file.getInputStream(), correlationId);
            return ResponseEntity.status(201).contentType(new MediaType(MediaType.APPLICATION_JSON,
                    StandardCharsets.UTF_8)).body(FileResponse.from(uploadService.upload(command)));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @GetMapping(path = "/{fileId}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<FileResponse> metadata(@PathVariable String fileId, HttpServletRequest request) {
        RequesterIdentity identity = identityResolver.resolve(request)
            .orElseThrow(RequesterUnauthenticatedException::new);
        return ResponseEntity.ok().contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
            .body(FileResponse.from(accessService.getMetadata(identity.userId(), UUID.fromString(fileId), correlationId(request))));
    }

    @DeleteMapping(path = "/{fileId}")
    public ResponseEntity<Void> delete(@PathVariable String fileId, HttpServletRequest request) {
        RequesterIdentity identity = identityResolver.resolve(request)
            .orElseThrow(RequesterUnauthenticatedException::new);
        accessService.delete(identity.userId(), UUID.fromString(fileId), correlationId(request));
        return ResponseEntity.noContent().build();
    }

    private static CorrelationId correlationId(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        if (value instanceof CorrelationId id) return id;
        return CorrelationId.random();
    }
}
