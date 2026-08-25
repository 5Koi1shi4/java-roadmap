package com.example.search.integration;

import com.example.search.application.maintenance.AliasTargets;
import com.example.search.application.maintenance.PreparedRebuild;
import com.example.search.application.maintenance.RebuildJob;
import com.example.search.application.maintenance.RebuildJobRepository;
import com.example.search.application.maintenance.RebuildStatus;
import com.example.search.application.maintenance.RebuildValidationException;
import com.example.search.application.maintenance.SearchRebuildCutover;
import com.example.search.application.maintenance.SearchRebuildPreparer;
import com.example.search.application.product.CreateProductCommand;
import com.example.search.application.product.ProductCommandService;
import com.example.search.application.product.ProductView;
import com.example.search.application.sync.OutboxClaimService;
import com.example.search.application.sync.SearchOutboxRepository;
import com.example.search.domain.ProductDetails;
import com.example.search.domain.ProductStatus;
import com.example.search.infrastructure.elasticsearch.ElasticsearchIndexManager;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RebuildCutoverIT extends SharedSearchContainers {
    @Autowired DataSource dataSource;
    @Autowired ProductCommandService products;
    @Autowired SearchRebuildPreparer preparer;
    @Autowired SearchRebuildCutover cutover;
    @Autowired ElasticsearchIndexManager indexes;
    @Autowired ElasticsearchClient client;
    @Autowired RebuildJobRepository rebuildJobs;
    @Autowired SearchOutboxRepository outbox;
    @Autowired OutboxClaimService claims;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void clean() throws Exception {
        clearTables(dataSource);
        indexes.createBootstrapIndex();
        if (!indexes.aliasTargets().isConfigured()) indexes.installAliases("products-vbootstrap");
    }

    @Test
    void switchesBothAliasesAtomicallyAfterFinalValidation() throws Exception {
        ProductView product = products.create(new CreateProductCommand(details("cutover")));
        PreparedRebuild prepared = preparer.prepare(preparer.start("cutover-it"));
        indexes.refresh(prepared.targetIndex());
        String documentId = Long.toString(product.id());
        assertThat(client.get(g -> g.index(prepared.targetIndex()).id(documentId), java.util.Map.class).source())
                .containsEntry("name", "cutover");

        products.update(product.id(), new com.example.search.application.product.UpdateProductCommand(product.version(), details("final-window")));
        long finalWindowSequence = outbox.highWatermark();
        assertThat(finalWindowSequence).isGreaterThan(prepared.preparedWatermark());
        assertThat(client.get(g -> g.index(prepared.targetIndex()).id(documentId), java.util.Map.class).source())
                .containsEntry("name", "cutover");

        RebuildJob completed = cutover.cutover(prepared);
        assertThat(completed.status().name()).isEqualTo("COMPLETED");
        assertThat(completed.finalWatermark()).isGreaterThanOrEqualTo(finalWindowSequence);
        assertThat(indexes.aliasTargets()).isEqualTo(new AliasTargets(prepared.targetIndex(), prepared.targetIndex()));
        indexes.refresh(prepared.targetIndex());
        assertThat(client.get(g -> g.index(prepared.targetIndex()).id(documentId), java.util.Map.class).source())
                .containsEntry("name", "final-window");
    }

    @Test
    void pausedDispatcherDoesNotClaimAvailableOutboxWork() {
        products.create(new CreateProductCommand(details("paused-claim")));
        jdbc.update("UPDATE search_coordination SET dispatcher_paused=TRUE WHERE id=1");

        assertThat(claims.claim("paused-it", 1)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM search_outbox ORDER BY id DESC LIMIT 1", String.class))
                .isEqualTo("NEW");

        jdbc.update("UPDATE search_coordination SET dispatcher_paused=FALSE WHERE id=1");
        assertThat(claims.claim("paused-it", 1)).hasSize(1);
    }

    @Test
    void validationFailureKeepsBothOldAliases() throws Exception {
        ProductView product = products.create(new CreateProductCommand(details("corrupt-me")));
        AliasTargets before = indexes.aliasTargets();
        PreparedRebuild prepared = preparer.prepare(preparer.start("cutover-it"));
        client.delete(d -> d.index(prepared.targetIndex()).id(Long.toString(product.id())));
        indexes.refresh(prepared.targetIndex());
        assertThatThrownBy(() -> cutover.cutover(prepared)).isInstanceOf(RebuildValidationException.class);
        assertThat(indexes.aliasTargets()).isEqualTo(before);
        assertThat(rebuildJobs.find(prepared.jobId())).get().extracting(RebuildJob::status).isEqualTo(RebuildStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT dispatcher_paused FROM search_coordination WHERE id=1", Boolean.class)).isFalse();
    }

    private static ProductDetails details(String name) {
        return new ProductDetails(name, null, "description", "BOOK", "图书", new BigDecimal("10.00"), ProductStatus.ON_SALE);
    }
}
