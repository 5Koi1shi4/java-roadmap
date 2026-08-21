package com.example.seckill.api;

import com.example.seckill.application.AlreadyPurchasedException;
import com.example.seckill.application.CreateOrderCommand;
import com.example.seckill.application.IdempotentOrderResult;
import com.example.seckill.application.IdempotencyKeyReusedException;
import com.example.seckill.application.ProductNotFoundException;
import com.example.seckill.application.SeckillOrderService;
import com.example.seckill.application.SoldOutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SeckillOrderController.class)
class SeckillOrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SeckillOrderService service;

    @Test
    void createsOrderWithUtf8Json() throws Exception {
        when(service.placeOrder(eq("create-key"), any(CreateOrderCommand.class)))
                .thenReturn(new IdempotentOrderResult(201, "{\"id\":11,\"userId\":7,\"productId\":1}"));

        mockMvc.perform(post("/api/seckill/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "create-key")
                        .content("{\"userId\":7,\"productId\":1}"))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("application/json;charset=UTF-8")))
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.productId").value(1));
    }

    @Test
    void rejectsInvalidRequestWithValidationError() throws Exception {
        mockMvc.perform(post("/api/seckill/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "validation-key")
                        .content("{\"userId\":0,\"productId\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("application/json;charset=UTF-8")))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void mapsProductNotFoundToNotFound() throws Exception {
        when(service.placeOrder(eq("not-found-key"), any(CreateOrderCommand.class)))
                .thenThrow(new ProductNotFoundException(99L));

        mockMvc.perform(post("/api/seckill/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "not-found-key")
                        .content("{\"userId\":7,\"productId\":99}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void mapsSoldOutToConflict() throws Exception {
        when(service.placeOrder(eq("sold-out-key"), any(CreateOrderCommand.class)))
                .thenThrow(new SoldOutException(1L));

        mockMvc.perform(post("/api/seckill/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "sold-out-key")
                        .content("{\"userId\":7,\"productId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOLD_OUT"));
    }

    @Test
    void mapsAlreadyPurchasedToConflict() throws Exception {
        when(service.placeOrder(eq("already-purchased-key"), any(CreateOrderCommand.class)))
                .thenThrow(new AlreadyPurchasedException(7L, 1L));

        mockMvc.perform(post("/api/seckill/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "already-purchased-key")
                        .content("{\"userId\":7,\"productId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_PURCHASED"));
    }

    @Test
    void requiresIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/seckill/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":7,\"productId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("application/json;charset=UTF-8")))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Idempotency-Key 请求头不能为空"));
    }

    @Test
    void rejectsOverlongIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/seckill/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "x".repeat(129))
                        .content("{\"userId\":7,\"productId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_INVALID"));
    }

    @Test
    void replaysSameHttpStatusAndJsonBodyForSameKey() throws Exception {
        String body = "{\"id\":11,\"userId\":7,\"productId\":1,\"message\":\"下单成功\"}";
        when(service.placeOrder(eq("replay-key"), any(CreateOrderCommand.class)))
                .thenReturn(new IdempotentOrderResult(201, body));

        var first = mockMvc.perform(post("/api/seckill/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "replay-key")
                        .content("{\"userId\":7,\"productId\":1}"))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("application/json;charset=UTF-8")))
                .andReturn();
        var retry = mockMvc.perform(post("/api/seckill/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "replay-key")
                        .content("{\"productId\":1,\"userId\":7}"))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("application/json;charset=UTF-8")))
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(retry.getResponse().getContentAsByteArray())
                .isEqualTo(first.getResponse().getContentAsByteArray());
    }

    @Test
    void rejectsReusedKeyWhenRequestDiffers() throws Exception {
        when(service.placeOrder(eq("reuse-key"), any(CreateOrderCommand.class)))
                .thenThrow(new IdempotencyKeyReusedException("reuse-key"));

        mockMvc.perform(post("/api/seckill/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "reuse-key")
                        .content("{\"userId\":8,\"productId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("application/json;charset=UTF-8")))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.message").value("幂等键已被不同请求复用"));
    }
}
