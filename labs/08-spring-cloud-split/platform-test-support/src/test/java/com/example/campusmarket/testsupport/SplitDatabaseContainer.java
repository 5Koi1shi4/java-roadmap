package com.example.campusmarket.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * 为身份、市场与商品读服务建立真实的三库最小权限测试边界。
 *
 * <p>root 管理连接只在本夹具内部用于建库和授权；测试对外只暴露各自的 runtime
 * 账号，以及供 Spring Flyway 使用的独立 migration 账号属性。</p>
 */
public final class SplitDatabaseContainer {

    public static final String IDENTITY_DATABASE = "identity_db";
    public static final String MARKET_DATABASE = "market_db";
    public static final String PRODUCT_DATABASE = "product_read_db";
    public static final String IDENTITY_ACCOUNT = "identity_app";
    public static final String MARKET_ACCOUNT = "market_app";
    public static final String PRODUCT_ACCOUNT = "product_app";
    public static final String IDENTITY_MIGRATOR_ACCOUNT = "identity_migrator";
    public static final String MARKET_MIGRATOR_ACCOUNT = "market_migrator";
    public static final String PRODUCT_MIGRATOR_ACCOUNT = "product_migrator";

    /** 仅用于 Testcontainers，一次性测试口令，不是任何真实环境秘密。 */
    private static final String IDENTITY_PASSWORD = "identity_test_password";
    private static final String IDENTITY_MIGRATOR_PASSWORD = "identity_migrator_test_password";
    private static final String MARKET_PASSWORD = "market_test_password";
    private static final String MARKET_MIGRATOR_PASSWORD = "market_migrator_test_password";
    private static final String PRODUCT_PASSWORD = "product_test_password";
    private static final String PRODUCT_MIGRATOR_PASSWORD = "product_migrator_test_password";
    private static final String ADMIN_ACCOUNT = "root";
    private static final String ADMIN_PASSWORD = "root_test_password";
    private static final String BOOTSTRAP_DATABASE = "bootstrap";
    private static final String JDBC_PARAMETERS = "?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final int MYSQL_PORT = 3306;

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
        .withDatabaseName(BOOTSTRAP_DATABASE)
        .withUsername(ADMIN_ACCOUNT)
        .withPassword(ADMIN_PASSWORD);

    static {
        MYSQL.start();
        provision();
        Runtime.getRuntime().addShutdownHook(new Thread(SplitDatabaseContainer::stop,
            "split-database-container-stop"));
    }

    private SplitDatabaseContainer() {
    }

    /** 返回指定数据库的 JDBC URL；账号和口令由对应 properties 方法提供。 */
    public static String jdbcUrl(String database) {
        Objects.requireNonNull(database, "database");
        if (!database.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("非法数据库名");
        }
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(MYSQL_PORT)
            + "/" + database + JDBC_PARAMETERS;
    }

    /** 返回 identity runtime datasource 与独立 Flyway migration 的 Spring 属性。 */
    public static Properties identityProperties() {
        return credentials(IDENTITY_DATABASE, IDENTITY_ACCOUNT, IDENTITY_PASSWORD,
            IDENTITY_MIGRATOR_ACCOUNT, IDENTITY_MIGRATOR_PASSWORD);
    }

    /** 返回 market runtime datasource 与独立 Flyway migration 的 Spring 属性。 */
    public static Properties marketProperties() {
        return credentials(MARKET_DATABASE, MARKET_ACCOUNT, MARKET_PASSWORD,
            MARKET_MIGRATOR_ACCOUNT, MARKET_MIGRATOR_PASSWORD);
    }

    /** 返回 product read runtime datasource 与独立 Flyway migration 的 Spring 属性。 */
    public static Properties productProperties() {
        return credentials(PRODUCT_DATABASE, PRODUCT_ACCOUNT, PRODUCT_PASSWORD,
            PRODUCT_MIGRATOR_ACCOUNT, PRODUCT_MIGRATOR_PASSWORD);
    }

    /** 将 identity 的 runtime/Flyway 属性注册到 Spring 测试环境。 */
    public static void identityProperties(DynamicPropertyRegistry registry) {
        registerSpringProperties(registry, identityProperties());
    }

    /** 将 market 的 runtime/Flyway 属性注册到 Spring 测试环境。 */
    public static void marketProperties(DynamicPropertyRegistry registry) {
        registerSpringProperties(registry, marketProperties());
    }

    /** 将 product read 的 runtime/Flyway 属性注册到 Spring 测试环境。 */
    public static void productProperties(DynamicPropertyRegistry registry) {
        registerSpringProperties(registry, productProperties());
    }

    public static boolean isRunning() {
        return MYSQL.isRunning();
    }

    public static void stop() {
        if (MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    private static Properties credentials(String database, String runtimeAccount, String runtimePassword,
                                         String migrationAccount, String migrationPassword) {
        Properties properties = new Properties();
        String url = jdbcUrl(database);
        properties.setProperty("jdbcUrl", url);
        properties.setProperty("username", runtimeAccount);
        properties.setProperty("password", runtimePassword);
        properties.setProperty("flywayUrl", url);
        properties.setProperty("flywayUsername", migrationAccount);
        properties.setProperty("flywayPassword", migrationPassword);
        properties.setProperty("spring.datasource.url", url);
        properties.setProperty("spring.datasource.username", runtimeAccount);
        properties.setProperty("spring.datasource.password", runtimePassword);
        properties.setProperty("spring.flyway.url", url);
        properties.setProperty("spring.flyway.user", migrationAccount);
        properties.setProperty("spring.flyway.password", migrationPassword);
        properties.setProperty("spring.flyway.locations", "classpath:db/migration");
        return properties;
    }

    private static void registerSpringProperties(DynamicPropertyRegistry registry, Properties properties) {
        Objects.requireNonNull(registry, "registry");
        List<String> names = List.of(
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            "spring.flyway.url",
            "spring.flyway.user",
            "spring.flyway.password",
            "spring.flyway.locations");
        for (String name : names) {
            registry.add(name, () -> properties.getProperty(name));
        }
    }

    private static void provision() {
        String adminUrl = jdbcUrl(BOOTSTRAP_DATABASE);
        try (Connection connection = DriverManager.getConnection(adminUrl, ADMIN_ACCOUNT, ADMIN_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + IDENTITY_DATABASE
                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            statement.execute("CREATE DATABASE " + MARKET_DATABASE
                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            statement.execute("CREATE DATABASE " + PRODUCT_DATABASE
                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");

            createUser(statement, IDENTITY_MIGRATOR_ACCOUNT, IDENTITY_MIGRATOR_PASSWORD);
            createUser(statement, IDENTITY_ACCOUNT, IDENTITY_PASSWORD);
            createUser(statement, MARKET_MIGRATOR_ACCOUNT, MARKET_MIGRATOR_PASSWORD);
            createUser(statement, MARKET_ACCOUNT, MARKET_PASSWORD);
            createUser(statement, PRODUCT_MIGRATOR_ACCOUNT, PRODUCT_MIGRATOR_PASSWORD);
            createUser(statement, PRODUCT_ACCOUNT, PRODUCT_PASSWORD);

            grantMigrationPrivileges(statement, IDENTITY_DATABASE, IDENTITY_MIGRATOR_ACCOUNT);
            grantRuntimePrivileges(statement, IDENTITY_DATABASE, IDENTITY_ACCOUNT);
            grantMigrationPrivileges(statement, MARKET_DATABASE, MARKET_MIGRATOR_ACCOUNT);
            grantRuntimePrivileges(statement, MARKET_DATABASE, MARKET_ACCOUNT);
            grantMigrationPrivileges(statement, PRODUCT_DATABASE, PRODUCT_MIGRATOR_ACCOUNT);
            grantRuntimePrivileges(statement, PRODUCT_DATABASE, PRODUCT_ACCOUNT);
        } catch (SQLException exception) {
            throw new IllegalStateException("无法初始化三库最小权限测试夹具", exception);
        }
    }

    private static void createUser(Statement statement, String account, String password) throws SQLException {
        statement.execute("CREATE USER '" + account + "'@'%' IDENTIFIED BY '" + password + "'");
    }

    private static void grantMigrationPrivileges(Statement statement, String database, String account)
        throws SQLException {
        statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES, DROP ON "
            + database + ".* TO '" + account + "'@'%'");
    }

    private static void grantRuntimePrivileges(Statement statement, String database, String account)
        throws SQLException {
        statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON " + database + ".* TO '"
            + account + "'@'%'");
    }
}
