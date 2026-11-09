package com.vijay.mall.pos.domain;

/** Why an offline bill needs a manager's look. The bill is recorded in every case. */
public enum OfflineExceptionKind {
    /** The till charged a different unit price than the catalog has now. */
    PRICE_CHANGED,
    /** The till used a different GST rate than the product has now. */
    GST_CHANGED,
    /** The system had less stock than the bill sold; what was there was taken. */
    OVERSOLD,
    /** A line discount or a manual discount, whose manager PIN could not be checked offline. */
    DISCOUNT,
    /** The product has been switched off since the till downloaded it. */
    PRODUCT_INACTIVE
}
