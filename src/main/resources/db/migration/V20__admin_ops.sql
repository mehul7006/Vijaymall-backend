-- Remembers which admin/staff actions were already carried out, so a retry or a double tap with the same Idempotency-Key
-- does not apply the same change twice (stock adjustment, coin gift, credit write-off, new product, ...).
create table admin_op (
  action      text        not null,
  op_key      text        not null,
  actor       uuid,
  result_ref  text,                  -- id of what the action created or touched (or its full result as JSON)
  req_hash    text,                  -- fingerprint of the request, so a reused key with a different request is caught
  created_at  timestamptz not null default now(),
  primary key (action, op_key)
);
create index admin_op_created_idx on admin_op (created_at);
