package com.example.campusmarket.identity;

import com.example.campusmarket.testsupport.SplitDatabaseContainer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 身份服务只拥有 identity_db 的三张身份表，且 Flyway 使用独立迁移账号。 */
@SpringBootTest(classes = IdentitySchemaIT.TestApplication.class)
class IdentitySchemaIT {

    private static final String FLYWAY_HISTORY = "flyway_schema_history";

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void registerIdentityDatabase(DynamicPropertyRegistry registry) {
        SplitDatabaseContainer.identityProperties(registry);
    }

    @Test
    void createsExactlyThreeIdentityTables() {
        assertThat(queryStrings(
            "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_type = 'BASE TABLE' "
                + "AND table_name <> ?",
            SplitDatabaseContainer.IDENTITY_DATABASE, FLYWAY_HISTORY))
            .containsExactlyInAnyOrder("campus_user", "email_verification", "external_identity");
    }

    @Test
    void preservesIdentityChecksAndUniqueKeys() {
        assertThat(queryStrings(
            "SELECT constraint_name FROM information_schema.table_constraints "
                + "WHERE constraint_schema = ? AND constraint_type = 'CHECK'",
            SplitDatabaseContainer.IDENTITY_DATABASE))
            .containsExactlyInAnyOrder(
                "ck_campus_user_status",
                "ck_email_verification_purpose",
                "ck_email_verification_status",
                "ck_email_verification_attempts",
                "ck_external_identity_status");

        assertThat(queryStrings(
            "SELECT constraint_name FROM information_schema.table_constraints "
                + "WHERE constraint_schema = ? AND constraint_type = 'UNIQUE'",
            SplitDatabaseContainer.IDENTITY_DATABASE))
            .containsExactlyInAnyOrder("uk_campus_user_email", "uk_external_identity_provider_subject");
    }

    @Test
    void preservesIdentityInternalForeignKeys() {
        assertThat(queryStrings(
            "SELECT CONCAT(constraint_name, '->', referenced_table_schema, '.', referenced_table_name) "
                + "FROM information_schema.key_column_usage "
                + "WHERE table_schema = ? AND referenced_table_name IS NOT NULL",
            SplitDatabaseContainer.IDENTITY_DATABASE))
            .containsExactlyInAnyOrder(
                "fk_email_verification_user->identity_db.campus_user",
                "fk_external_identity_user->identity_db.campus_user");
    }

    @Test
    void springDatasourceUsesRuntimeAccount() {
        assertThat(jdbc.queryForObject(
            "SELECT SUBSTRING_INDEX(CURRENT_USER(), '@', 1)", String.class))
            .isEqualTo(SplitDatabaseContainer.IDENTITY_ACCOUNT);
    }

    private List<String> queryStrings(String sql, Object... parameters) {
        return jdbc.query(sql, statement -> {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
        }, (result, row) -> result.getString(1));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
