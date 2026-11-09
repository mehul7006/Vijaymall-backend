-- Provider-specific extras (for example a terminal serial or an acquirer's commission breakdown) kept as JSON next to the normalized columns.
ALTER TABLE payment ADD COLUMN detail jsonb;
ALTER TABLE settlement_item ADD COLUMN detail jsonb;
