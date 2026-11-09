package com.vijay.mall.order.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.common.live.CatalogChanged;
import com.vijay.mall.common.live.CreditChanged;
import com.vijay.mall.common.live.StockChanged;
import com.vijay.mall.common.live.WalletChanged;
import com.vijay.mall.order.domain.OrderChanged;
import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Drives OrderEventHub with emitters that have a (fake) servlet connection attached, so sends really happen, can fail,
 * and the completion callbacks Spring would run can be fired by hand.
 */
class OrderEventHubCoverageTest {
  private static final Instant AT = Instant.parse("2027-03-01T10:00:00Z");

  /** The fake connection behind one emitter. */
  private static final class Wire {
    final List<String> names = new CopyOnWriteArrayList<>();
    final List<Object> payloads = new CopyOnWriteArrayList<>();
    final List<String> comments = new CopyOnWriteArrayList<>();
    final List<Runnable> onCompletion = new CopyOnWriteArrayList<>();
    final List<Runnable> onTimeout = new CopyOnWriteArrayList<>();
    final List<Consumer<Throwable>> onError = new CopyOnWriteArrayList<>();
    final List<Throwable> failedWith = new CopyOnWriteArrayList<>();
    volatile boolean completed;
    volatile boolean failSends;
    volatile Runnable duringSend;
    volatile int sends;

    @SuppressWarnings("unchecked")
    Wire attach(SseEmitter emitter) {
      try {
        Class<?> handler = Class.forName(ResponseBodyEmitter.class.getName() + "$Handler");
        Object h = Mockito.mock(handler, inv -> {
          switch (inv.getMethod().getName()) {
            case "send" -> {
              if (inv.getArguments().length == 1 && inv.getArgument(0) instanceof Set<?> chunks) {
                sends++;
                if (duringSend != null) duringSend.run();
                if (failSends) throw new IOException("client went away");
                for (Object c : chunks) {
                  Object d = ((ResponseBodyEmitter.DataWithMediaType) c).getData();
                  if (d instanceof String s) {
                    if (s.startsWith("event:")) names.add(s.substring(6).lines().findFirst().orElse("").trim());
                    else if (s.startsWith(":")) comments.add(s.substring(1).trim());
                  } else payloads.add(d);
                }
              }
            }
            case "complete" -> completed = true;
            case "completeWithError" -> failedWith.add(inv.getArgument(0));
            case "onCompletion" -> onCompletion.add(inv.getArgument(0));
            case "onTimeout" -> onTimeout.add(inv.getArgument(0));
            case "onError" -> onError.add(inv.getArgument(0));
            default -> { }
          }
          return null;
        });
        Method init = ResponseBodyEmitter.class.getDeclaredMethod("initialize", handler);
        init.setAccessible(true);
        init.invoke(emitter, h);
        return this;
      } catch (ReflectiveOperationException e) {
        throw new AssertionError(e);
      }
    }

    void clientDisconnects() { onCompletion.forEach(Runnable::run); }
    void clientTimesOut() { onTimeout.forEach(Runnable::run); }
    void connectionErrors() { onError.forEach(c -> c.accept(new IOException("broken pipe"))); }

    @SuppressWarnings("unchecked")
    <T> List<T> payloadsOf(Class<T> type) { return (List<T>) payloads.stream().filter(type::isInstance).toList(); }
  }

  /** An executor that only runs tasks when told to, so queues can build up. */
  private static final class Manual implements Executor {
    final List<Runnable> tasks = new ArrayList<>();
    @Override public void execute(Runnable r) { tasks.add(r); }
    void runAll() {
      while (!tasks.isEmpty()) tasks.remove(0).run();
    }
  }

  private static OrderChanged change(UUID user, String status) {
    return new OrderChanged(UUID.randomUUID(), "VSM-1", user, status, "PLACED", AT);
  }

  private final OrderEventHub hub = new OrderEventHub(Runnable::run);
  private final UUID alice = UUID.randomUUID();
  private final UUID bob = UUID.randomUUID();

  @Test
  void newStreamsGetReadyAndOrderEventsGoToTheOwnerAndStaffOnly() {
    Wire a = new Wire().attach(hub.subscribeCustomer(alice));
    Wire b = new Wire().attach(hub.subscribeCustomer(bob));
    Wire s = new Wire().attach(hub.subscribeStaff());
    assertThat(a.names).containsExactly("ready");

    OrderChanged e = change(alice, "CONFIRMED");
    hub.on(e);

    assertThat(a.names).containsExactly("ready", "order");
    assertThat(s.names).containsExactly("ready", "order");
    assertThat(b.names).containsExactly("ready");
    assertThat(a.payloadsOf(OrderEventHub.Event.class)).singleElement().satisfies(ev -> {
      assertThat(ev.orderId()).isEqualTo(e.orderId());
      assertThat(ev.status()).isEqualTo("CONFIRMED");
      assertThat(ev.previousStatus()).isEqualTo("PLACED");
      assertThat(ev.orderNo()).isEqualTo("VSM-1");
    });
  }

  @Test
  void walletAndCreditEventsReachOnlyTheirOwnerNotStaff() {
    Wire a = new Wire().attach(hub.subscribeCustomer(alice));
    Wire b = new Wire().attach(hub.subscribeCustomer(bob));
    Wire s = new Wire().attach(hub.subscribeStaff());
    hub.on(new WalletChanged(alice, "EARN", 10, AT));
    hub.on(new CreditChanged(bob, "SALE", 500, AT));
    assertThat(a.names).containsExactly("ready", "wallet");
    assertThat(b.names).containsExactly("ready", "credit");
    assertThat(s.names).containsExactly("ready");
  }

  @Test
  void heartbeatIsACommentLineOnEveryStream() {
    Wire a = new Wire().attach(hub.subscribeCustomer(alice));
    Wire s = new Wire().attach(hub.subscribeStaff());
    hub.heartbeat();
    assertThat(a.comments).containsExactly("keepalive");
    assertThat(s.comments).containsExactly("keepalive");
    assertThat(a.names).containsExactly("ready");
  }

  @Test
  void stockChangesAreMergedAndSentOnceListingEachProductOnce() {
    Wire a = new Wire().attach(hub.subscribeCustomer(alice));
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    hub.on(new StockChanged(List.of(p1, p2), "ORDER", AT));
    hub.on(new StockChanged(List.of(p2), "RESTOCK", AT.plusSeconds(1)));
    hub.flush();
    assertThat(a.names).containsExactly("ready", "stock");
    assertThat(a.payloadsOf(StockChanged.class)).singleElement().satisfies(sc -> {
      assertThat(sc.productIds()).containsExactly(p1, p2);
      assertThat(sc.reason()).isEqualTo("RESTOCK");
      assertThat(sc.at()).isEqualTo(AT.plusSeconds(1));
    });
    hub.flush();
    assertThat(a.names).as("nothing pending after a flush").hasSize(2);
  }

  @Test
  void aStockChangeWithoutProductIdsMeansManyAndIsSentAsAnEmptyList() {
    Wire a = new Wire().attach(hub.subscribeCustomer(alice));
    hub.on(new StockChanged(List.of(UUID.randomUUID()), "ORDER", AT));
    hub.on(new StockChanged(List.of(), "IMPORT", AT));
    hub.flush();
    assertThat(a.payloadsOf(StockChanged.class)).singleElement().satisfies(sc -> {
      assertThat(sc.productIds()).isEmpty();
      assertThat(sc.reason()).isEqualTo("IMPORT");
    });
    // and the "many" flag is reset for the next window
    UUID p = UUID.randomUUID();
    hub.on(new StockChanged(List.of(p), "ORDER", AT));
    hub.flush();
    assertThat(a.payloadsOf(StockChanged.class).get(1).productIds()).containsExactly(p);
  }

  @Test
  void moreThanTheListLimitOfDistinctProductsCollapsesToMany() {
    Wire a = new Wire().attach(hub.subscribeCustomer(alice));
    List<UUID> ids = IntStream.range(0, OrderEventHub.MAX_LISTED_PRODUCTS + 1).mapToObj(i -> UUID.randomUUID()).toList();
    hub.on(new StockChanged(ids, "IMPORT", AT));
    hub.flush();
    assertThat(a.payloadsOf(StockChanged.class)).singleElement().satisfies(sc -> assertThat(sc.productIds()).isEmpty());

    List<UUID> exactly = ids.subList(0, OrderEventHub.MAX_LISTED_PRODUCTS);
    hub.on(new StockChanged(exactly, "IMPORT", AT));
    hub.flush();
    assertThat(a.payloadsOf(StockChanged.class).get(1).productIds()).hasSize(OrderEventHub.MAX_LISTED_PRODUCTS);
  }

  @Test
  void catalogChangesAreSentOncePerAreaWithoutAStockMessage() {
    Wire s = new Wire().attach(hub.subscribeStaff());
    hub.on(new CatalogChanged("COUPONS", AT));
    hub.on(new CatalogChanged("COUPONS", AT.plusSeconds(5)));
    hub.on(new CatalogChanged("UNITS", AT));
    hub.flush();
    assertThat(s.names).containsExactly("ready", "catalog", "catalog");
    assertThat(s.payloadsOf(CatalogChanged.class)).extracting(CatalogChanged::area).containsExactly("COUPONS", "UNITS");
    assertThat(s.payloadsOf(CatalogChanged.class).get(0).at()).as("first change of the window wins").isEqualTo(AT);
  }

  @Test
  void disconnectTimeoutAndErrorEachRemoveACustomerStream() {
    Wire w1 = new Wire().attach(hub.subscribeCustomer(alice));
    Wire w2 = new Wire().attach(hub.subscribeCustomer(alice));
    Wire w3 = new Wire().attach(hub.subscribeCustomer(alice));
    assertThat(hub.customerStreams(alice)).isEqualTo(3);
    w1.clientDisconnects();
    assertThat(hub.customerStreams(alice)).isEqualTo(2);
    w2.clientTimesOut();
    assertThat(hub.customerStreams(alice)).isEqualTo(1);
    w3.connectionErrors();
    assertThat(hub.customerStreams(alice)).isZero();
    // a stream that is already gone can be dropped again without harm
    w3.clientDisconnects();
    assertThat(hub.customerStreams(alice)).isZero();
  }

  @Test
  void disconnectTimeoutAndErrorEachRemoveAStaffStream() {
    Wire w1 = new Wire().attach(hub.subscribeStaff());
    Wire w2 = new Wire().attach(hub.subscribeStaff());
    Wire w3 = new Wire().attach(hub.subscribeStaff());
    w1.clientDisconnects();
    w2.clientTimesOut();
    assertThat(hub.staffStreams()).isEqualTo(1);
    w3.connectionErrors();
    assertThat(hub.staffStreams()).isZero();
    // a dropped stream is not sent to any more
    hub.on(change(alice, "PACKED"));
    assertThat(w1.names).containsExactly("ready");
  }

  @Test
  void staffStreamsAreCappedByClosingTheOldest() {
    Wire first = new Wire().attach(hub.subscribeStaff());
    for (int i = 0; i < OrderEventHub.MAX_STAFF_STREAMS + 4; i++) hub.subscribeStaff();
    assertThat(hub.staffStreams()).isEqualTo(OrderEventHub.MAX_STAFF_STREAMS);
    hub.on(change(alice, "PACKED"));
    assertThat(first.names).as("the oldest stream was closed and hears nothing more").containsExactly("ready");
  }

  @Test
  void aFailingSendClosesTheStreamWithTheErrorAndStopsDelivery() {
    Wire a = new Wire().attach(hub.subscribeCustomer(alice));
    Wire other = new Wire().attach(hub.subscribeCustomer(alice));
    a.failSends = true;
    hub.on(change(alice, "CONFIRMED"));
    assertThat(a.failedWith).singleElement().isInstanceOf(Exception.class);
    assertThat(other.names).as("one broken client does not affect the other").containsExactly("ready", "order");
    int sendsAfterFailure = a.sends;
    hub.on(change(alice, "PACKED"));
    assertThat(a.sends).as("closed stream is not retried").isEqualTo(sendsAfterFailure);
    a.connectionErrors();
    assertThat(hub.customerStreams(alice)).isEqualTo(1);
  }

  @Test
  void aFailingStaffSendClosesThatStream() {
    Wire s = new Wire().attach(hub.subscribeStaff());
    s.failSends = true;
    hub.on(change(alice, "CONFIRMED"));
    assertThat(s.failedWith).hasSize(1);
    s.connectionErrors();
    assertThat(hub.staffStreams()).isZero();
  }

  @Test
  void aClientThatFallsTooFarBehindIsClosedCleanly() {
    Manual executor = new Manual();
    OrderEventHub slow = new OrderEventHub(executor);
    SseEmitter emitter = slow.subscribeStaff();
    // ready is queued (1); nothing drains because the executor is held back
    for (int i = 0; i < OrderEventHub.MAX_QUEUED - 1; i++) slow.heartbeat();
    assertThat(executor.tasks).as("only one drain task is ever scheduled").hasSize(1);
    slow.heartbeat(); // the 1001st waiting message
    Wire w = new Wire().attach(emitter);
    assertThat(w.completed).as("closed without error").isTrue();
    assertThat(w.failedWith).isEmpty();
    executor.runAll();
    assertThat(w.sends).as("queued messages were thrown away").isZero();
    slow.heartbeat();
    assertThat(w.sends).isZero();
  }

  @Test
  void aQueueExactlyAtTheLimitIsStillDelivered() {
    Manual executor = new Manual();
    OrderEventHub busy = new OrderEventHub(executor);
    SseEmitter emitter = busy.subscribeStaff();
    for (int i = 0; i < OrderEventHub.MAX_QUEUED - 1; i++) busy.heartbeat();
    Wire w = new Wire().attach(emitter);
    executor.runAll();
    assertThat(w.completed).isFalse();
    assertThat(w.names).containsExactly("ready");
    assertThat(w.comments).hasSize(OrderEventHub.MAX_QUEUED - 1);
  }

  @Test
  void aRejectedDrainTaskClosesTheStreamWithThatError() {
    OrderEventHub rejecting = new OrderEventHub(_ -> { throw new RejectedExecutionException("no threads"); });
    SseEmitter emitter = rejecting.subscribeStaff();
    Wire w = new Wire().attach(emitter);
    assertThat(w.failedWith).singleElement().isInstanceOf(RejectedExecutionException.class);
    rejecting.heartbeat(); // closed already: ignored without further errors
    assertThat(w.failedWith).hasSize(1);
  }

  @Test
  void aStreamClosedWhileItsQueueIsBeingDrainedStopsSending() {
    Manual executor = new Manual();
    OrderEventHub h = new OrderEventHub(executor);
    SseEmitter emitter = h.subscribeCustomer(alice);
    h.on(change(alice, "CONFIRMED"));
    h.on(change(alice, "PACKED"));
    Wire w = new Wire().attach(emitter);
    w.duringSend = w::clientDisconnects; // the browser goes away while the first message is being written
    executor.runAll();
    assertThat(w.sends).isEqualTo(1);
    assertThat(h.customerStreams(alice)).isZero();
  }

  @Test
  void closingAnAlreadyClosedStreamAfterAFailedSendDoesNothingFurther() {
    Manual executor = new Manual();
    OrderEventHub h = new OrderEventHub(executor);
    SseEmitter emitter = h.subscribeCustomer(alice);
    Wire w = new Wire().attach(emitter);
    w.failSends = true;
    w.duringSend = w::clientDisconnects; // disconnect callback marks it closed before the failure is handled
    executor.runAll();
    assertThat(w.failedWith).as("already closed, so the failure is not reported a second time").isEmpty();
  }

  @Test
  void aSecondOrderForTheSameClientReusesTheRunningDrain() {
    Manual executor = new Manual();
    OrderEventHub h = new OrderEventHub(executor);
    SseEmitter emitter = h.subscribeCustomer(alice);
    h.on(change(alice, "CONFIRMED"));
    h.on(change(alice, "PACKED"));
    assertThat(executor.tasks).hasSize(1);
    Wire w = new Wire().attach(emitter);
    executor.runAll();
    assertThat(w.names).containsExactly("ready", "order", "order");
    assertThat(w.payloadsOf(OrderEventHub.Event.class)).extracting(OrderEventHub.Event::status).containsExactly("CONFIRMED", "PACKED");
  }

  @Test
  void aCustomerOverTheStreamLimitLosesTheOldestOnlyAndOthersKeepWorking() {
    Wire oldest = new Wire().attach(hub.subscribeCustomer(alice));
    List<Wire> rest = new ArrayList<>();
    for (int i = 0; i < OrderEventHub.MAX_STREAMS_PER_CUSTOMER; i++) rest.add(new Wire().attach(hub.subscribeCustomer(alice)));
    assertThat(hub.customerStreams(alice)).isEqualTo(OrderEventHub.MAX_STREAMS_PER_CUSTOMER);
    assertThat(oldest.completed).as("the oldest was completed by the eviction").isTrue();
    hub.on(change(alice, "READY_FOR_PICKUP"));
    assertThat(oldest.names).containsExactly("ready");
    rest.forEach(r -> assertThat(r.names).containsExactly("ready", "order"));
  }
}
