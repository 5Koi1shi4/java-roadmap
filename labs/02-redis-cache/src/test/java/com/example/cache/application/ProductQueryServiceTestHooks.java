package com.example.cache.application;

public final class ProductQueryServiceTestHooks {

    private ProductQueryServiceTestHooks() {
    }

    public static int activeRebuildLockCount(ProductQueryService service) {
        return service.activeRebuildLockCount();
    }
}
