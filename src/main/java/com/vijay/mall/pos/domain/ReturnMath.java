package com.vijay.mall.pos.domain;

/** Exact shares for partial returns: the pieces of every return add up to the whole, never more. */
public final class ReturnMath {
    private ReturnMath() { }

    /** amount * num / den rounded half up. */
    public static long roundShare(long amount, long num, long den) {
        if (den <= 0 || num < 0 || num > den) {
            throw new IllegalArgumentException("bad share " + num + "/" + den);
        }
        return Math.floorDiv(Math.multiplyExact(amount, num) * 2 + den, 2 * den);
    }

    /**
     * The part of {@code amount} that belongs to {@code returning} units, given {@code alreadyReturned} units came back
     * before. Computed as a difference of cumulative shares, so many small returns sum to exactly the full amount.
     */
    public static long shareOfReturn(long amount, int sold, int alreadyReturned, int returning) {
        return roundShare(amount, alreadyReturned + returning, sold) - roundShare(amount, alreadyReturned, sold);
    }

    /** Coins (rounded down) for a cumulative share of the bill, minus what was already moved by earlier returns. */
    public static int coinsDelta(int coins, long returnedGross, long subtotal, int alreadyMoved) {
        if (coins <= 0 || subtotal <= 0) {
            return 0;
        }
        long cumulative = Math.floorDiv(Math.multiplyExact((long) coins, returnedGross), subtotal);
        return (int) Math.max(0, cumulative - alreadyMoved);
    }
}
