package com.vijay.mall.payment.infra;

import com.vijay.mall.payment.domain.CardTerminal;
import org.springframework.stereotype.Component;

/**
 * The terminal with no vendor API: the cashier charges the amount on the machine and types the approval code or RRN into the till. It
 * keeps no state; the till's session row holds the outcome, so {@link #status} always answers "pending".
 */
@Component
public class ManualTerminal implements CardTerminal {

  @Override public String provider() { return "manual"; }

  @Override public boolean manualConfirmation() { return true; }

  @Override
  public Session requestPayment(long amountPaise, String reference) {
    return new Session(reference, State.PENDING, Mode.MANUAL, "Charge Rs " + amountPaise / 100 + "." + String.format("%02d", amountPaise % 100)
        + " on the card machine, then enter the approval code or RRN here.", null, null, null, null);
  }

  @Override public Session status(String reference) { return new Session(reference, State.PENDING, Mode.MANUAL, null, null, null, null, null); }

  @Override public Session cancel(String reference) { return new Session(reference, State.CANCELLED, Mode.MANUAL, null, null, null, null, null); }
}
