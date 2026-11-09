# 71. Bank module: money accounts, vendor channels, receipts, settlements, card terminal

Audience: Flutter and web teams. Read `00-overview.md` first (auth, error envelope, paging, money, idempotency). Conventions here: money in paise (`...Paise`), instants ISO-8601 UTC, ids UUID. A page is `{ items, page, size, total, totalExact, hasMore }`; when `totalExact` is false show "10,000+" and keep paging with `hasMore`. Dates in report filters are `yyyy-MM-dd`, both days included, India time; default is the last 30 days, at most 400 days (400 `VALIDATION_FAILED` on `to`). Admin routes are ADMIN only; `/api/v1/pos/**` is CASHIER and ADMIN.

## 1. Purpose and the mental model

- A **money account** is a place money sits: `BANK`, `GATEWAY` (Razorpay...), `UPI` (a QR), `CARD_TERMINAL` (a swipe machine), `CASH` (the drawer). A gateway, UPI or card terminal "settles into" a BANK.
- A **payment channel** is what the cashier picks at payment time: a vendor (`provider`, free code such as `razorpay`, `paytm`, `manual`), optionally a terminal id, and the money account that receives the money. Several vendors can be active at once; a channel is just an active money account.
- Every receipt is tagged with a money account: online payments (the gateway account), counter lines (the chosen channel, else the shop default of the mode), refunds (follow their payment/bill). Old rows have no account and show as **Unassigned**.
- Settlements are batches the provider or bank paid out (UTR, fee, GST on the fee, net). Items inside are matched to our payments by reference and amount; anything that does not add up goes to the **exception queue**.
- Everything vendor-specific lives behind ports (`PaymentGateway`, `SettlementProvider`, `CardTerminal`). Razorpay is the only vendor adapter built. Paytm and others plug in later without changing any endpoint below.

## 2. Admin screens and flow

1. **Accounts**: list/create/edit/deactivate money accounts (section 4.1). Create at least: the bank, the Razorpay gateway account (provider `razorpay`, settles to the bank), each counter UPI QR, each card machine (provider e.g. `paytm`, identifier = terminal id), keep "Cash drawer".
2. **Defaults** (Config screen, `PUT /admin/config`): `pos.defaultAccount.CASH|UPI|CARD` (account id used when the till names none, and the pre-selected channel), `payment.gatewayAccountId` (account for online payments; blank = first active GATEWAY account of the payment's provider), `payment.onlineProvider` (gateway the customer app uses, blank = server default), `payment.settlementDays` (cycle, default 2), `pos.terminalProvider` (terminal adapter, default `manual`).
3. **Receipts** screen: summary cards from `/admin/receipts/summary`, drill into `/admin/receipts`.
4. **Settlements** screen: list, open one to see its items, "Import statement" (CSV), "Sync now".
5. **Exceptions** badge: open items from `/admin/settlements/exceptions?status=OPEN`; resolve each with a note.
6. **Unsettled** tab: money received, not yet settled, overdue ones highlighted.
7. **Account book**: running balance per account; add manual entries (opening balance, bank charge).
8. Order detail (admin): call `GET /admin/payments/by-order/{orderId}` for payer detail (the order endpoints themselves are unchanged).

## 3. Till flow: choosing the vendor

1. On entering the payment step call `GET /api/v1/pos/payment-channels` (cache per shift).
2. For each mode the cashier taps (CASH / UPI / CARD) show that mode's channels; pre-select the one with `default: true`. Several channels per mode means several vendors (for example a Paytm machine and a bank machine, a PhonePe QR and a Paytm QR).
3. Send the line with the chosen channel: `moneyAccountId` = channel's `moneyAccountId`, `terminalId` = channel's `terminalId`, `reference` = UPI ref / approval code / RRN the customer or machine shows.
4. Card machine without an API (`manual` terminal): `POST /pos/terminal/payments` (amount) shows instructions; cashier charges on the machine; `POST .../{id}/confirm` with approval code and RRN; put them in the line's `reference`. A terminal adapter that reports results itself (future Paytm) returns `mode: "QR"` with `qrPayload` / `qrImageUrl` or `PUSH`; poll `GET .../{id}` until `state` is not `PENDING`.
5. An old till that sends no account still works: CASH goes to the drawer, UPI/CARD to the configured default or stay Unassigned.

## 4. Endpoints

### 4.1 Money accounts (`/api/v1/admin/money-accounts`)

Account JSON: `{ id, name, kind, provider, identifier, settlesToAccountId, active, note, createdAt, updatedAt }`.

| Method and path | Purpose |
|---|---|
| `GET /` | Paged, A to Z. Filters `kind`, `active`, `q` (name/provider/identifier), `page`, `size` |
| `GET /{id}` | One account (404 if unknown) |
| `POST /` | Create. Optional `Idempotency-Key`. Body `{ name (1-80), kind, provider?, identifier? (60), settlesToAccountId?, note? (300) }` |
| `PUT /{id}` | Change the same fields. `kind` cannot change once the account is used (409 `ACCOUNT_IN_USE`) |
| `POST /{id}/deactivate`, `POST /{id}/activate` | Stop/resume offering for new receipts. Refused: last active CASH account (409 `LAST_CASH_ACCOUNT`), a bank that active accounts settle into (409 `ACCOUNT_IN_USE`) |
| `DELETE /{id}` | 204 only if nothing refers to it, else 409 `ACCOUNT_IN_USE` (deactivate instead) |
| `GET /{id}/book?from&to&page&size` | `{ moneyAccountId, accountName, openingBalancePaise, lines: Page<BookRow> }`. BookRow `{ at, type (RECEIPT, REFUND, PAYOUT, FEE, ENTRY), direction (IN/OUT), amountPaise, sourceType, sourceId, description, balancePaise }`. `sourceType`: ONLINE_PAYMENT, COUNTER_PAYMENT, ONLINE_REFUND, COUNTER_REFUND, SETTLEMENT, MANUAL. Purchase payments are not here yet: when that module posts them they appear as ENTRY rows with their own `sourceType` (extension point: `money_account_entry.source_type/source_id`) |
| `POST /{id}/entries` | Manual movement `{ direction IN/OUT, amountPaise, at?, note? }`; optional `Idempotency-Key`; 201 |

Validation errors: 400 `VALIDATION_FAILED` with `details.fields.{name|kind|provider|identifier|note|settlesToAccountId|direction|amountPaise}`; duplicate name 409 `DUPLICATE_ACCOUNT_NAME`. Only a GATEWAY/UPI/CARD_TERMINAL account may have `settlesToAccountId`, and it must be an active BANK.

### 4.2 Till (`/api/v1/pos`)

`GET /payment-channels` returns an array:
```json
[{ "mode": "CARD", "moneyAccountId": "…", "provider": "paytm", "name": "Paytm machine 1", "terminalId": "TID-77", "kind": "CARD_TERMINAL", "default": true },
 { "mode": "UPI", "moneyAccountId": "…", "provider": "manual", "name": "PhonePe QR", "terminalId": null, "kind": "UPI", "default": false },
 { "mode": "CASH", "moneyAccountId": "…", "provider": "manual", "name": "Cash drawer", "terminalId": null, "kind": "CASH", "default": true }]
```
Payment line (sale, offline sale, and refund lines) is additive: `{ mode, amountPaise, reference?, moneyAccountId?, terminalId? }`. Errors: 400 `fields["payments[i].moneyAccountId"]` when the account does not exist, is switched off (an offline bill keeps an account switched off after the sale), does not fit the mode (CASH needs a CASH account; UPI: UPI/GATEWAY/BANK; CARD: CARD_TERMINAL/GATEWAY/BANK) or is sent on a CREDIT line. Returns: a refund line follows the account of the bill's line of the same mode unless the refund line names one.

Terminal (`/pos/terminal/payments`), session JSON `{ id, reference, provider, amountPaise, state (PENDING|APPROVED|DECLINED|CANCELLED|EXPIRED), mode (MANUAL|QR|PUSH), instructions, qrPayload, qrImageUrl, approvalCode, rrn, moneyAccountId, createdAt }`:

- `POST /` body `{ amountPaise (1..100000000), reference? (60), moneyAccountId? }`, optional `Idempotency-Key`; 201. Same reference and amount returns the same session; same reference with another amount 409 `REFERENCE_REUSED`. 503 `TERMINAL_NOT_CONFIGURED` when the selected adapter is missing or not set up.
- `GET /{id}` poll. `POST /{id}/confirm` `{ approvalCode (1-40), rrn? }` (manual terminals only; 409 `TERMINAL_CONFIRMS_ITSELF` otherwise; 409 `TERMINAL_PAYMENT_CLOSED` if cancelled). `POST /{id}/cancel` (a finished session is returned unchanged).

### 4.3 Receipts: all vendors in one view (admin)

`GET /api/v1/admin/receipts?provider&account&method&from&to&page&size` returns a Page of
```json
{ "source": "ONLINE_ORDER|POS_SALE", "id": "…", "provider": "razorpay", "moneyAccountId": "…", "accountName": "Razorpay", "terminalId": null,
  "method": "UPI", "payer": "pa***@okhdfc", "reference": "pay_Abc", "utr": "…", "grossPaise": 12500, "feePaise": 200, "taxPaise": 36, "netPaise": 12264,
  "status": "CAPTURED", "settlementRef": "setl_1", "at": "…", "documentNo": "VSM-1012 or invoice no" }
```
`method` is UPI, CARD, CASH, NETBANKING, WALLET or the online word. Payer is masked (UPI id `ab***@bank`, card `Visa ****1111`, or bank); a full card number is never stored. Counter rows have no fee/net until a statement import matches them. `provider` is `counter` for counter lines without an account. `GET /admin/receipts/summary?from&to` returns an array `{ provider, moneyAccountId, accountName ("Unassigned" when null), method, receipts, grossPaise, feePaise, taxPaise }` (at most 500 groups) for per-vendor and per-account totals.

`GET /admin/bank-reports/receipts?from&to&page&size`: gross, refunded and net per account and method (`moneyAccountId` null = Unassigned). `GET /admin/bank-reports/fees?from&to`: per month and account `{ month "YYYY-MM", payments, grossPaise, feePaise, taxPaise (GST on fee: input credit), missingFee }`.

`GET /admin/payments/by-order/{orderId}`: array of `{ paymentId, provider, status, amountPaise, instrument, upiVpa, cardNetwork, cardLast4, bank, feePaise, taxPaise, netPaise, settlementRef, utr, settledAt, moneyAccountId, accountName, providerRef, capturedAt }`. Show only to admin; fields are null until the provider reported them.

### 4.4 Settlements (`/api/v1/admin/settlements`)

- `GET /?from&to&accountId&provider&source(GATEWAY_API|CSV)&page&size`: Page of `{ id, provider, providerSettlementId, utr, grossPaise, feePaise, taxPaise, netPaise, status, settledAt, moneyAccountId, accountName, source, items, unmatchedItems }`.
- `GET /{id}`, `GET /{id}/items?unmatchedOnly&page&size` (default size 50): item `{ id, kind (PAYMENT|REFUND|ADJUSTMENT|FEE), providerEntityId, grossPaise, feePaise, taxPaise, netPaise, method, settledAt, utr, reference, matched, paymentId, orderId, orderNo, refundId, posPaymentId, invoiceNo }`.
- `GET /unsettled?overdueOnly&page&size`: Page of `{ row: { source ONLINE|COUNTER, id, reference (order no or invoice no), moneyAccountId, accountName, amountPaise, receivedAt }, expectedBy, overdue }`. Expected = received + `payment.settlementDays`; overdue after one more day. Counter lines appear only for CARD lines on CARD_TERMINAL/GATEWAY accounts.
- `GET /exceptions?status(OPEN|RESOLVED)&kind(OVERDUE_UNSETTLED|UNMATCHED_ITEM|AMOUNT_MISMATCH)&page&size`: item `{ id, kind, settlementId, settlementItemId, paymentId, reference, expectedPaise, actualPaise, status, note, createdAt, resolvedAt }`. `POST /exceptions/{id}/resolve` `{ note (1-300) }` returns the item; resolving again changes nothing. An overdue exception closes itself (note "Settled") when the payment later settles.
- `POST /sync` `{ provider, days? (1..60, default 7) }` returns `{ provider, settlementsSeen, settlementsStored, itemsStored, skipped }` (`skipped: true` = another server is already syncing). 404 unknown provider, 503 `SETTLEMENT_NOT_CONFIGURED`. The nightly job (04:30) and Razorpay's `settlement.processed` webhook do the same automatically.
- `POST /import` multipart: `file` (CSV, 2 MB, 5000 rows), `moneyAccountId` (required), optional `provider` (default `manual`), `utr`, and column mapping `dateColumn, referenceColumn, amountColumn, feeColumn, taxColumn, descriptionColumn` (defaults: date, reference, amount, fee, tax, description; names case-insensitive), `dateFormat` (e.g. `dd/MM/yyyy`; blank = ISO), `amountUnit` (RUPEES default, or PAISE), `delimiter` (comma default, `;` or `tab`). A negative amount is an adjustment (matched automatically); a positive row is matched by reference and amount to an online payment's provider payment id or a counter UPI/CARD line's reference. Result `{ settlementId, alreadyImported, rows, matched, unmatched, netPaise }`. The same file for the same account is a no-op (`alreadyImported: true`). 400 `CSV_INVALID` with `details.problems` (first 20 `"row N: …"`) or `VALIDATION_FAILED` on a mapping field.

## 5. Dart-friendly model sketches

```dart
enum AccountKind { BANK, GATEWAY, UPI, CARD_TERMINAL, CASH }
class MoneyAccount { String id, name, provider, identifier, note; AccountKind kind; String? settlesToAccountId; bool active; }
class PayChannel { String mode, moneyAccountId, provider, name, kind; String? terminalId; bool isDefault; /* json "default" */ }
class PayLine { String mode; int amountPaise; String? reference, moneyAccountId, terminalId; }
class ReceiptLine { String source, id, provider, method, status; String? moneyAccountId, accountName, terminalId, payer, reference, utr, settlementRef, documentNo;
  int grossPaise; int? feePaise, taxPaise, netPaise; DateTime at; }
class Settlement { String id, provider, providerSettlementId, status, source; String? utr, moneyAccountId, accountName; DateTime? settledAt;
  int grossPaise, feePaise, taxPaise, netPaise, items, unmatchedItems; }
class SettlementException { String id, kind, status; String? settlementId, paymentId, reference, note; int? expectedPaise, actualPaise; }
class TerminalSession { String id, reference, provider, state, mode; int amountPaise; String? instructions, qrPayload, qrImageUrl, approvalCode, rrn; }
```

## 6. Behaviour the frontend must know

- Never show "Unassigned" as an error: it is the honest bucket for history and for lines nobody tagged. Offer "assign default account" in settings.
- `payer` and card fields are masked server-side; do not try to rebuild a card number.
- Fee, tax and net are null until the provider reports them (capture detail, then the settlement). `missingFee` in the fees report counts those.
- Reports read bounded ranges; show `totalExact:false` as "10,000+".
- Payment lines from an old app version (no account) keep working.
- The customer app picks the online gateway from `payment.onlineProvider`; there is no per-order override yet.

## 7. Adding a vendor later (Paytm, Cashfree...)

1. Payment gateway: implement `PaymentGateway` (create, session, parseWebhook, refund, fetchStatus) as a `@Component`; it appears in `PaymentGatewayRegistry` by `provider()`; select it with `payment.onlineProvider`.
2. Settlements: implement `SettlementProvider` (`provider()` same code, `configured()`, `listSettlements`, `settlementDetail`, `fetchPaymentDetail`) as a `@Component`. The nightly job and `/admin/settlements/sync` pick it up automatically; each provider is synced on its own and one failing provider does not stop the others. Map the vendor's per-transaction UTR, commission, GST, payment mode and RRN into `Item`; put anything else vendor-specific in the nullable `detail` jsonb columns (`payment.detail`, `settlement_item.detail`).
3. Terminal: implement `CardTerminal` (`requestPayment`, `status`, `cancel`; return `Mode.QR` with `qrPayload` or `qrImageUrl` for a QR, `Mode.PUSH` for a machine that prompts itself, `manualConfirmation()` false when it reports results) and set `pos.terminalProvider` to its code. No API detail of Paytm is assumed here.
4. Create the matching money accounts (provider code, identifier = terminal id) and they show up as till channels.

## 8. Not verified without a live account

`RazorpaySettlementProvider` was written from Razorpay's public API reference and tested only against canned HTTP responses. To check with the new Razorpay account: the list parameters (`from`/`to`/`count`/`skip`), that `fees` includes GST and `tax` is the GST part, the recon `combined` report fields (`credit`, `debit`, `fee`, `tax`, `settlement_id`, `settlement_utr`, `settled_at`) and that its `day` filter is the settlement date, the `settlement.processed` webhook payload shape (`payload.settlement.entity.id`), and that the account has the settlement/recon APIs enabled. The webhook must also subscribe to `settlement.processed`.

## 9. Implementation checklist

- [ ] Accounts CRUD screen and the defaults in Config.
- [ ] Till: load channels, per-mode chooser with default, send `moneyAccountId`/`terminalId`/`reference`.
- [ ] Manual terminal confirm dialog (approval code, RRN); QR/PUSH polling for future adapters.
- [ ] Receipts list + summary with filters; fees report; receipts-by-account report.
- [ ] Settlements list/detail, CSV import with column mapping, sync button.
- [ ] Exceptions queue with resolve note; unsettled/overdue tab.
- [ ] Account book with running balance and manual entries.
- [ ] Payer detail block on the admin order screen.
- [ ] Handle errors: `ACCOUNT_IN_USE`, `DUPLICATE_ACCOUNT_NAME`, `LAST_CASH_ACCOUNT`, `CSV_INVALID`, `TERMINAL_NOT_CONFIGURED`, `SETTLEMENT_NOT_CONFIGURED`, `REFERENCE_REUSED`.
