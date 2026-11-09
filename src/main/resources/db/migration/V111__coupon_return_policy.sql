-- What a return needs to know about the coupon the order used, so a later change of the coupon cannot change a refund:
--  * the coupon's minimum (covered value) at order time, for the CLAWBACK_BELOW_MINIMUM return policy (0 = none / older orders);
--  * the coupon amount taken back from a refund by that policy, per return;
--  * the coupon share of each counter bill line (online order lines already keep theirs in order_item.coupon_discount_paise).
ALTER TABLE orders ADD COLUMN coupon_min_order_paise bigint NOT NULL DEFAULT 0;
ALTER TABLE order_return ADD COLUMN coupon_clawback_paise bigint NOT NULL DEFAULT 0;
ALTER TABLE pos_sale ADD COLUMN coupon_min_order_paise bigint NOT NULL DEFAULT 0;
ALTER TABLE pos_sale_item ADD COLUMN coupon_share_paise bigint NOT NULL DEFAULT 0;
ALTER TABLE pos_return ADD COLUMN coupon_clawback_paise bigint NOT NULL DEFAULT 0;

-- The coupon share that left with the returned pieces, per returned line (what the preview showed), for credit notes and audits.
ALTER TABLE order_return_item ADD COLUMN coupon_share_paise bigint NOT NULL DEFAULT 0;
ALTER TABLE pos_return_item ADD COLUMN coupon_share_paise bigint NOT NULL DEFAULT 0;
