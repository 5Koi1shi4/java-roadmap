package com.example.campusmarket.order.application;

import com.example.campusmarket.catalog.application.InventoryPort;
import com.example.campusmarket.catalog.domain.ListingStatus;
import com.example.campusmarket.catalog.domain.WarrantyTerm;
import com.example.campusmarket.order.domain.TradeOrder;
import com.example.campusmarket.shared.Money;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;

/** 编排买家下单的单一 MySQL 事务。 */
@Service
@Profile("!test")
public class CreateOrderService {
    private final com.example.campusmarket.order.infrastructure.JdbcOrderRepository repository;
    private final InventoryPort inventory;
    private final IdempotentCommandService commands;
    private final OrderCreationHook hook;
    private final ObjectMapper objectMapper;

    public CreateOrderService(com.example.campusmarket.order.infrastructure.JdbcOrderRepository repository,
                              InventoryPort inventory, IdempotentCommandService commands,
                              OrderCreationHook hook, ObjectMapper objectMapper) {
        this.repository = Objects.requireNonNull(repository, "订单仓储不能为空");
        this.inventory = Objects.requireNonNull(inventory, "库存端口不能为空");
        this.commands = Objects.requireNonNull(commands, "幂等服务不能为空");
        this.hook = Objects.requireNonNull(hook, "订单钩子不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "JSON序列化器不能为空");
    }

    public CreateOrderResult create(UUID buyerId, String idempotencyKey, CreateOrderCommand command) {
        return commands.execute(buyerId, idempotencyKey, command, () -> createOnce(buyerId, command));
    }

    private CreateOrderResult createOnce(UUID buyerId, CreateOrderCommand command) {
        var listing = repository.lockListing(command.listingId());
        if (listing == null) throw new ListingNotFoundException();
        if (listing.status() != ListingStatus.ON_SALE) throw new StockConflictException();
        if (listing.sellerId().equals(buyerId)) throw new SelfPurchaseException();
        TradeOrder.ListingSnapshot snapshot = new TradeOrder.ListingSnapshot(
            listing.id(), listing.sellerId(), listing.title(), listing.description(), listing.category(),
            listing.unitPrice(), listing.warrantyTerm(), "SELLER_NON_HUMAN_FUNCTIONAL_FAILURE",
            listing.manufacturerWarrantyProofSnapshot(), listing.manufacturerWarrantyExpiresAt());
        Instant dbNow = repository.currentDatabaseTime();
        TradeOrder order = TradeOrder.create(UUID.randomUUID(), buyerId, listing.sellerId(), snapshot,
            command.quantity(), dbNow);
        repository.insertOrder(order);
        if (!inventory.deduct(listing.id(), order.quantity(), "order:" + order.id(), order.id())) {
            throw new StockConflictException();
        }
        hook.afterInventoryDeducted(order.id());
        repository.insertOrderCreatedOutbox(order, objectMapper);
        hook.afterOrderCreatedOutbox(order.id());
        byte[] response = response(order);
        return new CreateOrderResult(201, response, order.id());
    }

    private byte[] response(TradeOrder order) {
        try {
            var body = new LinkedHashMap<String, Object>();
            body.put("orderId", order.id().toString());
            body.put("status", order.status().name());
            body.put("totalAmountFen", order.totalAmount().fen());
            return objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("订单响应序列化失败", e);
        }
    }

    public record CreateOrderResult(int statusCode, byte[] responseUtf8, UUID orderId) {
        public CreateOrderResult(int statusCode, byte[] responseUtf8) {
            this(statusCode, responseUtf8, null);
        }
        public CreateOrderResult {
            if (statusCode <= 0 || responseUtf8 == null) throw new IllegalArgumentException("订单响应无效");
        }
    }

    public record ListingForOrder(UUID id, UUID sellerId, String title, String description, String category,
                                  Money unitPrice, WarrantyTerm warrantyTerm, String warrantyScope,
                                  String manufacturerWarrantyProofSnapshot, Instant manufacturerWarrantyExpiresAt,
                                  ListingStatus status) { }

    public static class ListingNotFoundException extends RuntimeException { }
    public static class StockConflictException extends RuntimeException { }
    public static class SelfPurchaseException extends RuntimeException { }
}
