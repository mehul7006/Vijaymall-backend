package com.vijay.mall.order.app;

import com.vijay.mall.order.domain.OrderChanged;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes order changes to open Server-Sent-Events streams the moment they are committed: a customer hears about
 * their own orders, counter staff and admins hear about every order. In memory, so it covers one server instance;
 * with several instances it needs a shared channel (for example Redis or PostgreSQL LISTEN/NOTIFY).
 */
@Component
public class OrderEventHub {
  private static final Logger log = LoggerFactory.getLogger(OrderEventHub.class);

  /** A stream lives 10 minutes, shorter than the 15 minute access token, so clients reconnect with a fresh token. */
  static final long STREAM_TIMEOUT_MS = 10 * 60 * 1000L;
  static final int MAX_STREAMS_PER_CUSTOMER = 5;
  static final int MAX_STAFF_STREAMS = 200;

  /** What a client receives as the data of an "order" event. */
  public record Event(UUID orderId, String orderNo, String status, String previousStatus, Instant at) {}

  private final Map<UUID, List<SseEmitter>> customers = new ConcurrentHashMap<>();
  private final List<SseEmitter> staff = new CopyOnWriteArrayList<>();
  private final Executor sender;

  public OrderEventHub() { this(Executors.newVirtualThreadPerTaskExecutor()); }

  /** Tests pass a direct executor so delivery is immediate. */
  OrderEventHub(Executor sender) { this.sender = sender; }

  /** A stream of one customer's own orders. Past the limit the oldest stream of that customer is closed. */
  public SseEmitter subscribeCustomer(UUID userId) {
    return register(customers.computeIfAbsent(userId, _ -> new CopyOnWriteArrayList<>()), MAX_STREAMS_PER_CUSTOMER);
  }

  /** A stream of every order, for counter staff and admins. */
  public SseEmitter subscribeStaff() { return register(staff, MAX_STAFF_STREAMS); }

  private SseEmitter register(List<SseEmitter> list, int max) {
    SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
    synchronized (list) {
      while (list.size() >= max) list.remove(0).complete();
      list.add(emitter);
    }
    Runnable drop = () -> list.remove(emitter);
    emitter.onCompletion(drop);
    emitter.onTimeout(drop);
    emitter.onError(_ -> drop.run());
    deliver(emitter, SseEmitter.event().name("ready").data(Map.of("connected", true), MediaType.APPLICATION_JSON));
    return emitter;
  }

  /** Runs after the order transaction commits, so a client never hears about a change that was rolled back. */
  @TransactionalEventListener(fallbackExecution = true)
  public void on(OrderChanged e) {
    Event data = new Event(e.orderId(), e.orderNo(), e.status(), e.previousStatus(), e.at());
    sender.execute(() -> {
      customers.getOrDefault(e.userId(), List.of()).forEach(em -> deliver(em, event(data)));
      staff.forEach(em -> deliver(em, event(data)));
    });
  }

  /** A coin balance moved: only that customer's own streams hear it, so the coins screen updates without re-login. */
  @TransactionalEventListener(fallbackExecution = true)
  public void on(com.vijay.mall.common.live.WalletChanged e) {
    sender.execute(() -> customers.getOrDefault(e.userId(), List.of()).forEach(em -> deliver(em,
        SseEmitter.event().name("wallet").data(e, MediaType.APPLICATION_JSON))));
  }

  /** Stock moved: every open stream hears it so catalogue, cart and stock-history screens refetch. */
  @TransactionalEventListener(fallbackExecution = true)
  public void on(com.vijay.mall.common.live.StockChanged e) {
    sender.execute(() -> {
      SseEmitter.SseEventBuilder b = SseEmitter.event().name("stock").data(e, MediaType.APPLICATION_JSON);
      customers.values().forEach(list -> list.forEach(em -> deliver(em, b)));
      staff.forEach(em -> deliver(em, b));
    });
  }

  private static SseEmitter.SseEventBuilder event(Event data) {
    return SseEmitter.event().id(data.orderId() + ":" + data.status()).name("order").data(data, MediaType.APPLICATION_JSON);
  }

  /** A comment line every 25 seconds keeps proxies and mobile networks from closing an idle stream. */
  @Scheduled(fixedDelay = 25_000)
  void heartbeat() {
    customers.values().forEach(list -> list.forEach(em -> deliver(em, SseEmitter.event().comment("keepalive"))));
    staff.forEach(em -> deliver(em, SseEmitter.event().comment("keepalive")));
    customers.values().removeIf(List::isEmpty);
  }

  private void deliver(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
    try {
      emitter.send(event);
    } catch (Exception ex) {
      log.debug("Order stream closed: {}", ex.getMessage());
      emitter.completeWithError(ex);
    }
  }

  int customerStreams(UUID userId) { return customers.getOrDefault(userId, List.of()).size(); }

  int staffStreams() { return staff.size(); }
}
