package com.example.campusmarket.product.api;

import com.example.campusmarket.product.search.ProductSearchPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping(path = {"/api/search", "/api/listings/search"}, produces = "application/json; charset=UTF-8")
public final class SearchController {
    private static final MediaType JSON_UTF8 = MediaType.parseMediaType("application/json; charset=UTF-8");

    private final ProductSearchPort search;

    public SearchController(ProductSearchPort search) {
        this.search = Objects.requireNonNull(search, "搜索端口不能为空");
    }

    @GetMapping
    public ResponseEntity<?> search(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Long minPriceFen,
            @RequestParam(required = false) Long maxPriceFen,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String searchAfter) {
        try {
            ProductSearchPort.SearchPage page = search.search(new ProductSearchPort.SearchRequest(
                    keyword, category, minPriceFen, maxPriceFen, 0, size, searchAfter));
            return ResponseEntity.ok().contentType(JSON_UTF8).body(page);
        } catch (ProductSearchPort.SearchUnavailableException e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "搜索服务暂时不可用");
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "搜索参数无效");
        }
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<SearchError> invalidParameter(Exception ignored) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "搜索参数无效");
    }

    private static ResponseEntity<SearchError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).contentType(JSON_UTF8)
                .body(new SearchError(code, message, UUID.randomUUID().toString()));
    }

    public record SearchError(String code, String message, String correlationId) {
    }
}
