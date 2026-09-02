package com.example.campusmarket.catalog.search;

import com.example.campusmarket.shared.DomainEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** 从 MySQL 商品事实投影到搜索索引；事件只作为变更通知和版本 fencing。 */
@Component
public class SearchProjector {
    private final JdbcTemplate jdbc;
    private final ProductSearchPort search;
    private final SearchGateRepository gate;

    @Autowired
    public SearchProjector(JdbcTemplate jdbc, ProductSearchPort search, SearchGateRepository gate) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC不能为空");
        this.search = Objects.requireNonNull(search, "搜索端口不能为空");
        this.gate = Objects.requireNonNull(gate, "搜索门禁不能为空");
    }

    /** 处理商品事件；旧事件即使重放也不会覆盖更新版本。 */
    @Transactional
    public void project(DomainEvent event) {
        Objects.requireNonNull(event, "商品事件不能为空");
        SearchSchema.requireEventType(event.eventType());
        UUID listingId;
        try {
            listingId = UUID.fromString(event.aggregateId());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("商品事件 aggregateId 无效", e);
        }
        gate.assertProjectionOpen();
        if ("LISTING_OFF_SALE".equals(event.eventType()) || "LISTING_SOLD_OUT".equals(event.eventType())) {
            search.tombstone(listingId.toString(), event.aggregateVersion());
        } else {
            projectListing(listingId, event.aggregateVersion(), search);
        }
    }

    /** 重建期间将文档写入指定索引，不触碰在线别名。 */
    /** Replay a change only while the exact rebuild lease is still current. */
    void projectInto(String index, UUID listingId, long eventVersion, SearchGateRepository.Lease lease) {
        gate.assertLease(lease);
        Objects.requireNonNull(index, "目标索引不能为空");
        Objects.requireNonNull(listingId, "商品ID不能为空");
        projectListing(listingId, eventVersion, new TargetIndex(search, index));
    }

    void projectDocumentInto(String index, ProductSearchPort.ProductDocument document, SearchGateRepository.Lease lease) {
        gate.assertLease(lease);
        Objects.requireNonNull(index, "目标索引不能为空");
        Objects.requireNonNull(document, "商品文档不能为空");
        ProductSearchPort target = new TargetIndex(search, index);
        if ("ON_SALE".equals(document.status()) && document.availableQuantity() > 0) target.index(document);
        else target.tombstone(document.listingId(), document.aggregateVersion());
        gate.assertLease(lease);
    }

    public ProductSearchPort.ProductDocument snapshot(UUID listingId) {
        return jdbc.query("SELECT id,title,description,category,unit_price_fen,available_quantity,status,version FROM listing WHERE id=?",
            rs -> rs.next() ? document(rs.getString("id"), rs.getString("title"), rs.getString("description"),
                rs.getString("category"), rs.getLong("unit_price_fen"), rs.getInt("available_quantity"),
                rs.getString("status"), rs.getLong("version")) : null, listingId.toString());
    }

    private void projectListing(UUID listingId, long eventVersion, ProductSearchPort target) {
        ProductSearchPort.ProductDocument document = snapshot(listingId);
        if (document == null) {
            // Listing rows are retained by the current schema; a missing row is a tombstone.
            target.tombstone(listingId.toString(), eventVersion);
            return;
        }
        long version = Math.max(eventVersion, document.aggregateVersion());
        if (!"ON_SALE".equals(document.status()) || document.availableQuantity() <= 0) {
            target.tombstone(document.listingId(), version);
            return;
        }
        target.index(new ProductSearchPort.ProductDocument(document.listingId(), document.title(), document.description(),
            document.category(), document.unitPriceFen(), document.availableQuantity(), document.status(), version));
    }

    private static ProductSearchPort.ProductDocument document(String id, String title, String description,
                                                               String category, long price, int quantity,
                                                               String status, long version) {
        return new ProductSearchPort.ProductDocument(id, title, description, category, price, quantity, status, version);
    }

    private static final class TargetIndex implements ProductSearchPort {
        private final ProductSearchPort delegate;
        private final String index;
        private TargetIndex(ProductSearchPort delegate, String index) { this.delegate = delegate; this.index = index; }
        @Override public void index(ProductDocument document) {
            delegate.indexInto(index, document);
        }
        @Override public void tombstone(String id, long version) {
            delegate.tombstoneInto(index, id, version);
        }
        @Override public SearchPage search(SearchRequest request) { return delegate.search(request); }
        @Override public void refresh() { delegate.refresh(); }
    }
}
