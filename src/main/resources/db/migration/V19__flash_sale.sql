-- Flash sales: a timed offer on chosen products, with a default discount that each item may override, a total quantity cap per
-- item and a per-customer limit. The status (scheduled / active / ended / cancelled) is never stored: it is worked out from the
-- clock and cancelled_at. Every unit sold or given back is recorded in flash_consumption (reports and the per-customer limit).
CREATE TABLE flash_sale (
  id uuid PRIMARY KEY,
  name text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 80),
  description text NOT NULL DEFAULT '' CHECK (char_length(description) <= 300),
  banner_text text NOT NULL DEFAULT '' CHECK (char_length(banner_text) <= 80),
  starts_at timestamptz NOT NULL,
  ends_at timestamptz NOT NULL,
  channel text NOT NULL CHECK (channel IN ('ONLINE', 'POS', 'BOTH')),
  discount_kind text NOT NULL CHECK (discount_kind IN ('PERCENT', 'FIXED_PRICE', 'NONE')),
  discount_value bigint NOT NULL DEFAULT 0 CHECK (discount_value >= 0),
  per_customer_limit int NOT NULL DEFAULT 0 CHECK (per_customer_limit >= 0),
  cancelled_at timestamptz,
  started_notified_at timestamptz,
  ended_notified_at timestamptz,
  created_by uuid,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CHECK (ends_at >= starts_at)
);
CREATE INDEX flash_sale_window ON flash_sale (starts_at, ends_at);

CREATE TABLE flash_sale_item (
  id uuid PRIMARY KEY,
  flash_sale_id uuid NOT NULL REFERENCES flash_sale(id) ON DELETE CASCADE,
  product_id uuid NOT NULL REFERENCES product(id),
  discount_kind text CHECK (discount_kind IN ('PERCENT', 'FIXED_PRICE', 'NONE')),
  discount_value bigint NOT NULL DEFAULT 0 CHECK (discount_value >= 0),
  max_qty int CHECK (max_qty IS NULL OR max_qty > 0),
  sold_qty int NOT NULL DEFAULT 0 CHECK (sold_qty >= 0),
  per_customer_limit int CHECK (per_customer_limit IS NULL OR per_customer_limit >= 0),
  sold_out_notified_at timestamptz,
  created_at timestamptz NOT NULL,
  UNIQUE (flash_sale_id, product_id),
  CHECK (max_qty IS NULL OR sold_qty <= max_qty)
);
CREATE INDEX flash_sale_item_product ON flash_sale_item (product_id);

-- One row per order / counter bill line that used the sale. released_at is set when the order is cancelled (units given back).
CREATE TABLE flash_consumption (
  id uuid PRIMARY KEY,
  flash_item_id uuid NOT NULL REFERENCES flash_sale_item(id),
  flash_sale_id uuid NOT NULL,
  product_id uuid NOT NULL,
  channel text NOT NULL CHECK (channel IN ('ONLINE', 'POS')),
  order_id uuid,
  pos_sale_id uuid,
  user_id uuid,
  qty int NOT NULL CHECK (qty > 0),
  unit_price_paise bigint NOT NULL,
  list_price_paise bigint NOT NULL,
  discount_paise bigint NOT NULL,
  created_at timestamptz NOT NULL,
  released_at timestamptz,
  CHECK ((order_id IS NOT NULL) <> (pos_sale_id IS NOT NULL))
);
CREATE INDEX flash_consumption_sale ON flash_consumption (flash_sale_id, created_at);
CREATE INDEX flash_consumption_item_user ON flash_consumption (flash_item_id, user_id) WHERE released_at IS NULL;
CREATE INDEX flash_consumption_order ON flash_consumption (order_id) WHERE order_id IS NOT NULL;
CREATE INDEX flash_consumption_pos ON flash_consumption (pos_sale_id) WHERE pos_sale_id IS NOT NULL;
