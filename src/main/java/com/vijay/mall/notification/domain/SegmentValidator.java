package com.vijay.mall.notification.domain;

import com.vijay.mall.common.error.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * Checks a segment against the {@link ConditionType} definitions and returns it cleaned up (upper-case words, trimmed texts, a
 * default {@code match}). Nothing the admin types ever reaches the database as SQL: only whitelisted types with typed parameters pass.
 */
public final class SegmentValidator {
  private SegmentValidator() {}

  /**
   * Validates and normalises a segment.
   *
   * @param in the segment as sent
   * @return the cleaned segment, safe to store and to compile
   * @throws ApiException 400 {@code VALIDATION_FAILED} with every problem under {@code details.fields}, keyed like {@code rules[1].paise}
   */
  public static Segment validate(Segment in) {
    Map<String, Object> fields = new LinkedHashMap<>();
    String match = in == null || in.match() == null ? "ALL" : in.match().strip().toUpperCase();
    if (!match.equals("ALL") && !match.equals("ANY")) fields.put("match", "must be ALL or ANY");
    List<Rule> given = in == null || in.rules() == null ? List.of() : in.rules();
    if (given.isEmpty()) fields.put("rules", "add at least one condition");
    if (given.size() > Segment.MAX_RULES) fields.put("rules", "at most " + Segment.MAX_RULES + " conditions");
    List<Rule> clean = new ArrayList<>();
    for (int i = 0; i < given.size() && i < Segment.MAX_RULES; i++) clean.add(rule(given.get(i), "rules[" + i + "]", fields));
    if (!fields.isEmpty()) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Please check the highlighted fields", Map.of("fields", fields));
    }
    return new Segment(match, clean);
  }

  private static Rule rule(Rule r, String at, Map<String, Object> fields) {
    String name = r == null || r.type() == null ? "" : r.type().strip().toUpperCase();
    var type = ConditionType.find(name);
    if (type.isEmpty()) {
      fields.put(at + ".type", "unknown condition type");
      return r;
    }
    Map<String, Object> given = r.params();
    Map<String, Object> clean = new LinkedHashMap<>();
    clean.put("type", name);
    clean.put("negate", Boolean.TRUE.equals(r.negate()));
    List<String> known = type.get().params().stream().map(Param::name).toList();
    given.keySet().stream().filter(k -> !known.contains(k)).forEach(k -> fields.put(at + "." + k, "is not used by " + name));
    for (Param p : type.get().params()) {
      Object v = given.get(p.name());
      if (v == null) {
        if (p.required()) fields.put(at + "." + p.name(), "is required");
        continue;
      }
      Object cleaned = value(p, v, at + "." + p.name(), fields);
      if (cleaned != null) clean.put(p.name(), cleaned);
    }
    type.get().crossCheck(clean, (field, message) -> fields.put(at + "." + field, message));
    return Rule.of(clean);
  }

  private static Object value(Param p, Object v, String at, Map<String, Object> fields) {
    switch (p.kind()) {
      case INT, AMOUNT_PAISE -> {
        long n = ((Number) v).longValue();
        if (n < p.min() || n > p.max()) {
          String unit = p.kind() == Param.Kind.AMOUNT_PAISE ? " paise" : "";
          fields.put(at, "must be between " + p.min() + " and " + p.max() + unit);
          return null;
        }
        return n;
      }
      case ENUM -> {
        String s = v.toString().strip().toUpperCase();
        if (!p.options().contains(s)) {
          fields.put(at, "must be one of " + String.join(", ", p.options()));
          return null;
        }
        return s;
      }
      case STRING -> {
        String s = v.toString().strip().toUpperCase();
        if (!Pattern.matches(p.pattern(), s)) {
          fields.put(at, "has an invalid format");
          return null;
        }
        return s;
      }
      case STRING_LIST -> {
        List<String> items = ((List<?>) v).stream().map(x -> String.valueOf(x).strip()).distinct().toList();
        if (items.size() < p.min() || items.size() > p.max()) {
          fields.put(at, "give between " + p.min() + " and " + p.max() + " values");
          return null;
        }
        if (!items.stream().allMatch(s -> Pattern.matches(p.pattern(), s))) {
          fields.put(at, "each value must match " + p.pattern());
          return null;
        }
        return items;
      }
      default -> {
        return v;
      }
    }
  }
}
