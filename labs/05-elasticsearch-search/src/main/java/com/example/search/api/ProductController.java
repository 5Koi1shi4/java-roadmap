package com.example.search.api;

import com.example.search.application.product.ProductCommandService;
import com.example.search.application.product.ProductRepository;
import com.example.search.application.product.ProductView;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/products", produces = "application/json;charset=UTF-8")
public class ProductController {
    private final ProductCommandService products;
    private final ProductRepository repository;

    public ProductController(ProductCommandService products, ProductRepository repository) {
        this.products = products;
        this.repository = repository;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProductResponse> create(@RequestBody CreateProductRequest request) {
        return ResponseEntity.status(201).body(ProductResponse.from(products.create(request.toCommand())));
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable long id) {
        return repository.findById(id).map(ProductView::from).map(ProductResponse::from).orElseThrow(() ->
                new com.example.search.application.product.ProductNotFoundException(id));
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ProductResponse update(@PathVariable long id, @RequestBody UpdateProductRequest request) {
        return ProductResponse.from(products.update(id, request.toCommand()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, @RequestParam long expectedVersion) {
        products.delete(id, expectedVersion);
        return ResponseEntity.noContent().build();
    }
}
