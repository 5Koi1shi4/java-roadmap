package com.example.campusmarket.unit.catalog;

import com.example.campusmarket.catalog.domain.Listing;
import com.example.campusmarket.catalog.domain.ListingStatus;
import com.example.campusmarket.catalog.domain.WarrantyTerm;
import com.example.campusmarket.shared.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListingTest {
    @Test
    void publishesOnlyCompleteListing() {
        Listing draft = Listing.draft(UUID.randomUUID(), "二手机械键盘", Money.ofFen(3500), 6,
            WarrantyTerm.sellerWarrantyDays(90));

        assertThatThrownBy(draft::publish).isInstanceOf(IllegalStateException.class);

        draft.addMedia(UUID.randomUUID());
        assertThat(draft.publish().status()).isEqualTo(ListingStatus.ON_SALE);
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 29, 31, 366})
    void rejectsNonStandardSellerWarrantyDays(int days) {
        assertThatThrownBy(() -> WarrantyTerm.sellerWarrantyDays(days))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void manufacturerWarrantyIsNotSellerWarranty() {
        Listing listing = Listing.draft(UUID.randomUUID(), "教材", Money.ofFen(100), 1,
            WarrantyTerm.none());
        listing.setManufacturerWarranty("厂家凭证摘要", java.time.Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(listing.warrantyTerm()).isEqualTo(WarrantyTerm.none());
        assertThat(listing.manufacturerWarrantyProofSnapshot()).isEqualTo("厂家凭证摘要");
    }

    @Test
    void rejectsMoreThanNineMedia() {
        Listing listing = Listing.draft(UUID.randomUUID(), "教材", Money.ofFen(100), 1,
            WarrantyTerm.none());
        for (int i = 0; i < 9; i++) {
            listing.addMedia(UUID.randomUUID());
        }
        assertThatThrownBy(() -> listing.addMedia(UUID.randomUUID()))
            .isInstanceOf(IllegalStateException.class);
    }
}
