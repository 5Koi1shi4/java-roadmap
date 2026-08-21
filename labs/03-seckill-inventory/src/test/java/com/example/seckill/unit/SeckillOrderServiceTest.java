package com.example.seckill.unit;

import com.example.seckill.application.SeckillOrderService;
import com.example.seckill.application.SoldOutException;
import com.example.seckill.domain.SeckillOrder;
import com.example.seckill.domain.SeckillProduct;
import com.example.seckill.domain.SeckillRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeckillOrderServiceTest {

    @Test
    void decrementsStockAndReturnsOrder() {
        SeckillRepository repository = mock(SeckillRepository.class);
        SeckillOrderService service = new SeckillOrderService(repository);
        when(repository.decrementStockIfAvailable(1L)).thenReturn(1);
        when(repository.findProduct(1L)).thenReturn(java.util.Optional.of(new SeckillProduct(1L, "商品", 1)));
        when(repository.insertOrder(7L, 1L)).thenReturn(new SeckillOrder(11L, 7L, 1L, Instant.now()));

        assertThat(service.placeOrder(7L, 1L).id()).isEqualTo(11L);
    }

    @Test
    void throwsSoldOutWhenConditionalUpdateAffectsNoRow() {
        SeckillRepository repository = mock(SeckillRepository.class);
        SeckillOrderService service = new SeckillOrderService(repository);
        when(repository.decrementStockIfAvailable(1L)).thenReturn(0);
        when(repository.findProduct(1L)).thenReturn(java.util.Optional.of(new SeckillProduct(1L, "商品", 1)));

        assertThatThrownBy(() -> service.placeOrder(7L, 1L))
                .isInstanceOf(SoldOutException.class);
        verify(repository, never()).insertOrder(anyLong(), anyLong());
    }
}
