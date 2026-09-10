package com.example.campusmarket.unit.observability;

import com.example.campusmarket.integration.SharedElasticsearchContainerFactory;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.HostConfig;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 直接验证共享 ES 容器配置，不加载 SharedContainers 的静态生命周期。 */
class SharedContainersResourceSafetyTest {
    private static final long ELASTICSEARCH_MEMORY_BYTES = 768L * 1024L * 1024L;

    @Test
    void sharedElasticsearchBindsBoundedJvmHeapToElasticsearchContainer() {
        ElasticsearchContainer container = newContainer();

        assertThat(container.getEnvMap())
            .containsEntry("ES_JAVA_OPTS", "-Xms128m -Xmx192m");
    }

    @Test
    void sharedElasticsearchBindsHostMemoryCapToCreateCommand() {
        ElasticsearchContainer container = newContainer();
        CreateContainerCmd command = mock(CreateContainerCmd.class);
        HostConfig hostConfig = new HostConfig();
        when(command.getHostConfig()).thenReturn(hostConfig);

        container.getCreateContainerCmdModifiers().forEach(modifier -> modifier.modify(command));

        assertThat(hostConfig.getMemory()).isEqualTo(ELASTICSEARCH_MEMORY_BYTES);
    }

    private static ElasticsearchContainer newContainer() {
        DockerImageName image = DockerImageName.parse("campus-market/elasticsearch:8.18.8-smartcn")
            .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch:8.18.8");
        return SharedElasticsearchContainerFactory.create(image);
    }
}
