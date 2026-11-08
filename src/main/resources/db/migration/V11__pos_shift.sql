-- Cashier shifts: a cashier opens a shift with the cash already in the drawer (float) and closes it with the cash
-- actually counted. The difference to what the system expects is recorded, so missing or extra cash is visible.
CREATE TABLE pos_shift (
  id uuid PRIMARY KEY,
  cashier_id uuid NOT NULL REFERENCES users(id),
  opened_at timestamptz NOT NULL,
  opening_float_paise bigint NOT NULL CHECK (opening_float_paise >= 0),
  closed_at timestamptz,
  expected_cash_paise bigint,
  counted_cash_paise bigint CHECK (counted_cash_paise IS NULL OR counted_cash_paise >= 0),
  variance_paise bigint,
  note text
);
-- A cashier can have only one open shift at a time.
CREATE UNIQUE INDEX pos_shift_one_open ON pos_shift (cashier_id) WHERE closed_at IS NULL;
CREATE INDEX pos_shift_opened ON pos_shift (opened_at DESC);
