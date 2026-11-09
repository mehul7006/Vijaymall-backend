package com.vijay.mall.notification.app;

import com.vijay.mall.identity.app.ProfileService;
import com.vijay.mall.notification.infra.BroadcastRepository;
import com.vijay.mall.notification.infra.SegmentQueryRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends the queued admin broadcasts in the background. Every two seconds it claims the oldest open broadcast and walks the
 * recipients in id order, {@value #BATCH} at a time: one bulk insert of inbox rows (the source of truth) and then the push to the
 * devices of the people who got a new row. A push that fails never fails the broadcast. A broadcast left sending by a process that
 * stopped is picked up again after {@link #STALE} without progress and continues after the last batch; the unique
 * (broadcast, user) inbox index guarantees nobody gets it twice. After {@value #MAX_ATTEMPTS} attempts it is marked failed.
 */
@Component
public class BroadcastProcessor {
  private static final Logger log = LoggerFactory.getLogger(BroadcastProcessor.class);
  /** Recipients per inbox batch and the most device tokens per push call. */
  static final int BATCH = 500;
  /** Tries before a broadcast is given up on. */
  static final int MAX_ATTEMPTS = 3;
  /** A sending broadcast with no progress for this long is considered abandoned. */
  static final Duration STALE = Duration.ofMinutes(2);
  private static final UUID NIL = new UUID(0, 0);

  private final BroadcastRepository store;
  private final SegmentQueryRepository segments;
  private final ProfileService profiles;
  private final PushSender push;
  private final Clock clock;

  /** Creates the processor (wired by Spring). */
  public BroadcastProcessor(BroadcastRepository store, SegmentQueryRepository segments, ProfileService profiles, PushSender push, Clock clock) {
    this.store = store;
    this.segments = segments;
    this.profiles = profiles;
    this.push = push;
    this.clock = clock;
  }

  /** Runs every two seconds: sends every open broadcast, one after the other. Never throws. */
  @Scheduled(fixedDelay = 2_000L)
  public void tick() {
    try {
      while (runOne()) {
        log.debug("Broadcast handled");
      }
    } catch (RuntimeException e) {
      log.error("Broadcast processing failed: {}", e.toString());
    }
  }

  /** Claims and sends one broadcast; false when none is open. A failure leaves it for a later attempt. */
  boolean runOne() {
    var now = clock.instant();
    var claimed = store.claim(now, now.minus(STALE), MAX_ATTEMPTS);
    if (claimed.isEmpty()) return false;
    var job = claimed.get();
    try {
      send(job);
      store.finish(job.id(), clock.instant());
      log.info("Broadcast {} done", job.id());
    } catch (RuntimeException e) {
      log.error("Broadcast {} stopped, it will be tried again: {}", job.id(), e.toString());
    }
    return true;
  }

  private void send(BroadcastRepository.Job job) {
    UUID cursor = job.lastUserId();
    List<UUID> batch = next(job, cursor);
    while (!batch.isEmpty()) {
      UUID last = batch.getLast();
      List<UUID> fresh = store.deliverBatch(job.id(), last, batch, job.title(), job.body(), job.ref(), clock.instant());
      pushTo(job, fresh);
      cursor = last;
      batch = next(job, cursor);
    }
  }

  private List<UUID> next(BroadcastRepository.Job job, UUID after) {
    Audience audience = Audience.valueOf(job.audience());
    if (audience == Audience.USERS) {
      return job.recipientIds().stream().filter(id -> after == null || id.toString().compareTo(after.toString()) > 0)
          .sorted(Comparator.comparing(UUID::toString)).limit(BATCH).toList();
    }
    UUID from = after == null ? NIL : after;
    if (audience == Audience.SEGMENT) return segments.idsAfter(job.segment(), from, BATCH, job.startedAt());
    return profiles.recipientIdsAfter(audience.roles(), from, BATCH);
  }

  private void pushTo(BroadcastRepository.Job job, List<UUID> users) {
    List<String> tokens = profiles.deviceTokensOf(users);
    for (int from = 0; from < tokens.size(); from += BATCH) {
      List<String> chunk = tokens.subList(from, Math.min(from + BATCH, tokens.size()));
      try {
        push.send(chunk, job.title(), job.body());
      } catch (RuntimeException e) {
        log.warn("Push for broadcast {} not sent: {}", job.id(), e.toString());
      }
      store.addPushed(job.id(), chunk.size(), clock.instant());
    }
  }
}
