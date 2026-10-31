package com.vijay.mall.pos.domain;

import java.util.List;
import java.util.Set;

/** Checks the way a bill (or a refund) is paid. Breaking a rule throws IllegalArgumentException with a readable message. */
public final class PaymentRules {
    public static final Set<String> MODES = Set.of("CASH", "UPI", "CARD");

    private PaymentRules() { }

    public record Line(String mode, long amountPaise, String reference) { }

    /**
     * Payments must add up exactly to the amount. For a zero bill there must be none. When the customer hands over
     * more cash than the cash share, the difference is the change.
     *
     * @return change to give back in paise (0 when no tendered cash was given)
     */
    public static long change(List<Line> lines, long total, Long cashTendered) {
        checkLines(lines);
        long sum = lines.stream().mapToLong(Line::amountPaise).sum();
        if (total == 0 && !lines.isEmpty()) {
            throw new IllegalArgumentException("nothing to pay on this bill");
        }
        if (sum != total) {
            throw new IllegalArgumentException("payments add up to " + Money.plain(sum) + " but the bill is " + Money.plain(total));
        }
        if (cashTendered == null) {
            return 0;
        }
        long cash = lines.stream().filter(l -> "CASH".equals(l.mode())).mapToLong(Line::amountPaise).sum();
        if (cash == 0) {
            throw new IllegalArgumentException("cash tendered given but no cash payment");
        }
        if (cashTendered < cash) {
            throw new IllegalArgumentException("cash tendered is less than the cash payment");
        }
        return cashTendered - cash;
    }

    /** Same checks for a refund (no change handling). */
    public static void checkRefund(List<Line> lines, long refund) {
        checkLines(lines);
        long sum = lines.stream().mapToLong(Line::amountPaise).sum();
        if (sum != refund) {
            throw new IllegalArgumentException("refunds add up to " + Money.plain(sum) + " but the amount is " + Money.plain(refund));
        }
    }

    private static void checkLines(List<Line> lines) {
        lines.forEach(l -> {
            if (!MODES.contains(l.mode())) {
                throw new IllegalArgumentException("payment mode must be CASH, UPI or CARD");
            }
            if (l.amountPaise() <= 0) {
                throw new IllegalArgumentException("payment amounts must be positive");
            }
        });
    }
}
