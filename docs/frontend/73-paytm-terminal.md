# 73 - Paytm card machine (wireless EDC): admin set-up and till flow (frontend guide)

Scope: the Paytm wireless swipe machine as one vendor of the shop's single card-machine flow (read `71-bank-and-settlements.md` first: channels, sessions, receipts, settlements). The backend sends the bill amount to a named machine, polls Paytm for the result and keeps the card details (network, masked card, RRN, authorisation code) for the bill. Also: admin configuration, the machines master, the "test connection" button, same-day void, and the Paytm settlement import.

Conventions as in 10-customer-catalog-wallet-etc.md (Bearer token, camelCase, money in paise, error body `{code, message, traceId, details}`, validation errors in `details.fields`).

**Status of this integration: built and tested against an in-JVM fake of Paytm only. Nothing has run against a real Paytm server.** See "Before go-live" at the end. Do not promise the owner that card payments work until the sandbox run is done.

## The unified model in one minute

- Each Paytm machine (a row of `payment_terminal`) has its own bank-module money account: kind `CARD_TERMINAL`, provider `paytm`, identifier = terminal id, name = machine name, optional `settlesToAccountId` = the BANK it pays out into. It is made when the machine is added (and by a one-off migration for machines that existed before), and the two are kept in step: switching the machine off switches its account off and the other way round.
- So `GET /api/v1/pos/payment-channels` lists the Paytm machines (with `terminalId`, `moneyAccountId` and a `default` flag) next to the manual machine, the PhonePe QR, the cash drawer and every other vendor's channel. Several vendors can be active at the same time; the cashier picks one per payment.
- The till starts, polls and cancels through the **generic** terminal calls `/api/v1/pos/terminal/payments` (see `71`, section 3). The channel's provider chooses the adapter: a Paytm channel goes to the Paytm machine, a `manual` channel stays manual. `pos.terminalProvider` is only the default for a channel whose provider has no adapter.
- A Paytm payment is **one row** (`terminal_payment`). The generic session id *is* that row's id. The Paytm-specific endpoints below (`/pos/terminals`, `/pos/terminal-payments...`) read and write the same row: a payment started on one can be polled, cancelled, voided or put on a bill through the other. There is no second table of truth and no duplicate row.
- The id goes on the sale line as `terminalPaymentId`. The line then carries the machine's money account, terminal id, the RRN as `reference` and the masked card/UPI detail (`pos_payment.detail`), so the receipts list, the bill payer view and the settlement import all show it under vendor `paytm`.

## Who can do what

| Area | Role |
|---|---|
| Configuration screen (Paytm group), machines master, test button | ADMIN only |
| `GET /api/v1/pos/payment-channels`, `/pos/terminal/payments...`, `GET /api/v1/pos/terminals`, `/pos/terminal-payments...` | CASHIER or ADMIN |
| Customers | 403 everywhere here |

## 1. Admin configuration screen

The Paytm settings are on the existing config screen (`GET/PUT /api/v1/admin/config`), as a group named `PAYTM` ("Paytm card machine"). Nothing new to build except showing this group; each row already carries its label, help text, type, allowed values and min/max. Keys:

| key | type | default | meaning |
|---|---|---|---|
| `paytm.enabled` | BOOLEAN | false | master switch. The save is refused (400, `details.fields["paytm.enabled"]`) while merchant id, merchant key or channel id is empty |
| `paytm.environment` | ENUM STAGING/PRODUCTION | STAGING | picks the default Paytm host |
| `paytm.baseUrl` | STRING | blank | optional override of the host; must start with `https://` |
| `paytm.mid` | STRING | | merchant id |
| `paytm.merchantKey` | SECRET | | exactly 16 characters; encrypted, never shown back (only `set: true`); leave blank to keep |
| `paytm.channelId` | STRING | | issued by Paytm |
| `paytm.pollSeconds` | INT 3..60 | 10 | seconds between status checks |
| `paytm.waitSeconds` | INT 30..900 | 180 | after this an unpaid payment becomes EXPIRED |
| `paytm.autoAccept` | BOOLEAN | true | machine takes the amount without the cashier touching it |
| `paytm.paymentMode` | ENUM ALL/CARD/QR | ALL | what the machine offers |
| `paytm.timeoutConfig` | INT 0..60 | 0 | time-out value sent to Paytm; 0 = not sent (Paytm gives no unit) |
| `paytm.settlement.enabled` | BOOLEAN | false | read settlements from the Paytm API (untested) |
| `paytm.settlement.authStyle` | ENUM CHECKSUM/BEARER | BEARER | how the settlement API is signed in to |
| `paytm.settlement.token` | SECRET | | bearer token for the settlement API |
| `paytm.settlement.baseUrl` | STRING | blank | settlement host override |
| `paytm.settlement.syncDays` | INT 1..60 | 7 | days each settlement sync reads back |

The `issues` list of the config view warns "The Paytm card machine is on but ... is missing". While `paytm.enabled` is off, a Paytm channel exists in the list but starting a payment on it answers 503 `TERMINAL_NOT_CONFIGURED`; hide or grey it from the status of the config if you want.

### Machines master (admin)

`/api/v1/admin/payment-terminals`

- `GET ?page=0&size=20` - paged list (newest first): `{items:[TerminalView], page, size, total, totalExact, hasMore}`.
- `POST` body `{terminalId, name, active?, settlesToAccountId?}` -> 201 `TerminalView`. `terminalId` is the Paytm terminal id (8 letters or digits, printed on the machine / from Paytm). `settlesToAccountId` is the BANK money account the machine's takings are paid into (optional; must be an active BANK, else 400 `details.fields.settlesToAccountId`). The machine's money account is created in the same step. 409 `DUPLICATE_TERMINAL` if already added; 400 with `details.fields.terminalId` or `name` otherwise.
- `PUT /{id}` body `{name, active?, settlesToAccountId?}` - rename (the account is renamed too; a name another account already has gets the terminal id appended), switch on/off, set or clear (send nothing) the bank. The terminal id cannot change. 404 if unknown.
- `POST /{id}/deactivate` - switch off the machine and its account.
- `POST /{id}/test` - the Test button. Sends a harmless status call for a made-up payment. Never moves money.

```json
// TerminalView
{ "id": "uuid", "provider": "paytm", "terminalId": "AB12CD34", "name": "Counter 1", "active": true,
  "moneyAccountId": "uuid-of-its-CARD_TERMINAL-account", "settlesToAccountId": "uuid-of-the-bank-or-null", "createdAt": "2026-11-09T04:30:00Z" }
// test result
{ "ok": false, "code": "TERMINAL_INACTIVE", "message": "The card machine is not active or not online. ..." }
```

Test result `code`: `OK`, `TERMINAL_INACTIVE`, `BAD_CREDENTIALS` (wrong merchant id/key/channel id), `NETWORK` (Paytm not reachable), `NOT_CONFIGURED` (missing settings), or `PAYTM_ERROR` / `PAYTM_HTTP_nnn` (something unexpected; the message has Paytm's raw code). Show `message` as is; colour by `ok`.

The account also appears on the money-accounts screen (kind `CARD_TERMINAL`). Switching it off there switches the machine off too, and switching it on brings the machine back.

## 2. Till flow

1. On opening the payment screen call `GET /api/v1/pos/payment-channels`. A Paytm machine is a CARD channel: `{mode:"CARD", moneyAccountId, provider:"paytm", name, terminalId, kind:"CARD_TERMINAL", default}`. `default` is the saved default, or the only channel of the mode. Let the cashier choose when there are several. (`GET /api/v1/pos/terminals` still lists just the Paytm machines, `{id, provider, name, terminalId, moneyAccountId, default}`, for Paytm-only screens.)
2. Cashier presses "Card / UPI on machine" on that channel: `POST /api/v1/pos/terminal/payments` with body `{amountPaise, reference: "<bill number or cart ref, new per attempt>", moneyAccountId: <channel.moneyAccountId>}` -> 201 session (shape in `71`, section 3). The reference is the key of the payment: the same reference and amount returns the same payment, so a retry after a crash is safe. (The Paytm-specific start, `POST /pos/terminal-payments` with `Idempotency-Key` and `{terminalId: <our machine row id>, ...}`, does the same and returns the Paytm payment object below.)
3. Show "Waiting for customer on the machine" and poll `GET /api/v1/pos/terminal/payments/{id}` every `pollAfterSeconds` while `open` is true. Each GET may itself check Paytm (at most once per poll interval), so polling alone is enough; the server also polls in the background, and a payment survives an app crash or a server restart: store the session `id` locally and resume with the GET.
4. `state` APPROVED (`providerStatus` SUCCESS): put it into the sale as a payment line `{mode: "CARD" or "UPI", amountPaise, terminalPaymentId: <id>}` (see 3). Use mode UPI when `payMethod` is `UPI`, otherwise CARD.
5. Terminal states: DECLINED (FAILED), CANCELLED (CANCELLED or VOIDED), EXPIRED: tell the cashier and offer another way to pay.

### Session object for a Paytm machine

The generic session (see `71`) with the Paytm fields filled:

```json
{ "id": "uuid", "reference": "INV/1", "provider": "paytm", "amountPaise": 12500, "state": "PENDING", "providerStatus": "PENDING",
  "mode": "PUSH", "open": true, "message": "Waiting for the customer to pay on the machine", "instructions": "Waiting for the customer to pay on the machine",
  "moneyAccountId": "uuid", "terminalId": "AB12CD34", "terminalName": "Counter 1",
  "payMethod": null, "cardScheme": null, "maskedCard": null, "rrn": null, "approvalCode": null, "issuingBank": null, "upiVpa": null,
  "canCancel": true, "cancelOnMachine": false, "canVoid": false, "pollAfterSeconds": 10, "posSaleId": null, "createdAt": "...", "finishedAt": null }
```

| `providerStatus` | `state` | meaning | UI |
|---|---|---|---|
| REQUESTED | PENDING | row written, amount being sent (rarely seen) | spinner |
| PENDING | PENDING | machine has the amount, customer paying | "Customer is paying on the machine", Cancel button |
| UNKNOWN | PENDING | the answer to the request was lost or unclear; the server is checking with Paytm | "Checking with the machine...", keep polling, do NOT start another payment on this machine |
| SUCCESS | APPROVED | paid. `payMethod` (CREDIT_CARD, DEBIT_CARD, UPI, EMI...), `cardScheme`, `maskedCard`, `rrn`, `approvalCode` (the Paytm auth code), `issuingBank`, `upiVpa` (masked) filled | green tick, show last digits and approval code, enable "Use for bill" |
| FAILED | DECLINED | declined, failed, or Paytm never got it | show `message`, offer retry |
| CANCELLED | CANCELLED | cashier asked to cancel and the machine reported the cancel | offer retry |
| EXPIRED | EXPIRED | nobody paid within `waitSeconds` | "Not paid in time". Still watched for 15 minutes: a late payment flips it to SUCCESS/APPROVED, so keep the payment id reachable (e.g. a "check again" button) before taking another payment |
| VOIDED | CANCELLED | paid and then voided the same day | cannot be used |

`canCancel` is true while `open`. `cancelOnMachine` is true once a cancel was asked and the machine has not reported it. `canVoid` is true for an APPROVED payment taken today, not yet on a bill.

The Paytm-specific object (`GET/POST /pos/terminal-payments...`) is the same payment in Paytm words: `{id, terminalRowId, terminalId, terminalName, amountPaise, reference, status, open, message, resultCode, payMethod, cardScheme, maskedCard, rrn, authCode, issuingBank, upiVpa, posSaleId, canCancel, cancelOnMachine, canVoid, pollAfterSeconds, createdAt, finishedAt}` with `status` = the `providerStatus` above.

### Errors of starting a payment

| code | HTTP | when / UI |
|---|---|---|
| `VALIDATION_FAILED` | 400 | amount outside 1..100000000, unknown or switched-off `moneyAccountId`, reference over 60 (Paytm: 50) chars, the account is not a Paytm machine (`details.fields`) |
| `REFERENCE_REUSED` | 409 | the reference was used for another amount or another machine; use a new reference |
| `TERMINAL_INACTIVE` | 422 | (Paytm-specific start) machine switched off in admin |
| `TERMINAL_BUSY` | 409 | the machine already has an open payment; `details.openPaymentId` lets the till resume it. Another machine, a manual channel or another vendor stays usable |
| `TERMINAL_REJECTED` | 422 | Paytm refused the request. `message` is the plain reason to show; `details.reason` is stable: `TERMINAL_INACTIVE` (machine off or offline), `TERMINAL_BUSY_AT_PAYTM` (another request is open on the machine), `BAD_CHECKSUM` (admin: wrong key/MID); `details.paytmCode`, `details.paymentId`. The payment is stored as FAILED and the machine is free again |
| `TERMINAL_NOT_CONFIGURED` | 503 | generic start while `paytm.enabled` is off or incomplete: tell the cashier to ask the admin |
| `PAYTM_NOT_CONFIGURED` | 503 | the same on the Paytm-specific endpoints |
| `RATE_LIMITED` | 429 | more than 30 card payments started a minute by one user |

An unreadable or lost answer to the request is NOT an error: you get 201 with `providerStatus: "UNKNOWN"`. A request is sent exactly once; a lost answer is resolved by a status check, so a customer is never charged twice by a retry.

`GET /api/v1/pos/terminal/payments/{id}` (or `/pos/terminal-payments/{id}`) returns the session; 404 if unknown.

`POST /api/v1/pos/terminal/payments/{id}/cancel` (or `/pos/terminal-payments/{id}/cancel`) - the cancel rule. **Paytm documents no call that cancels a sale already on the machine.** So the server never marks a payment CANCELLED on its own while a customer may still pay:
- if Paytm says the sale ended without payment (the cashier pressed Cancel on the machine): 200 with `state: CANCELLED`;
- if it is paid: 409 `ALREADY_PAID` (use the payment, or void it today);
- otherwise 409 `CANCEL_ON_MACHINE`: show "Press Cancel on the machine. This payment closes by itself once the machine reports it." Keep polling (`cancelOnMachine` is now true); it turns CANCELLED (because the cancel was asked) or EXPIRED;
- already finished payments: 200 with the current state.

`POST /api/v1/pos/terminal/payments/{id}/void` (or `/pos/terminal-payments/{id}/void`) - same-day void of a paid payment that no bill used:
- 200 `VOIDED` (`state` CANCELLED);
- 409 `NOT_VOIDABLE` (not paid), 409 `PAYMENT_IN_USE` (a bill has it: return the bill instead);
- 422 `VOID_WINDOW_PASSED`: the day (India time) ended. Message: refund from the Paytm dashboard;
- 422 `VOID_REJECTED` with Paytm's reason in `message` (`details.reason` e.g. `VOID_ALREADY_REQUESTED`);
- 502 `VOID_UNKNOWN`: Paytm not reachable; check the machine before trying again. While unresolved the payment cannot be put on a bill.

`POST /api/v1/pos/terminal/payments/{id}/confirm` is for manual machines only; on a Paytm session it answers 409 `TERMINAL_CONFIRMS_ITSELF`.

### 3. Using the payment on a counter bill

`POST /api/v1/pos/sales` payment lines get one optional field:

```json
{ "mode": "CARD", "amountPaise": 12500, "terminalPaymentId": "uuid" }
```

`reference`, `moneyAccountId` and `terminalId` may be omitted: the server fills them from the machine. The stored line then has `reference` = the RRN (else `PAYTM <transaction id>`), `moneyAccountId` = the machine's account, `terminal_id` = the Paytm terminal id and, in `pos_payment.detail`, `{provider, paymentId, terminalId, payMethod, cardScheme, maskedCard, cardLast4, issuingBank, authCode, upiVpa (masked), rrn}`. A `reference` the till sends is kept as sent. Naming another account than the machine's is 400 on `payments[i].moneyAccountId`.

Rules (checked inside the sale; the sale fails as a whole): paid (APPROVED), not used by another bill, not being voided, amount equal to the line, line mode CARD (or UPI when the customer paid by UPI on the machine), only on CARD or UPI lines, not on an offline bill. Old payloads without the field behave exactly as before. The same field takes a `manual` session id (see `71`).

| code | HTTP | meaning |
|---|---|---|
| `TERMINAL_PAYMENT_NOT_PAID` | 422 | not paid yet (or voided) |
| `TERMINAL_PAYMENT_USED` | 409 | already on a bill |
| `TERMINAL_PAYMENT_AMOUNT` | 422 | line amount differs from the card amount |
| `TERMINAL_PAYMENT_MODE` | 422 | line mode does not fit how the customer paid |
| `VALIDATION_FAILED` | 400 | unknown id, wrong line mode, offline bill, other account; `details.fields["payments[i].terminalPaymentId"]` |

Offline tills: a card machine payment needs the server, so card/UPI via the machine is online-only. If the network is down, use the old way (CARD line with a manual reference).

Refunds: a return of a Paytm machine line follows the line's account (the machine's). Paytm's refund API is not used yet: refund on the Paytm dashboard and record the return.

Receipts and bill view (admin): `GET /admin/receipts?provider=paytm` lists the line with `provider: "paytm"`, the machine's account, `method` CARD or UPI, `terminalId`, `payer` (`VISA ****1111` or the masked UPI id), `reference` = RRN, and, once settled, `utr`, fee, tax and net. `GET /admin/payments/by-sale/{saleId}` gives the payer detail of the bill's lines (masked; never a full card number).

## 4. Paytm settlements (bank and receipt details)

The owner wants UPI received, payments, fees, UTR and payouts per transaction, like Razorpay. They arrive in the bank module's settlement tables in two ways, both producing provider `paytm`:

- **Dashboard file.** `POST /api/v1/admin/settlements/import` (multipart) with the Paytm "Settlement Report" CSV (Transaction ID, Order ID, Transaction Date, Updated date, Transaction Type, Status, Amount, Commission, GST, Settled amount, Settled Date, UTR no., Split Flag, Payment Mode), `moneyAccountId` and optional `format=PAYTM` (picked automatically from the header when blank). Header case, spacing and BOM tolerant; rupee amounts become paise; dates ISO, dd-MM-yyyy, dd/MM/yyyy. One settlement per file; per line `utr`, settled date, commission as fee, GST as tax, settled amount as net, payment mode. Bad rows are reported as `CSV_INVALID` with `details.problems` ("row N: ...") and the file is not stored.
- **Settlement API.** `PaytmSettlementProvider` plugs `PaytmSettlementApiClient` into the bank's provider registry. Off unless `paytm.settlement.enabled`; when on (and the merchant id and the secret of the sign-in style are saved) the nightly job and "Sync now" (`POST /admin/settlements/sync {provider:"paytm"}`) read the last `paytm.settlement.syncDays` days and store one settlement per Paytm payout (payout id, else UTR, else settled date). Untested; auth style, paths other than SettlementDetail, field names and the response shape are unconfirmed.

Matching of a sale line (see `71`, section 4.4 for the order): a machine payment is matched by its **RRN or order id** (= the payment id without dashes, which is what Paytm calls the order id for a machine sale) and amount; a different amount is an `AMOUNT_MISMATCH` exception with our amount as `expectedPaise`. Refund and chargeback lines become adjustments that take money away. Items keep `posId`, `extSerialNo`, transaction id and type in their JSON `detail`.

**UPI on the QR sticker (not the machine).** Such a payment has no payment of ours behind it and no real-time API we know of; it appears only in the settlement. It is matched to a counter UPI line when exactly one line on a `UPI` money account of provider `paytm` has the same amount on the transaction day (India time). Create the sticker as an account `{kind:"UPI", provider:"paytm"}` and tag its counter UPI lines with it. With no candidate, or two candidates, the item becomes an `UNMATCHED_ITEM` exception for a person to resolve. The till cannot confirm a sticker payment at the counter; only a machine payment is confirmed instantly. If the shop sells many equal-amount UPI sales a day, expect those exceptions.

## 5. How the Paytm adapter plugs into the generic ports (for the integrator)

Everything Paytm-specific sits behind `com.vijay.mall.payment.paytm.domain.PaytmTerminalService` (`requestSale`, `status`, `cancelPending`, `voidSale`, `testConnection`). `PaytmCardTerminal` (package `payment.paytm.app`) implements the bank's `CardTerminal` port on top of `TerminalPaymentService`, which owns idempotency, the busy rule, polling and expiry:

| generic call | Paytm class and method |
|---|---|
| channel list | money account of kind `CARD_TERMINAL`, provider `paytm` (made by `PaymentTerminalService`); `activeForTill()` for the Paytm-only list |
| start | `PaytmCardTerminal.requestPayment(Request)` -> `TerminalPaymentService.start(actor, "pos:"+reference, terminalRowId, amountPaise, reference)` |
| poll | `find(id)` -> `TerminalPaymentService.get(id)` |
| cancel / void | `cancel(actor, id)`, `voidPayment(actor, id)` |
| attach to sale | `attach(...)` -> `TerminalPaymentService.attach(...)`; `TerminalService.attach` is what `PosService` calls for any session |
| settlements | `PaytmSettlementProvider` (API) and `PaytmStatementParser` (file) |

Dispatch: `TerminalService.request` takes the provider of the chosen channel's money account; if the registry has an adapter of that name it is used, otherwise the `pos.terminalProvider` default. A channel named explicitly with provider `manual` always uses the manual terminal; an implicit default account with provider `manual` does not override the default adapter.

## 6. Dart-friendly sketches

```dart
class TerminalSession {
  final String id, state, providerStatus, message;
  final bool open, cancelOnMachine;
  final int amountPaise, pollAfterSeconds;
  final String? rrn, approvalCode, cardScheme, maskedCard, payMethod;
  TerminalSession.fromJson(Map<String, dynamic> j)
      : id = j['id'], state = j['state'], providerStatus = j['providerStatus'], message = j['message'] ?? '',
        open = j['open'], cancelOnMachine = j['cancelOnMachine'] ?? false,
        amountPaise = j['amountPaise'], pollAfterSeconds = j['pollAfterSeconds'],
        rrn = j['rrn'], approvalCode = j['approvalCode'], cardScheme = j['cardScheme'],
        maskedCard = j['maskedCard'], payMethod = j['payMethod'];
  bool get paid => state == 'APPROVED';
  String get billMode => payMethod == 'UPI' ? 'UPI' : 'CARD';
}

Future<TerminalSession> takeMachinePayment(Api api, PayChannel channel, int paise, String ref) async {
  // ref is new per attempt and kept with the cart: a retry after a crash with the same ref returns the same payment
  var s = TerminalSession.fromJson(await api.post('/pos/terminal/payments',
      body: {'amountPaise': paise, 'reference': ref, 'moneyAccountId': channel.moneyAccountId}));
  await store.savePendingTerminalPayment(s.id);
  while (s.open) {
    await Future.delayed(Duration(seconds: s.pollAfterSeconds));
    s = TerminalSession.fromJson(await api.get('/pos/terminal/payments/${s.id}'));
  }
  return s; // then: sale line {mode: s.billMode, amountPaise: paise, terminalPaymentId: s.id}
}
```

## 7. Implementation checklist

- [ ] Show the `PAYTM` group on the config screen (already generic), with a Test button per machine.
- [ ] Machines screen: list, add (with the bank it settles into), rename, switch off, test.
- [ ] Till: channel picker from `/pos/payment-channels` (skip when `default`), amount, a new reference per attempt, polling by `pollAfterSeconds`, persist the session id for crash recovery.
- [ ] Handle all states; never start a second payment on a machine while one is PENDING/UNKNOWN (409 `TERMINAL_BUSY` gives `openPaymentId`).
- [ ] Cancel button: show the "press Cancel on the machine" message for `CANCEL_ON_MACHINE` and while `cancelOnMachine`.
- [ ] Attach with `terminalPaymentId` on the CARD/UPI payment line; show RRN and approval code on the receipt screen.
- [ ] Void button when `canVoid`; after the day, show the dashboard-refund message.
- [ ] Offline: fall back to a manual reference CARD/UPI line.
- [ ] Settlements screen: import the Paytm Settlement Report, work the exception queue (sticker UPI that could not be matched).

## 8. Before go-live (confirm with Paytm; nothing below is verified)

1. **Checksum interoperability.** The signing was implemented from Paytm's open-source checksum library (SHA-256 of `body|salt` + salt, AES-128/CBC/PKCS5, IV `@@@@&&&&####$$$$`, Base64) and matches it by construction, but Paytm publishes no test vector and it has never been accepted by Paytm's servers. The string signed is the compact JSON of the `body` object exactly as sent (one place: `PaytmEdcClient.envelope`). Run the sandbox test first.
2. Wireless/cloud ECR enabled on our MID, and the machine model supports it.
3. Staging MID, merchant key (16 characters), **channel id**, staging terminal id; production host(s). The default hosts (`https://securestage.paytmpayments.com`, `https://secure.paytmpayments.com`) are a guess from the public documentation; override with `paytm.baseUrl`.
4. `head.version` (we send `3.1`), the field types in the sale body (amount as a string of paise, `autoAccept` as the string `"true"`), `displayInfo` format (we send a short single line), and the meaning and unit of `timeoutConfig` (not sent unless configured).
5. Result codes: we handle 0009, 0000, 0010, 0030, 0011, 0404, 0012, 0007, 0333, 0233, 0330, 0182; any other code is shown as "Paytm answered with code ..." and the payment stays UNKNOWN until a status check resolves it. Confirm the full list, and what the void API and a status call return after a void (we treat 0009 or 0000 on void as accepted).
6. Field names of the status answer for UPI payer (`payerVpa`/`vpa`/`upiVpa` are all tried) and card fields.
7. How long an unpaid sale stays on the machine, whether a pending sale can be cancelled by API (we assume it cannot), rate limits for status calls.
8. Refunds on a later day: how a machine sale maps to the refund API (until then: refund in the Paytm dashboard and record it with the return).
9. Settlement APIs: access, auth style (BEARER vs CHECKSUM), exact paths and fields; whether machine card sales and QR payments appear in them; the settlement cycle and the report schedule.
10. **Settlement matching keys.** That the dashboard report's Order ID for a machine sale equals our merchant transaction id (the payment id without dashes) and that the report carries (or the API gives) the RRN; if not, machine lines fall back to the amount-and-day rule only for UPI and become exceptions for cards. Check one real report before relying on the matching, and whether a settlement report line for a machine sale has status SUCCESS or another word (lines with FAIL, PEND or CANCEL in the status are skipped).
11. Whether Paytm checks the response `head.checksum` for status/void (we do not verify response signatures).
12. Live check of the unified flow in the sandbox: start from the generic endpoint on a Paytm channel, pay on the machine, put the session id on a bill, import that day's report and see the line settle.
