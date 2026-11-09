package com.vijay.mall.promotion.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What a coupon is for, with names ready to show: {@code ALL} (every item), {@code CATEGORIES}, {@code ITEMS} (products and
 * product groups) or {@code MIXED} (categories and items together). {@code names} is every category, product and product
 * group name in that order, and {@code text} reads like "Dairy, Basmati Rice 5 kg and Dal" for messages.
 */
public record AppliesTo(String type, List<Named> categories, List<Named> products, List<Named> productGroups) {
  /** A category, product or product group by id with its display name and whether it is switched on (always true for a category). */
  public record Named(UUID id, String name, boolean active) {}

  /** Nothing limited: the coupon is for every item. */
  public static final AppliesTo EVERYTHING = new AppliesTo("ALL", List.of(), List.of(), List.of());

  /** Works out the type from which lists are filled. */
  public static AppliesTo of(List<Named> categories, List<Named> products, List<Named> productGroups) {
    boolean cats = !categories.isEmpty();
    boolean items = !products.isEmpty() || !productGroups.isEmpty();
    String type = cats && items ? "MIXED" : cats ? "CATEGORIES" : items ? "ITEMS" : "ALL";
    return new AppliesTo(type, categories, products, productGroups);
  }

  /** Every name: categories first, then products, then product groups. */
  public List<String> names() {
    List<String> out = new ArrayList<>();
    categories.forEach(n -> out.add(n.name()));
    products.forEach(n -> out.add(n.name()));
    productGroups.forEach(n -> out.add(n.name()));
    return out;
  }

  /** The names as a sentence part: "A", "A and B", "A, B and C". Empty for {@code ALL}. */
  public String text() {
    List<String> n = names();
    if (n.isEmpty()) return "";
    if (n.size() == 1) return n.get(0);
    return String.join(", ", n.subList(0, n.size() - 1)) + " and " + n.get(n.size() - 1);
  }
}
