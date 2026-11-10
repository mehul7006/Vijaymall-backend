-- The GST split of each online order line, frozen at order time (prices include GST). Old orders keep NULL: nothing is guessed for them.
ALTER TABLE order_item
    ADD COLUMN gst_percent   numeric(6,3),
    ADD COLUMN hsn           text,
    ADD COLUMN taxable_paise bigint,
    ADD COLUMN cgst_paise    bigint,
    ADD COLUMN sgst_paise    bigint;
