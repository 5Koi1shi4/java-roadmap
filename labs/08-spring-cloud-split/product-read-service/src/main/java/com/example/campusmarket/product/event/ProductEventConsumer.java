package com.example.campusmarket.product.event;

import com.example.campusmarket.product.infrastructure.JdbcProductInbox;
import com.example.campusmarket.product.infrastructure.JdbcProductIndexOutbox;
import com.example.campusmarket.product.infrastructure.JdbcProductProjection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Inbox、版本投影与索引待办同事务提交；Rabbit ACK 只可在返回后执行。 */
@Service
public class ProductEventConsumer {
    private final ProductSnapshotDecoder decoder;
    private final JdbcProductInbox inbox;
    private final JdbcProductProjection projection;
    private final JdbcProductIndexOutbox indexOutbox;

    public ProductEventConsumer(ProductSnapshotDecoder decoder, JdbcProductInbox inbox,
                                JdbcProductProjection projection, JdbcProductIndexOutbox indexOutbox) {
        this.decoder = Objects.requireNonNull(decoder, "事件解码器不能为空");
        this.inbox = Objects.requireNonNull(inbox, "Inbox不能为空");
        this.projection = Objects.requireNonNull(projection, "投影不能为空");
        this.indexOutbox = Objects.requireNonNull(indexOutbox, "索引待办不能为空");
    }

    @Transactional
    public boolean accept(byte[] payload) {
        ProductSnapshotEvent event = decoder.decode(payload);
        if (!inbox.complete(event.eventId())) return false;
        if (projection.apply(event)) indexOutbox.enqueue(event);
        return true;
    }
}
