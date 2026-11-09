package com.vijay.mall.order.app;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.outbox.OutboxService;
import com.vijay.mall.identity.app.ProfileService;
import com.vijay.mall.identity.domain.Address;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.catalog.app.PurchaseLookup;
import com.vijay.mall.order.domain.Fulfilment;
import com.vijay.mall.order.domain.OrderChanged;
import com.vijay.mall.order.domain.OrderStatus;
import com.vijay.mall.order.domain.OrderViews;
import com.vijay.mall.order.domain.Quote;
import com.vijay.mall.order.domain.QuoteRequest;
import com.vijay.mall.order.infra.OrderRepository;
import com.vijay.mall.payment.app.PaymentService;
import com.vijay.mall.payment.domain.PaymentEvents;
import com.vijay.mall.payment.domain.PaymentSession;
import com.vijay.mall.promotion.app.PromotionService;
import com.vijay.mall.wallet.app.OrderNumberLookup;
import com.vijay.mall.wallet.app.WalletService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Order use cases: place, move through statuses, cancel, expire. Talks to other modules only through their services. */
@Service
public class OrderService implements OrderNumberLookup, PurchaseLookup, com.vijay.mall.common.ReferenceLookup {
  private static final Logger log = LoggerFactory.getLogger(OrderService.class);

  /** slot is the delivery or pickup time window the customer picked, shown back as it was sent. */
  public record PlaceCommand(QuoteRequest quote, UUID addressId, String paymentMethod, long expectedTotalPaise,
                             String slot) {
    public PlaceCommand(QuoteRequest quote, UUID addressId, String paymentMethod, long expectedTotalPaise) {
      this(quote, addressId, paymentMethod, expectedTotalPaise, null);
    }
  }

  static final int MAX_SLOT = 40;

  /** Start of the status note written when staff reject an order; the notification module recognises it in the event. */
  public static final String REJECTED_PREFIX = "rejected by shop:";

  private final OrderRepository repo;
  private final QuoteService quotes;
  private final InventoryService inventory;
  private final WalletService wallet;
  private final PromotionService promotions;
  private final PaymentService payments;
  private final ProfileService profiles;
  private final OutboxService outbox;
  private final AuditService audit;
  private final JsonMapper json;
  private final TransactionTemplate tx;
  private final AppProps props;
  private final Clock clock;
  private final ApplicationEventPublisher events;

  public OrderService(OrderRepository repo, QuoteService quotes, InventoryService inventory, WalletService wallet,
                      PromotionService promotions, PaymentService payments, ProfileService profiles, OutboxService outbox,
                      AuditService audit, JsonMapper json, TransactionTemplate tx, AppProps props, Clock clock,
                      ApplicationEventPublisher events) {
    this.repo = repo;
    this.quotes = quotes;
    this.inventory = inventory;
    this.wallet = wallet;
    this.promotions = promotions;
    this.payments = payments;
    this.profiles = profiles;
    this.outbox = outbox;
    this.audit = audit;
    this.json = json;
    this.tx = tx;
    this.props = props;
    this.clock = clock;
    this.events = events;
  }

  // ---------- place ----------
  /** Places an order once per idempotency key; a repeated key returns the first result. */
  public OrderViews.View place(UUID userId, String idemKey, PlaceCommand cmd) {
    return com.vijay.mall.common.metrics.Metrics.timed(metrics, "mall.order.place", "Time to place an online order",
        () -> placeOnce(userId, idemKey, cmd));
  }

  private com.vijay.mall.config.RuntimeSettings settings;

  /** Optional (plain unit tests run without it): the admin can change the time allowed to pay an online order. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setSettings(com.vijay.mall.config.RuntimeSettings settings) { this.settings = settings; }

  private io.micrometer.core.instrument.MeterRegistry metrics;

  /** Optional (plain unit tests run without it): timers and counters for placing orders. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setMetrics(io.micrometer.core.instrument.MeterRegistry metrics) { this.metrics = metrics; }

  private com.vijay.mall.common.FlashPricing flash;

  /** Optional (plain unit tests run without it): flash sale units are counted when an order is placed and given back when it is cancelled. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setFlash(com.vijay.mall.common.FlashPricing flash) { this.flash = flash; }

  private OrderViews.View placeOnce(UUID userId, String idemKey, PlaceCommand cmd) {
    if (idemKey == null || idemKey.isBlank()) throw ApiException.validation("Idempotency-Key", "header is required");
    Optional<OrderRepository.Row> existing = repo.findByIdem(userId, idemKey);
    if (existing.isPresent()) return view(existing.get());
    try {
      UUID id = tx.execute(s -> doPlace(userId, idemKey, cmd));
      return view(repo.find(id).orElseThrow());
    } catch (DataIntegrityViolationException e) {
      return view(repo.findByIdem(userId, idemKey).orElseThrow(() -> e));
    } catch (com.vijay.mall.common.FlashPricing.Refused e) {
      // someone else took the last sale units (or the sale ended) between the quote and now: send the fresh quote so the app re-shows it
      throw ApiException.conflict("PRICE_CHANGED", "Prices changed, please review", Map.of("quote", quotes.quote(userId, cmd.quote())));
    }
  }

  private com.vijay.mall.common.OnlineOrdering ordering;

  /** Optional (plain unit tests run without it): the admin can pause online ordering. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setOrdering(com.vijay.mall.common.OnlineOrdering ordering) { this.ordering = ordering; }

  private UUID doPlace(UUID userId, String idemKey, PlaceCommand cmd) {
    if (ordering != null) ordering.requireOpen();
    String method = cmd.paymentMethod();
    String slot = cmd.slot() == null || cmd.slot().isBlank() ? null : cmd.slot().trim();
    if (slot != null && slot.length() > MAX_SLOT) throw ApiException.validation("slot", "must be at most " + MAX_SLOT + " characters");
    if ("CREDIT".equals(method) || "HYBRID".equals(method)) {
      throw ApiException.validation("paymentMethod", "credit and hybrid payment are only available at the shop counter");
    }
    if (!"COD".equals(method) && !"ONLINE".equals(method)) throw ApiException.validation("paymentMethod", "COD or ONLINE");
    payments.requireEnabled(method);
    Quote q = quotes.quote(userId, cmd.quote());
    if (q.totalPaise() != cmd.expectedTotalPaise()) {
      throw ApiException.conflict("PRICE_CHANGED", "Prices changed, please review", Map.of("quote", q));
    }
    Fulfilment f = cmd.quote().fulfil();
    String addressJson = null;
    if (f == Fulfilment.DELIVERY) {
      if (cmd.addressId() == null) throw ApiException.validation("addressId", "address is required for delivery");
      Address a = profiles.address(userId, cmd.addressId());
      addressJson = toJson(a);
    }
    UUID orderId = UUID.randomUUID();
    Instant now = clock.instant();
    boolean needsPayment = "ONLINE".equals(method) && q.totalPaise() > 0;
    String status = needsPayment ? OrderStatus.PAYMENT_PENDING.name() : OrderStatus.PLACED.name();
    long timeoutMinutes = settings != null ? settings.getInt(com.vijay.mall.config.Settings.ONLINE_TIMEOUT_MINUTES) : props.payment().onlineTimeoutMinutes();
    Instant expires = needsPayment ? now.plus(timeoutMinutes, ChronoUnit.MINUTES) : null;
    String orderNo = repo.nextOrderNo();

    repo.insert(new OrderRepository.NewOrder(orderId, orderNo, userId, idemKey, f.name(), status, q.subtotalPaise(),
        q.couponCode(), q.couponDiscountPaise(), q.coinsUsed(), q.coinDiscountPaise(), q.deliveryFeePaise(),
        q.totalPaise(), (int) q.coinsToEarn(), method, needsPayment ? "INITIATED" : "NONE", addressJson, expires, now,
        slot));

    for (Quote.Line l : q.lines()) {
      repo.insertItem(orderId, new OrderRepository.NewItem(l.productId(), l.name(), l.unitPricePaise(), l.qty(),
          l.lineTotalPaise(), l.earnsCoins(), l.coinRedeemable(), l.nameGu(), l.unitLabel(), l.couponDiscountPaise()));
    }
    repo.insertHistory(orderId, null, status, userId, null, now);

    // Stock is not taken here: staff confirm the order first (the item may have been sold at the counter meanwhile).
    inventory.checkAvailable(q.lines().stream().map(l -> new InventoryService.Item(l.productId(), l.qty())).toList());
    wallet.redeem(userId, q.coinsUsed(), orderId);
    promotions.consume(q.couponCode(), userId, orderId, q.couponDiscountPaise());
    if (flash != null) flash.consume(com.vijay.mall.common.FlashPricing.Channel.ONLINE, orderId, userId, flashClaims(q));
    payments.start(orderId, orderNo, q.totalPaise(), method);

    log.info("Order placed orderNo={} user={} total={} method={} status={} coinsUsed={} coupon={} items={}", orderNo, userId,
        q.totalPaise(), method, status, q.coinsUsed(), q.couponCode(), q.lines().size());
    // counted here, where the order is really created, so a repeated request (same key) is not counted again
    com.vijay.mall.common.metrics.Metrics.count(metrics, "mall.orders.placed", "Online orders placed", "method", method, 1);
    com.vijay.mall.common.metrics.Metrics.count(metrics, "mall.orders.value.paise", "Value of online orders placed, in paise", "method",
        method, q.totalPaise());
    outbox.add("ORDER_PLACED", Map.of("orderId", orderId, "orderNo", orderNo, "userId", userId, "totalPaise", q.totalPaise()));
    events.publishEvent(new OrderChanged(orderId, orderNo, userId, status, null, now));
    return orderId;
  }

  /** The quote lines that were priced by a flash sale, as claims on the sale's quantity. */
  private static List<com.vijay.mall.common.FlashPricing.Claim> flashClaims(Quote q) {
    return q.lines().stream().filter(l -> l.flashSaleId() != null)
        .map(l -> new com.vijay.mall.common.FlashPricing.Claim(l.flashSaleId(), l.productId(), l.qty(), l.unitPricePaise(), l.listPricePaise())).toList();
  }

  // ---------- status changes ----------
  @Transactional
  public OrderViews.AdminView adminChangeStatus(UUID orderId, OrderStatus target, String note, UUID actor) {
    OrderRepository.Row row = repo.find(orderId).orElseThrow(() -> ApiException.notFound("Order"));
    if (OrderStatus.valueOf(row.status()) == target) return adminView(row); // already there: nothing to do, not an error
    if (OrderStatus.PAYMENT_PENDING.name().equals(row.status()) && target != OrderStatus.CANCELLED) {
      // Only the payment result moves this order on; staff can turn it down but not wave it through unpaid.
      throw ApiException.conflict("AWAITING_PAYMENT", "The customer has not paid yet", Map.of());
    }
    transition(row, target, actor, note);
    audit.log(actor, "ORDER_STATUS", "orders", orderId, target.name());
    return adminView(repo.find(orderId).orElseThrow());
  }

  /**
   * Staff turn an order down (item not available, cannot deliver...). The customer gets the reason, and everything the
   * order took is given back: stock (if it was confirmed), coins, coupon use, and the money when it was paid online.
   */
  @Transactional
  public OrderViews.AdminView adminReject(UUID orderId, String reason, UUID actor) {
    String why = reason == null ? "" : reason.trim();
    if (why.isEmpty()) throw ApiException.validation("reason", "tell the customer why the order is rejected");
    if (why.length() > 200) throw ApiException.validation("reason", "must be at most 200 characters");
    OrderRepository.Row row = repo.find(orderId).orElseThrow(() -> ApiException.notFound("Order"));
    transition(row, OrderStatus.CANCELLED, actor, OrderService.REJECTED_PREFIX + " " + why);
    repo.setCancelReason(orderId, "Rejected by shop: " + why);
    audit.log(actor, "ORDER_REJECT", "orders", orderId, why);
    return adminView(repo.find(orderId).orElseThrow());
  }

  @Transactional
  public OrderViews.View customerCancel(UUID userId, UUID orderId) { return customerCancel(userId, orderId, null); }

  /** Same, keeping the reason the customer gave (blank means none). */
  @Transactional
  public OrderViews.View customerCancel(UUID userId, UUID orderId, String reason) {
    OrderRepository.Row row = repo.find(orderId).filter(r -> r.userId().equals(userId))
        .orElseThrow(() -> ApiException.notFound("Order"));
    if (!OrderStatus.valueOf(row.status()).customerCanCancel()) {
      throw ApiException.conflict("ILLEGAL_TRANSITION", "This order can no longer be cancelled", Map.of());
    }
    String why = reason == null || reason.isBlank() ? null : reason.trim();
    transition(row, OrderStatus.CANCELLED, userId, why == null ? "cancelled by customer" : "cancelled by customer: " + why);
    if (why != null) repo.setCancelReason(orderId, why);
    return view(repo.find(orderId).orElseThrow());
  }

  /** The one place status changes and their side effects happen. Runs inside the caller's transaction. */
  private void transition(OrderRepository.Row row, OrderStatus target, UUID actor, String note) {
    OrderStatus from = OrderStatus.valueOf(row.status());
    Fulfilment f = Fulfilment.valueOf(row.fulfilment());
    if (!from.canMoveTo(target, f)) {
      String why = from.isFinal() ? "Order is " + from + " and can no longer be changed" : "Cannot move from " + from + " to " + target;
      throw ApiException.conflict("ILLEGAL_TRANSITION", why, Map.of());
    }
    Instant now = clock.instant();
    // Confirming is the moment the shop commits its shelf stock. If it is gone, this throws OUT_OF_STOCK, the whole
    // change rolls back and the order stays where it was, so staff can reject it (which refunds the customer).
    if (target.holdsStock() && !row.stockReserved()) {
      inventory.reserve(repo.items(row.id()).stream().map(i -> new InventoryService.Item(i.productId(), i.qty())).toList(), row.id());
      repo.setStockReserved(row.id(), true);
    }
    if (!repo.updateStatus(row.id(), target.name(), row.version(), now)) {
      throw ApiException.conflict("VERSION_CONFLICT", "Order was changed by someone else, reload and retry", Map.of());
    }
    repo.insertHistory(row.id(), from.name(), target.name(), actor, note, now);
    log.info("Order status orderNo={} {} -> {} actor={} note={}", row.orderNo(), from, target, actor, note);

    if (target == OrderStatus.CANCELLED) onCancelled(row);
    if (target.completesOrder()) onCompleted(row);
    outbox.add("ORDER_STATUS", Map.of("orderId", row.id(), "orderNo", row.orderNo(), "userId", row.userId(), "status", target.name(),
        "note", note == null ? "" : note));
    events.publishEvent(new OrderChanged(row.id(), row.orderNo(), row.userId(), target.name(), from.name(), now));
  }

  private void onCancelled(OrderRepository.Row row) {
    if (row.stockReserved()) {
      inventory.release(repo.items(row.id()).stream().map(i -> new InventoryService.Item(i.productId(), i.qty())).toList(), row.id());
      repo.setStockReserved(row.id(), false);
    }
    promotions.release(row.id());
    if (flash != null) flash.release(row.id());
    if (row.coinsUsed() > 0) wallet.reverse(row.userId(), row.id());
    if (payments.refund(row.id(), "order cancelled")) repo.setPaymentStatus(row.id(), "REFUNDED", clock.instant());
    else if ("INITIATED".equals(row.paymentStatus())) repo.setPaymentStatus(row.id(), "FAILED", clock.instant());
  }

  private void onCompleted(OrderRepository.Row row) {
    if ("COD".equals(row.paymentMethod()) && !"CAPTURED".equals(row.paymentStatus())) {
      repo.setPaymentStatus(row.id(), "CAPTURED", clock.instant());
    }
    if (!row.coinsCredited() && row.coinsEarned() > 0) {
      wallet.credit(row.userId(), row.coinsEarned(), row.id());
      repo.markCoinsCredited(row.id());
    }
  }

  // ---------- payment results ----------
  @EventListener
  @Transactional
  public void on(PaymentEvents.PaymentCaptured e) {
    OrderRepository.Row row = repo.find(e.orderId()).orElse(null);
    if (row == null) return;
    if (OrderStatus.PAYMENT_PENDING.name().equals(row.status())) {
      transition(row, OrderStatus.PLACED, null, "payment received");
      repo.setPaymentStatus(row.id(), "CAPTURED", clock.instant());
    } else if (OrderStatus.CANCELLED.name().equals(row.status())) {
      // paid after we expired the order: give the money back
      if (payments.refund(row.id(), "paid after expiry")) {
        repo.setPaymentStatus(row.id(), "REFUNDED", clock.instant());
        touched(row);
      }
    } else {
      // staff moved the order on before the payment arrived: the order stays where it is, the payment is recorded
      repo.setPaymentStatus(row.id(), "CAPTURED", clock.instant());
      touched(row);
    }
  }

  /**
   * Money that cannot pay the order arrived (wrong amount, or a second payment) and is being refunded. The order itself is
   * not touched; the customer and the staff are told through the outbox. {@code payable} says whether the customer can still
   * pay this order.
   */
  @EventListener
  @Transactional
  public void on(PaymentEvents.PaymentAnomaly e) {
    OrderRepository.Row row = repo.find(e.orderId()).orElse(null);
    if (row == null) return;
    boolean payable = OrderStatus.PAYMENT_PENDING.name().equals(row.status());
    outbox.add("PAYMENT_ANOMALY", Map.of("orderId", row.id(), "orderNo", row.orderNo(), "userId", row.userId(), "kind", e.kind(),
        "expectedPaise", e.expectedPaise(), "receivedPaise", e.receivedPaise(), "payable", payable));
  }

  /** The order's status did not change but something on it did (its payment): tell the open screens to refresh it. */
  private void touched(OrderRepository.Row row) {
    events.publishEvent(new OrderChanged(row.id(), row.orderNo(), row.userId(), row.status(), row.status(), clock.instant()));
  }

  @EventListener
  @Transactional
  public void on(PaymentEvents.PaymentFailed e) {
    repo.find(e.orderId()).filter(r -> OrderStatus.PAYMENT_PENDING.name().equals(r.status()))
        .ifPresent(r -> transition(r, OrderStatus.CANCELLED, null, "payment failed"));
  }

  /** Cancels online orders nobody paid for, which frees their stock, coins and coupon. */
  @Scheduled(fixedDelay = 60_000)
  public void expireUnpaid() {
    for (UUID id : repo.expiredPending(clock.instant(), 100)) {
      try {
        tx.executeWithoutResult(s -> repo.find(id).filter(r -> OrderStatus.PAYMENT_PENDING.name().equals(r.status()))
            .ifPresent(r -> transition(r, OrderStatus.CANCELLED, null, "payment timeout")));
      } catch (RuntimeException ex) {
        log.warn("Could not expire order {}: {}", id, ex.getMessage());
      }
    }
  }

  // ---------- reads ----------
  @Transactional(readOnly = true)
  public OrderViews.View get(UUID userId, UUID orderId) {
    return view(repo.find(orderId).filter(r -> r.userId().equals(userId)).orElseThrow(() -> ApiException.notFound("Order")));
  }

  public Page<OrderViews.Summary> list(UUID userId, int page, int size) {
    Page<OrderRepository.SummaryRow> p = repo.list(userId, Math.max(0, page), Page.clampSize(size));
    return new Page<>(p.items().stream().map(r -> new OrderViews.Summary(r.id(), r.orderNo(), r.status(), r.fulfilment(),
        r.totalPaise(), r.itemCount(), r.placedAt(), r.subtotalPaise(), r.couponCode(), r.couponDiscountPaise(),
        r.coinsUsed(), r.coinDiscountPaise(), r.deliveryFeePaise(), r.paymentMethod(), r.paymentStatus())).toList(),
        p.page(), p.size(), p.total());
  }

  public Page<OrderViews.AdminSummary> adminList(String status, int page, int size) { return adminList(status, null, page, size); }

  /** q filters by order number (part of it is enough). */
  public Page<OrderViews.AdminSummary> adminList(String status, String q, int page, int size) {
    Page<OrderRepository.SummaryRow> p = repo.adminList(status, q, Math.max(0, page), Page.clampSize(size));
    Map<UUID, User> customers = profiles.users(p.items().stream().map(OrderRepository.SummaryRow::userId).distinct().toList());
    return new Page<>(p.items().stream().map(r -> {
      User u = customers.get(r.userId());
      return new OrderViews.AdminSummary(r.id(), r.orderNo(), r.status(), r.fulfilment(), r.totalPaise(), r.itemCount(),
          r.placedAt(), u == null ? null : u.name(), u == null ? null : u.email(), u == null ? null : u.mobile(),
          r.subtotalPaise(), r.couponCode(), r.couponDiscountPaise(), r.coinsUsed(), r.coinDiscountPaise(),
          r.deliveryFeePaise(), r.paymentMethod(), r.paymentStatus());
    }).toList(), p.page(), p.size(), p.total());
  }

  /** The customer's latest delivered or picked-up order that contains this product, if any. Used to allow reviews. */
  @Override
  public Optional<UUID> deliveredOrderWith(UUID userId, UUID productId) {
    return repo.latestCompletedOrderWith(userId, productId);
  }

  /** How many of the customer's orders are still open; they must finish before the account can be deleted. */
  public long openOrderCount(UUID userId) { return repo.openOrderCount(userId); }

  @Override
  public Map<UUID, String> orderNumbers(java.util.Collection<UUID> orderIds) { return repo.numbers(orderIds); }

  /** Order numbers, for screens that only hold the order id (stock history, coin ledger). */
  @Override
  public Map<UUID, String> numbers(java.util.Collection<UUID> ids) { return repo.numbers(ids); }

  @Transactional(readOnly = true)
  public OrderViews.AdminView adminGet(UUID orderId) {
    return adminView(repo.find(orderId).orElseThrow(() -> ApiException.notFound("Order")));
  }

  private OrderViews.AdminView adminView(OrderRepository.Row r) {
    User u = profiles.get(r.userId());
    return new OrderViews.AdminView(view(r), u.name(), u.email(), u.mobile());
  }

  private OrderViews.View view(OrderRepository.Row r) {
    PaymentSession session = OrderStatus.PAYMENT_PENDING.name().equals(r.status()) ? payments.sessionFor(r.id()).orElse(null) : null;
    return new OrderViews.View(r.id(), r.orderNo(), r.status(), r.fulfilment(), r.paymentMethod(), r.paymentStatus(),
        repo.items(r.id()), r.subtotal(), r.couponCode(), r.couponDiscount(), r.coinsUsed(), r.coinDiscount(),
        r.deliveryFee(), r.total(), r.coinsEarned(), fromJson(r.addressSnapshot()), session, repo.timeline(r.id()), r.placedAt(),
        r.deliverySlot(), r.cancelReason(), wallet.balance(r.userId()),
        OrderStatus.PAYMENT_PENDING.name().equals(r.status()) ? r.expiresAt() : null); // the app shows a countdown to this
  }

  private String toJson(Address a) {
    return json.writeValueAsString(a);
  }

  private Address fromJson(String s) {
    return Optional.ofNullable(s)
        .flatMap(v -> {
          try { return Optional.of(json.readValue(v, Address.class)); }
          catch (JacksonException e) { return Optional.empty(); }
        })
        .orElse(null);
  }
}
