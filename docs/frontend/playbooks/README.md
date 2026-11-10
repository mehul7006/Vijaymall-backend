# Implementation playbooks (P1 to P5)

The numbered guides (`00` to `74`) describe the API. The playbooks turn them into build instructions: for each feature the goal, who may use it, fields and validation, the exact API sequence, derived values, edge cases (crash, retry, offline), Dart sketches and acceptance tests, plus an error-code cheat sheet and a build order. They were checked against the controllers, services and integration tests; where an older guide disagreed with the code, the code won and the difference was listed at the end of the playbook and then fixed in the guide (table below).

Start with `../01-implementation-guide.md` (what to build, what to configure) and `../00-overview.md` (conventions), then read the playbook for your app.

## The playbooks

| File | Covers | Who should read it |
|---|---|---|
| [P1-orders-coupons-returns-payments.md](P1-orders-coupons-returns-payments.md) | Cart and quote, coupons and coins, flash prices in quotes, placing COD/online orders, online payment (session, result, timeout, duplicates), order tracking, cancel/reject, reorder, returns and refund maths, refunds queue, admin order desk, wallet ledger | Customer app and admin app developers |
| [P2-purchases-suppliers-labels.md](P2-purchases-suppliers-labels.md) | Suppliers, the purchase form (GST, discount, freight), payments and payables, purchase returns, supplier statement, credit and refunds, invoice attachment, limited edits, purchase reports, store barcodes, label templates and printing | Admin app developers (back office, inventory) |
| [P3-counter-till-and-card-machine.md](P3-counter-till-and-card-machine.md) | Cashier till: sign-in, shifts, catalog sync, cart and quote, payment lines, credit sales, the unified card/UPI machine flow (channel choice, Paytm and manual machines, void), receipts, holds, returns, repayments, offline billing, flash prices | Till (cashier) app developers |
| [P4-bank-settlements-receipts.md](P4-bank-settlements-receipts.md) | Money accounts, defaults and gateway settings, account book, till channels, the shared terminal session, payer detail, receipts and summary, settlements, statement import (incl. Paytm Settlement Report), exceptions, unsettled and fee reports | Admin app developers (money screens) and the till developer for the channel/terminal parts |
| [P5-stock-lots-pricing.md](P5-stock-lots-pricing.md) | Stock lots per variant, the `lotPriced` / `trackExpiry` switches (`GET`/`PUT lot-settings`), purchase receiving into lots, lot-aware scan and quote at the till, online CAP policy, flash precedence, expiring list and write-off, valuation, margin, lot check, settings, offline `lot` field | Admin, till and shop app developers |

The GST and HSN master (suggest the rate and HSN while the user types a product or purchase line) has a guide only, no playbook yet: `../75-gst-master.md`.

## Reading order

1. `../01-implementation-guide.md`, then `../00-overview.md` (once).
2. Customer app: P1 (then `../10`, `../20` for the field-level reference; P5 section 6 for the online lot price cap).
3. Till app: P3, then P4 sections 5 and 6 (channels and the terminal session), then P5 sections 5 and 13 (lot prices at the till and offline).
4. Admin app: P1 sections 10 to 12 (returns, refunds, order desk), P2, P4, P5.
5. Whenever two texts disagree, trust the playbook, then the addendum of a numbered guide, then the older body text.

## Resolved doc differences

Each difference found by the playbook authors was checked against the code and the guide text was corrected in place (minimal edits; the Addendum structure was kept). "Done" means the guide now states what the code does.

| Playbook item | What was wrong | File fixed |
|---|---|---|
| P1 #1 | order list `itemCount` is units, not lines | `20` |
| P1 #2 | "the API does not return the payment deadline" (`paymentExpiresAt` exists) | `20` |
| P1 #3 | `PAYMENT_FAILED` is never published | `20` |
| P1 #4 | quote `COUPON_INVALID` reasons `ITEM`, `FLASH_ITEMS` missing | `20` |
| P1 #5 | quote line fields `flashSaleId`, `listPricePaise`, `flashDiscountPaise` | `20` |
| P1 #6 | return view lacked `couponClawbackPaise`, `items[].couponSharePaise` | `20` |
| P1 #7 | single `PUT /cart` line over 99 error text | `20` |
| P1 #8, #9 | `cancelReason` for reject and for cancel through `status` | `20` |
| P1 #10 | preview accepts a blank `reason`, the real call does not | `20` |
| P1 #11 | return response lists one row per order line | `20` |
| P1 #12 | a return does not change the order `paymentStatus` | `20` |
| P1 #13 | return idempotency key is global | `20` |
| P1 #14 | ONLINE is off by default | `20` |
| P1 #15 | `GET /wallet` returns `expiryMonths` | `10` |
| P1 #16 | `GET /coupons` target fields | `10` |
| P1 #17 | address pincode must be 6 digits | `10` |
| P1 #18 | code comment only; `20` already states both transition checks | no change needed |
| P2 #1 | OVERDUE is a flag, not a status | no change needed (`70` is consistent) |
| P2 #2 | `CREDIT_NOTE` payment mode | `70` |
| P2 #3 | "not built" list (statement, refunds, file upload, edits, lots) | `70` (section 11 and the lots sentence now point to Addendum 1 and `74`) |
| P2 #4 | samples lacked `hasAttachment`, `attachmentUrl`, `balancePaise`, `creditPaise` | `70` |
| P2 #5 | `PUT` supplier replaces all fields; which writes take no key | `70` |
| P2 #6 | repeated key with a different body: 409 `IDEMPOTENCY_KEY_REUSED` for purchases (counter sales, returns, repayments still return the first result) | `30`, `70`, `00` |
| P2 #7, #8 | label `fields` keys and `fromStock` matching | `72` |
| P2 #9 | report range limit 366 days inclusive; `ageing` has none | `70` |
| P2 #10 | no preview endpoint; estimate can differ by paise | `70` |
| P2 #11 | template `...a002` id inferred, not tested | cannot be verified from code (not a guide error) |
| P2 #12 | statement row nullability | no change needed (the Dart sketch tolerates it) |
| P3 #1 | no receipt endpoint for returns and repayments | `30` |
| P3 #2 | quote accepts `manualDiscount` | `30` |
| P3 #3 | financial year follows `soldAt` | `30` |
| P3 #4 | upload result carries `exceptions` incl. `OVERSOLD` | `30` |
| P3 #5 | manual discount `reason` at most 100 | `30` |
| P3 #6 | credit picker gives a masked mobile; cashier types the full number | `30` |
| P3 #7 | `GET /pos/payment-channels` (now exists, Paytm is a channel) | `73`, `71` |
| P3 #8 | `terminalPaymentId` refused only on as-rung offline bills | `73` |
| P3 #9 | replay returns 201; key required on the Paytm start | `73` |
| P3 #10 | UPI vs CARD decided by "contains UPI" | `73` |
| P3 #11 | `terminalPaymentId` on a repayment is ignored | `73` |
| P3 #12 | `updatedSince` never returns deactivated products; do a daily full download | `30` |
| P3 #13 | holds are shared across cashiers | no change needed (`30` already says so) |
| P4 #1 | two terminal flows (now unified: one flow, `/pos/terminals` and `/pos/terminal-payments*` are twins on the same rows) | `71`, `73` |
| P4 #2 | `default` flag semantics, which kinds are offered | `71` |
| P4 #3 | receipt `status` values | `71` |
| P4 #4 | manual entry limits | `71` |
| P4 #5 | payment line vs terminal `reference` limits | `71` |
| P4 #6 | CSV size limits (2 MB service, 5 MB web layer) | `71` |
| P4 #7 | sync with a missing provider is 404 | `71` |
| P4 #8 | `account` vs `accountId` filter names | `71` |
| P4 #9 | bank report row fields | `71` |
| P4 #10 | purchase payments post nothing to the book | `71` |
| P4 #11 | unsettled filters | `71` |
| P4 #12 | `Idempotency-Key` is optional on the bank writes | `00`, `71` |
| P5 #1 | switch state was not readable (now `GET /admin/variants/{id}/lot-settings`) | `74` |
| P5 #2 | missing response bodies (lot-settings, write-off, expiring), write-off reason limit | `74` |
| P5 #3 | `unitCost` is paise with 4 decimals | `74` |
| P5 #4 | split lines are counter-only | `74` |
| P5 #5 | `lot` on scan vs item list | `74` |
| P5 #6 | offline `PRICE_CHANGED` compares per line to the catalog price | `74` |
| P5 #7 | `lotPriced` takes effect at the next stock movement | `74` |
| P5 #8 | lot list of an unknown variant is empty, not 404 | `74` |
| P5 #9 | margin and unknown-cost lots | `74` |
| P5 #10 | `expiryDate` now drives the lot expiry | `P2` (playbook) |
| P5 #11 | valuation total is a lower bound | `74` |

Counts: 66 differences listed in the playbooks; 61 corrected in a guide or playbook, 4 needed no change (P1 #18, P2 #1, P2 #12, P3 #13), 1 cannot be verified from code (P2 #11).

Other stale statements fixed while doing this: `70` "stock lots not built" (two places), `00` and `01` file tables now list `71`, `74` and this folder, `01` section 5 lists all `paytm.*`, `payment.*`, `pos.*` and `inventory.*` settings.

## Not verifiable from the code

The Paytm integration against a real Paytm server (`73` section 8), the Razorpay settlement/recon responses (`71` section 8), the JSON `details` of 422 `TOTAL_MISMATCH` beyond what tests assert, whether a missing label-template `fields` key is a 400 or defaults (`72`), and the exact error body for a multipart request over 5 MB.
