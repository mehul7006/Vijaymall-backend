-- Goods that came back with a "defective" claim. A claim is not a fact: each batch waits in PENDING_REVIEW (not on the shelf,
-- not yet counted as defective) until staff or the owner look at it and decide:
--   confirm defective  -> HELD, then the owner writes it off or sends it back to the supplier;
--   not defective      -> RESTOCKED: added back to sellable stock.
-- Confirmed-defective goods are never added back to the sellable stock.
create table defective_stock (
  id            uuid primary key,
  product_id    uuid        not null references product(id),
  qty           int         not null check (qty > 0),
  source        text        not null,                -- ORDER_RETURN or POS_RETURN
  ref_id        uuid,                                -- the return it came from
  note          text,                                -- the reason given at the return
  status        text        not null default 'PENDING_REVIEW'
                check (status in ('PENDING_REVIEW', 'HELD', 'WRITTEN_OFF', 'RETURNED_TO_SUPPLIER', 'RESTOCKED')),
  created_at    timestamptz not null,
  created_by    uuid,
  reviewed_at   timestamptz,
  reviewed_by   uuid,
  review_note   text,
  resolved_at   timestamptz,
  resolved_by   uuid,
  resolve_note  text
);
create index defective_stock_status_idx on defective_stock (status, created_at desc);
create index defective_stock_product_idx on defective_stock (product_id, status);
