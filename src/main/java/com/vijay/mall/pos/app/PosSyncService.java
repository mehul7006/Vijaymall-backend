package com.vijay.mall.pos.app;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.pos.app.PosViews.Cart;
import com.vijay.mall.pos.app.PosViews.ManualDiscount;
import com.vijay.mall.pos.app.PosViews.Pay;
import com.vijay.mall.pos.app.PosViews.SaleInput;
import com.vijay.mall.pos.domain.PosRecords.SaleRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Support for a counter that keeps selling while the internet is down. The till keeps its own copy of the products (see
 * {@code status().catalogVersion()}), rings bills up locally with a temporary receipt number, and when the connection returns
 * sends them here in batches. Each bill carries the idempotency key it was created with, so sending a batch twice, or one that
 * half-arrived, never rings anything up twice. Bills are real: the server gives them their invoice number on arrival.
 * A bill that cannot be accepted is reported back by itself and never blocks the others.
 */
@Service
public class PosSyncService {
  private static final Logger log = LoggerFactory.getLogger(PosSyncService.class);

  public static final int BATCH_LIMIT = 50;

  /** One bill rung up offline. soldAt is when it really happened. */
  public record OfflineSale(String idempotencyKey, Instant soldAt, Cart cart, List<Pay> payments, Long cashTenderedPaise,
                            ManualDiscount manualDiscount) {}

  /** status is CREATED (now recorded), DUPLICATE (already recorded earlier, nothing done) or REJECTED (with errorCode and message). */
  public record Result(String idempotencyKey, String status, UUID saleId, String invoiceNo, String errorCode, String message) {}

  public record Status(Instant serverTime, Instant catalogVersion, int maxOfflineDays, int batchLimit) {}

  private final PosService pos;
  private final PosCatalogService catalog;
  private final Clock clock;

  public PosSyncService(PosService pos, PosCatalogService catalog, Clock clock) {
    this.pos = pos;
    this.catalog = catalog;
    this.clock = clock;
  }

  public Status status() {
    return new Status(clock.instant(), catalog.catalogVersion(), (int) PosService.MAX_OFFLINE_DAYS, BATCH_LIMIT);
  }

  /** Records each bill on its own, in the order given; the answer lists the outcome of every one, in the same order. */
  public List<Result> upload(UUID cashierId, boolean admin, List<OfflineSale> sales) {
    if (sales == null || sales.isEmpty() || sales.size() > BATCH_LIMIT) {
      throw ApiException.validation("sales", "send 1 to " + BATCH_LIMIT + " bills at a time");
    }
    List<Result> out = new ArrayList<>();
    for (OfflineSale s : sales) out.add(one(cashierId, admin, s));
    return out;
  }

  private Result one(UUID cashierId, boolean admin, OfflineSale s) {
    String key = s == null ? null : s.idempotencyKey();
    if (key == null || key.isBlank()) return new Result(key, "REJECTED", null, null, "VALIDATION_FAILED", "idempotencyKey is required");
    try {
      var earlier = pos.findByIdempotencyKey(key);
      if (earlier.isPresent()) return new Result(key, "DUPLICATE", earlier.get().id(), earlier.get().invoiceNo(), null, null);
      SaleRecord done = pos.sale(cashierId, admin, key, new SaleInput(s.cart(), s.payments(), s.cashTenderedPaise(), s.manualDiscount(),
          s.soldAt(), true));
      return new Result(key, "CREATED", done.id(), done.invoiceNo(), null, null);
    } catch (ApiException e) {
      log.info("Offline bill refused key={} code={} reason={}", key, e.code(), e.getMessage());
      return new Result(key, "REJECTED", null, null, e.code(), e.getMessage());
    } catch (RuntimeException e) {
      log.error("Offline bill failed key={}", key, e);
      return new Result(key, "REJECTED", null, null, "INTERNAL_ERROR", "Could not record this bill, try again");
    }
  }
}
