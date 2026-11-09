-- Stock lots (FIFO / FEFO costing and lot-level prices). Every stock change goes through lots; the sum of qty_remaining of a product's lots
-- always equals inventory.qty_on_hand (checked by GET /admin/stock/lot-check).

ALTER TABLE product
  ADD COLUMN lot_priced boolean NOT NULL DEFAULT false,
  ADD COLUMN track_expiry boolean NOT NULL DEFAULT false;

CREATE TABLE stock_lot (
  id uuid PRIMARY KEY,
  product_id uuid NOT NULL REFERENCES product(id),
  purchase_item_id uuid REFERENCES purchase_item(id),
  received_at timestamptz NOT NULL,
  qty_received int NOT NULL CHECK (qty_received > 0),
  qty_remaining int NOT NULL CHECK (qty_remaining >= 0 AND qty_remaining <= qty_received),
  unit_cost numeric(18,4) NOT NULL DEFAULT 0 CHECK (unit_cost >= 0),
  cost_known boolean NOT NULL DEFAULT true,
  mrp_paise bigint CHECK (mrp_paise >= 0),
  price_paise bigint CHECK (price_paise >= 0),
  batch_no text NOT NULL DEFAULT '',
  expiry_date date,
  source text NOT NULL CHECK (source IN ('PURCHASE', 'OPENING', 'ADJUSTMENT', 'RETURN', 'TRANSFER')),
  created_at timestamptz NOT NULL
);
CREATE INDEX stock_lot_open ON stock_lot (product_id, received_at) WHERE qty_remaining > 0;
CREATE INDEX stock_lot_product_recent ON stock_lot (product_id, received_at DESC);
CREATE INDEX stock_lot_expiry ON stock_lot (expiry_date) WHERE qty_remaining > 0 AND expiry_date IS NOT NULL;
CREATE INDEX stock_lot_purchase_item ON stock_lot (purchase_item_id) WHERE purchase_item_id IS NOT NULL;

-- Which lot each unit came from. cost_paise is the whole-paise cost of qty units (the rounding remainder of a line sits on its last lot).
CREATE TABLE stock_allocation (
  id uuid PRIMARY KEY,
  lot_id uuid NOT NULL REFERENCES stock_lot(id),
  product_id uuid NOT NULL REFERENCES product(id),
  ref_type text NOT NULL CHECK (ref_type IN ('ORDER_ITEM', 'POS_SALE_ITEM', 'PURCHASE_RETURN', 'ADJUSTMENT')),
  ref_id uuid NOT NULL,
  qty int NOT NULL CHECK (qty > 0),
  unit_cost numeric(18,4) NOT NULL,
  cost_paise bigint NOT NULL,
  returned_qty int NOT NULL DEFAULT 0 CHECK (returned_qty >= 0 AND returned_qty <= qty),
  returned_cost_paise bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL
);
CREATE INDEX stock_allocation_ref ON stock_allocation (ref_type, ref_id);
CREATE INDEX stock_allocation_lot ON stock_allocation (lot_id);

ALTER TABLE order_item ADD COLUMN cost_paise bigint;
ALTER TABLE pos_sale_item ADD COLUMN cost_paise bigint;

-- One opening lot per product that has stock: the current quantity, at the latest purchase landed cost when there is one (else cost unknown).
INSERT INTO stock_lot (id, product_id, received_at, qty_received, qty_remaining, unit_cost, cost_known, source, created_at)
SELECT gen_random_uuid(), i.product_id, now(), i.qty_on_hand, i.qty_on_hand, COALESCE(c.cost, 0), c.cost IS NOT NULL, 'OPENING', now()
FROM inventory i
LEFT JOIN (SELECT DISTINCT ON (pi.product_id) pi.product_id, pi.landed_unit_cost AS cost
           FROM purchase_item pi JOIN purchase p ON p.id = pi.purchase_id
           ORDER BY pi.product_id, p.created_at DESC) c ON c.product_id = i.product_id
WHERE i.qty_on_hand > 0;
