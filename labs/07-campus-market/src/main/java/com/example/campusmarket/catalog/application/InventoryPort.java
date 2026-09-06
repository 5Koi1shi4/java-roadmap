package com.example.campusmarket.catalog.application;

import java.util.UUID;

/** 商品库存的唯一修改端口，所有实现必须以业务键保证幂等。 */
public interface InventoryPort {
    default boolean deduct(UUID listingId, int quantity, String businessKey) {
        return deduct(listingId, quantity, businessKey, null);
    }
    boolean deduct(UUID listingId, int quantity, String businessKey, UUID orderId);
    boolean restore(UUID listingId, int quantity, String businessKey);
    boolean quarantine(UUID listingId, int quantity, String businessKey);
    /** 卖家显式重新上架隔离库存；绝不由退款路径调用。 */
    boolean relistQuarantined(UUID listingId, int quantity, String businessKey);
    /** 卖家显式报损隔离库存；幂等且不增加可售数量。 */
    boolean scrapQuarantined(UUID listingId, int quantity, String businessKey);
}
