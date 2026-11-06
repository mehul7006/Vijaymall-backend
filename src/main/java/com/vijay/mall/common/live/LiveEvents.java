package com.vijay.mall.common.live;

import java.time.Clock;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * What a service calls to tell open app screens that something changed. Events are published inside the caller's
 * transaction and only sent to the streams after it commits (see OrderEventHub), so nobody hears about a rolled-back change.
 * Services take it through an optional setter, so plain unit tests need not provide one.
 */
@Component
public class LiveEvents {
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public LiveEvents(ApplicationEventPublisher events, Clock clock) {
    this.events = events;
    this.clock = clock;
  }

  /** Products, prices, categories, banners, variants. */
  public void catalog() { area(CatalogChanged.CATALOG); }

  public void area(String area) { events.publishEvent(new CatalogChanged(area, clock.instant())); }

  public void credit(UUID customerId, String reason, long deltaPaise) {
    events.publishEvent(new CreditChanged(customerId, reason, deltaPaise, clock.instant()));
  }
}
