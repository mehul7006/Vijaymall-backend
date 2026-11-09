# 71. Bank module: money accounts, vendor channels, receipts, settlements, card terminal

Audience: Flutter and web teams. Read `00-overview.md` first (auth, error envelope, paging, money, idempotency). Conventions here: money in paise (`...Paise`), instants ISO-8601 UTC, ids UUID. A page is `{ items, page, size, total, totalExact, hasMore }`; when `totalExact` is false show "10,000+" and keep paging with `hasMore`. Dates in report filters are `yyyy-MM-dd`, both days included, India time; default is the last 30 days, at most 400 days (400 `VALIDATION_FAILED` on `to`). Admin routes are ADMIN only; `/api/v1/pos/**` is CASHIER and ADMIN.

## 1. Purpose and the mental model

- A **money account** is a place money sits: `BANK`, `GATEWAY` (Razorpay...), `UPI` (a QR), `CARD_TERMINAL` (a swipe machine), `CASH` (the drawer). A gateway, UPI or card terminal "settles into" a BANK.
- A **payment channel** is what the cashier picks at payment time: a vendor (`provider`, free code such as `razorpay`, `paytm`, `manual`), optionally a terminal id, and the money account that receives the money. Several vendors can be active at once; a channel is just an active money account.
- Every receipt is tagged with a money account: online payments (the gateway account), counter lines (the chosen channel, else the shop default of the mode), refunds (follow their payment/bill). Old rows have no account and show as **Unassigned**.
- Settlements are batches the provider or bank paid out (UTR, fee, GST on the fee, net). Items inside are matched to our payments by reference and amount; anything that does not add up goes to the **exception queue**.
- Everything vendor-specific lives behind ports (`PaymentGateway`, `SettlementProvider`, `CardTerminal`, `StatementParser`). Razorpay (online gateway and settlements) and Paytm (wireless card machine, settlements and its Settlement Report file) are built; `manual` needs no vendor API. Several vendors are active at the same time and the cashier picks the channel for each payment. Paytm details are in `73-paytm-terminal.md`.
- **One card-machine flow for every vendor.** The channel the cashier picks decides which machine is used: the provider of the channel's money account (`manual`, `paytm`, ...) selects the adapter. `pos.terminalProvider` is only the default for a channel whose provider has no adapter, or when no channel is named. The same four calls (start, poll, confirm/cancel, put the session id on the bill line) serve all of them.

## 2. Admin screens and flow

1. **Accounts**: list/create/edit/deactivate money accounts (section 4.1). Create at least: the bank, the Razorpay gateway account (provider `razorpay`, settles to the bank), each counter UPI QR (a Paytm QR sticker is a `UPI` account with provider `paytm`), keep "Cash drawer". A **Paytm machine needs no hand-made account**: adding it on the machines screen (`/admin/payment-terminals`, optional `settlesToAccountId` = the bank) creates its `CARD_TERMINAL` account (provider `paytm`, identifier = terminal id, name = machine name) and keeps the two in step (switching either off switches the other off). A bank machine without an API is a `CARD_TERMINAL` account with provider `manual`.
2. **Defaults** (Config screen, `PUT /admin/config`): `pos.defaultAccount.CASH|UPI|CARD` (account id used when the till names none, and the pre-selected channel), `payment.gatewayAccountId` (account for online payments; blank = first active GATEWAY account of the payment's provider), `payment.onlineProvider` (gateway the customer app uses, blank = server default), `payment.settlementDays` (cycle, default 2), `pos.terminalProvider` (the default terminal adapter, default `manual`: used only for a channel whose provider has no adapter or when the till names no channel).
3. **Receipts** screen: summary cards from `/admin/receipts/summary`, drill into `/admin/receipts`.
4. **Settlements** screen: list, open one to see its items, "Import statement" (CSV), "Sync now".
5. **Exceptions** badge: open items from `/admin/settlements/exceptions?status=OPEN`; resolve each with a note.
6. **Unsettled** tab: money received, not yet settled, overdue ones highlighted.
7. **Account book**: running balance per account; add manual entries (opening balance, bank charge).
8. Order detail (admin): call `GET /admin/payments/by-order/{orderId}` for payer detail (the order endpoints themselves are unchanged).

## 3. Till flow: choosing the vendor

1. On entering the payment step call `GET /api/v1/pos/payment-channels` (cache per shift).
2. For each mode the cashier taps (CASH / UPI / CARD) show that mode's channels; pre-select the one with `default: true` (the saved default, or the only channel of the mode; the first CASH channel is the default for cash). A UPI or CARD mode with several channels and no valid saved default has NO channel flagged: make the cashier pick. A `GATEWAY` account is offered under both UPI and CARD; a `BANK` account is never offered. Several channels per mode means several vendors (for example a Paytm machine and a bank machine, a PhonePe QR and a Paytm QR).
3. **Cash, or a UPI/card line with no machine** (customer shows a UPI reference, an old card machine): send the line with the chosen channel: `moneyAccountId` = channel's `moneyAccountId`, `terminalId` = channel's `terminalId`, `reference` = the UPI ref / approval code / RRN.
4. **A card or UPI machine** (the channel's `provider` has a terminal adapter, today `paytm` and `manual`):
   1. `POST /api/v1/pos/terminal/payments` `{ amountPaise, reference, moneyAccountId = channel.moneyAccountId }` -> 201 session. `reference` is the till's key for the payment (use the cart or bill reference, new for every attempt); a repeat with the same reference and amount returns the same session.
   2. Poll `GET /api/v1/pos/terminal/payments/{id}` every `pollAfterSeconds` while `open` is true (a `manual` session never changes by itself; the cashier presses Confirm).
   3. `manual` machine: charge on the machine, then `POST .../{id}/confirm` `{ approvalCode, rrn? }`. `paytm` machine: nothing to type, the state turns `APPROVED` by itself.
   4. When `state` is `APPROVED`, ring the sale with a payment line `{ mode, amountPaise, terminalPaymentId: <session id> }` (mode CARD, or UPI when `payMethod` is `UPI`). **That one id is all the sale needs**: the server takes the machine's money account, terminal id, the RRN as `reference` and the masked payer detail from the session. You may omit `moneyAccountId`/`terminalId`/`reference`; naming another account than the machine's is a 400.
5. An old till that sends no account still works: CASH goes to the drawer, UPI/CARD to the configured default or stay Unassigned.

### Session JSON (one shape for every vendor)

```json
{ "id": "uuid", "reference": "INV-1042", "provider": "paytm", "amountPaise": 12500,
  "state": "PENDING", "providerStatus": "PENDING", "mode": "PUSH", "open": true,
  "instructions": "Waiting for the customer to pay on the machine", "message": "Waiting for the customer to pay on the machine",
  "qrPayload": null, "qrImageUrl": null, "approvalCode": null, "rrn": null,
  "moneyAccountId": "uuid", "terminalId": "AB12CD34", "terminalName": "Counter 1",
  "payMethod": null, "cardScheme": null, "maskedCard": null, "issuingBank": null, "upiVpa": null,
  "canCancel": true, "cancelOnMachine": false, "canVoid": false, "pollAfterSeconds": 10,
  "posSaleId": null, "createdAt": "...", "finishedAt": null }
```

- `state` is the generic word: `PENDING`, `APPROVED`, `DECLINED`, `CANCELLED`, `EXPIRED`. `providerStatus` is the machine vendor's own word (Paytm: REQUESTED, PENDING, UNKNOWN, SUCCESS, FAILED, CANCELLED, EXPIRED, VOIDED; for `manual` it equals `state`). Mapping for Paytm: REQUESTED/PENDING/UNKNOWN -> PENDING, SUCCESS -> APPROVED, FAILED -> DECLINED, CANCELLED and VOIDED -> CANCELLED, EXPIRED -> EXPIRED.
- A Paytm `EXPIRED` session is still watched for 15 minutes: a late payment turns it `APPROVED`. Keep the id reachable ("check again") before taking another payment on that machine.
- `cancelOnMachine` is true after the cashier pressed Cancel and the machine has not yet reported it: show "Press Cancel on the machine".
- Fields after `moneyAccountId` are filled only for machines that report them (Paytm); `manual` sessions have them null.
- Errors of the terminal calls: `TERMINAL_NOT_CONFIGURED` (503, adapter not set up), `REFERENCE_REUSED` (409), `TERMINAL_BUSY` (409, Paytm machine has an open payment; `details.openPaymentId`), `TERMINAL_REJECTED` (422), `CANCEL_ON_MACHINE` / `ALREADY_PAID` (409, Paytm cancel), `NOT_VOIDABLE` (409), `TERMINAL_CONFIRMS_ITSELF` (409), `TERMINAL_PAYMENT_CLOSED` (409). On the sale: `TERMINAL_PAYMENT_NOT_PAID` (422), `TERMINAL_PAYMENT_USED` (409), `TERMINAL_PAYMENT_AMOUNT` (422), `TERMINAL_PAYMENT_MODE` (422, Paytm payment taken by UPI on a CARD line or the reverse).

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
| `GET /{id}/book?from&to&page&size` | `{ moneyAccountId, accountName, openingBalancePaise, lines: Page<BookRow> }`. BookRow `{ at, type (RECEIPT, REFUND, PAYOUT, FEE, ENTRY), direction (IN/OUT), amountPaise, sourceType, sourceId, description, balancePaise }`. `sourceType`: ONLINE_PAYMENT, COUNTER_PAYMENT, ONLINE_REFUND, COUNTER_REFUND, SETTLEMENT, MANUAL. Purchase payments are not in the book: they store an unvalidated `moneyAccountId` but post nothing to any account (extension point: `money_account_entry.source_type/source_id`) |
| `POST /{id}/entries` | Manual movement `{ direction IN/OUT, amountPaise (1..10,000,000,000), at? (default now), note? (max 200) }`; optional `Idempotency-Key`; 201 |

Validation errors: 400 `VALIDATION_FAILED` with `details.fields.{name|kind|provider|identifier|note|settlesToAccountId|direction|amountPaise}`; duplicate name 409 `DUPLICATE_ACCOUNT_NAME`. Only a GATEWAY/UPI/CARD_TERMINAL account may have `settlesToAccountId`, and it must be an active BANK.

### 4.2 Till (`/api/v1/pos`)

`GET /payment-channels` returns an array:
```json
[{ "mode": "CARD", "moneyAccountId": "…", "provider": "paytm", "name": "Paytm machine 1", "terminalId": "TID-77", "kind": "CARD_TERMINAL", "default": true },
 { "mode": "UPI", "moneyAccountId": "…", "provider": "manual", "name": "PhonePe QR", "terminalId": null, "kind": "UPI", "default": false },
 { "mode": "CASH", "moneyAccountId": "…", "provider": "manual", "name": "Cash drawer", "terminalId": null, "kind": "CASH", "default": true }]
```
Payment line (sale, offline sale, and refund lines) is additive: `{ mode, amountPaise, reference? (max 100), moneyAccountId?, terminalId? (max 60), terminalPaymentId? (sale lines only, see section 3) }`. Note the session `reference` of the terminal start is max 60 (Paytm 50), a different field. Errors: 400 `fields["payments[i].moneyAccountId"]` when the account does not exist, is switched off (an offline bill keeps an account switched off after the sale), does not fit the mode (CASH needs a CASH account; UPI: UPI/GATEWAY/BANK; CARD: CARD_TERMINAL/GATEWAY/BANK) or is sent on a CREDIT line. Returns: a refund line follows the account of the bill's line of the same mode unless the refund line names one.

Terminal (`/pos/terminal/payments`), session JSON as in section 3:

- `POST /` body `{ amountPaise (1..100000000), reference? (60; a Paytm machine takes at most 50), moneyAccountId? }`; with an `Idempotency-Key` and no reference the reference is made from the key. 201. The adapter is the one of the chosen channel's provider. Same reference and amount returns the same session; the same reference with another amount or on another machine is 409 `REFERENCE_REUSED`. 503 `TERMINAL_NOT_CONFIGURED` when the adapter is missing or not set up. This call is not wrapped in a database transaction because a Paytm machine is called over the network.
- `GET /{id}` poll (for a Paytm session it also checks the machine when a check is due). `POST /{id}/confirm` `{ approvalCode (1-40), rrn? }` (`manual` machines only; 409 `TERMINAL_CONFIRMS_ITSELF` otherwise; 409 `TERMINAL_PAYMENT_CLOSED` if cancelled). `POST /{id}/cancel` (a finished session is returned unchanged; a Paytm session answers 409 `CANCEL_ON_MACHINE` or `ALREADY_PAID`, see 73). `POST /{id}/void` (Paytm same-day void; 409 `NOT_VOIDABLE` for a machine that cannot).
- The session id is what the sale line carries as `terminalPaymentId`. A Paytm session id is the Paytm payment id (no second row); a `manual` session id is its own row, marked used by the bill.
- The Paytm-specific calls (`GET /pos/terminals`, `/pos/terminal-payments...`) remain as the provider-level API and read and write the very same rows.

### 4.3 Receipts: all vendors in one view (admin)

`GET /api/v1/admin/receipts?provider&account&method&from&to&page&size` (the money-account filter is `account` here, but `accountId` on the settlements list) returns a Page of
```json
{ "source": "ONLINE_ORDER|POS_SALE", "id": "…", "provider": "razorpay", "moneyAccountId": "…", "accountName": "Razorpay", "terminalId": null,
  "method": "UPI", "payer": "pa***@okhdfc", "reference": "pay_Abc", "utr": "…", "grossPaise": 12500, "feePaise": 200, "taxPaise": 36, "netPaise": 12264,
  "status": "CAPTURED", "settlementRef": "setl_1", "at": "…", "documentNo": "VSM-1012 or invoice no" }
```
`status` is `CAPTURED` or `REFUNDED` for online rows and always `COMPLETED` for counter rows (CREDIT lines are not receipts). `method` is UPI, CARD, CASH, NETBANKING, WALLET or the online word (counter rows: the line mode). Payer is masked (UPI id `ab***@bank`, card `Visa ****1111`, or bank); a full card number is never stored. Counter rows have no fee/net until a statement import matches them. `provider` is `counter` for counter lines without an account. `GET /admin/receipts/summary?from&to` returns an array `{ provider, moneyAccountId, accountName ("Unassigned" when null), method, receipts, grossPaise, feePaise, taxPaise }` (at most 500 groups) for per-vendor and per-account totals.

`GET /admin/bank-reports/receipts?from&to&page&size`: a Page of `{ moneyAccountId, accountName, accountKind, method, receipts (count), grossPaise, refundedPaise, netPaise }` per account and method (`moneyAccountId` null = Unassigned). `GET /admin/bank-reports/fees?from&to`: per month and account `{ month "YYYY-MM", payments, grossPaise, feePaise, taxPaise (GST on fee: input credit), missingFee }`.

`GET /admin/payments/by-sale/{saleId}` (counter bill): array of `{ posPaymentId, mode, provider, amountPaise, reference (RRN), terminalId, moneyAccountId, accountName, payMethod, cardScheme, cardLast4, issuingBank, upiVpa (masked), authCode, settlementRef, utr, feePaise, taxPaise, netPaise, settledAt }`. Card machine lines carry the masked payer detail in `pos_payment.detail` (JSON); a full card number is never stored or returned. Receipts rows of counter lines show it as `payer` (`VISA ****1111`, masked UPI id, or bank) and, once a statement matched them, `utr`, fee, tax and net.

`GET /admin/payments/by-order/{orderId}`: array of `{ paymentId, provider, status, amountPaise, instrument, upiVpa, cardNetwork, cardLast4, bank, feePaise, taxPaise, netPaise, settlementRef, utr, settledAt, moneyAccountId, accountName, providerRef, capturedAt }`. Show only to admin; fields are null until the provider reported them.

### 4.4 Settlements (`/api/v1/admin/settlements`)

- `GET /?from&to&accountId&provider&source(GATEWAY_API|CSV)&page&size`: Page of `{ id, provider, providerSettlementId, utr, grossPaise, feePaise, taxPaise, netPaise, status, settledAt, moneyAccountId, accountName, source, items, unmatchedItems }`.
- `GET /{id}`, `GET /{id}/items?unmatchedOnly&page&size` (default size 50): item `{ id, kind (PAYMENT|REFUND|ADJUSTMENT|FEE), providerEntityId, grossPaise, feePaise, taxPaise, netPaise, method, settledAt, utr, reference, matched, paymentId, orderId, orderNo, refundId, posPaymentId, invoiceNo }`.
- `GET /unsettled?overdueOnly&page&size` (no date range): Page of `{ row: { source ONLINE|COUNTER, id, reference (order no or invoice no), moneyAccountId, accountName, amountPaise, receivedAt }, expectedBy, overdue }`. Expected = received + `payment.settlementDays`; overdue after one more day. Counter lines appear for CARD lines on CARD_TERMINAL/GATEWAY accounts and for UPI lines taken on a card machine.
- `GET /exceptions?status(OPEN|RESOLVED)&kind(OVERDUE_UNSETTLED|UNMATCHED_ITEM|AMOUNT_MISMATCH)&page&size`: item `{ id, kind, settlementId, settlementItemId, paymentId, reference, expectedPaise, actualPaise, status, note, createdAt, resolvedAt }`. `POST /exceptions/{id}/resolve` `{ note (1-300) }` returns the item; resolving again changes nothing. An overdue exception closes itself (note "Settled") when the payment later settles.
- `POST /sync` `{ provider, days? (1..60, default 7) }` returns `{ provider, settlementsSeen, settlementsStored, itemsStored, skipped }` (`skipped: true` = another server is already syncing). 404 unknown, missing or blank provider, 503 `SETTLEMENT_NOT_CONFIGURED`. The nightly job (04:30) and Razorpay's `settlement.processed` webhook do the same automatically.
- `POST /import` multipart: `file` (CSV, 2 MB (`CSV_INVALID` above that), 5000 rows; the web layer refuses a body over 5 MB (file) / 6 MB (request) earlier with a generic error), `moneyAccountId` (required), optional `format` (`PAYTM` = the Paytm dashboard Settlement Report, read with its own columns and picked automatically from its header when `format` is blank; `GENERIC` forces the column mapping), optional `provider` (default `manual`; forced to `paytm` for the Paytm format), `utr`, and, for the generic format, column mapping `dateColumn, referenceColumn, amountColumn, feeColumn, taxColumn, descriptionColumn` (defaults: date, reference, amount, fee, tax, description; names case-insensitive), `dateFormat` (e.g. `dd/MM/yyyy`; blank = ISO), `amountUnit` (RUPEES default, or PAISE), `delimiter` (comma default, `;` or `tab`). A negative amount is an adjustment (matched automatically); a positive row is matched by reference and amount to an online payment's provider payment id or a counter UPI/CARD line's reference. Result `{ settlementId, alreadyImported, rows, matched, unmatched, netPaise }`. The same file for the same account is a no-op (`alreadyImported: true`). 400 `CSV_INVALID` with `details.problems` (first 20 `"row N: …"`) or `VALIDATION_FAILED` on a mapping field or an unknown `format`.

**Paytm Settlement Report format.** One settlement per file (provider `paytm`, `utr` = the UTR all lines share, else null). Each Paytm line keeps its own `utr`, settled date, commission as `feePaise`, GST as `taxPaise` and the settled amount as `netPaise`; `detail` (JSON on the item) holds transaction id, order id, type, status, transaction date, payment mode and, from the API, `posId` and `extSerialNo`. A sale (ACQUIRING) becomes a PAYMENT; a refund, chargeback or anything else becomes an ADJUSTMENT that takes money away; FAILED, PENDING and CANCELLED lines are left out. Matching of a sale, in this order: online payment by provider id; counter line whose `reference` equals the Paytm order id or RRN; Paytm machine payment whose RRN or order id (= payment id without dashes) is the line's; then, for a UPI line, the counter UPI line on a `UPI` account of provider `paytm` with the same amount on the transaction day when it is the only candidate (this is how UPI received on the QR sticker, which has no payment of ours behind it, is matched). Whatever does not match goes to the exception queue: `UNMATCHED_ITEM` (nothing found, or the sticker candidate is not unique), `AMOUNT_MISMATCH` (payment found, other amount; `expectedPaise` is our amount).

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
class TerminalSession { String id, reference, provider, state, providerStatus, mode; int amountPaise, pollAfterSeconds; bool open, canCancel, cancelOnMachine, canVoid;
  String? instructions, message, qrPayload, qrImageUrl, approvalCode, rrn, moneyAccountId, terminalId, terminalName, payMethod, cardScheme, maskedCard, issuingBank, upiVpa, posSaleId; }
```

## 6. Behaviour the frontend must know

- Never show "Unassigned" as an error: it is the honest bucket for history and for lines nobody tagged. Offer "assign default account" in settings.
- `payer` and card fields are masked server-side; do not try to rebuild a card number.
- Fee, tax and net are null until the provider reports them (capture detail, then the settlement). `missingFee` in the fees report counts those.
- Reports read bounded ranges; show `totalExact:false` as "10,000+".
- Payment lines from an old app version (no account) keep working.
- The customer app picks the online gateway from `payment.onlineProvider`; there is no per-order override yet.

## 7. Adding a vendor later (Cashfree, PhonePe...)

1. Payment gateway: implement `PaymentGateway` (create, session, parseWebhook, refund, fetchStatus) as a `@Component`; it appears in `PaymentGatewayRegistry` by `provider()`; select it with `payment.onlineProvider`.
2. Settlements: implement `SettlementProvider` (`provider()` same code, `configured()`, `listSettlements`, `settlementDetail`, `fetchPaymentDetail`) as a `@Component`. The nightly job and `/admin/settlements/sync` pick it up automatically; each pullable provider is synced on its own and one failing provider does not stop the others (tested with two healthy providers, one throwing). Map the vendor's per-transaction UTR, commission, GST, payment mode and RRN into `Item` (`reference` and `altReference` are what a counter line is matched on); put anything else vendor-specific in `Item.detail` (stored as JSON). A vendor's own statement file: implement `StatementParser` (`format()`, `provider()`, `recognises(header)`, `parse`) and the CSV import offers it as `format=...` and picks it by header.
3. Terminal: implement `CardTerminal` (`requestPayment`, `status`, `cancel`; `Mode.QR` with `qrPayload` or `qrImageUrl`, `Mode.PUSH` for a machine that prompts itself, `manualConfirmation()` false when it reports results). An adapter that keeps its own durable sessions (like Paytm) also overrides `requestPayment(Request)`, `find`, `cancel(actor, id)`, `voidPayment` and `attach`; the simple ones need nothing more. It is chosen by the provider code of the channel's money account.
4. Create the matching money accounts (provider code, identifier = terminal id) and they show up as till channels.

## 8. Not verified without a live account

`RazorpaySettlementProvider` was written from Razorpay's public API reference and tested only against canned HTTP responses. To check with the new Razorpay account: the list parameters (`from`/`to`/`count`/`skip`), that `fees` includes GST and `tax` is the GST part, the recon `combined` report fields (`credit`, `debit`, `fee`, `tax`, `settlement_id`, `settlement_utr`, `settled_at`) and that its `day` filter is the settlement date, the `settlement.processed` webhook payload shape (`payload.settlement.entity.id`), and that the account has the settlement/recon APIs enabled. The webhook must also subscribe to `settlement.processed`.

Paytm (machine calls, the settlement API and the Settlement Report columns) has run only against an in-JVM fake and canned files; see "Before go-live" in `73-paytm-terminal.md`.

## 9. Implementation checklist

- [ ] Accounts CRUD screen and the defaults in Config.
- [ ] Till: load channels, per-mode chooser with default, send `moneyAccountId`/`terminalId`/`reference`.
- [ ] Card/UPI machine: start with the chosen channel's `moneyAccountId`, poll by `pollAfterSeconds`, manual confirm dialog (approval code, RRN) for `manual`, all states incl. `cancelOnMachine` and a late `EXPIRED` -> `APPROVED`; put the session id on the line as `terminalPaymentId`.
- [ ] Receipts list + summary with filters; fees report; receipts-by-account report.
- [ ] Settlements list/detail, CSV import (generic column mapping, or the Paytm Settlement Report format), sync button.
- [ ] Exceptions queue with resolve note; unsettled/overdue tab.
- [ ] Account book with running balance and manual entries.
- [ ] Payer detail block on the admin order screen and on the admin bill screen (`/admin/payments/by-sale/{saleId}`).
- [ ] Handle errors: `ACCOUNT_IN_USE`, `DUPLICATE_ACCOUNT_NAME`, `LAST_CASH_ACCOUNT`, `CSV_INVALID`, `TERMINAL_NOT_CONFIGURED`, `SETTLEMENT_NOT_CONFIGURED`, `REFERENCE_REUSED`, `TERMINAL_BUSY`.
