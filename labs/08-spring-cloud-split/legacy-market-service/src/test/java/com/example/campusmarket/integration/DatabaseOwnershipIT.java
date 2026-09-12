package com.example.campusmarket.integration;

import com.example.campusmarket.testsupport.SplitDatabaseContainer;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 两个应用账号只能访问各自数据库，跨库 SQL 必须由真实 MySQL 拒绝。 */
class DatabaseOwnershipIT {

    @Test
    void identityRuntimeCannotReachMarketDatabase() {
        Properties identity = SplitDatabaseContainer.identityProperties();
        JdbcTemplate jdbc = jdbc(identity);

        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT SUBSTRING_INDEX(CURRENT_USER(), '@', 1)", String.class))
            .isEqualTo(SplitDatabaseContainer.IDENTITY_ACCOUNT);
        assertThatThrownBy(() -> jdbc.queryForObject(
            "SELECT COUNT(*) FROM market_db.listing", Integer.class))
            .isInstanceOf(DataAccessException.class)
            .satisfies(DatabaseOwnershipIT::assertMySqlDenied);
        assertThatThrownBy(() -> jdbc.execute(
            "CREATE TABLE identity_db.runtime_ddl_probe (id INT NOT NULL)"))
            .isInstanceOf(DataAccessException.class)
            .satisfies(DatabaseOwnershipIT::assertMySqlDenied);
    }

    @Test
    void marketRuntimeCannotReachIdentityDatabase() {
        Properties market = SplitDatabaseContainer.marketProperties();
        JdbcTemplate jdbc = jdbc(market);

        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT SUBSTRING_INDEX(CURRENT_USER(), '@', 1)", String.class))
            .isEqualTo(SplitDatabaseContainer.MARKET_ACCOUNT);
        assertThatThrownBy(() -> jdbc.queryForObject(
            "SELECT COUNT(*) FROM identity_db.campus_user", Integer.class))
            .isInstanceOf(DataAccessException.class)
            .satisfies(DatabaseOwnershipIT::assertMySqlDenied);
        assertThatThrownBy(() -> jdbc.execute(
            "CREATE TABLE market_db.runtime_ddl_probe (id INT NOT NULL)"))
            .isInstanceOf(DataAccessException.class)
            .satisfies(DatabaseOwnershipIT::assertMySqlDenied);
    }

    @Test
    void exposesDifferentFlywayAndRuntimeAccounts() {
        Properties identity = SplitDatabaseContainer.identityProperties();
        Properties market = SplitDatabaseContainer.marketProperties();

        assertThat(identity.getProperty("spring.datasource.username"))
            .isEqualTo(SplitDatabaseContainer.IDENTITY_ACCOUNT);
        assertThat(identity.getProperty("spring.flyway.user"))
            .isEqualTo(SplitDatabaseContainer.IDENTITY_MIGRATOR_ACCOUNT);
        assertThat(identity.getProperty("spring.datasource.password"))
            .isNotEqualTo(identity.getProperty("spring.flyway.password"));
        assertThat(market.getProperty("spring.datasource.username"))
            .isEqualTo(SplitDatabaseContainer.MARKET_ACCOUNT);
        assertThat(market.getProperty("spring.flyway.user"))
            .isEqualTo(SplitDatabaseContainer.MARKET_MIGRATOR_ACCOUNT);
        assertThat(market.getProperty("spring.datasource.password"))
            .isNotEqualTo(market.getProperty("spring.flyway.password"));
        assertThat(identity.getProperty("spring.datasource.username"))
            .isNotEqualTo(identity.getProperty("spring.flyway.user"));
        assertThat(market.getProperty("spring.datasource.username"))
            .isNotEqualTo(market.getProperty("spring.flyway.user"));
    }

    private static JdbcTemplate jdbc(Properties properties) {
        return new JdbcTemplate(new DriverManagerDataSource(
            properties.getProperty("jdbcUrl"),
            properties.getProperty("username"),
            properties.getProperty("password")));
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
}
