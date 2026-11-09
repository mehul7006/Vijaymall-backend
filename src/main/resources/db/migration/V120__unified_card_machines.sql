-- One flow for every card machine: the generic terminal session and the counter line carry what the machine reported.

-- A session that was used on a bill; the counter line's terminal_payment_id now holds the id of whichever kind of session was used
-- (a Paytm machine payment id, or a generic session id), so it no longer points at terminal_payment alone. The unique index stays.
ALTER TABLE card_terminal_session ADD COLUMN pos_sale_id uuid;
ALTER TABLE pos_payment DROP CONSTRAINT IF EXISTS pos_payment_terminal_payment_id_fkey;

-- What the machine or the cashier reported about the payer (scheme, last four digits, bank, masked UPI address, RRN...), masked, as JSON.
ALTER TABLE pos_payment ADD COLUMN detail jsonb;

-- Settlement lines of a Paytm machine are matched by RRN.
CREATE INDEX terminal_payment_rrn ON terminal_payment (rrn) WHERE rrn IS NOT NULL;
