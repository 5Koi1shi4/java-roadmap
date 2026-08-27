package com.example.files.api;

import com.example.files.api.security.RequesterIdentity;
import com.example.files.api.security.RequesterIdentityResolver;
import com.example.files.api.security.RequesterUnauthenticatedException;
import com.example.files.application.audit.CorrelationId;
import com.example.files.application.upload.UploadCommand;
import com.example.files.application.upload.UploadService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** 安全上传 HTTP 边界；协议层只把流和已解析身份传给应用服务。 */
@RestController
@RequestMapping(path = "/api/files")
public final class FileController {
    private final UploadService uploadService;
    private final RequesterIdentityResolver identityResolver;

    public FileController(UploadService uploadService, RequesterIdentityResolver identityResolver) {
        this.uploadService = java.util.Objects.requireNonNull(uploadService, "uploadService");
        this.identityResolver = java.util.Objects.requireNonNull(identityResolver, "identityResolver");
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

    private static CorrelationId correlationId(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        if (value instanceof CorrelationId id) return id;
        return CorrelationId.random();
    }
}
