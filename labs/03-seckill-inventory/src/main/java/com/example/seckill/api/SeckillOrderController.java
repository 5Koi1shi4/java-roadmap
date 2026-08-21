package com.example.seckill.api;

import com.example.seckill.application.SeckillOrderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/seckill/orders")
public class SeckillOrderController {
    private final SeckillOrderService service;

    public SeckillOrderController(SeckillOrderService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<SeckillOrderResponse> placeOrder(
            @Valid @RequestBody CreateSeckillOrderRequest request) {
        SeckillOrderResponse response = SeckillOrderResponse.from(
                service.placeOrder(request.userId(), request.productId()));
        return ResponseEntity.created(URI.create("/api/seckill/orders/" + response.id())).body(response);
    }
}
