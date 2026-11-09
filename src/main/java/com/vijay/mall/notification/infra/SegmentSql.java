package com.vijay.mall.notification.infra;

import com.vijay.mall.notification.domain.Rule;
import com.vijay.mall.notification.domain.Segment;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a validated {@link Segment} into one parameterised SQL condition over {@code users u}. The text only ever contains fixed
 * fragments chosen by the (whitelisted) condition type; every value the admin supplied is a bound parameter. A purchase is an
 * online order that is not {@code PAYMENT_PENDING} or {@code CANCELLED}, or a completed counter sale billed to the customer.
 */
final class SegmentSql {
  /** The compiled condition and its named parameters. */
  record Compiled(String where, Map<String, Object> params) {}

  private static final String PURCHASES =
      "(select o.user_id as user_id, o.placed_at as at, o.total_paise as total from orders o "
          + "where o.status not in ('PAYMENT_PENDING', 'CANCELLED') "
          + "union all select s.customer_id, s.created_at, s.total_paise from pos_sale s "
          + "where s.customer_id is not null and s.status = 'COMPLETED')";

  private SegmentSql() {}

  /** Compiles the segment; relative times ("last N days") are measured back from {@code now}. */
  static Compiled compile(Segment segment, Instant now) {
    Map<String, Object> params = new LinkedHashMap<>();
    List<String> parts = new ArrayList<>();
    for (int i = 0; i < segment.rules().size(); i++) {
      Rule r = segment.rules().get(i);
      String sql = "coalesce((" + condition(r, "r" + i, now, params) + "), false)";
      parts.add(Boolean.TRUE.equals(r.negate()) ? "not " + sql : sql);
    }
    return new Compiled("(" + String.join(segment.matchAll() ? " and " : " or ", parts) + ")", params);
  }

  private static String condition(Rule r, String k, Instant now, Map<String, Object> params) {
    return switch (r.type()) {
      case "ORDER_COUNT" -> "(select count(*) from " + PURCHASES + " p where p.user_id = u.id" + since(r.withinDays(), "p.at", k, now, params) + ") "
          + op(r.op()) + " :" + bind(params, k + "v", r.value());
      case "TOTAL_SPENT" -> "(select coalesce(sum(p.total), 0) from " + PURCHASES + " p where p.user_id = u.id"
          + since(r.withinDays(), "p.at", k, now, params) + ") " + spent(r, k, params);
      case "AVG_ORDER_VALUE" -> "(select avg(p.total) from " + PURCHASES + " p where p.user_id = u.id) " + op(r.op())
          + " :" + bind(params, k + "p", r.paise());
      case "LAST_ORDER" -> r.withinDays() != null
          ? "exists (select 1 from " + PURCHASES + " p where p.user_id = u.id" + since(r.withinDays(), "p.at", k, now, params) + ")"
          : "exists (select 1 from " + PURCHASES + " p where p.user_id = u.id) and not exists (select 1 from " + PURCHASES
              + " p where p.user_id = u.id" + since(r.notWithinDays(), "p.at", k, now, params) + ")";
      case "FIRST_ORDER_WITHIN" -> "(select min(p.at) from " + PURCHASES + " p where p.user_id = u.id) >= :"
          + bind(params, k + "c", cutoff(now, r.days()));
      case "BOUGHT_PRODUCT" -> bought("i.product_id = :" + bind(params, k + "p", r.productId()), r.withinDays(), k, now, params);
      case "BOUGHT_CATEGORY" -> bought("i.product_id in (select pr.id from product pr where pr.category_id = :"
          + bind(params, k + "g", r.categoryId()) + ")", r.withinDays(), k, now, params);
      case "USED_COUPON" -> "exists (select 1 from orders o where o.user_id = u.id and o.status not in ('PAYMENT_PENDING', 'CANCELLED') "
          + "and o.coupon_code is not null" + coupon(r, "o.coupon_code", k, params) + since(r.withinDays(), "o.placed_at", k, now, params) + ") "
          + "or exists (select 1 from pos_sale s where s.customer_id = u.id and s.status = 'COMPLETED' and s.coupon_code is not null"
          + coupon(r, "s.coupon_code", k, params) + since(r.withinDays(), "s.created_at", k, now, params) + ")";
      case "REGISTERED" -> r.withinDays() != null
          ? "u.created_at >= :" + bind(params, k + "c", cutoff(now, r.withinDays()))
          : "u.created_at < :" + bind(params, k + "c", cutoff(now, r.beforeDays()));
      case "COIN_BALANCE" -> "coalesce((select w.coins from wallet_balance w where w.user_id = u.id), 0) " + op(r.op())
          + " :" + bind(params, k + "v", r.value());
      case "CREDIT_OWED" -> "coalesce((select a.balance_paise from credit_account a where a.user_id = u.id), 0) " + op(r.op())
          + " :" + bind(params, k + "p", r.paise());
      case "CART_ABANDONED" -> "(select max(ci.updated_at) from cart_item ci where ci.user_id = u.id) < :"
          + bind(params, k + "c", Timestamp.from(now.minus(Duration.ofHours(r.olderThanHours()))))
          + " and not exists (select 1 from " + PURCHASES + " p where p.user_id = u.id and p.at >= "
          + "(select max(ci2.updated_at) from cart_item ci2 where ci2.user_id = u.id))";
      case "WISHLIST_HAS" -> "exists (select 1 from wishlist_item w where w.user_id = u.id and w.product_id = :" + bind(params, k + "p", r.productId()) + ")";
      case "HAS_REVIEWED" -> "exists (select 1 from product_review v where v.user_id = u.id)";
      case "PINCODE_IN" -> "exists (select 1 from address a where a.user_id = u.id and a.pincode in (:" + bind(params, k + "pins", r.pincodes()) + "))";
      case "SOURCE" -> "u.source = :" + bind(params, k + "s", r.source());
      case "HAS_DEVICE" -> (r.has() ? "" : "not ") + "exists (select 1 from device_token d where d.user_id = u.id)";
      default -> throw new IllegalArgumentException("unknown condition type " + r.type());
    };
  }

  private static String bought(String productTest, Integer withinDays, String k, Instant now, Map<String, Object> params) {
    return "exists (select 1 from order_item i join orders o on o.id = i.order_id where o.user_id = u.id "
        + "and o.status not in ('PAYMENT_PENDING', 'CANCELLED') and " + productTest + since(withinDays, "o.placed_at", k, now, params) + ") "
        + "or exists (select 1 from pos_sale_item i join pos_sale s on s.id = i.sale_id where s.customer_id = u.id "
        + "and s.status = 'COMPLETED' and " + productTest + since(withinDays, "s.created_at", k, now, params) + ")";
  }

  private static String coupon(Rule r, String column, String k, Map<String, Object> params) {
    return r.code() == null ? "" : " and upper(" + column + ") = :" + bind(params, k + "code", r.code());
  }

  private static String spent(Rule r, String k, Map<String, Object> params) {
    if ("BETWEEN".equals(r.op())) {
      return "between :" + bind(params, k + "lo", r.minPaise()) + " and :" + bind(params, k + "hi", r.maxPaise());
    }
    return op(r.op()) + " :" + bind(params, k + "p", r.paise());
  }

  private static String since(Integer days, String column, String k, Instant now, Map<String, Object> params) {
    return days == null ? "" : " and " + column + " >= :" + bind(params, k + "since", cutoff(now, days));
  }

  private static Timestamp cutoff(Instant now, int days) { return Timestamp.from(now.minus(Duration.ofDays(days))); }

  private static String op(String op) {
    return switch (op) {
      case "GTE" -> ">=";
      case "LTE" -> "<=";
      default -> "=";
    };
  }

  private static String bind(Map<String, Object> params, String name, Object value) {
    params.put(name, value);
    return name;
  }
}
