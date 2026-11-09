-- Purchases v2: a real supplier master, GST-aware purchase documents, payments against a purchase and purchase returns (debit notes).
-- Unit costs that can be fractional (landed cost) are numeric(18,4) paise; every total stays an exact whole number of paise.

CREATE TABLE supplier (
  id uuid PRIMARY KEY,
  name text NOT NULL,
  name_key text NOT NULL UNIQUE,
  gstin text NOT NULL DEFAULT '',
  state_code text NOT NULL DEFAULT '',
  phone text NOT NULL DEFAULT '',
  address text NOT NULL DEFAULT '',
  payment_terms_days int NOT NULL DEFAULT 0 CHECK (payment_terms_days BETWEEN 0 AND 365),
  active boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL
);
CREATE INDEX supplier_phone ON supplier (phone) WHERE phone <> '';
CREATE INDEX supplier_gstin ON supplier (gstin) WHERE gstin <> '';

-- One supplier per distinct trimmed, case-insensitive name already used by a purchase; the first spelling used wins.
INSERT INTO supplier (id, name, name_key, created_at, updated_at)
SELECT gen_random_uuid(), name, name_key, first_at, first_at
FROM (SELECT DISTINCT ON (name_key) btrim(supplier_name) AS name, name_key, created_at AS first_at
      FROM (SELECT supplier_name, created_at, lower(regexp_replace(btrim(supplier_name), '\s+', ' ', 'g')) AS name_key FROM purchase) p
      ORDER BY name_key, created_at) s;

ALTER TABLE purchase
  ADD COLUMN supplier_id uuid REFERENCES supplier(id),
  ADD COLUMN supplier_state_code text NOT NULL DEFAULT '',
  ADD COLUMN inter_state boolean NOT NULL DEFAULT false,
  ADD COLUMN gst_mode text NOT NULL DEFAULT 'NONE' CHECK (gst_mode IN ('REGULAR', 'COMPOSITION', 'NONE')),
  ADD COLUMN invoice_date date,
  ADD COLUMN received_date date,
  ADD COLUMN attachment_note text NOT NULL DEFAULT '',
  ADD COLUMN request_hash text,
  ADD COLUMN line_discount_paise bigint NOT NULL DEFAULT 0,
  ADD COLUMN doc_discount_percent numeric(5,2),
  ADD COLUMN doc_discount_paise bigint NOT NULL DEFAULT 0,
  ADD COLUMN taxable_paise bigint NOT NULL DEFAULT 0,
  ADD COLUMN cgst_paise bigint NOT NULL DEFAULT 0,
  ADD COLUMN sgst_paise bigint NOT NULL DEFAULT 0,
  ADD COLUMN igst_paise bigint NOT NULL DEFAULT 0,
  ADD COLUMN cess_paise bigint NOT NULL DEFAULT 0,
  ADD COLUMN freight_paise bigint NOT NULL DEFAULT 0,
  ADD COLUMN freight_gst_percent int NOT NULL DEFAULT 0,
  ADD COLUMN freight_gst_paise bigint NOT NULL DEFAULT 0,
  ADD COLUMN round_off_paise bigint NOT NULL DEFAULT 0,
  ADD COLUMN due_date date,
  ADD COLUMN paid_paise bigint NOT NULL DEFAULT 0 CHECK (paid_paise >= 0),
  ADD COLUMN returned_paise bigint NOT NULL DEFAULT 0 CHECK (returned_paise >= 0);

UPDATE purchase p SET supplier_id = s.id,
  invoice_date = (p.created_at AT TIME ZONE 'Asia/Kolkata')::date,
  received_date = (p.created_at AT TIME ZONE 'Asia/Kolkata')::date,
  taxable_paise = p.total_cost_paise,
  paid_paise = p.total_cost_paise
FROM supplier s WHERE s.name_key = lower(regexp_replace(btrim(p.supplier_name), '\s+', ' ', 'g'));

ALTER TABLE purchase ALTER COLUMN supplier_id SET NOT NULL, ALTER COLUMN invoice_date SET NOT NULL, ALTER COLUMN received_date SET NOT NULL;

-- Derived, never written: what is still owed (negative = the supplier owes us) and the payment status. Stored so lists can filter and index them.
ALTER TABLE purchase
  ADD COLUMN due_paise bigint GENERATED ALWAYS AS (total_cost_paise - returned_paise - paid_paise) STORED,
  ADD COLUMN payment_status text GENERATED ALWAYS AS (
    CASE WHEN total_cost_paise - returned_paise - paid_paise <= 0 THEN 'PAID'
         WHEN paid_paise = 0 THEN 'PENDING'
         ELSE 'PARTIAL' END) STORED;

CREATE INDEX purchase_supplier_created ON purchase (supplier_id, created_at DESC);
CREATE INDEX purchase_invoice_date ON purchase (invoice_date DESC, created_at DESC);
CREATE INDEX purchase_status_due ON purchase (payment_status, due_date) WHERE payment_status <> 'PAID';
CREATE INDEX purchase_due_open ON purchase (due_date) WHERE due_paise > 0;

CREATE TABLE purchase_payment (
  id uuid PRIMARY KEY,
  purchase_id uuid NOT NULL REFERENCES purchase(id),
  amount_paise bigint NOT NULL CHECK (amount_paise > 0),
  mode text NOT NULL CHECK (mode IN ('CASH', 'UPI', 'CARD', 'BANK', 'CHEQUE')),
  reference text NOT NULL DEFAULT '',
  note text NOT NULL DEFAULT '',
  money_account_id uuid,
  paid_at timestamptz NOT NULL,
  created_by uuid REFERENCES users(id),
  created_at timestamptz NOT NULL
);
CREATE INDEX purchase_payment_purchase ON purchase_payment (purchase_id, paid_at);

-- Purchases recorded before this migration are treated as already paid in full (the shop never tracked what it owed).
INSERT INTO purchase_payment (id, purchase_id, amount_paise, mode, reference, paid_at, created_by, created_at)
SELECT gen_random_uuid(), id, total_cost_paise, 'CASH', 'LEGACY', created_at, created_by, created_at FROM purchase WHERE total_cost_paise > 0;

ALTER TABLE purchase_item
  ADD COLUMN free_qty int NOT NULL DEFAULT 0 CHECK (free_qty >= 0),
  ADD COLUMN discount_percent numeric(5,2),
  ADD COLUMN line_discount_paise bigint NOT NULL DEFAULT 0,
  ADD COLUMN doc_discount_share_paise bigint NOT NULL DEFAULT 0,
  ADD COLUMN taxable_paise bigint NOT NULL DEFAULT 0,
  ADD COLUMN gst_percent int NOT NULL DEFAULT 0,
  ADD COLUMN cgst_paise bigint NOT NULL DEFAULT 0,
  ADD COLUMN sgst_paise bigint NOT NULL DEFAULT 0,
  ADD COLUMN igst_paise bigint NOT NULL DEFAULT 0,
  ADD COLUMN cess_percent numeric(5,2) NOT NULL DEFAULT 0,
  ADD COLUMN cess_paise bigint NOT NULL DEFAULT 0,
  ADD COLUMN line_total_paise bigint NOT NULL DEFAULT 0,
  ADD COLUMN landed_total_paise bigint NOT NULL DEFAULT 0,
  ADD COLUMN landed_unit_cost numeric(18,4) NOT NULL DEFAULT 0,
  ADD COLUMN hsn text NOT NULL DEFAULT '',
  ADD COLUMN mrp_paise bigint,
  ADD COLUMN selling_price_paise bigint,
  ADD COLUMN batch_no text NOT NULL DEFAULT '',
  ADD COLUMN expiry_date date,
  ADD COLUMN apply_to_variant boolean NOT NULL DEFAULT false,
  ADD COLUMN returned_qty int NOT NULL DEFAULT 0 CHECK (returned_qty >= 0);

UPDATE purchase_item SET taxable_paise = qty * unit_cost_paise, line_total_paise = qty * unit_cost_paise,
  landed_total_paise = qty * unit_cost_paise, landed_unit_cost = unit_cost_paise;

CREATE SEQUENCE purchase_return_no_seq;

CREATE TABLE purchase_return (
  id uuid PRIMARY KEY,
  purchase_id uuid NOT NULL REFERENCES purchase(id),
  note_no text NOT NULL UNIQUE,
  reason text NOT NULL,
  taxable_paise bigint NOT NULL,
  cgst_paise bigint NOT NULL,
  sgst_paise bigint NOT NULL,
  igst_paise bigint NOT NULL,
  cess_paise bigint NOT NULL,
  total_paise bigint NOT NULL,
  idem_key text NOT NULL UNIQUE,
  created_by uuid REFERENCES users(id),
  created_at timestamptz NOT NULL
);
CREATE INDEX purchase_return_purchase ON purchase_return (purchase_id);
CREATE INDEX purchase_return_created ON purchase_return (created_at DESC, id);

CREATE TABLE purchase_return_item (
  id uuid PRIMARY KEY,
  return_id uuid NOT NULL REFERENCES purchase_return(id),
  purchase_item_id uuid NOT NULL REFERENCES purchase_item(id),
  product_id uuid NOT NULL REFERENCES product(id),
  qty int NOT NULL CHECK (qty > 0),
  taxable_paise bigint NOT NULL,
  cgst_paise bigint NOT NULL,
  sgst_paise bigint NOT NULL,
  igst_paise bigint NOT NULL,
  cess_paise bigint NOT NULL,
  total_paise bigint NOT NULL
);
CREATE INDEX purchase_return_item_return ON purchase_return_item (return_id);
CREATE INDEX purchase_return_item_line ON purchase_return_item (purchase_item_id);
