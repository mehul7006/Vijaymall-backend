package com.vijay.mall.order.app;

import com.vijay.mall.common.live.CatalogChanged;
import com.vijay.mall.common.live.CreditChanged;
import com.vijay.mall.common.live.StockChanged;
import com.vijay.mall.common.live.WalletChanged;
import com.vijay.mall.order.domain.OrderChanged;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes changes to open Server-Sent-Events streams the moment they are committed.
 *
 * <ul>
 *   <li>"order": a customer hears about their own orders; counter staff and admins hear about every order.</li>
 *   <li>"wallet" and "credit": only that customer's own streams.</li>
 *   <li>"stock" and "catalog": every stream. These are collected for {@link #BROADCAST_WINDOW_MS} and sent once, so a
 *       burst of sales or an admin editing many products costs one message per client, not one per change.</li>
 * </ul>
 *
 * Each client has its own small queue that one virtual thread drains in order, so events of one client never overtake
 * each other, one slow client never delays the others, and a client that falls too far behind is closed (the app
 * reconnects and reloads). In memory, so it covers one server instance; with several instances it needs a shared
 * channel (for example Redis or PostgreSQL LISTEN/NOTIFY).
 */
@Component
public class OrderEventHub {
  private static final Logger log = LoggerFactory.getLogger(OrderEventHub.class);

  /** A stream lives 10 minutes, shorter than the 15 minute access token, so clients reconnect with a fresh token. */
  static final long STREAM_TIMEOUT_MS = 10 * 60 * 1000L;
  static final int MAX_STREAMS_PER_CUSTOMER = 5;
  static final int MAX_STAFF_STREAMS = 200;
  /** Messages a client may have waiting before it is treated as stuck and closed. */
  static final int MAX_QUEUED = 1000;
  /** Stock and catalog changes are sent together at most this often. */
  static final long BROADCAST_WINDOW_MS = 500;
  /** More changed products than this are sent as "many" (empty list), so the app refetches everything. */
  static final int MAX_LISTED_PRODUCTS = 200;

  /** What a client receives as the data of an "order" event. */
  public record Event(UUID orderId, String orderNo, String status, String previousStatus, Instant at) {}

  /** One message to send. Built into an SSE event per client, because an event builder can only be used once. */
  private record Msg(String id, String name, Object data) {}

  private final Map<UUID, List<Client>> customers = new ConcurrentHashMap<>();
  private final List<Client> staff = new CopyOnWriteArrayList<>();
  private final Executor sender;

  private final Object pendingLock = new Object();
  private Set<UUID> pendingProducts = new LinkedHashSet<>();
  private boolean pendingStock;
  private boolean manyProducts;
  private String stockReason = "";
  private Instant stockAt;
  private final Map<String, Instant> pendingAreas = new LinkedHashMap<>();

  public OrderEventHub() { this(Executors.newVirtualThreadPerTaskExecutor()); }

  /** Tests pass a direct executor so delivery is immediate. */
  OrderEventHub(Executor sender) { this.sender = sender; }

  /** One open stream with its own ordered queue. */
  private final class Client {
    final SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
    private final Queue<Msg> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicBoolean draining = new AtomicBoolean();
    volatile boolean closed;

    void offer(Msg m) {
      if (closed) return;
      if (queued.incrementAndGet() > MAX_QUEUED) {
        log.debug("Stream too far behind, closing it");
        close(null);
        return;
      }
      queue.add(m);
      schedule();
    }

    private void schedule() {
      if (draining.compareAndSet(false, true)) {
        try {
          sender.execute(this::drain);
        } catch (RuntimeException ex) {
          draining.set(false);
          close(ex);
        }
      }
    }

    private void drain() {
      try {
        Msg m;
        while (!closed && (m = queue.poll()) != null) {
          queued.decrementAndGet();
          emitter.send(build(m));
        }
      } catch (Exception ex) {
        log.debug("Stream closed: {}", ex.getMessage());
        close(ex);
      } finally {
        draining.set(false);
        if (!closed && !queue.isEmpty()) schedule();
      }
    }

    void close(Exception cause) {
      if (closed) return;
      closed = true;
      queue.clear();
      if (cause == null) emitter.complete();
      else emitter.completeWithError(cause);
    }
  }

  private static SseEmitter.SseEventBuilder build(Msg m) {
    if (m.name() == null) return SseEmitter.event().comment("keepalive");
    SseEmitter.SseEventBuilder b = SseEmitter.event().name(m.name()).data(m.data(), MediaType.APPLICATION_JSON);
    return m.id() == null ? b : b.id(m.id());
  }

  // ---------- subscribing ----------
  /** A stream of one customer's own orders and balances. Past the limit the oldest stream of that customer is closed. */
  public SseEmitter subscribeCustomer(UUID userId) {
    Client c = newClient();
    List<Client> evicted = new ArrayList<>();
    customers.compute(userId, (k, list) -> {
      List<Client> l = list == null ? new CopyOnWriteArrayList<>() : list;
      while (l.size() >= MAX_STREAMS_PER_CUSTOMER) evicted.add(l.remove(0));
      l.add(c);
      return l;
    });
    // closed outside compute: closing runs the drop callback, which updates the same map entry
    evicted.forEach(e -> e.close(null));
    c.emitter.onCompletion(() -> removeCustomer(userId, c));
    c.emitter.onTimeout(() -> removeCustomer(userId, c));
    c.emitter.onError(_ -> removeCustomer(userId, c));
    return c.emitter;
  }

  /** A stream of every order, for counter staff and admins. */
  public SseEmitter subscribeStaff() {
    Client c = newClient();
    synchronized (staff) {
      while (staff.size() >= MAX_STAFF_STREAMS) staff.remove(0).close(null);
      staff.add(c);
    }
    Runnable drop = () -> {
      c.closed = true;
      staff.remove(c);
    };
    c.emitter.onCompletion(drop);
    c.emitter.onTimeout(drop);
    c.emitter.onError(_ -> drop.run());
    return c.emitter;
  }

  private Client newClient() {
    Client c = new Client();
    c.offer(new Msg(null, "ready", Map.of("connected", true)));
    return c;
  }

  private void removeCustomer(UUID userId, Client c) {
    if (c == null) return;
    c.closed = true;
    customers.computeIfPresent(userId, (k, l) -> {
      l.remove(c);
      return l.isEmpty() ? null : l;
    });
  }

  // ---------- events ----------
  /** Runs after the order transaction commits, so a client never hears about a change that was rolled back. */
  @TransactionalEventListener(fallbackExecution = true)
  public void on(OrderChanged e) {
    Msg m = new Msg(e.orderId() + ":" + e.status(), "order",
            new Event(e.orderId(), e.orderNo(), e.status(), e.previousStatus(), e.at()));
    toCustomer(e.userId(), m);
    staff.forEach(c -> c.offer(m));
  }

  /** A coin balance moved: only that customer's own streams hear it, so the coins screen updates without re-login. */
  @TransactionalEventListener(fallbackExecution = true)
  public void on(WalletChanged e) { toCustomer(e.userId(), new Msg(null, "wallet", e)); }

  /** A customer's credit balance or limit changed. */
  @TransactionalEventListener(fallbackExecution = true)
  public void on(CreditChanged e) { toCustomer(e.customerId(), new Msg(null, "credit", e)); }

  /** Stock moved: collected and sent to every stream at the next flush. */
  @TransactionalEventListener(fallbackExecution = true)
  public void on(StockChanged e) {
    synchronized (pendingLock) {
      pendingStock = true;
      stockReason = e.reason();
      stockAt = e.at();
      if (e.productIds().isEmpty()) manyProducts = true;
      else pendingProducts.addAll(e.productIds());
      if (pendingProducts.size() > MAX_LISTED_PRODUCTS) manyProducts = true;
    }
  }

  /** Something every screen shows changed: collected, one message per area, sent at the next flush. */
  @TransactionalEventListener(fallbackExecution = true)
  public void on(CatalogChanged e) {
    synchronized (pendingLock) { pendingAreas.putIfAbsent(e.area(), e.at()); }
  }

  /** Sends what was collected in the last window to every stream. */
  @Scheduled(fixedDelay = BROADCAST_WINDOW_MS)
  void flush() {
    List<Msg> out = new ArrayList<>();
    synchronized (pendingLock) {
      if (pendingStock) {
        List<UUID> ids = manyProducts ? List.of() : List.copyOf(pendingProducts);
        out.add(new Msg(null, "stock", new StockChanged(ids, stockReason, stockAt)));
        pendingProducts = new LinkedHashSet<>();
        pendingStock = false;
        manyProducts = false;
      }
      pendingAreas.forEach((area, at) -> out.add(new Msg(null, "catalog", new CatalogChanged(area, at))));
      pendingAreas.clear();
    }
    if (out.isEmpty()) return;
    broadcast(out);
  }

  private void broadcast(List<Msg> messages) {
    customers.values().forEach(list -> list.forEach(c -> messages.forEach(c::offer)));
    staff.forEach(c -> messages.forEach(c::offer));
  }

  private void toCustomer(UUID userId, Msg m) {
    customers.getOrDefault(userId, List.of()).forEach(c -> c.offer(m));
  }

  /** A comment line every 25 seconds keeps proxies and mobile networks from closing an idle stream. */
  @Scheduled(fixedDelay = 25_000)
  void heartbeat() { broadcast(List.of(new Msg(null, null, null))); }

  int customerStreams(UUID userId) { return customers.getOrDefault(userId, List.of()).size(); }

  int staffStreams() { return staff.size(); }
}