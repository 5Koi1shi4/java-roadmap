package com.example.campusmarket.integration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 保护模块化后 Testcontainers 构建上下文仍从模块目录稳定定位 Dockerfile。
 */
class DockerfilePathGuardTest {

    private static final String LEGACY_PATH = "Path.of(\"docker/elasticsearch/Dockerfile\")";
    private static final String MODULE_PATH = "Path.of(\"../docker/elasticsearch/Dockerfile\")";

    @Test
    void elasticsearchTestContainersUseModuleRelativeDockerfile() throws IOException {
        Path dockerfile = Path.of("../docker/elasticsearch/Dockerfile").normalize();
        assertThat(Files.isRegularFile(dockerfile))
            .as("从 legacy-market-service 模块目录定位的 Dockerfile")
            .isTrue();
        assertThat(Files.readString(dockerfile))
            .as("SmartCN 在线下载必须具备有限重试，避免瞬时网络错误破坏整套验收")
            .contains("seq 1 3")
            .contains("elasticsearch-plugin install --batch analysis-smartcn");

        List<Path> sources = List.of(
            Path.of("src/test/java/com/example/campusmarket/integration/JourneyContainers.java"),
            Path.of("src/test/java/com/example/campusmarket/integration/SharedContainers.java"),
            Path.of("src/test/java/com/example/campusmarket/integration/RecoveryDrillIT.java"),
            Path.of("src/test/java/com/example/campusmarket/integration/RecoveryInvariantStagesIT.java"));
        for (Path source : sources) {
            String content = Files.readString(source);
            assertThat(content)
                .as("%s 必须使用模块相对 Dockerfile 路径", source)
                .doesNotContain(LEGACY_PATH)
                .contains(MODULE_PATH);
        }
    }
}
