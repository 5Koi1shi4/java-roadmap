package com.example.campusmarket.warranty.api;

import com.example.campusmarket.api.ApiErrors;
import com.example.campusmarket.security.AuthenticatedUser;
import com.example.campusmarket.warranty.application.WarrantyService;
import com.example.campusmarket.warranty.domain.WarrantyDecision;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@Profile("!test")
public final class WarrantyController {
    private static final MediaType JSON=MediaType.parseMediaType("application/json; charset=UTF-8");
    private final WarrantyService warranties;
    public WarrantyController(WarrantyService warranties){this.warranties=warranties;}
    @PostMapping(path={"/api/orders/{orderId}/warranty","/api/orders/{orderId}/warranty-cases"},consumes=MediaType.APPLICATION_JSON_VALUE,produces="application/json; charset=UTF-8")
    public ResponseEntity<byte[]> open(@PathVariable UUID orderId,@RequestBody OpenRequest req,@RequestHeader("Idempotency-Key") String key,Authentication auth){try{WarrantyService.Result r=warranties.openWarrantyCase(orderId,req.quantity(),req.reason(),key,user(auth));return ResponseEntity.status(HttpStatus.CREATED).contentType(JSON).body(("{\"caseId\":\""+r.caseId()+"\",\"status\":\""+r.status()+"\"}").getBytes(StandardCharsets.UTF_8));}catch(WarrantyService.NotFoundException e){return error(HttpStatus.NOT_FOUND,"质保案件不存在");}catch(WarrantyService.IdempotencyConflictException e){return error(HttpStatus.CONFLICT,"幂等冲突");}catch(IllegalArgumentException e){return error(HttpStatus.BAD_REQUEST,"质保申请参数无效");}catch(IllegalStateException e){return error(HttpStatus.UNPROCESSABLE_ENTITY,"质保申请不满足业务条件");}}
    @PostMapping(path={"/api/warranty/{caseId}/decisions","/api/warranty-cases/{caseId}/decisions","/api/admin/warranty-cases/{caseId}/decisions"},consumes=MediaType.APPLICATION_JSON_VALUE,produces="application/json; charset=UTF-8")
    public ResponseEntity<byte[]> decide(@PathVariable UUID caseId,@RequestBody DecisionRequest req,@RequestHeader("Idempotency-Key") String idempotencyKey,Authentication auth){try{if(!admin(auth))return error(HttpStatus.FORBIDDEN,"无权执行该角色操作");WarrantyService.Result r=warranties.decide(caseId,user(auth),req.decision(),req.compensationAmountFen(),req.evidenceId(),idempotencyKey);return ResponseEntity.ok().contentType(JSON).body(("{\"caseId\":\""+r.caseId()+"\",\"status\":\""+r.status()+"\"}").getBytes(StandardCharsets.UTF_8));}catch(WarrantyService.NotFoundException e){return error(HttpStatus.NOT_FOUND,"质保案件不存在");}catch(WarrantyService.IdempotencyConflictException e){return error(HttpStatus.CONFLICT,"幂等冲突");}catch(IllegalArgumentException e){return error(HttpStatus.BAD_REQUEST,"质保裁定参数无效");}catch(IllegalStateException e){return error(HttpStatus.UNPROCESSABLE_ENTITY,"质保裁定不满足业务条件");}}
    @PostMapping(path={"/api/warranty/{caseId}/responses","/api/warranty-cases/{caseId}/responses"},consumes=MediaType.APPLICATION_JSON_VALUE,produces="application/json; charset=UTF-8")
    public ResponseEntity<byte[]> respond(@PathVariable UUID caseId,Authentication auth){try{var r=warranties.respond(caseId,user(auth));return ResponseEntity.ok().contentType(JSON).body(("{\"caseId\":\""+r.caseId()+"\",\"status\":\""+r.status()+"\"}").getBytes(StandardCharsets.UTF_8));}catch(WarrantyService.NotFoundException e){return error(HttpStatus.NOT_FOUND,"质保案件不存在");}catch(IllegalStateException e){return error(HttpStatus.CONFLICT,"质保状态冲突");}}
    @PostMapping(path={"/api/warranty/{caseId}/assignments","/api/warranty-cases/{caseId}/assignments"},consumes=MediaType.APPLICATION_JSON_VALUE,produces="application/json; charset=UTF-8")
    public ResponseEntity<byte[]> assign(@PathVariable UUID caseId,@RequestBody AssignmentRequest req,Authentication auth){try{if(!admin(auth)||req==null||!user(auth).equals(req.adminId()))return error(HttpStatus.FORBIDDEN,"无权执行该角色操作");var r=warranties.assign(caseId,user(auth));return ResponseEntity.ok().contentType(JSON).body(("{\"caseId\":\""+r.caseId()+"\",\"status\":\""+r.status()+"\"}").getBytes(StandardCharsets.UTF_8));}catch(WarrantyService.NotFoundException e){return error(HttpStatus.NOT_FOUND,"质保案件不存在");}}
    @GetMapping(path={"/api/warranty/{caseId}","/api/warranty-cases/{caseId}"},produces="application/json; charset=UTF-8")
    public ResponseEntity<byte[]> find(@PathVariable UUID caseId,Authentication auth){try{var r=warranties.find(caseId,user(auth));return ResponseEntity.ok().contentType(JSON).body(("{\"caseId\":\""+r.id()+"\",\"orderId\":\""+r.orderId()+"\",\"status\":\""+r.status()+"\",\"decision\":"+(r.decision()==null?"null":"\""+r.decision()+"\"")+",\"compensationAmountFen\":"+r.compensationAmountFen()+"}").getBytes(StandardCharsets.UTF_8));}catch(WarrantyService.NotFoundException e){return error(HttpStatus.NOT_FOUND,"质保案件不存在");}}
    @PostMapping(path={"/api/warranty/{caseId}/evidence","/api/warranty-cases/{caseId}/evidence"},consumes=MediaType.MULTIPART_FORM_DATA_VALUE,produces="application/json; charset=UTF-8")
    public ResponseEntity<byte[]> evidence(@PathVariable UUID caseId,@RequestPart("file") MultipartFile file,@RequestHeader(value="X-Evidence-Purpose",required=false) String purpose,Authentication auth)throws Exception{try{var e=warranties.attachEvidence(caseId,user(auth),file.getOriginalFilename(),file.getContentType(),purpose,file.getInputStream());return ResponseEntity.status(HttpStatus.CREATED).contentType(JSON).body(("{\"evidenceId\":\""+e.id()+"\"}").getBytes(StandardCharsets.UTF_8));}catch(com.example.campusmarket.dispute.application.EvidenceStorage.NotFoundException ex){return error(HttpStatus.NOT_FOUND,"证据不存在");}catch(com.example.campusmarket.dispute.application.EvidenceStorage.StorageUnavailableException ex){return error(HttpStatus.SERVICE_UNAVAILABLE,"证据存储暂时不可用");}catch(IllegalArgumentException ex){return error(HttpStatus.BAD_REQUEST,"证据请求无效");}}
    @GetMapping({"/api/warranty/{caseId}/evidence/{evidenceId}/content","/api/warranty-cases/{caseId}/evidence/{evidenceId}/content"}) public ResponseEntity<?> content(@PathVariable UUID caseId,@PathVariable UUID evidenceId,Authentication auth){try{var e=warranties.openEvidence(caseId,evidenceId,user(auth));return ResponseEntity.ok().contentType(MediaType.parseMediaType(e.mediaType())).body(new InputStreamResource(e.content()));}catch(com.example.campusmarket.dispute.application.EvidenceStorage.NotFoundException ex){return error(HttpStatus.NOT_FOUND,"证据不存在");}catch(com.example.campusmarket.dispute.application.EvidenceStorage.StorageUnavailableException ex){return error(HttpStatus.SERVICE_UNAVAILABLE,"证据存储暂时不可用");}}
    private static UUID user(Authentication a){if(a==null||!(a.getPrincipal() instanceof AuthenticatedUser u))throw new IllegalArgumentException("身份无效");return u.userId();} private static boolean admin(Authentication a){return a!=null&&a.getAuthorities().stream().anyMatch(x->"ROLE_ADMIN".equals(x.getAuthority()));} private static ResponseEntity<byte[]> error(HttpStatus s,String m){return ApiErrors.bytes(s,m);}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=false) public record OpenRequest(int quantity,String reason){}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=false) public record DecisionRequest(WarrantyDecision decision,long compensationAmountFen,String evidenceId){}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=false) public record AssignmentRequest(UUID adminId){}
    @ExceptionHandler({org.springframework.web.bind.MissingRequestHeaderException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.multipart.MultipartException.class})
    ResponseEntity<byte[]> protocolError(Exception ignored){return error(HttpStatus.BAD_REQUEST,"请求参数无效");}
}
