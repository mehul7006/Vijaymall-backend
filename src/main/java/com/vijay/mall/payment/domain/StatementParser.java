package com.vijay.mall.payment.domain;

import com.vijay.mall.payment.domain.SettlementProvider.Item;
import java.util.List;

/**
 * Port for a vendor's own statement file (for example the Paytm dashboard Settlement Report). The CSV import asks each parser whether it
 * recognises the file; the generic column-mapping reader is used when none does or the admin names no format.
 */
public interface StatementParser {
  /**
   * What reading a file gave.
   * @param items the lines that were read, already in the shape the settlement tables store
   * @param problems the lines that could not be read, each as {@code "row N: ..."} (at most 20)
   */
  record Result(List<Item> items, List<String> problems) {}

  /** The format code the admin sends as {@code format}, upper-case, for example {@code PAYTM}. */
  String format();

  /** The provider the lines belong to, lower-case, for example {@code paytm}. */
  String provider();

  /** True when the file's header row is this vendor's. */
  boolean recognises(String headerLine);

  /** Reads the whole file; a header problem is an {@code ApiException} 400 {@code CSV_INVALID}. */
  Result parse(byte[] bytes);
}
