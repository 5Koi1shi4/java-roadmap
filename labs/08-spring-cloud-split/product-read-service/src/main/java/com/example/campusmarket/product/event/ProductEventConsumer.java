package com.example.campusmarket.product.event;

import com.example.campusmarket.product.infrastructure.JdbcProductInbox;
import com.example.campusmarket.product.infrastructure.JdbcProductIndexOutbox;
import com.example.campusmarket.product.infrastructure.JdbcProductProjection;
import com.example.campusmarket.product.infrastructure.JdbcProductReadinessRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/** Inbox、版本投影与索引待办同事务提交；Rabbit ACK 只可在返回后执行。 */
@Service
public class ProductEventConsumer {
    private final ProductSnapshotDecoder decoder;
    private final ProductReplayCompleteDecoder replayCompleteDecoder;
    private final JdbcProductInbox inbox;
    private final JdbcProductProjection projection;
    private final JdbcProductIndexOutbox indexOutbox;
    private final JdbcProductReadinessRepository readiness;

    public ProductEventConsumer(ProductSnapshotDecoder decoder,
                                ProductReplayCompleteDecoder replayCompleteDecoder,
                                JdbcProductInbox inbox, JdbcProductProjection projection,
                                JdbcProductIndexOutbox indexOutbox,
                                JdbcProductReadinessRepository readiness) {
        this.decoder = Objects.requireNonNull(decoder, "事件解码器不能为空");
        this.replayCompleteDecoder = Objects.requireNonNull(replayCompleteDecoder, "replay 完成解码器不能为空");
        this.inbox = Objects.requireNonNull(inbox, "Inbox不能为空");
        this.projection = Objects.requireNonNull(projection, "投影不能为空");
        this.indexOutbox = Objects.requireNonNull(indexOutbox, "索引待办不能为空");
        this.readiness = Objects.requireNonNull(readiness, "投影 readiness 仓储不能为空");
    }

    /** 运行时故障耗尽重试后由 listener 调用；仓储内部用独立事务持久化 BLOCKED。 */
    public void markBlockedAfterExhausted() {
        readiness.markBlocked();
    }

    @Transactional
    public boolean accept(byte[] payload) {
        try {
            Optional<ProductReplayCompleteEvent> replayComplete =
                replayCompleteDecoder.decodeIfPresent(payload);
            if (replayComplete.isPresent()) {
                readiness.markReplayComplete(replayComplete.orElseThrow());
                return true;
            }

            ProductSnapshotEvent event = decoder.decode(payload);
            if (!inbox.complete(event.eventId())) return false;
            if (projection.apply(event)) indexOutbox.enqueue(event);
            readiness.markSnapshotReceived();
            return true;
        } catch (IllegalArgumentException invalidProtocol) {
            // 记录 BLOCKED 使用独立事务，不能随同非法消息的外层事务回滚。
            try {
                readiness.markBlocked();
            } catch (RuntimeException ignored) {
                // 数据库故障时保留协议异常类型，让 listener 继续走其失败路径。
            }
            throw invalidProtocol;
        }
    }
}
