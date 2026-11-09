-- Settlements: batches of money a provider or bank paid out, with the payments, refunds and charges inside.
CREATE TABLE settlement (
  id uuid PRIMARY KEY,
  provider text NOT NULL,
  provider_settlement_id text NOT NULL,
  utr text,
  gross_paise bigint NOT NULL,
  fee_paise bigint NOT NULL DEFAULT 0,
  tax_paise bigint NOT NULL DEFAULT 0,
  net_paise bigint NOT NULL,
  status text NOT NULL,
  settled_at timestamptz,
  money_account_id uuid REFERENCES money_account(id),
  source text NOT NULL CHECK (source IN ('GATEWAY_API','CSV')),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  UNIQUE (provider, provider_settlement_id)
);
CREATE INDEX settlement_settled ON settlement (settled_at DESC, id);
CREATE INDEX settlement_account_settled ON settlement (money_account_id, settled_at DESC);

CREATE TABLE settlement_item (
  id uuid PRIMARY KEY,
  settlement_id uuid NOT NULL REFERENCES settlement(id),
  kind text NOT NULL CHECK (kind IN ('PAYMENT','REFUND','ADJUSTMENT','FEE')),
  provider_entity_id text NOT NULL,
  payment_id uuid REFERENCES payment(id),
  refund_id uuid REFERENCES refund(id),
  pos_payment_id uuid REFERENCES pos_payment(id),
  gross_paise bigint NOT NULL,
  fee_paise bigint NOT NULL DEFAULT 0,
  tax_paise bigint NOT NULL DEFAULT 0,
  net_paise bigint NOT NULL,
  method text,
  settled_at timestamptz,
  utr text,
  reference text,
  matched boolean NOT NULL,
  UNIQUE (settlement_id, kind, provider_entity_id)
);
CREATE INDEX settlement_item_payment ON settlement_item (payment_id) WHERE payment_id IS NOT NULL;
CREATE INDEX settlement_item_pos_payment ON settlement_item (pos_payment_id) WHERE pos_payment_id IS NOT NULL;
CREATE INDEX settlement_item_unmatched ON settlement_item (settlement_id) WHERE NOT matched;

-- Things a person must look at. dedupe_key makes raising the same problem twice harmless.
CREATE TABLE settlement_exception (
  id uuid PRIMARY KEY,
  kind text NOT NULL CHECK (kind IN ('OVERDUE_UNSETTLED','UNMATCHED_ITEM','AMOUNT_MISMATCH')),
  dedupe_key text NOT NULL UNIQUE,
  settlement_id uuid REFERENCES settlement(id),
  settlement_item_id uuid REFERENCES settlement_item(id),
  payment_id uuid REFERENCES payment(id),
  reference text,
  expected_paise bigint,
  actual_paise bigint,
  status text NOT NULL CHECK (status IN ('OPEN','RESOLVED')),
  note text,
  created_at timestamptz NOT NULL,
  resolved_at timestamptz,
  resolved_by uuid
);
CREATE INDEX settlement_exception_status ON settlement_exception (status, created_at DESC);

-- One row per provider: the lock two instances claim before a sync, and where the last one got to.
CREATE TABLE settlement_sync_state (
  provider text PRIMARY KEY,
  locked_until timestamptz NOT NULL,
  last_synced_at timestamptz,
  last_error text
);

-- Hand-entered movements on an account (opening balance, bank charges, anything not captured elsewhere).
-- source_type / source_id are free for later modules (for example PURCHASE_PAYMENT) to post their own outflows.
CREATE TABLE money_account_entry (
  id uuid PRIMARY KEY,
  money_account_id uuid NOT NULL REFERENCES money_account(id),
  at timestamptz NOT NULL,
  direction text NOT NULL CHECK (direction IN ('IN','OUT')),
  amount_paise bigint NOT NULL CHECK (amount_paise > 0),
  source_type text,
  source_id text,
  note text NOT NULL DEFAULT '',
  created_by uuid,
  created_at timestamptz NOT NULL
);
CREATE INDEX money_account_entry_account_at ON money_account_entry (money_account_id, at);
CREATE UNIQUE INDEX money_account_entry_source ON money_account_entry (money_account_id, source_type, source_id) WHERE source_type IS NOT NULL AND source_id IS NOT NULL;

-- Card machine / terminal payment sessions started from the till.
CREATE TABLE card_terminal_session (
  id uuid PRIMARY KEY,
  reference text NOT NULL UNIQUE,
  provider text NOT NULL,
  amount_paise bigint NOT NULL CHECK (amount_paise > 0),
  state text NOT NULL CHECK (state IN ('PENDING','APPROVED','DECLINED','CANCELLED','EXPIRED')),
  mode text NOT NULL,
  instructions text,
  qr_payload text,
  qr_image_url text,
  approval_code text,
  rrn text,
  money_account_id uuid REFERENCES money_account(id),
  created_by uuid,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL
);
CREATE INDEX card_terminal_session_created ON card_terminal_session (created_at DESC);
