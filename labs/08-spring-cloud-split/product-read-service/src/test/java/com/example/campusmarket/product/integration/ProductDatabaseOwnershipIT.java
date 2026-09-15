package com.example.campusmarket.product.integration;

import com.example.campusmarket.testsupport.SplitDatabaseContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 读服务只拥有 product_read_db，运行账号不能跨入事实库，也不能执行 DDL。 */
@SpringBootTest(classes = ProductDatabaseOwnershipIT.TestApplication.class)
class ProductDatabaseOwnershipIT {

    private static final String FLYWAY_HISTORY = "flyway_schema_history";

    private static final String PRODUCT_DATABASE = SplitDatabaseContainer.PRODUCT_DATABASE;
    private static final String PRODUCT_ACCOUNT = SplitDatabaseContainer.PRODUCT_ACCOUNT;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void registerProductDatabase(DynamicPropertyRegistry registry) {
        SplitDatabaseContainer.productProperties(registry);
    }

    @Test
    void productRuntimeCannotReachIdentityOrMarketDatabase() {
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT SUBSTRING_INDEX(CURRENT_USER(), '@', 1)", String.class))
            .isEqualTo(PRODUCT_ACCOUNT);
        assertThatThrownBy(() -> jdbc.queryForObject(
            "SELECT COUNT(*) FROM identity_db.campus_user", Integer.class))
            .isInstanceOf(DataAccessException.class)
            .satisfies(ProductDatabaseOwnershipIT::assertMySqlDenied);
        assertThatThrownBy(() -> jdbc.queryForObject(
            "SELECT COUNT(*) FROM market_db.listing", Integer.class))
            .isInstanceOf(DataAccessException.class)
            .satisfies(ProductDatabaseOwnershipIT::assertMySqlDenied);
    }

    @Test
    void productMigratorCannotReachIdentityOrMarketDatabase() {
        Properties product = SplitDatabaseContainer.productProperties();
        JdbcTemplate flyway = jdbc(product, "flywayUsername", "flywayPassword");

        assertThat(flyway.queryForObject(
            "SELECT SUBSTRING_INDEX(CURRENT_USER(), '@', 1)", String.class))
            .isEqualTo(SplitDatabaseContainer.PRODUCT_MIGRATOR_ACCOUNT);
        assertThatThrownBy(() -> flyway.queryForObject(
            "SELECT COUNT(*) FROM identity_db.campus_user", Integer.class))
            .isInstanceOf(DataAccessException.class)
            .satisfies(ProductDatabaseOwnershipIT::assertMySqlDenied);
        assertThatThrownBy(() -> flyway.queryForObject(
            "SELECT COUNT(*) FROM market_db.listing", Integer.class))
            .isInstanceOf(DataAccessException.class)
            .satisfies(ProductDatabaseOwnershipIT::assertMySqlDenied);
    }

    @Test
    void createsOnlyProductReadTables() {
        assertThat(queryStrings(
            "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_type = 'BASE TABLE' "
                + "AND table_name <> ?",
            PRODUCT_DATABASE, FLYWAY_HISTORY))
            .containsExactlyInAnyOrder(
                "product_projection",
                "product_inbox",
                "product_index_outbox",
                "product_rebuild_gate",
                "product_index_cleanup_task");
    }

    @Test
    void preservesProjectionChecksAndInboxEventUniqueness() {
        assertThat(queryStrings(
            "SELECT constraint_name FROM information_schema.table_constraints "
                + "WHERE constraint_schema = ? AND constraint_type = 'CHECK'",
            PRODUCT_DATABASE))
            .contains(
                "ck_product_projection_aggregate_version",
                "ck_product_projection_unit_price",
                "ck_product_projection_available_quantity",
                "ck_product_projection_status",
                "ck_product_inbox_event_id",
                "ck_product_index_outbox_aggregate_version",
                "ck_product_index_outbox_status",
                "ck_product_index_outbox_attempt_count",
                "ck_product_rebuild_gate_id",
                "ck_product_rebuild_gate_mode",
                "ck_product_rebuild_gate_generation",
                "ck_product_index_cleanup_status",
                "ck_product_index_cleanup_attempt_count");

        assertThat(queryStrings(
            "SELECT CONCAT(table_name, '.', constraint_name) "
                + "FROM information_schema.table_constraints "
                + "WHERE constraint_schema = ? AND constraint_type = 'UNIQUE'",
            PRODUCT_DATABASE))
            .contains(
                "product_index_outbox.uk_product_index_outbox_listing_version",
                "product_index_cleanup_task.uk_product_index_cleanup_index_name");
    }

    @Test
    void productRuntimeCannotCreateOrAlterReadSchema() {
        assertThatThrownBy(() -> jdbc.execute(
            "CREATE TABLE product_read_db.runtime_ddl_probe (id INT NOT NULL)"))
            .isInstanceOf(DataAccessException.class)
            .satisfies(ProductDatabaseOwnershipIT::assertMySqlDenied);
    }

    @Test
    void exposesSeparateRuntimeAndFlywayAccounts() {
        Properties product = SplitDatabaseContainer.productProperties();
        assertThat(product.getProperty("spring.datasource.username"))
            .isEqualTo(PRODUCT_ACCOUNT);
        assertThat(product.getProperty("spring.flyway.user"))
            .isEqualTo(SplitDatabaseContainer.PRODUCT_MIGRATOR_ACCOUNT);
        assertThat(product.getProperty("spring.datasource.password"))
            .isNotEqualTo(product.getProperty("spring.flyway.password"));
    }

    private List<String> queryStrings(String sql, Object... parameters) {
        return jdbc.query(sql, statement -> {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
        }, (result, row) -> result.getString(1));
    }

    private static JdbcTemplate jdbc(Properties properties, String usernameKey, String passwordKey) {
        return new JdbcTemplate(new DriverManagerDataSource(
            properties.getProperty("jdbcUrl"),
            properties.getProperty(usernameKey),
            properties.getProperty(passwordKey)));
    }

    private static void assertMySqlDenied(Throwable throwable) {
        List<SQLException> sqlExceptions = sqlExceptions(throwable);
        assertThat(sqlExceptions)
            .as("跨库或 DDL 越权必须由 MySQL 直接拒绝")
            .isNotEmpty();
        assertThat(sqlExceptions).anySatisfy(sqlException -> {
            assertThat(sqlException.getErrorCode())
                .as("MySQL 权限拒绝错误码")
                .isIn(1044, 1045, 1142, 1143);
            assertThat(sqlException.getMessage().toLowerCase(Locale.ROOT))
                .contains("denied");
        });
    }

    private static List<SQLException> sqlExceptions(Throwable throwable) {
        List<SQLException> sqlExceptions = new ArrayList<>();
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) {
                sqlExceptions.add(sqlException);
            }
        }
        return sqlExceptions;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
