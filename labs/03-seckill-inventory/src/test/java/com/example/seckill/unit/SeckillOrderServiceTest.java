package com.example.seckill.unit;

import com.example.seckill.application.SeckillOrderService;
import com.example.seckill.application.CreateOrderCommand;
import com.example.seckill.application.AlreadyPurchasedException;
import com.example.seckill.application.IdempotencyKeyReusedException;
import com.example.seckill.application.IdempotentOrderResult;
import com.example.seckill.application.SoldOutException;
import com.example.seckill.domain.SeckillOrder;
import com.example.seckill.domain.SeckillProduct;
import com.example.seckill.domain.SeckillRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.seckill.api.ApiExceptionHandler;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;

class SeckillOrderServiceTest {

    @Test
    void doesNotExecuteWhenInsertCompetesWithFreshProcessingRecord() {
        SeckillRepository repository = mock(SeckillRepository.class);
        SeckillOrderService service = new SeckillOrderService(repository);
        when(repository.insertProcessing(anyString(), anyString())).thenReturn(0);
        String body = "{\"userId\":7,\"productId\":1}";
        var processing = new com.example.seckill.domain.IdempotencyRecord(
                "race-processing-key", service.requestHash(body), "PROCESSING", null, null,
                Instant.now(), Instant.now());
        when(repository.findByKey("race-processing-key")).thenReturn(java.util.Optional.empty());
        when(repository.findByKeyForUpdate("race-processing-key")).thenReturn(java.util.Optional.of(processing));
        when(repository.takeOverProcessingIfExpired(eq("race-processing-key"), any())).thenReturn(0);

        IdempotentOrderResult result = service.placeOrder("race-processing-key",
                new CreateOrderCommand(7L, 1L, body));

        assertThat(result.httpStatus()).isEqualTo(409);
        assertThat(result.responseBody()).contains("REQUEST_IN_PROGRESS");
        verify(repository, never()).findProduct(anyLong());
        verify(repository, never()).insertOrder(anyLong(), anyLong());
    }

    @Test
    void mapsDeadlockToUtf8RetryableHttpError() throws Exception {
        var response = new ApiExceptionHandler().retryableDatabaseConflict(
                new DeadlockLoserDataAccessException("deadlock", new SQLException("1213")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getContentType().toString()).containsIgnoringCase("charset=UTF-8");
        assertThat(new ObjectMapper().writeValueAsString(response.getBody()))
                .contains("RETRYABLE_DATABASE_CONFLICT")
                .contains("数据库锁冲突");
    }

    @Test
    void locksAfterUniqueKeyCompetitionAndReplaysTerminalResponse() {
        SeckillRepository repository = mock(SeckillRepository.class);
        SeckillOrderService service = new SeckillOrderService(repository);
        String body = "{\"userId\":7,\"productId\":1}";
        var terminal = new com.example.seckill.domain.IdempotencyRecord(
                "race-key", service.requestHash(body), "SUCCEEDED", 201, "{\"id\":11}", Instant.now(), Instant.now());
        when(repository.findByKey("race-key")).thenReturn(java.util.Optional.empty());
        when(repository.findByKeyForUpdate("race-key")).thenReturn(java.util.Optional.of(terminal));

        IdempotentOrderResult result = service.placeOrder("race-key", new CreateOrderCommand(7L, 1L, body));

        assertThat(result).isEqualTo(new IdempotentOrderResult(201, "{\"id\":11}"));
        verify(repository).insertProcessing(eq("race-key"), eq(service.requestHash(body)));
        verify(repository).findByKeyForUpdate("race-key");
        verify(repository).findByKey("race-key");
    }

    @Test
    void takesOverExpiredProcessingRecordBeforeExecutingOrder() {
        SeckillRepository repository = mock(SeckillRepository.class);
        SeckillOrderService service = new SeckillOrderService(repository);
        when(repository.insertProcessing(anyString(), anyString())).thenReturn(1);
        String body = "{\"userId\":7,\"productId\":1}";
        var expired = new com.example.seckill.domain.IdempotencyRecord(
                "expired-key", service.requestHash(body), "PROCESSING", null, null,
                Instant.now().minusSeconds(120), Instant.now().minusSeconds(120));
        when(repository.findByKey("expired-key")).thenReturn(java.util.Optional.of(expired));
        when(repository.findByKeyForUpdate("expired-key")).thenReturn(java.util.Optional.of(expired));
        when(repository.takeOverProcessingIfExpired(eq("expired-key"), any())).thenReturn(1);
        when(repository.findProduct(1L)).thenReturn(java.util.Optional.of(new SeckillProduct(1L, "商品", 1)));
        when(repository.decrementStockIfAvailable(1L)).thenReturn(1);
        when(repository.insertOrder(7L, 1L)).thenReturn(new SeckillOrder(11L, 7L, 1L, Instant.now()));

        IdempotentOrderResult result = service.placeOrder("expired-key", new CreateOrderCommand(7L, 1L, body));

        assertThat(result.httpStatus()).isEqualTo(201);
        verify(repository).takeOverProcessingIfExpired(eq("expired-key"), any());
        verify(repository).saveResponse(eq("expired-key"), eq(201), anyString());
    }

    @Test
    void replaysPersistedResponseForSameKeyAndSameRequest() {
        SeckillRepository repository = mock(SeckillRepository.class);
        SeckillOrderService service = new SeckillOrderService(repository);
        when(repository.insertProcessing(anyString(), anyString())).thenReturn(1);
        String body = "{\"userId\":7,\"productId\":1}";
        when(repository.findByKey("retry-key")).thenReturn(java.util.Optional.of(new com.example.seckill.domain.IdempotencyRecord(
                "retry-key", service.requestHash(body), "SUCCEEDED", 201, "{\"id\":11}", Instant.now(), Instant.now())));

        IdempotentOrderResult result = service.placeOrder("retry-key", new CreateOrderCommand(7L, 1L, body));

        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.responseBody()).isEqualTo("{\"id\":11}");
        verify(repository, never()).insertProcessing(anyString(), anyString());
    }

    @Test
    void rejectsSameKeyWhenCompleteRequestBodyDiffers() {
        SeckillRepository repository = mock(SeckillRepository.class);
        SeckillOrderService service = new SeckillOrderService(repository);
        when(repository.insertProcessing(anyString(), anyString())).thenReturn(1);
        String original = "{\"userId\":7,\"productId\":1}";
        when(repository.findByKey("retry-key")).thenReturn(java.util.Optional.of(new com.example.seckill.domain.IdempotencyRecord(
                "retry-key", service.requestHash(original), "SUCCEEDED", 201, "{}", Instant.now(), Instant.now())));

        assertThatThrownBy(() -> service.placeOrder("retry-key", new CreateOrderCommand(7L, 1L,
                "{\"userId\":7,\"productId\":2}")))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void persistsSoldOutResponseForReplay() {
        SeckillRepository repository = mock(SeckillRepository.class);
        SeckillOrderService service = new SeckillOrderService(repository);
        when(repository.insertProcessing(anyString(), anyString())).thenReturn(1);
        String body = "{\"userId\":7,\"productId\":1}";
        when(repository.findByKey("sold-out-key")).thenReturn(java.util.Optional.empty());
        when(repository.decrementStockIfAvailable(1L)).thenReturn(0);
        when(repository.findProduct(1L)).thenReturn(java.util.Optional.of(new SeckillProduct(1L, "商品", 0)));

        IdempotentOrderResult result = service.placeOrder("sold-out-key", new CreateOrderCommand(7L, 1L, body));

        assertThat(result.httpStatus()).isEqualTo(409);
        verify(repository).insertProcessing("sold-out-key", service.requestHash(body));
        verify(repository).saveResponse(eq("sold-out-key"), eq(409), eq(result.responseBody()));
    }

    @Test
    void persistsAlreadyPurchasedAsSucceededAndProducesValidUtf8Json() throws Exception {
        SeckillRepository repository = mock(SeckillRepository.class);
        SeckillOrderService service = new SeckillOrderService(repository);
        when(repository.insertProcessing(anyString(), anyString())).thenReturn(1);
        String body = "{\"userId\":7,\"productId\":1,\"note\":\"中文\"}";
        when(repository.findByKey("duplicate-key")).thenReturn(java.util.Optional.empty());
        when(repository.findProduct(1L)).thenReturn(java.util.Optional.of(new SeckillProduct(1L, "商品", 1)));
        when(repository.decrementStockIfAvailable(1L)).thenReturn(1);
        when(repository.insertOrder(7L, 1L)).thenThrow(new AlreadyPurchasedException(7L, 1L));

        IdempotentOrderResult result = service.placeOrder("duplicate-key",
                new CreateOrderCommand(7L, 1L, body));

        assertThat(result.httpStatus()).isEqualTo(409);
        JsonNode response = new ObjectMapper().readTree(result.responseBody());
        assertThat(response.get("code").asText()).isEqualTo("ALREADY_PURCHASED");
        assertThat(response.get("message").asText()).contains("已购买");
        verify(repository).saveResponse("duplicate-key", 409, result.responseBody());
    }

    @Test
    void escapesControlCharactersInBusinessErrorJson() throws Exception {
        SeckillRepository repository = mock(SeckillRepository.class);
        SeckillOrderService service = new SeckillOrderService(repository);
        when(repository.insertProcessing(anyString(), anyString())).thenReturn(1);
        String body = "{\"userId\":7,\"productId\":1}";
        when(repository.findByKey("escaped-key")).thenReturn(java.util.Optional.empty());
        when(repository.findProduct(1L)).thenReturn(java.util.Optional.of(new SeckillProduct(1L, "商品", 1)));
        when(repository.decrementStockIfAvailable(1L)).thenReturn(1);
        when(repository.insertOrder(7L, 1L)).thenThrow(new AlreadyPurchasedException(7L, 1L) {
            @Override
            public String getMessage() {
                return "包含\\反斜杠、\"引号\"和换行\n字符";
            }
        });

        IdempotentOrderResult result = service.placeOrder("escaped-key",
                new CreateOrderCommand(7L, 1L, body));

        assertThat(new ObjectMapper().readTree(result.responseBody()).get("message").asText())
                .isEqualTo("包含\\反斜杠、\"引号\"和换行\n字符");
    }

    @Test
    void hashesCompleteRequestBodyIncludingAdditionalFields() {
        SeckillRepository repository = mock(SeckillRepository.class);
        SeckillOrderService service = new SeckillOrderService(repository);
        String completeBody = "{\"userId\":7,\"productId\":1,\"coupon\":\"SPRING\",\"metadata\":{\"渠道\":\"web\"}}";

        assertThat(service.requestHash(completeBody))
                .isNotEqualTo(service.requestHash("{\"userId\":7,\"productId\":1}"));
        assertThat(new CreateOrderCommand(7L, 1L, completeBody).normalizedRequestBody())
                .isEqualTo(completeBody);
    }

    @Test
    void propagatesUnexpectedFailureWithoutSavingResponse() {
        SeckillRepository repository = mock(SeckillRepository.class);
        SeckillOrderService service = new SeckillOrderService(repository);
        when(repository.insertProcessing(anyString(), anyString())).thenReturn(1);
        when(repository.findByKey("system-error-key")).thenReturn(java.util.Optional.empty());
        when(repository.findProduct(1L)).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.placeOrder("system-error-key", new CreateOrderCommand(7L, 1L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
        verify(repository).insertProcessing(eq("system-error-key"), anyString());
        verify(repository, never()).saveResponse(anyString(), anyInt(), anyString());
    }

    @Test
    void decrementsStockAndReturnsOrder() {
        SeckillRepository repository = mock(SeckillRepository.class);
        SeckillOrderService service = new SeckillOrderService(repository);
        when(repository.insertProcessing(anyString(), anyString())).thenReturn(1);
        when(repository.decrementStockIfAvailable(1L)).thenReturn(1);
        when(repository.findProduct(1L)).thenReturn(java.util.Optional.of(new SeckillProduct(1L, "商品", 1)));
        when(repository.insertOrder(7L, 1L)).thenReturn(new SeckillOrder(11L, 7L, 1L, Instant.now()));

        assertThat(service.placeOrder(7L, 1L).id()).isEqualTo(11L);
    }

    @Test
    void throwsSoldOutWhenConditionalUpdateAffectsNoRow() {
        SeckillRepository repository = mock(SeckillRepository.class);
        SeckillOrderService service = new SeckillOrderService(repository);
        when(repository.insertProcessing(anyString(), anyString())).thenReturn(1);
        when(repository.decrementStockIfAvailable(1L)).thenReturn(0);
        when(repository.findProduct(1L)).thenReturn(java.util.Optional.of(new SeckillProduct(1L, "商品", 1)));

        assertThatThrownBy(() -> service.placeOrder(7L, 1L))
                .isInstanceOf(SoldOutException.class);
        verify(repository, never()).insertOrder(anyLong(), anyLong());
    }
}
