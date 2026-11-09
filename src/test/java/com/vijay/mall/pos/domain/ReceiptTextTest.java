package com.vijay.mall.pos.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReceiptTextTest {
  private static ReceiptData sample(boolean extras) {
    return new ReceiptData("Vijay Super Mall", "12 Market Road Rajkot Gujarat", extras ? "9876543210" : "",
        extras ? "24ABCDE1234F1Z5" : "", "VSM/2026-27/000042", "27-10-2026 10:30", "Asha", extras ? "9000000001" : "",
        List.of(new ReceiptLine("Basmati Rice 5kg premium long grain", SoldBy.UNIT, 2, 50000, 100000, 5),
            new ReceiptLine("Tomato", SoldBy.WEIGHT, 1250, 4000, 5000, 0)),
        105000, extras ? 5000 : 0, extras ? 1000 : 0, 99000, 94000, 2500, 2500,
        List.of(new ReceiptData.PaymentLine("CASH", 50000), new ReceiptData.PaymentLine("UPI", 49000)),
        extras ? 500 : 0, extras ? 9 : 0, "Thank you! Visit again.");
  }

  @Test void render32_fitsWidth_andHasKeyLines() {
    String t = ReceiptText.render(sample(true), 32);
    assertThat(t.lines()).allMatch(l -> l.length() <= 32);
    assertThat(t).contains("Vijay Super Mall", "GSTIN: 24ABCDE1234F1Z5", "Ph: 9876543210", "TAX INVOICE",
        "Inv: VSM/2026-27/000042", "Cust: 9000000001", "1.250 kg (0%)", "2 x 500.00 (5%)",
        "Coupon", "Coins", "TOTAL", "990.00", "CGST", "SGST", "CASH", "UPI", "Change", "5.00", "Coins earned: 9");
    assertThat(t).endsWith("\n");
  }
  @Test void render48_fitsWidth() {
    assertThat(ReceiptText.render(sample(true), 48).lines()).allMatch(l -> l.length() <= 48);
  }
  @Test void optionalParts_omittedWhenEmpty() {
    String t = ReceiptText.render(sample(false), 32);
    assertThat(t).doesNotContain("GSTIN", "Ph:", "Coupon", "Coins", "Change", "Cust:");
  }
  @Test void badWidth_throws() {
    assertThatThrownBy(() -> ReceiptText.render(sample(true), 40)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void center_padsAndLeavesLong() {
    assertThat(ReceiptText.center("ab", 6)).isEqualTo("  ab");
    assertThat(ReceiptText.center("abcdef", 4)).isEqualTo("abcdef");
  }
  @Test void split_alignsRight_andCutsLeft() {
    assertThat(ReceiptText.split("Total", "9.00", 12)).isEqualTo("Total   9.00");
    assertThat(ReceiptText.split("A very long label", "9.00", 12)).isEqualTo("A very  9.00");
    assertThat(ReceiptText.split("x", "12345678901234", 10)).isEqualTo(" 12345678901234");
  }
  @Test void wrap_breaksOnWords_andCutsGiantWords() {
    assertThat(ReceiptText.wrap("aaa bbb ccc", 7)).containsExactly("aaa bbb", "ccc");
    assertThat(ReceiptText.wrap("abcdefghij", 4)).containsExactly("abcd");
    assertThat(ReceiptText.wrap("  ", 4)).isEmpty();
  }

  private static ReceiptData withRoundOff(long roundOff) {
    return new ReceiptData("Shop", "Road", "", "", "INV-1", "01-01-2026", "Asha", "",
        List.of(new ReceiptLine("Rice", SoldBy.UNIT, 1, 10050, 10050, 0)), 10050, 0, 0, 10000, 10000, 0, 0,
        List.of(new ReceiptData.PaymentLine("CASH", 10000)), 0, 0, "Thanks", 0, roundOff);
  }

  @Test void roundOff_shownWithItsSignOnlyWhenNotZero() {
    assertThat(ReceiptText.render(withRoundOff(-50), 32)).contains("Round off").contains("-0.50");
    assertThat(ReceiptText.render(withRoundOff(30), 32)).contains("Round off").contains("+0.30");
    assertThat(ReceiptText.render(withRoundOff(0), 32)).doesNotContain("Round off");
  }

  @Test void shorterConstructorsDefaultTheMissingParts() {
    var lines = List.of(new ReceiptLine("Rice", SoldBy.UNIT, 1, 100, 100, 0));
    var pays = List.of(new ReceiptData.PaymentLine("CASH", 100));
    var noRound = new ReceiptData("S", "A", "", "", "I", "D", "C", "", lines, 100, 0, 0, 100, 100, 0, 0, pays, 0, 0, "F", 25);
    assertThat(noRound.manualDiscountPaise()).isEqualTo(25);
    assertThat(noRound.roundOffPaise()).isZero();
    assertThat(ReceiptText.render(noRound, 32)).contains("Discount");
  }
}
