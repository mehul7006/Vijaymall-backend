-- The IFSC code of a BANK money account (optional, upper case, 11 characters); null for every other kind.
ALTER TABLE money_account ADD COLUMN ifsc text CHECK (ifsc IS NULL OR ifsc ~ '^[A-Z]{4}0[A-Z0-9]{6}$');
