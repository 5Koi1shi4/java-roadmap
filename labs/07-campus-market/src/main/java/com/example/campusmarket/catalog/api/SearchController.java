package com.example.campusmarket.catalog.api;

import com.example.campusmarket.api.ApiErrors;
import com.example.campusmarket.catalog.search.ProductSearchPort;
import com.example.campusmarket.catalog.search.ElasticsearchProductSearch;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 商品搜索 HTTP 薄适配器；MySQL 事实源和 ES 投影仍由搜索端口负责。 */
@RestController
@RequestMapping(path = {"/api/search", "/api/listings/search"}, produces = "application/json; charset=UTF-8")
public final class SearchController {
    private final ProductSearchPort search;

    public SearchController(ProductSearchPort search) {
        this.search = java.util.Objects.requireNonNull(search, "搜索端口不能为空");
    }

    @GetMapping
    public ResponseEntity<?> search(@RequestParam(defaultValue = "") String keyword,
                                    @RequestParam(required = false) String category,
                                    @RequestParam(required = false) Long minPriceFen,
                                    @RequestParam(required = false) Long maxPriceFen,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(required = false) String searchAfter) {
        try {
            ProductSearchPort.SearchPage page = search.search(new ProductSearchPort.SearchRequest(
                keyword, category, minPriceFen, maxPriceFen, 0, size, searchAfter));
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/json; charset=UTF-8"))
                .body(page);
        } catch (IllegalArgumentException e) {
            return ApiErrors.entity(HttpStatus.BAD_REQUEST, "搜索参数无效");
        } catch (ElasticsearchProductSearch.SearchUnavailableException e) {
            return ApiErrors.entity(HttpStatus.SERVICE_UNAVAILABLE, "搜索服务暂时不可用");
        }
    }
}
