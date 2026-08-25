package com.example.search.api;

import com.example.search.application.search.ProductSearchCriteria;
import com.example.search.application.search.ProductSearchService;
import com.example.search.application.search.ProductSearchResult;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping(value = "/api/products/search", produces = MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
public class ProductSearchController {
    private final ProductSearchService service;
    public ProductSearchController(ProductSearchService service) { this.service = service; }

    @GetMapping
    public ProductSearchResponse search(@RequestParam(required = false) String q,
                                      @RequestParam(required = false) String categoryCode,
                                      @RequestParam(required = false) BigDecimal minPrice,
                                      @RequestParam(required = false) BigDecimal maxPrice,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size,
                                      @RequestParam(defaultValue = "relevance") String sort) {
        return ProductSearchResponse.from(service.search(
                ProductSearchCriteria.of(q, categoryCode, minPrice, maxPrice, page, size, sort)));
    }
}
