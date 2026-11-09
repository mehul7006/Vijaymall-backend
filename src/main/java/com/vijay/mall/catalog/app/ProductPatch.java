package com.vijay.mall.catalog.app;

import com.vijay.mall.catalog.domain.AdminProduct;
import com.vijay.mall.common.error.ApiException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

/**
 * A partial product edit: only the keys present in the request body change. An explicit JSON {@code null} for
 * {@code hotStart}, {@code hotEnd} or {@code description} clears the value, an absent key keeps it.
 * <p>Stock is never changed here: a {@code qtyOnHand} key is rejected so that stock only moves through the stock endpoint.
 */
public final class ProductPatch {
  private final Map<String, Object> fields;

  /** Wraps the parsed JSON body; {@code qtyOnHand} is rejected with a 400. */
  public ProductPatch(Map<String, Object> fields) {
    if (fields.containsKey("qtyOnHand")) {
      throw ApiException.validation("qtyOnHand", "use the stock endpoint (POST /api/v1/admin/products/{id}/stock)");
    }
    this.fields = fields;
  }

  /** The new variant name, or null when the key is absent (keep the current one). */
  public String variantName() { return fields.containsKey("variantName") ? str("variantName", true) : null; }

  /** The new variant order, or null when the key is absent or null (keep the current one). */
  public Integer variantOrder() {
    Object v = fields.get("variantOrder");
    return v == null ? null : (int) num("variantOrder");
  }

  /** True when the body asks to change the variant name or order. */
  public boolean touchesVariant() { return fields.containsKey("variantName") || fields.containsKey("variantOrder"); }

  /** Merges the present fields over the current product and returns the input for a normal update (stock kept). */
  public CatalogService.ProductInput applyTo(AdminProduct b) {
    return new CatalogService.ProductInput(
        has("categoryId") ? uuid("categoryId") : b.categoryId(),
        has("nameEn") ? required("nameEn") : b.nameEn(),
        has("nameGu") ? required("nameGu") : b.nameGu(),
        has("description") ? str("description", true) : b.description(),
        has("mrpPaise") ? num("mrpPaise") : b.mrpPaise(),
        has("pricePaise") ? num("pricePaise") : b.pricePaise(),
        has("unit") ? str("unit", true) : b.unit(),
        has("packSize") ? str("packSize", true) : b.packSize(),
        has("active") ? bool("active") : b.active(),
        has("hotSale") ? bool("hotSale") : b.hotSale(),
        has("hotStart") ? instant("hotStart") : b.hotStart(),
        has("hotEnd") ? instant("hotEnd") : b.hotEnd(),
        has("earnsCoins") ? bool("earnsCoins") : b.earnsCoins(),
        has("coinRedeemable") ? bool("coinRedeemable") : b.coinRedeemable(),
        -1,
        has("lowThreshold") ? (int) nonNegative("lowThreshold") : -1);
  }

  private boolean has(String key) { return fields.containsKey(key); }

  private String str(String key, boolean nullOk) {
    Object v = fields.get(key);
    if (v == null && nullOk) return null;
    if (v instanceof String s) return s;
    throw ApiException.validation(key, "must be text");
  }

  private String required(String key) {
    String s = str(key, false);
    if (s.isBlank()) throw ApiException.validation(key, "must not be blank");
    return s;
  }

  private long num(String key) {
    if (fields.get(key) instanceof Number n && n.doubleValue() == n.longValue()) return n.longValue();
    throw ApiException.validation(key, "must be a whole number");
  }

  private long nonNegative(String key) {
    long n = num(key);
    if (n < 0 || n > Integer.MAX_VALUE) throw ApiException.validation(key, "must be 0 or more");
    return n;
  }

  private boolean bool(String key) {
    if (fields.get(key) instanceof Boolean b) return b;
    throw ApiException.validation(key, "must be true or false");
  }

  private UUID uuid(String key) {
    try {
      return UUID.fromString(str(key, false));
    } catch (IllegalArgumentException e) {
      throw ApiException.validation(key, "must be an id");
    }
  }

  private Instant instant(String key) {
    String s = str(key, true);
    try {
      return s == null ? null : Instant.parse(s);
    } catch (DateTimeParseException e) {
      throw ApiException.validation(key, "must be a date-time such as 2026-01-31T10:00:00Z");
    }
  }
}
