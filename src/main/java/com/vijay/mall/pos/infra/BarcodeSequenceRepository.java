package com.vijay.mall.pos.infra;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The counters behind store-internal barcodes: one running number per in-store prefix. */
@Repository
public class BarcodeSequenceRepository {
  private final JdbcClient jdbc;

  /** Creates the repository. */
  public BarcodeSequenceRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  /**
   * Takes the next number for a prefix (the first is 1). One statement does the read and the increment, so two requests at the
   * same moment never get the same number.
   */
  public long next(String prefix) {
    return jdbc.sql("insert into barcode_sequence (prefix, next_value) values (:p, 2) "
        + "on conflict (prefix) do update set next_value = barcode_sequence.next_value + 1, updated_at = now() "
        + "returning next_value - 1").param("p", prefix).query(Long.class).single();
  }

  /** Every prefix ever used, so a barcode made under an earlier prefix is still recognised as store-generated. */
  public List<String> prefixes() { return jdbc.sql("select prefix from barcode_sequence").query(String.class).list(); }

  /** Locks the product row until the end of the transaction, so two requests for one variant take turns. */
  public boolean lockProduct(UUID productId) {
    return jdbc.sql("select id from product where id = :id for update").param("id", productId).query(UUID.class).optional().isPresent();
  }
}
