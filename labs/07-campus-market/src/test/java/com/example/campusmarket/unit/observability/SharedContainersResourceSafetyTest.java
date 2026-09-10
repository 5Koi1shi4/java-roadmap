package com.example.campusmarket.unit.observability;

import com.example.campusmarket.integration.SharedContainers;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 只读取容器定义的 class 资源，避免资源契约测试触发 SharedContainers 静态启动。 */
class SharedContainersResourceSafetyTest {
    private static final long ELASTICSEARCH_MEMORY_BYTES = 768L * 1024L * 1024L;

    @Test
    void sharedElasticsearchDeclaresBoundedJvmHeap() throws IOException {
        String classFile = new String(readClassFile(), java.nio.charset.StandardCharsets.ISO_8859_1);

        assertThat(classFile)
            .as("共享 ES 必须显式限制 JVM heap，避免全量集成验收突破 Windows 内存红线")
            .contains("ES_JAVA_OPTS")
            .contains("-Xms128m -Xmx192m");
    }

    @Test
    void sharedElasticsearchDeclaresHostMemoryCap() throws IOException {
        byte[] classFile = readClassFile();

        assertThat(new String(classFile, java.nio.charset.StandardCharsets.ISO_8859_1))
            .as("共享 ES 必须通过 Testcontainers host config 设置容器内存上限")
            .contains("withCreateContainerCmdModifier");
        assertThat(readLongConstants(classFile))
            .as("共享 ES 必须固定为 768 MiB host memory cap")
            .contains(ELASTICSEARCH_MEMORY_BYTES);
    }

    private static byte[] readClassFile() throws IOException {
        String resource = "/" + SharedContainers.class.getName().replace('.', '/') + ".class";
        try (InputStream input = SharedContainers.class.getResourceAsStream(resource)) {
            assertThat(input).as("找不到 %s", resource).isNotNull();
            return input.readAllBytes();
        }
    }

    private static Set<Long> readLongConstants(byte[] classFile) throws IOException {
        try (DataInputStream input = new DataInputStream(new java.io.ByteArrayInputStream(classFile))) {
            assertThat(input.readInt()).isEqualTo(0xCAFEBABE);
            input.readUnsignedShort();
            input.readUnsignedShort();
            int constantPoolCount = input.readUnsignedShort();
            Set<Long> longs = new HashSet<>();
            for (int index = 1; index < constantPoolCount; index++) {
                switch (input.readUnsignedByte()) {
                    case 1 -> input.skipBytes(input.readUnsignedShort());
                    case 3, 4 -> input.skipBytes(4);
                    case 5, 6 -> {
                        longs.add(input.readLong());
                        index++;
                    }
                    case 7, 8, 16, 19, 20 -> input.skipBytes(2);
                    case 9, 10, 11, 12, 17, 18 -> input.skipBytes(4);
                    case 15 -> input.skipBytes(3);
                    default -> throw new IOException("未知 class 常量池 tag");
                }
            }
            return longs;
        }
    }
}
