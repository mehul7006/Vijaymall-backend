package com.vijay.mall.notification.app;

import com.vijay.mall.identity.app.ProfileService;
import com.vijay.mall.notification.infra.BroadcastRepository;
import com.vijay.mall.notification.infra.BroadcastRepository.Superseded;
import com.vijay.mall.notification.infra.SegmentQueryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends the queued admin broadcasts in the background. Every two seconds it claims the oldest open broadcast and walks the
 * recipients in id order, {@value #BATCH} at a time: one bulk insert of inbox rows (the source of truth) and then the push to the
 * devices of the people who got a new row.
 *
 * <p><b>Guarantees.</b> The inbox is exact: the unique (broadcast, user) index means nobody gets a message twice, however often a batch is
 * retried. Push is at least once per batch: after each batch the worker records how far it has pushed, and a broadcast resumed after a
 * crash first pushes again the batch whose inbox rows were written but not yet marked pushed (a person may then get that one push twice,
 * never zero). A push call that throws never fails the broadcast; the batch is counted in {@code pushFailedBatches} and not retried, and
 * {@code pushed} counts only devices handed to a provider that returned normally (nothing while no provider is configured).
 *
 * <p><b>Ownership.</b> A claim is fenced by its attempt number: every write is conditioned on it, so a worker that was replaced stops
 * (it can neither move the cursor backwards nor mark the broadcast done). The worker shows it is alive by touching the row at least
 * every {@link #HEARTBEAT} (before each slow call); another instance takes a broadcast over only after {@link #STALE} without a sign of
 * life, and gives up on it after {@value #MAX_ATTEMPTS} attempts.
 */
@Component
public class BroadcastProcessor {
  private static final Logger log = LoggerFactory.getLogger(BroadcastProcessor.class);
  /** Recipients per inbox batch and the most device tokens per push call. */
  static final int BATCH = 500;
  /** Tries before a broadcast is given up on. */
  static final int MAX_ATTEMPTS = 3;
  /** A sending broadcast with no sign of life for this long is considered abandoned. */
  static final Duration STALE = Duration.ofMinutes(10);
  /** The most time a working worker goes without touching its broadcast. */
  public static final Duration HEARTBEAT = Duration.ofSeconds(30);
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
      store.finish(job.id(), job.attempt(), clock.instant());
      log.info("Broadcast {} done", job.id());
    } catch (Superseded e) {
      log.warn("Broadcast {} was taken over by another worker; this one stops: {}", job.id(), e.getMessage());
    } catch (RuntimeException e) {
      log.error("Broadcast {} stopped, it will be tried again: {}", job.id(), e.toString());
    }
    return true;
  }

  /** Tracks when the worker last touched its broadcast, so slow steps cannot make it look abandoned. */
  private final class Beat {
    private final BroadcastRepository.Job job;
    private Instant last = clock.instant();

    Beat(BroadcastRepository.Job job) { this.job = job; }

    /** Touches the broadcast when {@link #HEARTBEAT} has passed since the last touch (or any write). */
    void pulse() {
      var now = clock.instant();
      if (Duration.between(last, now).compareTo(HEARTBEAT) >= 0) {
        store.heartbeat(job.id(), job.attempt(), now);
        last = now;
      }
    }

    /** A write has just touched the row. */
    void touched() { last = clock.instant(); }
  }

  private void send(BroadcastRepository.Job job) {
    Beat beat = new Beat(job);
    if (job.lastUserId() != null && !Objects.equals(job.lastUserId(), job.pushedThrough())) {
      pushBatch(job, beat, store.inboxUsersBetween(job.id(), job.pushedThrough(), job.lastUserId()), job.lastUserId());
    }
    UUID cursor = job.lastUserId();
    beat.pulse();
    List<UUID> batch = next(job, cursor);
    while (!batch.isEmpty()) {
      UUID last = batch.getLast();
      List<UUID> fresh = store.deliverBatch(job.id(), job.attempt(), last, batch, job.title(), job.body(), job.ref(), clock.instant());
      beat.touched();
      pushBatch(job, beat, fresh, last);
      cursor = last;
      beat.pulse();
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

  private void pushBatch(BroadcastRepository.Job job, Beat beat, List<UUID> users, UUID through) {
    boolean real = push.isConfigured();
    List<String> tokens = real ? profiles.deviceTokensOf(users) : List.of();
    int handedOver = 0;
    boolean failed = false;
    for (int from = 0; from < tokens.size(); from += BATCH) {
      List<String> chunk = tokens.subList(from, Math.min(from + BATCH, tokens.size()));
      beat.pulse();
      try {
        push.send(chunk, job.title(), job.body());
        handedOver += chunk.size();
      } catch (RuntimeException e) {
        failed = true;
        log.warn("Push for broadcast {} not sent: {}", job.id(), e.toString());
      }
    }
    store.markPushed(job.id(), job.attempt(), through, handedOver, failed, clock.instant());
    beat.touched();
  }
}
