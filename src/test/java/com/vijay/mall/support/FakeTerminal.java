package com.vijay.mall.support;

import com.vijay.mall.payment.domain.CardTerminal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A card terminal that reports results itself, used only in tests: tests set the state a reference will report. */
public class FakeTerminal implements CardTerminal {
  public final Map<String, Session> sessions = new ConcurrentHashMap<>();
  public volatile boolean configured = true;

  @Override public String provider() { return "faketerm"; }

  @Override public boolean configured() { return configured; }

  @Override
  public Session requestPayment(long amountPaise, String reference) {
    Session s = new Session(reference, State.PENDING, Mode.QR, "Customer scans the QR", "upi://pay?am=" + amountPaise, "https://qr.test/" + reference, null, null);
    sessions.put(reference, s);
    return s;
  }

  @Override public Session status(String reference) { return sessions.get(reference); }

  @Override
  public Session cancel(String reference) {
    Session s = new Session(reference, State.CANCELLED, Mode.QR, null, null, null, null, null);
    sessions.put(reference, s);
    return s;
  }

  /** Makes the terminal report this outcome for the reference. */
  public void finish(String reference, State state, String approval, String rrn) {
    sessions.put(reference, new Session(reference, state, Mode.QR, null, null, null, approval, rrn));
  }
}
