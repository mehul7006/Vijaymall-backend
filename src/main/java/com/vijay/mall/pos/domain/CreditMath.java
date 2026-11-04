package com.vijay.mall.pos.domain;

/** The arithmetic of customer credit. All amounts are paise. */
public final class CreditMath {
    private CreditMath() { }

    /** What the customer can still buy on credit: the limit minus what they already owe (never negative). */
    public static long available(long limit, long balance) {
        return Math.max(0, limit - balance);
    }

    /** True when {@code charge} more still keeps the balance within the limit. */
    public static boolean fits(long limit, long balance, long charge) {
        return charge <= available(limit, balance);
    }

    /**
     * How much of a refund simply reduces what the customer owes. It cannot be more than the refund, more than the
     * credit this bill put on their account that earlier returns have not already cancelled, or more than they owe now
     * (anything beyond that was already paid back, so it goes out as money).
     */
    public static long returnAdjustment(long creditOnBill, long alreadyAdjusted, long refund, long balance) {
        long open = Math.max(0, creditOnBill - alreadyAdjusted);
        return Math.max(0, Math.min(refund, Math.min(open, balance)));
    }
}
