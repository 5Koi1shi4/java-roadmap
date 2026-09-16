package com.example.campusmarket.catalog.domain;

import java.util.Set;

public record WarrantyTerm(Integer sellerWarrantyDays) {
    private static final Set<Integer> STANDARD_DAYS = Set.of(30, 90, 180, 365);

    public WarrantyTerm {
        if (sellerWarrantyDays != null && !STANDARD_DAYS.contains(sellerWarrantyDays)) {
            throw new IllegalArgumentException("卖家质保期限必须为30、90、180或365天");
        }
    }

    public static WarrantyTerm none() {
        return new WarrantyTerm(null);
    }

    public static WarrantyTerm sellerWarrantyDays(int days) {
        return new WarrantyTerm(days);
    }
}
