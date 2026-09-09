package com.example.campusmarket.integration;

import java.util.List;
import java.util.Set;

/** 可由轻量单测审计的恢复演练容器拓扑。 */
public final class RecoveryDrillResourcePlan {
    private RecoveryDrillResourcePlan() {}

    public static List<Stage> stages() {
        return List.of(
            new Stage("rabbit-core", Set.of("MYSQL", "REDIS", "RABBIT", "TOXIPROXY")),
            new Stage("rabbit-acl", Set.of("MYSQL", "REDIS", "MINIO")),
            new Stage("rabbit-search", Set.of("MYSQL", "ELASTICSEARCH")),
            new Stage("search-core", Set.of("MYSQL", "ELASTICSEARCH", "TOXIPROXY")),
            new Stage("search-acl", Set.of("MYSQL", "REDIS", "MINIO")),
            new Stage("search-search", Set.of("MYSQL", "ELASTICSEARCH")),
            new Stage("storage-core", Set.of("MYSQL", "REDIS", "MINIO", "TOXIPROXY")),
            new Stage("storage-acl", Set.of("MYSQL", "REDIS", "MINIO")),
            new Stage("storage-search", Set.of("MYSQL", "ELASTICSEARCH")));
    }

    public record Stage(String name, Set<String> resources) {}
}
