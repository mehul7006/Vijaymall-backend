package com.vijay.mall.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.notification.domain.ConditionType;
import com.vijay.mall.notification.domain.Param;
import com.vijay.mall.notification.domain.Rule;
import com.vijay.mall.notification.domain.Segment;
import com.vijay.mall.notification.domain.SegmentValidator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SegmentValidatorTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  static Rule rule(String json) { return JSON.readValue(json, Rule.class); }

  static Segment all(String... rules) { return new Segment("ALL", Arrays.stream(rules).map(SegmentValidatorTest::rule).toList()); }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> fieldsOf(Segment s) {
    try {
      SegmentValidator.validate(s);
    } catch (ApiException e) {
      assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
      return (Map<String, Object>) e.details().get("fields");
    }
    throw new AssertionError("expected a validation error");
  }

  @Test
  void aGoodSegmentIsCleanedUp() {
    Segment s = SegmentValidator.validate(new Segment(" any ", List.of(
        rule("{\"type\":\"order_count\",\"op\":\"gte\",\"value\":3}"),
        rule("{\"type\":\"PINCODE_IN\",\"pincodes\":[\" 380001 \",\"380001\",\"380002\"],\"negate\":true}"),
        rule("{\"type\":\"USED_COUPON\",\"code\":\" welcome10 \"}"))));
    assertThat(s.match()).isEqualTo("ANY");
    assertThat(s.rules().get(0).type()).isEqualTo("ORDER_COUNT");
    assertThat(s.rules().get(0).op()).isEqualTo("GTE");
    assertThat(s.rules().get(0).negate()).isFalse();
    assertThat(s.rules().get(1).pincodes()).containsExactly("380001", "380002");
    assertThat(s.rules().get(1).negate()).isTrue();
    assertThat(s.rules().get(2).code()).isEqualTo("WELCOME10");
  }

  @Test
  void matchDefaultsToAll() {
    assertThat(SegmentValidator.validate(new Segment(null, List.of(rule("{\"type\":\"HAS_REVIEWED\"}")))).match()).isEqualTo("ALL");
  }

  @Test
  void badSegmentShapes() {
    assertThat(fieldsOf(null)).containsKey("rules");
    assertThat(fieldsOf(new Segment("ALL", null))).containsKey("rules");
    assertThat(fieldsOf(new Segment("ALL", List.of()))).containsEntry("rules", "add at least one condition");
    assertThat(fieldsOf(new Segment("SOME", List.of(rule("{\"type\":\"HAS_REVIEWED\"}"))))).containsKey("match");
    List<Rule> eleven = new ArrayList<>();
    for (int i = 0; i < 11; i++) eleven.add(rule("{\"type\":\"HAS_REVIEWED\"}"));
    assertThat(fieldsOf(new Segment("ALL", eleven))).containsEntry("rules", "at most 10 conditions");
    assertThat(fieldsOf(new Segment("ALL", Collections.singletonList(null)))).containsKey("rules[0].type");
  }

  @Test
  void unknownTypeAndStrayParameters() {
    assertThat(fieldsOf(all("{\"type\":\"DROP_TABLE\"}"))).containsEntry("rules[0].type", "unknown condition type");
    assertThat(fieldsOf(all("{}"))).containsKey("rules[0].type");
    assertThat(fieldsOf(all("{\"type\":\"HAS_REVIEWED\",\"value\":3}"))).containsEntry("rules[0].value", "is not used by HAS_REVIEWED");
  }

  @Test
  void requiredParametersAndRanges() {
    assertThat(fieldsOf(all("{\"type\":\"ORDER_COUNT\"}"))).containsKeys("rules[0].op", "rules[0].value");
    assertThat(fieldsOf(all("{\"type\":\"ORDER_COUNT\",\"op\":\"GT\",\"value\":1}"))).containsKey("rules[0].op");
    assertThat(fieldsOf(all("{\"type\":\"ORDER_COUNT\",\"op\":\"GTE\",\"value\":-1}"))).containsKey("rules[0].value");
    assertThat(fieldsOf(all("{\"type\":\"ORDER_COUNT\",\"op\":\"GTE\",\"value\":1,\"withinDays\":0}"))).containsKey("rules[0].withinDays");
    assertThat(fieldsOf(all("{\"type\":\"ORDER_COUNT\",\"op\":\"GTE\",\"value\":1,\"withinDays\":99999}"))).containsKey("rules[0].withinDays");
    assertThat(fieldsOf(all("{\"type\":\"CART_ABANDONED\",\"olderThanHours\":0}"))).containsKey("rules[0].olderThanHours");
    assertThat(fieldsOf(all("{\"type\":\"HAS_DEVICE\"}"))).containsKey("rules[0].has");
    assertThat(fieldsOf(all("{\"type\":\"SOURCE\",\"source\":\"WEB\"}"))).containsKey("rules[0].source");
    assertThat(fieldsOf(all("{\"type\":\"USED_COUPON\",\"code\":\"bad code!\"}"))).containsEntry("rules[0].code", "has an invalid format");
  }

  @Test
  void pincodeLists() {
    assertThat(fieldsOf(all("{\"type\":\"PINCODE_IN\",\"pincodes\":[]}"))).containsKey("rules[0].pincodes");
    assertThat(fieldsOf(all("{\"type\":\"PINCODE_IN\",\"pincodes\":[\"12\"]}"))).containsKey("rules[0].pincodes");
    String many = IntStream.range(0, 51).mapToObj(i -> "\"" + (100000 + i) + "\"").collect(Collectors.joining(","));
    assertThat(fieldsOf(all("{\"type\":\"PINCODE_IN\",\"pincodes\":[" + many + "]}"))).containsKey("rules[0].pincodes");
  }

  @Test
  void spendingRangesNeedTheRightParameters() {
    assertThat(fieldsOf(all("{\"type\":\"TOTAL_SPENT\",\"op\":\"GTE\"}"))).containsEntry("rules[0].paise", "is required");
    assertThat(fieldsOf(all("{\"type\":\"TOTAL_SPENT\",\"op\":\"GTE\",\"paise\":1,\"minPaise\":1,\"maxPaise\":2}")))
        .containsKeys("rules[0].minPaise", "rules[0].maxPaise");
    assertThat(fieldsOf(all("{\"type\":\"TOTAL_SPENT\",\"op\":\"BETWEEN\"}"))).containsKeys("rules[0].minPaise", "rules[0].maxPaise");
    assertThat(fieldsOf(all("{\"type\":\"TOTAL_SPENT\",\"op\":\"BETWEEN\",\"minPaise\":500,\"maxPaise\":100}")))
        .containsEntry("rules[0].maxPaise", "must not be less than minPaise");
    assertThat(fieldsOf(all("{\"type\":\"TOTAL_SPENT\",\"op\":\"BETWEEN\",\"minPaise\":1,\"maxPaise\":2,\"paise\":3}"))).containsKey("rules[0].paise");
    assertThat(SegmentValidator.validate(all("{\"type\":\"TOTAL_SPENT\",\"op\":\"between\",\"minPaise\":1,\"maxPaise\":2}")).rules()).hasSize(1);
  }

  @Test
  void exactlyOneOfTwoAlternatives() {
    assertThat(fieldsOf(all("{\"type\":\"LAST_ORDER\"}"))).containsKey("rules[0].withinDays");
    assertThat(fieldsOf(all("{\"type\":\"LAST_ORDER\",\"withinDays\":3,\"notWithinDays\":4}"))).containsKey("rules[0].notWithinDays");
    assertThat(fieldsOf(all("{\"type\":\"REGISTERED\",\"withinDays\":3,\"beforeDays\":4}"))).containsKey("rules[0].beforeDays");
    assertThat(SegmentValidator.validate(all("{\"type\":\"REGISTERED\",\"beforeDays\":4}")).rules()).hasSize(1);
  }

  @Test
  void everyProblemIsReportedAtOnce() {
    assertThat(fieldsOf(all("{\"type\":\"ORDER_COUNT\"}", "{\"type\":\"NOPE\"}"))).containsKeys("rules[0].op", "rules[0].value", "rules[1].type");
  }

  @Test
  void theCatalogAndTheValidatorDescribeTheSameTypes() {
    Set<String> catalog = ConditionType.catalog().stream().map(ConditionType.Entry::type).collect(Collectors.toSet());
    Set<String> defined = Arrays.stream(ConditionType.values()).map(Enum::name).collect(Collectors.toSet());
    assertThat(catalog).isEqualTo(defined);
    Set<String> ruleFields = Arrays.stream(Rule.class.getRecordComponents()).map(c -> c.getName()).collect(Collectors.toSet());
    for (ConditionType.Entry e : ConditionType.catalog()) {
      assertThat(e.supportsNegate()).isTrue();
      assertThat(e.label()).isNotBlank();
      assertThat(e.description()).isNotBlank();
      assertThat(e.group()).isIn("Spending", "Products", "Activity", "Account");
      for (Param p : e.params()) {
        assertThat(ruleFields).as(e.type() + "." + p.name()).contains(p.name());
        if (p.kind() == Param.Kind.PRODUCT_ID || p.kind() == Param.Kind.CATEGORY_ID) assertThat(p.pickFrom()).startsWith("GET /api/v1/");
        if (p.kind() == Param.Kind.ENUM) assertThat(p.options()).isNotEmpty();
      }
      assertThat(SegmentValidator.validate(new Segment("ALL", List.of(e.example()))).rules()).as(e.type() + " example").hasSize(1);
      assertThat(e.example().type()).isEqualTo(e.type());
    }
    assertThat(ConditionType.find("ORDER_COUNT")).isPresent();
    assertThat(ConditionType.find("order_count")).isEmpty();
  }

  @Test
  void ruleParamsListOnlyWhatIsSet() {
    assertThat(rule("{\"type\":\"ORDER_COUNT\",\"op\":\"EQ\",\"value\":0,\"negate\":true}").params()).containsOnlyKeys("op", "value");
  }
}
