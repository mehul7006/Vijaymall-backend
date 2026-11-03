-- Credit (udhar) at the shop counter. Money is in paise. Credit is never used online.

-- A bill may be paid partly or fully on CREDIT. A hybrid bill is CREDIT plus other lines.
ALTER TABLE pos_payment DROP CONSTRAINT pos_payment_mode_check;
ALTER TABLE pos_payment ADD CONSTRAINT pos_payment_mode_check CHECK (mode IN ('CASH','UPI','CARD','CREDIT'));

-- How the bill was paid, kept for reports: NONE, CASH, UPI, CARD, SPLIT (several non-credit modes), CREDIT, HYBRID.
ALTER TABLE pos_sale ADD COLUMN payment_type text NOT NULL DEFAULT 'NONE';
UPDATE pos_sale s SET payment_type = CASE
  WHEN NOT EXISTS (SELECT 1 FROM pos_payment p WHERE p.sale_id = s.id) THEN 'NONE'
  WHEN (SELECT count(DISTINCT p.mode) FROM pos_payment p WHERE p.sale_id = s.id) = 1
    THEN (SELECT min(p.mode) FROM pos_payment p WHERE p.sale_id = s.id)
  ELSE 'SPLIT' END;
CREATE INDEX pos_sale_payment_type ON pos_sale (payment_type, created_at);

-- Part of a refund that reduced the customer's credit instead of being paid out.
ALTER TABLE pos_return ADD COLUMN credit_adjust_paise bigint NOT NULL DEFAULT 0 CHECK (credit_adjust_paise >= 0);

-- Receipts for credit repayments get their own gap-free numbers.
ALTER TABLE invoice_counter DROP CONSTRAINT invoice_counter_kind_check;
ALTER TABLE invoice_counter ADD CONSTRAINT invoice_counter_kind_check CHECK (kind IN ('INVOICE','CREDIT_NOTE','CREDIT_RECEIPT'));

-- One row per customer who has (or may get) credit. limit 0 means credit is not allowed for them.
CREATE TABLE credit_account (
  user_id uuid PRIMARY KEY REFERENCES users(id),
  balance_paise bigint NOT NULL DEFAULT 0 CHECK (balance_paise >= 0),
  limit_paise bigint NOT NULL DEFAULT 0 CHECK (limit_paise >= 0),
  updated_at timestamptz NOT NULL
);
CREATE INDEX credit_account_balance ON credit_account (balance_paise DESC) WHERE balance_paise > 0;

-- A repayment of credit (the customer pays what they owe), possibly split over CASH / UPI / CARD.
CREATE TABLE credit_payment (
  id uuid PRIMARY KEY,
  receipt_no text NOT NULL UNIQUE,
  idem_key text NOT NULL UNIQUE,
  user_id uuid NOT NULL REFERENCES users(id),
  cashier_id uuid NOT NULL REFERENCES users(id),
  amount_paise bigint NOT NULL CHECK (amount_paise > 0),
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL
);
CREATE INDEX credit_payment_user ON credit_payment (user_id, created_at DESC);
CREATE INDEX credit_payment_created ON credit_payment (created_at);

CREATE TABLE credit_payment_line (
  id uuid PRIMARY KEY,
  payment_id uuid NOT NULL REFERENCES credit_payment(id),
  mode text NOT NULL CHECK (mode IN ('CASH','UPI','CARD')),
  amount_paise bigint NOT NULL CHECK (amount_paise > 0),
  reference text
);
CREATE INDEX credit_payment_line_payment ON credit_payment_line (payment_id);

-- Every change to a customer's balance. delta > 0 means they owe more, delta < 0 means they owe less.
CREATE TABLE credit_ledger (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(id),
  kind text NOT NULL CHECK (kind IN ('SALE','PAYMENT','RETURN','ADJUST')),
  delta_paise bigint NOT NULL CHECK (delta_paise <> 0),
  balance_after_paise bigint NOT NULL CHECK (balance_after_paise >= 0),
  ref_no text,
  sale_id uuid REFERENCES pos_sale(id),
  return_id uuid REFERENCES pos_return(id),
  payment_id uuid REFERENCES credit_payment(id),
  note text NOT NULL DEFAULT '',
  actor_id uuid REFERENCES users(id),
  created_at timestamptz NOT NULL
);
CREATE INDEX credit_ledger_user ON credit_ledger (user_id, created_at DESC);
CREATE INDEX credit_ledger_created ON credit_ledger (created_at);
CREATE INDEX credit_ledger_sale ON credit_ledger (sale_id) WHERE sale_id IS NOT NULL;
