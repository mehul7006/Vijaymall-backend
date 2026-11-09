package com.vijay.mall.pos.app;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.pos.app.PosViews.Cart;
import com.vijay.mall.pos.app.PosViews.ManualDiscount;
import com.vijay.mall.pos.app.PosViews.Pay;
import com.vijay.mall.pos.app.PosViews.Rung;
import com.vijay.mall.pos.app.PosViews.SaleInput;
import com.vijay.mall.pos.domain.OfflineFinding;
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
 *
 * <p>A bill that carries {@code rung} (what the till really charged) is recorded exactly as rung: its prices, GST rates and
 * discounts are kept, and only a malformed bill is refused. Differences from the catalog (price, GST rate, discount, stock short,
 * product switched off) are stored as exceptions for a manager and listed in the result. A bill without {@code rung} is the
 * legacy form for old apps: the server prices the cart again at today's prices and refuses it if the payments no longer fit.
 */
@Service
public class PosSyncService {
  private static final Logger log = LoggerFactory.getLogger(PosSyncService.class);

  /** Most bills accepted in one upload. */
  public static final int BATCH_LIMIT = 50;

  /**
   * One bill rung up offline. soldAt is when it really happened. {@code rung} is what the till actually charged, line by line;
   * when it is given the server records those numbers (the cart then only carries the customer mobile). Without it the legacy
   * path prices the cart again.
   */
  public record OfflineSale(String idempotencyKey, Instant soldAt, Cart cart, List<Pay> payments, Long cashTenderedPaise,
                            ManualDiscount manualDiscount, Rung rung) {
    /** A legacy bill: no rung snapshot, the server prices the cart. */
    public OfflineSale(String idempotencyKey, Instant soldAt, Cart cart, List<Pay> payments, Long cashTenderedPaise,
                       ManualDiscount manualDiscount) {
      this(idempotencyKey, soldAt, cart, payments, cashTenderedPaise, manualDiscount, null);
    }
  }

  /** A difference from the system that a manager will review; the bill is recorded anyway. */
  public record ExceptionView(String kind, String detail) {}

  /**
   * status is CREATED (now recorded), DUPLICATE (already recorded earlier, nothing done) or REJECTED (with errorCode and message).
   * exceptions lists what a manager will review (empty when the bill matched the system, and for a rejected bill).
   */
  public record Result(String idempotencyKey, String status, UUID saleId, String invoiceNo, String errorCode, String message,
                       List<ExceptionView> exceptions) {
    /** A result with no exceptions. */
    public Result(String idempotencyKey, String status, UUID saleId, String invoiceNo, String errorCode, String message) {
      this(idempotencyKey, status, saleId, invoiceNo, errorCode, message, List.of());
    }
  }

  /**
   * What an offline till needs to know before syncing: the server time, the catalog version stamp, how many days old
   * an offline bill may be, and the upload batch size.
   */
  public record Status(Instant serverTime, Instant catalogVersion, int maxOfflineDays, int batchLimit) {}

  private final PosService pos;
  private final PosCatalogService catalog;
  private final OfflineExceptionService exceptions;
  private final Clock clock;

  /** Creates the service. */
  public PosSyncService(PosService pos, PosCatalogService catalog, OfflineExceptionService exceptions, Clock clock) {
    this.pos = pos;
    this.catalog = catalog;
    this.exceptions = exceptions;
    this.clock = clock;
  }

  /** Current server time, catalog version, maximum offline age and batch limit. */
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

  private List<ExceptionView> views(UUID saleId) {
    return exceptions.forSale(saleId).stream().map((OfflineFinding f) -> new ExceptionView(f.kind().name(), f.detail())).toList();
  }

  private Result one(UUID cashierId, boolean admin, OfflineSale s) {
    String key = s == null ? null : s.idempotencyKey();
    if (key == null || key.isBlank()) return new Result(key, "REJECTED", null, null, "VALIDATION_FAILED", "idempotencyKey is required");
    try {
      var earlier = pos.findByIdempotencyKey(key);
      if (earlier.isPresent()) {
        return new Result(key, "DUPLICATE", earlier.get().id(), earlier.get().invoiceNo(), null, null, views(earlier.get().id()));
      }
      // a bill with a rung snapshot needs no cart; the customer mobile is the only thing read from it
      Cart cart = s.cart() == null && s.rung() != null ? new Cart(null, null, null, null, null) : s.cart();
      SaleRecord done = pos.sale(cashierId, admin, key, new SaleInput(cart, s.payments(), s.cashTenderedPaise(), s.manualDiscount(),
          s.soldAt(), true, s.rung()));
      List<ExceptionView> found = views(done.id());
      log.atInfo().setMessage("Offline bill recorded").addKeyValue("event", "offline-bill-recorded").addKeyValue("invoiceNo", done.invoiceNo())
          .addKeyValue("mode", s.rung() == null ? "legacy" : "as-rung").addKeyValue("exceptions", found.size())
          .addKeyValue("needsReview", !found.isEmpty()).log();
      return new Result(key, "CREATED", done.id(), done.invoiceNo(), null, null, found);
    } catch (ApiException e) {
      log.info("Offline bill refused key={} code={} reason={}", key, e.code(), e.getMessage());
      return new Result(key, "REJECTED", null, null, e.code(), e.getMessage());
    } catch (RuntimeException e) {
      log.error("Offline bill failed key={}", key, e);
      return new Result(key, "REJECTED", null, null, "INTERNAL_ERROR", "Could not record this bill, try again");
    }
  }
}
