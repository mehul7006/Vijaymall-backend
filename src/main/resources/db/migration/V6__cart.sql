-- Server-side basket: one row per user plus its lines. Version number leaves room for V4/V5.
CREATE TABLE cart (
  user_id      uuid PRIMARY KEY REFERENCES users(id),
  coupon_code  text,
  coin_mode    text NOT NULL DEFAULT 'NONE' CHECK (coin_mode IN ('NONE','ALL','PERCENT')),
  coin_percent int CHECK (coin_percent BETWEEN 0 AND 100),
  updated_at   timestamptz NOT NULL
);

CREATE TABLE cart_item (
  user_id    uuid NOT NULL REFERENCES users(id),
  product_id uuid NOT NULL REFERENCES product(id),
  qty        int  NOT NULL CHECK (qty > 0),
  position   int  NOT NULL,
  updated_at timestamptz NOT NULL,
  PRIMARY KEY (user_id, product_id)
);

CREATE INDEX cart_item_user ON cart_item (user_id, position);
