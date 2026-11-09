package com.vijay.mall.payment.paytm.app;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.payment.domain.CardTerminal;
import com.vijay.mall.payment.paytm.app.TerminalPaymentService.Confirmed;
import com.vijay.mall.payment.paytm.app.TerminalPaymentService.TerminalPaymentView;
import com.vijay.mall.payment.paytm.infra.PaymentTerminalEntity;
import com.vijay.mall.payment.paytm.infra.PaymentTerminalJpa;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The Paytm wireless machine as a {@link CardTerminal} of the till: it keeps no table of its own but delegates to
 * {@link TerminalPaymentService}, so a payment started from the generic till endpoint and one started from the Paytm-specific endpoint
 * are the same row. The session id the till gets back is the Paytm payment id.
 *
 * <p>The till's reference is the key of the payment: it is sent to {@link TerminalPaymentService#start} as the idempotency key
 * {@code pos:<reference>}, so a repeated start returns the first payment and {@link #status} and {@link #cancel(String)} find it again.
 */
@Component
public class PaytmCardTerminal implements CardTerminal {
  private static final Pattern LAST4 = Pattern.compile("(\\d{4})$");

  private final TerminalPaymentService payments;
  private final PaymentTerminalJpa terminals;
  private final PaytmSettings cfg;

  /** Creates the adapter. */
  public PaytmCardTerminal(TerminalPaymentService payments, PaymentTerminalJpa terminals, PaytmSettings cfg) {
    this.payments = payments;
    this.terminals = terminals;
    this.cfg = cfg;
  }

  /** The idempotency key the generic flow uses for a till reference. */
  public static String idemKey(String reference) { return "pos:" + reference; }

  @Override public String provider() { return PaymentTerminalService.PROVIDER; }

  @Override public boolean configured() { return cfg.ready(); }

  @Override
  public Session requestPayment(long amountPaise, String reference) {
    throw ApiException.validation("moneyAccountId", "choose the card machine to charge");
  }

  @Override
  public Session requestPayment(Request r) {
    PaymentTerminalEntity t = machine(r);
    return session(payments.start(r.actor(), idemKey(r.reference()), t.id, r.amountPaise(), r.reference()));
  }

  private PaymentTerminalEntity machine(Request r) {
    if (r.moneyAccountId() != null) {
      return terminals.findByMoneyAccountId(r.moneyAccountId()).orElseThrow(() -> ApiException.validation("moneyAccountId", "this account is not a Paytm card machine"));
    }
    if (r.terminalId() != null && !r.terminalId().isBlank()) {
      return terminals.findByProviderAndTerminalId(provider(), r.terminalId().strip()).orElseThrow(() -> ApiException.validation("terminalId", "unknown card machine"));
    }
    List<PaymentTerminalEntity> active = terminals.findByProviderAndActiveTrueOrderByNameAsc(provider());
    if (active.size() != 1) throw ApiException.validation("moneyAccountId", "choose which card machine to charge");
    return active.get(0);
  }

  @Override
  public Session status(String reference) {
    TerminalPaymentView v = payments.byIdemKey(idemKey(reference)).orElseThrow(() -> ApiException.notFound("Terminal payment"));
    return session(payments.get(v.id()));
  }

  @Override
  public Session cancel(String reference) {
    TerminalPaymentView v = payments.byIdemKey(idemKey(reference)).orElseThrow(() -> ApiException.notFound("Terminal payment"));
    return session(payments.cancel(null, v.id()));
  }

  @Override public Optional<Session> find(UUID sessionId) { return payments.find(sessionId).map(this::session); }

  @Override public Session cancel(UUID actor, UUID sessionId) { return session(payments.cancel(actor, sessionId)); }

  @Override public Session voidPayment(UUID actor, UUID sessionId) { return session(payments.voidPayment(actor, sessionId)); }

  @Override
  public Optional<Attachment> attach(UUID sessionId, UUID saleId, long amountPaise, String mode, String field, UUID actor) {
    if (!payments.exists(sessionId)) return Optional.empty();
    Confirmed c = payments.attach(sessionId, saleId, amountPaise, mode, field, actor);
    Map<String, String> d = new LinkedHashMap<>();
    put(d, "provider", provider());
    put(d, "paymentId", c.id().toString());
    put(d, "terminalId", c.terminalId());
    put(d, "payMethod", c.payMethod());
    put(d, "cardScheme", c.cardScheme());
    put(d, "maskedCard", c.maskedCard());
    if (c.maskedCard() != null) {
      Matcher m = LAST4.matcher(c.maskedCard());
      if (m.find()) put(d, "cardLast4", m.group(1));
    }
    put(d, "issuingBank", c.issuingBank());
    put(d, "authCode", c.authCode());
    put(d, "upiVpa", c.upiVpa());
    put(d, "rrn", c.rrn());
    return Optional.of(new Attachment(c.reference(), account(c.terminalRowId()), c.terminalId(), d));
  }

  private static void put(Map<String, String> d, String key, String value) {
    if (value != null && !value.isBlank()) d.put(key, value);
  }

  private UUID account(UUID terminalRowId) { return terminals.findById(terminalRowId).map(t -> t.moneyAccountId).orElse(null); }

  /** Maps a Paytm payment to the generic session: SUCCESS is APPROVED, FAILED is DECLINED, VOIDED is CANCELLED; the Paytm word stays in the detail. */
  Session session(TerminalPaymentView v) {
    State state = switch (v.status()) {
      case "SUCCESS" -> State.APPROVED;
      case "FAILED" -> State.DECLINED;
      case "CANCELLED", "VOIDED" -> State.CANCELLED;
      case "EXPIRED" -> State.EXPIRED;
      default -> State.PENDING;
    };
    Detail detail = new Detail(v.amountPaise(), account(v.terminalRowId()), v.status(), v.message(), v.terminalId(), v.terminalName(), v.payMethod(),
        v.cardScheme(), v.maskedCard(), v.issuingBank(), v.upiVpa(), v.canCancel(), v.cancelOnMachine(), v.canVoid(), v.pollAfterSeconds(),
        v.posSaleId(), v.createdAt(), v.finishedAt());
    return new Session(v.id(), v.reference(), state, Mode.PUSH, v.open() ? v.message() : null, null, null, v.authCode(), v.rrn(), detail);
  }
}
