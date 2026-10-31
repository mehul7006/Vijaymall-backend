-- POS (shop counter billing). Money in paise, quantities in base unit (pieces or grams).

ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN ('CUSTOMER','ADMIN','CASHIER'));
ALTER TABLE users ADD COLUMN source text NOT NULL DEFAULT 'APP' CHECK (source IN ('APP','POS'));
CREATE UNIQUE INDEX users_mobile_pos ON users (mobile) WHERE source = 'POS' AND mobile IS NOT NULL;

-- counter sales have no online order row
ALTER TABLE coupon_redemption DROP CONSTRAINT coupon_redemption_order_id_fkey;

ALTER TABLE product ADD COLUMN pos_only boolean NOT NULL DEFAULT false;

CREATE TABLE product_pos (
  product_id uuid PRIMARY KEY REFERENCES product(id),
  sold_by text NOT NULL DEFAULT 'UNIT' CHECK (sold_by IN ('UNIT','WEIGHT')),
  plu text,
  gst_percent int NOT NULL DEFAULT 0 CHECK (gst_percent IN (0,3,5,12,18,28)),
  hsn text NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL
);
CREATE UNIQUE INDEX product_pos_plu ON product_pos (plu) WHERE plu IS NOT NULL;

CREATE TABLE product_barcode (
  code text PRIMARY KEY,
  product_id uuid NOT NULL REFERENCES product(id),
  created_at timestamptz NOT NULL
);
CREATE INDEX product_barcode_product ON product_barcode (product_id);

CREATE TABLE shop_setting (
  id int PRIMARY KEY CHECK (id = 1),
  shop_name text NOT NULL,
  address text NOT NULL DEFAULT '',
  phone text NOT NULL DEFAULT '',
  gstin text NOT NULL DEFAULT '',
  state_code text NOT NULL DEFAULT '24',
  upi_id text NOT NULL DEFAULT '',
  invoice_prefix text NOT NULL DEFAULT 'VSM',
  receipt_footer text NOT NULL DEFAULT 'Thank you! Visit again.',
  weighed_prefix text NOT NULL DEFAULT '2',
  weighed_mode text NOT NULL DEFAULT 'WEIGHT_GRAMS' CHECK (weighed_mode IN ('WEIGHT_GRAMS','PRICE_PAISE')),
  updated_at timestamptz NOT NULL
);
INSERT INTO shop_setting (id, shop_name, updated_at) VALUES (1, 'Vijay Super Mall', now());

CREATE TABLE invoice_counter (
  kind text NOT NULL CHECK (kind IN ('INVOICE','CREDIT_NOTE')),
  fy text NOT NULL,
  last_no bigint NOT NULL,
  PRIMARY KEY (kind, fy)
);

CREATE TABLE pos_sale (
  id uuid PRIMARY KEY,
  invoice_no text NOT NULL UNIQUE,
  idem_key text NOT NULL UNIQUE,
  cashier_id uuid NOT NULL REFERENCES users(id),
  customer_id uuid REFERENCES users(id),
  customer_mobile text,
  subtotal_paise bigint NOT NULL,
  coupon_code text,
  coupon_discount_paise bigint NOT NULL DEFAULT 0,
  coins_used int NOT NULL DEFAULT 0,
  coin_discount_paise bigint NOT NULL DEFAULT 0,
  total_paise bigint NOT NULL,
  taxable_paise bigint NOT NULL,
  cgst_paise bigint NOT NULL,
  sgst_paise bigint NOT NULL,
  coins_earned int NOT NULL DEFAULT 0,
  tendered_paise bigint,
  change_paise bigint NOT NULL DEFAULT 0,
  status text NOT NULL DEFAULT 'COMPLETED' CHECK (status IN ('COMPLETED')),
  created_at timestamptz NOT NULL
);
CREATE INDEX pos_sale_created ON pos_sale (created_at);
CREATE INDEX pos_sale_cashier ON pos_sale (cashier_id, created_at);
CREATE INDEX pos_sale_customer ON pos_sale (customer_id);

CREATE TABLE pos_sale_item (
  id uuid PRIMARY KEY,
  sale_id uuid NOT NULL REFERENCES pos_sale(id),
  product_id uuid NOT NULL REFERENCES product(id),
  name text NOT NULL,
  sold_by text NOT NULL,
  qty int NOT NULL CHECK (qty > 0),
  unit_price_paise bigint NOT NULL,
  line_paise bigint NOT NULL,
  discount_paise bigint NOT NULL DEFAULT 0,
  net_paise bigint NOT NULL,
  gst_percent int NOT NULL,
  hsn text NOT NULL DEFAULT '',
  taxable_paise bigint NOT NULL,
  cgst_paise bigint NOT NULL,
  sgst_paise bigint NOT NULL,
  returned_qty int NOT NULL DEFAULT 0 CHECK (returned_qty >= 0 AND returned_qty <= qty)
);
CREATE INDEX pos_sale_item_sale ON pos_sale_item (sale_id);

CREATE TABLE pos_payment (
  id uuid PRIMARY KEY,
  sale_id uuid NOT NULL REFERENCES pos_sale(id),
  mode text NOT NULL CHECK (mode IN ('CASH','UPI','CARD')),
  amount_paise bigint NOT NULL CHECK (amount_paise > 0),
  reference text
);
CREATE INDEX pos_payment_sale ON pos_payment (sale_id);

CREATE TABLE pos_return (
  id uuid PRIMARY KEY,
  credit_note_no text NOT NULL UNIQUE,
  idem_key text NOT NULL UNIQUE,
  sale_id uuid NOT NULL REFERENCES pos_sale(id),
  cashier_id uuid NOT NULL REFERENCES users(id),
  reason text NOT NULL DEFAULT '',
  refund_paise bigint NOT NULL,
  taxable_paise bigint NOT NULL,
  cgst_paise bigint NOT NULL,
  sgst_paise bigint NOT NULL,
  coins_clawed_back int NOT NULL DEFAULT 0,
  coins_restored int NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL
);
CREATE INDEX pos_return_sale ON pos_return (sale_id);
CREATE INDEX pos_return_created ON pos_return (created_at);

CREATE TABLE pos_return_item (
  id uuid PRIMARY KEY,
  return_id uuid NOT NULL REFERENCES pos_return(id),
  sale_item_id uuid NOT NULL REFERENCES pos_sale_item(id),
  qty int NOT NULL CHECK (qty > 0),
  refund_paise bigint NOT NULL,
  taxable_paise bigint NOT NULL,
  cgst_paise bigint NOT NULL,
  sgst_paise bigint NOT NULL
);
CREATE INDEX pos_return_item_return ON pos_return_item (return_id);

CREATE TABLE pos_refund_payment (
  id uuid PRIMARY KEY,
  return_id uuid NOT NULL REFERENCES pos_return(id),
  mode text NOT NULL CHECK (mode IN ('CASH','UPI','CARD')),
  amount_paise bigint NOT NULL CHECK (amount_paise > 0),
  reference text
);

CREATE TABLE pos_hold (
  id uuid PRIMARY KEY,
  cashier_id uuid NOT NULL REFERENCES users(id),
  label text NOT NULL DEFAULT '',
  customer_mobile text,
  payload text NOT NULL,
  created_at timestamptz NOT NULL
);
CREATE INDEX pos_hold_cashier ON pos_hold (cashier_id, created_at);
