-- 1. Online orders take stock when staff CONFIRM them, not when they are placed (the item may have been sold at the
--    counter in the meantime). stock_reserved says whether this order currently holds stock, so it is given back
--    exactly once on cancel. Orders that exist today were placed under the old rule and already hold their stock.
ALTER TABLE orders ADD COLUMN stock_reserved boolean NOT NULL DEFAULT false;
UPDATE orders SET stock_reserved = true WHERE status <> 'CANCELLED';

-- 2. Refunds are queued in the same transaction as the cancel and sent to the payment provider afterwards,
--    so a slow or failed provider call can never block or half-undo a cancel.
ALTER TABLE refund ADD COLUMN attempts int NOT NULL DEFAULT 0;
ALTER TABLE refund ADD COLUMN last_error text;
ALTER TABLE refund ADD COLUMN updated_at timestamptz;
CREATE INDEX refund_open ON refund (created_at) WHERE status IN ('PENDING', 'PROCESSING');
