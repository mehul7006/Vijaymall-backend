package com.vijay.mall.payment.paytm.app;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.ratelimit.RateLimiter;
import com.vijay.mall.payment.paytm.domain.PaytmReply;
import com.vijay.mall.payment.paytm.domain.PaytmReply.TxnDetails;
import com.vijay.mall.payment.paytm.domain.PaytmResultCode.Outcome;
import com.vijay.mall.payment.paytm.domain.PaytmTerminalService;
import com.vijay.mall.payment.paytm.domain.PaytmTerminalService.Txn;
import com.vijay.mall.payment.paytm.infra.PaymentTerminalEntity;
import com.vijay.mall.payment.paytm.infra.PaymentTerminalJpa;
import com.vijay.mall.payment.paytm.infra.PaytmEdcClient;
import com.vijay.mall.payment.paytm.infra.TerminalPaymentEntity;
import com.vijay.mall.payment.paytm.infra.TerminalPaymentJpa;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The durable state machine of a card-machine payment.
 *
 * <p>States: REQUESTED (row written, sale being sent), PENDING (machine has it), UNKNOWN (the answer to the sale was lost or
 * unreadable: only a status check can tell), SUCCESS, FAILED, CANCELLED (the machine reported a cancel the cashier asked for),
 * EXPIRED (nobody paid in {@code waitSeconds}) and VOIDED (a paid sale undone the same day). A machine holds at most one of
 * REQUESTED, PENDING or UNKNOWN at a time (a unique index), which is the {@code TERMINAL_BUSY} rule.
 *
 * <p>Cancel rule: Paytm documents no call that cancels a sale waiting on the machine. So a cancel never marks a payment CANCELLED
 * by itself: it asks Paytm for the status; only when Paytm reports the sale ended without payment (the cashier pressed cancel on
 * the machine) does it become CANCELLED, otherwise the answer is 409 {@code CANCEL_ON_MACHINE} and the request is remembered, so
 * the next check that reports the machine-side cancel closes it as CANCELLED. An unpaid payment becomes EXPIRED only after
 * {@code waitSeconds} and one last status check. An EXPIRED payment is still watched for 15 minutes, because a customer may pay late:
 * a late success turns it into SUCCESS.
 *
 * <p>The merchant transaction id is the payment id as 32 lower-case hex characters (letters and digits, the 8 to 32 Paytm allows).
 */
@Service
public class TerminalPaymentService {
  private static final Logger log = LoggerFactory.getLogger(TerminalPaymentService.class);
  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
  /** States in which a machine is busy. */
  static final Set<String> OPEN = Set.of("REQUESTED", "PENDING", "UNKNOWN");
  /** States that are checked with Paytm: open ones and expired ones still watched for a late payment. */
  static final Set<String> WATCHED = Set.of("REQUESTED", "PENDING", "UNKNOWN", "EXPIRED");
  private static final Duration LATE_WATCH = Duration.ofMinutes(15);
  private static final int TICK_BATCH = 20;
  private static final long MAX_PAISE = 100_000_000L;

  /**
   * What the till shows.
   * @param id payment id
   * @param terminalRowId the machine
   * @param terminalId the vendor terminal id
   * @param terminalName the machine's name
   * @param amountPaise amount
   * @param reference bill number or cart reference
   * @param status REQUESTED, PENDING, UNKNOWN, SUCCESS, FAILED, CANCELLED, EXPIRED or VOIDED
   * @param open true while the machine is waiting (the till keeps polling)
   * @param message plain text for the cashier
   * @param resultCode the vendor's last result code
   * @param payMethod CREDIT_CARD, DEBIT_CARD, UPI...
   * @param cardScheme card network
   * @param maskedCard masked card number
   * @param rrn retrieval reference number
   * @param authCode authorisation code
   * @param issuingBank issuing bank
   * @param upiVpa payer UPI address, masked
   * @param posSaleId the bill that used it, or null
   * @param canCancel true while a cancel can be tried
   * @param canVoid true when a same-day void is possible
   * @param pollAfterSeconds how long the till should wait before asking again
   * @param createdAt start
   * @param finishedAt end, or null
   */
  public record TerminalPaymentView(UUID id, UUID terminalRowId, String terminalId, String terminalName, long amountPaise, String reference,
                                    String status, boolean open, String message, String resultCode, String payMethod, String cardScheme,
                                    String maskedCard, String rrn, String authCode, String issuingBank, String upiVpa, UUID posSaleId,
                                    boolean canCancel, boolean canVoid, int pollAfterSeconds, Instant createdAt, Instant finishedAt) {}

  /**
   * A paid card-machine payment ready to be attached to a bill.
   * @param id payment id
   * @param amountPaise amount
   * @param payMethod CREDIT_CARD, DEBIT_CARD, UPI...
   * @param cardScheme card network
   * @param maskedCard masked card
   * @param rrn retrieval reference number
   * @param authCode authorisation code
   * @param terminalId vendor terminal id
   * @param reference short text for the bill's payment line
   */
  public record Confirmed(UUID id, long amountPaise, String payMethod, String cardScheme, String maskedCard, String rrn, String authCode,
                          String terminalId, String reference) {}

  private final TerminalPaymentJpa payments;
  private final PaymentTerminalJpa terminals;
  private final PaytmTerminalService paytm;
  private final PaytmSettings cfg;
  private final AuditService audit;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final RateLimiter limiter;
  private final int startsPerMinute;

  /** Creates the service; {@code app.paytm.starts-per-minute} (default 30) caps how many card payments one user can start a minute. */
  public TerminalPaymentService(TerminalPaymentJpa payments, PaymentTerminalJpa terminals, PaytmTerminalService paytm, PaytmSettings cfg,
                                AuditService audit, Clock clock, TransactionTemplate tx, RateLimiter limiter,
                                @Value("${app.paytm.starts-per-minute:30}") int startsPerMinute) {
    this.startsPerMinute = startsPerMinute;
    this.payments = payments;
    this.terminals = terminals;
    this.paytm = paytm;
    this.cfg = cfg;
    this.audit = audit;
    this.clock = clock;
    this.tx = tx;
    this.limiter = limiter;
  }

  /** The merchant transaction id for a payment id. */
  public static String merchantTransactionId(UUID id) { return id.toString().replace("-", ""); }

  private void requireReady() {
    if (!cfg.ready()) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYTM_NOT_CONFIGURED", "The card machine is not set up. Ask the admin to finish the Paytm settings.");
    }
  }

  private static Txn txn(TerminalPaymentEntity p) { return new Txn(p.terminalId, p.merchantTransactionId, p.txnDateTime); }

  // ---------- start ----------
  /**
   * Writes the payment and sends the amount to the machine.
   *
   * @param idemKey the caller's Idempotency-Key: the same key returns the same payment
   * @throws ApiException 409 {@code TERMINAL_BUSY} (details.openPaymentId) when the machine already has an open payment;
   *     422 {@code TERMINAL_REJECTED} with Paytm's plain reason when Paytm refuses the sale; 503 {@code PAYTM_NOT_CONFIGURED}
   */
  public TerminalPaymentView start(UUID actor, String idemKey, UUID terminalRowId, long amountPaise, String reference) {
    requireReady();
    if (idemKey == null || idemKey.isBlank() || idemKey.length() > 100) throw ApiException.validation("Idempotency-Key", "header is required (at most 100 characters)");
    if (terminalRowId == null) throw ApiException.validation("terminalId", "is required");
    if (amountPaise < 1 || amountPaise > MAX_PAISE) throw ApiException.validation("amountPaise", "must be between 1 and " + MAX_PAISE);
    String ref = reference == null ? "" : reference.strip();
    if (ref.length() > 50) throw ApiException.validation("reference", "at most 50 characters");
    var again = payments.findByIdemKey(idemKey);
    if (again.isPresent()) return view(again.get());
    limiter.check("terminal-pay:" + actor, startsPerMinute, 60);
    PaymentTerminalEntity terminal = terminals.findById(terminalRowId)
        .orElseThrow(() -> ApiException.validation("terminalId", "unknown card machine"));
    if (!terminal.active) throw ApiException.unprocessable("TERMINAL_INACTIVE", "This card machine is switched off in the admin screen",
        Map.of("fields", Map.of("terminalId", "machine is switched off")));
    Instant now = clock.instant();
    TerminalPaymentEntity p = TerminalPaymentEntity.create();
    p.id = UUID.randomUUID();
    p.paymentTerminalId = terminal.id;
    p.terminalId = terminal.terminalId;
    p.provider = terminal.provider;
    p.merchantTransactionId = merchantTransactionId(p.id);
    p.txnDateTime = PaytmEdcClient.TIME.format(now.atZone(INDIA));
    p.idemKey = idemKey;
    p.amountPaise = amountPaise;
    p.reference = ref;
    p.status = "REQUESTED";
    p.message = "Sending the amount to the machine";
    p.requestedBy = actor;
    p.nextPollAt = now.plusSeconds(cfg.pollSeconds());
    p.createdAt = now;
    p.updatedAt = now;
    try {
      tx.executeWithoutResult(s -> {
        payments.saveAndFlush(p);
        audit.log(actor, "TERMINAL_PAYMENT_START", "terminal_payment", p.id, p.terminalId + " amount=" + amountPaise + " ref=" + ref);
      });
    } catch (DataIntegrityViolationException e) {
      var raced = payments.findByIdemKey(idemKey);
      if (raced.isPresent()) return view(raced.get());
      Map<String, Object> details = new java.util.HashMap<>();
      payments.findFirstByPaymentTerminalIdAndStatusIn(terminal.id, OPEN).ifPresent(o -> details.put("openPaymentId", o.id.toString()));
      details.put("fields", Map.of("terminalId", "this machine already has an open payment"));
      throw ApiException.conflict("TERMINAL_BUSY", "This card machine already has a payment waiting. Finish or cancel it first.", details);
    }
    PaytmReply reply = paytm.requestSale(new PaytmTerminalService.Sale(p.terminalId, p.merchantTransactionId, p.txnDateTime, amountPaise, ref));
    return afterSale(p.id, reply);
  }

  private TerminalPaymentView afterSale(UUID id, PaytmReply reply) {
    TerminalPaymentView v = tx.execute(s -> {
      TerminalPaymentEntity p = payments.lockById(id).orElseThrow();
      Instant now = clock.instant();
      p.resultCode = reply.code();
      switch (reply.outcome()) {
        case ACCEPTED -> {
          p.status = "PENDING";
          p.message = "Waiting for the customer to pay on the machine";
          p.nextPollAt = now.plusSeconds(cfg.pollSeconds());
        }
        case REJECTED -> {
          finish(p, "FAILED", reply.message(), now);
          audit.log(null, "TERMINAL_PAYMENT_RESULT", "terminal_payment", p.id, "FAILED " + reply.internalCode());
        }
        default -> {
          p.status = "UNKNOWN";
          p.message = "Could not confirm that the machine got the amount. Checking with Paytm.";
          p.nextPollAt = now.plusSeconds(cfg.pollSeconds());
          log.warn("Terminal payment {} sale answer unclear: {} {}", p.id, reply.outcome(), reply.code());
        }
      }
      p.updatedAt = now;
      payments.save(p);
      return view(p);
    });
    if (reply.outcome() == Outcome.REJECTED) {
      throw ApiException.unprocessable("TERMINAL_REJECTED", reply.message(),
          Map.of("fields", Map.of("terminalId", reply.message()), "reason", reply.internalCode(), "paytmCode", reply.code(), "paymentId", id.toString()));
    }
    return v;
  }

  // ---------- reading and polling ----------
  /** The current state. An open payment that is due is checked with Paytm first, so a polling till works without the background job. */
  public TerminalPaymentView get(UUID id) {
    TerminalPaymentEntity p = payments.findById(id).orElseThrow(() -> ApiException.notFound("Terminal payment"));
    if (WATCHED.contains(p.status) && cfg.ready()) {
      checkNow(id);
      p = payments.findById(id).orElseThrow();
    }
    return view(p);
  }

  private void checkNow(UUID id) {
    Instant now = clock.instant();
    UUID claimed = tx.execute(s -> payments.dueById(id, WATCHED, now).map(this::lease).orElse(null));
    if (claimed != null) checkOne(claimed);
  }

  private UUID lease(TerminalPaymentEntity p) {
    Instant now = clock.instant();
    p.nextPollAt = now.plusSeconds(cfg.pollSeconds());
    p.lastPolledAt = now;
    payments.save(p);
    return p.id;
  }

  /** Background job: checks every payment that is due. Several servers can run it: a payment claimed by one is skipped by the others. */
  @Scheduled(fixedDelayString = "${app.paytm.poll-tick:PT3S}", initialDelayString = "PT20S")
  public void pollDue() {
    if (!cfg.ready()) return;
    Instant now = clock.instant();
    List<UUID> ids = tx.execute(s -> payments.due(WATCHED, now, PageRequest.of(0, TICK_BATCH)).stream().map(this::lease).toList());
    for (UUID id : ids) {
      try {
        checkOne(id);
      } catch (RuntimeException e) {
        log.warn("Checking terminal payment {} failed: {}", id, e.getMessage());
      }
    }
  }

  private void checkOne(UUID id) {
    TerminalPaymentEntity p = payments.findById(id).orElse(null);
    if (p == null || !WATCHED.contains(p.status)) return;
    PaytmReply reply = paytm.status(txn(p));
    tx.executeWithoutResult(s -> apply(id, reply, false));
  }

  /** Applies a status answer to a payment (row locked). Final states are never changed here, except a late success on EXPIRED. */
  private void apply(UUID id, PaytmReply reply, boolean cancelAsked) {
    TerminalPaymentEntity p = payments.lockById(id).orElse(null);
    if (p == null || !WATCHED.contains(p.status)) return;
    Instant now = clock.instant();
    boolean expired = "EXPIRED".equals(p.status);
    if (cancelAsked && p.cancelRequestedAt == null) p.cancelRequestedAt = now;
    p.lastPolledAt = now;
    if (!reply.code().isEmpty()) p.resultCode = reply.code();
    Duration age = Duration.between(p.createdAt, now);
    boolean stopWatching = false;
    switch (reply.outcome()) {
      case SUCCESS -> success(p, reply, now, expired);
      case PENDING -> {
        if (!expired) {
          p.status = "PENDING";
          p.message = reply.message();
        }
      }
      case FAILED -> {
        if (expired) stopWatching = true;
        else finish(p, p.cancelRequestedAt != null ? "CANCELLED" : "FAILED", p.cancelRequestedAt != null ? "Cancelled on the machine" : reply.message(), now);
      }
      case NOT_FOUND -> {
        if (!expired && age.compareTo(Duration.ofSeconds(2L * cfg.pollSeconds())) >= 0) {
          finish(p, p.cancelRequestedAt != null ? "CANCELLED" : "FAILED", "Paytm never received this payment. Nothing was charged.", now);
        }
      }
      default -> { /* no usable answer this time: the payment keeps its state and is checked again */ }
    }
    if (OPEN.contains(p.status)) {
      if (age.compareTo(Duration.ofSeconds(cfg.waitSeconds())) >= 0) {
        p.status = "EXPIRED";
        p.finishedAt = now;
        p.message = "No payment was made in time. If the customer still pays, it will be picked up for a few minutes.";
        audit.log(null, "TERMINAL_PAYMENT_RESULT", "terminal_payment", p.id, "EXPIRED");
      }
      p.nextPollAt = now.plusSeconds(cfg.pollSeconds());
    } else if ("EXPIRED".equals(p.status)) {
      boolean watchOver = stopWatching || Duration.between(p.finishedAt, now).compareTo(LATE_WATCH) >= 0;
      p.nextPollAt = watchOver ? null : now.plusSeconds(cfg.pollSeconds());
    }
    p.updatedAt = now;
    payments.save(p);
  }

  private void success(TerminalPaymentEntity p, PaytmReply reply, Instant now, boolean late) {
    TxnDetails d = reply.details();
    if (d != null && d.amountPaise() != null && d.amountPaise() != p.amountPaise) {
      p.status = late ? "EXPIRED" : "UNKNOWN";
      p.message = "Paytm reports a payment of a different amount. Check the machine slip before doing anything.";
      log.warn("Terminal payment {} amount mismatch: asked {} got {}", p.id, p.amountPaise, d.amountPaise());
      audit.log(null, "TERMINAL_PAYMENT_RESULT", "terminal_payment", p.id, "AMOUNT_MISMATCH asked=" + p.amountPaise + " got=" + d.amountPaise());
      return;
    }
    if (d != null) {
      p.payMethod = d.payMethod();
      p.cardScheme = d.cardScheme();
      p.maskedCard = d.maskedCard();
      p.rrn = d.rrn();
      p.authCode = d.authCode();
      p.issuingBank = d.issuingBank();
      p.acquiringBank = d.acquiringBank();
      p.upiVpa = d.upiVpa();
      p.bankResponseCode = d.bankResponseCode();
      p.bankResponseMessage = d.bankResponseMessage();
      p.rawResponse = d.raw();
    }
    finish(p, "SUCCESS", "Payment received", now);
    if (late) log.warn("Terminal payment {} was paid after it expired; it is now SUCCESS", p.id);
    audit.log(null, "TERMINAL_PAYMENT_RESULT", "terminal_payment", p.id, "SUCCESS rrn=" + p.rrn + (late ? " late" : ""));
  }

  private static void finish(TerminalPaymentEntity p, String status, String message, Instant now) {
    p.status = status;
    p.message = message;
    p.finishedAt = now;
    p.nextPollAt = null;
  }

  // ---------- cancel ----------
  /**
   * Tries to cancel an unpaid payment. See the class comment for the rule.
   *
   * @throws ApiException 409 {@code ALREADY_PAID} when the customer has paid; 409 {@code CANCEL_ON_MACHINE} when the cashier must
   *     press cancel on the machine (the request is remembered)
   */
  public TerminalPaymentView cancel(UUID actor, UUID id) {
    requireReady();
    TerminalPaymentEntity p = payments.findById(id).orElseThrow(() -> ApiException.notFound("Terminal payment"));
    if ("SUCCESS".equals(p.status)) throw alreadyPaid();
    if (!OPEN.contains(p.status)) return view(p);
    PaytmTerminalService.CancelResult r = paytm.cancelPending(txn(p));
    tx.executeWithoutResult(s -> {
      apply(id, r.reply(), true);
      audit.log(actor, "TERMINAL_PAYMENT_CANCEL", "terminal_payment", id, "asked");
    });
    TerminalPaymentEntity after = payments.findById(id).orElseThrow();
    if ("SUCCESS".equals(after.status)) throw alreadyPaid();
    if (OPEN.contains(after.status)) {
      throw ApiException.conflict("CANCEL_ON_MACHINE", "Paytm cannot cancel a payment that is already on the machine. Press cancel on the machine; "
          + "this payment is closed as cancelled as soon as the machine reports it.", Map.of("paymentId", id.toString()));
    }
    return view(after);
  }

  private static ApiException alreadyPaid() {
    return ApiException.conflict("ALREADY_PAID", "The customer has already paid on the machine. Use the payment, or void it the same day.", Map.of());
  }

  // ---------- void ----------
  /**
   * Voids a paid sale of the same day (India time) that no bill has used yet.
   *
   * @throws ApiException 409 {@code NOT_VOIDABLE}, 409 {@code PAYMENT_IN_USE}, 422 {@code VOID_WINDOW_PASSED}, 422 {@code VOID_REJECTED}
   *     with Paytm's reason, 502 {@code VOID_UNKNOWN} when Paytm could not be reached
   */
  public TerminalPaymentView voidPayment(UUID actor, UUID id) {
    requireReady();
    Instant now = clock.instant();
    TerminalPaymentEntity snapshot = tx.execute(s -> {
      TerminalPaymentEntity p = payments.lockById(id).orElseThrow(() -> ApiException.notFound("Terminal payment"));
      if ("VOIDED".equals(p.status)) return p;
      if (!"SUCCESS".equals(p.status)) throw ApiException.conflict("NOT_VOIDABLE", "Only a paid card payment can be voided", Map.of());
      if (p.posSaleId != null) throw ApiException.conflict("PAYMENT_IN_USE", "A bill already used this payment. Return the bill instead.", Map.of());
      LocalDate paidOn = p.finishedAt.atZone(INDIA).toLocalDate();
      if (!paidOn.equals(now.atZone(INDIA).toLocalDate())) {
        throw ApiException.unprocessable("VOID_WINDOW_PASSED", "A payment can be voided only on the day it was taken. Refund it from the Paytm dashboard instead.", Map.of());
      }
      p.voidRequestedAt = now;
      p.updatedAt = now;
      return payments.save(p);
    });
    if ("VOIDED".equals(snapshot.status)) return view(snapshot);
    PaytmReply reply = paytm.voidSale(txn(snapshot));
    if (reply.outcome() == Outcome.NETWORK_ERROR) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "VOID_UNKNOWN", "Could not reach Paytm. Check the payment on the machine before trying the void again.", Map.of());
    }
    if (reply.outcome() != Outcome.ACCEPTED && reply.outcome() != Outcome.SUCCESS) {
      tx.executeWithoutResult(s -> {
        TerminalPaymentEntity p = payments.lockById(id).orElseThrow();
        p.voidRequestedAt = null;
        p.resultCode = reply.code();
        payments.save(p);
        audit.log(actor, "TERMINAL_PAYMENT_VOID", "terminal_payment", id, "rejected " + reply.internalCode());
      });
      throw ApiException.unprocessable("VOID_REJECTED", reply.message(),
          Map.of("fields", Map.of("id", reply.message()), "reason", reply.internalCode(), "paytmCode", reply.code()));
    }
    PaytmReply check = paytm.status(txn(snapshot));
    return tx.execute(s -> {
      TerminalPaymentEntity p = payments.lockById(id).orElseThrow();
      Instant t = clock.instant();
      p.status = "VOIDED";
      p.voidedAt = t;
      p.finishedAt = p.finishedAt == null ? t : p.finishedAt;
      p.resultCode = reply.code();
      p.message = check.outcome() == Outcome.SUCCESS
          ? "Paytm accepted the void. Make sure the machine shows it as voided."
          : "Paytm accepted the void.";
      p.updatedAt = t;
      payments.save(p);
      audit.log(actor, "TERMINAL_PAYMENT_VOID", "terminal_payment", id, "accepted amount=" + p.amountPaise);
      return view(p);
    });
  }

  // ---------- using a payment on a bill ----------
  /**
   * The paid payment, for code that only needs to read it.
   *
   * @throws ApiException 422 {@code TERMINAL_PAYMENT_NOT_PAID} when it is not SUCCESS, 409 {@code TERMINAL_PAYMENT_USED} when a bill has it
   */
  @Transactional(readOnly = true)
  public Confirmed confirmedPayment(UUID id) {
    TerminalPaymentEntity p = payments.findById(id).orElseThrow(() -> ApiException.validation("terminalPaymentId", "unknown card machine payment"));
    return confirmed(p, "terminalPaymentId", false);
  }

  /**
   * Attaches a paid payment to a bill inside the bill's transaction: it must be SUCCESS, unused, not being voided, of the same
   * amount, and its method must fit the line's mode (card methods for CARD, UPI for UPI).
   *
   * @param field the request field named in errors, for example {@code payments[1].terminalPaymentId}
   */
  @Transactional
  public Confirmed attach(UUID id, UUID saleId, long amountPaise, String mode, String field, UUID actor) {
    TerminalPaymentEntity p = payments.lockById(id).orElseThrow(() -> ApiException.validation(field, "unknown card machine payment"));
    Confirmed c = confirmed(p, field, true);
    if (p.amountPaise != amountPaise) {
      throw ApiException.unprocessable("TERMINAL_PAYMENT_AMOUNT", "The card machine took Rs " + p.amountPaise / 100.0 + " but this line is for a different amount",
          Map.of("fields", Map.of(field, "amount must equal the card machine payment of " + p.amountPaise + " paise")));
    }
    boolean upi = p.payMethod != null && p.payMethod.toUpperCase().contains("UPI");
    boolean card = p.payMethod != null && !upi;
    if ((upi && !"UPI".equals(mode)) || (card && !"CARD".equals(mode))) {
      throw ApiException.unprocessable("TERMINAL_PAYMENT_MODE", "This payment was taken by " + p.payMethod + ": use mode " + (upi ? "UPI" : "CARD"),
          Map.of("fields", Map.of(field, "mode does not fit how the customer paid")));
    }
    p.posSaleId = saleId;
    p.updatedAt = clock.instant();
    payments.save(p);
    audit.log(actor, "TERMINAL_PAYMENT_ATTACH", "terminal_payment", id, "sale=" + saleId);
    return c;
  }

  private Confirmed confirmed(TerminalPaymentEntity p, String field, boolean forUse) {
    if (!"SUCCESS".equals(p.status) || p.voidRequestedAt != null) {
      throw ApiException.unprocessable("TERMINAL_PAYMENT_NOT_PAID", "This card machine payment is not paid (it is " + p.status + ")",
          Map.of("fields", Map.of(field, "payment is not paid")));
    }
    if (p.posSaleId != null || p.orderId != null) {
      throw ApiException.conflict("TERMINAL_PAYMENT_USED", "This card machine payment was already used for another bill",
          Map.of("fields", Map.of(field, "payment already used")));
    }
    String ref = p.rrn != null ? "PAYTM RRN " + p.rrn + (p.authCode != null ? " AUTH " + p.authCode : "") : "PAYTM " + p.merchantTransactionId;
    return new Confirmed(p.id, p.amountPaise, p.payMethod, p.cardScheme, p.maskedCard, p.rrn, p.authCode, p.terminalId, ref);
  }

  // ---------- view ----------
  private TerminalPaymentView view(TerminalPaymentEntity p) {
    boolean open = OPEN.contains(p.status);
    Instant now = clock.instant();
    boolean canVoid = "SUCCESS".equals(p.status) && p.posSaleId == null && p.voidRequestedAt == null && p.finishedAt != null
        && p.finishedAt.atZone(INDIA).toLocalDate().equals(now.atZone(INDIA).toLocalDate());
    String name = terminals.findById(p.paymentTerminalId).map(t -> t.name).orElse(p.terminalId);
    return new TerminalPaymentView(p.id, p.paymentTerminalId, p.terminalId, name, p.amountPaise, p.reference, p.status, open, p.message,
        p.resultCode, p.payMethod, p.cardScheme, p.maskedCard, p.rrn, p.authCode, p.issuingBank, maskVpa(p.upiVpa), p.posSaleId, open,
        canVoid, cfg.pollSeconds(), p.createdAt, p.finishedAt);
  }

  /** Keeps the first two characters of a UPI address and the handle: {@code ab***@okbank}. */
  static String maskVpa(String vpa) {
    if (vpa == null) return null;
    int at = vpa.indexOf('@');
    if (at < 0) return vpa.length() <= 2 ? "***" : vpa.substring(0, 2) + "***";
    return (at <= 2 ? vpa.substring(0, Math.min(1, at)) : vpa.substring(0, 2)) + "***" + vpa.substring(at);
  }
}
