package com.vijay.mall.order.infra;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.ShopIdentity;
import com.vijay.mall.common.persistence.BoundedCount;
import com.vijay.mall.common.persistence.Window;
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
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores and reads online orders, their lines and status history. Other modules see rows and views, never the entities.
 */
@Repository
public class OrderRepository {
  /**
   * An order as stored. Money is in paise.
   * @param version optimistic-lock version used by {@link #updateStatus}
   * @param stockReserved whether the order currently holds shelf stock
   * @param coinsCredited whether the earned coins were already paid out
   * @param cancelledBy CUSTOMER, SHOP or SYSTEM once cancelled
   * @param cancelCode machine readable reason of the cancel
   */
  public record Row(UUID id, String orderNo, UUID userId, String fulfilment, String status, long subtotal,
                    String couponCode, long couponDiscount, int coinsUsed, long coinDiscount, long deliveryFee,
                    long total, int coinsEarned, boolean coinsCredited, String paymentMethod, String paymentStatus,
                    String addressSnapshot, Instant expiresAt, Instant placedAt, long version, String deliverySlot,
                    String cancelReason, boolean stockReserved, String cancelledBy, String cancelCode) {
    /** A row without who cancelled it and why (both {@code null}). */
    public Row(UUID id, String orderNo, UUID userId, String fulfilment, String status, long subtotal,
               String couponCode, long couponDiscount, int coinsUsed, long coinDiscount, long deliveryFee,
               long total, int coinsEarned, boolean coinsCredited, String paymentMethod, String paymentStatus,
               String addressSnapshot, Instant expiresAt, Instant placedAt, long version, String deliverySlot,
               String cancelReason, boolean stockReserved) {
      this(id, orderNo, userId, fulfilment, status, subtotal, couponCode, couponDiscount, coinsUsed, coinDiscount,
          deliveryFee, total, coinsEarned, coinsCredited, paymentMethod, paymentStatus, addressSnapshot, expiresAt,
          placedAt, version, deliverySlot, cancelReason, stockReserved, null, null);
    }

    /** A row without the stock-reserved flag (taken as {@code false}). */
    public Row(UUID id, String orderNo, UUID userId, String fulfilment, String status, long subtotal,
               String couponCode, long couponDiscount, int coinsUsed, long coinDiscount, long deliveryFee,
               long total, int coinsEarned, boolean coinsCredited, String paymentMethod, String paymentStatus,
               String addressSnapshot, Instant expiresAt, Instant placedAt, long version, String deliverySlot,
               String cancelReason) {
      this(id, orderNo, userId, fulfilment, status, subtotal, couponCode, couponDiscount, coinsUsed, coinDiscount,
          deliveryFee, total, coinsEarned, coinsCredited, paymentMethod, paymentStatus, addressSnapshot, expiresAt,
          placedAt, version, deliverySlot, cancelReason, false);
    }

    /** A row without slot, cancel reason and stock-reserved flag. */
    public Row(UUID id, String orderNo, UUID userId, String fulfilment, String status, long subtotal,
               String couponCode, long couponDiscount, int coinsUsed, long coinDiscount, long deliveryFee,
               long total, int coinsEarned, boolean coinsCredited, String paymentMethod, String paymentStatus,
               String addressSnapshot, Instant expiresAt, Instant placedAt, long version) {
      this(id, orderNo, userId, fulfilment, status, subtotal, couponCode, couponDiscount, coinsUsed, coinDiscount,
          deliveryFee, total, coinsEarned, coinsCredited, paymentMethod, paymentStatus, addressSnapshot, expiresAt,
          placedAt, version, null, null, false);
    }
  }

  /**
   * Everything needed to insert an order. Money is in paise.
   * @param idemKey the customer's idempotency key; unique per user
   * @param now the placement time
   */
  public record NewOrder(UUID id, String orderNo, UUID userId, String idemKey, String fulfilment, String status,
                         long subtotal, String couponCode, long couponDiscount, int coinsUsed, long coinDiscount,
                         long deliveryFee, long total, int coinsEarned, String paymentMethod, String paymentStatus,
                         String addressSnapshot, Instant expiresAt, Instant now, String deliverySlot) {}

  /**
   * Everything needed to insert one order line; names and flags are copied so the order reads right after the product changes.
   * @param earns whether the line earns coins
   * @param redeemable whether coins may pay for it
   * @param couponDiscount this line's share of the coupon discount, in paise
   * @param imageUrl the product picture now, kept with the line
   */
  public record NewItem(UUID productId, String name, long unitPrice, int qty, long lineTotal, boolean earns,
                        boolean redeemable, String nameGu, String unitLabel, long couponDiscount, String imageUrl) {
    /** A line without picture. */
    public NewItem(UUID productId, String name, long unitPrice, int qty, long lineTotal, boolean earns,
                   boolean redeemable, String nameGu, String unitLabel, long couponDiscount) {
      this(productId, name, unitPrice, qty, lineTotal, earns, redeemable, nameGu, unitLabel, couponDiscount, null);
    }

    /** A line without coupon share. */
    public NewItem(UUID productId, String name, long unitPrice, int qty, long lineTotal, boolean earns,
                   boolean redeemable, String nameGu, String unitLabel) {
      this(productId, name, unitPrice, qty, lineTotal, earns, redeemable, nameGu, unitLabel, 0);
    }

    /** A line without Gujarati name or pack label; the English name stands in. */
    public NewItem(UUID productId, String name, long unitPrice, int qty, long lineTotal, boolean earns, boolean redeemable) {
      this(productId, name, unitPrice, qty, lineTotal, earns, redeemable, name, "");
    }
  }

  /** One line of an order list; the customer's name is added by the service, which knows the identity module. */
  public record SummaryRow(UUID id, UUID userId, String orderNo, String status, String fulfilment, long totalPaise,
                           int itemCount, Instant placedAt, long subtotalPaise, String couponCode,
                           long couponDiscountPaise, int coinsUsed, long coinDiscountPaise, long deliveryFeePaise,
                           String paymentMethod, String paymentStatus, String cancelledBy, String cancelCode) {
    /** A line without who cancelled the order and why. */
    public SummaryRow(UUID id, UUID userId, String orderNo, String status, String fulfilment, long totalPaise,
                      int itemCount, Instant placedAt, long subtotalPaise, String couponCode,
                      long couponDiscountPaise, int coinsUsed, long coinDiscountPaise, long deliveryFeePaise,
                      String paymentMethod, String paymentStatus) {
      this(id, userId, orderNo, status, fulfilment, totalPaise, itemCount, placedAt, subtotalPaise, couponCode,
          couponDiscountPaise, coinsUsed, coinDiscountPaise, deliveryFeePaise, paymentMethod, paymentStatus, null, null);
    }
  }

  private final OrderJpa orders;
  private final OrderItemJpa items;
  private final OrderHistoryJpa history;

  private final ShopIdentity shop;

  @PersistenceContext
  private EntityManager em;

  OrderRepository(OrderJpa orders, OrderItemJpa items, OrderHistoryJpa history, ShopIdentity shop) {
    this.orders = orders;
    this.items = items;
    this.history = history;
    this.shop = shop;
  }

  private static Row toRow(OrderEntity e) {
    return new Row(e.id, e.orderNo, e.userId, e.fulfilment, e.status, e.subtotalPaise, e.couponCode, e.couponDiscountPaise,
        e.coinsUsed, e.coinDiscountPaise, e.deliveryFeePaise, e.totalPaise, e.coinsEarned, e.coinsCredited, e.paymentMethod,
        e.paymentStatus, e.addressSnapshot, e.expiresAt, e.placedAt, e.version == null ? 0 : e.version, e.deliverySlot,
        e.cancelReason, e.stockReserved, e.cancelledBy, e.cancelCode);
  }

  /**
   * Next order number: the shop's order prefix (default {@code VSM}) and the next value of the {@code order_no_seq} sequence.
   */
  public String nextOrderNo() {
    Number n = (Number) em.createNativeQuery("select nextval('order_no_seq')").getSingleResult();
    // The number starts with the prefix from the shop settings, the same one counter bills use.
    String prefix = shop.identity().orderPrefix();
    return (prefix == null || prefix.isBlank() ? "VSM" : prefix) + "-" + n.longValue();
  }

  /** The order with this id, if any. */
  public Optional<Row> find(UUID id) { return orders.findById(id).map(OrderRepository::toRow); }

  /** The customer's order placed with this idempotency key, if any. */
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

  /** Inserts one order line. */
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
    e.couponDiscountPaise = i.couponDiscount();
    e.earnsCoins = i.earns();
    e.coinRedeemable = i.redeemable();
    e.imageUrl = i.imageUrl();
    items.save(e);
  }

  /**
   * Appends a status change to the order's history; {@code from} is {@code null} for the first entry and {@code actor} may be {@code null} for system changes.
   */
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

  /** An order line with what has been returned so far; the lines of a return refer to these by id. */
  public record ItemRow(UUID id, UUID productId, String name, int qty, int returnedQty, long unitPricePaise, long lineTotalPaise,
                        long couponDiscountPaise) {}

  /** The order's lines with ids and returned counts, sorted by name. */
  public List<ItemRow> itemRows(UUID orderId) {
    return items.findByOrderIdOrderByName(orderId).stream().map(i -> new ItemRow(i.id, i.productId, i.name, i.qty, i.returnedQty,
        i.unitPricePaise, i.lineTotalPaise, i.couponDiscountPaise)).toList();
  }

  /** The order's lines as customer-facing items, sorted by name. */
  public List<OrderViews.Item> items(UUID orderId) {
    return items.findByOrderIdOrderByName(orderId).stream()
        .map(i -> new OrderViews.Item(i.productId, i.name, i.qty, i.unitPricePaise, i.lineTotalPaise, i.nameGu,
            i.unitLabel, i.couponDiscountPaise, i.imageUrl)).toList();
  }

  /** The order's status changes, oldest first. */
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

  /** Sets the payment status of the order. */
  @Transactional
  public void setPaymentStatus(UUID id, String status, Instant now) {
    orders.findById(id).ifPresent(e -> {
      e.paymentStatus = status;
      e.updatedAt = now;
    });
  }

  /** Records whether the order currently holds stock (taken when staff confirm, given back on cancel). */
  @Transactional
  public void setStockReserved(UUID id, boolean reserved) { orders.findById(id).ifPresent(e -> e.stockReserved = reserved); }

  /**
   * Stores who cancelled the order, the machine readable reason and the free text (which may be {@code null}).
   * @param by CUSTOMER, SHOP or SYSTEM
   * @param code for example PAYMENT_TIMEOUT
   * @param reason the raw text shown to the customer, without any prefix
   */
  @Transactional
  public void setCancelInfo(UUID id, String by, String code, String reason) {
    orders.findById(id).ifPresent(e -> {
      e.cancelledBy = by;
      e.cancelCode = code;
      e.cancelReason = reason;
    });
  }

  /** The person's latest delivered or picked-up order that contains the product. */
  public Optional<UUID> latestCompletedOrderWith(UUID userId, UUID productId) {
    return em.createQuery("select o.id from OrderEntity o, OrderItemEntity i where i.orderId = o.id and i.productId = :p "
            + "and o.userId = :u and o.status in ('DELIVERED', 'PICKED_UP') order by o.placedAt desc", UUID.class)
        .setParameter("p", productId).setParameter("u", userId).setMaxResults(1).getResultList().stream().findFirst();
  }

  /** How many orders are in each status right now. */
  public Map<String, Long> countByStatus() {
    return em.createQuery("select o.status, count(o) from OrderEntity o group by o.status", Object[].class).getResultList().stream()
        .collect(Collectors.toMap(r -> (String) r[0], r -> ((Number) r[1]).longValue()));
  }

  /** Orders that are still being handled (not delivered, picked up or cancelled). */
  public long openOrderCount(UUID userId) { return orders.countOpen(userId); }

  /** Orders the person has placed that were not cancelled; used for "first order only" coupons. */
  public long activeOrderCount(UUID userId) { return orders.countByUserIdAndStatusNot(userId, "CANCELLED"); }

  /** Records that the earned coins were paid out, so they are never paid twice. */
  @Transactional
  public void markCoinsCredited(UUID id) { orders.findById(id).ifPresent(e -> e.coinsCredited = true); }

  /**
   * Ids of unpaid online orders whose payment deadline is before {@code now}, earliest first, at most {@code limit}.
   */
  public List<UUID> expiredPending(Instant now, int limit) {
    return orders.expiredPending(now, PageRequest.of(0, limit)).stream().map(e -> e.id).toList();
  }

  /** Order numbers for the given ids; ids that do not exist are left out. */
  public Map<UUID, String> numbers(Collection<UUID> ids) {
    if (ids.isEmpty()) return Map.of();
    return orders.numbers(ids).stream().collect(Collectors.toMap(r -> (UUID) r[0], r -> (String) r[1]));
  }

  private Page<SummaryRow> summaries(org.springframework.data.domain.Page<OrderEntity> page, int pageNo, int size) {
    return summaries(new Page<>(page.getContent(), pageNo, size, page.getTotalElements()));
  }

  /** Turns a page of order rows into list rows (with the number of items of each), keeping the page's total, exactness and hasMore. */
  private Page<SummaryRow> summaries(Page<OrderEntity> page) {
    List<UUID> ids = page.items().stream().map(e -> e.id).toList();
    Map<UUID, Integer> counts = ids.isEmpty() ? Map.of() : items.countsFor(ids).stream()
        .collect(Collectors.toMap(r -> (UUID) r[0], r -> ((Number) r[1]).intValue()));
    List<SummaryRow> rows = page.items().stream()
        .map(e -> new SummaryRow(e.id, e.userId, e.orderNo, e.status, e.fulfilment, e.totalPaise,
            counts.getOrDefault(e.id, 0), e.placedAt, e.subtotalPaise, e.couponCode, e.couponDiscountPaise, e.coinsUsed,
            e.coinDiscountPaise, e.deliveryFeePaise, e.paymentMethod, e.paymentStatus, e.cancelledBy, e.cancelCode)).toList();
    return new Page<>(rows, page.page(), page.size(), page.total(), page.totalExact(), page.hasMore());
  }

  /** A customer's orders, newest first. */
  public Page<SummaryRow> list(UUID userId, int page, int size) {
    return summaries(orders.findByUserId(userId, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "placedAt"))), page, size);
  }

  /** All orders, newest first, optionally of one status. */
  public Page<SummaryRow> adminList(String status, int page, int size) { return adminList(status, null, page, size); }

  /**
   * How many orders match, counting at most {@link BoundedCount#CAP} + 1 rows (see {@link BoundedCount}): exact, never cached, and
   * as cheap on 5 million orders as on 5,000. A text search matches the order number or any customer whose name, e-mail or mobile
   * number contains it, decided in SQL (no list of customers is built or capped).
   */
  private long boundedTotal(String status, String like, boolean anyStatus, boolean anyNumber) {
    StringBuilder from = new StringBuilder("orders o where 1 = 1");
    Map<String, Object> params = new HashMap<>();
    if (!anyStatus) {
      from.append(" and o.status = :status");
      params.put("status", status);
    }
    if (!anyNumber) {
      from.append(" and o.id in (select a.id from orders a where lower(a.order_no) like :pattern union select b.id from orders b "
          + "where b.user_id in (select u.id from users u where lower(coalesce(u.name, '')) like :pattern "
          + "or lower(coalesce(u.email, '')) like :pattern or coalesce(u.mobile, '') like :pattern))");
      params.put("pattern", like);
    }
    return BoundedCount.count(em, from.toString(), params);
  }

  /** "1001" becomes "%1001%" in lower case; a % or _ typed by the person is dropped so it cannot act as a wildcard. */
  static String pattern(String q) { return "%" + q.trim().toLowerCase().replace("%", "").replace("_", "") + "%"; }

  /** Orders newest first, optionally of one status and/or whose order number or customer (name, mobile, e-mail) contains q. */
  public Page<SummaryRow> adminList(String status, String q, int page, int size) {
    Window req = Window.of(page, size, Sort.by(Sort.Direction.DESC, "placedAt")); // one extra row tells whether another page follows
    boolean anyStatus = status == null || status.isBlank();
    boolean anyNumber = q == null || q.isBlank();
    String like = anyNumber ? null : pattern(q);
    List<OrderEntity> content = anyStatus
        ? (anyNumber ? orders.findPageBy(req) : orders.listByNumberOrCustomer(like, req))
        : (anyNumber ? orders.findPageByStatus(status, req) : orders.listByStatusAndNumberOrCustomer(status, like, req));
    // the total is exact: no count when the page settles it, otherwise a count that stops at BoundedCount.CAP rows
    return summaries(BoundedCount.assemble(content, page, size, () -> boundedTotal(status, like, anyStatus, anyNumber)));
  }
}
