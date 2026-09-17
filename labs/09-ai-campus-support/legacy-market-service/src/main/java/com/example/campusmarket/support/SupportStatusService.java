package com.example.campusmarket.support;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 只读支持状态接口的应用边界。 */
@Service
@Profile("!test")
public final class SupportStatusService {
    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 20;

    private final JdbcSupportStatusRepository repository;
    private final SupportCursor cursors;

    public SupportStatusService(JdbcSupportStatusRepository repository, SupportCursor cursors) {
        this.repository = Objects.requireNonNull(repository, "支持状态仓储不能为空");
        this.cursors = Objects.requireNonNull(cursors, "支持状态游标不能为空");
    }

    public StatusView find(String type, UUID id, UUID userId) {
        SupportCursor.validateType(type);
        Objects.requireNonNull(id, "资源 ID 不能为空");
        Objects.requireNonNull(userId, "用户 ID 不能为空");
        return switch (type) {
            case "orders" -> repository.order(id, userId);
            case "disputes" -> repository.dispute(id, userId);
            case "warranties" -> repository.warranty(id, userId);
            default -> throw new IllegalArgumentException("资源类型无效");
        };
    }

    public StatusPage list(String type, UUID userId, Integer requestedLimit, String cursor) {
        SupportCursor.validateType(type);
        Objects.requireNonNull(userId, "用户 ID 不能为空");
        int limit = normalizeLimit(requestedLimit);
        SupportCursor.Position position = cursor == null
            ? null : cursors.decode(cursor, userId, type);

        List<StatusView> rows = switch (type) {
            case "orders" -> repository.orders(userId, createdAt(position), id(position), limit);
            case "disputes" -> repository.disputes(userId, createdAt(position), id(position), limit);
            case "warranties" -> repository.warranties(userId, createdAt(position), id(position), limit);
            default -> throw new IllegalArgumentException("资源类型无效");
        };
        if (rows.size() <= limit) {
            return new StatusPage(rows, null);
        }
        List<StatusView> visible = List.copyOf(rows.subList(0, limit));
        StatusView last = visible.get(visible.size() - 1);
        return new StatusPage(visible, cursors.encode(userId, type, last.createdAt(), last.id()));
    }

    public StatusPage list(String type, UUID userId, int requestedLimit, String cursor) {
        return list(type, userId, Integer.valueOf(requestedLimit), cursor);
    }

    private static int normalizeLimit(Integer requestedLimit) {
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("分页大小无效");
        }
        return limit;
    }

    private static Instant createdAt(SupportCursor.Position position) {
        return position == null ? null : position.createdAt();
    }

    private static UUID id(SupportCursor.Position position) {
        return position == null ? null : position.id();
    }

    public record StatusView(UUID id, String type, String status, Instant createdAt, Instant deadline) {
        public StatusView {
            Objects.requireNonNull(id, "资源 ID 不能为空");
            Objects.requireNonNull(type, "资源类型不能为空");
            Objects.requireNonNull(status, "资源状态不能为空");
            Objects.requireNonNull(createdAt, "创建时间不能为空");
        }
    }

    public record StatusPage(List<StatusView> items, String nextCursor) {
        public StatusPage {
            items = List.copyOf(Objects.requireNonNull(items, "资源列表不能为空"));
        }
    }
}
