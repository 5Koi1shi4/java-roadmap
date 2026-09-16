package com.example.campusmarket.testsupport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootVersion;
import org.springframework.core.SpringVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformVersionTest {

    @Test
    void experimentNineUsesSupportedPlatformGeneration() throws IOException {
        assertThat(SpringBootVersion.getVersion()).isEqualTo("4.1.1");
        assertThat(SpringVersion.getVersion()).isEqualTo("7.0.9");

        String rootPom = Files.readString(experimentRoot().resolve("pom.xml"));
        assertThat(rootPom).contains("<spring-ai.version>2.0.1</spring-ai.version>");
    }

    private static Path experimentRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path pom = current.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                try {
                    String content = Files.readString(pom);
                    if (content.contains("<artifactId>campus-market-cloud</artifactId>")
                        && content.contains("<module>platform-test-support</module>")) {
                        return current;
                    }
                } catch (IOException exception) {
                    throw new IllegalStateException("无法读取实验九根 POM：" + pom, exception);
                }
            }
            current = current.getParent();
        }
        throw new IllegalStateException("无法定位实验九根 POM");
    }
}
