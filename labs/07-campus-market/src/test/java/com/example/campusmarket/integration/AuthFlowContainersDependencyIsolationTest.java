package com.example.campusmarket.integration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 读取字节码验证认证集成测试不会触发共享重型容器生命周期。 */
class AuthFlowContainersDependencyIsolationTest {
    @Test
    void authFlowDoesNotLinkToSharedContainerLifecycle() throws IOException {
        assertThat(AuthFlowIT.class.getSuperclass())
            .as("AuthFlow 必须直接继承专用容器基类")
            .isEqualTo(AuthFlowContainers.class);
        assertThat(AuthFlowContainers.class.getSuperclass())
            .as("专用基类不得间接继承 SharedContainers")
            .isEqualTo(Object.class);
    }

    @Test
    void authFlowContainerBaseDoesNotLinkToUnusedExternalServices() throws IOException {
        String classFile = readClassFile(AuthFlowContainers.class);

        assertThat(classFile)
            .as("认证流程专用容器基类不得声明未使用的外部服务容器")
            .contains("mysql:8.4", "redis:7.4.2-alpine")
            .doesNotContain(
                "RabbitMQContainer", "ElasticsearchContainer", "Minio", "Toxiproxy",
                "rabbitmq:", "elasticsearch:", "minio:", "toxiproxy:");
    }

    private static String readClassFile(Class<?> type) throws IOException {
        String resource = type.getSimpleName() + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            assertThat(input).as("找不到 %s", resource).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
