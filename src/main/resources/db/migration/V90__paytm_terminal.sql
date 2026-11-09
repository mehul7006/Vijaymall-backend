-- Card machines (Paytm wireless EDC) and the payments taken on them.

CREATE TABLE payment_terminal (
  id uuid PRIMARY KEY,
  provider text NOT NULL DEFAULT 'paytm',
  terminal_id text NOT NULL,
  name text NOT NULL,
  active boolean NOT NULL DEFAULT true,
  -- the money account the machine settles into; owned by the bank module, so deliberately no foreign key here
  money_account_id uuid,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  CONSTRAINT payment_terminal_unique UNIQUE (provider, terminal_id)
);

CREATE TABLE terminal_payment (
  id uuid PRIMARY KEY,
  payment_terminal_id uuid NOT NULL REFERENCES payment_terminal(id),
  terminal_id text NOT NULL,
  provider text NOT NULL DEFAULT 'paytm',
  merchant_transaction_id text NOT NULL,
  txn_date_time text NOT NULL,
  idem_key text NOT NULL,
  amount_paise bigint NOT NULL CHECK (amount_paise > 0),
  reference text NOT NULL DEFAULT '',
  status text NOT NULL CHECK (status IN ('REQUESTED','PENDING','SUCCESS','FAILED','CANCELLED','EXPIRED','UNKNOWN','VOIDED')),
  result_code text,
  message text NOT NULL DEFAULT '',
  pay_method text,
  card_scheme text,
  masked_card text,
  rrn text,
  auth_code text,
  issuing_bank text,
  acquiring_bank text,
  upi_vpa text,
  bank_response_code text,
  bank_response_message text,
  raw_response text,
  pos_sale_id uuid,
  order_id uuid,
  requested_by uuid NOT NULL,
  cancel_requested_at timestamptz,
  void_requested_at timestamptz,
  voided_at timestamptz,
  next_poll_at timestamptz,
  last_polled_at timestamptz,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  finished_at timestamptz,
  CONSTRAINT terminal_payment_txn_unique UNIQUE (merchant_transaction_id),
  CONSTRAINT terminal_payment_idem_unique UNIQUE (idem_key)
);
-- a machine can hold one open request at a time: the database enforces it, so two tills cannot both win
CREATE UNIQUE INDEX terminal_payment_one_open ON terminal_payment (payment_terminal_id) WHERE status IN ('REQUESTED','PENDING','UNKNOWN');
CREATE INDEX terminal_payment_status_updated ON terminal_payment (status, updated_at);
CREATE INDEX terminal_payment_due ON terminal_payment (next_poll_at) WHERE next_poll_at IS NOT NULL;

ALTER TABLE pos_payment ADD COLUMN terminal_payment_id uuid REFERENCES terminal_payment(id);
CREATE UNIQUE INDEX pos_payment_terminal_payment ON pos_payment (terminal_payment_id) WHERE terminal_payment_id IS NOT NULL;
