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
import com.vijay.mall.order.domain.Fulfilment;
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
public class OrderService implements OrderNumberLookup {
  private static final Logger log = LoggerFactory.getLogger(OrderService.class);

  /** slot is the delivery or pickup time window the customer picked, shown back as it was sent. */
  public record PlaceCommand(QuoteRequest quote, UUID addressId, String paymentMethod, long expectedTotalPaise,
                             String slot) {
    public PlaceCommand(QuoteRequest quote, UUID addressId, String paymentMethod, long expectedTotalPaise) {
      this(quote, addressId, paymentMethod, expectedTotalPaise, null);
    }
  }

  static final int MAX_SLOT = 40;

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

  public OrderService(OrderRepository repo, QuoteService quotes, InventoryService inventory, WalletService wallet,
                      PromotionService promotions, PaymentService payments, ProfileService profiles, OutboxService outbox,
                      AuditService audit, JsonMapper json, TransactionTemplate tx, AppProps props, Clock clock) {
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
  }

  // ---------- place ----------
  /** Places an order once per idempotency key; a repeated key returns the first result. */
  public OrderViews.View place(UUID userId, String idemKey, PlaceCommand cmd) {
    if (idemKey == null || idemKey.isBlank()) throw ApiException.validation("Idempotency-Key", "header is required");
    Optional<OrderRepository.Row> existing = repo.findByIdem(userId, idemKey);
    if (existing.isPresent()) return view(existing.get());
    try {
      UUID id = tx.execute(s -> doPlace(userId, idemKey, cmd));
      return view(repo.find(id).orElseThrow());
    } catch (DataIntegrityViolationException e) {
      return view(repo.findByIdem(userId, idemKey).orElseThrow(() -> e));
    }
  }

  private UUID doPlace(UUID userId, String idemKey, PlaceCommand cmd) {
    String method = cmd.paymentMethod();
    String slot = cmd.slot() == null || cmd.slot().isBlank() ? null : cmd.slot().trim();
    if (slot != null && slot.length() > MAX_SLOT) throw ApiException.validation("slot", "must be at most " + MAX_SLOT + " characters");
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
    Instant expires = needsPayment ? now.plus(props.payment().onlineTimeoutMinutes(), ChronoUnit.MINUTES) : null;
    String orderNo = repo.nextOrderNo();

    repo.insert(new OrderRepository.NewOrder(orderId, orderNo, userId, idemKey, f.name(), status, q.subtotalPaise(),
        q.couponCode(), q.couponDiscountPaise(), q.coinsUsed(), q.coinDiscountPaise(), q.deliveryFeePaise(),
        q.totalPaise(), (int) q.coinsToEarn(), method, needsPayment ? "INITIATED" : "NONE", addressJson, expires, now,
        slot));

    for (Quote.Line l : q.lines()) {
      repo.insertItem(orderId, new OrderRepository.NewItem(l.productId(), l.name(), l.unitPricePaise(), l.qty(),
          l.lineTotalPaise(), l.earnsCoins(), l.coinRedeemable(), l.nameGu(), l.unitLabel()));
    }
    repo.insertHistory(orderId, null, status, userId, null, now);

    inventory.reserve(q.lines().stream().map(l -> new InventoryService.Item(l.productId(), l.qty())).toList(), orderId);
    wallet.redeem(userId, q.coinsUsed(), orderId);
    promotions.consume(q.couponCode(), userId, orderId, q.couponDiscountPaise());
    payments.start(orderId, orderNo, q.totalPaise(), method);

    log.info("Order placed orderNo={} user={} total={} method={} status={} coinsUsed={} coupon={} items={}", orderNo, userId,
        q.totalPaise(), method, status, q.coinsUsed(), q.couponCode(), q.lines().size());
    outbox.add("ORDER_PLACED", Map.of("orderId", orderId, "orderNo", orderNo, "userId", userId, "totalPaise", q.totalPaise()));
    return orderId;
  }

  // ---------- status changes ----------
  @Transactional
  public OrderViews.AdminView adminChangeStatus(UUID orderId, OrderStatus target, String note, UUID actor) {
    OrderRepository.Row row = repo.find(orderId).orElseThrow(() -> ApiException.notFound("Order"));
    transition(row, target, actor, note);
    audit.log(actor, "ORDER_STATUS", "orders", orderId, target.name());
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
      throw ApiException.conflict("ILLEGAL_TRANSITION", "Cannot move from " + from + " to " + target, Map.of());
    }
    Instant now = clock.instant();
    if (!repo.updateStatus(row.id(), target.name(), row.version(), now)) {
      throw ApiException.conflict("VERSION_CONFLICT", "Order was changed by someone else, reload and retry", Map.of());
    }
    repo.insertHistory(row.id(), from.name(), target.name(), actor, note, now);
    log.info("Order status orderNo={} {} -> {} actor={} note={}", row.orderNo(), from, target, actor, note);

    if (target == OrderStatus.CANCELLED) onCancelled(row);
    if (target.completesOrder()) onCompleted(row);
    outbox.add("ORDER_STATUS", Map.of("orderId", row.id(), "orderNo", row.orderNo(), "userId", row.userId(), "status", target.name()));
  }

  private void onCancelled(OrderRepository.Row row) {
    inventory.release(repo.items(row.id()).stream().map(i -> new InventoryService.Item(i.productId(), i.qty())).toList(), row.id());
    promotions.release(row.id());
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
      if (payments.refund(row.id(), "paid after expiry")) repo.setPaymentStatus(row.id(), "REFUNDED", clock.instant());
    }
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
        r.totalPaise(), r.itemCount(), r.placedAt())).toList(), p.page(), p.size(), p.total());
  }

  public Page<OrderViews.AdminSummary> adminList(String status, int page, int size) {
    Page<OrderRepository.SummaryRow> p = repo.adminList(status, Math.max(0, page), Page.clampSize(size));
    Map<UUID, User> customers = profiles.users(p.items().stream().map(OrderRepository.SummaryRow::userId).distinct().toList());
    return new Page<>(p.items().stream().map(r -> {
      User u = customers.get(r.userId());
      return new OrderViews.AdminSummary(r.id(), r.orderNo(), r.status(), r.fulfilment(), r.totalPaise(), r.itemCount(),
          r.placedAt(), u == null ? null : u.name(), u == null ? null : u.email(), u == null ? null : u.mobile());
    }).toList(), p.page(), p.size(), p.total());
  }

  @Override
  public Map<UUID, String> orderNumbers(java.util.Collection<UUID> orderIds) { return repo.numbers(orderIds); }

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
        r.deliverySlot(), r.cancelReason());
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
