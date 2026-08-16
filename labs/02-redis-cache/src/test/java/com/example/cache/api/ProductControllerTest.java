package com.example.cache.api;

import com.example.cache.application.ProductQueryService;
import com.example.cache.domain.Product;
import com.example.cache.domain.ProductCache;
import com.example.cache.domain.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProductControllerTest {

    @Test
    void returnsTheProductAsJson() throws Exception {
        Product product = new Product(7L, "Java 编程思想", 9_900L);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProductController(serviceReturning(product))).build();

        mvc.perform(get("/api/products/7"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.name").value("Java 编程思想"))
                .andExpect(jsonPath("$.priceInCents").value(9900));
    }

    @Test
    void returnsNotFoundWhenTheProductDoesNotExist() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProductController(serviceReturning(null))).build();

        mvc.perform(get("/api/products/8"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    private ProductQueryService serviceReturning(Product product) {
        return new ProductQueryService(new FixedRepository(product), new AlwaysMissCache());
    }

    private record FixedRepository(Product product) implements ProductRepository {
        @Override
        public Optional<Product> findById(long id) {
            return Optional.ofNullable(product);
        }

        @Override
        public void update(Product product) {
            throw new UnsupportedOperationException("not needed by controller test");
        }
    }

    private static final class AlwaysMissCache implements ProductCache {
        @Override
        public CacheLookup get(long id) {
            return CacheLookup.miss();
        }

        @Override
        public void put(Product product) {
        }

        @Override
        public void putNegative(long id) {
        }

        @Override
        public void evict(long id) {
        }
    }
}
