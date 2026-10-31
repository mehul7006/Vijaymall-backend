-- Indexes for lookups added after the first review.
CREATE INDEX coupon_redemption_order ON coupon_redemption (order_id);
CREATE INDEX orders_pending_expiry ON orders (expires_at) WHERE status = 'PAYMENT_PENDING';
CREATE INDEX payment_open ON payment (created_at) WHERE status = 'CREATED';
CREATE INDEX stock_movement_product ON stock_movement (product_id, created_at DESC);
CREATE INDEX coin_ledger_order ON coin_ledger (order_id) WHERE order_id IS NOT NULL;
