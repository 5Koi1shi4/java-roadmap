package com.example.search.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import com.example.search.application.maintenance.AliasTargets;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.example.search.application.maintenance.AliasTargets.READ_ALIAS;
import static com.example.search.application.maintenance.AliasTargets.WRITE_ALIAS;

@Component
public class ElasticsearchIndexManager {
    private final ElasticsearchClient client;

    public ElasticsearchIndexManager(ElasticsearchClient client) {
        this.client = client;
    }

    public String createPhysicalIndex(UUID jobId) {
        if (jobId == null) throw new IllegalArgumentException("jobId is required");
        String name = "products-v" + jobId.toString().replace("-", "");
        create(name);
        return name;
    }

    public String createBootstrapIndex() {
        String name = "products-vbootstrap";
        if (!exists(name)) create(name);
        return name;
    }

    public void refresh(String index) {
        requireName(index);
        try {
            client.indices().refresh(r -> r.index(index));
        } catch (IOException e) {
            throw new IllegalStateException("could not refresh search index " + index, e);
        }
    }

    public AliasTargets aliasTargets() {
        return new AliasTargets(aliasTarget(READ_ALIAS), aliasTarget(WRITE_ALIAS));
    }

    public void installAliases(String physical) {
        requireName(physical);
        try {
            client.indices().updateAliases(u -> u.actions(
                    Action.of(a -> a.add(add -> add.index(physical).alias(READ_ALIAS))),
                    Action.of(a -> a.add(add -> add.index(physical).alias(WRITE_ALIAS)))));
        } catch (IOException e) {
            throw new IllegalStateException("could not install search aliases", e);
        }
    }

    public Set<String> pluginNames() {
        try {
            Set<String> names = new HashSet<>();
            client.nodes().info(i -> i.metric("plugins")).nodes().values()
                    .forEach(node -> node.plugins().forEach(plugin -> names.add(plugin.name())));
            return names;
        } catch (IOException e) {
            throw new IllegalStateException("could not inspect Elasticsearch plugins", e);
        }
    }

    public String readStatus(String index, long productId) {
        try {
            var response = client.get(g -> g.index(index).id(Long.toString(productId)), Map.class);
            if (!response.found() || response.source() == null) return null;
            Object status = response.source().get("status");
            return status == null ? null : status.toString();
        } catch (IOException e) {
            throw new IllegalStateException("could not read product " + productId, e);
        }
    }

    private void create(String name) {
        try {
            client.indices().create(c -> c.index(name)
                    .settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                    .mappings(m -> m.dynamic(DynamicMapping.Strict)
                            .properties("productId", p -> p.long_(l -> l))
                            .properties("sourceVersion", p -> p.long_(l -> l))
                            .properties("name", p -> p.text(t -> t.analyzer("smartcn")
                                    .fields("raw", f -> f.keyword(k -> k))))
                            .properties("subtitle", p -> p.text(t -> t.analyzer("smartcn")))
                            .properties("description", p -> p.text(t -> t.analyzer("smartcn")))
                            .properties("categoryCode", p -> p.keyword(k -> k))
                            .properties("categoryName", p -> p.keyword(k ->
                                    k.fields("text", f -> f.text(t -> t.analyzer("smartcn")))))
                            .properties("price", p -> p.scaledFloat(s -> s.scalingFactor(100.0)))
                            .properties("status", p -> p.keyword(k -> k))
                            .properties("createdAt", p -> p.date(d -> d))
                            .properties("updatedAt", p -> p.date(d -> d))));
        } catch (IOException e) {
            throw new IllegalStateException("could not create search index " + name, e);
        }
    }

    private boolean exists(String index) {
        try {
            return client.indices().exists(e -> e.index(index)).value();
        } catch (IOException e) {
            throw new IllegalStateException("could not check search index " + index, e);
        }
    }

    private String aliasTarget(String alias) {
        try {
            var result = client.indices().getAlias(g -> g.name(alias)).result();
            if (result.size() > 1) throw new IllegalStateException("alias " + alias + " points to multiple indices");
            return result.keySet().stream().findFirst().orElse(null);
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            if (e.status() == 404) return null;
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("could not inspect alias " + alias, e);
        }
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("index is required");
    }
}
