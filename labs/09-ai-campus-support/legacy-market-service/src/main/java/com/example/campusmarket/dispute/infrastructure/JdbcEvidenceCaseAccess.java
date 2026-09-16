package com.example.campusmarket.dispute.infrastructure;

import com.example.campusmarket.dispute.application.EvidenceCaseAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

import java.util.UUID;

@Repository
@Profile("!test")
public class JdbcEvidenceCaseAccess implements EvidenceCaseAccess {
    private final JdbcTemplate jdbc;
    public JdbcEvidenceCaseAccess(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public boolean canAttach(String caseType, UUID caseId, UUID actorId) {
        return canRead(caseType, caseId, actorId);
    }

    @Override public boolean canRead(String caseType, UUID caseId, UUID actorId) {
        if (caseId == null || actorId == null) return false;
        String sql = "DISPUTE".equals(caseType)
            ? "SELECT COUNT(*) FROM dispute_case c JOIN trade_order o ON o.id=c.order_id WHERE c.id=? AND (o.buyer_id=? OR o.seller_id=? OR c.assigned_admin_id=?)"
            : "WARRANTY".equals(caseType)
            ? "SELECT COUNT(*) FROM warranty_case c WHERE c.id=? AND (c.buyer_id=? OR c.seller_id=? OR EXISTS (SELECT 1 FROM warranty_case w WHERE w.id=c.id AND w.assigned_admin_id=?))"
            : null;
        if (sql == null) return false;
        Integer found = jdbc.queryForObject(sql, Integer.class, caseId.toString(), actorId.toString(), actorId.toString(), actorId.toString());
        return found != null && found == 1;
    }
}
