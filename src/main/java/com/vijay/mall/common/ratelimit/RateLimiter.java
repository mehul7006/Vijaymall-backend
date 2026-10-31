package com.vijay.mall.common.ratelimit;

import com.vijay.mall.common.error.ApiException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Small in-memory sliding-window limiter. Good enough for one instance. */
@Component
public class RateLimiter {
  private final Clock clock;
  private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

  public RateLimiter(Clock clock) { this.clock = clock; }

  /** Throws RATE_LIMITED when more than max calls happen inside windowSeconds. */
  public void check(String key, int max, long windowSeconds) {
    long now = clock.millis();
    long from = now - windowSeconds * 1000;
    Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
    synchronized (q) {
      while (!q.isEmpty() && q.peekFirst() < from) q.pollFirst();
      if (q.size() >= max) {
        long retry = Math.max(1, (q.peekFirst() + windowSeconds * 1000 - now) / 1000);
        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests",
            Map.of("retryAfterSeconds", retry));
      }
      q.addLast(now);
    }
  }

  /** Drops idle keys once an hour so the map cannot grow forever. */
  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3_600_000L)
  public void cleanup() {
    long from = clock.millis() - 3_600_000L;
    hits.entrySet().removeIf(e -> {
      Deque<Long> q = e.getValue();
      synchronized (q) { return q.isEmpty() || q.peekLast() < from; }
    });
  }
}
