package com.example.campusmarket.integration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JourneyContainersDependencyIsolationTest {
    @Test
    void commonDisabledDependenciesRegisterWithoutContainerLifecycle() {
        Map<String, Object> properties = new LinkedHashMap<>();
        JourneyDependencyProperties.registerCommonDisabledDependencies(
            (name, supplier) -> properties.put(name, supplier.get()));

        assertThat(properties).containsEntry("spring.rabbitmq.host", "127.0.0.1")
            .containsEntry("spring.rabbitmq.port", "1")
            .containsEntry("spring.rabbitmq.listener.simple.auto-startup", "false")
            .containsEntry("spring.rabbitmq.listener.direct.auto-startup", "false");
    }

    @Test
    void warrantyContainerDoesNotLinkToTextbookContainerLifecycle() throws IOException {
        // 读取 class 资源不会初始化类，因此不会启动任何 Testcontainers。
        String classFile = readClassFile(WarrantyContainers.class);

        assertThat(classFile)
            .as("质保旅程的公共禁用依赖注册不得耦合教材容器类，否则加载属性时会启动整套教材容器")
            .doesNotContain("TextbookContainers");
    }

    private static String readClassFile(Class<?> type) throws IOException {
        String resource = type.getSimpleName() + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            assertThat(input).as("找不到 %s", resource).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
