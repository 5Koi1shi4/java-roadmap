package com.example.campusmarket.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaCheckClauseNormalizationTest {
    @Test
    void normalizesMySqlNestedParenthesesWithoutLooseningTheRefundBound() {
        String mysqlClause = "((reserved_refund_fen + successful_refund_fen) + amount_fen) <= paid_amount_fen";

        assertThat(CheckClauseNormalizer.normalize(mysqlClause))
            .isEqualTo("reserved_refund_fen+successful_refund_fen+amount_fen<=paid_amount_fen");
        assertThat(CheckClauseNormalizer.normalize(
            "((reserved_refund_fen + successful_refund_fen) + amount_fen) <= paid_amount_fen AND 1=1"))
            .isNotEqualTo("reserved_refund_fen+successful_refund_fen+amount_fen<=paid_amount_fen");
    }
}

@ActiveProfiles("local")
class SchemaIT extends SharedContainers {
    private static final String REFUND_TOTAL_CHECK =
        "reserved_refund_fen+successful_refund_fen+amount_fen<=paid_amount_fen";

    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .load()
            .migrate();
    }

    @Test
    void createsAllCoreTables() throws SQLException {
        Set<String> expected = Set.of(
            "campus_user", "email_verification", "external_identity", "listing", "listing_media",
            "inventory_movement", "search_outbox", "trade_order", "order_command", "order_transition",
            "order_deadline_claim", "payment_order", "payment_callback_event", "refund_order", "settlement",
            "handoff_record", "dispute_case", "dispute_evidence", "return_case", "warranty_case",
            "seller_obligation", "trade_review", "audit_event", "integration_outbox", "consumed_event",
            "object_upload_session", "storage_cleanup_task", "manual_failure", "search_rebuild_gate",
            "search_index_cleanup_task");
        assertThat(tableNames()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(expected).hasSize(30);
    }

    @Test
    void preservesOrderSnapshotAndStateConstraints() throws SQLException {
        assertThat(columnType("trade_order", "total_amount_fen")).isEqualTo("bigint");
        assertThat(columnNames("trade_order")).contains("listing_id", "listing_title_snapshot", "unit_price_fen",
            "quantity", "warranty_days", "warranty_scope_snapshot", "t0", "acceptance_deadline",
            "trial_deadline", "warranty_deadline", "payment_deadline", "handoff_deadline", "receipt_deadline",
            "status", "version");
        for (String deadline : List.of("payment_deadline", "handoff_deadline", "receipt_deadline",
            "acceptance_deadline", "trial_deadline", "warranty_deadline")) {
            assertThat(columnType("trade_order", deadline)).as("deadline %s uses microsecond timestamp", deadline)
                .isEqualTo("timestamp");
        }
        assertThat(checkConstraintNames("trade_order")).contains("ck_trade_order_quantity", "ck_trade_order_status");
    }

    @Test
    void preservesPaymentRefundAndIdempotencyConstraints() throws SQLException {
        assertThat(indexNames("refund_order")).contains("uk_refund_idempotency", "idx_refund_reconcile");
        assertThat(indexNames("payment_callback_event")).contains("uk_payment_callback_provider_event");
        assertThat(columnNames("payment_callback_event")).contains("provider_event_id", "payload_digest",
            "signature_valid", "status", "owner_id", "claim_token", "lease_until", "attempt_count");
        assertThat(indexNames("refund_order")).contains("idx_refund_order_amounts");
        assertThat(checkConstraintNames("refund_order")).contains("ck_refund_successful_le_paid",
            "ck_refund_reserved_le_paid", "ck_refund_amount_le_paid", "ck_refund_total_le_paid");
        assertThat(checkClauses("refund_order")).anyMatch(clause ->
            REFUND_TOTAL_CHECK.equals(normalizeCheckClause(clause)));
        assertThat(indexNames("integration_outbox")).contains("uk_integration_outbox_event_id");
        assertThat(indexNames("consumed_event")).contains("uk_consumed_event");
        assertThat(columnNames("integration_outbox")).contains("aggregate_version", "schema_version", "payload",
            "owner_id", "claim_token", "lease_until", "attempt_count");
        assertThat(columnNames("consumed_event")).contains("status", "owner_id", "claim_token", "lease_until",
            "attempt_count");
        assertThat(columnNames("manual_failure")).contains("source_type", "source_id", "failure_class", "payload",
            "status", "owner_id", "claim_token", "lease_until", "attempt_count", "available_at");
        assertThat(indexNames("manual_failure")).contains("uk_manual_failure_source", "idx_manual_failure_claim");
        assertThat(checkConstraintNames("manual_failure")).contains("ck_manual_failure_class",
            "ck_manual_failure_status", "ck_manual_failure_attempt_count", "ck_manual_failure_claim_fields");
    }

    @Test
    void preservesObligationAndForeignKeyIntegrity() throws SQLException {
        assertThat(columnNames("seller_obligation")).contains("obligation_business_key", "obligation_amount_fen",
            "funded_amount_fen", "funding_deadline", "future_settlement_deduction_key", "restriction_status",
            "version");
        assertThat(indexNames("seller_obligation")).contains("uk_seller_obligation_business_key",
            "uk_seller_obligation_warranty_case");
        assertThat(foreignKeyNames()).contains("fk_listing_seller", "fk_trade_order_listing", "fk_refund_order_order",
            "fk_warranty_case_order", "fk_seller_obligation_warranty_case");
    }

    private Set<String> tableNames() throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection connection = MYSQL.createConnection("");
             ResultSet result = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (result.next()) {
                String name = result.getString("TABLE_NAME");
                if (!"flyway_schema_history".equals(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private String columnType(String table, String column) throws SQLException {
        try (Connection connection = MYSQL.createConnection("");
             ResultSet result = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
            assertThat(result.next()).as("column %s.%s exists", table, column).isTrue();
            return result.getString("TYPE_NAME").toLowerCase();
        }
    }

    private Set<String> indexNames(String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection connection = MYSQL.createConnection("");
             ResultSet result = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, false)) {
            while (result.next()) {
                String name = result.getString("INDEX_NAME");
                if (name != null) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private Set<String> columnNames(String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection connection = MYSQL.createConnection("");
             ResultSet result = connection.getMetaData().getColumns(connection.getCatalog(), null, table, "%")) {
            while (result.next()) {
                names.add(result.getString("COLUMN_NAME"));
            }
        }
        return names;
    }

    private Set<String> checkConstraintNames(String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection connection = MYSQL.createConnection("");
             var statement = connection.prepareStatement(
                 "SELECT tc.CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS tc "
                     + "WHERE tc.CONSTRAINT_SCHEMA = ? AND tc.TABLE_NAME = ? AND tc.CONSTRAINT_TYPE = 'CHECK'")) {
            statement.setString(1, connection.getCatalog());
            statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    names.add(result.getString(1));
                }
            }
        }
        return names;
    }

    private Set<String> foreignKeyNames() throws SQLException {
        Set<String> names = new HashSet<>();
        try (Connection connection = MYSQL.createConnection("");
             var statement = connection.prepareStatement(
                 "SELECT DISTINCT CONSTRAINT_NAME FROM information_schema.REFERENTIAL_CONSTRAINTS "
                     + "WHERE CONSTRAINT_SCHEMA = ?")) {
            statement.setString(1, connection.getCatalog());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    names.add(result.getString(1));
                }
            }
        }
        return names;
    }

    private Set<String> checkClauses(String table) throws SQLException {
        Set<String> clauses = new HashSet<>();
        try (Connection connection = MYSQL.createConnection("");
             var statement = connection.prepareStatement(
                 "SELECT cc.CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS cc "
                     + "JOIN information_schema.TABLE_CONSTRAINTS tc "
                     + "ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA "
                     + "AND tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME "
                     + "WHERE tc.CONSTRAINT_SCHEMA = ? AND tc.TABLE_NAME = ? "
                     + "AND tc.CONSTRAINT_TYPE = 'CHECK'")) {
            statement.setString(1, connection.getCatalog());
            statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    clauses.add(result.getString(1));
                }
            }
        }
        return clauses;
    }

    static String normalizeCheckClause(String clause) {
        return CheckClauseNormalizer.normalize(clause);
    }
}

final class CheckClauseNormalizer {
    private CheckClauseNormalizer() {
    }

    static String normalize(String clause) {
        if (clause == null) {
            return "";
        }
        String compact = clause.replace("`", "")
            .replaceAll("\\s+", "")
            .toLowerCase(Locale.ROOT);
        try {
            return new CheckClauseParser(compact).parse();
        } catch (IllegalArgumentException ignored) {
            return compact;
        }
    }

    private static final class CheckClauseParser {
        private final String input;
        private int position;

        private CheckClauseParser(String input) {
            this.input = input;
        }

        private String parse() {
            Comparison comparison = parseComparison();
            if (position != input.length()) {
                throw invalid();
            }
            if (comparison.rightTerms.size() != 1) {
                throw invalid();
            }
            return String.join("+", comparison.leftTerms) + "<=" + comparison.rightTerms.get(0);
        }

        private Comparison parseComparison() {
            int start = position;
            if (consume('(')) {
                try {
                    Comparison nested = parseComparison();
                    require(')');
                    if (position == input.length()) {
                        return nested;
                    }
                } catch (IllegalArgumentException ignored) {
                    // The opening parenthesis belongs to a grouped additive term.
                }
                position = start;
            }
            List<String> leftTerms = parseAdditive();
            require("<=");
            List<String> rightTerms = parseAdditive();
            return new Comparison(leftTerms, rightTerms);
        }

        private List<String> parseAdditive() {
            List<String> terms = new ArrayList<>();
            terms.addAll(parsePrimary());
            while (consume('+')) {
                terms.addAll(parsePrimary());
            }
            return terms;
        }

        private List<String> parsePrimary() {
            if (consume('(')) {
                List<String> terms = parseAdditive();
                require(')');
                return terms;
            }
            int start = position;
            while (position < input.length()
                && (Character.isLetterOrDigit(input.charAt(position)) || input.charAt(position) == '_')) {
                position++;
            }
            if (start == position) {
                throw invalid();
            }
            return List.of(input.substring(start, position));
        }

        private boolean consume(char expected) {
            if (position < input.length() && input.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void require(char expected) {
            if (!consume(expected)) {
                throw invalid();
            }
        }

        private void require(String expected) {
            if (!input.startsWith(expected, position)) {
                throw invalid();
            }
            position += expected.length();
        }

        private IllegalArgumentException invalid() {
            return new IllegalArgumentException("Unsupported CHECK expression at position " + position);
        }

        private record Comparison(List<String> leftTerms, List<String> rightTerms) {
        }
    }
}
