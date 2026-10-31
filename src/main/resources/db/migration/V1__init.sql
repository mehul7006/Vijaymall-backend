-- Vijay Super Mall schema. Money in paise (bigint). Times are timestamptz (UTC).

CREATE TABLE users (
  id uuid PRIMARY KEY,
  email text NOT NULL UNIQUE,
  name text,
  mobile text,
  role text NOT NULL CHECK (role IN ('CUSTOMER','ADMIN')),
  status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','BLOCKED')),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL
);

CREATE TABLE otp_challenge (
  id uuid PRIMARY KEY,
  email text NOT NULL,
  otp_hash text NOT NULL,
  expires_at timestamptz NOT NULL,
  attempts int NOT NULL DEFAULT 0,
  consumed boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL
);
CREATE INDEX otp_challenge_email ON otp_challenge (email, created_at DESC);

CREATE TABLE refresh_token (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(id),
  token_hash text NOT NULL UNIQUE,
  device_id text,
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  created_at timestamptz NOT NULL
);
CREATE INDEX refresh_token_user ON refresh_token (user_id);

CREATE TABLE device_token (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(id),
  fcm_token text NOT NULL UNIQUE,
  platform text NOT NULL,
  created_at timestamptz NOT NULL
);

CREATE TABLE address (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(id),
  label text NOT NULL,
  line1 text NOT NULL,
  area text NOT NULL,
  pincode text NOT NULL,
  is_default boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL
);
CREATE INDEX address_user ON address (user_id);

CREATE TABLE category (
  id uuid PRIMARY KEY,
  name_en text NOT NULL,
  name_gu text NOT NULL,
  image_url text,
  sort_order int NOT NULL DEFAULT 0,
  active boolean NOT NULL DEFAULT true
);

CREATE TABLE product (
  id uuid PRIMARY KEY,
  category_id uuid NOT NULL REFERENCES category(id),
  name_en text NOT NULL,
  name_gu text NOT NULL,
  description text NOT NULL DEFAULT '',
  mrp_paise bigint NOT NULL CHECK (mrp_paise >= 0),
  price_paise bigint NOT NULL CHECK (price_paise >= 0),
  unit text NOT NULL DEFAULT 'pc',
  pack_size text NOT NULL DEFAULT '',
  image_url text,
  active boolean NOT NULL DEFAULT true,
  hot_sale boolean NOT NULL DEFAULT false,
  hot_start timestamptz,
  hot_end timestamptz,
  earns_coins boolean NOT NULL DEFAULT true,
  coin_redeemable boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL
);
CREATE INDEX product_category ON product (category_id, active);
CREATE INDEX product_hot ON product (hot_sale, active);

CREATE TABLE hot_banner (
  id uuid PRIMARY KEY,
  title text NOT NULL,
  subtitle text NOT NULL DEFAULT '',
  image_url text,
  start_at timestamptz,
  end_at timestamptz,
  active boolean NOT NULL DEFAULT true
);

CREATE TABLE inventory (
  product_id uuid PRIMARY KEY REFERENCES product(id),
  qty_on_hand int NOT NULL CHECK (qty_on_hand >= 0),
  low_threshold int NOT NULL DEFAULT 5,
  updated_at timestamptz NOT NULL
);

CREATE TABLE stock_movement (
  id uuid PRIMARY KEY,
  product_id uuid NOT NULL REFERENCES product(id),
  delta int NOT NULL,
  reason text NOT NULL,
  ref_id uuid,
  actor_id uuid,
  created_at timestamptz NOT NULL
);

CREATE TABLE coupon (
  id uuid PRIMARY KEY,
  code text NOT NULL UNIQUE,
  kind text NOT NULL CHECK (kind IN ('FLAT','PERCENT')),
  value bigint NOT NULL CHECK (value > 0),
  min_order_paise bigint NOT NULL DEFAULT 0,
  max_discount_paise bigint NOT NULL DEFAULT 0,
  category_id uuid REFERENCES category(id),
  per_user_limit int NOT NULL DEFAULT 0,
  total_limit int NOT NULL DEFAULT 0,
  used_count int NOT NULL DEFAULT 0,
  start_at timestamptz,
  end_at timestamptz,
  active boolean NOT NULL DEFAULT true
);

CREATE TABLE coin_rule (
  id uuid PRIMARY KEY,
  name text NOT NULL,
  spend_paise bigint NOT NULL CHECK (spend_paise > 0),
  coins bigint NOT NULL CHECK (coins > 0),
  mode text NOT NULL CHECK (mode IN ('PRO_RATA','SLAB')),
  min_order_paise bigint NOT NULL DEFAULT 0,
  start_at timestamptz,
  end_at timestamptz,
  active boolean NOT NULL DEFAULT true
);

CREATE TABLE coin_setting (
  id int PRIMARY KEY CHECK (id = 1),
  paise_per_coin bigint NOT NULL CHECK (paise_per_coin > 0),
  max_redeem_percent int NOT NULL CHECK (max_redeem_percent BETWEEN 0 AND 100),
  expiry_months int NOT NULL CHECK (expiry_months > 0)
);
INSERT INTO coin_setting (id, paise_per_coin, max_redeem_percent, expiry_months) VALUES (1, 100, 100, 12);

CREATE TABLE wallet_balance (
  user_id uuid PRIMARY KEY REFERENCES users(id),
  coins int NOT NULL DEFAULT 0 CHECK (coins >= 0),
  version bigint NOT NULL DEFAULT 0
);

CREATE TABLE coin_ledger (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(id),
  kind text NOT NULL CHECK (kind IN ('EARN','REDEEM','REVERSE','EXPIRE','ADJUST')),
  coins int NOT NULL,
  remaining int,
  expires_at timestamptz,
  order_id uuid,
  rule_id uuid,
  idem_key text NOT NULL UNIQUE,
  created_at timestamptz NOT NULL
);
CREATE INDEX coin_ledger_user ON coin_ledger (user_id, created_at DESC);
CREATE INDEX coin_lot_open ON coin_ledger (user_id, expires_at) WHERE remaining > 0;

CREATE TABLE coin_lot_use (
  redeem_id uuid NOT NULL REFERENCES coin_ledger(id),
  lot_id uuid NOT NULL REFERENCES coin_ledger(id),
  coins int NOT NULL CHECK (coins > 0),
  PRIMARY KEY (redeem_id, lot_id)
);

CREATE SEQUENCE order_no_seq START 1001;

CREATE TABLE orders (
  id uuid PRIMARY KEY,
  order_no text NOT NULL UNIQUE,
  user_id uuid NOT NULL REFERENCES users(id),
  idem_key text NOT NULL,
  fulfilment text NOT NULL CHECK (fulfilment IN ('DELIVERY','PICKUP')),
  status text NOT NULL,
  subtotal_paise bigint NOT NULL,
  coupon_code text,
  coupon_discount_paise bigint NOT NULL DEFAULT 0,
  coins_used int NOT NULL DEFAULT 0,
  coin_discount_paise bigint NOT NULL DEFAULT 0,
  delivery_fee_paise bigint NOT NULL DEFAULT 0,
  total_paise bigint NOT NULL CHECK (total_paise >= 0),
  coins_earned int NOT NULL DEFAULT 0,
  coins_credited boolean NOT NULL DEFAULT false,
  payment_method text NOT NULL,
  payment_status text NOT NULL,
  address_snapshot text,
  expires_at timestamptz,
  placed_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (user_id, idem_key)
);
CREATE INDEX orders_user ON orders (user_id, placed_at DESC);
CREATE INDEX orders_status ON orders (status, placed_at DESC);

CREATE TABLE order_item (
  id uuid PRIMARY KEY,
  order_id uuid NOT NULL REFERENCES orders(id),
  product_id uuid NOT NULL REFERENCES product(id),
  name_snapshot text NOT NULL,
  unit_price_paise bigint NOT NULL,
  qty int NOT NULL CHECK (qty > 0),
  line_total_paise bigint NOT NULL,
  earns_coins_snapshot boolean NOT NULL,
  coin_redeemable_snapshot boolean NOT NULL
);
CREATE INDEX order_item_order ON order_item (order_id);

CREATE TABLE order_status_history (
  id uuid PRIMARY KEY,
  order_id uuid NOT NULL REFERENCES orders(id),
  from_status text,
  to_status text NOT NULL,
  actor_id uuid,
  note text,
  created_at timestamptz NOT NULL
);
CREATE INDEX order_history_order ON order_status_history (order_id, created_at);

CREATE TABLE coupon_redemption (
  id uuid PRIMARY KEY,
  coupon_id uuid NOT NULL REFERENCES coupon(id),
  user_id uuid NOT NULL REFERENCES users(id),
  order_id uuid NOT NULL REFERENCES orders(id),
  discount_paise bigint NOT NULL,
  released boolean NOT NULL DEFAULT false,
  UNIQUE (coupon_id, order_id)
);

CREATE TABLE notification (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(id),
  title text NOT NULL,
  body text NOT NULL,
  kind text NOT NULL,
  ref text,
  read_at timestamptz,
  created_at timestamptz NOT NULL
);
CREATE INDEX notification_user ON notification (user_id, created_at DESC);

CREATE TABLE outbox_event (
  id uuid PRIMARY KEY,
  type text NOT NULL,
  payload text NOT NULL,
  created_at timestamptz NOT NULL,
  published_at timestamptz
);
CREATE INDEX outbox_unpublished ON outbox_event (created_at) WHERE published_at IS NULL;

CREATE TABLE audit_log (
  id uuid PRIMARY KEY,
  actor_id uuid,
  action text NOT NULL,
  entity text NOT NULL,
  entity_id text,
  change text,
  created_at timestamptz NOT NULL
);

CREATE TABLE payment (
  id uuid PRIMARY KEY,
  order_id uuid NOT NULL REFERENCES orders(id),
  provider text NOT NULL,
  method text NOT NULL,
  amount_paise bigint NOT NULL,
  currency text NOT NULL DEFAULT 'INR',
  status text NOT NULL,
  provider_order_id text,
  provider_ref text,
  failure_reason text,
  expires_at timestamptz,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL
);
CREATE INDEX payment_order ON payment (order_id);
CREATE INDEX payment_provider_order ON payment (provider, provider_order_id);

CREATE TABLE payment_event (
  id uuid PRIMARY KEY,
  provider text NOT NULL,
  provider_event_id text NOT NULL,
  type text NOT NULL,
  payload text NOT NULL,
  processed_at timestamptz NOT NULL,
  UNIQUE (provider, provider_event_id)
);

CREATE TABLE refund (
  id uuid PRIMARY KEY,
  payment_id uuid NOT NULL REFERENCES payment(id),
  amount_paise bigint NOT NULL,
  status text NOT NULL,
  provider_ref text,
  reason text,
  created_at timestamptz NOT NULL
);

CREATE TABLE payment_setting (
  code text PRIMARY KEY,
  enabled boolean NOT NULL,
  sort_order int NOT NULL DEFAULT 0
);
INSERT INTO payment_setting (code, enabled, sort_order) VALUES ('COD', true, 1), ('ONLINE', false, 2);
