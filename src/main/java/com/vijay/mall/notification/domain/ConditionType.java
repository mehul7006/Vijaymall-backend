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
  ORDER_COUNT("Number of orders",
      "ઓર્ડરની સંખ્યા", "Customers whose number of orders compares as chosen. Count 0 with EQ means never purchased.",
      "જેમના ઓર્ડરની સંખ્યા પસંદ કરેલી શરત મુજબ હોય તેવા ગ્રાહકો. સંખ્યા 0 સાથે 'બરાબર' એટલે જેમણે ક્યારેય ખરીદી કરી નથી.", "Spending",
      List.of(Param.choice("op", "Comparison", "સરખામણી", true, "GTE", "LTE", "EQ"), Param.integer("value", "Number of orders", "ઓર્ડરની સંખ્યા", true, 0, 100_000),
          Param.days("withinDays", "Only count the last N days", "ફક્ત છેલ્લા કેટલા દિવસ ગણવા", false)),
      Map.of("type", "ORDER_COUNT", "op", "EQ", "value", 0), none()),
  TOTAL_SPENT("Total spent",
      "કુલ ખર્ચ", "Customers whose total spending compares as chosen. Use op BETWEEN with minPaise and maxPaise, otherwise paise.",
      "જેમનો કુલ ખર્ચ પસંદ કરેલી શરત મુજબ હોય તેવા ગ્રાહકો. 'વચ્ચે' માટે ઓછામાં ઓછી અને વધુમાં વધુ રકમ આપો, નહીંતર એક રકમ આપો.",
      "Spending",
      List.of(Param.choice("op", "Comparison", "સરખામણી", true, "GTE", "LTE", "BETWEEN"), Param.amount("paise", "Amount (Rs)", "રકમ (રૂપિયા)", false),
          Param.amount("minPaise", "From amount (Rs)", "આટલી રકમથી (રૂપિયા)", false), Param.amount("maxPaise", "To amount (Rs)", "આટલી રકમ સુધી (રૂપિયા)", false),
          Param.days("withinDays", "Only count the last N days", "ફક્ત છેલ્લા કેટલા દિવસ ગણવા", false)),
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
  AVG_ORDER_VALUE("Average order value",
      "સરેરાશ ઓર્ડરની રકમ", "Customers whose average order value compares as chosen. Customers with no purchase never match.",
      "જેમના ઓર્ડરની સરેરાશ રકમ પસંદ કરેલી શરત મુજબ હોય તેવા ગ્રાહકો. જેમણે ખરીદી કરી નથી તેઓ આમાં આવતા નથી.",
      "Spending", List.of(Param.choice("op", "Comparison", "સરખામણી", true, "GTE", "LTE"), Param.amount("paise", "Amount (Rs)", "રકમ (રૂપિયા)", true)),
      Map.of("type", "AVG_ORDER_VALUE", "op", "GTE", "paise", 100_000), none()),
  LAST_ORDER("Last purchase",
      "છેલ્લી ખરીદી", "withinDays: bought in the last N days. notWithinDays: has bought before but not in the last N days (lapsed).",
      "છેલ્લા N દિવસમાં ખરીદી કરી હોય તેવા, અથવા પહેલાં ખરીદી કરી હોય પણ છેલ્લા N દિવસમાં ન કરી હોય તેવા (લાંબા સમયથી ન આવેલા) ગ્રાહકો.",
      "Activity", List.of(Param.days("withinDays", "Bought within the last N days", "છેલ્લા કેટલા દિવસમાં ખરીદી કરી", false),
          Param.days("notWithinDays", "Has not bought for N days", "કેટલા દિવસથી ખરીદી કરી નથી", false)),
      Map.of("type", "LAST_ORDER", "notWithinDays", 30), exactlyOne("withinDays", "notWithinDays")),
  FIRST_ORDER_WITHIN("New buyers",
      "નવા ખરીદદારો", "Customers whose first ever purchase was in the last N days.",
      "જેમની પહેલી ખરીદી છેલ્લા N દિવસમાં થઈ હોય તેવા ગ્રાહકો.", "Activity",
      List.of(Param.days("days", "First purchase within the last N days", "પહેલી ખરીદી છેલ્લા કેટલા દિવસમાં", true)), Map.of("type", "FIRST_ORDER_WITHIN", "days", 7), none()),
  BOUGHT_PRODUCT("Bought a product",
      "એક વસ્તુ ખરીદી", "Customers who bought this product. Turn on negate for people who never bought it.",
      "આ વસ્તુ ખરીદનાર ગ્રાહકો. જેમણે ક્યારેય ખરીદી નથી તેમને પસંદ કરવા 'ઊલટું' ચાલુ કરો.", "Products",
      List.of(Param.product("Product", "વસ્તુ"), Param.days("withinDays", "Only look at the last N days", "ફક્ત છેલ્લા કેટલા દિવસ જોવા", false)),
      Map.of("type", "BOUGHT_PRODUCT", "productId", "00000000-0000-0000-0000-000000000001"), none()),
  BOUGHT_CATEGORY("Bought from a category",
      "વિભાગમાંથી ખરીદી", "Customers who bought any product of this category. Turn on negate for people who never did.",
      "આ વિભાગની કોઈ પણ વસ્તુ ખરીદનાર ગ્રાહકો. જેમણે ક્યારેય ખરીદી નથી તેમને પસંદ કરવા 'ઊલટું' ચાલુ કરો.",
      "Products", List.of(Param.category("Category", "વિભાગ"), Param.days("withinDays", "Only look at the last N days", "ફક્ત છેલ્લા કેટલા દિવસ જોવા", false)),
      Map.of("type", "BOUGHT_CATEGORY", "categoryId", "00000000-0000-0000-0000-000000000002"), none()),
  USED_COUPON("Used a coupon",
      "કૂપન વાપર્યો", "Customers who used a coupon on a purchase (any coupon, or the given code).",
      "ખરીદીમાં કૂપન (કોઈ પણ અથવા આપેલો કોડ) વાપરનાર ગ્રાહકો.", "Products",
      List.of(Param.text("code", "Coupon code (optional)", "કૂપન કોડ (જરૂરી નથી)", "[A-Z0-9_-]{1,40}"), Param.days("withinDays", "Only look at the last N days", "ફક્ત છેલ્લા કેટલા દિવસ જોવા", false)),
      Map.of("type", "USED_COUPON", "code", "WELCOME10"), none()),
  REGISTERED("Registered",
      "નોંધણી", "withinDays: joined in the last N days. beforeDays: joined more than N days ago.",
      "છેલ્લા N દિવસમાં જોડાયેલા, અથવા N દિવસ કરતાં પહેલાં જોડાયેલા ગ્રાહકો.", "Account",
      List.of(Param.days("withinDays", "Joined within the last N days", "છેલ્લા કેટલા દિવસમાં જોડાયા", false), Param.days("beforeDays", "Joined more than N days ago", "કેટલા દિવસ પહેલાં જોડાયા", false)),
      Map.of("type", "REGISTERED", "withinDays", 7), exactlyOne("withinDays", "beforeDays")),
  COIN_BALANCE("Coin balance",
      "સિક્કાની બાકી", "Customers whose coin balance compares as chosen.",
      "જેમના સિક્કાની બાકી પસંદ કરેલી શરત મુજબ હોય તેવા ગ્રાહકો.", "Account",
      List.of(Param.choice("op", "Comparison", "સરખામણી", true, "GTE", "LTE", "EQ"), Param.integer("value", "Coins", "સિક્કા", true, 0, 100_000_000)),
      Map.of("type", "COIN_BALANCE", "op", "GTE", "value", 500), none()),
  CREDIT_OWED("Credit owed (udhar)",
      "ઉધાર બાકી", "Customers whose outstanding counter credit compares as chosen.",
      "કાઉન્ટર પર જેમનું ઉધાર બાકી રકમ પસંદ કરેલી શરત મુજબ હોય તેવા ગ્રાહકો.", "Account",
      List.of(Param.choice("op", "Comparison", "સરખામણી", true, "GTE", "LTE", "EQ"), Param.amount("paise", "Amount (Rs)", "રકમ (રૂપિયા)", true)),
      Map.of("type", "CREDIT_OWED", "op", "GTE", "paise", 1), none()),
  CART_ABANDONED("Abandoned cart",
      "કાર્ટમાં છોડી દીધેલો માલ", "Customers with items in their cart that have not been touched for N hours and who did not buy since.",
      "જેમના કાર્ટમાં વસ્તુઓ N કલાકથી પડી છે અને ત્યાર પછી ખરીદી કરી નથી તેવા ગ્રાહકો.",
      "Activity", List.of(Param.integer("olderThanHours", "Cart untouched for (hours)", "કાર્ટ કેટલા કલાકથી પડ્યું છે", true, 1, 8760)),
      Map.of("type", "CART_ABANDONED", "olderThanHours", 24), none()),
  WISHLIST_HAS("Has a product in the wishlist",
      "વિશલિસ્ટમાં વસ્તુ", "Customers who saved this product to their wishlist.",
      "આ વસ્તુ વિશલિસ્ટમાં સાચવનાર ગ્રાહકો.", "Products",
      List.of(Param.product("Product", "વસ્તુ")), Map.of("type", "WISHLIST_HAS", "productId", "00000000-0000-0000-0000-000000000001"), none()),
  HAS_REVIEWED("Wrote a review",
      "રિવ્યૂ લખ્યો", "Customers who wrote at least one product review. Turn on negate for people who never did.",
      "ઓછામાં ઓછો એક વસ્તુનો રિવ્યૂ લખનાર ગ્રાહકો. જેમણે ક્યારેય લખ્યો નથી તેમને પસંદ કરવા 'ઊલટું' ચાલુ કરો.", "Activity",
      List.of(), Map.of("type", "HAS_REVIEWED"), none()),
  PINCODE_IN("Lives in a pincode",
      "પિનકોડમાં રહે છે", "Customers with a saved address in one of these pincodes.",
      "આમાંથી કોઈ પિનકોડનું સરનામું સાચવનાર ગ્રાહકો.", "Account",
      List.of(Param.list("pincodes", "Pincodes (6 digits each)", "પિનકોડ (દરેક 6 અંકનો)", 50, "\\d{6}")), Map.of("type", "PINCODE_IN", "pincodes", List.of("380001")), none()),
  SOURCE("Signed up through",
      "કેવી રીતે જોડાયા", "APP: joined in the app. POS: created at the shop counter and later claimed by the customer.",
      "એપ: ગ્રાહક એપમાં જોડાયા. દુકાનનું કાઉન્ટર: દુકાને કાઉન્ટર પર બનાવેલા, પછી ગ્રાહકે પોતે અપનાવ્યા.", "Account",
      List.of(Param.choice("source", "Source", "ક્યાંથી જોડાયા", true, "APP", "POS")), Map.of("type", "SOURCE", "source", "APP"), none()),
  HAS_DEVICE("Can receive push",
      "પુશ મેળવી શકે છે", "has true: the customer has at least one registered push device. false: none (inbox only).",
      "હા: ગ્રાહકનો ઓછામાં ઓછો એક ફોન નોંધાયેલો છે. ના: એકેય નથી (ફક્ત ઇનબોક્સ).", "Account",
      List.of(Param.flag("has", "Has a push device", "ફોન નોંધાયેલો છે")), Map.of("type", "HAS_DEVICE", "has", true), none());

  private final String label;
  private final String labelGu;
  private final String description;
  private final String descriptionGu;
  private final String group;
  private final List<Param> params;
  private final Map<String, Object> example;
  private final BiConsumer<Map<String, Object>, BiConsumer<String, String>> cross;

  ConditionType(String label, String labelGu, String description, String descriptionGu, String group, List<Param> params,
                Map<String, Object> example, BiConsumer<Map<String, Object>, BiConsumer<String, String>> cross) {
    this.label = label;
    this.labelGu = labelGu;
    this.description = description;
    this.descriptionGu = descriptionGu;
    this.group = group;
    this.params = params;
    this.example = example;
    this.cross = cross;
  }

  /**
   * What the segment-builder screen shows for one condition type. {@code labelGu} and {@code descriptionGu} are the Gujarati texts (the
   * screen falls back to the English ones when they are blank).
   */
  public record Entry(String type, String label, String description, String group, List<Param> params, boolean supportsNegate, Rule example,
                      String labelGu, String descriptionGu) {}

  /** The condition with this exact name, if any. */
  public static Optional<ConditionType> find(String name) {
    return Arrays.stream(values()).filter(t -> t.name().equals(name)).findFirst();
  }

  /** The catalog of all condition types for the segment builder. */
  public static List<Entry> catalog() {
    return Arrays.stream(values()).map(t -> new Entry(t.name(), t.label, t.description, t.group, t.params, true, Rule.of(t.example), t.labelGu,
        t.descriptionGu)).toList();
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
