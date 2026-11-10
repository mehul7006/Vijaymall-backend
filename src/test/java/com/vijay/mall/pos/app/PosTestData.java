package com.vijay.mall.pos.app;

import com.vijay.mall.common.tax.GstRates;
import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.pos.domain.PosRecords.PaymentRecord;
import com.vijay.mall.pos.domain.PosRecords.SaleItemRecord;
import com.vijay.mall.pos.domain.PosRecords.SaleRecord;
import com.vijay.mall.pos.domain.PosRecords.ShopSetting;
import com.vijay.mall.pos.domain.SoldBy;
import com.vijay.mall.pos.domain.WeighedMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Small builders shared by the POS unit tests. */
final class PosTestData {
  static final Instant NOW = Instant.parse("2026-10-27T05:00:00Z"); // 10:30 IST, financial year 2026-27

  private PosTestData() {}

  static ShopSetting shop() {
    return new ShopSetting("Vijay Super Mall", "Market Road", "9876543210", "24ABCDE1234F1Z5", "24", "shop@upi", "VSM",
        "Thank you!", "2", WeighedMode.WEIGHT_GRAMS);
  }

  static ProductData product(UUID id, String name, long price, boolean active) {
    return new ProductData(id, UUID.randomUUID(), name, name, "", price + 500, price, "pc", "", null, active, false, null, null,
        true, true);
  }

  static SaleItemRecord item(UUID saleItem, UUID product, int qty, long unit, long net, int gst, int returned) {
    return new SaleItemRecord(saleItem, product, "Item", SoldBy.UNIT, qty, unit, unit * qty, unit * qty - net, net, GstRates.of(gst), "", net, 0, 0,
        returned);
  }

  static SaleRecord sale(UUID id, UUID customer, List<SaleItemRecord> items, int coinsEarned, int coinsUsed) {
    long subtotal = items.stream().mapToLong(SaleItemRecord::linePaise).sum();
    long total = items.stream().mapToLong(SaleItemRecord::netPaise).sum();
    return new SaleRecord(id, "VSM/2026-27/000001", UUID.randomUUID(), customer, customer == null ? null : "9000000001", subtotal,
        null, 0, coinsUsed, subtotal - total, total, total, 0, 0, coinsEarned, null, 0, NOW, items,
        List.of(new PaymentRecord("CASH", total, null)));
  }
}
