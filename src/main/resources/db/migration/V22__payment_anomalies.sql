-- Wrong-amount and duplicate online payments.
--
-- 1. A refund remembers which provider payment it gives back. Until now it was found through the payment row's provider_ref,
--    which would send an old refund to the wrong payment once that reference changed (a wrong-amount capture followed by a
--    correct one on the same provider order). NULL means "the payment row's own provider_ref" (cancel, reject, return).
alter table refund add column provider_payment_ref text;

-- Refunds queued by the old mismatch code pointed at the payment's reference as it was at that moment: pin it.
update refund r set provider_payment_ref = p.provider_ref
  from payment p where p.id = r.payment_id and p.status = 'MISMATCH' and r.reason = 'amount mismatch';

-- Replaying a payment never queues a second automatic refund for the same provider payment and reason.
create unique index refund_anomaly_once on refund (provider_payment_ref, reason)
  where provider_payment_ref is not null;

-- 2. What went wrong, for the admin list and for reports.
create table payment_anomaly (
  id             uuid primary key,
  payment_id     uuid        not null references payment(id),
  order_id       uuid        not null,
  kind           text        not null check (kind in ('MISMATCH', 'DUPLICATE')),
  expected_paise bigint      not null,
  received_paise bigint      not null,
  provider_ref   text        not null,                -- the provider payment that is being refunded
  created_at     timestamptz not null
);
create index payment_anomaly_created_idx on payment_anomaly (created_at desc);
