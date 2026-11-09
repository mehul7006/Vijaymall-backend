package com.vijay.mall.payment.paytm.settlement;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.payment.domain.SettlementProvider.Item;
import com.vijay.mall.payment.domain.StatementParser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Reads the Paytm dashboard Settlement Report for the bank module's CSV import ({@code format=PAYTM}, or picked automatically when the header is
 * Paytm's). The reading itself is {@link PaytmSettlementReportParser}; this class maps the lines to settlement items.
 */
@Component
public class PaytmStatementParser implements StatementParser {
  private final PaytmSettlementReportParser reader;

  /** Creates the parser. */
  public PaytmStatementParser(PaytmSettlementReportParser reader) { this.reader = reader; }

  @Override public String format() { return "PAYTM"; }

  @Override public String provider() { return "paytm"; }

  /** Paytm's header has Transaction ID, Commission and Settled amount columns. */
  @Override
  public boolean recognises(String headerLine) {
    String k = headerLine == null ? "" : headerLine.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9,]", "");
    return k.contains("transactionid") && k.contains("commission") && k.contains("settledamount");
  }

  @Override
  public Result parse(byte[] bytes) {
    PaytmSettlementReportParser.Result r;
    try {
      r = reader.parse(new String(bytes, StandardCharsets.UTF_8));
    } catch (IllegalArgumentException e) {
      throw ApiException.fieldError("CSV_INVALID", "file", e.getMessage());
    }
    List<Item> items = r.records().stream().filter(PaytmSettlementMapper::paidOut).map(PaytmSettlementMapper::toItem).toList();
    List<String> problems = r.errors().stream().limit(20).map(e -> "row " + e.line() + ": " + e.message()).toList();
    return new Result(items, problems);
  }
}
