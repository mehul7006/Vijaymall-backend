package com.vijay.mall.common.outbox;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Writes an event in the same transaction as the business change, so it is never lost or sent for a rolled-back change. */
@Service
public class OutboxService {
  private final OutboxJpa jpa;
  private final JsonMapper json;
  private final Clock clock;

  OutboxService(OutboxJpa jpa, JsonMapper json, Clock clock) {
    this.jpa = jpa;
    this.json = json;
    this.clock = clock;
  }

  /** Events written but not yet delivered; a growing number means the relay or a listener is stuck. */
  public long pending() { return jpa.countByPublishedAtIsNull(); }

  @Transactional
  public void add(String type, Object payload) {
    OutboxEventEntity e = new OutboxEventEntity();
    e.id = UUID.randomUUID();
    e.eventType = type;
    e.payload = json.writeValueAsString(payload);
    e.createdAt = clock.instant();
    jpa.save(e);
  }
}
