# 30. Shop-counter POS, shifts, credit (udhar), offline sync, purchasing, stock takes, reports, audit log

Audience: Flutter counter app (cashier and admin screens), web admin, and the LLM assistants that implement them. Read `00-overview.md` first for authentication, the error body, `Idempotency-Key`, pagination and money rules. Everything here is derived from the backend code; where the code leaves something open it is marked **(unsure)**.

Conventions used below (not repeated per endpoint):
- Base path `/api/v1`. Every call needs `Authorization: Bearer <accessToken>` unless stated.
- Money = integer **paise**. Dates/times = ISO-8601 UTC (`2026-11-08T17:52:42Z`); "date" query params and report days are plain `yyyy-MM-dd` and are interpreted in **shop time (Asia/Kolkata)**.
- Error body: `{ "code", "message", "traceId", "details" }`. Validation errors from `@Valid` have `details.fields = { "<field>": "<message>" }`; service-level validation errors also use `VALIDATION_FAILED` (400) with `details.fields = { "<field>": "<message>" }`. A missing `Idempotency-Key` header on endpoints that require it gives 400 `VALIDATION_FAILED` with message "Bad request".
- Every endpoint can also answer 401 `UNAUTHENTICATED` (no/expired token), 403 `FORBIDDEN` (wrong role, or blocked staff account) and 429 `RATE_LIMITED` (300 calls/minute per client IP; `details.retryAfterSeconds`). These are omitted from the per-endpoint error tables.
- Page object: `{ "items": [...], "page": 0, "size": 20, "total": 123 }`; `size` is clamped to 1..50 everywhere it is accepted.
- Who may call (verified in `SecurityConfig`): `/api/v1/pos/**` = CASHIER and ADMIN. `/api/v1/admin/**` = ADMIN only (the only exception, `/api/v1/admin/orders**`, is CASHIER too but belongs to another document). `/api/v1/credit**` = any signed-in user, the data returned is the caller's own (customer app).

## Table of contents

Counter (cashier + admin, `/pos/**`)
1. `GET /api/v1/pos/shop` - shop details for the till (name, GSTIN, UPI id, invoice prefix, weighed-label settings)
2. `GET /api/v1/pos/barcode/{code}` - resolve a scanned pack barcode or weighed scale label to a product, quantity and price
3. `GET /api/v1/pos/products` - search products by name (manual add, also used to build the offline product copy)
4. `POST /api/v1/pos/quote` - price a basket (GST split, coupon, coins, round-off); changes nothing
5. `POST /api/v1/pos/sales` - complete a bill with payments (idempotent)
6. `GET /api/v1/pos/sales` - find bills by invoice number, customer mobile or day
7. `GET /api/v1/pos/sales/{id}` - one bill
8. `GET /api/v1/pos/sales/{id}/receipt` - printable text receipt (32 or 48 columns)
9. `POST /api/v1/pos/sales/{id}/returns` - return items from a bill, credit note and refund (idempotent)
10. `GET /api/v1/pos/sales/{id}/returns` - returns already made on a bill
11. `GET /api/v1/pos/upi-link` - UPI deep link to render as a QR
12. `POST /api/v1/pos/holds` - park a basket
13. `GET /api/v1/pos/holds` - list the parked baskets (at most 100 can exist)
14. `GET /api/v1/pos/holds/{id}` - one parked basket
15. `DELETE /api/v1/pos/holds/{id}` - discard a parked basket
16. `GET /api/v1/pos/reports/day` - cash-up report for a day

Shifts (cash drawer)
17. `POST /api/v1/pos/shifts/open`
18. `GET /api/v1/pos/shifts/current`
19. `POST /api/v1/pos/shifts/close`
20. `GET /api/v1/admin/pos/shifts` - admin list of all shifts

Credit / udhar
21. `GET /api/v1/pos/credit/account` - look up a customer's credit by mobile
22. `GET /api/v1/pos/credit/ledger` - a customer's credit ledger by mobile
23. `POST /api/v1/pos/credit/payments` - take a repayment (idempotent)
24. `GET /api/v1/credit` - customer sees own credit account
25. `GET /api/v1/credit/ledger` - customer sees own credit ledger
26. `PUT /api/v1/admin/credit/limit` - approve credit for a mobile number (creates the customer)
27. `GET /api/v1/admin/credit/accounts/{customerId}`
28. `GET /api/v1/admin/credit/accounts/{customerId}/ledger`
29. `PUT /api/v1/admin/credit/accounts/{customerId}/limit`
30. `POST /api/v1/admin/credit/accounts/{customerId}/adjust` - correction / write-off

Approvals, offline sync
31. `PUT /api/v1/admin/me/approval-pin` - admin sets the manager PIN
32. `GET /api/v1/pos/sync/status` - server clock, catalog version, offline limits
33. `POST /api/v1/pos/sync/sales` - upload bills rung up offline

Admin counter set-up
34. `GET /api/v1/admin/shop-settings`
35. `PUT /api/v1/admin/shop-settings`
36. `GET /api/v1/admin/products/{id}/pos` - counter attributes of a product
37. `PUT /api/v1/admin/products/{id}/pos` - set sold-by, PLU, GST, HSN, barcodes
38. `GET /api/v1/admin/staff` - cashier roster
39. `POST /api/v1/admin/staff` - create a cashier
40. `PATCH /api/v1/admin/staff/{id}` - block / unblock a cashier

Purchasing and stock
41. `POST /api/v1/admin/purchases` - receive a supplier delivery (GRN, idempotent)
42. `GET /api/v1/admin/purchases`
43. `GET /api/v1/admin/purchases/{id}`
44. `POST /api/v1/admin/stock-takes` - set stock to counted figures

Reports and audit
45. `GET /api/v1/admin/dashboard`
46. `GET /api/v1/admin/reports/summary`
47. `GET /api/v1/admin/reports/credit`
48. `GET /api/v1/admin/credit/accounts` - debtors list
49. `GET /api/v1/admin/reports/counter-controls`
50. `GET /api/v1/admin/stock/low` - low-stock list
51. `GET /api/v1/admin/audit-log`

Cross-references (documented in the catalog document, not here): `POST /api/v1/admin/products/{id}/stock` (manual +/- stock with reason) and `GET /api/v1/admin/stock-movements` (stock history, includes reasons `POS_SALE`, `POS_RETURN`, `PURCHASE`, `STOCK_TAKE`). `GET/PUT /api/v1/admin/shop-profile` and the public shop endpoints are also documented elsewhere.

---

## Business rules in one place

**Units.** A product is sold `UNIT` (quantity = pieces) or `WEIGHT` (quantity = **grams**; the product price is paise **per 1000 g**). Weighed line amount = `round_half_up(pricePaise * grams / 1000)`. Allowed quantity per line: 1..9999 pieces, 1..100000 grams (100 kg). Stock for weighed products is also held in grams (a sale takes `grams` from stock), so purchases and stock takes of weighed products are in grams **(inferred from how sales take stock)**.

**GST.** Prices are GST-inclusive. Per line, after discounts: `taxable = round_half_up(net * 100 / (100 + gstPercent))`, `tax = net - taxable`, `CGST = floor(tax / 2)`, `SGST = tax - CGST` (SGST can be 1 paise more than CGST). The bill's `taxablePaise`, `cgstPaise`, `sgstPaise` are the sums over lines. Allowed `gstPercent` values: 0, 3, 5, 12, 18, 28. Intra-state only (no IGST).

**Discount order.** Coupon first (a category coupon lowers only the lines it covers, and their GST), then coins, then a manual discount typed at the till. Coin and manual discounts are spread over the remaining line amounts proportionally (largest-remainder, so paise add up exactly). A line never goes below 0.

**Coins.** `coinMode` is `NONE`, `ALL` or `PERCENT` (with `percent` 0..100, a percent of the allowed maximum). Needs `customerMobile`. A coupon that does not work with coins blocks coin use and adds a warning. Coins are earned only when `customerMobile` is present (`coinsToEarn`).

**Round-off.** When the admin sets config `gst.roundOff = NEAREST_RUPEE`, the payable total is rounded to the nearest rupee (50 paise rounds up). `roundOffPaise` = paise added (can be negative); `totalPaise` already includes it. With `NONE` (default) `roundOffPaise` is 0. Payments must add up to `totalPaise` including round-off. Round-off is computed after the manual discount; `POST /pos/quote` has no manual discount input, so after the cashier enters a manual discount the UI must compute the new total itself: `t = quote.totalPaise - quote.roundOffPaise - manualDiscountPaise`, then if rounding is on `total = floor((t + 50) / 100) * 100`, else `total = t`. The `POST /pos/sales` response is authoritative.

**Invoice numbers.** `<prefix>/<financialYear>/<6-digit running number>`, e.g. `VSM/2026-27/000042`. Financial year runs 1 April to 31 March and is taken from the server time when the bill is recorded (not `soldAt`). Credit notes (returns): `<prefix>-CN/2026-27/000001`. Credit repayment receipts: `<prefix>-CR/2026-27/000001`. The prefix is the shop's `invoicePrefix` (2-6 capital letters/digits, default `VSM`). Numbers are assigned by the server only; the till never invents them (an offline till shows its own temporary receipt number and replaces it with the server's `invoiceNo` after sync).

**Barcodes.**
- Pack barcode: any code of 4-32 letters/digits/dashes registered on a product (normally EAN-13). Exact match. Maps to quantity 1 piece. A `WEIGHT` product cannot be scanned by a pack barcode (400 "weighed item: enter the weight").
- Weighed scale label: a 13-digit all-numeric code that starts with the shop's `weighedPrefix` (1 or 2 digits, default `2`), with a valid EAN-13 check digit. Layout: `[prefix][PLU][5-digit value][check digit]` where the PLU occupies the digits from after the prefix up to and including position 7 (1-based, i.e. characters 2..7 for a 1-digit prefix = 6 digits; 5 digits for a 2-digit prefix) and the value is characters 8..12. Leading zeros of the PLU are ignored when matching the product's PLU. The value means **grams** when the shop's `weighedMode` is `WEIGHT_GRAMS`, or **price in paise** when `PRICE_PAISE` (the server converts price to grams using the product price). A 13-digit code starting with the prefix but with a bad check digit is 400 "Invalid check digit on weighed label".

**Payment modes.** On a bill: `CASH`, `UPI`, `CARD` (upper-case is the canonical form; a lower-case or mixed-case mode such as `cash` or `Upi` is accepted and treated as `CASH` / `UPI`) and at most one `CREDIT` line (pay later / udhar). On refunds and credit repayments only `CASH`, `UPI`, `CARD`. Every payment amount > 0. The sum of a bill's payments must equal the payable total exactly (zero-value bill: send no payments). `reference` is optional free text (UPI transaction id, card slip no.). Cash handed over: `cashTenderedPaise` must be >= the CASH payment amount and a CASH line must exist; `changePaise = cashTendered - cashPayment`. A bill is classified `paymentType`: `NONE`, `CASH`/`UPI`/`CARD` (one mode), `SPLIT` (several modes, no credit), `CREDIT` (all credit), `HYBRID` (credit plus other modes).

**Manual discount and manager PIN.** `manualDiscount = { amountPaise, reason, approverPin }`. Needs a reason (1-100 chars after trimming), amount <= what the bill still costs after coupon and coins. An ADMIN may give any amount without a PIN (recorded as approved by themself). A CASHIER may give it alone only if `amount * 100 <= subtotal * cashierDiscountPercent` (admin config `pos.cashierDiscountPercent`, 0-100, default 0 which means every manual discount needs a PIN). Above that the cashier needs `approverPin`: the 4-8 digit PIN of any admin (set by `PUT /admin/me/approval-pin`). Wrong or missing PIN gives 422 `DISCOUNT_NEEDS_APPROVAL`. PIN guesses are rate limited to 5 per 10 minutes per cashier (429 `RATE_LIMITED`).

**Cashier return window.** A CASHIER may return items only from bills no older than `pos.cashierReturnDays` (default 7, 0 = no limit; admin config). Older bills answer 422 `RETURN_NEEDS_ADMIN` with `details.days`; an ADMIN is never limited.

**Shifts.** A cashier opens a shift with the float in the drawer and closes it with the cash counted. If admin config `pos.requireOpenShift` is on, `POST /pos/sales` (and offline upload) is refused with 422 `SHIFT_NOT_OPEN` until the cashier has an open shift. Returns are not gated by shifts.

**Credit (udhar).** Only for counter bills, never online orders. A customer may owe at most their `limitPaise` (set by admin; 0 = credit off). A CREDIT payment needs `customerMobile` and an approved limit. Returns from a credit bill first cancel what the customer still owes (up to the credit that bill put on the account, minus what earlier returns already cancelled, capped by the current balance); only the remainder is paid out as cash/UPI/card.

**Idempotency.** `POST /pos/sales`, `POST /pos/sales/{id}/returns`, `POST /pos/credit/payments` and `POST /admin/purchases` require `Idempotency-Key`. The first call stores the result under the key; any later call with the same key returns **that stored result unchanged (same HTTP status as a fresh call, e.g. 201)** and does no work, even if the body differs. Two simultaneous calls with the same key produce one record. Keys for sales, returns, repayments and purchases are separate namespaces. Use a fresh UUID per user action and keep it until the action is confirmed.

---

## Counter day flow

1. **Sign in** as CASHIER (e-mail OTP; see `00-overview.md`). Load `GET /pos/shop` (shop header, UPI id, weighed settings) and `GET /pos/sync/status` (catalog version, offline limits).
2. **Open shift** (`POST /pos/shifts/open` with the opening float). On app start call `GET /pos/shifts/current`; 404 means no open shift, show the open-shift screen. If the shop requires shifts, billing is blocked until this is done.
3. **Scan / search.** Each scan calls `GET /pos/barcode/{code}`: add `qty` of `product` to the basket (for weighed items `qty` is grams). If scan answers 404, offer name search `GET /pos/products?q=`. For loose items typed by weight, send grams as `qty`.
4. **Customer (optional).** Enter the customer's mobile to use a coupon, coins, to earn coins or to sell on credit. For credit, first call `GET /pos/credit/account?mobile=` to show available credit.
5. **Quote.** `POST /pos/quote` every time the basket changes: gives per-line GST, discounts, total, round-off, coins to earn, warnings. Show `warnings` to the cashier.
6. **Manual discount (optional).** Cashier types amount and reason; if over the cashier limit the UI prompts for a manager PIN (or the manager signs in as admin). The server tells you with 422 `DISCOUNT_NEEDS_APPROVAL`; prompt for the PIN and resend with the **same** Idempotency-Key (nothing was stored on failure).
7. **Payment.** Build `payments` (split allowed) that sum exactly to the payable total; for UPI show a QR from `GET /pos/upi-link`. `POST /pos/sales` with a fresh `Idempotency-Key`. On timeout, retry with the same key.
8. **Receipt.** `GET /pos/sales/{id}/receipt?width=32` (58 mm) or `48` (80 mm), send the `text` to the thermal printer in a monospace font. If the sale was a held basket, `DELETE /pos/holds/{id}` (the server does not do this automatically).
9. **Returns.** Find the bill (`GET /pos/sales?invoiceNo=` or `mobile=`), show `items[].returnableQty`, `POST /pos/sales/{id}/returns`. Print the credit note number from the response.
10. **Holds.** `POST /pos/holds` to park a basket, `GET /pos/holds` to list, `GET /pos/holds/{id}` to resume (then re-quote), `DELETE` to discard.
11. **Credit repayments.** A customer paying their udhar: `GET /pos/credit/account?mobile=`, then `POST /pos/credit/payments`.
12. **Cash-up.** `GET /pos/reports/day` shows today's bills, payments by mode, refunds and `cashExpectedPaise`. The cashier counts the drawer.
13. **Close shift.** `POST /pos/shifts/close` with `countedCashPaise`. If it differs from the expected cash, a written `note` is required (422 `VARIANCE_NEEDS_NOTE`).

## Offline mode

> **Read the addendum at the end of this file first**: new apps send the bill **as rung** (`rung` snapshot of the prices the till charged) and the server records it as it was, listing differences as exceptions for the manager. The re-pricing behaviour described in steps 4-6 below is the **legacy path**, used only for bills sent without `rung` (old app versions).

The backend supports a till that keeps selling when the internet is down. The flow is entirely client-driven:

1. **While online**, keep a local copy of the product list. There is **no bulk catalog endpoint**: page through `GET /pos/products?page=N&size=50` (50 is the maximum) until `items` is empty or `page*size >= total`, and store each product with its barcodes, `plu`, `soldBy`, `pricePaise`, `gstPercent`, `qtyOnHand`. Also store `GET /pos/shop` (weighed prefix/mode, UPI id, footer). Save `catalogVersion` from `GET /pos/sync/status`.
2. **Detect stale copy.** Poll `GET /pos/sync/status` (also on app start and after reconnect). If its `catalogVersion` is newer than the saved one, download the list again. `serverTime` lets you detect device clock error: if the device clock differs from `serverTime` by more than a few minutes, warn the cashier (the server rejects `soldAt` that is more than 5 minutes in the future or more than `maxOfflineDays` (7) in the past).
3. **While offline**, price the basket locally (same rules as above: GST-inclusive prices, weighed lines, rounding), take payment, print a temporary receipt with a local number, and store for each bill: a fresh UUID `idempotencyKey` (generate once, never regenerate), `soldAt` (the moment of sale, UTC ISO), `cart`, `payments`, `cashTenderedPaise`, `manualDiscount`.
4. **When back online** send queued bills oldest first, in batches of at most 50, to `POST /pos/sync/sales`. The call returns HTTP 200 with one result per bill in the same order. Per result `status`:
   - `CREATED`: recorded; replace the temporary receipt number with `invoiceNo`, remember `saleId`.
   - `DUPLICATE`: this key was already recorded earlier (e.g. a previous upload half-arrived); treat as success, use `saleId`/`invoiceNo`.
   - `REJECTED`: not recorded (no stock/coins/coupon/credit side effects). Look at `errorCode`/`message`. `INTERNAL_ERROR` is transient: retry with the same key. Anything else needs a person: show the bill in a "needs attention" list for the manager.
   A rejected bill never blocks the others. Keep a bill in the queue until it is `CREATED`/`DUPLICATE`.
5. **What the server does differently for offline bills.** (a) Stock is taken with "take what is left": a bill never fails for short stock; if the shelf had less than sold, the bill is recorded and stock stops at 0, and the shortfall is written to the audit log as action `OFFLINE_OVERSOLD` (entity `pos_sale`) and the server log. The upload response does **not** list shortfalls; the admin finds them in `GET /admin/audit-log?action=OFFLINE_OVERSOLD`. (b) The bill's time (`createdAt`, used by reports) is `soldAt`. (c) The bill is attributed to the user who uploads it, not necessarily the cashier who rang it. (d) The invoice number is assigned at upload time from the server's financial year.
6. **Things that can make an offline bill REJECTED** (design the offline UI to avoid them):
   - **Prices are re-read from the server at upload time.** The server prices the cart at *current* prices and requires `payments` to add up exactly to its own total. If a price, GST rate or round-off setting changed while the till was offline, the bill is rejected with `VALIDATION_FAILED` ("payments add up to X but the bill is Y"). Manager must re-enter it as a new bill (a corrected bill may reuse the same key because nothing was stored; keep the original key to stay idempotent).
   - Coupons (`COUPON_INVALID`), coins (`VALIDATION_FAILED` "not enough coins"), and credit (`CREDIT_NOT_ALLOWED`, `CREDIT_LIMIT_EXCEEDED`) are checked against live data at upload. Recommended: disable coupons, coins and credit while offline.
   - `soldAt`: `VALIDATION_FAILED` with field `soldAt` if older than 7 days or in the future.
   - `SHIFT_NOT_OPEN` if the shop requires shifts and the uploading cashier has no open shift at upload time. **Not applied to offline bills:** an offline bill was rung up in the till's own shift, so the uploader does not need an open shift at upload time (online `POST /pos/sales` still does).
   - `DISCOUNT_NEEDS_APPROVAL` if a cashier's manual discount is over the limit and no valid PIN was supplied (the PIN is verified at upload; store `approverPin` with the bill only if your security policy allows it).
7. **Shifts and cash.** Expected drawer cash counts bills by their stored time (`soldAt`) against the shift's open time, so bills uploaded long after the sale and whose `soldAt` is before the current shift opened do not appear in the current shift's expected cash. Upload the queue **before** closing the shift.
8. **Returns, holds, credit repayments and shift calls are online-only** (no offline endpoint).

---

## Reports: what each answers

| Endpoint | Question it answers |
|---|---|
| `GET /pos/reports/day` | "Cash-up": for one day (and optionally one cashier), how many bills, how much sold/refunded, how it was paid per mode, how much cash should be in the drawer, credit given/returned/recovered. Cashier sees only own bills. |
| `GET /admin/dashboard` | Home screen numbers: today's online sales/orders, new (PLACED) orders, coins issued today, last 7 days of online sales, 10 emptiest low-stock items, today's counter sales/bills/refunds. Cached ~30 s. |
| `GET /admin/reports/summary` | Sales over a period (week/month/quarter/custom): online + counter totals, per-day series, top 10 products, counter refunds, coins issued, credit given/recovered and outstanding. Cached ~1 min. |
| `GET /admin/reports/credit` | Credit health: outstanding total, customers owing, credit given/returned/recovered/written-off/added in the period, recovery by mode, bills by payment type, per-day credit, top 20 debtors. |
| `GET /admin/credit/accounts` | Paged list of customers with credit accounts, biggest balance first (collections list). |
| `GET /admin/reports/counter-controls` | Loss prevention: per cashier sales, manual discounts (count, paise, % of sales), returns (count, paise, % of sales) and closed-shift cash variance (negative = drawer short). |
| `GET /admin/stock/low` | Reorder list: active products at or below their own stock alert level, emptiest first. |
| `GET /admin/audit-log` | Who changed what and when (sales, returns, shift closes, credit changes, purchases, stock takes, settings, etc). |

Period rules shared by the three period reports (`summary`, `credit`, `counter-controls`): query `period` = `week` (7 days, default), `month` (30) or `quarter` (90), ending **today** in shop time; **or** explicit `from` (and optional `to`, default today) as `yyyy-MM-dd`, both days included. If `from` is given it wins and `period` is ignored. If only `to` is given (no `from`) it is **ignored** and the period range ending today is used. Invalid `period` gives 400 `VALIDATION_FAILED` (field `period`, "must be week, month or quarter"); `to` before `from` gives 400 (field `to`); a range over 366 days gives 400 (field `to`, "range is limited to 366 days").

---

# Endpoints

## Counter

### GET /api/v1/pos/shop
- **Who**: both (cashier and admin; `/pos/**`).
- **Purpose / when the UI calls it**: when the till starts and after settings change, to print the receipt header locally, build UPI QRs and decode weighed labels offline.
- **Request**: no params.
- **Response**: 200.
```json
{ "shopName": "Vijay Super Mall", "address": "Station Road, Surat", "phone": "9876543210",
  "gstin": "24ABCDE1234F1Z5", "stateCode": "24", "upiId": "vijaymall@okbank", "invoicePrefix": "VSM",
  "receiptFooter": "Thank you! Visit again.", "weighedPrefix": "2", "weighedMode": "WEIGHT_GRAMS",
  "promise": "Fresh every day", "details": "About the shop text" }
```
| field | type | notes |
|---|---|---|
| shopName, address, phone | string | printed on bill |
| gstin | string | 15-char GSTIN or empty |
| stateCode | string | 2-digit GST state code |
| upiId | string | empty = UPI QR unavailable |
| invoicePrefix | string | 2-6 capital letters/digits |
| receiptFooter | string | printed at bottom |
| weighedPrefix | string | 1-2 digits; scale labels start with it |
| weighedMode | enum | `WEIGHT_GRAMS` or `PRICE_PAISE`: what the 5-digit value of a scale label means |
| promise, details | string | customer-facing texts (may be empty) |
- **Errors**: none specific.
- **UI notes**: cache locally; refresh on start and on reconnect.

### GET /api/v1/pos/barcode/{code}
- **Who**: both.
- **Purpose / when the UI calls it**: each barcode scan at the counter; resolves the code to a product, the quantity the code implies and the price of that quantity.
- **Request**: path `code` (string): the scanned digits/characters. No body.
- **Response**: 200.
```json
{ "product": { "productId": "uuid", "name": "Basmati Rice", "nameGu": "બાસમતી ચોખા", "pricePaise": 12000, "mrpPaise": 13000,
    "soldBy": "WEIGHT", "gstPercent": 5, "hsn": "1006", "plu": "123", "posOnly": true, "qtyOnHand": 25000,
    "imageUrl": "/img/product/abc_400.jpg", "coinRedeemable": true, "earnsCoins": true, "barcodes": ["8901234567890"] },
  "qty": 750, "linePaise": 9000 }
```
| field | type | meaning |
|---|---|---|
| product.productId | uuid | id to put in the cart |
| product.name / nameGu | string | English / Gujarati name (nameGu may be null/empty) |
| product.pricePaise | long | per piece, or per 1000 g when `soldBy=WEIGHT` |
| product.mrpPaise | long | printed MRP |
| product.soldBy | `UNIT`/`WEIGHT` | |
| product.gstPercent / hsn | int / string | GST rate and HSN |
| product.plu | string or null | item code for scale labels |
| product.posOnly | bool | counter-only (all weighed items are) |
| product.qtyOnHand | int | stock; grams for weighed items |
| product.imageUrl | string or null | prefix with server origin |
| product.coinRedeemable / earnsCoins | bool | coin rules |
| product.barcodes | string[] | registered codes |
| qty | int | 1 for a pack barcode; grams for a weighed label |
| linePaise | long | price of `qty` |
- **Errors**:

| status | code | when |
|---|---|---|
| 400 | VALIDATION_FAILED (field `code`) | "Invalid check digit on weighed label"; "weighed item: enter the weight" (pack barcode of a weighed product); "this item code is not a weighed item"; "weight on the label is out of range" (0 or over 100000 g) |
| 404 | NOT_FOUND | "Barcode not found", "Item code not found", or product inactive ("Product not found"): tell cashier to search by name |
- **UI notes**: scanning does not check or reserve stock; compare `qtyOnHand` yourself if you want to warn. Add the same product twice by scanning twice (the server merges duplicate cart lines by summing `qty`).

### GET /api/v1/pos/products
- **Who**: both.
- **Purpose / when the UI calls it**: manual product search at the counter (type a name), and paging through all products to build the offline copy.
- **Request**: query `q` (string, optional; case-insensitive "contains" match on English or Gujarati name, and also an exact barcode or item code (PLU): a product whose barcode or PLU equals `q` is listed first; at most 100 characters, else 400 `q`), `page` (int, default 0), `size` (int, default 20, clamped 1..50). Sorted by English name. Only active products; counter-only items included.
- **Response**: 200 page object whose `items` are the `product` objects shown above (`PosProduct`).
- **Errors**: none specific.
- **UI notes**: debounce typing.

### POST /api/v1/pos/quote
- **Who**: both.
- **Purpose / when the UI calls it**: every time the basket or customer changes, to show the live bill. Changes nothing (no stock, coins, coupon use or customers created).
- **Request**: no Idempotency-Key. Body:
```json
{ "items": [ { "productId": "uuid", "qty": 2 }, { "productId": "uuid", "qty": 750 } ],
  "customerMobile": "9876543210", "couponCode": "DIWALI10", "coinMode": "PERCENT", "percent": 50 }
```
| field | type | required | rules |
|---|---|---|---|
| items | array | yes | not empty (`@NotEmpty`); each `productId` uuid required, `qty` int > 0; duplicates of a product are summed; max 9999 pieces or 100000 grams per product; product must exist and be active |
| items[].qty | int | yes | pieces, or **grams** for `WEIGHT` products |
| customerMobile | string | no | Indian mobile; spaces/dashes and a `+91` prefix are accepted; normalised form must match `[6-9]\d{9}`. Required if a coupon or coins are used |
| couponCode | string | no | coupon code; invalid coupons are an error, not a warning |
| coinMode | `NONE`/`ALL`/`PERCENT` | no | default no coins; needs customerMobile |
| percent | int | no | 0..100 for `PERCENT` |
- **Response**: 200.
```json
{ "lines": [ { "productId": "uuid", "name": "Basmati Rice", "soldBy": "WEIGHT", "qty": 750, "unitPricePaise": 12000,
      "linePaise": 9000, "discountPaise": 450, "netPaise": 8550, "gstPercent": 5 } ],
  "subtotalPaise": 9000, "couponCode": "DIWALI10", "couponDiscountPaise": 450, "coinsUsed": 0, "coinDiscountPaise": 0,
  "totalPaise": 8600, "taxablePaise": 8143, "cgstPaise": 204, "sgstPaise": 203, "coinsToEarn": 85,
  "customerCoinBalance": 120, "warnings": [], "roundOffPaise": 50 }
```
| field | type | meaning |
|---|---|---|
| lines[] | | one per distinct product, in the order first added |
| lines[].unitPricePaise | long | price per piece / per 1000 g |
| lines[].linePaise | long | gross amount for `qty` |
| lines[].discountPaise | long | coupon + coins share on this line |
| lines[].netPaise | long | `linePaise - discountPaise`; GST-inclusive |
| subtotalPaise | long | sum of linePaise |
| couponCode / couponDiscountPaise | string or null / long | applied coupon |
| coinsUsed / coinDiscountPaise | int / long | coins redeemed and their rupee value |
| totalPaise | long | amount the customer pays **including round-off** |
| taxablePaise, cgstPaise, sgstPaise | long | GST split of the bill (excluding round-off) |
| coinsToEarn | long | coins the customer will earn (0 without a mobile) |
| customerCoinBalance | int | current coin balance (0 for unknown mobile) |
| warnings | string[] | e.g. "Coins cannot be used together with coupon X", "Some items cannot be paid with coins" |
| roundOffPaise | long | rounding added (negative = taken off); 0 when rounding is off |
- **Errors**:

| status | code | when |
|---|---|---|
| 400 | VALIDATION_FAILED | empty basket (`items`); `qty` <= 0 (field `qty`); quantity too large; product missing/inactive (field `productId`); bad mobile (`customerMobile`); coupon or coins without a mobile (`customerMobile`) |
| 422 | COUPON_INVALID | `details.reason` one of `NOT_FOUND`, `EXPIRED`, `MIN_ORDER`, `CATEGORY`, `LIMIT_REACHED`, `FIRST_ORDER_ONLY` |
- **UI notes**: no manual discount input here; see the round-off formula above. Show `warnings` as hints. Quote does not check stock.

### POST /api/v1/pos/sales
- **Who**: both. Admin sales skip the PIN for manual discounts; cashier sales follow the discount limit.
- **Purpose / when the UI calls it**: when the cashier confirms payment: records the bill, takes stock, redeems/earns coins, consumes the coupon, puts credit on the customer's account, assigns the invoice number.
- **Request**: header `Idempotency-Key: <uuid>` **mandatory** (missing = 400). Replay with the same key returns the original bill (HTTP 201, no second bill, no second stock take). Body:
```json
{ "cart": { "items": [ { "productId": "uuid", "qty": 2 } ], "customerMobile": "9876543210", "couponCode": null,
            "coinMode": "NONE", "percent": null },
  "payments": [ { "mode": "CASH", "amountPaise": 5000, "reference": null }, { "mode": "UPI", "amountPaise": 3600, "reference": "UTR123" } ],
  "cashTenderedPaise": 5000,
  "manualDiscount": { "amountPaise": 200, "reason": "Damaged pack", "approverPin": "4821" } }
```
| field | type | required | rules |
|---|---|---|---|
| cart | object | yes | same as quote body (`items` not empty etc.) |
| payments | array | yes in practice | each `mode` in `CASH`,`UPI`,`CARD`, or one `CREDIT`; `amountPaise` > 0; sum must equal payable total exactly; empty only for a zero-value bill |
| payments[].reference | string | no | free text, no validation |
| cashTenderedPaise | long | no | only with a CASH payment; >= CASH amount; change = tendered - CASH amount |
| manualDiscount | object | no | `amountPaise` >= 0 (0 = ignored), `reason` 1..100 chars, `approverPin` needed for a cashier above the limit |
- **Response**: 201 with the stored bill (`SaleRecord`):
```json
{ "id": "uuid", "invoiceNo": "VSM/2026-27/000042", "cashierId": "uuid", "customerId": "uuid", "customerMobile": "9876543210",
  "subtotalPaise": 9000, "couponCode": null, "couponDiscountPaise": 0, "coinsUsed": 0, "coinDiscountPaise": 0,
  "totalPaise": 8600, "taxablePaise": 8190, "cgstPaise": 205, "sgstPaise": 205, "coinsEarned": 86,
  "tenderedPaise": 5000, "changePaise": 0, "createdAt": "2026-11-08T12:00:00Z",
  "items": [ { "id": "uuid", "productId": "uuid", "name": "Basmati Rice", "soldBy": "WEIGHT", "qty": 750, "unitPricePaise": 12000,
      "linePaise": 9000, "discountPaise": 400, "netPaise": 8600, "gstPercent": 5, "hsn": "1006",
      "taxablePaise": 8190, "cgstPaise": 205, "sgstPaise": 205, "returnedQty": 0 } ],
  "payments": [ { "mode": "CASH", "amountPaise": 5000, "reference": null }, { "mode": "UPI", "amountPaise": 3600, "reference": "UTR123" } ],
  "paymentType": "SPLIT", "manualDiscountPaise": 200, "manualDiscountReason": "Damaged pack",
  "manualDiscountApprover": "uuid-of-admin-or-null", "roundOffPaise": 0 }
```
Fields: `customerId`/`customerMobile` null for walk-in; `tenderedPaise` null when not given; `coinsEarned` int; `items[].id` is the **sale item id to use in returns**; `items[].returnedQty` how much has been returned so far (the response of a *later* GET shows updates; `returnableQty` is `qty - returnedQty`, computed by the client); `manualDiscountApprover` null when the cashier was within the limit, the admin's id otherwise (for an admin sale, the admin's own id); `createdAt` = time of sale. The rest are as in the quote.
Use `payments` with `mode == "CREDIT"` to find the credit amount.
- **Errors**:

| status | code | when |
|---|---|---|
| 400 | VALIDATION_FAILED | missing key header; empty `items`; bad qty/product/mobile; coupon/coins without mobile; `payments`: wrong sum ("payments add up to X but the bill is Y"), unknown mode, amount <= 0, more than one CREDIT, "cash tendered given but no cash payment", "cash tendered is less than the cash payment", payments sent for a zero bill; `manualDiscount`: negative, missing/too long reason, more than the bill ("is more than the bill"); `customerMobile` "customer mobile is needed to sell on credit"; "not enough coins" |
| 400 | VALIDATION_FAILED (field `soldAt`) | only for offline upload path |
| 409 | OUT_OF_STOCK | `details.items = [{ "productId", "available" }]`. Show which product is short; remove or reduce and retry with a **new** key only if the basket changes (same key is fine if nothing was stored; since nothing is stored on failure either is safe) |
| 422 | DISCOUNT_NEEDS_APPROVAL | cashier's manual discount exceeds their limit and PIN missing/wrong. Prompt for a manager PIN |
| 422 | COUPON_INVALID | `details.reason` as in quote |
| 422 | SHIFT_NOT_OPEN | shop requires an open shift and the caller has none |
| 422 | CREDIT_NOT_ALLOWED | CREDIT payment but customer has no credit limit (0) or is blocked; `details.customerId` |
| 422 | CREDIT_LIMIT_EXCEEDED | `details`: `limitPaise`, `balancePaise`, `availablePaise`, `requestedPaise` |
| 429 | RATE_LIMITED | too many wrong approver PINs |
- **UI notes**: a failed sale leaves no trace (everything is one transaction), so fixing the cart and retrying is safe. Keep the same key while retrying the *same* basket after a timeout/5xx. Print the receipt from `GET .../receipt`, or locally from this response. A customer that has never been seen is created automatically from the mobile number. Always show `changePaise` to the cashier. Do not add round-off yourself: the server's `totalPaise` already has it.

### GET /api/v1/pos/sales
- **Who**: both. (A cashier can see every bill, not just their own.)
- **Purpose / when the UI calls it**: look up a bill to reprint, return, or review the day.
- **Request**: query (first matching filter wins, in this order): `invoiceNo` (exact, e.g. `VSM/2026-27/000042`; max 1 result), else `mobile` (customer mobile, same normalisation as above; the latest 50 bills of that customer), else `date` (`yyyy-MM-dd`, shop day; defaults to **today**; at most the latest 200 bills, newest first).
- **Response**: 200 array of `SaleRecord` (shape above, not paged).
- **Errors**: 400 `VALIDATION_FAILED` (`customerMobile`... "enter a 10 digit mobile number") for an invalid `mobile`; a bad `date` format gives 400 "Bad request".
- **UI notes**: no paging; a day with more than 200 bills is truncated.

### GET /api/v1/pos/sales/{id}
- **Who**: both.
- **Purpose**: open one bill (e.g. to start a return).
- **Request**: path `id` uuid.
- **Response**: 200 `SaleRecord`.
- **Errors**: 404 `NOT_FOUND` ("Bill not found"); malformed uuid = 400.
- **UI notes**: use `items[].id` and `items[].returnedQty` for the return screen.

### GET /api/v1/pos/sales/{id}/receipt
- **Who**: both.
- **Purpose**: printable receipt for thermal printers.
- **Request**: path `id`; query `width` int, default 32; **only 32 (58 mm paper) or 48 (80 mm paper)** are allowed.
- **Response**: 200 `{ "width": 32, "text": "...\n" }`. `text` is plain monospace text with `\n` newlines: shop header, "TAX INVOICE", invoice no, date/time (shop time, `dd-MM-yyyy HH:mm`), cashier name, customer mobile, item lines (weighed items print as `0.750 kg`), subtotal, coupon/coins/manual discount, round-off (+/-), TOTAL, taxable, CGST, SGST, payment lines (mode and amount), change, coins earned, footer.
- **Errors**:

| status | code | when |
|---|---|---|
| 400 | VALIDATION_FAILED (field `width`) | width not 32 or 48 |
| 404 | NOT_FOUND | bill missing |
- **UI notes**: Print with a fixed-width font; ESC/POS text printing needs no further wrapping. Amounts are printed in rupees with 2 decimals. There is no receipt endpoint for returns or credit repayments: render them yourself from their responses.

### POST /api/v1/pos/sales/{id}/returns
- **Who**: both. Cashiers limited to the return window (default 7 days, config `pos.cashierReturnDays`); admins not limited.
- **Purpose / when the UI calls it**: customer brings goods back; records a credit note, puts stock back, refunds money, adjusts coins and credit.
- **Request**: path `id` = sale id. Header `Idempotency-Key` **mandatory**; replay returns the original return (201). Body:
```json
{ "items": [ { "saleItemId": "uuid", "qty": 1 } ], "reason": "Damaged", "refunds": [ { "mode": "CASH", "amountPaise": 4500, "reference": null } ] }
```
| field | type | required | rules |
|---|---|---|---|
| items | array | yes | not empty; `saleItemId` must be an item of this bill; `qty` > 0 (grams for weighed items); duplicates are summed; total returned per item can not exceed `qty - returnedQty` |
| reason | string | no | free text, trimmed |
| refunds | array | no | modes `CASH`,`UPI`,`CARD` only (no CREDIT); amounts > 0; **must sum to the pay-out amount**. If omitted, the full pay-out goes out as `CASH` |
The refund value of a line is its share of `netPaise` (what was actually paid after discounts), computed as a difference of cumulative shares so partial returns sum exactly. Round-off and manual discount are already inside `netPaise`.
The "pay-out amount" = refund total minus any part that just reduces the customer's credit (when the bill had a CREDIT payment). If that is the whole refund, send no `refunds` (or an empty list); the server records none.
- **Response**: 201.
```json
{ "id": "uuid", "creditNoteNo": "VSM-CN/2026-27/000003", "saleId": "uuid", "cashierId": "uuid", "reason": "Damaged",
  "refundPaise": 4300, "taxablePaise": 4095, "cgstPaise": 103, "sgstPaise": 102, "coinsClawedBack": 0, "coinsRestored": 0,
  "createdAt": "2026-11-08T13:00:00Z",
  "items": [ { "saleItemId": "uuid", "qty": 1, "refundPaise": 4300, "taxablePaise": 4095, "cgstPaise": 103, "sgstPaise": 102 } ],
  "refunds": [ { "mode": "CASH", "amountPaise": 4300, "reference": null } ], "creditAdjustedPaise": 0 }
```
`refundPaise` total value of the returned goods; `creditAdjustedPaise` the part that reduced the customer's udhar instead of being paid out (`refunds` sum = `refundPaise - creditAdjustedPaise`); `coinsClawedBack` coins taken back that the sale had earned; `coinsRestored` coins given back that the sale had redeemed (both proportional to the value returned, 0 for walk-in sales).
- **Errors**:

| status | code | when |
|---|---|---|
| 400 | VALIDATION_FAILED | missing key; empty `items`; `saleItemId` not on this bill (field `saleItemId`); `qty` <= 0; `refunds` do not sum to the pay-out ("refunds add up to X but the amount is Y", extra text mentions the credit part), wrong mode, amount <= 0 |
| 404 | NOT_FOUND | bill missing |
| 409 | RETURN_EXCEEDS_SOLD | more than returnable; `details.saleItemId`, `details.returnable` |
| 422 | RETURN_NEEDS_ADMIN | cashier and bill older than the window; `details.days` |
- **UI notes**: show remaining returnable qty per line. For a credit bill show a note that part of the refund reduces the customer's balance (`creditAdjustedPaise`). Offer a prefilled refund line in the original payment mode; if the customer paid by split, let the cashier divide.

### GET /api/v1/pos/sales/{id}/returns
- **Who**: both.
- **Purpose**: list returns already made on a bill (before starting a new return, or to reprint a credit note).
- **Request**: path `id`.
- **Response**: 200 array of return objects (shape above), oldest first. Empty array when none (also for an unknown id: no 404).
- **Errors**: none specific.

### GET /api/v1/pos/upi-link
- **Who**: both.
- **Purpose**: when the customer pays by UPI, show a QR for the shop's UPI id and the amount.
- **Request**: query `amountPaise` (long, required, > 0), `note` (string, optional; shown in the UPI app; use the invoice-less basket reference or empty).
- **Response**: 200 `{ "link": "upi://pay?pa=vijaymall%40okbank&pn=Vijay%20Super%20Mall&am=86.00&cu=INR&tn=" }`. Render as QR; `am` is in rupees with 2 decimals.
- **Errors**:

| status | code | when |
|---|---|---|
| 400 | VALIDATION_FAILED (`amountPaise`) | amount <= 0; missing `amountPaise` = "Bad request" |
| 422 | UPI_NOT_CONFIGURED | the shop has no UPI id set (admin must set it in shop settings) |
- **UI notes**: the backend does not verify the UPI payment; the cashier confirms it visually and puts the transaction id in `reference`.

### POST /api/v1/pos/holds
- **Who**: both.
- **Purpose**: park a basket while the customer fetches something; resume later (any till can resume).
- **Request**: body `{ "label": "Mrs Shah", "cart": { ...same cart object... } }`. `cart` required with non-empty `items`; `label` optional free text (trimmed, at most 60 characters). At most 50 lines per basket; each line `qty` is 1 to 9999 pieces (1 to 100000 grams for a weighed item), else 400 with `details.fields["cart.items[N].qty"]` "must be 1 to 9999" ("must be 1 to 100000" for weighed). Customer mobile, if present, must be valid. Prices/stock are not checked and nothing is reserved. Holds are not tied to a shift.
- **Response**: 201 `{ "id": "uuid", "label": "Mrs Shah", "customerMobile": "9876543210", "cashierId": "uuid", "createdAt": "...", "expiresAt": "...", "cart": { "items": [...], "customerMobile": ..., "couponCode": ..., "coinMode": ..., "percent": ... } }`. `expiresAt` is when the basket is removed automatically (see Addendum 5), `null` when baskets never expire.
- **Errors**: 400 `VALIDATION_FAILED` (`cart.items` empty/missing; more than 50 lines (`items` "must have at most 50 lines"); a `qty` out of range; `label` over 60; bad mobile). 409 `HOLD_LIMIT` ("Too many parked baskets: resume or discard some first. Parked baskets are removed automatically after 7 days", `details.max` = 100, `details.expiryDays` = 7) when 100 baskets are already parked: resume or discard one first, so no basket ever drops out of the list. The last sentence of the message is left out when baskets never expire.
- **UI notes**: the cart is stored as sent, the product may be gone/changed later: re-quote on resume.

### GET /api/v1/pos/holds
- **Who**: both. Returns **every** held basket from every cashier, newest first (not paged). At most 100 can exist (creating the 101st is refused with 409 `HOLD_LIMIT`), so none is ever missing from the list.
- **Response**: 200 array of hold objects as above. **Errors**: none.

### GET /api/v1/pos/holds/{id}
- **Who**: both. **Response**: 200 hold object. **Errors**: 404 `NOT_FOUND` ("Held basket not found").

### DELETE /api/v1/pos/holds/{id}
- **Who**: both (any cashier can delete any hold).
- **Purpose**: discard a hold, or clean up after the resumed basket has been sold (sales do not delete holds automatically).
- **Response**: 204 no body. **Errors**: 404 `NOT_FOUND`.

### GET /api/v1/pos/reports/day
- **Who**: both. A CASHIER always gets only their own bills (any `cashierId` they send is ignored). An ADMIN gets everybody's unless `cashierId` is given.
- **Purpose**: end-of-day cash-up; also the "today" tile on the counter home.
- **Request**: query `date` (`yyyy-MM-dd`, shop day, default today), `cashierId` (uuid, admin only).
- **Response**: 200.
```json
{ "date": "2026-11-08", "bills": 42, "salesPaise": 1250000, "taxablePaise": 1190000, "cgstPaise": 30000, "sgstPaise": 30000,
  "coinsRedeemed": 120, "creditNotes": 2, "refundsPaise": 8000, "netPaise": 1242000,
  "paidByMode": { "CARD": 200000, "CASH": 700000, "UPI": 350000 }, "refundedByMode": { "CARD": 0, "CASH": 8000, "UPI": 0 },
  "cashExpectedPaise": 698000, "creditGivenPaise": 50000, "creditReturnedPaise": 0, "creditRecoveredPaise": 10000,
  "creditRecoveredByMode": { "CARD": 0, "CASH": 10000, "UPI": 0 } }
```
| field | meaning |
|---|---|
| bills | number of bills |
| salesPaise | sum of bill totals (includes credit sales, includes round-off) |
| taxablePaise, cgstPaise, sgstPaise | GST totals of bills (returns not subtracted) |
| coinsRedeemed | coins used on bills (a count, not paise) |
| creditNotes | number of returns |
| refundsPaise | value of goods returned (includes the part that reduced credit) |
| netPaise | `salesPaise - refundsPaise` |
| paidByMode / refundedByMode / creditRecoveredByMode | always contain keys CARD, CASH, UPI (0 when none); paise received / paid out / received as credit repayments |
| cashExpectedPaise | `paid.CASH - refunded.CASH + creditRecovered.CASH`: cash that should have come into the drawer for the scope (does **not** include the opening float) |
| creditGivenPaise | bills' CREDIT payments |
| creditReturnedPaise | returns that reduced credit |
| creditRecoveredPaise | repayments taken |
The day is by bill time (`createdAt`, which is `soldAt` for offline bills).
- **Errors**: 400 for a bad date format ("Bad request").
- **UI notes**: for the drawer count compare with the shift's expectedCashPaise (which adds the opening float).

---

## Shifts (cash drawer)

`ShiftView` used by all shift endpoints:
```json
{ "id": "uuid", "cashierId": "uuid", "openedAt": "2026-11-08T03:30:00Z", "openingFloatPaise": 200000, "closedAt": null,
  "expectedCashPaise": 898000, "countedCashPaise": null, "variancePaise": null, "note": "", "open": true }
```
| field | type | meaning |
|---|---|---|
| expectedCashPaise | long | `openingFloat + cash received on bills - cash refunded + cash taken as credit repayments`, all by this cashier since `openedAt`. Live while open; frozen at close |
| closedAt, countedCashPaise, variancePaise | nullable | null while open. `variancePaise` = counted - expected: negative means the drawer was short |
| note | string | reason text given at close (empty string when none) |
| open | bool | |

### POST /api/v1/pos/shifts/open
- **Who**: both (a cashier opens their own; admin can open their own too). One open shift per user.
- **Purpose**: start of day / handover: count the drawer and record the float.
- **Request**: body `{ "openingFloatPaise": 200000 }`: long >= 0 (`@Min(0)`), required.
- **Response**: 201 `ShiftView` (open, `expectedCashPaise` = float).
- **Errors**: 400 `VALIDATION_FAILED` (`openingFloatPaise` negative); 409 `SHIFT_ALREADY_OPEN` ("You already have an open shift: close it first") also when two opens race.
- **UI notes**: on 409 call `GET /pos/shifts/current` and continue with it.

### GET /api/v1/pos/shifts/current
- **Who**: both. **Purpose**: on app start / before billing, know whether a shift is open and show running expected cash.
- **Response**: 200 `ShiftView`. **Errors**: 404 `NOT_FOUND` ("Open shift not found") = no open shift (normal state, not a failure).

### POST /api/v1/pos/shifts/close
- **Who**: both (closes the caller's own open shift).
- **Purpose**: end of shift: enter the counted cash.
- **Request**: body `{ "countedCashPaise": 897500, "note": "Rs 5 short, coins given" }`. `countedCashPaise` long >= 0 required; `note` optional, max 200 chars (trimmed). If `countedCashPaise` differs from the expected cash, a non-empty `note` is required.
- **Response**: 200 `ShiftView` (closed; `variancePaise` set).
- **Errors**:

| status | code | when |
|---|---|---|
| 400 | VALIDATION_FAILED | `countedCashPaise` negative; note longer than 200 |
| 404 | NOT_FOUND | no open shift |
| 422 | VARIANCE_NEEDS_NOTE | counted != expected and note empty; `details.expectedCashPaise`, `details.countedCashPaise`. Message says over/short by Rs X: show it and ask for the reason |
- **UI notes**: upload queued offline bills before closing. After closing, billing is blocked if the shop requires shifts.

### GET /api/v1/admin/pos/shifts
- **Who**: admin only (`/admin/**`). **Purpose**: review all shifts and drawer differences.
- **Request**: query `cashierId` uuid (optional filter), `page` (default 0), `size` (default 20, max 50). Newest opened first.
- **Response**: 200 page of `ShiftView`. **Errors**: none specific.

---

## Credit (udhar)

`AccountView`:
```json
{ "customerId": "uuid", "name": "Ramesh", "mobile": "9876543210", "balancePaise": 120000, "limitPaise": 500000, "availablePaise": 380000 }
```
`balancePaise` = what the customer owes now; `limitPaise` = maximum they may owe (0 = credit off); `availablePaise = max(0, limit - balance)`. `name` may be null.

`LedgerView` (one row per change, newest first):
```json
{ "id": "uuid", "kind": "SALE", "deltaPaise": 50000, "balanceAfterPaise": 120000, "refNo": "VSM/2026-27/000042", "note": "", "at": "2026-11-08T12:00:00Z" }
```
`kind`: `SALE` (credit taken on a bill, delta > 0), `RETURN` (goods returned cancelled debt, delta < 0, refNo = credit note no.), `PAYMENT` (repayment, delta < 0, refNo = receipt no.), `ADJUST` (admin correction, either sign). `deltaPaise > 0` = customer owes more. `refNo` and `note` may be null/empty.

### GET /api/v1/pos/credit/account
- **Who**: both. **Purpose**: before offering credit or taking a repayment, look the customer up by mobile.
- **Request**: query `mobile` (required; accepts `9876543210`, `+91 98765 43210`, `09876543210`, `91-9876543210`).
- **Response**: 200 `AccountView`.
- **Errors**: 400 `VALIDATION_FAILED` (`mobile`: enter a 10 digit mobile number); 404 `NOT_FOUND` ("Customer not found": shop has never seen this number; an admin must approve credit first via `PUT /admin/credit/limit`).
- **UI notes**: if `limitPaise == 0`, hide/disable the CREDIT payment option and say "credit not approved".

### GET /api/v1/pos/credit/ledger
- **Who**: both. **Request**: query `mobile` (required), `page` (0), `size` (20, max 50). **Response**: 200 page of `LedgerView`. **Errors**: same as account (400 / 404).

### POST /api/v1/pos/credit/payments
- **Who**: both. **Purpose**: customer pays some or all of their udhar at the counter. Cash received here is included in the drawer's expected cash.
- **Request**: header `Idempotency-Key` **mandatory**; replay returns the first receipt (201). Body:
```json
{ "customerMobile": "9876543210", "payments": [ { "mode": "CASH", "amountPaise": 20000, "reference": null } ], "note": "Part payment" }
```
| field | type | required | rules |
|---|---|---|---|
| customerMobile | string | yes | non-blank (`@NotBlank`), valid Indian mobile |
| payments | array | yes | not empty; modes `CASH`,`UPI`,`CARD` only; each amount > 0; total may not exceed what the customer owes |
| note | string | no | max 200 chars |
- **Response**: 201.
```json
{ "id": "uuid", "receiptNo": "VSM-CR/2026-27/000007", "customerId": "uuid", "customerMobile": "9876543210", "amountPaise": 20000,
  "payments": [ { "mode": "CASH", "amountPaise": 20000, "reference": null } ], "balanceDuePaise": 100000, "note": "Part payment", "at": "2026-11-08T14:00:00Z" }
```
`balanceDuePaise` = what the customer still owes after this payment. (On an idempotent replay it shows the *current* balance, not the balance at that moment.)
- **Errors**:

| status | code | when |
|---|---|---|
| 400 | VALIDATION_FAILED | missing key; blank mobile; bad mobile; empty `payments`; mode not CASH/UPI/CARD (CREDIT is refused here); amount <= 0; note > 200 |
| 404 | NOT_FOUND | customer unknown |
| 422 | CREDIT_OVERPAYMENT | paid more than owed; `details.balancePaise`, `details.paidPaise` |
- **UI notes**: print a receipt from this response (no receipt-text endpoint). Pre-fill the amount with `balancePaise` for "pay full".

### GET /api/v1/credit
- **Who**: any signed-in user, in practice the **customer** app: returns the caller's own account (zeros if they never had credit). **Response**: 200 `AccountView`. **Errors**: 404 if the user record vanished. 
- **Purpose**: "What I owe the shop" screen.

### GET /api/v1/credit/ledger
- **Who**: same as above (own data only). **Request**: `page`, `size` (max 50). **Response**: 200 page of `LedgerView`. Read only; customers cannot pay online through this API.

### PUT /api/v1/admin/credit/limit
- **Who**: admin only. **Purpose**: approve (or change, or switch off) credit for a mobile number, even before the customer's first bill; creates the customer record if the shop has never seen the number.
- **Request**: body `{ "mobile": "9876543210", "limitPaise": 500000 }`. `mobile` non-blank, valid; `limitPaise` long >= 0 (`@PositiveOrZero`); 0 switches credit off.
- **Response**: 200 `AccountView`.
- **Errors**: 400 `VALIDATION_FAILED` (`mobile`, `limitPaise`).
- **UI notes**: this is the main "approve udhar customer" screen; the cashier at the till cannot do this (CASHIER gets 403).

### GET /api/v1/admin/credit/accounts/{customerId}
- **Who**: admin. **Response**: 200 `AccountView`. **Errors**: 404 `NOT_FOUND` ("Customer not found").

### GET /api/v1/admin/credit/accounts/{customerId}/ledger
- **Who**: admin. **Request**: `page` (0), `size` (20, max 50). **Response**: 200 page of `LedgerView`. (No 404 for an unknown id, just an empty page.)

### PUT /api/v1/admin/credit/accounts/{customerId}/limit
- **Who**: admin. **Request**: body `{ "limitPaise": 300000 }` (>= 0). **Response**: 200 `AccountView`. **Errors**: 400 (negative), 404 (customer). Lowering the limit below the current balance is allowed; the customer simply cannot take more credit.

### POST /api/v1/admin/credit/accounts/{customerId}/adjust
- **Who**: admin. **Purpose**: correction or write-off of a customer's debt, recorded in the ledger as `ADJUST` and in the audit log.
- **Request**: body `{ "amountPaise": -5000, "note": "Written off, old balance" }`. `amountPaise` long, non-zero, from -100,000,000 to 100,000,000 paise (negative lowers what they owe, positive raises it); `note` required (`@NotBlank`), max 200 chars.
- **Response**: 200 `AccountView`.
- **Errors**:

| status | code | when |
|---|---|---|
| 400 | VALIDATION_FAILED | `amountPaise` beyond the bound (`fields.amountPaise` "must be at most 100000000" / "must be at least -100000000"); amount 0; blank note; note > 200 |
| 404 | NOT_FOUND | customer |
| 422 | CREDIT_ADJUST_TOO_LARGE | would make the balance negative; `details.balancePaise` |
- **UI notes**: confirm dialog; irreversible except by a counter-adjustment.

---

## Approvals

### PUT /api/v1/admin/me/approval-pin
- **Who**: admin only. **Purpose**: each manager sets a PIN they type at the till to approve a cashier's discount.
- **Request**: body `{ "pin": "4821" }`: 4 to 8 digits only (`\d{4,8}`), required. Replaces any earlier PIN. Stored hashed; never returned.
- **Response**: 204.
- **Errors**: 400 `VALIDATION_FAILED` (`pin`: "use 4 to 8 digits").
- **UI notes**: a cashier's discount prompt accepts the PIN of **any** admin that has one. The PIN prompt must be a numeric password field; allow 5 wrong tries per 10 minutes (429 after).

---

## Offline sync

### GET /api/v1/pos/sync/status
- **Who**: both. **Purpose**: see whether the local product copy is stale and learn the offline limits.
- **Response**: 200 `{ "serverTime": "2026-11-08T17:52:42Z", "catalogVersion": "2026-11-08T10:00:00Z", "maxOfflineDays": 7, "batchLimit": 50 }`. `catalogVersion` = time of the latest change to any product or its counter details (the epoch `1970-01-01T00:00:00Z` if none): re-download the list when it is newer than your saved value.
- **Errors**: none specific.

### POST /api/v1/pos/sync/sales
- **Who**: both. **Purpose**: upload bills rung up offline.
- **Request**: no header; each bill carries its own idempotency key. Body:
```json
{ "sales": [ { "idempotencyKey": "5b9c...uuid", "soldAt": "2026-11-08T09:15:00Z",
  "cart": { "items": [ { "productId": "uuid", "qty": 2 } ], "customerMobile": null, "couponCode": null, "coinMode": null, "percent": null },
  "payments": [ { "mode": "CASH", "amountPaise": 5000, "reference": null } ], "cashTenderedPaise": 5000, "manualDiscount": null } ] }
```
| field | type | required | rules |
|---|---|---|---|
| sales | array | yes | 1 to 50 bills (else 400, field `sales`) |
| sales[].idempotencyKey | string | yes | non-blank; the key is the dedupe identity: the same key is never rung up twice |
| sales[].soldAt | ISO UTC | no | when it really happened; not more than 5 minutes in the future, not older than 7 days; default = now |
| sales[].cart / payments / cashTenderedPaise / manualDiscount | | | exactly as in `POST /pos/sales` |
- **Response**: 200 always (if the envelope is valid):
```json
{ "results": [ { "idempotencyKey": "5b9c...", "status": "CREATED", "saleId": "uuid", "invoiceNo": "VSM/2026-27/000043", "errorCode": null, "message": null },
               { "idempotencyKey": "7d2e...", "status": "REJECTED", "saleId": null, "invoiceNo": null, "errorCode": "VALIDATION_FAILED", "message": "payments add up to 50.00 but the bill is 52.00" } ] }
```
`status` = `CREATED` | `DUPLICATE` | `REJECTED`. For REJECTED, `errorCode` is the same code the single-sale endpoint would have returned (`VALIDATION_FAILED`, `OUT_OF_STOCK` is *not* possible for offline bills, `COUPON_INVALID`, `DISCOUNT_NEEDS_APPROVAL`, `SHIFT_NOT_OPEN`, `CREDIT_NOT_ALLOWED`, `CREDIT_LIMIT_EXCEEDED`, `INTERNAL_ERROR`) and `message` is human readable.
- **Errors** (whole request): 400 `VALIDATION_FAILED` (empty list, over 50, malformed JSON); 401/403/429. An invalid single bill never turns the call into an error.
- **UI notes**: see the Offline mode section. Process results by `idempotencyKey` (the order is the same as sent). Bills in a batch are processed in order, so put older bills first. A bill with neither `rung` nor `cart` is reported as REJECTED `VALIDATION_FAILED`.

---

## Admin counter set-up

### GET /api/v1/admin/shop-settings
- **Who**: admin only. **Purpose**: the "Shop details for bills" form. **Response**: 200 same object as `GET /pos/shop`. (The customer-facing `GET /api/v1/shop-settings` is a different, public endpoint documented elsewhere.)

### PUT /api/v1/admin/shop-settings
- **Who**: admin only. **Purpose**: save the details printed on every bill and used for the scale labels. Applies immediately to new bills; also pushes a live "shop" change event.
- **Request**: body (all strings unless noted):
```json
{ "shopName": "Vijay Super Mall", "address": "Station Road", "phone": "9876543210", "gstin": "24ABCDE1234F1Z5", "stateCode": "24",
  "upiId": "vijaymall@okbank", "invoicePrefix": "VSM", "receiptFooter": "Thank you!", "weighedPrefix": "2", "weighedMode": "WEIGHT_GRAMS",
  "promise": "Fresh every day", "details": "About the shop" }
```
| field | required | rules / default when omitted or blank |
|---|---|---|
| shopName | yes | non-blank (`@NotBlank`), trimmed |
| address, phone | no | default "" |
| gstin | no | upper-cased; if not blank must match GSTIN format `^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$`; else 400 field `gstin` |
| stateCode | no | default "24"; must be 2 digits |
| upiId | no | default ""; if not blank must look like `name@bank` (`^[\w.\-]{2,}@[\w]{2,}$`) |
| invoicePrefix | no | default "VSM"; upper-cased; 2 to 6 capital letters/digits |
| receiptFooter | no | default "Thank you! Visit again." |
| weighedPrefix | no | default "2"; 1 or 2 digits |
| weighedMode | no | `WEIGHT_GRAMS` (default) or `PRICE_PAISE` |
| promise | no | max 200 chars; **if the field is omitted (null) the stored value is kept**; send "" to clear |
| details | no | max 500 chars; same keep-if-omitted rule |
- **Response**: 200 saved settings (same shape as GET).
- **Errors**: 400 `VALIDATION_FAILED` with the field names above (`shopName`, `gstin`, `stateCode`, `upiId`, `invoicePrefix`, `weighedPrefix`, `details`, `promise`).
- **UI notes**: the other fields are replaced, with defaults filling blanks. Changing `invoicePrefix` affects only new numbers. Changing `weighedPrefix`/`weighedMode` changes how scale labels are read: tell the admin to reprint scale labels.

### GET /api/v1/admin/products/{id}/pos
- **Who**: admin only. **Purpose**: edit counter attributes of a product.
- **Response**: 200 `{ "productId": "uuid", "soldBy": "UNIT", "plu": null, "gstPercent": 0, "hsn": "", "posOnly": false, "barcodes": ["8901234567890"] }`. Defaults for a product never configured: `UNIT`, no PLU, 0 % GST, empty HSN.
- **Errors**: 404 `NOT_FOUND` (product).

### PUT /api/v1/admin/products/{id}/pos
- **Who**: admin only. **Purpose**: set how the product is billed at the counter and which barcodes it answers to. **Replaces** the barcode list.
- **Request**: body:
```json
{ "soldBy": "WEIGHT", "plu": "123", "gstPercent": 5, "hsn": "1006", "barcodes": ["8901234567890"] }
```
| field | type | required | rules |
|---|---|---|---|
| soldBy | `UNIT`/`WEIGHT` | yes (`@NotNull`) | `WEIGHT` makes the product counter-only (`posOnly=true`) and its price per 1000 g |
| plu | string | required for `WEIGHT` | 1 to 6 digits (`\d{1,6}`); leading zeros ignored; must be unique across products |
| gstPercent | int | yes in practice (absent = 0) | one of 0, 3, 5, 12, 18, 28 |
| hsn | string | no | trimmed, free text |
| barcodes | string[] | no | each 4-32 of letters, digits, dashes; blanks removed, duplicates removed; unique across products; for `WEIGHT` products no 13-digit code starting with the shop's weighed prefix |
- **Response**: 200 same shape as GET.
- **Errors**:

| status | code | when |
|---|---|---|
| 400 | VALIDATION_FAILED | `plu` missing/non-numeric for WEIGHT, "digits only, up to 6"; bad `gstPercent`; bad `barcodes` |
| 404 | NOT_FOUND | product |
| 409 | PLU_TAKEN | another product uses this PLU; `details.plu` |
| 409 | BARCODE_TAKEN | barcode already on another product; `details.barcode` |
- **UI notes**: weighed items' `stock` and `price` are managed in the catalog screens (price per kg; stock in grams).

### GET /api/v1/admin/staff
- **Who**: admin only. **Purpose**: cashier roster. **Response**: 200 array (not paged) `[ { "id": "uuid", "email": "c@x.com", "name": "Asha", "mobile": "9876543210", "active": true } ]`. `active=false` = blocked. Only role CASHIER users are listed.

### POST /api/v1/admin/staff
- **Who**: admin only. **Purpose**: add a cashier; they then sign in with the normal e-mail OTP flow.
- **Request**: body `{ "email": "c@x.com", "name": "Asha", "mobile": "9876543210" }`. `email` required, must look like an e-mail (stored lower-case); `name` optional, 1 to 80 characters; `mobile` optional, exactly 10 digits (else 400 `mobile` "must be 10 digits"); `confirmPromote` optional boolean (see below).
- **Response**: 201 staff object (`active: true`).
- **Errors**: 400 `VALIDATION_FAILED` (`email` invalid, "this email belongs to an admin", bad `name` or `mobile`). 409 `EMAIL_ALREADY_REGISTERED` when the e-mail already has any account (customer, cashier, blocked or deleted) and the request does not qualify for promotion below; `details.role` tells what the account is.
- **Promoting an existing customer**: send `"confirmPromote": true` after asking the admin "this e-mail already belongs to a customer: make them a cashier?". Only an **active customer** is promoted (201, the account becomes a cashier and keeps its history; a `name`/`mobile` in the request replaces the stored one). A blocked or deleted account, an existing cashier and an admin are never promoted: they stay 409 (admin: 400). Without `confirmPromote` nothing is changed.
- **UI notes**: the cashier does not need a password; they sign in with the e-mail code. Show the 409 as a confirmation dialog, not as an error.

### PATCH /api/v1/admin/staff/{id}
- **Who**: admin only. **Purpose**: block or unblock a cashier; a blocked cashier loses access on the next request.
- **Request**: body `{ "active": false }`.
- **Response**: 200 with empty body (no `204` annotation; treat any 2xx as success). **Errors**: 404 `NOT_FOUND` ("Cashier not found") for unknown ids and for non-cashier ids.

---

## Purchasing and stock

> **Superseded by `70-purchases.md`.** Purchases now carry GST, discounts, freight, payments, suppliers and returns; the old body below still works, but `totalCostPaise` now includes GST and the answer has many more fields. Read file 70 for the current contract.

### POST /api/v1/admin/purchases
- **Who**: admin only. **Purpose**: receive a supplier delivery (GRN): records the document and adds stock to each product (reason `PURCHASE`, linked to the document) in one transaction.
- **Request**: header `Idempotency-Key` **mandatory**; replay returns the first document (201) and adds no stock. Body:
```json
{ "supplierName": "Shah Traders", "invoiceNo": "ST-2231", "note": "Morning delivery",
  "items": [ { "productId": "uuid", "qty": 24, "unitCostPaise": 9500 } ] }
```
| field | type | required | rules |
|---|---|---|---|
| supplierName | string | yes | 1..120 chars after trimming |
| invoiceNo | string | no | supplier's invoice number, max 60 |
| note | string | no | max 200 |
| items | array | yes | 1..200 lines; a product may appear only once per delivery |
| items[].productId | uuid | yes | must exist |
| items[].qty | int | yes | 1..1,000,000 (pieces; grams for weighed products) |
| items[].unitCostPaise | long | yes | 0..100,000,000 paise (Rs 10 lakh per unit upper bound); cost per stock unit **(unsure how cost is expressed for weighed items)** |
- **Response**: 201.
```json
{ "id": "uuid", "supplierName": "Shah Traders", "invoiceNo": "ST-2231", "note": "Morning delivery", "totalCostPaise": 228000,
  "createdBy": "uuid", "createdAt": "2026-11-08T08:00:00Z",
  "items": [ { "productId": "uuid", "name": "Basmati Rice 1kg", "qty": 24, "unitCostPaise": 9500, "lineCostPaise": 228000 } ],
  "warnings": [] }
```
`warnings` is a list of texts; it is non-empty when the same invoice number was already recorded for the same supplier (see Addendum 5). The delivery is recorded either way.
- **Errors**: 400 `VALIDATION_FAILED` (fields `supplierName`, `invoiceNo`, `note`, `items` incl. "a product appears twice: add its quantities together", `productId` required / "a product does not exist", `qty`, `unitCostPaise`; missing key header).
- **UI notes**: generate the key when the admin opens the "new delivery" form, keep it until success. Never let the admin submit twice with new keys.

### GET /api/v1/admin/purchases
- **Who**: admin only. **Request**: `page` (0), `size` (20, max 50). **Response**: 200 page of purchase documents (shape above), newest first.

### GET /api/v1/admin/purchases/{id}
- **Who**: admin only. **Response**: 200 purchase document. **Errors**: 404 `NOT_FOUND` ("Purchase not found").

### POST /api/v1/admin/stock-takes
- **Who**: admin only. **Purpose**: after a physical count, set stock to the counted figures. The difference is saved in the stock history (reason `STOCK_TAKE`) so shrinkage is visible.
- **Request**: no Idempotency-Key (it sets absolute figures, so a repeat finds nothing to change unless sales happened in between). Body:
```json
{ "note": "Monthly count, aisle 3", "items": [ { "productId": "uuid", "countedQty": 18 } ] }
```
| field | type | required | rules |
|---|---|---|---|
| note | string | no | free text, only written to the audit log (no length rule) |
| items | array | yes | 1..500 lines; no repeated product |
| items[].productId | uuid | yes | must exist |
| items[].countedQty | int | yes | >= 0 (pieces; grams for weighed products) |
- **Response**: 200.
```json
{ "changes": [ { "productId": "uuid", "name": "Basmati Rice 1kg", "before": 20, "counted": 18, "delta": -2 } ], "unchanged": 4 }
```
`delta` = counted - before; negative = stock was missing. Products whose count equals the system figure are only counted in `unchanged`.
- **Errors**: 400 `VALIDATION_FAILED` (`items` empty/over 500/repeated product, `productId` required or does not exist, `countedQty` negative).
- **UI notes**: show the result as a variance report. Sales that happen while counting will skew the numbers; count outside trading or after closing.

---

## Reports and audit

### GET /api/v1/admin/dashboard
- **Who**: admin only. **Purpose**: home screen. **Request**: none.
- **Response**: 200.
```json
{ "salesTodayPaise": 450000, "ordersToday": 12, "newOrders": 3, "coinsIssuedToday": 340,
  "last7Days": [ { "date": "2026-11-02", "salesPaise": 400000 } ],
  "lowStock": [ { "productId": "uuid", "name": "Sugar 1kg", "qty": 2 } ],
  "posSalesTodayPaise": 1250000, "posBillsToday": 42, "posRefundsTodayPaise": 8000 }
```
`salesTodayPaise`/`ordersToday`/`last7Days` count **online orders only** (excluding CANCELLED and PAYMENT_PENDING); `newOrders` = orders still in status PLACED; `last7Days` always has 7 entries, oldest first; `lowStock` max 10; `pos*` fields are counter totals since the start of today (shop time). Cached for about 30 s.
- **Errors**: none specific.

### GET /api/v1/admin/reports/summary
- **Who**: admin only. **Purpose**: sales over a period, online and counter together. **Request**: `period` / `from` / `to` (see the shared rules above).
- **Response**: 200.
```json
{ "from": "2026-11-02", "to": "2026-11-08", "salesPaise": 9000000, "orders": 310, "onlineSalesPaise": 3000000, "onlineOrders": 90,
  "posSalesPaise": 6000000, "posBills": 220, "posRefundsPaise": 120000, "coinsIssued": 5400,
  "byDay": [ { "date": "2026-11-02", "salesPaise": 1200000, "orders": 40 } ],
  "topProducts": [ { "productId": "uuid", "name": "Basmati Rice", "qty": 120, "salesPaise": 480000 } ],
  "creditGivenPaise": 300000, "creditRecoveredPaise": 150000, "outstandingCreditPaise": 900000 }
```
`salesPaise`/`orders` = online + counter. `posRefundsPaise` is listed separately and **not** subtracted from sales. `byDay` has one entry per day in the range. `topProducts` max 10, by sales; `qty` counts pieces only (weighed items add to `salesPaise` but not to `qty`). `outstandingCreditPaise` is the total owed **now** (not at `to`). Cached ~1 min.
- **Errors**: 400 as in the shared period rules.

### GET /api/v1/admin/reports/credit
- **Who**: admin only. **Request**: `period` / `from` / `to`.
- **Response**: 200.
```json
{ "from": "2026-11-02", "to": "2026-11-08", "outstandingPaise": 900000, "customersWithDues": 14, "givenPaise": 300000, "returnedPaise": 20000,
  "recoveredPaise": 150000, "writtenOffPaise": 5000, "addedPaise": 0, "recoveredByMode": { "CASH": 100000, "UPI": 50000, "CARD": 0 },
  "salesByPaymentType": [ { "type": "CASH", "bills": 120, "totalPaise": 3000000 } ],
  "byDay": [ { "date": "2026-11-02", "creditGivenPaise": 40000, "recoveredPaise": 10000 } ],
  "topDebtors": [ { "customerId": "uuid", "name": "Ramesh", "mobile": "9876543210", "balancePaise": 120000, "limitPaise": 500000,
                    "lastSaleAt": "2026-11-07T10:00:00Z", "lastPaymentAt": null } ] }
```
`outstandingPaise` and `customersWithDues` are current (not period). `givenPaise` credit put on accounts by bills; `returnedPaise` debt cancelled by returns; `recoveredPaise` repayments; `writtenOffPaise`/`addedPaise` admin adjustments down/up. `salesByPaymentType[].type` is one of `NONE`, `CASH`, `UPI`, `CARD`, `SPLIT`, `CREDIT`, `HYBRID`, largest first. `topDebtors` max 20. `lastSaleAt`/`lastPaymentAt` nullable.
- **Errors**: 400 as in shared period rules.

### GET /api/v1/admin/credit/accounts
- **Who**: admin only. **Purpose**: debtors list for collections.
- **Request**: `outstandingOnly` boolean (default **true**: only customers who owe > 0), `page` (0), `size` (20, max 50). Sorted by balance desc, then name.
- **Response**: 200 page of the `topDebtors` objects above. **Errors**: none specific.

### GET /api/v1/admin/reports/counter-controls
- **Who**: admin only. **Purpose**: spot cashiers who discount, refund or come up short far more than others.
- **Request**: `period` / `from` / `to`.
- **Response**: 200.
```json
{ "from": "2026-11-02", "to": "2026-11-08", "cashiers": [ { "cashierId": "uuid", "name": "Asha", "bills": 120, "salesPaise": 3000000,
  "manualDiscounts": 4, "manualDiscountPaise": 8000, "discountPercentOfSales": 0.3, "returns": 2, "refundsPaise": 12000,
  "refundPercentOfSales": 0.4, "shiftsClosed": 5, "shiftVariancePaise": -1500 } ] }
```
Sorted by `salesPaise` descending. Percentages are of that cashier's own sales, one decimal (0.0 when no sales). `name` is the name or e-mail ("?" if unknown). `shiftVariancePaise` sums the variance of shifts **closed** in the range; negative = drawer short. Cashiers appear if they had sales, returns or closed shifts in the range.
- **Errors**: 400 as in shared period rules.

### GET /api/v1/admin/stock/low
- **Who**: admin only. **Purpose**: reorder list. Retired (inactive) products are not listed (they are filtered before the cap), so every row is a product that can still be sold.
- **Request**: query `limit` int, default 50. **The hard cap is 200 rows**: a `limit` above 200 returns 200 and one below 1 returns 1, silently (no error). There is no `page` parameter and no `hasMore`, so this endpoint can never show more than the 200 emptiest products; the dashboard's `lowStock` list is the same list cut to 10.
- **Response**: 200 plain array (deliberately not a paged object) `[ { "productId": "uuid", "name": "Sugar 1kg", "qty": 2 } ]`: products whose stock is at or below their own alert level (`lowThreshold`), **sorted by `qty` ascending** (emptiest first; products with the same `qty` come in no guaranteed order). Retired (inactive) products are filtered out before the cap. Set the alert level per product in the catalog admin screens.

### GET /api/v1/admin/audit-log
- **Who**: admin only. **Purpose**: "who changed what", newest first.
- **Request**: all optional: `actorId` (uuid), `action` (string, e.g. `SALE`, `RETURN`, `MANUAL_DISCOUNT`, `OFFLINE_OVERSOLD`, `SHIFT_CLOSE`, `CREDIT_PAYMENT`, `CREDIT_LIMIT`, `CREDIT_ADJUST`, `PURCHASE`, `STOCK_TAKE`, `APPROVAL_PIN_SET`, `CREATE`/`BLOCK`/`UNBLOCK` for staff, `UPDATE`; other modules write their own actions), `entity` (string: record kind such as `pos_sale`, `pos_return`, `pos_shift`, `credit_account`, `credit_payment`, `purchase`, `inventory`, `staff`, `shop_setting`, `product_pos`, `orders`, `coupon`), `entityId` (string), `from`, `to` (`yyyy-MM-dd`, shop days, both included; if both given the range is limited to 93 days), `page` (0), `size` (20, max 50). Blank filters are ignored. The exact action list is open-ended **(unsure)**.
- **Response**: 200 page:
```json
{ "items": [ { "id": "uuid", "actorId": "uuid", "actorName": "Asha", "action": "SALE", "entity": "pos_sale", "entityId": "uuid",
  "change": "VSM/2026-27/000042 total=8600", "at": "2026-11-08T12:00:00Z" } ], "page": 0, "size": 20, "total": 1 }
```
`actorName` = name or e-mail, null for system actions; `actorId` nullable; `change` is a free-text description (do not parse).
- **Errors**: 400 `VALIDATION_FAILED` (`to` before `from`; range over 93 days).

---

## Error code quick reference (this document)

| code | status | meaning |
|---|---|---|
| VALIDATION_FAILED | 400 | bad input; see `details.fields` |
| NOT_FOUND | 404 | bill, hold, customer, product, shift, purchase not found |
| OUT_OF_STOCK | 409 | `details.items[{productId, available}]` |
| RETURN_EXCEEDS_SOLD | 409 | more than returnable |
| SHIFT_ALREADY_OPEN | 409 | |
| PLU_TAKEN / BARCODE_TAKEN | 409 | |
| DISCOUNT_NEEDS_APPROVAL | 422 | manager PIN needed or wrong |
| COUPON_INVALID | 422 | `details.reason` |
| SHIFT_NOT_OPEN | 422 | open a shift first |
| VARIANCE_NEEDS_NOTE | 422 | write a reason for the cash difference |
| RETURN_NEEDS_ADMIN | 422 | bill older than the cashier window |
| CREDIT_NOT_ALLOWED / CREDIT_LIMIT_EXCEEDED / CREDIT_OVERPAYMENT / CREDIT_ADJUST_TOO_LARGE | 422 | credit rules |
| UPI_NOT_CONFIGURED | 422 | shop UPI id missing |
| RATE_LIMITED | 429 | too many calls or PIN tries |
| INTERNAL_ERROR | 500 (or in sync results) | retry with the same Idempotency-Key |

## Open points / behaviours the code leaves unclear

- A `null` payment `mode` may cause a 500 instead of a 400 (the allowed-mode check uses an immutable set); never send null modes.
- Offline upload when the shop requires open shifts: refused if the uploader has no open shift at that moment.
- Cost per unit for weighed products in purchases is not defined by the code.
- Staff create on an existing non-admin e-mail converts that account to CASHIER.


---
## Addendum: defective returns, safe retries, small validation changes

### Returning goods claimed as defective (review flow)
A customer saying "it is defective" is a claim, not a fact. Staff look at the goods and decide.

1. **Taking the return.** `POST /api/v1/pos/sales/{id}/returns` lines accept an optional `restock` flag: `{ "saleItemId": "...", "qty": 2, "restock": false }`. `restock` null or `true` = the goods are fine: they go straight back on the shelf (as before). `false` = "claimed defective": the customer is refunded and the credit note is issued as usual, but those units are **not** added to sellable stock; they wait in a review queue with status `PENDING_REVIEW`. A line can be split by sending the same `saleItemId` twice with different flags. The online equivalent is `restock:false` on `POST /api/v1/admin/orders/{id}/returns` (file 20).
2. **Review (staff or admin).** Someone looks at the goods and decides with `POST /api/v1/pos/stock/defective/{id}/review`:
   - `CONFIRM_DEFECTIVE`: it really is defective. The batch moves to the defective list (status `HELD`).
   - `RESTOCK`: it is fine after all. The units are added back to sellable inventory (status `RESTOCKED`).
3. **Final decision (admin only)** on a confirmed-defective (`HELD`) batch: `POST /api/v1/admin/stock/defective/{id}/resolve` with `WRITE_OFF` or `RETURN_TO_SUPPLIER`. Confirmed-defective goods are never added back to sellable stock; if a confirmation was a mistake, correct the stock with the normal stock adjustment.

Statuses: `PENDING_REVIEW` -> (`HELD` | `RESTOCKED`); `HELD` -> (`WRITTEN_OFF` | `RETURNED_TO_SUPPLIER`). Each step can be done once.

### GET /api/v1/pos/stock/defective
- **Who**: cashier or admin (the review queue). **Purpose**: the "returned goods to check" screen at the counter or back office.
- **Request**: query `status` (default `PENDING_REVIEW`; one of `PENDING_REVIEW`, `HELD`, `WRITTEN_OFF`, `RETURNED_TO_SUPPLIER`, `RESTOCKED`; other = 400 `fields.status`), `productId` (optional uuid), `page` (default 0), `size` (default 20, max 50).
- **Response**: `{ items: [ { id, productId, productName, qty, source ("ORDER_RETURN"|"POS_RETURN"), refId (the return it came from), note (reason given at the return), status, createdAt, createdBy, reviewedAt, reviewedBy, reviewNote, resolvedAt, resolvedBy, resolveNote } ], page, size, total }`, newest first.

### POST /api/v1/pos/stock/defective/{id}/review
- **Who**: cashier or admin. **Purpose**: decide a claim (see flow above). A counter staff member cannot `CONFIRM_DEFECTIVE` the batch they took back themselves: that answers `422 DEFECTIVE_SELF_REVIEW`; another person or the admin must confirm it (`RESTOCK` has no such limit).
- **Request**: body `{ "decision": "CONFIRM_DEFECTIVE" | "RESTOCK", "note": string(optional, max 200) }` (decision is case-insensitive; anything else, including `WRITE_OFF`, is 400 `VALIDATION_FAILED` `fields.decision`; note longer than 200 is 400 `fields.note`).
- **Response**: `200` the batch with its new `status` (`HELD` or `RESTOCKED`), `reviewedAt`, `reviewedBy`, `reviewNote`.
- **Errors**: 404 `NOT_FOUND` unknown batch; 409 `DEFECTIVE_ALREADY_REVIEWED` the batch is no longer waiting for review.
- **UI notes**: show the quantity, product, return reason and who took the return; two buttons "Defective" and "Fine, put back on sale"; refresh the queue afterwards.

### GET /api/v1/admin/stock/defective
- **Who**: admin only. **Purpose**: the full report of returned goods and what became of them. Same query and response as `GET /api/v1/pos/stock/defective` but `status` defaults to all.

### GET /api/v1/admin/stock/defective/summary
- **Who**: admin only. **Purpose**: dashboard tile and list of what is open right now.
- **Response**: `[ { productId, productName, heldQty (confirmed defective, waiting for write-off or supplier), pendingReviewQty (claims not yet reviewed), batches } ]`, biggest first.

### POST /api/v1/admin/stock/defective/{id}/resolve
- **Who**: admin only. **Purpose**: the owner's final decision on a confirmed-defective batch.
- **Request**: body `{ "action": "WRITE_OFF" | "RETURN_TO_SUPPLIER", "note": string(optional, max 200) }`. Any other value (including `RESTOCK`: confirmed-defective goods are never put back on sale) is 400 `VALIDATION_FAILED` `fields.action`.
- **Response**: `200` the batch with status `WRITTEN_OFF` or `RETURNED_TO_SUPPLIER`, `resolvedAt`, `resolvedBy`, `resolveNote`.
- **Errors**: 404 `NOT_FOUND`; 409 `DEFECTIVE_NOT_HELD` the batch is not a confirmed-defective one waiting for a decision (still pending review, or already decided).
- Ops metrics: `mall_stock_defective_pending_units` (claims waiting for review) and `mall_stock_defective_units` (confirmed defective, held).

### Optional Idempotency-Key on admin actions
`POST /admin/credit/accounts/{customerId}/adjust` and `POST /admin/stock-takes` accept an optional `Idempotency-Key` header (see the addendum in file 10 for the rules). A repeat of a credit adjustment is not applied again; a repeat of a stock take returns the first call's result and changes nothing. Same key + different request (or different admin) = `409 IDEMPOTENCY_KEY_REUSED`.

### Validation changes
- `POST /pos/sales` without a `cart`, and a payment line without a `mode`, answer 400 `VALIDATION_FAILED` (they used to answer 500). In an offline upload a bill without a `cart` is reported as `REJECTED` with `errorCode` `VALIDATION_FAILED`.
- An offline bill's invoice number uses the financial year of its `soldAt` date (not the upload day), so a bill sold on 31 March and uploaded on 2 April belongs to the old year.


---
## Addendum: offline bills recorded "as rung" (current standard)

A customer who has already paid and left cannot be sent back to pay a different price. So an offline bill is recorded **exactly as the till rang it**; anything that differs from the server's current data becomes an **exception** that a manager reviews. The sale is never lost.

### POST /api/v1/pos/sync/sales (extended)
Each bill in `sales[]` may carry a `rung` snapshot:

```json
{ "idempotencyKey": "uuid", "soldAt": "2026-11-09T04:10:00Z",
  "cart": { "customerMobile": "9000000001" },
  "rung": { "lines": [ { "productId": "uuid", "qty": 2, "unitPricePaise": 12500, "gstPercent": 5, "discountPaise": 0, "name": "Basmati Rice", "linePaise": 25000 } ] },
  "payments": [ { "mode": "CASH", "amountPaise": 25000 } ], "cashTenderedPaise": 30000,
  "manualDiscount": { "amountPaise": 0, "reason": "" } }
```

| field | rules |
|---|---|
| `rung.lines[]` | at least one; each: `productId` (must exist), `qty` > 0 (pieces; **grams for weighed items**), `unitPricePaise` >= 0 (per piece, or per 1000 g for weighed items), `gstPercent` one of 0, 3, 5, 12, 18, 28, `discountPaise` >= 0 and not more than the line (default 0), `name` optional, `linePaise` optional but, if sent, must equal price x qty (weighed: the amount the till charged) |
| `payments` | modes `CASH`, `UPI`, `CARD`; must add up **exactly** to the as-rung total (line amounts less discounts, with the shop's `gst.roundOff` applied). Anything else is refused |
| `cart.customerMobile` | optional: the only part of `cart` that is read when `rung` is present (the customer is linked to the bill; **no coins are earned** on an as-rung bill) |
| `manualDiscount` | optional fixed amount the till already applied; recorded without a manager PIN check and always raises a `DISCOUNT` exception |
| not allowed with `rung` | coupons, coin redemption and CREDIT (udhar) payments cannot be checked offline: `REJECTED` `VALIDATION_FAILED` |

The server stores the sale as an offline sale: `soldAt` is the sale time, the **financial year and invoice number come from `soldAt`** (not the upload day), no open shift is needed, stock is taken best-effort (never fails for short stock), tax (taxable/CGST/SGST) is computed from the till's own prices and GST rates. Without `rung` the old re-pricing path runs (legacy).

**Hard rejections** (`REJECTED`, `errorCode` `VALIDATION_FAILED`, `message` names the field): missing idempotency key; no lines or a line without `productId`; qty missing or <= 0 or too large; unit price missing or negative; `gstPercent` missing or not an allowed rate; unknown product; discount negative or more than the line; `linePaise` not equal to price x qty; manual discount negative or more than the bill; payments not equal to the total or an empty payment line; `soldAt` outside the 7-day window; coupon/coins/credit with `rung`. A duplicate key is `DUPLICATE`. Keep a rejected bill on the device and show it in a "needs attention" list.

**Per-bill result** (existing fields unchanged): `status` (`CREATED` | `DUPLICATE` | `REJECTED`), `saleId`, `invoiceNo`, `errorCode`, `message`, plus new `exceptions: [ { "kind": "...", "detail": "..." } ]` (empty for `REJECTED`; `DUPLICATE` returns the earlier bill's exceptions). Show "recorded, the manager will review" when `exceptions` is not empty.

**Exception kinds** (the bill is still recorded and flagged `needsReview`): `PRICE_CHANGED` (the till's price differs from the current catalog price; a flash-sale price shows as this), `GST_CHANGED`, `PRODUCT_INACTIVE`, `DISCOUNT` (one per discounted line plus one for a manual discount: the manager PIN could not be checked offline), `OVERSOLD` (the shelf had less than was sold).

### GET /api/v1/admin/pos/offline-exceptions
- **Who**: admin only. **Purpose**: the manager's "offline bills to review" list.
- **Request**: query `status` (`OPEN` default, or `REVIEWED`; other = 400), `page` (default 0), `size` (default 20, max 50).
- **Response**: `{ items: [ { id, saleId, invoiceNo, cashierId, cashierName, soldAt, kind, detail, productId, productName, expected, actual, createdAt, reviewedBy, reviewedAt, reviewNote } ], page, size, total }`, newest first. `expected`/`actual` are plain strings: paise for prices and discounts, percent for GST, units for stock.

### POST /api/v1/admin/pos/offline-exceptions/{id}/review
- **Who**: admin only. **Request**: body `{ "note": string(optional, max 500) }`. **Response**: `200` the row with `reviewedBy`, `reviewedAt`, `reviewNote`.
- **Errors**: 404 `NOT_FOUND`; 409 `ALREADY_REVIEWED` (each exception is reviewed once). Writes the audit entry `OFFLINE_EXCEPTION_REVIEWED`.
- **Ops**: metrics `mall_pos_offline_exceptions_open`, `mall_pos_offline_exceptions_stale` (open for more than a day), `mall_pos_offline_exceptions_recorded_total{kind}`; alert `OfflineBillExceptionsUnreviewed`.


---
## Addendum: reports and purchases

- **Refunds on returned online orders in the reports**: `GET /api/v1/admin/dashboard` gains `onlineRefundsTodayPaise`, and the sales summary (`GET /api/v1/admin/reports/summary`) gains `onlineRefundsPaise`: refunds given in the period on returned online orders (paise). Like the counter refunds (`posRefundsTodayPaise` / `posRefundsPaise`) they are **listed apart and not subtracted** from the sales figures: show net sales as sales minus both refund figures if you want a net view.
- **`POST /api/v1/admin/purchases`**: a repeated `Idempotency-Key` returns the first delivery only if the request is the same delivery (supplier, invoice number and lines); the same key with a different delivery answers `409 IDEMPOTENCY_KEY_REUSED`.
- **`POST /api/v1/admin/stock-takes`**: each product's stock row is locked while it is counted, so a sale cannot change the quantity between the read and the adjustment.


---
## Addendum: flash-sale prices at the counter

Counter quotes and sales (`POST /pos/quote`, `POST /pos/sales`) apply a running flash sale exactly like the online quote does: the line is priced at the sale price, a line larger than the units left in the sale is **split** into a sale-price line and a normal-price line, and each line carries `flashSaleId`, `listPricePaise` and `flashDiscountPaise`. Rules specific to the counter: weighed items (grams) are never discounted by a flash sale; a bill with no customer mobile is not subject to the sale's per-customer limit; an offline bill is always recorded (it takes what is left of the sale and the rest is recorded at the till's price, so the cap can be exceeded). Coupons and coins apply on top of the sale price. See `40-flash-sales.md`.


---
## Addendum (2026-11-09 release)

Money is in paise everywhere below. Everything here is read from the code; nothing changes the endpoints' URLs.

### Payment modes are trimmed and upper-cased (sales, return refunds, credit repayments, offline lines)
- **Applies to**: `payments[].mode` of `POST /pos/sales`, `refunds[].mode` of `POST /pos/sales/{id}/returns`, `payments[].mode` of `POST /pos/credit/payments`, and `payments[].mode` of every bill in `POST /pos/sync/sales` (also bills with `rung`).
- **Rule**: the mode is trimmed and upper-cased, so `" cash "` is `CASH`. There are no other aliases. Allowed: `CASH`, `UPI`, `CARD`; `CREDIT` too, but only on a sale or offline bill, never on a refund or a repayment. A missing (null) mode is a 400, not a 500.
- **Error**: `400 VALIDATION_FAILED`. `message` is `payment mode must be CASH, UPI or CARD, or CREDIT on a bill (got "xyz")` for a sale or offline bill, and `payment mode must be CASH, UPI or CARD (got "xyz")` for a refund or repayment. A missing mode says `(got none)`. The field is `details.fields['payments[i].mode']` (`'refunds[i].mode'` for return refunds), `i` = the first bad line (0-based). A null line in the array gives `details.fields['payments[i]']` = `payment line is empty`.
- **Offline sync**: a bad mode rejects only that bill: `status` `REJECTED`, `errorCode` `VALIDATION_FAILED`, `message` = the same text (no `details` per bill).

### GET /api/v1/pos/shop (changed)
- Response gains `roundOffMode`: `NONE` or `NEAREST_RUPEE` (the admin's `gst.roundOff` setting). With `NEAREST_RUPEE` the bill total is rounded to a whole rupee (half a rupee rounds up) and `roundOffPaise` on quotes/bills shows the paise added (negative = taken off). Existing fields unchanged.

### POST /api/v1/pos/quote (changed)
- Request gains optional `manualDiscount` `{ "amountPaise": 500, "reason": "..." }`, the same object a sale takes. It is subtracted after coupon and coin discounts, so the previewed total matches the sale. **Preview only**: no reason and no manager PIN are needed; the reason and PIN are enforced when `POST /pos/sales` is called.
- Response gains `manualDiscountPaise` (0 when none).
- **Errors**: 400 `VALIDATION_FAILED` on `manualDiscount` for a negative amount (`must not be negative`) or one above what is left to pay after coupon and coins (`is more than the bill`).

### POST /api/v1/pos/sales/{id}/returns (changed): refundMode
- Request gains optional `refundMode`: `CASH`, `UPI` or `CARD`, paying out the whole refund in that one mode (trimmed, case-insensitive). `refunds[]` (an explicit split) still works.
- **Default** when neither is sent: the mode of the bill if it was paid in exactly one of CASH/UPI/CARD, otherwise cash. (Before: always cash.)
- **Errors**: 400 `VALIDATION_FAILED` on `refundMode`: sent together with a non-empty `refunds[]` (`cannot be combined with refunds: send one or the other`), or not CASH/UPI/CARD (same mode text as above, `details.fields.refundMode`). If the whole refund only reduces the customer's credit (nothing to pay out) no refund line is made and the mode is not used.

### GET /api/v1/pos/sales/{id}/returns/{returnId}/receipt?width=32|48
- **Who**: both. **Purpose**: the credit note of a return as printable text, same `{ "width": 32, "text": "..." }` shape as the sale receipt (monospace; 32 = 58 mm, 48 = 80 mm; default 32).
- **Content** (`CreditNoteData`): shop name, address, phone, GSTIN; credit note number; the invoice number of the bill; date and time; cashier name; customer mobile (blank for walk-in); reason; returned lines (name, how sold, qty (grams for weighed), GST percent, refund for the line); taxable, CGST, SGST and total refund; how the refund was paid (mode and amount per line); `creditAdjustedPaise` (the part that only reduced the customer's credit, not paid out); shop footer.
- **Errors**: 404 `NOT_FOUND` for an unknown bill or a return that is not on that bill; 400 `VALIDATION_FAILED` (field `width`) for a width other than 32 or 48.

### GET /api/v1/pos/credit/payments/{id}/receipt?width=32|48
- **Who**: both. **Purpose**: the receipt of a credit repayment (the `id` is the payment id returned by `POST /pos/credit/payments`). Same `{ width, text }` shape.
- **Content** (`RepaymentData`): shop name, address, phone, GSTIN; receipt number; date and time; cashier name; customer mobile; amount paid; payment lines (mode and amount); balance before and balance after (what the customer still owes); note; shop footer. The balance after is the one recorded right after this payment, not today's balance.
- **Errors**: 404 `NOT_FOUND` (`Payment`); 400 `VALIDATION_FAILED` (field `width`) for a bad width.

### GET /api/v1/pos/products (changed): search and incremental refresh
- `q` still matches names, and now also a **barcode or an item code (PLU)** typed exactly. The PLU is matched when `q` is 1 to 6 digits, leading zeros ignored. A product with an exact barcode/PLU match is listed first. A blank `q` lists everything.
- New optional `updatedSince`: only products changed at or after it (the product, its counter details or its barcodes). Use it to refresh an offline copy; use `catalogVersion` from `/pos/sync/status` to decide when. **Limitation**: a barcode that was only deleted is not seen by `updatedSince` (only creation times are kept for barcodes). **Accepted formats** (the same parser as every request date, see file 00): `2026-11-08T10:00:00Z` or with an offset `2026-11-08T15:30:00+05:30`; a zone-less date-time `2026-11-08T15:30:00` (India time); a plain date `2026-11-08` (start of that day, India time). Before this release only the first form was accepted and the others gave a bare 400. Anything else is a 400 `VALIDATION_FAILED`. Typical use: keep the largest `updatedAt`/the time you started the last refresh and send it back; for the first full download omit the parameter.
- Each product gains four fields: `packSize` (string, `""` when none), `unit` (string, `""` when none), `variantName` (string, `""` when none) and `displayName`. The same fields are on the product inside `GET /pos/barcode/{code}`.
- **`displayName`** is what to show on the counter: the English `name`, then the variant name, then the pack size, e.g. `Basmati Rice Premium 1 kg`. Rules: words of the variant name that are already in the name (case-insensitive) are not repeated; a pack size already written in the name as a whole phrase is left out; with no variant and no pack size it equals `name`.
- Paged lists carry `totalExact` and `hasMore` (see the paging note below).

### Offline sync: summary of the as-rung rules (details in the section "offline bills recorded as rung")
- `rung` snapshot: `rung.lines[]` with the till's own price, GST and discount, `payments` must add up exactly to the as-rung total; the bill is recorded as rung and never re-priced.
- `exceptions[]` on every per-bill result: `{ "kind": "PRICE_CHANGED" | "GST_CHANGED" | "PRODUCT_INACTIVE" | "DISCOUNT" | "OVERSOLD", "detail": "..." }`; empty when nothing differs or the bill is `REJECTED`. A bill with exceptions is still `CREATED`.
- **Coupons, coin redemption and CREDIT (udhar) are refused offline**: `REJECTED`, `VALIDATION_FAILED` (a coupon or coin redemption: `a coupon or coin redemption cannot be checked offline: ring this bill without it`; credit: `credit (udhar) cannot be given on an offline bill: it needs the customer's account`). This applies with or without `rung`.
- **`SHIFT_NOT_OPEN` never happens on an offline bill**, even when the shop requires an open shift for counter bills: the till rang it in its own earlier shift, so the uploader needs no shift now.
- Admin review: `GET /api/v1/admin/pos/offline-exceptions` (query `status` `OPEN`/`REVIEWED`, `page`, `size`) and `POST /api/v1/admin/pos/offline-exceptions/{id}/review` (body `{ "note": "..." }`, 404 `NOT_FOUND`, 409 `ALREADY_REVIEWED`), documented in the section above.

### Flash-sale prices at the counter
Documented in the addendum "flash-sale prices at the counter" above and in `40-flash-sales.md`: sale-price lines, split lines, `flashSaleId`, `listPricePaise`, `flashDiscountPaise`; grams items never discounted; offline bills record the till's price and show it as a `PRICE_CHANGED` exception.

### GET /api/v1/pos/holds (changed): at most 100 exist
- Returns every held basket, newest first, from every cashier. There is no paging. Since the 101st basket is refused (`POST /pos/holds` answers 409 `HOLD_LIMIT`), the list can no longer hide older baskets. Baskets parked before this rule that already number more than 100 still show only the newest 100 until some are discarded.

### Paging fields: stock movements and audit log
`GET /api/v1/admin/stock-movements` and `GET /api/v1/admin/audit-log` (and the other paged lists) now return `{ "items": [...], "page": 0, "size": 20, "total": 123, "totalExact": true, "hasMore": false }`.
- `total` is exact up to **10,000** rows. Above that, `total` = `10000` and `totalExact` = `false` (show "10,000+").
- `hasMore` is **always exact**: keep paging until it is `false`. Do not compute "last page" from `total` when `totalExact` is `false`.
- No cached totals: the numbers are never out of date. Existing fields are unchanged.

## Addendum: round 2 (2026-11-09 evening)

Everything below is read from the code (PosController, PosService, PaymentRules, ReceiptText). Money is in paise.

### Payment-mode error text depends on the context
Same rule everywhere (trimmed, case-insensitive, no aliases), two texts:
- A **bill** (`POST /pos/sales`, bills in `POST /pos/sync/sales`): `payment mode must be CASH, UPI or CARD, or CREDIT on a bill (got "xyz")`.
- A **refund or repayment** (`refunds[].mode` of a return, `refundMode`, `payments[].mode` of `POST /pos/credit/payments`): `payment mode must be CASH, UPI or CARD (got "xyz")`.
A missing mode ends `(got none)`. So `CREDIT` is valid on a bill and the text for a bill mentions it; on a refund or repayment `CREDIT` is refused with the shorter text. Both are `400 VALIDATION_FAILED`.

### GET /api/v1/pos/shop: roundOffMode
`roundOffMode` is `"NONE"` or `"NEAREST_RUPEE"` (the admin config `gst.roundOff`). With `NEAREST_RUPEE` the payable total is rounded to a whole rupee, 50 paise rounds up; the paise added are `roundOffPaise` (negative = taken off) on `/pos/quote` and on the sale, and `totalPaise` already includes it. Payments must add up to `totalPaise` after rounding. With `NONE`, `roundOffPaise` is 0. Example: items 249.60 -> `roundOffPaise` 40, `totalPaise` 25000; items 249.40 -> `roundOffPaise` -40, `totalPaise` 24900.
```json
{ "shopName": "Vijay Super Mall", "roundOffMode": "NEAREST_RUPEE" }
```

### POST /api/v1/pos/sales/{id}/returns: refundMode
Header `Idempotency-Key` required. Body (`items` required, the rest optional):
```json
{ "items": [ { "saleItemId": "uuid-of-bill-line", "qty": 1, "restock": true } ], "reason": "damaged", "refundMode": "UPI" }
```
- `refundMode` `CASH`, `UPI` or `CARD` (trimmed, case-insensitive) pays the whole refund in that one mode. Or send `refunds` for a split: `"refunds": [ { "mode": "CASH", "amountPaise": 5000 }, { "mode": "UPI", "amountPaise": 4000 } ]`, which must add up to the part that is paid out. Never both (`400`, `fields.refundMode` = `cannot be combined with refunds: send one or the other`).
- Neither sent: the bill's own mode if it was paid in exactly one of CASH/UPI/CARD, otherwise CASH.
- Bad mode: `400` `fields.refundMode` = `payment mode must be CASH, UPI or CARD (got "xyz")`. On a credit bill the refund first cancels what the customer still owes; only the rest is paid out in `refundMode`. If nothing is left to pay out, no refund line is made and the mode is unused.
- Response: the return record with `creditNoteNo`, the refund lines actually paid (`refunds[].mode`, `amountPaise`), `refundTotalPaise` and `creditAdjustedPaise`.

### GET /api/v1/pos/sales/{id}/returns/{returnId}/receipt?width=32|48
Response `{ "width": 32, "text": "..." }`. The text, 32 columns, for a return of 2 soap and 1.5 kg of loose sugar where 20.00 only reduced the customer's credit and 92.50 was paid out by UPI (lines end with a newline; amounts are right-aligned; weighed items show kg):
```
        Vijay Super Mall
       Market Road, Surat
         Ph: 9876543210
     GSTIN: 24ABCDE1234F1Z5
--------------------------------
CREDIT NOTE
CN: VSM-CN/2026-27/000003
Orig Inv: VSM/2026-27/000007
27-10-2026 10:30
Cashier: Asha  Cust: 9000000001
--------------------------------
Soap
  Returned 2 (12%)         45.00
Loose Sugar
  Returned 1.500 kg (5%)   67.50
--------------------------------
Taxable                   100.00
CGST                        6.00
SGST                        6.50
--------------------------------
Refund total              112.50
Credit reduced             20.00
Refund UPI                 92.50
Reason: damaged
--------------------------------
           Thank you!
```
Parts that are empty are left out: `Ph:`, `GSTIN:`, `Cust:` (walk-in), `Reason:`, and `Credit reduced` (only shown when part of the refund only reduced credit). One `Refund <MODE>` line per paid mode. At width 48 the same layout is wider (lines up to 48 characters). Bad width: `400` `fields.width` = `width must be 32 or 48`; unknown bill or return: `404 NOT_FOUND`.

### GET /api/v1/pos/credit/payments/{id}/receipt?width=32|48
`id` is the payment id from `POST /pos/credit/payments`. Response `{ "width": 32, "text": "..." }`; sample for a 30.00 repayment (10.00 cash + 20.00 UPI) that took the balance from 50.00 to 20.00:
```
        Vijay Super Mall
       Market Road, Surat
         Ph: 9876543210
     GSTIN: 24ABCDE1234F1Z5
--------------------------------
CREDIT PAYMENT RECEIPT
Receipt: VSM-CR/2026-27/000002
27-10-2026 10:30
Cashier: Asha
Customer: 9000000001
--------------------------------
Balance before             50.00
Paid CASH                  10.00
Paid UPI                   20.00
Amount paid                30.00
Balance after              20.00
Note: part payment
--------------------------------
           Thank you!
```
`Note:` appears only when a note was sent. Balance before/after are the balances right before and after this payment (not today's). Errors: `404 NOT_FOUND` (`Payment`), `400` `fields.width`.

### POST /api/v1/pos/credit/payments
Request and response are in the section above (the response `id` is the id for the receipt URL). Its UI note "no receipt-text endpoint" is out of date: use the receipt endpoint above for the printable text.

### GET /api/v1/pos/products: updatedSince and the four extra fields
- **updatedSince** accepts `2026-11-08T10:00:00Z`, `2026-11-08T15:30:00+05:30`, `2026-11-08T15:30:00` (India time) and `2026-11-08` (start of that day, India time); anything else is `400`. Details in the 2026-11-09 addendum above.
- Each product has `packSize` (`""` when none), `unit` (`""` when none), `variantName` (`""` when none) and `displayName`:
```json
{ "productId": "uuid", "name": "Basmati Rice Premium", "packSize": "1 kg", "unit": "kg", "variantName": "Premium", "displayName": "Basmati Rice Premium 1 kg", "pricePaise": 14000 }
```
`displayName` = English `name`, then the variant name, then the pack size, with repeats removed (variant words already in the name are not repeated, a pack size already written in the name is left out); with no variant and no pack size it equals `name`. Use it as the line title at the counter and on the bill preview.

### GET /api/v1/admin/stock/low
Plain array, unchanged. Cap 200 rows (`limit` 1..200, default 50), sorted by `qty` ascending. See the report section above.

## Addendum: input limits (counter, credit, purchasing)
Every limit below is also in the Swagger schema. A value outside it answers `400 VALIDATION_FAILED` with the field in `details.fields` (the field name is the JSON path, for example `cart.items[0].qty`), so a huge number never reaches the business rules.

| input | limit |
|---|---|
| Basket line `qty` (quote, sale, hold) | 1 to 9999 pieces, 1 to 100000 grams for a weighed item (a hold names the bound: "must be 1 to 9999") |
| Held basket | label at most 60 characters, at most 50 lines, at most 100 baskets in all (409 `HOLD_LIMIT`) |
| Payment / refund / repayment `amountPaise` | 0 to 100,000,000 paise (and the usual rules: more than 0 where the guide says so) |
| `cashTenderedPaise` | at most 100,000,000 paise |
| Manual discount `amountPaise` | 0 to 100,000,000 paise; `reason` at most 200 characters |
| Return line `qty` | 1 to 100000 (grams for a weighed item); `reason` at most 200 characters |
| Credit limit (`limitPaise`) | 0 to 100,000,000 paise |
| Credit adjust `amountPaise` | -100,000,000 to 100,000,000 paise, not 0 (`fields.amountPaise` "must be at most 100000000" / "must be at least -100000000") |
| Credit adjust / repayment `note` | at most 200 characters |
| Purchase `supplierName` | 1 to 120 characters |
| Purchase `invoiceNo` / `note` | at most 60 / 200 characters |
| Purchase line | `qty` 1 to 1,000,000; `unitCostPaise` 0 to 100,000,000; at most 200 lines |
| Staff `name` / `mobile` | 1 to 80 characters / exactly 10 digits |
| Search `q` (`GET /pos/products`, `GET /admin/customers`, `GET /admin/orders`) | at most 100 characters (`fields.q` "must be at most 100 characters") |

Supplier invoice numbers: recording the same `invoiceNo` twice for one supplier is allowed, and the answer carries a `warnings` entry (see Addendum 5).

## Addendum 5 (duplicate invoice warning, held basket expiry)

### POST /api/v1/admin/purchases: `warnings` (new response field)
The delivery is always recorded (legitimate re-entries exist), but when an earlier delivery has the same supplier and the same `invoiceNo` the 201 body carries a text in the new `warnings` array, for example `"invoice number ST-2231 was already recorded for this supplier on 2026-11-01 (purchase 5f0c...)"`. The date is the shop-time day of the earlier delivery (the oldest one if there are several) and the id is that delivery. `warnings` is `[]` otherwise, and always `[]` in `GET /admin/purchases` and `GET /admin/purchases/{id}`. Supplier and invoice number are compared after trimming and ignoring upper/lower case (`" st-2231 "` equals `ST-2231`); a blank invoice number never warns. A repeat of the same request (same `Idempotency-Key`) returns the same body, warning included. Show the warning as a confirmation banner ("Same invoice entered on 1 Nov: check you are not counting this delivery twice") with a link to that purchase; do not block the user or undo the delivery.

### Held baskets expire
- Parked baskets older than `app.pos.hold-expiry-days` (environment variable `HOLD_EXPIRY_DAYS`, default 7, `0` = never expire) are deleted automatically every night at 03:00 shop time (Asia/Kolkata), so forgotten baskets cannot fill the 100-basket limit. The clean-up writes an audit entry (`HOLD_EXPIRED`) with the number removed, logs it and counts it in the metric `mall.pos.holds.expired`.
- `expiresAt` (new, in every hold object of `POST /pos/holds`, `GET /pos/holds` and `GET /pos/holds/{id}`) is the moment the basket becomes eligible for removal (`createdAt` plus the expiry days, removed at the next 03:00 run after that); `null` when baskets never expire. Show "expires in N days" and warn when a basket is close to expiring.
- The 409 `HOLD_LIMIT` message now ends with "Parked baskets are removed automatically after N days" and `details.expiryDays` holds N (0 when baskets never expire, in which case the sentence is left out). A basket removed this way answers 404 on `GET /pos/holds/{id}` like a discarded one, so a till that tries to resume an old basket should refresh its list.

## Addendum 6 (credit: search, picker, blocked customers; dashboard)

**`GET /admin/credit/accounts`** (ADMIN) new query parameters: `q` (part of the name or mobile number, any case; a typed `%` or `_` is not a wildcard; at most 100 characters), `eligibleOnly=true` (only customers whose limit is above 0). Existing `outstandingOnly` (default `true`; send `false` to include customers who owe nothing), `page`, `size` stay. Each row now also has `availablePaise` (limit minus balance, never below 0) and `blocked`. `total` is exact up to 10,000 (`totalExact`, `hasMore`).

**`GET /pos/credit/customers`** (CASHIER and ADMIN, read only): the till's credit picker. Query: `q` (name or mobile part), `all` (default `false`), `page`, `size`. By default only customers with a credit limit above 0 are listed, by name. `all=true` also lists customers without a limit (then `q` needs at least 2 characters, else `400 fields.q`). Row: `customerId`, `name`, `maskedMobile` (`98XXXXXX10`), `limitPaise`, `balancePaise`, `availablePaise`, `blocked`. Pick one and call `GET /pos/credit/account?mobile=` for the full number. Switching credit on or changing a limit stays admin-only (`PUT /admin/credit/limit` answers 403 for a cashier); there is no manager-PIN path.

**Credit sale errors (422)**, now different codes: `CREDIT_NOT_ALLOWED` "Credit is not enabled for this customer" (no limit set) and `CUSTOMER_BLOCKED` "This customer is blocked, so credit cannot be given" (blocked account). `CREDIT_LIMIT_EXCEEDED` is unchanged. A customer with no limit AND blocked gets `CREDIT_NOT_ALLOWED`. This also applies to offline-sync `REJECTED` rows (`errorCode`). The account view (`GET /pos/credit/account`, `GET /admin/credit/accounts/{id}`) has a new `blocked` flag: show a "blocked" badge instead of offering credit.

**`POST /pos/sales/{id}/returns` with no items:** `400` with `fields.items` = `choose at least one item to return` (it used to be `must not be empty`).

**`GET /admin/dashboard`** has a new `needsAction` number next to `newOrders`: online orders in PLACED, CONFIRMED, PACKED, OUT_FOR_DELIVERY or READY_FOR_PICKUP (includes `newOrders`); cached about 30 seconds.
