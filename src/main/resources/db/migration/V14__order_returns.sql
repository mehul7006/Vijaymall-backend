-- Returns and missing/damaged items on delivered online orders. Each return refunds the returned items' share of what the
-- customer really paid (after coupon and coins), optionally puts the goods back on the shelf, and adjusts coins.
ALTER TABLE order_item ADD COLUMN returned_qty int NOT NULL DEFAULT 0 CHECK (returned_qty >= 0);

CREATE TABLE order_return (
  id uuid PRIMARY KEY,
  order_id uuid NOT NULL REFERENCES orders(id),
  idem_key text NOT NULL UNIQUE,
  reason text NOT NULL,
  refund_paise bigint NOT NULL CHECK (refund_paise >= 0),
  refund_mode text NOT NULL,
  coins_clawed_back int NOT NULL DEFAULT 0,
  coins_restored int NOT NULL DEFAULT 0,
  created_by uuid REFERENCES users(id),
  created_at timestamptz NOT NULL
);
CREATE INDEX order_return_order ON order_return (order_id);

CREATE TABLE order_return_item (
  id uuid PRIMARY KEY,
  return_id uuid NOT NULL REFERENCES order_return(id),
  order_item_id uuid NOT NULL REFERENCES order_item(id),
  qty int NOT NULL CHECK (qty > 0),
  refund_paise bigint NOT NULL CHECK (refund_paise >= 0),
  restocked boolean NOT NULL
);
CREATE INDEX order_return_item_return ON order_return_item (return_id);
