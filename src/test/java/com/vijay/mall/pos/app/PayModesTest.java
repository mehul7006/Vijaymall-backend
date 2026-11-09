package com.vijay.mall.pos.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.pos.app.PosViews.Pay;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Payment line modes are normalised once and a bad one is reported on its own indexed field. */
class PayModesTest {
  @Test void normalisesEveryLineAndKeepsAmountAndReference() {
    var out = PayModes.normalize(List.of(new Pay(" cash ", 100, null), new Pay("Upi", 200, "U1")), false, "payments");
    assertThat(out).containsExactly(new Pay("CASH", 100, null), new Pay("UPI", 200, "U1"));
  }

  @Test void nullListIsEmpty() { assertThat(PayModes.normalize(null, true, "payments")).isEmpty(); }

  @Test void creditIsOnlyAllowedWhereAskedFor() {
    assertThat(PayModes.normalize(List.of(new Pay("credit", 5, null)), true, "payments").get(0).mode()).isEqualTo("CREDIT");
    assertThatThrownBy(() -> PayModes.normalize(List.of(new Pay("CREDIT", 5, null)), false, "refunds"))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(fields(e)).containsKey("refunds[0].mode"));
  }

  @Test void badModeIsReportedOnTheIndexedFieldWithTheValueReceived() {
    var e = catchApi(() -> PayModes.normalize(List.of(new Pay("CASH", 1, null), new Pay("xyz", 1, null)), true, "payments"));
    assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
    assertThat(fields(e)).containsEntry("payments[1].mode", "payment mode must be CASH, UPI or CARD, or CREDIT on a bill (got \"xyz\")");
  }

  @Test void nullModeIsAValidationErrorNotAnNpe() {
    var e = catchApi(() -> PayModes.normalize(List.of(new Pay(null, 1, null)), false, "payments"));
    assertThat(fields(e)).containsEntry("payments[0].mode", "payment mode must be CASH, UPI or CARD (got none)");
  }

  @Test void emptyLineIsAValidationError() {
    List<Pay> pays = new ArrayList<>();
    pays.add(null);
    assertThat(fields(catchApi(() -> PayModes.normalize(pays, false, "payments")))).containsKey("payments[0]");
  }

  static ApiException catchApi(Runnable r) {
    try {
      r.run();
    } catch (ApiException e) {
      return e;
    }
    throw new AssertionError("expected ApiException");
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> fields(ApiException e) { return (Map<String, Object>) e.details().get("fields"); }
}
