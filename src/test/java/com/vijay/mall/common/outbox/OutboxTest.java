package com.vijay.mall.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class OutboxTest {
  private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final OutboxJpa jpa = mock(OutboxJpa.class);
  private final JsonMapper json = JsonMapper.builder().build();

  private static OutboxEventEntity event(String type, String payload) {
    var e = new OutboxEventEntity();
    e.id = UUID.randomUUID();
    e.eventType = type;
    e.payload = payload;
    e.createdAt = Instant.parse("2026-12-31T00:00:00Z");
    return e;
  }

  @Test
  void addSerialisesPayloadAndStampsTime() {
    new OutboxService(jpa, json, clock).add("ORDER_PLACED", Map.of("orderNo", "VM-1"));
    var cap = ArgumentCaptor.forClass(OutboxEventEntity.class);
    verify(jpa).save(cap.capture());
    assertThat(cap.getValue().eventType).isEqualTo("ORDER_PLACED");
    assertThat(cap.getValue().payload).isEqualTo("{\"orderNo\":\"VM-1\"}");
    assertThat(cap.getValue().createdAt).isEqualTo(NOW);
    assertThat(cap.getValue().publishedAt).isNull();
  }

  @Test
  void relayPublishesEachPendingEventAndMarksItPublished() {
    var a = event("A", "{}");
    var b = event("B", "{\"x\":1}");
    when(jpa.nextBatch(any())).thenReturn(List.of(a, b));
    var events = mock(ApplicationEventPublisher.class);
    new OutboxRelay(jpa, events, new TransactionTemplate(mock(PlatformTransactionManager.class)), clock).publishPending();
    verify(events).publishEvent(new OutboxMessage(a.id, "A", "{}"));
    verify(events).publishEvent(new OutboxMessage(b.id, "B", "{\"x\":1}"));
    assertThat(a.publishedAt).isEqualTo(NOW);
    assertThat(b.publishedAt).isEqualTo(NOW);
  }

  @Test
  void aFailingListenerDoesNotBlockTheRestOrLoseTheEvent() {
    var bad = event("BAD", "{}");
    var good = event("GOOD", "{}");
    when(jpa.nextBatch(any())).thenReturn(List.of(bad, good));
    var events = mock(ApplicationEventPublisher.class);
    doThrow(new IllegalStateException("listener broke")).when(events).publishEvent(new OutboxMessage(bad.id, "BAD", "{}"));
    new OutboxRelay(jpa, events, new TransactionTemplate(mock(PlatformTransactionManager.class)), clock).publishPending();
    verify(events).publishEvent(new OutboxMessage(good.id, "GOOD", "{}"));
    assertThat(good.publishedAt).isEqualTo(NOW);
    assertThat(bad.publishedAt).as("failed events are marked so they are not retried forever").isEqualTo(NOW);
  }

  @Test
  void emptyBatchDoesNothing() {
    when(jpa.nextBatch(any())).thenReturn(List.of());
    var events = mock(ApplicationEventPublisher.class);
    new OutboxRelay(jpa, events, new TransactionTemplate(mock(PlatformTransactionManager.class)), clock).publishPending();
    org.mockito.Mockito.verifyNoInteractions(events);
  }

  @Test
  void purgeDropsEventsPublishedMoreThanTwoWeeksAgo() {
    new OutboxRelay(jpa, mock(ApplicationEventPublisher.class),
        new TransactionTemplate(mock(PlatformTransactionManager.class)), clock).purge();
    verify(jpa).purge(NOW.minusSeconds(14L * 86400));
  }
}
