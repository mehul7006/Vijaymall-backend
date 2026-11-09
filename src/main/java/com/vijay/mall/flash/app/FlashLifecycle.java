package com.vijay.mall.flash.app;

import com.vijay.mall.common.live.CatalogChanged;
import com.vijay.mall.common.live.LiveEvents;
import com.vijay.mall.common.outbox.OutboxService;
import com.vijay.mall.flash.infra.FlashRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Once a minute: notices sales that started or ended, tells staff once (the "already announced" mark is in the database, so a
 * restart or a second server does not repeat it), and tells open screens to refetch. Sales that ended long ago (the server was down)
 * are marked done without a notification.
 */
@Component
public class FlashLifecycle {
  private static final Logger log = LoggerFactory.getLogger(FlashLifecycle.class);
  /** An ended sale older than this is not announced any more. */
  static final Duration STALE = Duration.ofHours(24);

  private final FlashRepository repo;
  private final OutboxService outbox;
  private final LiveEvents live;
  private final Clock clock;

  public FlashLifecycle(FlashRepository repo, OutboxService outbox, LiveEvents live, Clock clock) {
    this.repo = repo;
    this.outbox = outbox;
    this.live = live;
    this.clock = clock;
  }

  @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
  @Transactional
  public void tick() {
    Instant now = clock.instant();
    boolean changed = false;
    for (FlashRepository.Lifecycle s : repo.claimStarted(now)) {
      changed = true;
      log.atInfo().setMessage("Flash sale started").addKeyValue("event", "flash-started").addKeyValue("saleId", s.saleId())
          .addKeyValue("name", s.name()).addKeyValue("endsAt", s.endsAt()).log();
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("saleId", s.saleId());
      p.put("name", s.name());
      p.put("endsAt", s.endsAt().toString());
      p.put("items", repo.items(s.saleId()).size());
      outbox.add("FLASH_STARTED", p);
    }
    for (FlashRepository.Lifecycle s : repo.claimEnded(now)) {
      changed = true;
      FlashRepository.Totals t = repo.totals(s.saleId());
      log.atInfo().setMessage("Flash sale ended").addKeyValue("event", "flash-ended").addKeyValue("saleId", s.saleId())
          .addKeyValue("name", s.name()).addKeyValue("unitsSold", t.units()).addKeyValue("revenuePaise", t.revenuePaise())
          .addKeyValue("discountPaise", t.discountPaise()).log();
      if (s.endsAt().isBefore(now.minus(STALE))) continue; // old news (the server was down): no notification
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("saleId", s.saleId());
      p.put("name", s.name());
      p.put("unitsSold", t.units());
      p.put("revenuePaise", t.revenuePaise());
      p.put("discountPaise", t.discountPaise());
      outbox.add("FLASH_ENDED", p);
    }
    if (changed) live.area(CatalogChanged.FLASH);
  }
}
