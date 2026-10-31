package com.vijay.mall.pos.domain;

/** How a product is sold at the counter. Quantities are always integers in the base unit. */
public enum SoldBy {
    /** Quantity is a count of pieces. */
    UNIT,
    /** Quantity is grams; the product price is per 1000 g. */
    WEIGHT;

    /** Line amount in paise for {@code qty} base units at {@code price} paise (per piece, or per kg). */
    public long lineAmount(long price, int qty) {
        return switch (this) {
            case UNIT -> Math.multiplyExact(price, (long) qty);
            case WEIGHT -> Math.floorDiv(Math.multiplyExact(price, (long) qty) + 500, 1000L);
        };
    }

    /** Base units (pieces or grams) that {@code amount} paise buys at {@code price}; weighed labels that carry a price. */
    public long qtyForAmount(long price, long amount) {
        if (price <= 0) {
            throw new IllegalArgumentException("price must be positive");
        }
        return switch (this) {
            case UNIT -> amount / price;
            case WEIGHT -> Math.floorDiv(Math.multiplyExact(amount, 1000L) + price / 2, price);
        };
    }
}
