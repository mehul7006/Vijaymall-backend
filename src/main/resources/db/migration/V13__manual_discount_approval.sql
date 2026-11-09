-- Manager approval for counter overrides. An admin sets a PIN (stored hashed); a cashier needs it to give a manual
-- discount above their own limit. Every manual discount keeps its reason and who approved it.
ALTER TABLE users ADD COLUMN approval_pin_hash text;
ALTER TABLE pos_sale ADD COLUMN manual_discount_paise bigint NOT NULL DEFAULT 0 CHECK (manual_discount_paise >= 0);
ALTER TABLE pos_sale ADD COLUMN manual_discount_reason text NOT NULL DEFAULT '';
ALTER TABLE pos_sale ADD COLUMN manual_discount_approver uuid REFERENCES users(id);
