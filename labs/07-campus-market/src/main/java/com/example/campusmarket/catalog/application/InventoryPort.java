package com.example.campusmarket.catalog.application;

import java.util.UUID;

/** 商品库存的唯一修改端口，所有实现必须以业务键保证幂等。 */
public interface InventoryPort {
    boolean deduct(UUID listingId, int quantity, String businessKey);
    boolean restore(UUID listingId, int quantity, String businessKey);
    boolean quarantine(UUID listingId, int quantity, String businessKey);
}
