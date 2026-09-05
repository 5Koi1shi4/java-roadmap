package com.example.campusmarket.dispute.api;

import com.example.campusmarket.dispute.application.DisputeService;
import com.example.campusmarket.dispute.application.EvidenceStorage;
import com.example.campusmarket.dispute.domain.DisputeDecision;
import com.example.campusmarket.identity.application.AuthenticatedUser;
import com.example.campusmarket.order.application.IdempotentCommandService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@Profile("!test")
public final class DisputeController {
    private static final MediaType JSON = MediaType.parseMediaType("application/json; charset=UTF-8");
    private final DisputeService disputes;
    private final EvidenceStorage evidence;

    public DisputeController(DisputeService disputes, EvidenceStorage evidence) { this.disputes = disputes; this.evidence = evidence; }

    @PostMapping(path = "/api/orders/{orderId}/disputes", consumes = MediaType.APPLICATION_JSON_VALUE, produces = "application/json; charset=UTF-8")
    public ResponseEntity<byte[]> open(@PathVariable UUID orderId, @RequestBody OpenRequest request,
                                       @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        try { requireKey(key); if (request == null) throw new IllegalArgumentException("请求不能为空"); DisputeService.Result result = disputes.open(orderId, user(auth), key, request.disputedQuantity(), request.reason(), requestBytes(request)); return body(result, HttpStatus.CREATED); }
        catch (DisputeService.NotFoundException ex) { return error(HttpStatus.NOT_FOUND, "争议不存在"); }
        catch (DisputeService.ConflictException | IdempotentCommandService.IdempotencyConflictException ex) { return error(HttpStatus.CONFLICT, "争议状态或数量冲突"); }
        catch (IllegalArgumentException ex) { return error(HttpStatus.BAD_REQUEST, "请求参数无效"); }
    }

    @PostMapping(path = "/api/disputes/{caseId}/responses", consumes = MediaType.APPLICATION_JSON_VALUE, produces = "application/json; charset=UTF-8")
    public ResponseEntity<byte[]> respond(@PathVariable UUID caseId, @RequestBody ResponseRequest request,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        try { requireKey(key); if (request == null) throw new IllegalArgumentException("请求不能为空"); return body(disputes.respond(caseId, user(auth), key, request.response(), requestBytes(request)), HttpStatus.OK); }
        catch (DisputeService.NotFoundException ex) { return error(HttpStatus.NOT_FOUND, "争议不存在"); }
        catch (DisputeService.ConflictException | IdempotentCommandService.IdempotencyConflictException ex) { return error(HttpStatus.CONFLICT, "争议状态冲突"); }
        catch (IllegalArgumentException ex) { return error(HttpStatus.BAD_REQUEST, "请求参数无效"); }
    }

    @PostMapping(path = "/api/disputes/{caseId}/assignments", consumes = MediaType.APPLICATION_JSON_VALUE, produces = "application/json; charset=UTF-8")
    public ResponseEntity<byte[]> assign(@PathVariable UUID caseId, @RequestBody AssignmentRequest request, Authentication auth) {
        try { if (!isAdmin(auth)) return error(HttpStatus.FORBIDDEN, "无权执行该角色操作"); if (request == null) throw new IllegalArgumentException("请求不能为空"); return body(disputes.assign(caseId, user(auth), request.adminId()), HttpStatus.OK); }
        catch (DisputeService.NotFoundException ex) { return error(HttpStatus.NOT_FOUND, "争议不存在"); }
        catch (DisputeService.ConflictException ex) { return error(HttpStatus.CONFLICT, "争议状态冲突"); }
        catch (DisputeService.ForbiddenException ex) { return error(HttpStatus.FORBIDDEN, "无权执行该角色操作"); }
        catch (IllegalArgumentException ex) { return error(HttpStatus.BAD_REQUEST, "请求参数无效"); }
    }

    @PostMapping(path = "/api/disputes/{caseId}/decisions", consumes = MediaType.APPLICATION_JSON_VALUE, produces = "application/json; charset=UTF-8")
    public ResponseEntity<byte[]> decide(@PathVariable UUID caseId, @RequestBody DecisionRequest request,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key, Authentication auth) {
        try { requireKey(key); if (!isAdmin(auth)) return error(HttpStatus.FORBIDDEN, "无权执行该角色操作"); if (request == null) throw new IllegalArgumentException("请求不能为空"); return body(disputes.decide(caseId, user(auth), key, request.decision(), request.approvedQuantity(), requestBytes(request)), HttpStatus.OK); }
        catch (DisputeService.NotFoundException ex) { return error(HttpStatus.NOT_FOUND, "争议不存在"); }
        catch (DisputeService.ConflictException | IdempotentCommandService.IdempotencyConflictException ex) { return error(HttpStatus.CONFLICT, "争议状态或数量冲突"); }
        catch (IllegalArgumentException ex) { return error(HttpStatus.BAD_REQUEST, "请求参数无效"); }
    }

    @PostMapping(path = "/api/disputes/{caseId}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "application/json; charset=UTF-8")
    public ResponseEntity<byte[]> attach(@PathVariable UUID caseId, @RequestPart("file") MultipartFile file, Authentication auth) {
        try { EvidenceStorage.EvidenceRecord saved = evidence.attach(caseId, user(auth), file.getOriginalFilename(), file.getContentType(), file.getInputStream()); return ResponseEntity.status(HttpStatus.CREATED).contentType(JSON).body(("{\"evidenceId\":\"" + saved.id() + "\",\"mediaType\":\"" + saved.mediaType() + "\",\"sizeBytes\":" + saved.sizeBytes() + "}").getBytes(StandardCharsets.UTF_8)); }
        catch (EvidenceStorage.NotFoundException ex) { return error(HttpStatus.NOT_FOUND, "证据不存在"); }
        catch (EvidenceStorage.StorageUnavailableException ex) { return error(HttpStatus.SERVICE_UNAVAILABLE, "对象存储暂不可用"); }
        catch (IllegalArgumentException | IOException ex) { return error(HttpStatus.BAD_REQUEST, "证据文件无效"); }
    }

    @GetMapping(path = "/api/disputes/{caseId}/evidence/{evidenceId}/content")
    public ResponseEntity<?> content(@PathVariable UUID caseId, @PathVariable UUID evidenceId, Authentication auth) {
        try { EvidenceStorage.OpenedEvidence opened = evidence.open(caseId, evidenceId, user(auth));
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(opened.mediaType()))
                .body(new InputStreamResource(opened.content())); }
        catch (EvidenceStorage.NotFoundException ex) { return error(HttpStatus.NOT_FOUND, "证据不存在"); }
        catch (EvidenceStorage.StorageUnavailableException ex) { return error(HttpStatus.SERVICE_UNAVAILABLE, "对象存储暂不可用"); }
    }

    private static UUID user(Authentication auth) { if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser u)) throw new IllegalArgumentException("身份无效"); return u.userId(); }
    private static boolean isAdmin(Authentication auth) { return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())); }
    private static void requireKey(String key) { if (key == null || key.isBlank() || key.length() > 191 || key.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("幂等参数无效"); }
    private static byte[] requestBytes(Object request) { return request.toString().getBytes(StandardCharsets.UTF_8); }
    private static ResponseEntity<byte[]> body(DisputeService.Result result, HttpStatus status) { return ResponseEntity.status(status).contentType(JSON).body(result.responseUtf8()); }
    private static ResponseEntity<byte[]> error(HttpStatus status, String message) { return ResponseEntity.status(status).contentType(JSON).body(("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8)); }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<byte[]> malformedRequest() { return error(HttpStatus.BAD_REQUEST, "请求格式无效"); }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    public record OpenRequest(int disputedQuantity, String reason) {}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    public record ResponseRequest(String response) {}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    public record AssignmentRequest(UUID adminId) {}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    public record DecisionRequest(DisputeDecision decision, int approvedQuantity) {}
}
