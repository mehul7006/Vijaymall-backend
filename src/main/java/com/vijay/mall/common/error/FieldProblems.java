package com.vijay.mall.common.error;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.validation.FieldError;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidNullException;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * Turns request-reading failures into short, displayable texts: which field is wrong and what it must be, such as
 * {@code fulfilment: must be DELIVERY or PICKUP}. Field names follow the JSON path ({@code items[0].qty}).
 */
final class FieldProblems {
  /** The message shown when the body cannot be read as JSON at all. */
  static final String NOT_JSON = "The request body is not valid JSON";

  /** A field the caller has to send. */
  static final String REQUIRED = "is required";

  /** From this many choices on, the text reads "must be one of A, B, C, ..." instead of "must be A, B or C". */
  private static final int SHORT_LIST = 3;

  private static final Set<String> DEFAULT_REQUIRED = Set.of("must not be null", "must not be blank", "must not be empty");
  private static final String AT_LEAST = "must be greater than or equal to ";
  private static final String AT_MOST = "must be less than or equal to ";
  private static final Pattern CREATOR = Pattern.compile("creator property '([^']+)'");

  private FieldProblems() {}

  /** What the value of the given type has to look like, in a few words after "must be". */
  static String mustBe(Class<?> type) {
    if (type == null) return "must be a valid value";
    if (type.isEnum()) return choices(type.getEnumConstants());
    if (type == boolean.class || type == Boolean.class) return "must be true or false";
    if (isWhole(type)) return "must be a whole number";
    if (type == double.class || type == Double.class || type == float.class || type == Float.class || type == BigDecimal.class) {
      return "must be a number";
    }
    if (type == UUID.class) return "must be a valid id";
    if (type == LocalDate.class) return "must be a valid date";
    if (type == Instant.class || type == OffsetDateTime.class || type == ZonedDateTime.class || type == LocalDateTime.class) {
      return "must be a valid date and time";
    }
    if (type == String.class) return "must be text";
    if (type.isArray() || Collection.class.isAssignableFrom(type)) return "must be a list";
    if (Map.class.isAssignableFrom(type)) return "must be an object";
    return "must be a valid " + type.getSimpleName();
  }

  private static boolean isWhole(Class<?> t) {
    return t == int.class || t == Integer.class || t == long.class || t == Long.class || t == short.class || t == Short.class
        || t == byte.class || t == Byte.class || t == BigInteger.class;
  }

  /** The allowed values of an enum: "must be A or B", "must be A, B or C", or "must be one of A, B, C, D" for a longer list. */
  static String choices(Object[] constants) {
    List<String> names = Arrays.stream(constants).map(c -> ((Enum<?>) c).name()).toList();
    if (names.size() > SHORT_LIST) return "must be one of " + String.join(", ", names);
    String last = names.getLast();
    if (names.size() == 1) return "must be " + last;
    return "must be " + String.join(", ", names.subList(0, names.size() - 1)) + " or " + last;
  }

  /** The field a failure belongs to: its JSON path, or for a missing creator property the property name; empty when none. */
  static String field(JacksonException e) {
    String path = path(e);
    if (!path.isEmpty()) return path;
    Matcher m = CREATOR.matcher(String.valueOf(e.getOriginalMessage()));
    return m.find() ? m.group(1) : "";
  }

  private static String path(JacksonException e) {
    StringBuilder sb = new StringBuilder();
    for (JacksonException.Reference r : e.getPath()) {
      if (r.getPropertyName() != null) {
        if (!sb.isEmpty()) sb.append('.');
        sb.append(r.getPropertyName());
      } else if (r.getIndex() >= 0) {
        sb.append('[').append(r.getIndex()).append(']');
      }
    }
    return sb.toString();
  }

  /**
   * The text for a failed bean-validation rule. A required value that was left out reads "is required"; the default number
   * limits read "must be 0 or more" / "must be 99 or less"; every other rule, including custom messages, is kept as written.
   */
  static String message(FieldError f) {
    String m = f.getDefaultMessage();
    if (m == null) return null;
    if (f.getRejectedValue() == null && DEFAULT_REQUIRED.contains(m)) return REQUIRED;
    if (m.startsWith(AT_LEAST)) return "must be " + m.substring(AT_LEAST.length()) + " or more";
    if (m.startsWith(AT_MOST)) return "must be " + m.substring(AT_MOST.length()) + " or less";
    return m;
  }

  /** The first Jackson failure in the cause chain, or null when the body failure came from somewhere else (an empty body). */
  static JacksonException jackson(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof JacksonException j) return j;
    }
    return null;
  }

  /** The text for a field whose value Jackson could not use: required when it was null, else what it must be. */
  static String describe(MismatchedInputException e) {
    if (e instanceof InvalidNullException || isNullOrMissing(e)) return REQUIRED;
    return mustBe(e.getTargetType());
  }

  private static boolean isNullOrMissing(MismatchedInputException e) {
    String m = e.getOriginalMessage();
    return m != null && ((m.startsWith("Missing ") && m.contains("creator property")) || m.contains("`null`"));
  }

  /** True when no single field can be blamed: the text is not JSON at all, is cut off, or has the wrong shape as a whole. */
  static boolean unreadable(JacksonException j) {
    return !(j instanceof MismatchedInputException) || field(j).isEmpty();
  }
}
