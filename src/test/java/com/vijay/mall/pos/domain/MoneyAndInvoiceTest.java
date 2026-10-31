package com.vijay.mall.pos.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class MoneyAndInvoiceTest {
  @Test void money_formats() {
    assertThat(Money.plain(0)).isEqualTo("0.00");
    assertThat(Money.plain(5)).isEqualTo("0.05");
    assertThat(Money.plain(123450)).isEqualTo("1234.50");
    assertThat(Money.plain(-250)).isEqualTo("-2.50");
  }
  @Test void fy_aprilStartsNewYear() {
    assertThat(InvoiceNumbers.financialYear(LocalDate.of(2026, 4, 1))).isEqualTo("2026-27");
    assertThat(InvoiceNumbers.financialYear(LocalDate.of(2027, 3, 31))).isEqualTo("2026-27");
    assertThat(InvoiceNumbers.financialYear(LocalDate.of(2026, 3, 31))).isEqualTo("2025-26");
  }
  @Test void fy_centuryRollover() { assertThat(InvoiceNumbers.financialYear(LocalDate.of(2099, 5, 1))).isEqualTo("2099-00"); }
  @Test void fy_usesIndiaTime() {
    // 31 Mar 2027 20:00 UTC is already 1 Apr 01:30 IST
    assertThat(InvoiceNumbers.financialYear(Instant.parse("2027-03-31T20:00:00Z"))).isEqualTo("2027-28");
    assertThat(InvoiceNumbers.financialYear(Instant.parse("2027-03-31T18:00:00Z"))).isEqualTo("2026-27");
  }
  @Test void format_padsNumber() {
    assertThat(InvoiceNumbers.format("VSM", "2026-27", 42)).isEqualTo("VSM/2026-27/000042");
    assertThat(InvoiceNumbers.format("CN", "2026-27", 1234567)).isEqualTo("CN/2026-27/1234567");
  }
}
