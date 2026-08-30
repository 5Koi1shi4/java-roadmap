package com.example.campusmarket.catalog.api;

import com.example.campusmarket.catalog.application.ListingRepository;
import com.example.campusmarket.catalog.application.ListingService;
import com.example.campusmarket.catalog.domain.Listing;
import com.example.campusmarket.identity.application.AuthenticatedUser;
import com.example.campusmarket.storage.MinioPrivateObjectStorage;
import com.example.campusmarket.storage.PrivateObjectStorage;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping(path = "/api/listings", produces = "application/json; charset=UTF-8")
public class ListingController {
    private final ListingService service;
    private final PrivateObjectStorage storage;

    public ListingController(ListingService service, PrivateObjectStorage storage) {
        this.service = service;
        this.storage = storage;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ListingResponse> create(@RequestBody CreateRequest request, Authentication authentication) {
        Listing listing = service.createDraft(user(authentication), request.title(), request.description(), request.category(),
            request.unitPriceFen(), request.availableQuantity(), request.sellerWarrantyDays());
        return ResponseEntity.status(HttpStatus.CREATED).body(ListingResponse.from(listing));
    }

    @PostMapping(path = "/{listingId}/publish")
    public ListingResponse publish(@PathVariable UUID listingId, Authentication authentication) {
        return ListingResponse.from(service.publish(user(authentication), listingId));
    }

    @PostMapping(path = "/{listingId}/off-sale")
    public ResponseEntity<Void> offSale(@PathVariable UUID listingId, Authentication authentication) {
        service.takeOffSale(user(authentication), listingId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/{listingId}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaResponse> upload(@PathVariable UUID listingId, @RequestPart("file") MultipartFile file,
                                                  Authentication authentication) throws IOException {
        ListingRepository.MediaRecord media = service.addMedia(user(authentication), listingId, file.getOriginalFilename(),
            file.getContentType(), file.getInputStream());
        return ResponseEntity.status(HttpStatus.CREATED).body(MediaResponse.from(media));
    }

    @GetMapping(path = "/{listingId}/media/{mediaId}")
    public ResponseEntity<InputStreamResource> open(@PathVariable UUID listingId, @PathVariable UUID mediaId,
                                                    Authentication authentication) {
        ListingService.MediaContent metadata = service.openMedia(user(authentication), listingId, mediaId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(metadata.mediaType()))
            .header(HttpHeaders.CONTENT_LENGTH, Long.toString(metadata.sizeBytes()))
            .body(new InputStreamResource(storage.open(metadata.objectKey())));
    }

    private static UUID user(Authentication authentication) {
        return ((AuthenticatedUser) authentication.getPrincipal()).userId();
    }

    @ExceptionHandler(ListingService.NotFoundException.class)
    ResponseEntity<ErrorResponse> notFound() { return ResponseEntity.status(404).body(new ErrorResponse("资源不存在")); }

    @ExceptionHandler(MinioPrivateObjectStorage.StorageUnavailableException.class)
    ResponseEntity<ErrorResponse> unavailable() { return ResponseEntity.status(503).body(new ErrorResponse("对象存储暂时不可用")); }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ResponseEntity<ErrorResponse> badRequest(RuntimeException e) { return ResponseEntity.badRequest().body(new ErrorResponse("请求参数无效")); }

    public record CreateRequest(String title, String description, String category, long unitPriceFen,
                                int availableQuantity, Integer sellerWarrantyDays) { }
    public record ListingResponse(String id, String title, String status, long unitPriceFen, int availableQuantity) {
        static ListingResponse from(Listing l) { return new ListingResponse(l.id().toString(), l.title(), l.status().name(), l.unitPrice().fen(), l.availableQuantity()); }
    }
    public record MediaResponse(String id, String mediaType, long sizeBytes) {
        static MediaResponse from(ListingRepository.MediaRecord m) { return new MediaResponse(m.id().toString(), m.mediaType(), m.sizeBytes()); }
    }
    public record ErrorResponse(String error) { }
}
