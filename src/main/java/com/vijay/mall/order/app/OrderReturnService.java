package com.vijay.mall.order.app;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.outbox.OutboxService;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.order.domain.OrderChanged;
import com.vijay.mall.order.domain.OrderReturnMath;
import com.vijay.mall.order.infra.OrderRepository;
import com.vijay.mall.order.infra.OrderReturnRepository;
import com.vijay.mall.payment.app.PaymentService;
import com.vijay.mall.wallet.app.WalletService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Goods coming back from a delivered online order (damaged, wrong or missing items). Staff pick the lines and quantities;
 * the customer is refunded those lines' share of what they really paid (after coupon and coins, delivery fee excluded),
 * resellable goods go back on the shelf, and the coins earned or spent on those goods are adjusted. Once per idempotency key.
 */
@Service
public class OrderReturnService {
  public record ItemInput(UUID productId, int qty, Boolean restock) {}

  public record Input(String reason, List<ItemInput> items) {}

  public record ItemView(UUID productId, String name, int qty, long refundPaise, boolean restocked) {}

  /** refundMode is ONLINE (back to the customer's payment method), CASH (the shop hands it over; cash-on-delivery orders) or NONE. */
  public record ReturnView(UUID id, UUID orderId, String orderNo, String reason, long refundPaise, String refundMode,
                           int coinsClawedBack, int coinsRestored, Instant createdAt, List<ItemView> items) {}

  static final int MAX_ITEMS = 100;
  static final int MAX_REASON = 200;

  private final OrderRepository orders;
  private final OrderReturnRepository returns;
  private final InventoryService inventory;
  private final WalletService wallet;
  private final PaymentService payments;
  private final OutboxService outbox;
  private final AuditService audit;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final ApplicationEventPublisher events;

  public OrderReturnService(OrderRepository orders, OrderReturnRepository returns, InventoryService inventory, WalletService wallet,
                            PaymentService payments, OutboxService outbox, AuditService audit, TransactionTemplate tx, Clock clock,
                            ApplicationEventPublisher events) {
    this.orders = orders;
    this.returns = returns;
    this.inventory = inventory;
    this.wallet = wallet;
    this.payments = payments;
    this.outbox = outbox;
    this.audit = audit;
    this.tx = tx;
    this.clock = clock;
    this.events = events;
  }

  public ReturnView create(UUID actor, UUID orderId, String idemKey, Input in) {
    if (idemKey == null || idemKey.isBlank()) throw ApiException.validation("Idempotency-Key", "header is required");
    var existing = returns.findByIdem(idemKey);
    if (existing.isPresent()) return view(existing.get());
    try {
      UUID id = tx.execute(s -> doCreate(actor, orderId, idemKey, in));
      return view(returns.find(id).orElseThrow());
    } catch (DataIntegrityViolationException e) {
      return view(returns.findByIdem(idemKey).orElseThrow(() -> e));
    }
  }

  private UUID doCreate(UUID actor, UUID orderId, String idemKey, Input in) {
    OrderRepository.Row order = orders.find(orderId).orElseThrow(() -> ApiException.notFound("Order"));
    if (!"DELIVERED".equals(order.status()) && !"PICKED_UP".equals(order.status())) {
      throw ApiException.conflict("ORDER_NOT_DELIVERED", "Only a delivered or picked-up order can have items returned", Map.of());
    }
    String reason = in.reason() == null ? "" : in.reason().strip();
    if (reason.isEmpty() || reason.length() > MAX_REASON) throw ApiException.validation("reason", "1 to " + MAX_REASON + " characters");
    List<ItemInput> wanted = in.items() == null ? List.of() : in.items();
    if (wanted.isEmpty() || wanted.size() > MAX_ITEMS) throw ApiException.validation("items", "1 to " + MAX_ITEMS + " products");

    List<OrderRepository.ItemRow> lines = orders.itemRows(orderId);
    // One product can be on an order twice (a flash-sale line and a normal-price line), so lines are grouped by product.
    Map<UUID, List<OrderRepository.ItemRow>> byProduct = new LinkedHashMap<>();
    for (OrderRepository.ItemRow l : lines) byProduct.computeIfAbsent(l.productId(), _ -> new ArrayList<>()).add(l);
    Map<UUID, ItemInput> asked = new LinkedHashMap<>();
    Map<UUID, Integer> perLine = new java.util.HashMap<>(); // order line id -> units coming back on it
    Set<UUID> seen = new HashSet<>();
    for (ItemInput i : wanted) {
      if (i.productId() == null || !byProduct.containsKey(i.productId())) throw ApiException.validation("productId", "is not on this order");
      if (!seen.add(i.productId())) throw ApiException.validation("items", "a product appears twice");
      if (i.qty() <= 0) throw ApiException.validation("qty", "must be positive");
      List<OrderRepository.ItemRow> own = byProduct.get(i.productId());
      int returnable = own.stream().mapToInt(l -> l.qty() - l.returnedQty()).sum();
      if (i.qty() > returnable) {
        throw ApiException.conflict("RETURN_EXCEEDS_SOLD", "More than what is left to return",
            Map.of("productId", i.productId().toString(), "returnable", returnable));
      }
      // units are taken from the product's lines in the order they were sold (a flash-sale line first, then the normal-price line)
      int left = i.qty();
      for (OrderRepository.ItemRow l : own) {
        int take = Math.min(left, l.qty() - l.returnedQty());
        if (take > 0) perLine.put(l.id(), take);
        left -= take;
      }
      asked.put(i.productId(), i);
    }

    // What each line really cost the customer: its price less its coupon share, less its share of the coins spent.
    long[] net = lines.stream().mapToLong(l -> Math.max(0, l.lineTotalPaise() - l.couponDiscountPaise())).toArray();
    long[] coinShare = OrderReturnMath.allocate(Math.min(order.coinDiscount(), java.util.Arrays.stream(net).sum()), net);
    OrderReturnRepository.Totals prior = returns.totals(orderId);

    UUID returnId = UUID.randomUUID();
    List<OrderReturnRepository.Line> stored = new ArrayList<>();
    List<InventoryService.Item> restock = new ArrayList<>();
    long refund = 0;
    long cumulativeGross = 0;
    for (int k = 0; k < lines.size(); k++) {
      OrderRepository.ItemRow l = lines.get(k);
      ItemInput i = asked.get(l.productId());
      int returning = perLine.getOrDefault(l.id(), 0);
      cumulativeGross += OrderReturnMath.share(l.lineTotalPaise(), l.qty(), 0, l.returnedQty() + returning);
      if (returning == 0) continue;
      long lineRefund = OrderReturnMath.share(net[k] - coinShare[k], l.qty(), l.returnedQty(), returning);
      boolean back = i.restock() == null || i.restock();
      if (!returns.addReturned(l.id(), returning)) {
        throw ApiException.conflict("RETURN_EXCEEDS_SOLD", "More than what is left to return", Map.of("productId", l.productId().toString()));
      }
      refund += lineRefund;
      stored.add(new OrderReturnRepository.Line(l.id(), returning, lineRefund, back));
      if (back) restock.add(new InventoryService.Item(l.productId(), returning));
    }
    if (prior.refundPaise() + refund > order.total()) {
      throw ApiException.conflict("REFUND_EXCEEDS_ORDER", "More than the order was worth", Map.of("totalPaise", order.total()));
    }

    int clawed = 0;
    int restored = 0;
    if (order.coinsCredited()) {
      int claw = OrderReturnMath.coinsDelta(order.coinsEarned(), cumulativeGross, order.subtotal(), prior.coinsClawedBack());
      clawed = wallet.clawback(order.userId(), claw, returnId);
    }
    int restore = OrderReturnMath.coinsDelta(order.coinsUsed(), cumulativeGross, order.subtotal(), prior.coinsRestored());
    wallet.restore(order.userId(), restore, returnId);
    restored = restore;

    if (!restock.isEmpty()) inventory.release(restock, returnId, "ORDER_RETURN");

    String mode = refund <= 0 ? "NONE" : "ONLINE".equals(order.paymentMethod()) && payments.refundPartial(orderId, refund, "return " + order.orderNo())
        ? "ONLINE" : "CASH";
    Instant now = clock.instant();
    returns.insert(returnId, orderId, idemKey, reason, refund, mode, clawed, restored, actor, stored, now);
    audit.log(actor, "ORDER_RETURN", "orders", orderId, order.orderNo() + " refund=" + refund + " mode=" + mode + " " + reason);
    outbox.add("ORDER_RETURN", Map.of("orderId", orderId, "orderNo", order.orderNo(), "userId", order.userId(), "refundPaise", refund,
        "mode", mode));
    events.publishEvent(new OrderChanged(orderId, order.orderNo(), order.userId(), order.status(), order.status(), now));
    return returnId;
  }

  /** The returns of one order, oldest first. {@code onlyFor} limits it to the customer's own order (null for staff). */
  public List<ReturnView> forOrder(UUID orderId, UUID onlyFor) {
    OrderRepository.Row order = orders.find(orderId).filter(o -> onlyFor == null || o.userId().equals(onlyFor))
        .orElseThrow(() -> ApiException.notFound("Order"));
    return returns.forOrder(orderId).stream().map(r -> view(r, order.orderNo(), orders.itemRows(orderId))).toList();
  }

  private ReturnView view(OrderReturnRepository.Return r) {
    String no = orders.find(r.orderId()).map(OrderRepository.Row::orderNo).orElse("?");
    return view(r, no, orders.itemRows(r.orderId()));
  }

  private static ReturnView view(OrderReturnRepository.Return r, String orderNo, List<OrderRepository.ItemRow> lines) {
    Map<UUID, OrderRepository.ItemRow> byId = lines.stream().collect(Collectors.toMap(OrderRepository.ItemRow::id, Function.identity()));
    List<ItemView> items = r.lines().stream().map(l -> {
      OrderRepository.ItemRow row = byId.get(l.orderItemId());
      return new ItemView(row == null ? null : row.productId(), row == null ? "?" : row.name(), l.qty(), l.refundPaise(), l.restocked());
    }).toList();
    return new ReturnView(r.id(), r.orderId(), orderNo, r.reason(), r.refundPaise(), r.refundMode(), r.coinsClawedBack(),
        r.coinsRestored(), r.createdAt(), items);
  }
}
