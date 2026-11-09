package com.vijay.mall.order.infra;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Stores and reads returns of online orders and their lines. */
@Repository
public class OrderReturnRepository {
  /** A line of a return: which order line, how many pieces, what was refunded for them, and whether they went back on the shelf. */
  public record Line(UUID orderItemId, int qty, long refundPaise, boolean restocked, long couponSharePaise) {
    /** A line that carries no coupon share. */
    public Line(UUID orderItemId, int qty, long refundPaise, boolean restocked) { this(orderItemId, qty, refundPaise, restocked, 0); }
  }

  /**
   * A recorded return.
   * @param refundPaise the refund, in paise
   * @param refundMode {@code ONLINE}, {@code CASH} or {@code NONE}
   * @param coinsClawedBack earned coins taken back
   * @param coinsRestored spent coins given back
   * @param createdBy the staff member
   * @param couponClawbackPaise coupon discount taken back out of the refund by the CLAWBACK_BELOW_MINIMUM policy (0 otherwise)
   */
  public record Return(UUID id, UUID orderId, String reason, long refundPaise, String refundMode, int coinsClawedBack,
                       int coinsRestored, UUID createdBy, Instant createdAt, List<Line> lines, long couponClawbackPaise) {
    /** A return that took no coupon discount back. */
    public Return(UUID id, UUID orderId, String reason, long refundPaise, String refundMode, int coinsClawedBack,
                  int coinsRestored, UUID createdBy, Instant createdAt, List<Line> lines) {
      this(id, orderId, reason, refundPaise, refundMode, coinsClawedBack, coinsRestored, createdBy, createdAt, lines, 0);
    }
  }

  /** What earlier returns of one order already moved, so a new return only moves its own share. */
  public record Totals(long refundPaise, int coinsClawedBack, int coinsRestored) {}

  private final OrderReturnJpa returns;
  private final OrderReturnItemJpa lines;
  private final OrderItemJpa orderItems;

  OrderReturnRepository(OrderReturnJpa returns, OrderReturnItemJpa lines, OrderItemJpa orderItems) {
    this.returns = returns;
    this.lines = lines;
    this.orderItems = orderItems;
  }

  private List<Return> withLines(List<OrderReturnEntity> found) {
    Map<UUID, List<Line>> byReturn = found.isEmpty() ? Map.of() : lines.findByReturnIdIn(found.stream().map(r -> r.id).toList()).stream()
        .collect(Collectors.groupingBy(l -> l.returnId, Collectors.mapping(
            l -> new Line(l.orderItemId, l.qty, l.refundPaise, l.restocked, l.couponSharePaise), Collectors.toList())));
    return found.stream().map(r -> new Return(r.id, r.orderId, r.reason, r.refundPaise, r.refundMode, r.coinsClawedBack,
        r.coinsRestored, r.createdBy, r.createdAt, byReturn.getOrDefault(r.id, List.of()), r.couponClawbackPaise)).toList();
  }

  /** The return recorded with this idempotency key, if any. */
  public Optional<Return> findByIdem(String key) {
    return returns.findByIdemKey(key).map(r -> withLines(List.of(r)).get(0));
  }

  /** The return with this id, with its lines. */
  public Optional<Return> find(UUID id) { return returns.findById(id).map(r -> withLines(List.of(r)).get(0)); }

  /** The returns of one order, oldest first. */
  public List<Return> forOrder(UUID orderId) { return withLines(returns.findByOrderIdOrderByCreatedAtAsc(orderId)); }

  /** Sums of refund and coin movements of the earlier returns of the order (zero when there are none). */
  public Totals totals(UUID orderId) {
    Object[] t = returns.totalsFor(orderId).get(0);
    return new Totals(((Number) t[0]).longValue(), ((Number) t[1]).intValue(), ((Number) t[2]).intValue());
  }

  /** False when another return took the pieces first (the line would end up with more returned than sold). */
  @Transactional
  public boolean addReturned(UUID orderItemId, int qty) { return orderItems.addReturned(orderItemId, qty) == 1; }

  /** Flushes at once so a repeated idempotency key fails here, inside the caller's transaction. */
  @Transactional
  public UUID insert(UUID id, UUID orderId, String idemKey, String reason, long refundPaise, String refundMode, int clawed, int restored,
                     UUID actor, Collection<Line> items, Instant now) {
    return insert(id, orderId, idemKey, reason, refundPaise, refundMode, clawed, restored, actor, items, now, 0);
  }

  /** As above, also keeping the coupon discount the policy took back out of the refund. */
  @Transactional
  public UUID insert(UUID id, UUID orderId, String idemKey, String reason, long refundPaise, String refundMode, int clawed, int restored,
                     UUID actor, Collection<Line> items, Instant now, long couponClawbackPaise) {
    OrderReturnEntity e = new OrderReturnEntity();
    e.id = id;
    e.orderId = orderId;
    e.idemKey = idemKey;
    e.reason = reason;
    e.refundPaise = refundPaise;
    e.refundMode = refundMode;
    e.coinsClawedBack = clawed;
    e.coinsRestored = restored;
    e.couponClawbackPaise = couponClawbackPaise;
    e.createdBy = actor;
    e.createdAt = now;
    returns.saveAndFlush(e);
    for (Line l : items) {
      OrderReturnItemEntity i = new OrderReturnItemEntity();
      i.id = UUID.randomUUID();
      i.returnId = e.id;
      i.orderItemId = l.orderItemId();
      i.qty = l.qty();
      i.refundPaise = l.refundPaise();
      i.restocked = l.restocked();
      i.couponSharePaise = l.couponSharePaise();
      lines.save(i);
    }
    return e.id;
  }
}
