package com.example.campusmarket.unit.warranty;

import com.example.campusmarket.messaging.EventEnvelopeCodec;
import com.example.campusmarket.messaging.OutboxDispatcher;
import com.example.campusmarket.messaging.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import com.example.campusmarket.observability.CampusMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WarrantyOutboxDispatcherTest {
    @Test
    void warrantyRefundEventIsAcceptedAndCompletedByDispatcher() {
        OutboxRepository repository=mock(OutboxRepository.class); RabbitTemplate rabbit=mock(RabbitTemplate.class);
        UUID event=UUID.randomUUID(), obligation=UUID.randomUUID();
        String payload="{\"orderId\":\""+UUID.randomUUID()+"\",\"caseId\":\""+UUID.randomUUID()+"\",\"obligationId\":\""+obligation+"\",\"amountFen\":800}";
        var message=new OutboxRepository.OutboxMessage(UUID.randomUUID(),event,"WARRANTY_REFUND_REQUESTED",obligation.toString(),1,1,payload,Instant.now(),"owner","token",Instant.now().plusSeconds(30),1,false);
        org.mockito.Mockito.when(repository.claimBatch(any(),eq(10),any())).thenReturn(List.of(message));
        org.mockito.Mockito.when(repository.complete(eq(event),any(),any())).thenReturn(1);
        AtomicReference<String> routing=new AtomicReference<>();
        var dispatcher=new OutboxDispatcher(repository,rabbit,new EventEnvelopeCodec(),(key, ignored)->routing.set(key));
        assertThat(dispatcher.dispatchOnce(10)).isEqualTo(1);
        assertThat(routing).hasValue("WARRANTY_REFUND_REQUESTED");
        verify(repository).complete(eq(event),any(),any());
    }

    @Test
    void zeroRowCompletionDoesNotRecordPublishedMetric() {
        OutboxRepository repository = mock(OutboxRepository.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        UUID event = UUID.randomUUID();
        var message = new OutboxRepository.OutboxMessage(UUID.randomUUID(), event, "WARRANTY_REFUND_REQUESTED",
            UUID.randomUUID().toString(), 1, 1, "{}", Instant.now(), "owner", "token",
            Instant.now().plusSeconds(30), 1, false);
        org.mockito.Mockito.when(repository.claimBatch(any(), eq(10), any())).thenReturn(List.of(message));
        org.mockito.Mockito.when(repository.complete(eq(event), any(), any())).thenReturn(0);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CampusMetrics metrics = new CampusMetrics(registry);

        var dispatcher = new OutboxDispatcher(repository, rabbit, new EventEnvelopeCodec(), (key, ignored) -> { }, metrics);
        assertThat(dispatcher.dispatchOnce(10)).isZero();
        assertThat(registry.find("campus.market.outbox.total").tag("status", "PUBLISHED").counter()).isNull();
    }
}
