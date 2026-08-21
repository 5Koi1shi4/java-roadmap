package com.example.seckill.api;

import com.example.seckill.application.AlreadyPurchasedException;
import com.example.seckill.application.ProductNotFoundException;
import com.example.seckill.application.SeckillOrderService;
import com.example.seckill.application.SoldOutException;
import com.example.seckill.domain.SeckillOrder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

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
        when(service.placeOrder(7L, 1L)).thenReturn(new SeckillOrder(11L, 7L, 1L, Instant.parse("2026-08-21T10:15:30Z")));

        mockMvc.perform(post("/api/seckill/orders")
                        .contentType(MediaType.APPLICATION_JSON)
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
                        .content("{\"userId\":0,\"productId\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("application/json;charset=UTF-8")))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void mapsProductNotFoundToNotFound() throws Exception {
        when(service.placeOrder(7L, 99L)).thenThrow(new ProductNotFoundException(99L));

        mockMvc.perform(post("/api/seckill/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":7,\"productId\":99}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void mapsSoldOutToConflict() throws Exception {
        when(service.placeOrder(7L, 1L)).thenThrow(new SoldOutException(1L));

        mockMvc.perform(post("/api/seckill/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":7,\"productId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOLD_OUT"));
    }

    @Test
    void mapsAlreadyPurchasedToConflict() throws Exception {
        when(service.placeOrder(7L, 1L)).thenThrow(new AlreadyPurchasedException(7L, 1L));

        mockMvc.perform(post("/api/seckill/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":7,\"productId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_PURCHASED"));
    }
}
