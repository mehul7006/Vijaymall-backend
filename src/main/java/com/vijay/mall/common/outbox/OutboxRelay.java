package com.vijay.mall.common.outbox;

import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Moves saved events to in-process listeners. Swap this for a broker publisher later without touching producers. */
@Component
public class OutboxRelay {
  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private final OutboxJpa jpa;
  private final ApplicationEventPublisher events;
  private final TransactionTemplate tx;
  private final Clock clock;

  OutboxRelay(OutboxJpa jpa, ApplicationEventPublisher events, TransactionTemplate tx, Clock clock) {
    this.jpa = jpa;
    this.events = events;
    this.tx = tx;
    this.clock = clock;
  }

  /**
   * Every 2 seconds publishes up to 50 undelivered events as {@link OutboxMessage}. Rows are locked with skip-locked, so several servers do not send the same event. An event is marked delivered even if a listener failed (the failure is logged).
   */
  @Scheduled(fixedDelay = 2000)
  public void publishPending() {
    tx.executeWithoutResult(_ -> {
      List<OutboxEventEntity> batch = jpa.nextBatch(PageRequest.of(0, 50));
      for (OutboxEventEntity e : batch) {
        try {
          events.publishEvent(new OutboxMessage(e.id, e.eventType, e.payload));
        } catch (RuntimeException ex) {
          log.error("Outbox listener failed for {} {}: {}", e.eventType, e.id, ex.getMessage());
        }
        e.publishedAt = clock.instant();
      }
    });
  }

  /** Housekeeping: drop old published rows. */
  @Scheduled(cron = "0 30 3 * * *")
  public void purge() {
    tx.executeWithoutResult(_ -> jpa.purge(clock.instant().minusSeconds(14L * 86400)));
  }
}
