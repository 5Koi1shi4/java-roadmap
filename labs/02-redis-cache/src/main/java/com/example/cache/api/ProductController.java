package com.example.cache.api;

import com.example.cache.application.ProductQueryService;
import com.example.cache.application.ProductView;
import com.example.cache.domain.Product;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
public final class ProductController {

    private final ProductQueryService productQueryService;

    public ProductController(ProductQueryService productQueryService) {
        this.productQueryService = productQueryService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> getProduct(@PathVariable long id) {
        ProductView result = productQueryService.getProduct(id);
        if (!result.found()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result.product());
    }
}
