-- Supplier credit (a negative amount due) can be used against another purchase of the same supplier or refunded in cash or by bank;
-- a recorded purchase can carry one invoice attachment (a private file) and its GSTIN text can be corrected.

-- What was taken out of the credit of a purchase (offset against another purchase, or refunded). Part of the generated due amount, so the
-- due amount and the status are re-created (the table is small: a shop's purchases).
ALTER TABLE purchase DROP COLUMN payment_status, DROP COLUMN due_paise;
ALTER TABLE purchase ADD COLUMN credit_used_paise bigint NOT NULL DEFAULT 0 CHECK (credit_used_paise >= 0);
ALTER TABLE purchase
  ADD COLUMN due_paise bigint GENERATED ALWAYS AS (total_cost_paise - returned_paise - paid_paise + credit_used_paise) STORED;
ALTER TABLE purchase
  ADD COLUMN payment_status text GENERATED ALWAYS AS (
    CASE WHEN total_cost_paise - returned_paise - paid_paise + credit_used_paise <= 0 THEN 'PAID'
         WHEN paid_paise = 0 THEN 'PENDING'
         ELSE 'PARTIAL' END) STORED;
CREATE INDEX purchase_status_due ON purchase (payment_status, due_date) WHERE payment_status <> 'PAID';
CREATE INDEX purchase_due_open ON purchase (due_date) WHERE due_paise > 0;
CREATE INDEX purchase_supplier_credit ON purchase (supplier_id) WHERE due_paise < 0;

-- How much of a debit note's value was used as credit.
ALTER TABLE purchase_return ADD COLUMN credit_used_paise bigint NOT NULL DEFAULT 0 CHECK (credit_used_paise >= 0);

-- A payment can be made with the credit of a debit note of the same supplier.
ALTER TABLE purchase_payment DROP CONSTRAINT purchase_payment_mode_check;
ALTER TABLE purchase_payment ADD CONSTRAINT purchase_payment_mode_check CHECK (mode IN ('CASH', 'UPI', 'CARD', 'BANK', 'CHEQUE', 'CREDIT_NOTE'));
ALTER TABLE purchase_payment ADD COLUMN debit_note_id uuid REFERENCES purchase_return(id);

-- Money the supplier gave back (cash or bank), taken out of the supplier's credit.
CREATE TABLE supplier_refund (
  id uuid PRIMARY KEY,
  supplier_id uuid NOT NULL REFERENCES supplier(id),
  amount_paise bigint NOT NULL CHECK (amount_paise > 0),
  mode text NOT NULL CHECK (mode IN ('CASH', 'UPI', 'CARD', 'BANK', 'CHEQUE')),
  reference text NOT NULL DEFAULT '',
  money_account_id uuid,
  received_at timestamptz NOT NULL,
  created_by uuid REFERENCES users(id),
  created_at timestamptz NOT NULL
);
CREATE INDEX supplier_refund_supplier ON supplier_refund (supplier_id, received_at);

-- The invoice picture or PDF (a private file name and its content type) and a corrected GSTIN text (null: the supplier's).
ALTER TABLE purchase
  ADD COLUMN attachment_file text,
  ADD COLUMN attachment_type text,
  ADD COLUMN supplier_gstin_override text;
