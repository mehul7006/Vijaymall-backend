# 73 - Paytm card machine (wireless EDC): admin set-up and till flow (frontend guide)

Scope: the Paytm wireless swipe machine. The backend sends the bill amount to a named machine, polls Paytm for the result and keeps the card details (network, masked card, RRN, authorisation code) for the bill. Also: admin configuration, the machines master, the "test connection" button, same-day void, and the Paytm settlement import (bank/receipt details).

Conventions as in 10-customer-catalog-wallet-etc.md (Bearer token, camelCase, money in paise, error body `{code, message, traceId, details}`, validation errors in `details.fields`).

**Status of this integration: built and tested against an in-JVM fake of Paytm only. Nothing has run against a real Paytm server.** See "Before go-live" at the end. Do not promise the owner that card payments work until the sandbox run is done.

## Who can do what

| Area | Role |
|---|---|
| Configuration screen (Paytm group), machines master, test button | ADMIN only |
| `GET /api/v1/pos/terminals`, start/read/cancel/void a card payment | CASHIER or ADMIN |
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

The `issues` list of the config view warns "The Paytm card machine is on but ... is missing".

### Machines master (admin)

`/api/v1/admin/payment-terminals`

- `GET ?page=0&size=20` - paged list (newest first): `{items:[TerminalView], page, size, total, totalExact, hasMore}`.
- `POST` body `{terminalId, name, active?, moneyAccountId?}` -> 201 `TerminalView`. `terminalId` is the Paytm terminal id (8 letters or digits, printed on the machine / from Paytm). 409 `DUPLICATE_TERMINAL` if already added; 400 with `details.fields.terminalId` or `name` otherwise.
- `PUT /{id}` body `{name, active?, moneyAccountId?}` - rename, switch on/off, set the money account (the terminal id cannot change). 404 if unknown.
- `POST /{id}/deactivate` - switch off.
- `POST /{id}/test` - the Test button. Sends a harmless status call for a made-up payment. Never moves money.

```json
// TerminalView
{ "id": "uuid", "provider": "paytm", "terminalId": "AB12CD34", "name": "Counter 1", "active": true,
  "moneyAccountId": null, "createdAt": "2026-11-09T04:30:00Z" }
// test result
{ "ok": false, "code": "TERMINAL_INACTIVE", "message": "The card machine is not active or not online. ..." }
```

Test result `code`: `OK`, `TERMINAL_INACTIVE`, `BAD_CREDENTIALS` (wrong merchant id/key/channel id), `NETWORK` (Paytm not reachable), `NOT_CONFIGURED` (missing settings), or `PAYTM_ERROR` / `PAYTM_HTTP_nnn` (something unexpected; the message has Paytm's raw code). Show `message` as is; colour by `ok`.

`moneyAccountId` is an uuid of the bank module's money account (no foreign key; the bank module validates it when it exists). Send null until then.

## 2. Till flow

1. On opening the payment screen call `GET /api/v1/pos/terminals`. Each item: `{id, provider, name, terminalId, default}`. `default` is true when exactly one machine is active, so the till picks it without asking. Otherwise let the cashier choose. When other vendors' channels exist, the generic `GET /api/v1/pos/payment-channels` (bank module) lists Paytm machines together with them; this endpoint stays for Paytm-only screens.
2. Cashier presses "Card / UPI on machine": `POST /api/v1/pos/terminal-payments` with header `Idempotency-Key: <new uuid per attempt>` and body `{terminalId: <id from list>, amountPaise, reference: "<bill number or cart ref>"}` -> 201 payment.
3. Show "Waiting for customer on the machine" and poll `GET /api/v1/pos/terminal-payments/{id}` every `pollAfterSeconds` (from the payment itself) while `open` is true. Each GET may itself check Paytm (at most once per poll interval), so polling alone is enough; the server also polls in the background, and a payment survives an app crash: store the payment `id` locally and resume with the GET.
4. `status` SUCCESS: take the payment into the sale by adding a payment line `{mode: "CARD" or "UPI", amountPaise, terminalPaymentId: <id>}` (see 3). Use mode UPI when `payMethod` is `UPI`, otherwise CARD.
5. Terminal states: FAILED, CANCELLED, EXPIRED, VOIDED: tell the cashier and offer another way to pay.

### Payment object

```json
{ "id": "uuid", "terminalRowId": "uuid", "terminalId": "AB12CD34", "terminalName": "Counter 1", "amountPaise": 12500,
  "reference": "INV/1", "status": "PENDING", "open": true, "message": "Waiting for the customer to pay on the machine",
  "resultCode": "0009", "payMethod": null, "cardScheme": null, "maskedCard": null, "rrn": null, "authCode": null,
  "issuingBank": null, "upiVpa": null, "posSaleId": null, "canCancel": true, "canVoid": false, "pollAfterSeconds": 10,
  "createdAt": "...", "finishedAt": null }
```

| status | meaning | UI |
|---|---|---|
| REQUESTED | row written, amount being sent (rarely seen) | spinner |
| PENDING | machine has the amount, customer paying | "Customer is paying on the machine", Cancel button |
| UNKNOWN | the answer to the request was lost or unclear; the server is checking with Paytm | "Checking with the machine...", keep polling, do NOT start another payment on this machine |
| SUCCESS | paid. `payMethod` (CREDIT_CARD, DEBIT_CARD, UPI, EMI...), `cardScheme`, `maskedCard`, `rrn`, `authCode`, `issuingBank`, `upiVpa` (masked) filled | green tick, show last digits and approval code, enable "Use for bill" |
| FAILED | declined, failed, or Paytm never got it | show `message`, offer retry |
| CANCELLED | cashier asked to cancel and the machine reported the cancel | offer retry |
| EXPIRED | nobody paid within `waitSeconds` | "Not paid in time". Still watched for 15 minutes: a late payment flips it to SUCCESS, so keep the payment id reachable (e.g. a "check again" button) before taking another payment |
| VOIDED | paid and then voided the same day | cannot be used |

`canCancel` is true while `open`. `canVoid` is true for a SUCCESS payment taken today, not yet on a bill.

### Endpoints

`POST /api/v1/pos/terminal-payments` (201, or 200-ish replay: the same Idempotency-Key returns the same payment)

| code | HTTP | when / UI |
|---|---|---|
| `VALIDATION_FAILED` | 400 | missing Idempotency-Key, amount outside 1..100000000, unknown `terminalId`, reference over 50 chars (`details.fields`) |
| `TERMINAL_INACTIVE` | 422 | machine switched off in admin |
| `TERMINAL_BUSY` | 409 | the machine already has an open payment; `details.openPaymentId` lets the till resume it |
| `TERMINAL_REJECTED` | 422 | Paytm refused the request. `message` is the plain reason to show; `details.reason` is stable: `TERMINAL_INACTIVE` (machine off or offline), `TERMINAL_BUSY_AT_PAYTM` (another request is open on the machine), `BAD_CHECKSUM` (admin: wrong key/MID); `details.paytmCode`, `details.paymentId`. The payment is stored as FAILED and the machine is free again |
| `PAYTM_NOT_CONFIGURED` | 503 | settings missing / switched off: tell the cashier to ask the admin |
| `RATE_LIMITED` | 429 | more than 30 card payments started a minute by one user |

An unreadable or lost answer to the request is NOT an error: you get 201 with `status: "UNKNOWN"`. A request is sent exactly once; a lost answer is resolved by a status check, so a customer is never charged twice by a retry.

`GET /api/v1/pos/terminal-payments/{id}` returns the payment; 404 if unknown.

`POST /api/v1/pos/terminal-payments/{id}/cancel` - the cancel rule. **Paytm documents no call that cancels a sale already on the machine.** So the server never marks a payment CANCELLED on its own while a customer may still pay:
- if Paytm says the sale ended without payment (the cashier pressed Cancel on the machine): 200 with `status: CANCELLED`;
- if it is paid: 409 `ALREADY_PAID` (use the payment, or void it today);
- otherwise 409 `CANCEL_ON_MACHINE`: show "Press Cancel on the machine. This payment closes by itself once the machine reports it." Keep polling; it turns CANCELLED (because the cancel was asked) or EXPIRED;
- already finished payments: 200 with the current state.

`POST /api/v1/pos/terminal-payments/{id}/void` - same-day void of a SUCCESS payment that no bill used:
- 200 `VOIDED`;
- 409 `NOT_VOIDABLE` (not paid), 409 `PAYMENT_IN_USE` (a bill has it: return the bill instead);
- 422 `VOID_WINDOW_PASSED`: the day (India time) ended. Message: refund from the Paytm dashboard;
- 422 `VOID_REJECTED` with Paytm's reason in `message` (`details.reason` e.g. `VOID_ALREADY_REQUESTED`);
- 502 `VOID_UNKNOWN`: Paytm not reachable; check the machine before trying again. While unresolved the payment cannot be put on a bill.

### 3. Using the payment on a counter bill

`POST /api/v1/pos/sales` payment lines get one optional field:

```json
{ "mode": "CARD", "amountPaise": 12500, "reference": "optional", "terminalPaymentId": "uuid" }
```

Rules (checked inside the sale; the sale fails as a whole): status SUCCESS, not used by another bill, not being voided, amount equal to the line, line mode CARD (or UPI when the customer paid by UPI on the machine), only on CARD or UPI lines, not on an offline bill. If `reference` is blank the server fills `PAYTM RRN <rrn> AUTH <authCode>` (stored in `pos_payment.reference`; the full card details stay on the payment). Old payloads without the field behave exactly as before.

| code | HTTP | meaning |
|---|---|---|
| `TERMINAL_PAYMENT_NOT_PAID` | 422 | not SUCCESS yet (or voided) |
| `TERMINAL_PAYMENT_USED` | 409 | already on a bill |
| `TERMINAL_PAYMENT_AMOUNT` | 422 | line amount differs from the card amount |
| `TERMINAL_PAYMENT_MODE` | 422 | line mode does not fit how the customer paid |
| `VALIDATION_FAILED` | 400 | unknown id, wrong line mode, offline bill; `details.fields["payments[i].terminalPaymentId"]` |

Offline tills: a card machine payment needs the server, so card/UPI via the machine is online-only. If the network is down, use the old way (CARD line with a manual reference).

## 4. Bank and receipt details from Paytm (settlement)

The owner wants UPI received, payments, fees, UTR and payouts per transaction, like Razorpay. This module only parses and returns typed records; the bank module stores them (no settlement tables here).

- `PaytmSettlementReportParser.parse(csvText)` - the dashboard "Settlement Report" CSV (Transaction ID, Order ID, Transaction Date, Updated date, Transaction Type, Status, Amount, Commission, GST, Settled amount, Settled Date, UTR no., Split Flag, Payment Mode). Header case, spacing and BOM tolerant; rupee amounts become paise; dates ISO, dd-MM-yyyy, dd/MM/yyyy. Returns `{records, errors:[{line,message}]}`; a bad row never stops the file.
- `PaytmSettlementApiClient.sync(Api)` / `fetch(Api, from, to)` for `DETAIL` (`POST {secureBase}/merchant-settlement/SettlementDetail`), `TRANSACTION`, `ORDER`, `SUMMARY`. Off unless `paytm.settlement.enabled`; reads the last `paytm.settlement.syncDays` days. Untested; auth style, paths other than SettlementDetail, field names and the response shape are unconfirmed.
- Both produce `PaytmSettlementRecord`: provider `paytm`, source (CSV, API_*), transactionId, orderId, transactionType (ACQUIRING/REFUND/CHARGEBACK...), status, paymentMode (UPI/CARD/BALANCE/NETBANKING/OTHER), requestType (e.g. UPI_QR_CODE), rrn, maskedCard, cardNetwork, vpa (masked), posId, extSerialNo, amountPaise, settledAmountPaise, commissionPaise, gstPaise, payoutId, utr, transactionDate, settledDate, splitFlag, bankName/bankAccount (summary API; account masked) and `detail` (every unmapped field).

**Important for the UI and the owner:** UPI received on the shop's Paytm QR (the sticker, NOT through the machine) has no real-time per-payment API that we know of. Such payments appear only in these settlement records (API or CSV), after Paytm settles them. The till cannot confirm a QR sticker payment at the counter; only a machine payment (this guide) is confirmed instantly.

Glue for the bank module: call the parser on an uploaded file, or schedule `sync(Api.DETAIL)` and `sync(Api.SUMMARY)`; map `PaytmSettlementRecord` to its settlement item: match machine card/UPI sales by `rrn` (equals `terminal_payment.rrn`) or `orderId` (equals `terminal_payment.merchant_transaction_id`, the payment id without dashes), then amount and date; `utr`+`payoutId` identify the payout; `commissionPaise` and `gstPaise` are the fee and its GST.

## 5. Glue to the generic ports (for the integrator)

Everything Paytm-specific sits behind `com.vijay.mall.payment.paytm.domain.PaytmTerminalService` (`requestSale`, `status`, `cancelPending`, `voidSale`, `testConnection`). A thin adapter implementing the generic `CardTerminal` port should call `TerminalPaymentService` (package `payment.paytm.app`), not the HTTP client, because that class owns idempotency, the busy rule, polling and expiry:

| generic CardTerminal | Paytm class and method |
|---|---|
| list channels / terminals | `PaymentTerminalService.activeForTill()` (`provider`, `name`, `terminalId`, `default`) |
| start(terminal, amount, reference, idemKey) | `TerminalPaymentService.start(actor, idemKey, terminalRowId, amountPaise, reference)` |
| status(id) | `TerminalPaymentService.get(id)` |
| cancel(id) | `TerminalPaymentService.cancel(actor, id)` (rule above) |
| void(id) | `TerminalPaymentService.voidPayment(actor, id)` |
| attach to sale | `TerminalPaymentService.attach(...)` / `confirmedPayment(id)` (already wired into `PosService`) |
| test | `PaymentTerminalService.test(actor, id)` |

The channel entry for the till is `{provider: "paytm", name, terminalId, default}` plus our terminal row `id` used as `terminalId` in the start call. `payment_terminal.money_account_id` is the money account for receipts reports.

## 6. Dart-friendly sketches

```dart
class TerminalPayment {
  final String id, status, message;
  final bool open;
  final int amountPaise, pollAfterSeconds;
  final String? rrn, authCode, cardScheme, maskedCard, payMethod;
  TerminalPayment.fromJson(Map<String, dynamic> j)
      : id = j['id'], status = j['status'], message = j['message'] ?? '', open = j['open'],
        amountPaise = j['amountPaise'], pollAfterSeconds = j['pollAfterSeconds'],
        rrn = j['rrn'], authCode = j['authCode'], cardScheme = j['cardScheme'],
        maskedCard = j['maskedCard'], payMethod = j['payMethod'];
  bool get paid => status == 'SUCCESS';
  String get billMode => payMethod == 'UPI' ? 'UPI' : 'CARD';
}

Future<TerminalPayment> takeCardPayment(Api api, String terminalId, int paise, String ref) async {
  final key = const Uuid().v4(); // keep it with the cart so a retry after a crash reuses it
  var p = TerminalPayment.fromJson(await api.post('/pos/terminal-payments',
      headers: {'Idempotency-Key': key}, body: {'terminalId': terminalId, 'amountPaise': paise, 'reference': ref}));
  await store.savePendingTerminalPayment(p.id);
  while (p.open) {
    await Future.delayed(Duration(seconds: p.pollAfterSeconds));
    p = TerminalPayment.fromJson(await api.get('/pos/terminal-payments/${p.id}'));
  }
  return p;
}
```

## 7. Implementation checklist

- [ ] Show the `PAYTM` group on the config screen (already generic), with a Test button per machine.
- [ ] Machines screen: list, add, rename, switch off, test.
- [ ] Till: machine picker (skip when `default`), amount, Idempotency-Key per attempt, polling by `pollAfterSeconds`, persist the payment id for crash recovery.
- [ ] Handle all statuses; never start a second payment on a machine while one is PENDING/UNKNOWN (409 `TERMINAL_BUSY` gives `openPaymentId`).
- [ ] Cancel button: show the "press Cancel on the machine" message for `CANCEL_ON_MACHINE`.
- [ ] Attach with `terminalPaymentId` on the CARD/UPI payment line; show RRN and approval code on the receipt screen.
- [ ] Void button when `canVoid`; after the day, show the dashboard-refund message.
- [ ] Offline: fall back to a manual reference CARD/UPI line.

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
10. Whether Paytm checks the response `head.checksum` for status/void (we do not verify response signatures).
