package com.vijay.mall.pos.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.common.tax.GstRates;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The credit note and credit repayment receipts. */
class ReceiptTextDocumentsTest {
  private static CreditNoteData note(long creditAdjusted, String reason, String phone, String gstin) {
    return new CreditNoteData("Vijay Super Mall", "Market Road", phone, gstin, "VSM-CN/2026-27/000003", "VSM/2026-27/000007",
        "27-10-2026 10:30", "Asha", "9000000001", reason,
        List.of(new CreditNoteData.Line("Soap", SoldBy.UNIT, 2, GstRates.of(12), 4500), new CreditNoteData.Line("Loose Sugar", SoldBy.WEIGHT, 1500, GstRates.of(5), 6750)),
        10000, 600, 650, 11250, List.of(new ReceiptData.PaymentLine("UPI", 11250)), creditAdjusted, "Thank you!");
  }

  @Test void creditNoteShowsNumbersLinesGstSplitRefundsAndTotal() {
    String t = ReceiptText.renderCreditNote(note(0, "damaged", "9876543210", "24ABCDE1234F1Z5"), 32);
    assertThat(t).contains("CREDIT NOTE", "CN: VSM-CN/2026-27/000003", "Orig Inv: VSM/2026-27/000007", "Cashier: Asha  Cust: 9000000001",
        "Soap", "Returned 2 (12%)", "45.00", "1.500 kg", "Taxable", "100.00", "CGST", "SGST", "Refund total", "112.50", "Refund UPI",
        "Reason: damaged", "Ph: 9876543210", "GSTIN: 24ABCDE1234F1Z5", "Thank you!");
    assertThat(t).doesNotContain("Credit reduced");
    assertThat(t.lines().allMatch(l -> l.length() <= 32)).isTrue();
    assertThat(t).endsWith("\n");
  }

  @Test void creditNoteShowsCreditReducedAndSkipsEmptyParts() {
    String t = ReceiptText.renderCreditNote(note(2000, "", "", ""), 48);
    assertThat(t).contains("Credit reduced").doesNotContain("Reason:", "Ph:", "GSTIN:");
    var noCustomer = new CreditNoteData("S", "A", "", "", "CN1", "INV1", "d", "c", "", "", List.of(), 0, 0, 0, 0, List.of(), 0, "f");
    assertThat(ReceiptText.renderCreditNote(noCustomer, 32)).contains("Cashier: c").doesNotContain("Cust:");
  }

  @Test void repaymentShowsCustomerAmountModesAndBalances() {
    var d = new RepaymentData("Vijay Super Mall", "Market Road", "9876543210", "24ABCDE1234F1Z5", "VSM-CR/2026-27/000002", "27-10-2026 10:30",
        "Asha", "9000000001", 3000, List.of(new ReceiptData.PaymentLine("CASH", 1000), new ReceiptData.PaymentLine("UPI", 2000)),
        5000, 2000, "part payment", "Thank you!");
    String t = ReceiptText.renderRepayment(d, 32);
    assertThat(t).contains("CREDIT PAYMENT RECEIPT", "Receipt: VSM-CR/2026-27/000002", "Customer: 9000000001", "Balance before", "50.00",
        "Paid CASH", "Paid UPI", "Amount paid", "30.00", "Balance after", "20.00", "Note: part payment", "Thank you!");
    var plain = new RepaymentData("S", "A", "", "", "R1", "d", "c", "9", 100, List.of(), 100, 0, "", "f");
    assertThat(ReceiptText.renderRepayment(plain, 48)).doesNotContain("Note:", "Ph:", "GSTIN:");
  }

  @Test void onlyThe58And80mmWidthsAreAccepted() {
    assertThatThrownBy(() -> ReceiptText.renderCreditNote(note(0, "", "", ""), 40)).hasMessage("width must be 32 or 48");
    assertThatThrownBy(() -> ReceiptText.renderRepayment(new RepaymentData("S", "A", "", "", "R", "d", "c", "m", 1, List.of(), 1, 0, "", ""), 20))
        .hasMessage("width must be 32 or 48");
  }
}
