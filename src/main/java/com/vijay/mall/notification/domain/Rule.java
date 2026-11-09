package com.vijay.mall.notification.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * One condition of a segment. Only the fields that belong to the {@code type} may be set (see {@link ConditionType}); the others
 * stay null. Every field is always present in the JSON so the apps see one stable shape.
 *
 * @param type the condition type name, for example {@code TOTAL_SPENT}
 * @param negate true to select the people who do NOT meet the condition
 * @param op comparison: {@code GTE}, {@code LTE}, {@code EQ} or {@code BETWEEN}
 * @param value a count (orders, coins)
 * @param paise an amount in paise
 * @param minPaise lower amount of a {@code BETWEEN}, in paise
 * @param maxPaise upper amount of a {@code BETWEEN}, in paise
 * @param withinDays only look at the last N days (N times 24 hours back from now)
 * @param notWithinDays no activity in the last N days
 * @param beforeDays older than N days
 * @param days a day count
 * @param olderThanHours age in hours
 * @param productId a product
 * @param categoryId a category
 * @param code a coupon code
 * @param pincodes 6-digit pincodes
 * @param source {@code APP} or {@code POS}
 * @param has yes/no
 */
public record Rule(@Schema(description = "The condition type; see the condition-types catalog", allowableValues = {
    "ORDER_COUNT", "TOTAL_SPENT", "AVG_ORDER_VALUE", "LAST_ORDER", "FIRST_ORDER_WITHIN", "BOUGHT_PRODUCT", "BOUGHT_CATEGORY",
    "USED_COUPON", "REGISTERED", "COIN_BALANCE", "CREDIT_OWED", "CART_ABANDONED", "WISHLIST_HAS", "HAS_REVIEWED", "PINCODE_IN",
    "SOURCE", "HAS_DEVICE"}) String type, Boolean negate, String op, Long value, Long paise, Long minPaise, Long maxPaise, Integer withinDays,
                   Integer notWithinDays, Integer beforeDays, Integer days, Integer olderThanHours, UUID productId, UUID categoryId,
                   String code, List<String> pincodes, String source, Boolean has) {
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** Builds a rule from its JSON-style fields (the name {@code type}, optional {@code negate} and the parameters). */
  public static Rule of(Map<String, ?> fields) { return MAPPER.convertValue(fields, Rule.class); }

  /** The parameters that are set (everything except {@code type} and {@code negate}), by field name, in declaration order. */
  @SuppressWarnings("unchecked")
  public Map<String, Object> params() {
    Map<String, Object> all = new LinkedHashMap<>(MAPPER.convertValue(this, Map.class));
    all.remove("type");
    all.remove("negate");
    all.values().removeIf(v -> v == null);
    return all;
  }
}
