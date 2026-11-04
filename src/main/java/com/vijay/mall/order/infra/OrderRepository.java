package com.vijay.mall.order.infra;

import com.vijay.mall.common.Page;
import com.vijay.mall.order.domain.OrderViews;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OrderRepository {
  public record Row(UUID id, String orderNo, UUID userId, String fulfilment, String status, long subtotal,
                    String couponCode, long couponDiscount, int coinsUsed, long coinDiscount, long deliveryFee,
                    long total, int coinsEarned, boolean coinsCredited, String paymentMethod, String paymentStatus,
                    String addressSnapshot, Instant expiresAt, Instant placedAt, long version, String deliverySlot,
                    String cancelReason) {
    public Row(UUID id, String orderNo, UUID userId, String fulfilment, String status, long subtotal,
               String couponCode, long couponDiscount, int coinsUsed, long coinDiscount, long deliveryFee,
               long total, int coinsEarned, boolean coinsCredited, String paymentMethod, String paymentStatus,
               String addressSnapshot, Instant expiresAt, Instant placedAt, long version) {
      this(id, orderNo, userId, fulfilment, status, subtotal, couponCode, couponDiscount, coinsUsed, coinDiscount,
          deliveryFee, total, coinsEarned, coinsCredited, paymentMethod, paymentStatus, addressSnapshot, expiresAt,
          placedAt, version, null, null);
    }
  }

  public record NewOrder(UUID id, String orderNo, UUID userId, String idemKey, String fulfilment, String status,
                         long subtotal, String couponCode, long couponDiscount, int coinsUsed, long coinDiscount,
                         long deliveryFee, long total, int coinsEarned, String paymentMethod, String paymentStatus,
                         String addressSnapshot, Instant expiresAt, Instant now, String deliverySlot) {}

  public record NewItem(UUID productId, String name, long unitPrice, int qty, long lineTotal, boolean earns,
                        boolean redeemable, String nameGu, String unitLabel) {
    public NewItem(UUID productId, String name, long unitPrice, int qty, long lineTotal, boolean earns, boolean redeemable) {
      this(productId, name, unitPrice, qty, lineTotal, earns, redeemable, name, "");
    }
  }

  /** One line of an order list; the customer's name is added by the service, which knows the identity module. */
  public record SummaryRow(UUID id, UUID userId, String orderNo, String status, String fulfilment, long totalPaise,
                           int itemCount, Instant placedAt, long subtotalPaise, String couponCode,
                           long couponDiscountPaise, int coinsUsed, long coinDiscountPaise, long deliveryFeePaise,
                           String paymentMethod, String paymentStatus) {}

  private final OrderJpa orders;
  private final OrderItemJpa items;
  private final OrderHistoryJpa history;

  @PersistenceContext
  private EntityManager em;

  OrderRepository(OrderJpa orders, OrderItemJpa items, OrderHistoryJpa history) {
    this.orders = orders;
    this.items = items;
    this.history = history;
  }

  private static Row toRow(OrderEntity e) {
    return new Row(e.id, e.orderNo, e.userId, e.fulfilment, e.status, e.subtotalPaise, e.couponCode, e.couponDiscountPaise,
        e.coinsUsed, e.coinDiscountPaise, e.deliveryFeePaise, e.totalPaise, e.coinsEarned, e.coinsCredited, e.paymentMethod,
        e.paymentStatus, e.addressSnapshot, e.expiresAt, e.placedAt, e.version == null ? 0 : e.version, e.deliverySlot,
        e.cancelReason);
  }

  public String nextOrderNo() {
    Number n = (Number) em.createNativeQuery("select nextval('order_no_seq')").getSingleResult();
    return "VSM-" + n.longValue();
  }

  public Optional<Row> find(UUID id) { return orders.findById(id).map(OrderRepository::toRow); }

  public Optional<Row> findByIdem(UUID userId, String key) {
    return orders.findByUserIdAndIdemKey(userId, key).map(OrderRepository::toRow);
  }

  /** Flushes at once so a repeated idempotency key fails here, inside the transaction that can be rolled back. */
  @Transactional
  public void insert(NewOrder o) {
    OrderEntity e = new OrderEntity();
    e.id = o.id();
    e.orderNo = o.orderNo();
    e.userId = o.userId();
    e.idemKey = o.idemKey();
    e.fulfilment = o.fulfilment();
    e.status = o.status();
    e.subtotalPaise = o.subtotal();
    e.couponCode = o.couponCode();
    e.couponDiscountPaise = o.couponDiscount();
    e.coinsUsed = o.coinsUsed();
    e.coinDiscountPaise = o.coinDiscount();
    e.deliveryFeePaise = o.deliveryFee();
    e.totalPaise = o.total();
    e.coinsEarned = o.coinsEarned();
    e.paymentMethod = o.paymentMethod();
    e.paymentStatus = o.paymentStatus();
    e.addressSnapshot = o.addressSnapshot();
    e.deliverySlot = o.deliverySlot();
    e.expiresAt = o.expiresAt();
    e.placedAt = o.now();
    e.updatedAt = o.now();
    orders.saveAndFlush(e);
  }

  @Transactional
  public void insertItem(UUID orderId, NewItem i) {
    OrderItemEntity e = new OrderItemEntity();
    e.id = UUID.randomUUID();
    e.orderId = orderId;
    e.productId = i.productId();
    e.name = i.name();
    e.nameGu = i.nameGu() == null ? i.name() : i.nameGu();
    e.unitLabel = i.unitLabel() == null ? "" : i.unitLabel();
    e.unitPricePaise = i.unitPrice();
    e.qty = i.qty();
    e.lineTotalPaise = i.lineTotal();
    e.earnsCoins = i.earns();
    e.coinRedeemable = i.redeemable();
    items.save(e);
  }

  @Transactional
  public void insertHistory(UUID orderId, String from, String to, UUID actor, String note, Instant now) {
    OrderHistoryEntity e = new OrderHistoryEntity();
    e.id = UUID.randomUUID();
    e.orderId = orderId;
    e.fromStatus = from;
    e.toStatus = to;
    e.actorId = actor;
    e.note = note;
    e.createdAt = now;
    history.save(e);
  }

  public List<OrderViews.Item> items(UUID orderId) {
    return items.findByOrderIdOrderByName(orderId).stream()
        .map(i -> new OrderViews.Item(i.productId, i.name, i.qty, i.unitPricePaise, i.lineTotalPaise, i.nameGu,
            i.unitLabel)).toList();
  }

  public List<OrderViews.Step> timeline(UUID orderId) {
    return history.findByOrderIdOrderByCreatedAtAsc(orderId).stream()
        .map(h -> new OrderViews.Step(h.toStatus, h.createdAt)).toList();
  }

  /** Moves status if the version still matches. Returns false when someone else changed the order first. */
  @Transactional
  public boolean updateStatus(UUID id, String status, long expectedVersion, Instant now) {
    OrderEntity e = orders.findById(id).orElse(null);
    if (e == null || e.version == null || e.version != expectedVersion) return false;
    e.status = status;
    e.updatedAt = now;
    try {
      orders.saveAndFlush(e);
      return true;
    } catch (OptimisticLockingFailureException ex) {
      return false;
    }
  }

  @Transactional
  public void setPaymentStatus(UUID id, String status, Instant now) {
    orders.findById(id).ifPresent(e -> {
      e.paymentStatus = status;
      e.updatedAt = now;
    });
  }

  @Transactional
  public void setCancelReason(UUID id, String reason) { orders.findById(id).ifPresent(e -> e.cancelReason = reason); }

  /** The person's latest delivered or picked-up order that contains the product. */
  public Optional<UUID> latestCompletedOrderWith(UUID userId, UUID productId) {
    return em.createQuery("select o.id from OrderEntity o, OrderItemEntity i where i.orderId = o.id and i.productId = :p "
            + "and o.userId = :u and o.status in ('DELIVERED', 'PICKED_UP') order by o.placedAt desc", UUID.class)
        .setParameter("p", productId).setParameter("u", userId).setMaxResults(1).getResultList().stream().findFirst();
  }

  /** Orders the person has placed that were not cancelled; used for "first order only" coupons. */
  public long activeOrderCount(UUID userId) { return orders.countByUserIdAndStatusNot(userId, "CANCELLED"); }

  @Transactional
  public void markCoinsCredited(UUID id) { orders.findById(id).ifPresent(e -> e.coinsCredited = true); }

  public List<UUID> expiredPending(Instant now, int limit) {
    return orders.expiredPending(now, PageRequest.of(0, limit)).stream().map(e -> e.id).toList();
  }

  public Map<UUID, String> numbers(Collection<UUID> ids) {
    if (ids.isEmpty()) return Map.of();
    return orders.numbers(ids).stream().collect(java.util.stream.Collectors.toMap(r -> (UUID) r[0], r -> (String) r[1]));
  }

  private Page<SummaryRow> summaries(org.springframework.data.domain.Page<OrderEntity> page, int pageNo, int size) {
    List<UUID> ids = page.getContent().stream().map(e -> e.id).toList();
    Map<UUID, Integer> counts = ids.isEmpty() ? Map.of() : items.countsFor(ids).stream()
        .collect(java.util.stream.Collectors.toMap(r -> (UUID) r[0], r -> ((Number) r[1]).intValue()));
    List<SummaryRow> rows = page.getContent().stream()
        .map(e -> new SummaryRow(e.id, e.userId, e.orderNo, e.status, e.fulfilment, e.totalPaise,
            counts.getOrDefault(e.id, 0), e.placedAt, e.subtotalPaise, e.couponCode, e.couponDiscountPaise, e.coinsUsed,
            e.coinDiscountPaise, e.deliveryFeePaise, e.paymentMethod, e.paymentStatus)).toList();
    return new Page<>(rows, pageNo, size, page.getTotalElements());
  }

  public Page<SummaryRow> list(UUID userId, int page, int size) {
    return summaries(orders.findByUserId(userId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "placedAt"))), page, size);
  }

  public Page<SummaryRow> adminList(String status, int page, int size) {
    PageRequest req = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "placedAt"));
    var result = (status == null || status.isBlank()) ? orders.findAll(req) : orders.findByStatus(status, req);
    return summaries(result, page, size);
  }
}
