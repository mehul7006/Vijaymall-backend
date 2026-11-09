package com.vijay.mall.notification.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Every condition an admin may use in a segment. This is the single source of truth: the validator, the SQL builder's switch and
 * the machine-readable catalog the segment-builder screen renders all read these definitions.
 *
 * <p><b>What counts as a purchase</b> (used by every order and spending condition): an online order whose status is not
 * {@code PAYMENT_PENDING} or {@code CANCELLED}, plus a completed counter (POS) sale that was billed to the customer's account.
 * Returns and refunds are not subtracted. "Within N days" means the last N times 24 hours back from the moment the broadcast
 * starts (or the preview runs).
 */
public enum ConditionType {
  ORDER_COUNT("Number of orders", "Customers whose number of orders compares as chosen. Count 0 with EQ means never purchased.", "Spending",
      List.of(Param.choice("op", "Comparison", true, "GTE", "LTE", "EQ"), Param.integer("value", "Number of orders", true, 0, 100_000),
          Param.days("withinDays", "Only count the last N days", false)),
      Map.of("type", "ORDER_COUNT", "op", "EQ", "value", 0), none()),
  TOTAL_SPENT("Total spent", "Customers whose total spending compares as chosen. Use op BETWEEN with minPaise and maxPaise, otherwise paise.",
      "Spending",
      List.of(Param.choice("op", "Comparison", true, "GTE", "LTE", "BETWEEN"), Param.amount("paise", "Amount (paise)", false),
          Param.amount("minPaise", "From amount (paise)", false), Param.amount("maxPaise", "To amount (paise)", false),
          Param.days("withinDays", "Only count the last N days", false)),
      Map.of("type", "TOTAL_SPENT", "op", "GTE", "paise", 500_000), (p, err) -> {
        boolean between = "BETWEEN".equals(p.get("op"));
        if (between) {
          require(p, err, "minPaise");
          require(p, err, "maxPaise");
          forbid(p, err, "paise", "is only used when op is GTE or LTE");
          if (p.get("minPaise") instanceof Number lo && p.get("maxPaise") instanceof Number hi && lo.longValue() > hi.longValue()) {
            err.accept("maxPaise", "must not be less than minPaise");
          }
        } else {
          require(p, err, "paise");
          forbid(p, err, "minPaise", "is only used when op is BETWEEN");
          forbid(p, err, "maxPaise", "is only used when op is BETWEEN");
        }
      }),
  AVG_ORDER_VALUE("Average order value", "Customers whose average order value compares as chosen. Customers with no purchase never match.",
      "Spending", List.of(Param.choice("op", "Comparison", true, "GTE", "LTE"), Param.amount("paise", "Amount (paise)", true)),
      Map.of("type", "AVG_ORDER_VALUE", "op", "GTE", "paise", 100_000), none()),
  LAST_ORDER("Last purchase", "withinDays: bought in the last N days. notWithinDays: has bought before but not in the last N days (lapsed).",
      "Activity", List.of(Param.days("withinDays", "Bought within the last N days", false),
          Param.days("notWithinDays", "Has not bought for N days", false)),
      Map.of("type", "LAST_ORDER", "notWithinDays", 30), exactlyOne("withinDays", "notWithinDays")),
  FIRST_ORDER_WITHIN("New buyers", "Customers whose first ever purchase was in the last N days.", "Activity",
      List.of(Param.days("days", "First purchase within the last N days", true)), Map.of("type", "FIRST_ORDER_WITHIN", "days", 7), none()),
  BOUGHT_PRODUCT("Bought a product", "Customers who bought this product. Turn on negate for people who never bought it.", "Products",
      List.of(Param.product("Product"), Param.days("withinDays", "Only look at the last N days", false)),
      Map.of("type", "BOUGHT_PRODUCT", "productId", "00000000-0000-0000-0000-000000000001"), none()),
  BOUGHT_CATEGORY("Bought from a category", "Customers who bought any product of this category. Turn on negate for people who never did.",
      "Products", List.of(Param.category("Category"), Param.days("withinDays", "Only look at the last N days", false)),
      Map.of("type", "BOUGHT_CATEGORY", "categoryId", "00000000-0000-0000-0000-000000000002"), none()),
  USED_COUPON("Used a coupon", "Customers who used a coupon on a purchase (any coupon, or the given code).", "Products",
      List.of(Param.text("code", "Coupon code (optional)", "[A-Z0-9_-]{1,40}"), Param.days("withinDays", "Only look at the last N days", false)),
      Map.of("type", "USED_COUPON", "code", "WELCOME10"), none()),
  REGISTERED("Registered", "withinDays: joined in the last N days. beforeDays: joined more than N days ago.", "Account",
      List.of(Param.days("withinDays", "Joined within the last N days", false), Param.days("beforeDays", "Joined more than N days ago", false)),
      Map.of("type", "REGISTERED", "withinDays", 7), exactlyOne("withinDays", "beforeDays")),
  COIN_BALANCE("Coin balance", "Customers whose coin balance compares as chosen.", "Account",
      List.of(Param.choice("op", "Comparison", true, "GTE", "LTE", "EQ"), Param.integer("value", "Coins", true, 0, 100_000_000)),
      Map.of("type", "COIN_BALANCE", "op", "GTE", "value", 500), none()),
  CREDIT_OWED("Credit owed (udhar)", "Customers whose outstanding counter credit compares as chosen.", "Account",
      List.of(Param.choice("op", "Comparison", true, "GTE", "LTE", "EQ"), Param.amount("paise", "Amount (paise)", true)),
      Map.of("type", "CREDIT_OWED", "op", "GTE", "paise", 1), none()),
  CART_ABANDONED("Abandoned cart", "Customers with items in their cart that have not been touched for N hours and who did not buy since.",
      "Activity", List.of(Param.integer("olderThanHours", "Cart untouched for (hours)", true, 1, 8760)),
      Map.of("type", "CART_ABANDONED", "olderThanHours", 24), none()),
  WISHLIST_HAS("Has a product in the wishlist", "Customers who saved this product to their wishlist.", "Products",
      List.of(Param.product("Product")), Map.of("type", "WISHLIST_HAS", "productId", "00000000-0000-0000-0000-000000000001"), none()),
  HAS_REVIEWED("Wrote a review", "Customers who wrote at least one product review. Turn on negate for people who never did.", "Activity",
      List.of(), Map.of("type", "HAS_REVIEWED"), none()),
  PINCODE_IN("Lives in a pincode", "Customers with a saved address in one of these pincodes.", "Account",
      List.of(Param.list("pincodes", "Pincodes (6 digits each)", 50, "\\d{6}")), Map.of("type", "PINCODE_IN", "pincodes", List.of("380001")), none()),
  SOURCE("Signed up through", "APP: joined in the app. POS: created at the shop counter and later claimed by the customer.", "Account",
      List.of(Param.choice("source", "Source", true, "APP", "POS")), Map.of("type", "SOURCE", "source", "APP"), none()),
  HAS_DEVICE("Can receive push", "has true: the customer has at least one registered push device. false: none (inbox only).", "Account",
      List.of(Param.flag("has", "Has a push device")), Map.of("type", "HAS_DEVICE", "has", true), none());

  private final String label;
  private final String description;
  private final String group;
  private final List<Param> params;
  private final Map<String, Object> example;
  private final BiConsumer<Map<String, Object>, BiConsumer<String, String>> cross;

  ConditionType(String label, String description, String group, List<Param> params, Map<String, Object> example,
                BiConsumer<Map<String, Object>, BiConsumer<String, String>> cross) {
    this.label = label;
    this.description = description;
    this.group = group;
    this.params = params;
    this.example = example;
    this.cross = cross;
  }

  /** What the segment-builder screen shows for one condition type. */
  public record Entry(String type, String label, String description, String group, List<Param> params, boolean supportsNegate, Rule example) {}

  /** The condition with this exact name, if any. */
  public static Optional<ConditionType> find(String name) {
    return Arrays.stream(values()).filter(t -> t.name().equals(name)).findFirst();
  }

  /** The catalog of all condition types for the segment builder. */
  public static List<Entry> catalog() {
    return Arrays.stream(values()).map(t -> new Entry(t.name(), t.label, t.description, t.group, t.params, true, Rule.of(t.example))).toList();
  }

  /** The parameters this condition accepts. */
  public List<Param> params() { return params; }

  /** Checks the rules that involve several parameters; reports problems as (field, message). */
  void crossCheck(Map<String, Object> given, BiConsumer<String, String> problem) { cross.accept(given, problem); }

  private static BiConsumer<Map<String, Object>, BiConsumer<String, String>> none() { return (p, err) -> { }; }

  private static BiConsumer<Map<String, Object>, BiConsumer<String, String>> exactlyOne(String a, String b) {
    return (p, err) -> {
      if (p.containsKey(a) == p.containsKey(b)) err.accept(p.containsKey(a) ? b : a, "give exactly one of " + a + " and " + b);
    };
  }

  private static void require(Map<String, Object> p, BiConsumer<String, String> err, String name) {
    if (!p.containsKey(name)) err.accept(name, "is required");
  }

  private static void forbid(Map<String, Object> p, BiConsumer<String, String> err, String name, String why) {
    if (p.containsKey(name)) err.accept(name, why);
  }
}
