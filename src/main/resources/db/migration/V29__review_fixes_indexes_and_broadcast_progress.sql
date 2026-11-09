-- Indexes for lookups the segment / push / audience queries run once per user (measured on 255k orders and 41k users: a HAS_DEVICE count
-- over 10,001 customers went from 2.8 s to 26 ms), and the progress columns a broadcast needs to be resumed and reported honestly.
-- Plain CREATE INDEX locks the table while it builds; on a big database create them first with CREATE INDEX CONCURRENTLY IF NOT EXISTS.

CREATE INDEX IF NOT EXISTS device_token_user ON device_token (user_id);
CREATE INDEX IF NOT EXISTS product_review_user ON product_review (user_id);
CREATE INDEX IF NOT EXISTS order_item_product ON order_item (product_id);
CREATE INDEX IF NOT EXISTS pos_sale_item_product ON pos_sale_item (product_id);

-- The last user whose batch has been pushed (a batch's inbox rows commit first; this marks that its push was also handed over).
ALTER TABLE notification_broadcast ADD COLUMN pushed_through uuid;
-- Batches in which at least one push call failed.
ALTER TABLE notification_broadcast ADD COLUMN push_failed_batches int NOT NULL DEFAULT 0;
-- False while recipients is only an estimate (a big segment is counted up to a cap); the real total is what was delivered.
ALTER TABLE notification_broadcast ADD COLUMN recipients_exact boolean NOT NULL DEFAULT true;
