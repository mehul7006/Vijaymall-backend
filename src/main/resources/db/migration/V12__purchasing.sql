-- Stock coming in from a supplier. Receiving a purchase adds the stock (reason PURCHASE in the stock history, linked to
-- the purchase) and keeps what each unit cost, so the shop can see cost prices and what it paid a supplier.
CREATE TABLE purchase (
  id uuid PRIMARY KEY,
  supplier_name text NOT NULL,
  invoice_no text NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  total_cost_paise bigint NOT NULL CHECK (total_cost_paise >= 0),
  idem_key text NOT NULL UNIQUE,
  created_by uuid REFERENCES users(id),
  created_at timestamptz NOT NULL
);
CREATE INDEX purchase_created ON purchase (created_at DESC);

CREATE TABLE purchase_item (
  id uuid PRIMARY KEY,
  purchase_id uuid NOT NULL REFERENCES purchase(id),
  product_id uuid NOT NULL REFERENCES product(id),
  qty int NOT NULL CHECK (qty > 0),
  unit_cost_paise bigint NOT NULL CHECK (unit_cost_paise >= 0)
);
CREATE INDEX purchase_item_purchase ON purchase_item (purchase_id);
CREATE INDEX purchase_item_product ON purchase_item (product_id);
