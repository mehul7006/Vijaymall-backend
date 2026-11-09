package com.vijay.mall.order.app;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.domain.Product;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.order.domain.OrderViews;
import com.vijay.mall.order.infra.OrderRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Order again": checks every line of an earlier order against the catalog and stock as they are right now, so the
 * app does not have to guess from the products it happens to have loaded.
 */
@Service
public class ReorderService {
  /** Reason: the product was removed or is hidden. */
  public static final String UNAVAILABLE = "UNAVAILABLE";
  /** Reason: the product has no stock left. */
  public static final String OUT_OF_STOCK = "OUT_OF_STOCK";
  /** Reason: only part of the requested quantity is in stock. */
  public static final String LOW_STOCK = "LOW_STOCK";

  /**
   * qty is what can be added to the basket now (never more than asked for or in stock). reason is null when the whole
   * quantity is available, LOW_STOCK when only part is, OUT_OF_STOCK or UNAVAILABLE (removed or hidden) when none is.
   * unitPricePaise is today's price; priceChanged tells the app it differs from the earlier order.
   */
  public record Line(UUID productId, String name, String nameGu, String unitLabel, int requestedQty, int qty,
                     long unitPricePaise, boolean priceChanged, boolean available, String reason) {}

  /**
   * Result of the check.
   * @param items one entry per line of the old order
   * @param allAvailable {@code true} when every line can be added in full
   */
  public record Reorder(UUID orderId, String orderNo, List<Line> items, boolean allAvailable) {}

  private final OrderRepository orders;
  private final CatalogService catalog;

  /** Creates the service. */
  public ReorderService(OrderRepository orders, CatalogService catalog) {
    this.orders = orders;
    this.catalog = catalog;
  }

  /**
   * Checks each line of the customer's earlier order against the live catalog and stock; {@code 404} if the order is not theirs.
   */
  @Transactional(readOnly = true)
  public Reorder check(UUID userId, UUID orderId) {
    OrderRepository.Row order = orders.find(orderId).filter(r -> r.userId().equals(userId))
        .orElseThrow(() -> ApiException.notFound("Order"));
    List<OrderViews.Item> items = orders.items(orderId);
    Map<UUID, Product> live = catalog.visible(items.stream().map(OrderViews.Item::productId).distinct().toList()).stream()
        .collect(Collectors.toMap(Product::id, p -> p));
    List<Line> lines = items.stream().map(i -> line(i, live.get(i.productId()))).toList();
    return new Reorder(order.id(), order.orderNo(), lines, lines.stream().allMatch(l -> l.available() && l.reason() == null));
  }

  static Line line(OrderViews.Item item, Product now) {
    if (now == null) {
      return new Line(item.productId(), item.name(), item.nameGu(), item.unitLabel(), item.qty(), 0, item.unitPricePaise(),
          false, false, UNAVAILABLE);
    }
    int available = Math.max(0, now.qtyAvailable());
    int qty = Math.min(item.qty(), available);
    String reason = available == 0 ? OUT_OF_STOCK : qty < item.qty() ? LOW_STOCK : null;
    return new Line(item.productId(), item.name(), item.nameGu(), item.unitLabel(), item.qty(), qty, now.pricePaise(),
        now.pricePaise() != item.unitPricePaise(), qty > 0, reason);
  }
}
