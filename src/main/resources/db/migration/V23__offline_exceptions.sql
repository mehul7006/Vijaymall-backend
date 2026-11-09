-- Offline counter bills are recorded as they were rung at the till. Anything that differs from what the system would have done
-- (price, GST rate, discount, stock, product switched off) is kept here as an exception for a manager to look at; the bill itself
-- is never refused for it.
ALTER TABLE pos_sale ADD COLUMN needs_review boolean NOT NULL DEFAULT false;

CREATE TABLE pos_offline_exception (
  id             uuid primary key,
  sale_id        uuid        not null references pos_sale(id),
  kind           text        not null check (kind in ('PRICE_CHANGED', 'GST_CHANGED', 'OVERSOLD', 'DISCOUNT', 'PRODUCT_INACTIVE')),
  detail         text        not null,
  product_id     uuid references product(id),
  expected_value text,
  actual_value   text,
  created_at     timestamptz not null,
  reviewed_by    uuid,
  reviewed_at    timestamptz,
  review_note    text
);
CREATE INDEX pos_offline_exception_sale ON pos_offline_exception (sale_id);
CREATE INDEX pos_offline_exception_open ON pos_offline_exception (created_at) WHERE reviewed_at IS NULL;
CREATE INDEX pos_sale_needs_review ON pos_sale (created_at) WHERE needs_review;
