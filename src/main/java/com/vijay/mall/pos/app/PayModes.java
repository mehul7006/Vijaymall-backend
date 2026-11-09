package com.vijay.mall.pos.app;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.pos.app.PosViews.Pay;
import com.vijay.mall.pos.domain.PaymentRules;
import java.util.ArrayList;
import java.util.List;

/** Checks and normalises the payment modes of the lines of a bill, refund or repayment in one place. */
final class PayModes {
  private PayModes() {}

  /**
   * Returns the lines with every mode trimmed and upper-cased.
   *
   * @param pays the lines as sent (null or empty gives an empty list)
   * @param allowCredit true for a bill, where CREDIT is also a valid mode
   * @param field the request field, such as {@code payments} or {@code refunds}
   * @throws ApiException 400 VALIDATION_FAILED with {@code field[i].mode} naming the first bad line and the value received
   */
  static List<Pay> normalize(List<Pay> pays, boolean allowCredit, String field) {
    if (pays == null) return List.of();
    List<Pay> out = new ArrayList<>();
    for (int i = 0; i < pays.size(); i++) {
      Pay p = pays.get(i);
      if (p == null) throw ApiException.validation(field + "[" + i + "]", "payment line is empty");
      try {
        out.add(new Pay(PaymentRules.requireMode(p.mode(), allowCredit), p.amountPaise(), p.reference(), p.terminalPaymentId()));
      } catch (IllegalArgumentException e) {
        throw ApiException.validation(field + "[" + i + "].mode", e.getMessage());
      }
    }
    return out;
  }
}
