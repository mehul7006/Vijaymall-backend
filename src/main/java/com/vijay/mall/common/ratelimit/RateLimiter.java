package com.vijay.mall.common.ratelimit;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.metrics.Metrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Small in-memory sliding-window limiter. Good enough for one instance. */
@Component
public class RateLimiter {
  private final Clock clock;
  private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

  private MeterRegistry metrics;

  /** Creates the limiter. */
  public RateLimiter(Clock clock) { this.clock = clock; }

  /** Optional (plain unit tests run without it): counts the calls that were refused. */
  @Autowired(required = false)
  void setMetrics(MeterRegistry metrics) { this.metrics = metrics; }

  /** Throws RATE_LIMITED when more than max calls happen inside windowSeconds. */
  public void check(String key, int max, long windowSeconds) {
    long now = clock.millis();
    long from = now - windowSeconds * 1000;
    Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
    synchronized (q) {
      while (!q.isEmpty() && q.peekFirst() < from) q.pollFirst();
      if (q.size() >= max) {
        Metrics.count(metrics, "mall.rate.limited", "Calls refused by a rate limit", "scope", scope(key), 1);
        // with a limit of 0 nothing is ever recorded, so there is no oldest call: the wait is the whole window
        long oldest = q.isEmpty() ? now : q.peekFirst();
        long retry = Math.max(1, (oldest + windowSeconds * 1000 - now) / 1000);
        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests",
            Map.of("retryAfterSeconds", retry));
      }
      q.addLast(now);
    }
  }

  /** The kind of limit ("ip", "otp-email"...), never the person: keys hold addresses and must not become metric labels. */
  static String scope(String key) {
    int colon = key.indexOf(':');
    return colon < 0 ? key : key.substring(0, colon);
  }

  /** Drops idle keys once an hour so the map cannot grow forever. */
  @Scheduled(fixedDelay = 3_600_000L)
  public void cleanup() {
    long from = clock.millis() - 3_600_000L;
    hits.entrySet().removeIf(e -> {
      Deque<Long> q = e.getValue();
      synchronized (q) { return q.isEmpty() || q.peekLast() < from; }
    });
  }
}
