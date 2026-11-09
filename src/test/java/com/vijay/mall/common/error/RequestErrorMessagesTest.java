package com.vijay.mall.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** A mistake in the request becomes a field-level message the app can show under the input. */
class RequestErrorMessagesTest {
  enum Mode { NONE, ALL, PERCENT }
  enum Way { DELIVERY, PICKUP }
  enum Status { PLACED, CONFIRMED, PACKED, DONE }
  enum Only { ONE }

  record Item(UUID productId, int qty) {}

  record Body(Mode coinMode, Way fulfilment, Status status, Only only, long amount, boolean flag, UUID id, Instant at,
              LocalDate day, List<Item> items, Double ratio, String text, Map<String, String> extra, LocalDateTime local) {}

  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private static final JsonMapper STRICT = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).build();
  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  private ErrorBody read(JsonMapper mapper, String json) {
    try {
      mapper.readValue(json, Body.class);
      throw new AssertionError("expected a failure for " + json);
    } catch (tools.jackson.core.JacksonException e) {
      var r = handler.unreadable(new HttpMessageNotReadableException("x", e, new MockHttpInputMessage(json.getBytes())));
      assertThat(r.getStatusCode().value()).isEqualTo(400);
      assertThat(r.getBody().code()).isEqualTo("VALIDATION_FAILED");
      return r.getBody();
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> fields(ErrorBody b) { return (Map<String, Object>) b.details().get("fields"); }

  private void assertField(String json, String field, String message) {
    assertThat(fields(read(MAPPER, json))).isEqualTo(Map.of(field, message));
  }

  @Test
  void enumsListTheirRealValues() {
    assertField("{\"fulfilment\":\"XYZ\"}", "fulfilment", "must be DELIVERY or PICKUP");
    assertField("{\"coinMode\":\"x\"}", "coinMode", "must be NONE, ALL or PERCENT");
    assertField("{\"status\":\"NOPE\"}", "status", "must be one of PLACED, CONFIRMED, PACKED, DONE");
    assertField("{\"only\":\"x\"}", "only", "must be ONE");
  }

  @Test
  void wrongTypesSayWhatIsExpected() {
    assertField("{\"amount\":\"abc\"}", "amount", "must be a whole number");
    assertField("{\"flag\":\"maybe\"}", "flag", "must be true or false");
    assertField("{\"id\":\"abc\"}", "id", "must be a valid id");
    assertField("{\"at\":\"yesterday\"}", "at", "must be a valid date and time");
    assertField("{\"day\":\"tomorrow\"}", "day", "must be a valid date");
    assertField("{\"ratio\":\"x\"}", "ratio", "must be a number");
    assertField("{\"items\":\"x\"}", "items", "must be a list");
    assertField("{\"extra\":\"x\"}", "extra", "must be an object");
    assertField("{\"text\":[1]}", "text", "must be text");
  }

  @Test
  void nestedValuesUseTheJsonPath() {
    assertField("{\"items\":[{\"productId\":\"" + UUID.randomUUID() + "\",\"qty\":\"two\"}]}", "items[0].qty", "must be a whole number");
    assertField("{\"items\":[{\"productId\":\"abc\",\"qty\":1}]}", "items[0].productId", "must be a valid id");
  }

  @Test
  void nullForAPrimitiveIsRequired() {
    assertField("{\"amount\":null}", "amount", "is required");
  }

  @Test
  void missingCreatorPropertyIsRequired() {
    assertThat(fields(read(STRICT, "{\"fulfilment\":\"PICKUP\"}"))).containsEntry("coinMode", "is required");
  }

  @Test
  void brokenOrWrongShapedJsonHasNoFields() {
    for (String json : new String[] {"{\"amount\":", "not json", "[1,2]", "\"abc\""}) {
      ErrorBody b = read(MAPPER, json);
      assertThat(b.message()).isEqualTo("The request body is not valid JSON");
      assertThat(b.details()).isEmpty();
    }
  }

  @Test
  void mustBeCoversEveryKnownType() {
    assertThat(FieldProblems.mustBe(null)).isEqualTo("must be a valid value");
    for (Class<?> c : new Class<?>[] {int.class, Integer.class, long.class, Long.class, short.class, Short.class, byte.class,
        Byte.class, BigInteger.class}) {
      assertThat(FieldProblems.mustBe(c)).isEqualTo("must be a whole number");
    }
    for (Class<?> c : new Class<?>[] {double.class, Double.class, float.class, Float.class, BigDecimal.class}) {
      assertThat(FieldProblems.mustBe(c)).isEqualTo("must be a number");
    }
    for (Class<?> c : new Class<?>[] {boolean.class, Boolean.class}) assertThat(FieldProblems.mustBe(c)).isEqualTo("must be true or false");
    for (Class<?> c : new Class<?>[] {Instant.class, OffsetDateTime.class, ZonedDateTime.class, LocalDateTime.class}) {
      assertThat(FieldProblems.mustBe(c)).isEqualTo("must be a valid date and time");
    }
    assertThat(FieldProblems.mustBe(String[].class)).isEqualTo("must be a list");
    assertThat(FieldProblems.mustBe(Object.class)).isEqualTo("must be a valid Object");
  }

  record Rules(@NotNull UUID a, @NotBlank String b, @Min(0) long c, @Max(9) int d, @Size(max = 2) String e,
               @NotBlank(message = "custom") String f) {}

  @Test
  void ruleMessagesAreFriendly() {
    var validator = new SpringValidatorAdapter(Validation.buildDefaultValidatorFactory().getValidator());
    var errors = new BeanPropertyBindingResult(new Rules(null, null, -5, 12, "abc", null), "rules");
    validator.validate(new Rules(null, null, -5, 12, "abc", null), errors);
    Map<String, String> m = new java.util.HashMap<>();
    errors.getFieldErrors().forEach(f -> m.put(f.getField(), FieldProblems.message(f)));
    assertThat(m).containsEntry("a", "is required").containsEntry("b", "is required").containsEntry("c", "must be 0 or more")
        .containsEntry("d", "must be 9 or less").containsEntry("e", "size must be between 0 and 2").containsEntry("f", "custom");
  }

  @Test
  void blankIsNotMissingAndNoMessageStaysNull() {
    assertThat(FieldProblems.message(new FieldError("o", "x", "", false, null, null, "must not be blank"))).isEqualTo("must not be blank");
    assertThat(FieldProblems.message(new FieldError("o", "x", null, false, null, null, null))).isNull();
  }
}
