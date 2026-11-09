# P4. Bank module: money accounts, payment channels, receipts, settlements and exceptions (implementation playbook)

Audience: a developer or LLM coding agent who must build the ADMIN app screens (and the till payment-channel / card-terminal parts) WITHOUT reading backend code. Everything below was checked against the code: `AdminMoneyAccountController`, `PosChannelController`, `PosTerminalController`, `AdminBankReportController`, `AdminSettlementController`, `MoneyAccountService`, `TerminalService`, `BankReportService`, `SettlementService`, `SettlementCsv`, `BankReportRepository`, `Settings`, migration `V50__money_accounts.sql`, and the integration tests `MoneyAccountIT`, `SettlementIT`. Where `71-bank-and-settlements.md` (or `73`, `00`) disagreed with the code, the code won; the differences are listed in the last section and have been fixed in those guides (ticked off in `README.md`).

> The Paytm card machine is part of the bank module: one channel list, one set of terminal calls, one receipts view and settlement import for every vendor. Sources of truth: `71-bank-and-settlements.md` and `73-paytm-terminal.md`; this playbook follows them and the code.

## Conventions (confirmed in code)

- Base path `/api/v1`. JSON, camelCase. `Authorization: Bearer <accessToken>`. Roles: everything under `/admin/**` is ADMIN only (a CASHIER gets 403 `FORBIDDEN`, no token 401). `/pos/**` is CASHIER and ADMIN; a CUSTOMER token gets 403.
- Money is an integer in **paise** (Dart `int`). Never use `double` for money.
- Error body: `{ "code", "message", "traceId", "details": { "fields": { "<field>": "<text>" } } }`. Switch on `code`; put `details.fields[x]` under input `x`.
- Paged list: `{ items, page, size, total, totalExact, hasMore }`. `page` starts at 0; `size` is clamped to 1..50 (a larger size silently becomes 50). When `totalExact` is false show "10,000+" and keep paging while `hasMore`.
- Report dates are `yyyy-MM-dd`, India time, BOTH days included. Missing `from`/`to` = last 30 days up to today. More than 400 days -> 400 `VALIDATION_FAILED` on `to`; `from` after `to` -> 400 on `from`. Instants in responses are UTC ISO-8601.
- IDs are UUID strings.
- `Idempotency-Key` header is OPTIONAL on `POST /admin/money-accounts`, `POST /admin/money-accounts/{id}/entries` and `POST /pos/terminal/payments`. When sent, a repeat returns the first result (HTTP 201 again) and does no work. Generate one UUID per button tap and reuse it on retries.
- Settings are saved with `PUT /admin/config` body `{ "changes": [ { "key": "...", "value": "..." } ] }` (screen described in `20-orders-payments-admin-config.md`; settings are read with `GET /admin/config`).

## Table of contents

1. [Admin screens map](#1-admin-screens-map)
2. [Money accounts (list, create, edit, deactivate, delete)](#2-money-accounts)
3. [Defaults per mode and the online gateway (settings)](#3-defaults-per-mode-and-the-online-gateway-settings)
4. [Account book and manual entries](#4-account-book-and-manual-entries)
5. [Till: choose the payment channel and tag payment lines](#5-till-choose-the-payment-channel-and-tag-payment-lines)
6. [Till: card terminal session (one flow for every vendor)](#6-till-card-terminal-session-one-flow-for-every-vendor)
7. [Payer detail on the admin order screen](#7-payer-detail-on-the-admin-order-screen)
8. [Receipts list, summary and receipts-by-account report](#8-receipts-list-summary-and-receipts-by-account-report)
9. [Settlements: list, detail, items, sync button](#9-settlements-list-detail-items-sync-button)
10. [Statement CSV import](#10-statement-csv-import)
11. [Exceptions queue](#11-exceptions-queue)
12. [Unsettled report](#12-unsettled-report)
13. [Monthly fees report](#13-monthly-fees-report)
14. [Error code cheat sheet](#14-error-code-cheat-sheet)
15. [Build order](#15-build-order)
16. [Doc vs code differences](#16-doc-vs-code-differences)

---

## 1. Admin screens map

| # | Screen | Endpoints |
|---|---|---|
| A1 | Accounts list + form | `GET/POST /admin/money-accounts`, `GET/PUT/DELETE /admin/money-accounts/{id}`, `POST .../{id}/deactivate`, `POST .../{id}/activate` |
| A2 | Account book | `GET /admin/money-accounts/{id}/book`, `POST /admin/money-accounts/{id}/entries` |
| A3 | Config > Counter rules / Payments groups | `GET/PUT /admin/config` (`pos.defaultAccount.*`, `payment.gatewayAccountId`, `payment.onlineProvider`, `payment.settlementDays`, `pos.terminalProvider`) |
| A4 | Receipts (summary cards + drill-down list) | `GET /admin/receipts/summary`, `GET /admin/receipts`, `GET /admin/bank-reports/receipts` |
| A5 | Settlements list/detail/items + Sync + Import | `GET /admin/settlements`, `GET /admin/settlements/{id}`, `GET .../{id}/items`, `POST .../sync`, `POST .../import` |
| A6 | Exceptions badge + queue | `GET /admin/settlements/exceptions`, `POST .../exceptions/{id}/resolve` |
| A7 | Unsettled tab | `GET /admin/settlements/unsettled` |
| A8 | Fees report | `GET /admin/bank-reports/fees` |
| A9 | Order detail payer block | `GET /admin/payments/by-order/{orderId}` |
| T1 | Till payment-channel chooser | `GET /pos/payment-channels` |
| T2 | Till card terminal dialog (any vendor, chosen by the channel) | `POST /pos/terminal/payments`, `GET .../{id}`, `POST .../{id}/confirm` (manual), `POST .../{id}/cancel`, `POST .../{id}/void` (Paytm) |
| A10 | Paytm machines master (ADMIN) and Paytm settings group | `/admin/payment-terminals` and `paytm.*` in `GET/PUT /admin/config`; see `73` |
| A11 | Admin bill screen payer block | `GET /admin/payments/by-sale/{saleId}` (section 7.9) |

Suggested navigation: a "Money" section with Accounts, Receipts, Settlements (badge = count of OPEN exceptions), Unsettled, Fees.

---

## 2. Money accounts

### 2.1 Goal
Maintain the places money sits: a bank, an online gateway (Razorpay), each counter UPI QR, each card machine, the cash drawer. Every receipt is tagged with one account; reports group by it.

### 2.2 Who / settings
ADMIN only. A migration seeds one account `Cash drawer` (kind `CASH`, provider `manual`, id `00000000-0000-0000-0000-0000000000a1`). Kinds: `BANK`, `GATEWAY`, `UPI`, `CARD_TERMINAL`, `CASH`. A `GATEWAY`, `UPI` or `CARD_TERMINAL` account may "settle into" a `BANK` account (`settlesToAccountId`).

### 2.3 Screens and fields

**List** (filters + paging; sorted A to Z, size default 20, max 50)

| Filter | Type | Validation |
|---|---|---|
| `kind` | enum | case-insensitive; anything else -> 400 `kind` ("BANK, GATEWAY, UPI, CARD_TERMINAL or CASH") |
| `active` | bool | omit = both. Default the UI to `active=true` with a "show switched-off" toggle |
| `q` | string | matches name, provider or identifier (`%` and `_` are literal) |
| `page`, `size` | int | 0 / 20 |

**Create / edit form** (PUT replaces all fields: always send the whole form)

| Field | Type | Required | Validation (server) |
|---|---|---|---|
| `name` | string | yes | trimmed, 1..80 chars; unique ignoring case (409 `DUPLICATE_ACCOUNT_NAME`, `details.fields.name`) |
| `kind` | enum | yes | one of the five kinds (case-insensitive, stored upper-case) |
| `provider` | string | no | free code, max 40, stored lower-case; blank -> `manual`. Use `razorpay`, `paytm`, `manual`, ... Match the code the settlement/gateway adapter uses (`razorpay`) |
| `identifier` | string | no | max 60: last 4 digits, UPI id, or terminal id (it is what the till sends as `terminalId`) |
| `settlesToAccountId` | uuid | no | only for GATEWAY / UPI / CARD_TERMINAL (else 400 `settlesToAccountId`); target must exist and be an ACTIVE `BANK` (400); cannot be itself (400, on PUT) |
| `note` | string | no | max 300 |

Row actions: Edit, Deactivate / Activate, Delete (only for unused accounts), Open book.

### 2.4 API sequence
1. `GET /admin/money-accounts?active=true&page=0&size=20` -> page of Account.
2. Create: `POST /admin/money-accounts`, header `Idempotency-Key: <uuid>` (optional but recommended).
```json
{ "name": "Razorpay", "kind": "GATEWAY", "provider": "razorpay", "identifier": "", "settlesToAccountId": "<bank uuid>", "note": "" }
```
   -> 201 Account `{ id, name, kind, provider, identifier, settlesToAccountId, active, note, createdAt, updatedAt }`. Errors: 400 `VALIDATION_FAILED` (map `details.fields.{name|kind|provider|identifier|note|settlesToAccountId}`), 409 `DUPLICATE_ACCOUNT_NAME`.
3. Edit: `PUT /admin/money-accounts/{id}` same body -> 200. Extra errors: 404 `NOT_FOUND`; 409 `ACCOUNT_IN_USE` with `details.fields.kind` when `kind` is changed after the account has any receipt, entry, settlement, or another account pointing at it (disable the kind dropdown once used; or just show the error).
4. `POST /admin/money-accounts/{id}/deactivate` (no body) -> 200 Account `active:false`. Idempotent. 409 `LAST_CASH_ACCOUNT` (the last active CASH account) or 409 `ACCOUNT_IN_USE` (an active account still settles into this bank: deactivate those first).
5. `POST /admin/money-accounts/{id}/activate` -> 200. Idempotent.
6. `DELETE /admin/money-accounts/{id}` -> 204; 409 `ACCOUNT_IN_USE` ("deactivate it instead"). Show Delete only after the admin confirms; prefer Deactivate.
7. `GET /admin/money-accounts/{id}` -> 200 or 404.

### 2.5 State / derived
- `active` is the only state. Deactivating stops the account from being OFFERED for new receipts (it disappears from `GET /pos/payment-channels`); old receipts keep pointing at it.
- The server decides whether an account is "used". Never infer it client-side.

### 2.6 Edge cases
- Same-key retry of create returns the first account (201). A retry without a key after a timeout may hit 409 `DUPLICATE_ACCOUNT_NAME`: reload the list and open the existing one.
- To retire a bank: deactivate every gateway/UPI/terminal account that settles into it first (or edit them to another bank).
- Empty state: "No accounts" never happens (the drawer exists), but the list for `kind=BANK` can be empty: show "Add your bank account to see payouts".
- Unknown `kind` in filter is a 400, not an empty list.

### 2.7 Dart models
```dart
enum AccountKind { BANK, GATEWAY, UPI, CARD_TERMINAL, CASH }
class MoneyAccount {
  final String id, name, kind, provider, identifier, note; // strings never null ("" when empty)
  final String? settlesToAccountId;
  final bool active;
  final DateTime createdAt, updatedAt;
}
class MoneyAccountBody { String name, kind; String? provider, identifier, note, settlesToAccountId; }
```

### 2.8 Acceptance
- Given a valid body, when I create a `GATEWAY` account settling into an active bank, then I get 201 and it appears in the list.
- Given an account name that exists in another case, when I save, then 409 `DUPLICATE_ACCOUNT_NAME` is shown under the name field.
- Given a gateway account settles into bank B, when I deactivate B, then I see "Active accounts still settle into this one" (409 `ACCOUNT_IN_USE`) and B stays active.
- Given only one active CASH account, when I deactivate it, then 409 `LAST_CASH_ACCOUNT` is shown.
- Given an account with receipts, when I change its kind, then 409 `ACCOUNT_IN_USE` is shown and the kind is unchanged.
- Given an unused account, when I delete it, then 204 and it is gone; given a used one, 409 and the Deactivate hint.
- Given `settlesToAccountId` on a BANK or CASH account, when I save, then 400 on `settlesToAccountId`.

---

## 3. Defaults per mode and the online gateway (settings)

### 3.1 Goal
Tell the system which account receives money when nobody names one, and which gateway the customer app uses.

### 3.2 Who / settings (all on `GET/PUT /admin/config`, ADMIN)

| Key | Group | Type | Default | Meaning |
|---|---|---|---|---|
| `pos.defaultAccount.CASH` | Counter rules | text (account id, max 40) | blank | Account for cash lines with none named. Blank = first active CASH account (the drawer) |
| `pos.defaultAccount.UPI` | Counter rules | text | blank | Account for UPI lines with none named. Blank = Unassigned |
| `pos.defaultAccount.CARD` | Counter rules | text | blank | Same for card lines. Blank = Unassigned |
| `pos.terminalProvider` | Counter rules | text (max 40) | `manual` | Fallback adapter: used only for a channel whose provider has no adapter, or when the till names no channel. The channel's own provider (`paytm`, `manual`) normally decides (section 6) |
| `payment.gatewayAccountId` | Payments | text | blank | Account that receives ONLINE payments. Blank = first active GATEWAY account whose `provider` equals the payment's provider (e.g. `razorpay`) |
| `payment.onlineProvider` | Payments | text (max 40) | blank | Gateway the customer app pays with. Blank, or a code that is not a registered gateway = the server default |
| `payment.settlementDays` | Payments | int 0..30 | 2 | Normal days a provider takes to settle. A captured payment unsettled after this + 1 day is OVERDUE |

### 3.3 Screens and fields
In the config screen show the account settings as dropdowns of ACTIVE accounts (filter by kind: CASH setting -> CASH accounts; UPI setting -> UPI/GATEWAY; CARD -> CARD_TERMINAL/GATEWAY; gateway setting -> GATEWAY) with a "None" option that sends `""`. `payment.onlineProvider` is a free text/enum chosen from the providers you know (`razorpay`, `cod` is not a gateway choice); show the help text from the config response.

### 3.4 API sequence
1. `GET /admin/config` -> read current values and help text.
2. `PUT /admin/config`
```json
{ "changes": [ { "key": "pos.defaultAccount.UPI", "value": "<account uuid>" }, { "key": "pos.defaultAccount.CARD", "value": "" } ] }
```
   -> 200 on success; 400 with `details.fields["<key>"]` on a bad value (save is atomic).

### 3.5 State / derived
- A saved id that is not a UUID, does not exist, or is inactive is IGNORED at use time (the line falls back to the next rule), it is not an error. Warn in the UI when the chosen account is later deactivated.
- The server resolves which account a receipt gets. The app only sends what the cashier chose (section 5).

### 3.6 Edge cases
- Changing `payment.onlineProvider` affects NEW online payments only; webhooks for every configured gateway keep working.
- Unknown code in `payment.onlineProvider` saves fine and silently falls back to the default gateway: validate against a list in the app.
- There is no per-order gateway override.

### 3.7 Dart
```dart
class SettingChange { final String key; final String value; }
```

### 3.8 Acceptance
- Given `pos.defaultAccount.UPI` = X, when a till sends a UPI line with no account, then the receipt shows X.
- Given the setting holds a deactivated account, when a UPI line has no account, then the receipt is Unassigned (not an error).
- Given `payment.onlineProvider` = `razorpay`, when a customer starts an online payment, then the Razorpay gateway is used.

---

## 4. Account book and manual entries

### 4.1 Goal
A running balance per account, built from receipts, refunds, payouts, fees and hand-entered movements (opening balance, bank charge, interest).

### 4.2 Who / settings
ADMIN. No settings.

### 4.3 Screens and fields

**Book** (date range + paged list, size default 20): `from`, `to` (rules in Conventions).

**Add entry form**

| Field | Type | Required | Validation |
|---|---|---|---|
| `direction` | `IN` / `OUT` | yes | case-insensitive; else 400 `direction` |
| `amountPaise` | int | yes | 1..10,000,000,000 (Rs 10 crore) else 400 `amountPaise` |
| `at` | instant (ISO-8601 UTC) | no | default now. When the admin picks a date convert India local date to an instant |
| `note` | string | no | max 200 chars (400 `note`) |

### 4.4 API sequence
1. `GET /admin/money-accounts/{id}/book?from=2026-11-01&to=2026-11-09&page=0&size=20` -> 
```json
{ "moneyAccountId": "...", "accountName": "HDFC", "openingBalancePaise": 100000,
  "lines": { "items": [ { "at": "...Z", "type": "ENTRY", "direction": "OUT", "amountPaise": 2500, "sourceType": "MANUAL", "sourceId": "...", "description": "bank charge", "balancePaise": 97500 } ],
             "page": 0, "size": 20, "total": 1, "totalExact": true, "hasMore": false } }
```
   Lines are oldest first. `type`: `RECEIPT`, `REFUND`, `PAYOUT`, `FEE`, `ENTRY`. `sourceType`: `ONLINE_PAYMENT`, `COUNTER_PAYMENT`, `ONLINE_REFUND`, `COUNTER_REFUND`, `SETTLEMENT`, `MANUAL` (or another module's own value). Errors: 404 unknown account; 400 on `from`/`to`.
2. `POST /admin/money-accounts/{id}/entries`, header `Idempotency-Key: <uuid>`:
```json
{ "direction": "OUT", "amountPaise": 2500, "at": "2026-01-02T10:00:00Z", "note": "bank charge" }
```
   -> 201 `{ id, moneyAccountId, at, direction, amountPaise, sourceType, sourceId, note, createdBy }` (`sourceType` is null for hand entries; the book shows it as `MANUAL`). Then reload the book.

### 4.5 State / derived
- `balancePaise` on each line and `openingBalancePaise` (balance before `from`) are computed by the server. Never recompute running balances in the app; when paging, show the server's value per line.
- For a GATEWAY / UPI / CARD_TERMINAL account the book shows receipts IN, and payouts and fees OUT (a payout's net plus fee and GST). For the BANK it settles into, the payout net appears IN. The balance of a gateway therefore shows money held at the provider.
- Purchase supplier payments are NOT in any book yet (`moneyAccountId` on a purchase payment is stored but not validated or posted). Do not promise the admin that supplier payments reduce a bank balance.

### 4.6 Edge cases
- Entries cannot be edited or deleted (no endpoint). Correct a mistake with an opposite entry and a note.
- A retry with the same `Idempotency-Key` returns the first entry; without a key it books twice.
- Empty range: `lines.items` is empty and `openingBalancePaise` still tells the balance.
- Unassigned money is not a real account and has no book.

### 4.7 Dart
```dart
class BookRow { final DateTime at; final String type, direction, sourceType, sourceId, description; final int amountPaise, balancePaise; }
class Book { final String moneyAccountId, accountName; final int openingBalancePaise; final Page<BookRow> lines; }
class EntryBody { String direction; int amountPaise; DateTime? at; String? note; }
```

### 4.8 Acceptance
- Given opening IN 100000 on 1 Jan and a bank charge OUT 2500 on 2 Jan, when I open the book for 2 Jan, then opening is 100000 and the line shows balance 97500.
- Given I tap Save twice with the same key, when both succeed, then there is one entry.
- Given amount 0 or direction "SIDEWAYS", when I save, then field errors show and nothing is saved.
- Given a range over 400 days, when I load, then an error is shown on the date field.

---

## 5. Till: choose the payment channel and tag payment lines

### 5.1 Goal
At the payment step the cashier picks WHICH vendor/account a CASH, UPI or CARD amount goes to (several vendors can be active at the same time: a Paytm machine and a bank machine, two QRs). The chosen channel is sent on the payment line so the receipt lands in the right account.

### 5.2 Who / settings
CASHIER and ADMIN. Defaults come from `pos.defaultAccount.*` (section 3). Old tills that send no account keep working.

### 5.3 Screens and fields

Payment line (sale `payments[]`, offline sale lines, and refund lines `refunds[]`):

| Field | Type | Required | Validation |
|---|---|---|---|
| `mode` | `CASH` / `UPI` / `CARD` / `CREDIT` | yes | max 20 chars; CREDIT only on a sale |
| `amountPaise` | int | yes | 0..100,000,000 |
| `reference` | string | no | max 100: UPI transaction ref, card approval code, RRN |
| `moneyAccountId` | uuid | no | the channel's `moneyAccountId`. 400 on `payments[i].moneyAccountId` when: unknown ("no such money account"), switched off, wrong kind for the mode, or sent on a CREDIT line |
| `terminalId` | string | no | max 60: the channel's `terminalId` |

Allowed account kinds per mode: CASH -> `CASH`; UPI -> `UPI`, `GATEWAY`, `BANK`; CARD -> `CARD_TERMINAL`, `GATEWAY`, `BANK`.

### 5.4 API sequence
1. On entering the payment step (cache for the shift; reload on error): `GET /pos/payment-channels` ->
```json
[ { "mode": "CARD", "moneyAccountId": "…", "provider": "paytm", "name": "Paytm machine 1", "terminalId": "TID-77", "kind": "CARD_TERMINAL", "default": true },
  { "mode": "UPI",  "moneyAccountId": "…", "provider": "manual", "name": "PhonePe QR", "terminalId": null, "kind": "UPI", "default": false },
  { "mode": "CASH", "moneyAccountId": "…", "provider": "manual", "name": "Cash drawer", "terminalId": null, "kind": "CASH", "default": true } ]
```
   The array is grouped by mode (CASH, UPI, CARD), accounts in creation order, at most 200 per mode. `terminalId` is the account's `identifier` (null when empty).
2. When the cashier taps a mode, show that mode's channels. Pre-select the one with `default: true`. One channel only: skip the chooser. No channel for a mode: send the line with no account (the server applies the default or leaves it Unassigned).
3. Send the line with the chosen channel:
```json
{ "mode": "CARD", "amountPaise": 12500, "reference": "APPR-1", "moneyAccountId": "<channel.moneyAccountId>", "terminalId": "TID-77" }
```
   in `POST /pos/sales` (see `P3` F4 for the full sale call and its `Idempotency-Key`). A 400 with `details.fields["payments[0].moneyAccountId"]`: reload the channel list (the admin may have switched the account off) and ask again.
4. Returns: a refund line follows the account of the bill's line of the same mode unless the refund line names one (`refunds[].moneyAccountId`). Normally send none.

### 5.5 State / derived
- `default` is computed by the server: true for the channel equal to `pos.defaultAccount.<MODE>` when that account is among the offered ones; otherwise the first CASH channel is default and NO UPI/CARD channel is default. Do not assume every mode has a default.
- A GATEWAY account (e.g. Razorpay) appears as a channel under BOTH UPI and CARD; a BANK account never appears as a channel (even though it is accepted if sent explicitly).
- Which account a line without `moneyAccountId` gets: setting default if active, else (CASH only) the first active CASH account, else null (Unassigned). Unassigned is normal for history, not an error.
- An OFFLINE bill uploaded later keeps an account that was switched off after the sale was rung (no 400 for it).

### 5.6 Edge cases
- Do not send `moneyAccountId` on CREDIT lines (400).
- `terminalId` is just a label stored on the line; the server does not check it against the account.
- Stale cache: a deactivated account in the cached list gives the 400 above; refresh and retry with a NEW choice, same `Idempotency-Key` of the sale (a 400 stored nothing).
- A card/UPI machine payment (section 6) is linked to the sale by sending the session id as `terminalPaymentId` on the line; the server then takes the account, terminal id, reference and payer detail from the session. Only a payment WITHOUT a session (typed reference) needs `moneyAccountId`/`terminalId`/`reference` from the channel.

### 5.7 Dart
```dart
class PayChannel { final String mode, moneyAccountId, provider, name, kind; final String? terminalId; final bool isDefault; /* json "default" */ }
class PayLine { String mode; int amountPaise; String? reference, moneyAccountId, terminalId; }
```

### 5.8 Acceptance
- Given two UPI channels and a configured default, when the cashier opens UPI, then the default is pre-selected and the other is one tap away.
- Given a CARD line with `moneyAccountId` of a UPI-kind account, when the sale is sent, then 400 on `payments[0].moneyAccountId` and the chooser reopens.
- Given an old payload with no account, when a CASH sale is made, then it is tagged to the drawer.
- Given the account was deactivated after caching, when the cashier pays, then the channel list reloads and the account is gone.

---

## 6. Till: card terminal session (one flow for every vendor)

> One source of truth: the session JSON, the Paytm state table and the error list are in `71-bank-and-settlements.md` (sections 3 and 4.2) and `73-paytm-terminal.md` (sections 2 and 3). The complete till recipe (freeze the basket, crash recovery, void, acceptance tests) is `P3` F6. This section covers what an admin/bank-module developer needs: how the channel chooses the adapter, the manual flow, and the common calls. Put the terminal calls behind one repository class anyway; the vendor only changes the adapter on the server, never the calls.

### 6.1 Goal
Ask the card/UPI machine the cashier picked to collect an amount, follow the result, and put the finished session on the bill line. The same four calls (start, poll, confirm/cancel/void, `terminalPaymentId` on the line) serve every vendor.

### 6.2 Who / settings
CASHIER and ADMIN. The channel's money account decides the adapter: its `provider` (`paytm`, `manual`, ...) selects it. `pos.terminalProvider` (default `manual`) is only the default for a channel whose provider has no adapter, or when the start names no `moneyAccountId`. Adapters in the code: `manual` (the cashier types the approval code) and `paytm` (wireless machine; needs `paytm.enabled` and the machines master, see `73`). Adapter missing or not set up: 503 `TERMINAL_NOT_CONFIGURED` ("The card terminal is not set up"): fall back to a plain CARD/UPI line with a typed reference. A Paytm machine added in the machines master creates its own `CARD_TERMINAL` account (provider `paytm`), so it appears in `GET /pos/payment-channels` with no hand-made account.

### 6.3 Screens and fields

| Field | Type | Required | Validation |
|---|---|---|---|
| `amountPaise` (start) | int | yes | 1..100,000,000 (400 `amountPaise`). MUST equal the CARD/UPI line amount of the bill |
| `reference` (start) | string | no | max 60 (a Paytm machine: 50). Blank -> server makes `TP-xxxxxxxxxxxx`. Your own bill/cart ref is the idempotency key of the payment |
| `moneyAccountId` (start) | uuid | no | the chosen `channel.moneyAccountId`; must pass the CARD rules of section 5 (400 `moneyAccountId`); blank = `pos.defaultAccount.CARD` and the default adapter |
| `approvalCode` (confirm) | string | yes | trimmed 1..40 (400) |
| `rrn` (confirm) | string | no | max 40 (400) |

### 6.4 API sequence
1. **Start.** `POST /pos/terminal/payments` (`Idempotency-Key` optional), body `{ "amountPaise": 12345, "reference": "BILL-1", "moneyAccountId": "<channel.moneyAccountId>" }` -> 201 session. A `manual` session:
```json
{ "id": "uuid", "reference": "BILL-1", "provider": "manual", "amountPaise": 12345, "state": "PENDING", "providerStatus": "PENDING", "mode": "MANUAL", "open": true,
  "instructions": "Charge Rs 123.45 on the card machine, then enter the approval code or RRN here.", "message": "…same text…",
  "qrPayload": null, "qrImageUrl": null, "approvalCode": null, "rrn": null, "moneyAccountId": "uuid",
  "terminalId": null, "terminalName": null, "payMethod": null, "cardScheme": null, "maskedCard": null, "issuingBank": null, "upiVpa": null,
  "canCancel": true, "cancelOnMachine": false, "canVoid": false, "pollAfterSeconds": 3, "posSaleId": null, "createdAt": "…Z", "finishedAt": null }
```
   A Paytm session is the same JSON with `provider: "paytm"`, `mode: "PUSH"`, the machine fields filled and its own `providerStatus` words (`73` section 2). Same `reference` + same amount returns the SAME session (retry-safe, still 201); same reference with another amount or on another machine -> 409 `REFERENCE_REUSED`. Save `id` locally at once.
2. **Show by `mode`.** `MANUAL`: show `instructions`, fields for approval code and RRN, Confirm / Cancel. `PUSH`: "Customer is paying on the machine" and poll. `QR`: draw `qrPayload` (or load `qrImageUrl`) and poll (no adapter produces QR today; handle it generically).
3. **Poll** while `open` is true: `GET /pos/terminal/payments/{id}` every `pollAfterSeconds` (3 for manual, 10 default for Paytm). A manual session never changes by itself. 404 unknown id.
4. **Confirm (`manual` only).** `POST /pos/terminal/payments/{id}/confirm` body `{ "approvalCode": "OK123", "rrn": "RRN9" }` -> 200 session `state: "APPROVED"`. Repeating a confirm on an approved session returns it unchanged. Errors: 400 field errors; 409 `TERMINAL_PAYMENT_CLOSED` (already cancelled/declined); 409 `TERMINAL_CONFIRMS_ITSELF` (Paytm and other adapters that report results themselves: just keep polling).
5. **Cancel.** `POST /pos/terminal/payments/{id}/cancel` -> 200 `state: "CANCELLED"` for manual. A session that is already over is returned unchanged: check `state`. A Paytm session may answer 409 `CANCEL_ON_MACHINE` (press Cancel on the machine, keep polling) or `ALREADY_PAID`.
6. **Void (Paytm, same day).** `POST /pos/terminal/payments/{id}/void` when `canVoid`; a manual session answers 409 `NOT_VOIDABLE`.
7. **Attach to the bill.** On `APPROVED`, send the payment line `{ "mode": "CARD" (or "UPI"), "amountPaise": <same amount>, "terminalPaymentId": "<session id>" }`. That one id is enough: the server takes the machine's money account, terminal id, the RRN (else the approval code) as `reference`, and the masked payer detail. `moneyAccountId`, `terminalId` and `reference` may be omitted; a `reference` you send is kept; another account than the machine's is 400 on `payments[i].moneyAccountId`. The sale checks, as one transaction: paid (else 422 `TERMINAL_PAYMENT_NOT_PAID`), not used by another bill (409 `TERMINAL_PAYMENT_USED`), same amount (422 `TERMINAL_PAYMENT_AMOUNT`), and for Paytm the right mode (422 `TERMINAL_PAYMENT_MODE`); an unknown id, a non-CARD/UPI line or an offline bill is 400 on `payments[i].terminalPaymentId`. A manual session is marked used by the bill like a Paytm one. Without a session (old machine, offline) send the line with the typed `reference` and the channel's `moneyAccountId`/`terminalId` (section 5).

### 6.5 State / derived
`state`: `PENDING`, `APPROVED`, `DECLINED`, `CANCELLED`, `EXPIRED`. `APPROVED` = money taken (use it). The others = offer retry with a NEW reference or another mode. `providerStatus` is the vendor's word (equals `state` for manual). The app never decides approval by itself; for MANUAL the cashier's confirm is the approval.

### 6.6 Edge cases
- Do not let the cashier change the basket total after the session starts: the line amount must equal `amountPaise` (the sale answers 422 `TERMINAL_PAYMENT_AMOUNT` otherwise, and nothing is stored).
- App killed after charging: on restart read the stored `id`, `GET` it, and offer Confirm again (manual) or resume polling (Paytm).
- Retry storms: reuse the same `reference`.
- Several vendors are active at once: a Paytm channel busy (`TERMINAL_BUSY`, `details.openPaymentId`) does not block a manual or another Paytm channel.
- Customer-facing screens do not use this flow.
- The Paytm-only calls (`/pos/terminals`, `/pos/terminal-payments...`) still exist and read and write the same rows; a new app does not need them.

### 6.7 Dart
```dart
class TerminalSession { final String id, reference, provider, state, providerStatus, mode; final int amountPaise, pollAfterSeconds; final bool open, canCancel, cancelOnMachine, canVoid;
  final String? instructions, message, qrPayload, qrImageUrl, approvalCode, rrn, moneyAccountId, terminalId, terminalName, payMethod, cardScheme, maskedCard, issuingBank, upiVpa, posSaleId;
  final DateTime createdAt; final DateTime? finishedAt; }
class TerminalStartBody { int amountPaise; String? reference, moneyAccountId; }
class TerminalConfirmBody { String approvalCode; String? rrn; }
```

### 6.8 Acceptance
- Given a start with reference R twice, when both succeed, then the same session id is returned.
- Given R is reused with another amount, then 409 `REFERENCE_REUSED`.
- Given an approval code of 41 chars, when I confirm, then a field error shows under approvalCode.
- Given a cancelled session, when I confirm, then 409 `TERMINAL_PAYMENT_CLOSED` is shown.
- Given a Paytm session, when I confirm, then 409 `TERMINAL_CONFIRMS_ITSELF`.
- Given an APPROVED session of 12345 and a CARD line of 12345 with its id as `terminalPaymentId`, when the sale is posted, then 201 and the receipt shows the line under the machine's account; a second bill using the id gets 409 `TERMINAL_PAYMENT_USED`.
- Given `pos.terminalProvider` names a missing adapter and the channel has no adapter, when I start, then 503 `TERMINAL_NOT_CONFIGURED` and the manual-reference fallback is offered.

---

## 7. Payer detail on the admin order screen

### 7.1 Goal
Show how an online order was paid: UPI id, card network and last 4, bank, the provider fee, and where it settled.

### 7.2 Who / settings
ADMIN only. Provider details are fetched by a background job every 30 minutes, so they can be null for a recent payment.

### 7.3 Screens and fields (read only)
One card per payment (an order can have several attempts): provider, status, amount, instrument, UPI id (masked), card network + last 4, bank, fee, GST on fee, net, settlement ref, UTR, settled at, account name, provider ref, captured at.

### 7.4 API sequence
1. `GET /admin/payments/by-order/{orderId}` -> array (empty for COD or unpaid orders):
```json
[ { "paymentId": "…", "provider": "razorpay", "status": "CAPTURED", "amountPaise": 12500, "instrument": "UPI", "upiVpa": "pa***@okhdfc",
    "cardNetwork": null, "cardLast4": null, "bank": "HDFC", "feePaise": 200, "taxPaise": 36, "netPaise": 12264,
    "settlementRef": "setl_1", "utr": "…", "settledAt": "…Z", "moneyAccountId": "…", "accountName": "Razorpay", "providerRef": "pay_Abc", "capturedAt": "…Z" } ]
```

### 7.5 State / derived
`feePaise`, `taxPaise`, `netPaise`, `settlementRef`, `utr`, `settledAt` are null until the provider reports them. Show "Pending" for null, never 0. A full card number is never available; do not try to build one. Net is computed by the server.

### 7.6 Edge cases
Unknown or non-online order returns `[]`, not 404. Show the block only inside the admin app.

### 7.7 Dart
```dart
class PayerRow { final String paymentId, provider, status; final int amountPaise;
  final String? instrument, upiVpa, cardNetwork, cardLast4, bank, settlementRef, utr, moneyAccountId, accountName, providerRef;
  final int? feePaise, taxPaise, netPaise; final DateTime? settledAt, capturedAt; }
```

### 7.8 Acceptance
- Given a settled payment, when I open the order, then fee, GST, net, UTR and settlement date are shown.
- Given a payment captured a minute ago, when I open it, then fee/net show "Pending" instead of 0.

---

### 7.9 Counter bill payer block (admin bill screen)
`GET /admin/payments/by-sale/{saleId}` (ADMIN) -> array, one row per payment line in the order taken: `{ posPaymentId, mode, provider, amountPaise, reference (the RRN for a card machine), terminalId, moneyAccountId, accountName, payMethod, cardScheme, cardLast4, issuingBank, upiVpa (masked), authCode, settlementRef, utr, feePaise, taxPaise, netPaise, settledAt }`. `provider` is the money account's provider (`paytm` for a Paytm machine, `razorpay`, `manual`) or `counter` when the line has no account. Card machine lines carry the masked payer detail the session supplied (`terminalPaymentId`); lines typed by hand have only `mode`, `reference` and the account. Fee/tax/net/`utr`/`settledAt` are null until a settlement matched the line: show "Pending", never 0. A full card number is never returned. Unknown sale id returns `[]`. Same display rules as 7.5.

---

## 8. Receipts list, summary and receipts-by-account report

### 8.1 Goal
One normalized list of all money received (online orders and counter bills, every vendor) with totals per vendor/account/method.

### 8.2 Who / settings
ADMIN. Range rules as in Conventions.

### 8.3 Screens and fields

| Filter | Type | Validation |
|---|---|---|
| `from`, `to` | date | default last 30 days; max 400 days |
| `provider` | string | lower-cased by the server; `counter` = counter lines with no account; else the account's provider (`razorpay`, `paytm`, `manual`...). A Paytm machine payment shows under `paytm`, with `terminalId`, `method` CARD or UPI, `payer` (`VISA ****1111` or masked UPI id) and `reference` = the RRN, because the sale line carries the machine's account |
| `account` | uuid | exact money account (NOTE: the query parameter is `account`, not `accountId`) |
| `method` | string | upper-cased: `UPI`, `CARD`, `CASH`, `NETBANKING`, `WALLET`, `ONLINE`... |
| `page`, `size` | int | 0 / 20 (max 50) |

### 8.4 API sequence
1. Summary cards: `GET /admin/receipts/summary?from&to` -> array (max 500 groups, ordered provider, account, method):
```json
[ { "provider": "razorpay", "moneyAccountId": "…", "accountName": "Razorpay", "method": "UPI", "receipts": 12, "grossPaise": 150000, "feePaise": 2400, "taxPaise": 432 } ]
```
   `accountName` is `"Unassigned"` when `moneyAccountId` is null. Sum client-side only for display totals of these returned groups (the server gives no grand total).
2. Drill-down: `GET /admin/receipts?provider=&account=&method=&from=&to=&page=0&size=20` -> Page of newest first:
```json
{ "source": "ONLINE_ORDER", "id": "…", "provider": "razorpay", "moneyAccountId": "…", "accountName": "Razorpay", "terminalId": null,
  "method": "UPI", "payer": "pa***@okhdfc", "reference": "pay_Abc", "utr": "…", "grossPaise": 12500, "feePaise": 200, "taxPaise": 36, "netPaise": 12264,
  "status": "CAPTURED", "settlementRef": "setl_1", "at": "…Z", "documentNo": "VSM-1012" }
```
   `source`: `ONLINE_ORDER` (documentNo = order number) or `POS_SALE` (documentNo = invoice number; `id` is the counter PAYMENT line id, not the sale id). Tap a row: for ONLINE_ORDER open the order by its number; for POS_SALE search the bill by invoice number (`GET /pos/sales?invoiceNo=`).
3. Per account and method with refunds: `GET /admin/bank-reports/receipts?from&to&page&size` (size default 50) -> Page of `{ moneyAccountId, accountName, accountKind, method, receipts, grossPaise, refundedPaise, netPaise }` (`moneyAccountId` null = Unassigned).

### 8.5 State / derived
- Online rows count only `CAPTURED` and `REFUNDED` payments; counter rows count every non-CREDIT line, with `status` always `COMPLETED`.
- `method` for online = the provider's instrument upper-cased (`UPI`, `CARD`, `NETBANKING`, ...) or `ONLINE` when unknown; for counter = the line mode.
- `feePaise`, `taxPaise`, `netPaise` are null on counter rows until a statement import matches them, and null on online rows until the provider reports them. Show "-". Never compute net as gross - fee client-side when null.
- `payer` is masked by the server (`ab***@bank`, `Visa ****1111`, or bank name).

### 8.6 Edge cases
- `totalExact:false` -> "10,000+". Paging only via `page`.
- Empty state: "No receipts in this period".
- Counter rows of an unassigned line have `provider: "counter"` and `accountName: null` in the list (summary names it "Unassigned").
- Changing a filter resets `page` to 0.

### 8.7 Dart
```dart
class ReceiptLine { final String source, id, provider, method, status; final String? moneyAccountId, accountName, terminalId, payer, reference, utr, settlementRef, documentNo;
  final int grossPaise; final int? feePaise, taxPaise, netPaise; final DateTime at; }
class ReceiptTotal { final String provider, accountName, method; final String? moneyAccountId; final int receipts, grossPaise, feePaise, taxPaise; }
class ReceiptRow { final String? moneyAccountId; final String accountName, accountKind, method; final int receipts, grossPaise, refundedPaise, netPaise; }
```

### 8.8 Acceptance
- Given online and counter receipts in range, when I open Receipts, then both appear newest first with masked payers.
- Given a filter `provider=razorpay` and `method=UPI`, when applied, then only those rows load.
- Given a counter UPI line with no account, when I view the summary, then an "Unassigned" group exists.
- Given a range over 400 days, then the error shows under `to`.

---

## 9. Settlements: list, detail, items, sync button

### 9.1 Goal
See what each provider/bank paid out (UTR, gross, fee, GST on fee, net) and which of our payments each line matched.

### 9.2 Who / settings
ADMIN. Two sources: `GATEWAY_API` (pulled from the provider: `razorpay`, and `paytm` when `paytm.settlement.enabled` is on; the Paytm API part is untested, see `73` section 4) and `CSV` (imported, section 10). The pull runs nightly at 04:30 and on Razorpay's `settlement.processed` webhook; the **Sync now** button does the same on demand.

### 9.3 Screens and fields

List filters: `from`, `to` (by settled date, default 30 days, max 400), `accountId` (uuid), `provider` (lower-cased), `source` (`GATEWAY_API` or `CSV`, else 400 `source`), `page`, `size` (20). Items filter: `unmatchedOnly` (bool), `page`, `size` (default 50). Sync form: `provider` (required) and `days`.

| Sync field | Type | Required | Validation |
|---|---|---|---|
| `provider` | string | yes | known provider code; missing or unknown -> 404 `NOT_FOUND` |
| `days` | int | no | 1..60, default 7 (400 `days`) |

### 9.4 API sequence
1. `GET /admin/settlements?from&to&accountId&provider&source&page&size` -> Page, newest first:
```json
{ "id": "…", "provider": "razorpay", "providerSettlementId": "setl_1", "utr": "UTR123", "grossPaise": 100000, "feePaise": 2000, "taxPaise": 360,
  "netPaise": 97640, "status": "processed", "settledAt": "…Z", "moneyAccountId": "…", "accountName": "Razorpay", "source": "GATEWAY_API", "items": 12, "unmatchedItems": 1 }
```
   Show a red chip when `unmatchedItems > 0`.
2. `GET /admin/settlements/{id}` -> one row (404 unknown).
3. `GET /admin/settlements/{id}/items?unmatchedOnly=false&page=0&size=50` -> Page:
```json
{ "id": "…", "kind": "PAYMENT", "providerEntityId": "pay_Abc", "grossPaise": 12500, "feePaise": 200, "taxPaise": 36, "netPaise": 12264, "method": "upi",
  "settledAt": "…Z", "utr": "…", "reference": "pay_Abc", "matched": true, "paymentId": "…", "orderId": "…", "orderNo": "VSM-1012", "refundId": null, "posPaymentId": null, "invoiceNo": null }
```
   `kind`: `PAYMENT`, `REFUND`, `ADJUSTMENT`, `FEE`. Matched online payment -> link by `orderNo`; matched counter line -> `invoiceNo`; refund -> `refundId`. `matched:false` rows also appear in the exceptions queue.
4. **Sync now:** `POST /admin/settlements/sync` body `{ "provider": "razorpay", "days": 7 }` -> 200 `{ "provider", "settlementsSeen", "settlementsStored", "itemsStored", "skipped" }`. Show a toast "Seen N, new M". `provider` may be `razorpay` or `paytm`. `skipped: true` -> "Another sync is already running, try again in a minute". Errors: 404 unknown provider; 503 `SETTLEMENT_NOT_CONFIGURED` ("This provider is not set up yet": the keys are missing); 400 `days`; a provider outage is a 5xx: show a retry. `manual` provider always answers zeros (nothing to pull).

### 9.5 State / derived
- `netPaise` = gross - fee - tax for payments (server-computed, provider-reported for the API). `items` and `unmatchedItems` are counts computed by the server.
- Matching is done only by the server: an online item matches by provider payment id and amount; a counter item (CSV) by reference and amount to a UPI/CARD line that is not yet matched.
- Syncing is safe to repeat: settlements and items are stored once; a settlement already complete is skipped.

### 9.6 Edge cases
- Double tap on Sync: disable the button while the call runs; a parallel call returns `skipped:true`.
- A settlement can be listed with `items: 0` right after a webhook pull before details arrive.
- Empty state: "No settlements in this period. Use Sync now or Import statement."
- Statuses are provider text (`processed`, `IMPORTED`); display as-is, do not switch on them.
- Paytm payouts (API or file) arrive as provider `paytm`; each item keeps its own `utr`, fee (commission), tax (GST) and net, and `detail` JSON (transaction id, order id, type, status, payment mode, `posId`, `extSerialNo`).

### 9.7 Dart
```dart
class Settlement { final String id, provider, providerSettlementId, status, source; final String? utr, moneyAccountId, accountName; final DateTime? settledAt;
  final int grossPaise, feePaise, taxPaise, netPaise, items, unmatchedItems; }
class SettlementItem { final String id, kind, providerEntityId; final int grossPaise, feePaise, taxPaise, netPaise; final bool matched;
  final String? method, utr, reference, paymentId, orderId, orderNo, refundId, posPaymentId, invoiceNo; final DateTime? settledAt; }
class SyncResult { final String provider; final int settlementsSeen, settlementsStored, itemsStored; final bool skipped; }
```

### 9.8 Acceptance
- Given a settlement with 1 unmatched item, when I open the list, then a red "1 unmatched" chip shows and Items with `unmatchedOnly=true` lists it.
- Given I tap Sync with days 0, then a field error shows under days.
- Given Razorpay keys are missing, when I tap Sync, then "not set up yet" (503) is shown.
- Given two taps, then the second shows "already running" (skipped).

---

## 10. Statement CSV import

### 10.1 Goal
Load an acquirer/bank statement (card machine batch, UPI collection, bank statement) so counter lines and online payments get their fee, GST, net and UTR, and so mismatches reach the exceptions queue.

### 10.2 Who / settings
ADMIN. Limits: file at most 2 MB and 5000 data rows (the web server itself refuses bodies over 5 MB before that check). UTF-8; a leading BOM is ignored. First row is the header.

### 10.3 Screens and fields (multipart/form-data)

| Part | Type | Required | Validation / default |
|---|---|---|---|
| `file` | file (.csv) | yes | see limits; empty file / no data rows -> 400 `CSV_INVALID` |
| `moneyAccountId` | uuid | yes | must exist (400 `moneyAccountId`). The account the statement belongs to (the card terminal, UPI or bank) |
| `provider` | string | no | free code, max 40, lower-cased; default `manual`; forced to `paytm` for the Paytm format |
| `format` | string | no | `PAYTM` = the Paytm dashboard Settlement Report (columns Transaction ID, Order ID, Transaction Date, Transaction Type, Status, Amount, Commission, GST, Settled amount, Settled Date, UTR no., Payment Mode), `GENERIC` = force the column mapping below. Blank = picked automatically from the header (a Paytm report is recognised without sending it). Unknown value -> 400 `format`. When a vendor format applies, the mapping parts below are ignored |
| `utr` | string | no | bank reference of the whole payout; stored on each item |
| `dateColumn` | string | no | header name, case-insensitive; default `date` |
| `referenceColumn` | string | no | default `reference`. The value matched against payment/provider ids and counter references |
| `amountColumn` | string | no | default `amount` |
| `feeColumn` | string | no | default `fee`; column may be absent (then 0) |
| `taxColumn` | string | no | default `tax`; GST on the fee; may be absent |
| `descriptionColumn` | string | no | default `description`; may be absent |
| `dateFormat` | string | no | a Java pattern such as `dd/MM/yyyy`; blank = ISO `yyyy-MM-dd` (or a full ISO instant). Invalid pattern -> 400 `dateFormat`. A date-only value means 00:00 India time |
| `amountUnit` | `RUPEES` / `PAISE` | no | default `RUPEES` (decimals allowed, max 2). `PAISE` = whole paise |
| `delimiter` | string | no | default `,`; `;` or the word `tab` or any single character |

Cell rules: amounts accept `1,234.50`, `Rs 99`, `Rs. 99`, a rupee sign, and negatives. Fee and tax are taken as absolute values. A blank fee/tax is 0; a blank amount or reference-less required column is an error. Required columns that are missing from the header -> 400 `VALIDATION_FAILED` on `dateColumn`/`referenceColumn`/`amountColumn` ("no column named ..."). Quoted cells may hold the delimiter and line breaks (RFC 4180).

### 10.4 API sequence
1. Let the admin pick the file and the account, then preview the header row on the client (first line) to let them map columns with dropdowns pre-filled by case-insensitive name match. Remember the mapping per account (local storage) since statements repeat.
2. `POST /admin/settlements/import` as `multipart/form-data` with the parts above (the `file` part named `file`).
3. 200 result:
```json
{ "settlementId": "…", "alreadyImported": false, "rows": 4, "matched": 2, "unmatched": 2, "netPaise": 15730 }
```
   Show "4 rows: 2 matched, 2 need a look" and a button to open the settlement items with `unmatchedOnly=true`.
4. Errors: 400 `CSV_INVALID` with `details.fields.file` (first problem) and `details.problems` (up to 20 strings `"row N: ..."`, header is row 1): show the list; nothing was saved. Other 400s: `moneyAccountId`, `provider`, `dateFormat`, mapping fields.

### 10.5 State / derived
- **Paytm Settlement Report (`format=PAYTM`, or recognised by its header).** One settlement per file, provider `paytm`. Each line keeps its own `utr`, settled date, commission as fee, GST as tax and settled amount as net. A sale becomes a PAYMENT; a refund, chargeback or anything else becomes an ADJUSTMENT (takes money away); lines whose status contains FAIL, PEND or CANCEL are left out. Header case, spacing and BOM tolerant; rupee amounts become paise; dates ISO, dd-MM-yyyy or dd/MM/yyyy. Matching order: online payment by provider id; counter line whose `reference` is the Paytm order id or RRN; Paytm machine payment by RRN or order id (payment id without dashes) and amount; then, for UPI, the single counter UPI line on a `UPI` account of provider `paytm` with the same amount on that day (QR sticker). A different amount is `AMOUNT_MISMATCH`; no or ambiguous match is `UNMATCHED_ITEM`. Details in `71` section 4.4 and `73` section 4.
- Positive amount = a payment/credit line (kind `PAYMENT`): net = amount - fee - tax; matched by `reference` + exact amount against an online payment's provider payment id first, then a counter UPI/CARD line with that `reference` that has no settlement yet. Wrong amount -> `AMOUNT_MISMATCH` exception (expected vs actual); no hit -> `UNMATCHED_ITEM`.
- Negative amount = an ADJUSTMENT (bank charge, chargeback): always counts as matched; net is negative.
- Settlement gross counts only credit rows; fee, tax and net sum all rows. The settlement gets `source: "CSV"`, `status: "IMPORTED"`, settled date = latest row date.
- Matching sets fee/GST/net/UTR on the matched online payment, so receipts and the fees report update.
- The app must not parse the CSV to compute anything; show the server's counts.

### 10.6 Edge cases
- Importing the SAME file (same bytes) for the same account is a no-op: `alreadyImported:true`, `rows` is the row count, `matched`/`unmatched` are 0 (show "Already imported" and open that settlement).
- A one-byte change makes it a NEW settlement: avoid hand-editing and re-uploading a statement already imported (rows would double-count).
- Re-importing never duplicates exceptions for the same item.
- A counter line can be matched once only.
- Large files: show a progress indicator; there is no partial result.
- The `reference` of counter lines is whatever the cashier typed at the till (approval code / UPI ref): train staff to type it, otherwise rows become `UNMATCHED_ITEM`.

### 10.7 Dart
```dart
class ImportResult { final String settlementId; final bool alreadyImported; final int rows, matched, unmatched, netPaise; }
class CsvMapping { String? dateColumn, referenceColumn, amountColumn, feeColumn, taxColumn, descriptionColumn, dateFormat, amountUnit, delimiter; }
// ApiError.details['problems'] : List<String> for CSV_INVALID
```

### 10.8 Acceptance
- Given a semicolon file with `Date;Ref;Credit;Charge;GST;Narration` and mapping `dateColumn=date, referenceColumn=ref, amountColumn=credit, feeColumn=charge, taxColumn=gst, descriptionColumn=narration, dateFormat=dd/MM/yyyy, delimiter=;`, when I import, then rows are parsed and counts shown.
- Given a row amount "abc", when I import, then 400 `CSV_INVALID` lists `row N: "abc" is not an amount` and nothing is stored.
- Given the same file twice, then the second answer is `alreadyImported:true`.
- Given a row whose amount differs from our payment, then an AMOUNT_MISMATCH exception appears in the queue.
- Given no `moneyAccountId`, then a field error shows under it.

---

## 11. Exceptions queue

### 11.1 Goal
A short worklist of money that does not add up, each closed with a note.

### 11.2 Who / settings
ADMIN. Kinds: `OVERDUE_UNSETTLED` (a captured online payment still unsettled after `payment.settlementDays` + 1 day; raised by the nightly job at 04:30), `UNMATCHED_ITEM` (a settlement line with no payment/refund/counter line), `AMOUNT_MISMATCH` (matched but the amount differs).

### 11.3 Screens and fields
List filters: `status` (`OPEN` / `RESOLVED`, else 400 `status`), `kind` (else 400 `kind`), `page`, `size` (20). Resolve dialog: `note` required, trimmed, 1..300 chars.

### 11.4 API sequence
1. Badge: `GET /admin/settlements/exceptions?status=OPEN&size=1` -> read `total` (cap shows as "10,000+").
2. List: `GET /admin/settlements/exceptions?status=OPEN&kind=&page=0&size=20` -> Page newest first:
```json
{ "id": "…", "kind": "AMOUNT_MISMATCH", "settlementId": "…", "settlementItemId": "…", "paymentId": "…", "reference": "pay_Abc",
  "expectedPaise": 12500, "actualPaise": 13500, "status": "OPEN", "note": null, "createdAt": "…Z", "resolvedAt": null }
```
   `settlementId`/`settlementItemId` are null for OVERDUE_UNSETTLED; then `reference` is the payment id and `expectedPaise` is the payment amount, `actualPaise` null. Open detail: for settlement exceptions go to the settlement items; for overdue go to the order via payer detail (find the order from Unsettled, section 12).
3. Resolve: `POST /admin/settlements/exceptions/{id}/resolve` body `{ "note": "paid by cash, matched by hand" }` -> 200 the item with `status: "RESOLVED"`, `note`, `resolvedAt`. Errors: 400 `note`; 404.

### 11.5 State / derived
- Resolving does not change any payment or settlement; it only closes the problem. Fix the data first (e.g. import the right statement) if you want the numbers fixed.
- An OVERDUE exception closes itself with note "Settled" when the payment later settles.
- Resolving an already resolved item changes nothing and returns it with the ORIGINAL note (do not treat as error).

### 11.6 Edge cases
- Two admins resolving the same item: the second gets the first note back, no error.
- Empty: "Nothing needs attention".
- A re-synced or re-imported item does not create a second exception.

### 11.7 Dart
```dart
class SettlementException { final String id, kind, status; final String? settlementId, settlementItemId, paymentId, reference, note;
  final int? expectedPaise, actualPaise; final DateTime createdAt; final DateTime? resolvedAt; }
```

### 11.8 Acceptance
- Given a mismatched item, when I resolve with a 301-char note, then a field error shows under note.
- Given an OPEN overdue exception, when the payment settles, then it shows RESOLVED with note "Settled".
- Given I resolve twice, then the second call returns the first note and no error.

---

## 12. Unsettled report

### 12.1 Goal
Money received but not yet reported in any settlement, oldest first, with the expected day and an overdue highlight.

### 12.2 Who / settings
ADMIN. Uses `payment.settlementDays` (default 2). No date filter: it lists everything still unsettled.

### 12.3 Screens and fields
Filter: `overdueOnly` toggle (default false), `page`, `size` (20).

### 12.4 API sequence
`GET /admin/settlements/unsettled?overdueOnly=false&page=0&size=20` -> Page:
```json
{ "row": { "source": "ONLINE", "id": "…", "reference": "VSM-1012", "moneyAccountId": "…", "accountName": "Razorpay", "amountPaise": 12500, "receivedAt": "…Z" },
  "expectedBy": "…Z", "overdue": true }
```
`source`: `ONLINE` (reference = order number) or `COUNTER` (reference = invoice number; id = counter payment line id). `accountName` is null when unassigned.

### 12.5 State / derived
`expectedBy` = received + settlementDays; `overdue` = received earlier than now - (settlementDays + 1 days). Both are server-computed; do not recompute (the app clock may differ). Counter lines appear only for CARD lines on CARD_TERMINAL or GATEWAY accounts that no settlement item matched yet. Online rows are CAPTURED payments with no settlement.

### 12.6 Edge cases
- Cash and UPI counter lines are never "unsettled" (not on this report).
- After a statement import matches a counter line, it disappears from the list.
- Highlight overdue rows red; tap an ONLINE row to open the order by number.

### 12.7 Dart
```dart
class UnsettledRow { final String source, id, reference; final String? moneyAccountId, accountName; final int amountPaise; final DateTime receivedAt; }
class Unsettled { final UnsettledRow row; final DateTime expectedBy; final bool overdue; }
```

### 12.8 Acceptance
- Given a payment captured 4 days ago and cycle 2, then it shows overdue.
- Given overdueOnly on, then not-yet-due rows are hidden.
- Given a counter card line is matched by a CSV import, then it leaves the list.

---

## 13. Monthly fees report

### 13.1 Goal
Gateway fee and GST on the fee per month and account (the GST is input credit for accounting).

### 13.2 Who / settings
ADMIN. Range rules as in Conventions.

### 13.3 / 13.4 API
`GET /admin/bank-reports/fees?from&to&page&size` (size default 50) -> Page, newest month first:
```json
{ "month": "2026-11", "moneyAccountId": "…", "accountName": "Razorpay", "payments": 40, "grossPaise": 500000, "feePaise": 9000, "taxPaise": 1620, "missingFee": 3 }
```
Months are India calendar months of the capture date; only online CAPTURED/REFUNDED payments count (counter lines have no fee here).

### 13.5 State / derived
`missingFee` = number of payments in that group for which the provider has not reported a fee yet; `feePaise`/`taxPaise` are sums of the known ones only. Show a warning "3 payments have no fee yet" when `missingFee > 0`; totals are incomplete until the sync/job fills them. Never estimate fees in the app.

### 13.6 Edge cases
Unassigned account shows `accountName: "Unassigned"`. A range cutting a month shows only the days included. Empty: "No online payments in this period".

### 13.7 Dart
```dart
class FeeRow { final String month, accountName; final String? moneyAccountId; final int payments, grossPaise, feePaise, taxPaise, missingFee; }
```

### 13.8 Acceptance
- Given 3 payments without a reported fee, then the row shows a "3 fees pending" badge.
- Given a range over 400 days, then an error on `to`.

---

## 14. Error code cheat sheet

| HTTP / code | Where | UI |
|---|---|---|
| 400 `VALIDATION_FAILED` | any form | map `details.fields` |
| 400 `CSV_INVALID` | CSV import | show `details.problems` list |
| 404 `NOT_FOUND` | unknown account, settlement, exception, session, provider | go back / refresh |
| 409 `DUPLICATE_ACCOUNT_NAME` | create/edit account | field `name` |
| 409 `ACCOUNT_IN_USE` | edit kind, deactivate bank, delete | explain; offer Deactivate |
| 409 `LAST_CASH_ACCOUNT` | deactivate | "Keep at least one cash account" |
| 409 `REFERENCE_REUSED` | start terminal session | new reference |
| 409 `TERMINAL_PAYMENT_CLOSED` | confirm | session over; start a new one |
| 409 `TERMINAL_CONFIRMS_ITSELF` | confirm | stop confirming, poll |
| 409 `TERMINAL_BUSY` (`details.openPaymentId`), 422 `TERMINAL_REJECTED` | start Paytm session | resume / show message; see `73` |
| 409 `CANCEL_ON_MACHINE`, `ALREADY_PAID`, `NOT_VOIDABLE`, `PAYMENT_IN_USE`; 422 `VOID_WINDOW_PASSED`, `VOID_REJECTED`; 502 `VOID_UNKNOWN` | cancel / void | see `P3` F6 |
| 422 `TERMINAL_PAYMENT_NOT_PAID` / `_AMOUNT` / `_MODE`, 409 `TERMINAL_PAYMENT_USED` | sale with `terminalPaymentId` | see `P3` F6 |
| 503 `TERMINAL_NOT_CONFIGURED` | terminal calls | manual reference fallback |
| 503 `SETTLEMENT_NOT_CONFIGURED` | sync | "provider not set up" |
| 403 / 401 | wrong role / no token | sign-in |

---

## 15. Build order

1. Accounts list/form (2), defaults in config (3).
2. Till channel chooser and line tagging (5), then the generic terminal dialog (6) behind a single repository class.
3. Receipts list + summary (8), receipts-by-account.
4. Settlements list/detail/items + Sync (9).
5. CSV import with mapping memory (10).
6. Exceptions + badge (11), Unsettled (12), Fees (13).
7. Account book + entries (4), payer block (7).

---

## 16. Doc vs code differences

Items where `71-bank-and-settlements.md`, `73-paytm-terminal.md` or `00-overview.md` disagreed with the code. The code won; the guides were corrected afterwards (state in brackets).

1. **Two terminal flows / Paytm not an adapter.** (Resolved by the unification, in `71` and `73`.) The Paytm machine is now a `CardTerminal` adapter and a channel of kind `CARD_TERMINAL`; one set of calls (`/pos/terminal/payments`) serves `manual` and `paytm`; `/pos/terminals` and `/pos/terminal-payments*` remain as provider-level twins on the same rows. `PaytmSettlementProvider` is a settlement provider (API, off unless `paytm.settlement.enabled`) next to `razorpay` and `manual`, and `PaytmStatementParser` reads the dashboard file.
2. **`default` flag.** (Fixed in `71` section 3.) A UPI/CARD mode may have NO default (blank setting, or the account is inactive/not offered); only CASH falls back to the first channel. A GATEWAY account is offered under both UPI and CARD; BANK is never offered.
3. **Receipt `status`.** (Fixed in `71` section 4.3.) Online rows `CAPTURED` or `REFUNDED`; counter rows always `COMPLETED`, `method` = the line mode, CREDIT lines excluded.
4. **Manual entry limits.** (Fixed in `71` section 4.1.) `amountPaise` 1..10,000,000,000, `note` max 200, `at` optional.
5. **Payment-line `reference` vs terminal `reference`.** (Fixed in `71` section 4.2.) Payment line `reference` max 100, `terminalId` max 60; terminal session `reference` max 60 (Paytm 50).
6. **CSV size.** (Fixed in `71` section 4.4.) 2 MB is checked by the service (`CSV_INVALID`); the web layer caps multipart at 5 MB file / 6 MB request and refuses bigger bodies earlier (generic error body: not verified).
7. **Sync with no provider.** (Fixed in `71`.) 404 also when `provider` is missing or blank.
8. **Receipts filter name.** (Fixed in `71`.) The receipts list takes `account` (uuid); settlements take `accountId`.
9. **Bank report row.** (Fixed in `71`.) Also has `accountKind` and `receipts` (count).
10. **Purchase payments.** (Fixed in `71`.) They store `moneyAccountId` unvalidated and post nothing to any book (see `P2`).
11. **Unsettled filters.** (Fixed in `71`.) No date range, only `overdueOnly`, `page`, `size`.
12. **Idempotency-Key.** `00`/older guides imply keys on all money writes. Here the key is OPTIONAL on account create, entries and the generic terminal start (the terminal also dedupes by `reference`). (Stated in section Conventions; `71` says "optional" per endpoint.)

Not verified: real Razorpay settlement/recon responses (canned HTTP only, as `71` section 8 says); the exact error body for a multipart over 5 MB; the Paytm flows against a real Paytm server (only the in-JVM fake and canned reports are tested; see `73` section 8).
