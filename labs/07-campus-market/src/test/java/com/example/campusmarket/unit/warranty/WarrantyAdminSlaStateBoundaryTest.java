package com.example.campusmarket.unit.warranty;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WarrantyAdminSlaStateBoundaryTest {
    @Test
    void adminSlaMutationOnlyTargetsNonTerminalCases() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/com/example/campusmarket/warranty/application/WarrantyDeadlineScheduler.java"));
        int branch = source.indexOf("else if(\"ADMIN_SLA\".equals(c.type())");
        int end = source.indexOf("}else if(\"HARD_DEADLINE\"", branch);
        assertThat(branch).isGreaterThanOrEqualTo(0);
        assertThat(source.substring(branch, end)).contains(
            "WHERE id=? AND status IN ('OPEN','SELLER_RESPONDED','UNDER_REVIEW','ESCALATED') AND admin_sla_alerted_at IS NULL");
    }
}
