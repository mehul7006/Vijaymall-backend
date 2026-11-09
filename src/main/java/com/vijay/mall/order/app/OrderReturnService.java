package com.vijay.mall.order.app;

import com.vijay.mall.common.CouponReturnMath;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.outbox.OutboxService;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.inventory.app.DefectiveService;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.order.domain.OrderChanged;
import com.vijay.mall.order.domain.OrderReturnMath;
import com.vijay.mall.order.infra.OrderRepository;
import com.vijay.mall.order.infra.OrderReturnRepository;
import com.vijay.mall.payment.app.PaymentService;
import com.vijay.mall.wallet.app.WalletService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
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
  private DefectiveService defective;

  private RuntimeSettings settings;

  /** Optional (plain unit tests run without it): the admin's coupon policy for returns. */
  @Autowired(required = false)
  void setSettings(RuntimeSettings settings) { this.settings = settings; }

  private String policy() { return settings == null ? CouponReturnMath.KEEP : settings.get(Settings.RETURNS_COUPON_POLICY); }

  /** Optional (plain unit tests run without it): where goods returned as defective are kept. */
  @Autowired(required = false)
  void setDefective(DefectiveService defective) { this.defective = defective; }

  /**
   * One product the staff member returns.
   * @param productId the product on the order
   * @param qty units coming back, at most what is still returnable
   * @param restock {@code false} to treat the units as defective (refund, but not back on the shelf); {@code null} or {@code true} puts them back
   */
  @Schema(name = "ReturnItemInput")
  public record ItemInput(UUID productId, int qty, Boolean restock) {}

  /**
   * What staff send to record a return.
   * @param reason why, 1 to 200 characters
   * @param items 1 to 100 products, each at most once
   */
  public record Input(String reason, List<ItemInput> items) {}

  /**
   * One returned product as shown back.
   * @param refundPaise the refund for these units, in paise
   * @param restocked whether the units went back on the shelf
   * @param couponSharePaise the coupon discount that left with these units (not refunded), in paise
   */
  @Schema(name = "ReturnItemView")
  public record ItemView(UUID productId, String name, int qty, long refundPaise, boolean restocked, long couponSharePaise) {
    /** An item that carried no coupon share. */
    public ItemView(UUID productId, String name, int qty, long refundPaise, boolean restocked) {
      this(productId, name, qty, refundPaise, restocked, 0);
    }
  }

  /** refundMode is ONLINE (back to the customer's payment method), CASH (the shop hands it over; cash-on-delivery orders) or NONE. couponClawbackPaise is the coupon discount the CLAWBACK_BELOW_MINIMUM policy took back out of this refund (0 under KEEP). */
  public record ReturnView(UUID id, UUID orderId, String orderNo, String reason, long refundPaise, String refundMode,
                           int coinsClawedBack, int coinsRestored, Instant createdAt, List<ItemView> items, long couponClawbackPaise) {
    /** A return that took no coupon discount back. */
    public ReturnView(UUID id, UUID orderId, String orderNo, String reason, long refundPaise, String refundMode,
                      int coinsClawedBack, int coinsRestored, Instant createdAt, List<ItemView> items) {
      this(id, orderId, orderNo, reason, refundPaise, refundMode, coinsClawedBack, coinsRestored, createdAt, items, 0);
    }
  }

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

  /** Creates the service. */
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

  /**
   * Records a return on a delivered or picked-up order, once per idempotency key (a retry returns the first result).
   * <p>Refunds the returned lines' share of what was really paid, puts resellable units back on the shelf (others go to the defective ledger), and moves coins. Errors: {@code 409 ORDER_NOT_DELIVERED}, {@code 409 RETURN_EXCEEDS_SOLD}, {@code 409 REFUND_EXCEEDS_ORDER}, and validation errors for reason and items.
   * @param actor the staff member recording the return
   * @param orderId the order
   * @param idemKey the required {@code Idempotency-Key}
   * @param in the reason and lines
   */
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

  /** Everything worked out before anything is changed: the order, its lines, who asked for what, and the refund plan. */
  private record Prepared(OrderRepository.Row order, List<OrderRepository.ItemRow> lines, Map<UUID, ItemInput> asked,
                          Map<UUID, Integer> perLine, CouponReturnMath.Plan plan, String reason, String policy) {}

  private Prepared prepare(UUID orderId, Input in) {
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
    Map<UUID, Integer> perLine = new HashMap<>(); // order line id -> units coming back on it
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
    long[] coinShare = OrderReturnMath.allocate(Math.min(order.coinDiscount(), Arrays.stream(net).sum()), net);
    List<CouponReturnMath.Line> sold = new ArrayList<>();
    for (int k = 0; k < lines.size(); k++) {
      OrderRepository.ItemRow l = lines.get(k);
      sold.add(new CouponReturnMath.Line(l.qty(), l.lineTotalPaise(), Math.min(l.couponDiscountPaise(), l.lineTotalPaise()), net[k] - coinShare[k]));
    }
    Map<UUID, Integer> index = new HashMap<>();
    for (int k = 0; k < lines.size(); k++) index.put(lines.get(k).id(), k);
    List<CouponReturnMath.Earlier> earlier = new ArrayList<>();
    for (OrderReturnRepository.Return r : returns.forOrder(orderId)) {
      int[] back = new int[lines.size()];
      r.lines().forEach(rl -> back[index.get(rl.orderItemId())] += rl.qty());
      earlier.add(new CouponReturnMath.Earlier(back, r.couponClawbackPaise()));
    }
    int[] returning = new int[lines.size()];
    for (int k = 0; k < lines.size(); k++) returning[k] = perLine.getOrDefault(lines.get(k).id(), 0);
    String policy = policy();
    CouponReturnMath.Plan plan = CouponReturnMath.plan(sold, lines.stream().mapToInt(OrderRepository.ItemRow::returnedQty).toArray(), earlier, returning, policy, orders.couponMinimum(orderId));
    return new Prepared(order, lines, asked, perLine, plan, reason, policy);
  }

  private UUID doCreate(UUID actor, UUID orderId, String idemKey, Input in) {
    Prepared p = prepare(orderId, in);
    OrderRepository.Row order = p.order();
    List<OrderRepository.ItemRow> lines = p.lines();
    String reason = p.reason();
    OrderReturnRepository.Totals prior = returns.totals(orderId);

    UUID returnId = UUID.randomUUID();
    List<OrderReturnRepository.Line> stored = new ArrayList<>();
    List<InventoryService.Item> restock = new ArrayList<>();
    List<InventoryService.Item> defectiveGoods = new ArrayList<>();
    long refund = 0;
    long cumulativeGross = 0;
    for (int k = 0; k < lines.size(); k++) {
      OrderRepository.ItemRow l = lines.get(k);
      ItemInput i = p.asked().get(l.productId());
      int returning = p.perLine().getOrDefault(l.id(), 0);
      cumulativeGross += OrderReturnMath.share(l.lineTotalPaise(), l.qty(), 0, l.returnedQty() + returning);
      if (returning == 0) continue;
      long lineRefund = p.plan().refundPaise()[k];
      boolean back = i.restock() == null || i.restock();
      if (!returns.addReturned(l.id(), returning)) {
        throw ApiException.conflict("RETURN_EXCEEDS_SOLD", "More than what is left to return", Map.of("productId", l.productId().toString()));
      }
      refund += lineRefund;
      stored.add(new OrderReturnRepository.Line(l.id(), returning, lineRefund, back, p.plan().couponSharePaise()[k]));
      if (back) restock.add(new InventoryService.Item(l.productId(), returning, l.id()));
      else defectiveGoods.add(new InventoryService.Item(l.productId(), returning, l.id()));
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
    // goods that are not fit to sell are kept apart for the owner to decide on, not lost
    if (defective != null && !defectiveGoods.isEmpty()) defective.record(defectiveGoods, "ORDER_RETURN", returnId, actor, reason);
    else if (!defectiveGoods.isEmpty()) inventory.release(defectiveGoods, returnId, "ORDER_RETURN"); // no defective ledger wired: never lose the units

    String mode = refund <= 0 ? "NONE" : "ONLINE".equals(order.paymentMethod()) && payments.refundPartial(orderId, refund, "return " + order.orderNo())
        ? "ONLINE" : "CASH";
    Instant now = clock.instant();
    long clawbackTotal = p.plan().clawbackTotalPaise();
    returns.insert(returnId, orderId, idemKey, reason, refund, mode, clawed, restored, actor, stored, now, clawbackTotal);
    audit.log(actor, "ORDER_RETURN", "orders", orderId, order.orderNo() + " refund=" + refund + " mode=" + mode
        + (clawbackTotal > 0 ? " couponClawback=" + clawbackTotal : "") + " " + reason);
    outbox.add("ORDER_RETURN", Map.of("orderId", orderId, "orderNo", order.orderNo(), "userId", order.userId(), "refundPaise", refund,
        "mode", mode));
    events.publishEvent(new OrderChanged(orderId, order.orderNo(), order.userId(), order.status(), order.status(), now));
    return returnId;
  }

  /**
   * One line of a return preview. For the returned pieces: {@code grossPaise} (their price), less {@code couponSharePaise} (their
   * share of the coupon discount), less {@code coinSharePaise} (their share of the coins spent), less {@code clawbackPaise} (coupon
   * discount taken back under the CLAWBACK_BELOW_MINIMUM policy) is the {@code refundPaise}.
   */
  @Schema(name = "ReturnPreviewItem")
  public record PreviewItem(UUID orderItemId, UUID productId, String name, int qty, long grossPaise, long couponSharePaise,
                            long coinSharePaise, long clawbackPaise, long refundPaise) {}

  /**
   * What a return would refund, exactly as recording it would, without changing anything.
   * @param couponPolicy the policy in force, {@code KEEP} or {@code CLAWBACK_BELOW_MINIMUM}
   * @param refundPaise the total refund
   * @param couponShareRemovedPaise the coupon discount that leaves with the returned pieces (the customer does not get this back)
   * @param coinShareRemovedPaise the coin value that leaves with them
   * @param couponClawbackPaise coupon discount of the kept pieces taken back out of this refund (0 under KEEP)
   * @param deliveryFeeRefundedPaise always 0: the delivery fee is not refunded
   */
  @Schema(name = "ReturnPreview")
  public record PreviewView(UUID orderId, String orderNo, String couponPolicy, long refundPaise, long couponShareRemovedPaise,
                            long coinShareRemovedPaise, long couponClawbackPaise, long deliveryFeeRefundedPaise,
                            List<PreviewItem> items) {}

  /**
   * The refund a return would give, with the coupon and coin share per line. Same checks and the same maths as {@link #create}, no
   * stock, money or coin movement and nothing stored.
   * @param orderId the order
   * @param in the reason (optional here) and lines, as for {@link #create}
   */
  public PreviewView preview(UUID orderId, Input in) {
    Input asked = in.reason() == null || in.reason().isBlank() ? new Input("preview", in.items()) : in;
    Prepared p = tx.execute(_ -> prepare(orderId, asked));
    CouponReturnMath.Plan plan = p.plan();
    List<PreviewItem> items = new ArrayList<>();
    for (int k = 0; k < p.lines().size(); k++) {
      OrderRepository.ItemRow l = p.lines().get(k);
      int returning = p.perLine().getOrDefault(l.id(), 0);
      if (returning == 0) continue;
      items.add(new PreviewItem(l.id(), l.productId(), l.name(), returning, plan.grossPaise()[k], plan.couponSharePaise()[k],
          plan.otherSharePaise()[k], plan.clawbackPaise()[k], plan.refundPaise()[k]));
    }
    return new PreviewView(orderId, p.order().orderNo(), p.policy(), items.stream().mapToLong(PreviewItem::refundPaise).sum(),
        items.stream().mapToLong(PreviewItem::couponSharePaise).sum(), items.stream().mapToLong(PreviewItem::coinSharePaise).sum(),
        plan.clawbackTotalPaise(), 0, items);
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
      return new ItemView(row == null ? null : row.productId(), row == null ? "?" : row.name(), l.qty(), l.refundPaise(), l.restocked(), l.couponSharePaise());
    }).toList();
    return new ReturnView(r.id(), r.orderId(), orderNo, r.reason(), r.refundPaise(), r.refundMode(), r.coinsClawedBack(),
        r.coinsRestored(), r.createdAt(), items, r.couponClawbackPaise());
  }
}
