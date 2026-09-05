package com.example.campusmarket.dispute.application;

import com.example.campusmarket.dispute.domain.DisputeCase;
import com.example.campusmarket.dispute.domain.DisputeDecision;
import com.example.campusmarket.dispute.domain.DisputeReason;
import com.example.campusmarket.dispute.infrastructure.JdbcDisputeRepository;
import com.example.campusmarket.order.application.IdempotentCommandService;
import com.example.campusmarket.order.domain.OrderStatus;
import com.example.campusmarket.order.infrastructure.JdbcOrderLifecycleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;

/** 普通争议用例：订单冻结、卖家回应和管理员单轮裁决均由 MySQL 事务编排。 */
@Service
@Profile("!test")
public final class DisputeService {
    private final JdbcDisputeRepository repository;
    private final IdempotentCommandService commands;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public DisputeService(JdbcDisputeRepository repository, IdempotentCommandService commands, ObjectMapper mapper,
                          org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.repository = Objects.requireNonNull(repository, "争议仓储不能为空");
        this.commands = Objects.requireNonNull(commands, "幂等服务不能为空");
        this.mapper = Objects.requireNonNull(mapper, "JSON序列化器不能为空");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "事务管理器不能为空"));
    }

    public Result open(UUID orderId, UUID actorId, String key, int quantity, String reason, byte[] request) {
        DisputeReason parsed = DisputeReason.parse(reason);
        byte[] body = request == null ? ("{" + quantity + "," + parsed.name() + "}").getBytes(StandardCharsets.UTF_8) : request.clone();
        byte[] response = commands.executeLifecycle(actorId, key, body, orderId, () -> openOnce(orderId, actorId, quantity, parsed));
        return new Result(201, response, fieldUuid(response, "disputeId"));
    }

    private byte[] openOnce(UUID orderId, UUID actorId, int quantity, DisputeReason reason) {
        var order = repository.lockOrder(orderId);
        if (order == null) throw new NotFoundException();
        if (!order.buyerId().equals(actorId)) throw new NotFoundException();
        if (order.status() != OrderStatus.AWAITING_RECEIPT && order.status() != OrderStatus.AFTERSALE_WINDOW)
            throw new ConflictException();
        Instant now = repository.databaseNow();
        if (order.status() == OrderStatus.AFTERSALE_WINDOW && !DisputeCase.isReasonAllowed(reason, order.t0(), now)
            && !reason.isExclusion()) throw new ConflictException();
        int used = repository.cumulativeReservedQuantity(orderId);
        if (used + quantity > order.quantity()) throw new QuantityConflictException();
        UUID id = UUID.randomUUID();
        DisputeCase file = order.status() == OrderStatus.AWAITING_RECEIPT
            ? DisputeCase.openBeforeReceipt(id, orderId, order.buyerId(), order.sellerId(), order.quantity(), quantity, reason, now)
            : DisputeCase.open(id, orderId, order.buyerId(), order.sellerId(), order.quantity(), quantity, reason, order.t0(), now);
        repository.insert(file, now);
        if (repository.markDisputed(orderId, actorId, order.version(), now) != 1) throw new ConflictException();
        return json(MapBuilder.of("disputeId", id, "orderId", orderId, "status", "OPEN", "reason", reason.name(), "disputedQuantity", quantity));
    }

    public Result respond(UUID caseId, UUID actorId, String key, String response, byte[] request) {
        JdbcDisputeRepository.CaseRow file = requireCase(caseId);
        byte[] body = request == null ? String.valueOf(response).getBytes(StandardCharsets.UTF_8) : request.clone();
        byte[] saved = commands.executeLifecycle(actorId, key, body, file.orderId(), () -> respondOnce(caseId, actorId, response));
        return new Result(200, saved, caseId);
    }

    private byte[] respondOnce(UUID caseId, UUID actorId, String response) {
        return transactions.execute(status -> {
            JdbcDisputeRepository.CaseRow hint = repository.find(caseId);
            if (hint == null) throw new NotFoundException();
            var order = repository.lockOrder(hint.orderId());
            JdbcDisputeRepository.CaseRow file = repository.lock(caseId);
            if (file == null) throw new NotFoundException();
            if (order == null || !order.sellerId().equals(actorId)) throw new NotFoundException();
            if (response == null || response.isBlank() || response.length() > 2000) throw new IllegalArgumentException("卖家回应无效");
            if (repository.markSellerResponded(caseId, file.version(), repository.databaseNow(), response) != 1) throw new ConflictException();
            return json(MapBuilder.of("disputeId", caseId, "status", "SELLER_RESPONDED"));
        });
    }

    public Result assign(UUID caseId, UUID adminId, UUID targetAdminId) {
        if (!adminId.equals(targetAdminId)) throw new ForbiddenException();
        return transactions.execute(status -> {
            JdbcDisputeRepository.CaseRow file = repository.lock(caseId);
            if (file == null) throw new NotFoundException();
            Instant now = repository.databaseNow();
            if (repository.assignAdmin(caseId, targetAdminId, file.version(), now) != 1) throw new ConflictException();
            return new Result(200, json(MapBuilder.of("disputeId", caseId, "status", "UNDER_REVIEW", "assignedAdminId", targetAdminId)), caseId);
        });
    }

    public Result decide(UUID caseId, UUID adminId, String key, DisputeDecision choice, int approvedQuantity, byte[] request) {
        JdbcDisputeRepository.CaseRow current = requireCase(caseId);
        if (current.assignedAdminId() != null && !current.assignedAdminId().equals(adminId)) throw new NotFoundException();
        byte[] body = request == null ? (choice.name() + ":" + approvedQuantity).getBytes(StandardCharsets.UTF_8) : request.clone();
        byte[] saved = commands.executeLifecycle(adminId, key, body, current.orderId(), () -> decideOnce(caseId, adminId, choice, approvedQuantity));
        return new Result(200, saved, caseId);
    }

    private byte[] decideOnce(UUID caseId, UUID adminId, DisputeDecision choice, int approvedQuantity) {
        return transactions.execute(status -> {
            JdbcDisputeRepository.CaseRow hint = repository.find(caseId);
            if (hint == null) throw new NotFoundException();
            var order = repository.lockOrder(hint.orderId());
            JdbcDisputeRepository.CaseRow file = repository.lock(caseId);
            if (file == null) throw new NotFoundException();
            if (order == null || (file.assignedAdminId() != null && !file.assignedAdminId().equals(adminId))) throw new NotFoundException();
            DisputeCase domain = hydrate(file, order);
            if (choice == null) throw new IllegalArgumentException("裁决不能为空");
            if (choice != DisputeDecision.REJECT) {
                int used = repository.cumulativeReservedQuantity(order.id()) - file.disputedQuantity();
                if (used + approvedQuantity > order.quantity()) throw new QuantityConflictException();
            }
            domain.decide(choice, approvedQuantity);
            Instant now = repository.databaseNow();
            if (repository.decide(caseId, file.version(), choice, approvedQuantity, now) != 1) throw new ConflictException();
            if (choice == DisputeDecision.REJECT && repository.releaseOrderAfterRejection(order.id(), adminId, order.version(), now) != 1)
                throw new ConflictException();
            return json(MapBuilder.of("disputeId", caseId, "status", choice == DisputeDecision.REJECT ? "REJECTED" : "RESOLVED",
                "decision", choice, "approvedQuantity", approvedQuantity));
        });
    }

    public JdbcDisputeRepository.CaseRow requireCase(UUID caseId) {
        JdbcDisputeRepository.CaseRow file = repository.find(caseId);
        if (file == null) throw new NotFoundException();
        return file;
    }

    private static DisputeCase hydrate(JdbcDisputeRepository.CaseRow file, JdbcOrderLifecycleRepository.OrderRow order) {
        if (order.status() == OrderStatus.AWAITING_RECEIPT)
            return DisputeCase.openBeforeReceipt(file.id(), file.orderId(), order.buyerId(), order.sellerId(), order.quantity(), file.disputedQuantity(), file.reason(), file.openedAt());
        DisputeCase d = DisputeCase.open(file.id(), file.orderId(), order.buyerId(), order.sellerId(), order.quantity(), file.disputedQuantity(), file.reason(), order.t0(), file.openedAt());
        if (file.status() == DisputeCase.Status.SELLER_RESPONDED) d.sellerResponded();
        return d;
    }

    private byte[] json(Object value) { try { return mapper.writeValueAsBytes(value); } catch (Exception ex) { throw new IllegalStateException("争议响应序列化失败", ex); } }
    private static UUID fieldUuid(byte[] response, String field) { try { String text = new String(response, StandardCharsets.UTF_8); int p = text.indexOf('"' + field + '"'); int colon = text.indexOf(':', p); int a = text.indexOf('"', colon + 1); int b = text.indexOf('"', a + 1); return UUID.fromString(text.substring(a + 1, b)); } catch (Exception e) { return null; } }
    private static final class MapBuilder extends LinkedHashMap<String,Object> { static MapBuilder of(Object... values) { MapBuilder map = new MapBuilder(); for (int i=0;i<values.length;i+=2) map.put(String.valueOf(values[i]), values[i+1]); return map; } }
    public record Result(int statusCode, byte[] responseUtf8, UUID disputeId) {}
    public static class NotFoundException extends RuntimeException {}
    public static class ForbiddenException extends RuntimeException {}
    public static class ConflictException extends RuntimeException {}
    public static class QuantityConflictException extends ConflictException {}
}
