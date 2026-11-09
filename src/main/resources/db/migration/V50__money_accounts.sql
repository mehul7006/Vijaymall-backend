-- Money accounts: where money is held (bank, gateway, UPI QR, card machine, cash drawer). Every receipt can name one.
CREATE TABLE money_account (
  id uuid PRIMARY KEY,
  name text NOT NULL,
  kind text NOT NULL CHECK (kind IN ('BANK','GATEWAY','UPI','CARD_TERMINAL','CASH')),
  provider text NOT NULL DEFAULT 'manual',
  identifier text NOT NULL DEFAULT '',
  settles_to_account_id uuid REFERENCES money_account(id),
  active boolean NOT NULL DEFAULT true,
  note text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL
);
CREATE UNIQUE INDEX money_account_name ON money_account (lower(name));
CREATE INDEX money_account_settles_to ON money_account (settles_to_account_id) WHERE settles_to_account_id IS NOT NULL;
CREATE INDEX money_account_provider ON money_account (provider, kind) WHERE active;

INSERT INTO money_account (id, name, kind, provider, identifier, active, note, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-0000000000a1', 'Cash drawer', 'CASH', 'manual', '', true, 'The till drawer', now(), now());

-- Online payments: which account received the money, what the customer paid with, what the provider charged, where it settled.
ALTER TABLE payment
  ADD COLUMN money_account_id uuid REFERENCES money_account(id),
  ADD COLUMN instrument text,
  ADD COLUMN upi_vpa text,
  ADD COLUMN card_network text,
  ADD COLUMN card_last4 text,
  ADD COLUMN bank text,
  ADD COLUMN fee_paise bigint,
  ADD COLUMN tax_paise bigint,
  ADD COLUMN net_paise bigint,
  ADD COLUMN settlement_ref text,
  ADD COLUMN utr text,
  ADD COLUMN settled_at timestamptz,
  ADD COLUMN captured_at timestamptz,
  ADD COLUMN detail_fetched_at timestamptz;
UPDATE payment SET captured_at = updated_at WHERE status IN ('CAPTURED','REFUNDED') AND provider_ref IS NOT NULL;
CREATE INDEX payment_account_captured ON payment (money_account_id, captured_at) WHERE captured_at IS NOT NULL;
CREATE INDEX payment_captured ON payment (captured_at) WHERE captured_at IS NOT NULL;
CREATE INDEX payment_unsettled ON payment (captured_at) WHERE status = 'CAPTURED' AND settlement_ref IS NULL;
CREATE INDEX payment_provider_ref ON payment (provider_ref) WHERE provider_ref IS NOT NULL;
CREATE INDEX payment_detail_missing ON payment (captured_at) WHERE status IN ('CAPTURED','REFUNDED') AND detail_fetched_at IS NULL;

ALTER TABLE refund ADD COLUMN money_account_id uuid REFERENCES money_account(id);
CREATE INDEX refund_provider_ref ON refund (provider_ref) WHERE provider_ref IS NOT NULL;
CREATE INDEX refund_created ON refund (created_at);

-- Counter payments: optional account, terminal id; the reference column (UPI ref, approval code, RRN) already exists.
ALTER TABLE pos_payment ADD COLUMN money_account_id uuid REFERENCES money_account(id), ADD COLUMN terminal_id text;
CREATE INDEX pos_payment_account ON pos_payment (money_account_id) WHERE money_account_id IS NOT NULL;
CREATE INDEX pos_payment_reference ON pos_payment (reference) WHERE reference IS NOT NULL;
ALTER TABLE pos_refund_payment ADD COLUMN money_account_id uuid REFERENCES money_account(id);
CREATE INDEX pos_refund_payment_return ON pos_refund_payment (return_id);
