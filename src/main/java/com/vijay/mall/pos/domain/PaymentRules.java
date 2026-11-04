package com.vijay.mall.pos.domain;

import java.util.List;
import java.util.Set;

/** Checks the way a bill (or a refund) is paid. Breaking a rule throws IllegalArgumentException with a readable message. */
public final class PaymentRules {
    /** Ways money changes hands: bills, refunds and credit repayments. */
    public static final Set<String> MODES = Set.of("CASH", "UPI", "CARD");

    /** Pay later: the customer owes the shop. Only bills can use it, never refunds or repayments. */
    public static final String CREDIT = "CREDIT";

    private PaymentRules() { }

    public record Line(String mode, long amountPaise, String reference) { }

    /**
     * Payments must add up exactly to the amount. For a zero bill there must be none. When the customer hands over
     * more cash than the cash share, the difference is the change.
     *
     * @return change to give back in paise (0 when no tendered cash was given)
     */
    public static long change(List<Line> lines, long total, Long cashTendered) {
        checkLines(lines, true);
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
        checkLines(lines, false);
        long sum = lines.stream().mapToLong(Line::amountPaise).sum();
        if (sum != refund) {
            throw new IllegalArgumentException("refunds add up to " + Money.plain(sum) + " but the amount is " + Money.plain(refund));
        }
    }

    /** The part of a bill left on credit (0 when none). */
    public static long creditAmount(List<Line> lines) {
        return lines.stream().filter(l -> CREDIT.equals(l.mode())).mapToLong(Line::amountPaise).sum();
    }

    /**
     * Names how a bill was paid, for reports: NONE (nothing to pay), CASH / UPI / CARD (one mode), SPLIT (several
     * ways, none on credit), CREDIT (all on credit) or HYBRID (part on credit, the rest paid now).
     */
    public static String classify(List<Line> lines) {
        if (lines.isEmpty()) {
            return "NONE";
        }
        boolean credit = lines.stream().anyMatch(l -> CREDIT.equals(l.mode()));
        long others = lines.stream().filter(l -> !CREDIT.equals(l.mode())).map(Line::mode).distinct().count();
        if (credit) {
            return others == 0 ? "CREDIT" : "HYBRID";
        }
        return others == 1 ? lines.get(0).mode() : "SPLIT";
    }

    private static void checkLines(List<Line> lines, boolean allowCredit) {
        if (allowCredit && lines.stream().filter(l -> CREDIT.equals(l.mode())).count() > 1) {
            throw new IllegalArgumentException("only one credit payment is allowed on a bill");
        }
        lines.forEach(l -> {
            boolean ok = MODES.contains(l.mode()) || (allowCredit && CREDIT.equals(l.mode()));
            if (!ok) {
                throw new IllegalArgumentException(allowCredit
                    ? "payment mode must be CASH, UPI or CARD (or CREDIT on a bill)" : "payment mode must be CASH, UPI or CARD");
            }
            if (l.amountPaise() <= 0) {
                throw new IllegalArgumentException("payment amounts must be positive");
            }
        });
    }
}
