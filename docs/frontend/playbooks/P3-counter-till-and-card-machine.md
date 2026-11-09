# P3. Counter till and card machine: implementation playbook

Audience: a developer or LLM coding agent who must build the cashier till (billing counter) app WITHOUT reading backend code. Everything below was checked against the backend code (controllers, services, validators, migrations) and the integration tests (`PosFlowIT`, `PosOfflineSyncIT`, `PaytmTerminalIT`, `PosDiscountIT`, `PosShiftIT`, `PosRoundOffIT`, `CreditFlowIT`, `FlashSaleIT`). Where the older guides (`00-overview.md`, `20-...`, `30-pos-reports-purchasing.md`, `73-paytm-terminal.md`) disagreed with the code, the code won; the differences are listed in the last section and have been fixed in those guides (ticked off in `README.md`). The card machine is ONE flow for every vendor (Paytm, manual bank machine, ...): see F6, `71-bank-and-settlements.md` and `73-paytm-terminal.md`.

Conventions used everywhere (from `00-overview.md`, confirmed in code):

- Base path `/api/v1`. JSON, camelCase. `Authorization: Bearer <accessToken>` on every call. The cashier signs in with e-mail/mobile OTP (dev: cashier `cashier@vijaysupermall.local`, fixed OTP `12345`). Roles allowed on `/pos/**`: CASHIER and ADMIN. A CUSTOMER token gets 403, no token gets 401.
- Money is an integer in **paise**. Never use floating point. Show `Rs 125.00` for `12500`. Largest single money input: `100000000` paise (Rs 10 lakh) for payment lines, cash tendered, manual discount, card amount.
- Error body: `{ "code", "message", "traceId", "details": { "fields": { "<field>": "<text>" } } }`. Switch on `code`; show `message`; put `details.fields[x]` under input `x`. Never parse message texts for logic.
- Lists that page return `{ items, page, size, total, totalExact, hasMore }`. `page` starts at 0, `size` is clamped to 1..50.
- `Idempotency-Key` header: required on `POST /pos/sales`, `POST /pos/sales/{id}/returns`, `POST /pos/credit/payments`, and (in practice) the Paytm-only `POST /pos/terminal-payments`. The generic card-machine start `POST /pos/terminal/payments` de-duplicates on its `reference` instead (the header is optional there, see F6). Generate a fresh UUID per cashier action (the tap on Pay), persist it with the action, reuse it on every retry. A repeat returns the stored result (HTTP 201 again) and does no work, even if the body differs (counter sales, returns and repayments look the key up and never compare bodies; this is different from admin purchases, which answer 409 `IDEMPOTENCY_KEY_REUSED`). Missing header: 400 `VALIDATION_FAILED`, `details.fields["Idempotency-Key"]`.
- Times in responses are UTC ISO-8601. Shop clock is India time (Asia/Kolkata).

## Table of contents

1. [Till screen map](#till-screen-map)
2. [F0 Sign-in and bootstrap](#f0-sign-in-and-bootstrap)
3. [F1 Shift open, close and cash-up](#f1-shift-open-close-and-cash-up)
4. [F2 Catalog sync for the till and product lookup](#f2-catalog-sync-for-the-till-and-product-lookup)
5. [F3 Cart and quote at the counter](#f3-cart-and-quote-at-the-counter)
6. [F4 Payment lines and completing the sale](#f4-payment-lines-and-completing-the-sale)
7. [F5 Credit (udhar) sales and the credit picker](#f5-credit-udhar-sales-and-the-credit-picker)
8. [F6 Card and UPI on a card machine (one flow for every vendor, Paytm wireless included)](#f6-card-and-upi-on-a-card-machine-one-flow-for-every-vendor-paytm-wireless-included)
9. [F7 Receipts and printing](#f7-receipts-and-printing)
10. [F8 Holds (parked baskets)](#f8-holds-parked-baskets)
11. [F9 Returns at the counter](#f9-returns-at-the-counter)
12. [F10 Credit repayments](#f10-credit-repayments)
13. [F11 Offline billing and sync](#f11-offline-billing-and-sync)
14. [F12 Flash sale prices at the counter](#f12-flash-sale-prices-at-the-counter)
15. [F13 Reports, lookups and audit-type screens a cashier can use](#f13-reports-lookups-and-audit-type-screens-a-cashier-can-use)
16. [Error code cheat sheet](#error-code-cheat-sheet)
17. [Doc vs code differences](#doc-vs-code-differences)

---

## Till screen map

| # | Screen | Endpoints |
|---|---|---|
| S1 | Sign-in | `POST /auth/otp/send`, `POST /auth/otp/verify`, `POST /auth/refresh`, `POST /auth/logout` (see `00-overview.md`) |
| S2 | Start-up / bootstrap | `GET /pos/shop`, `GET /pos/sync/status`, `GET /pos/shifts/current` |
| S3 | Open shift | `POST /pos/shifts/open` |
| S4 | Billing (scan, search, basket, customer, discount) | `GET /pos/barcode/{code}`, `GET /pos/products`, `POST /pos/quote`, `GET /pos/credit/account` |
| S5 | Payment (split lines, change, UPI QR, channel choice per mode) | `GET /pos/payment-channels`, `POST /pos/sales`, `GET /pos/upi-link` |
| S6 | Card machine dialog (any vendor: channel chosen on S5) | `POST /pos/terminal/payments`, `GET /pos/terminal/payments/{id}`, `POST /pos/terminal/payments/{id}/confirm` (manual machines), `POST /pos/terminal/payments/{id}/cancel`, `POST /pos/terminal/payments/{id}/void` (Paytm). Old Paytm-only twins `GET /pos/terminals`, `/pos/terminal-payments...` still work on the same rows |
| S7 | Credit customer picker | `GET /pos/credit/customers`, `GET /pos/credit/account`, `GET /pos/credit/ledger` |
| S8 | Receipt / print preview | `GET /pos/sales/{id}/receipt`, `GET /pos/sales/{id}/returns/{returnId}/receipt`, `GET /pos/credit/payments/{id}/receipt` |
| S9 | Held baskets | `POST /pos/holds`, `GET /pos/holds`, `GET /pos/holds/{id}`, `DELETE /pos/holds/{id}` |
| S10 | Bill search | `GET /pos/sales?invoiceNo=` / `?mobile=` / `?date=`, `GET /pos/sales/{id}` |
| S11 | Return | `GET /pos/sales/{id}`, `POST /pos/sales/{id}/returns/preview`, `POST /pos/sales/{id}/returns`, `GET /pos/sales/{id}/returns` |
| S12 | Returned goods to check (defective queue) | `GET /pos/stock/defective`, `POST /pos/stock/defective/{id}/review` |
| S13 | Credit repayment | `GET /pos/credit/account`, `POST /pos/credit/payments`, `GET /pos/credit/payments/{id}/receipt` |
| S14 | Cash-up and close shift | `GET /pos/reports/day`, `GET /pos/shifts/current`, `POST /pos/shifts/close` |
| S15 | Offline queue / sync | `GET /pos/sync/status`, `POST /pos/sync/sales`, `GET /pos/products?updatedSince=` |
| S16 | Manager PIN prompt (modal) | no endpoint: the PIN is sent inside `manualDiscount.approverPin` of `POST /pos/sales` |
| S17 | Order desk (out of scope here) | `/admin/orders/**` and `/admin/flash-sales/**` are also open to CASHIER; see files 20 and 40 |

Not available to a cashier (403): `/admin/reports/**`, `/admin/audit-log`, `/admin/pos/offline-exceptions`, `/admin/pos/shifts`, `/admin/config`, `/admin/payment-terminals`, `/admin/credit/**`, `/admin/me/approval-pin`.

---

## F0 Sign-in and bootstrap

1. **Goal.** Get a token and load what the till needs before the first bill.
2. **Who/settings.** CASHIER or ADMIN. A blocked staff member is refused on the next request (401/403).
3. **Screens/fields.** Standard OTP screen (file 00). Bootstrap has no inputs.
4. **API sequence.**
   1. Sign in (file 00 section 2). On 401 anywhere: `POST /auth/refresh` once, retry once, else back to sign-in.
   2. `GET /pos/shop` -> `ShopView`: `shopName, address, phone, gstin, stateCode, upiId, invoicePrefix, receiptFooter, weighedPrefix, weighedMode (WEIGHT_GRAMS|PRICE_PAISE), promise, details, roundOffMode (NONE|NEAREST_RUPEE)`. Cache it (needed offline).
   3. `GET /pos/sync/status` -> `{ serverTime, catalogVersion, maxOfflineDays: 7, batchLimit: 50 }`. Compare `serverTime` with the device clock; warn if they differ by more than a few minutes (offline `soldAt` must be within 5 minutes of the future and 7 days of the past).
   4. `GET /pos/shifts/current`: 200 = shift open; 404 `NOT_FOUND` = show the open-shift screen.
5. **Derived values.** `roundOffMode` is the only way the till learns whether rounding is on. The setting `pos.requireOpenShift` is NOT exposed to a cashier: the till learns of it only when a sale answers 422 `SHIFT_NOT_OPEN`.
6. **Edge cases.** Shop details change rarely; re-read `/pos/shop` on every app start and after reconnect.
7. **Dart.**
```dart
class ShopView { String shopName, address, phone, gstin, stateCode, upiId, invoicePrefix, receiptFooter, weighedPrefix, promise, details, roundOffMode; String weighedMode; }
class SyncStatus { DateTime serverTime, catalogVersion; int maxOfflineDays, batchLimit; }
```
8. **Acceptance.**
   - Given a CUSTOMER token, when `GET /pos/shop`, then 403 and the app shows "not allowed".
   - Given a cashier with no open shift, when the app starts, then `GET /pos/shifts/current` is 404 and the open-shift screen is shown.

---

## F1 Shift open, close and cash-up

1. **Goal.** Count the drawer in, bill, count it out; keep the variance.
2. **Who/settings.** Each cashier opens/closes only their own shift. Config `pos.requireOpenShift` (default false): when on, `POST /pos/sales` is refused with 422 `SHIFT_NOT_OPEN` ("Open your shift (count the drawer) before billing") until a shift is open. Returns, credit repayments and offline uploads are never gated by shifts. Admin lists everyone's shifts at `GET /admin/pos/shifts` (ADMIN only).
3. **Screens and fields.**

| Field | Type | Required | Validation |
|---|---|---|---|
| openingFloatPaise (open) | long | yes | >= 0 (negative: 400) |
| countedCashPaise (close) | long | yes | >= 0 |
| note (close) | string | only if counted != expected | trimmed, max 200 chars (400 `note`) |

4. **API sequence.**
   1. `POST /pos/shifts/open` `{ "openingFloatPaise": 100000 }` -> 201 `ShiftView`. 409 `SHIFT_ALREADY_OPEN` ("You already have an open shift: close it first"): go to the current shift instead.
   2. While open: `GET /pos/shifts/current` -> `{ id, cashierId, openedAt, openingFloatPaise, closedAt: null, expectedCashPaise, countedCashPaise: null, variancePaise: null, note, open: true }`. `expectedCashPaise` is live.
   3. Close: show the drawer count screen. Do NOT show `expectedCashPaise` before the cashier types the count if the shop wants blind counts (the field is available, the choice is yours).
   4. `POST /pos/shifts/close` `{ "countedCashPaise": 99500, "note": "gave Rs 5 change by mistake" }` -> 200 `ShiftView` with `open:false`, `expectedCashPaise`, `variancePaise` (counted minus expected, negative = short). Errors: 422 `VARIANCE_NEEDS_NOTE` (`details.expectedCashPaise`, `details.countedCashPaise`; message "The drawer is short by Rs 5.00: write the reason") -> show a note field and resend; 404 `NOT_FOUND` no open shift.
5. **Derived values (server only).** `expected = openingFloat + CASH paid on this cashier's bills since openedAt - CASH refunded + CASH credit repayments taken`. Bills are counted by their stored sale time (`createdAt`, for an offline bill its `soldAt`). Change given back is not in "CASH paid" (only the cash share of the bill counts). The app must never compute expected cash.
6. **Edge cases.** Two open taps at once: one gets 409. App killed with a shift open: next start `current` returns it. Upload the offline queue BEFORE closing the shift (offline bills older than the shift open time are not in this shift's expected cash). Cashier forgets to close: next day `open` answers 409 until closed.
7. **Dart.**
```dart
class ShiftView { String id, cashierId; DateTime openedAt; int openingFloatPaise; DateTime? closedAt;
  int expectedCashPaise; int? countedCashPaise, variancePaise; String? note; bool open; }
```
8. **Acceptance.**
   - Given an open shift with float 100000 and one cash bill of 12500, when `current`, then `expectedCashPaise` = 112500.
   - Given counted 500 short and no note, when close, then 422 `VARIANCE_NEEDS_NOTE` and after resending with a note, 200 with `variancePaise` -500 and `open:false`.
   - Given an open shift, when open again, then 409 `SHIFT_ALREADY_OPEN`.

---

## F2 Catalog sync for the till and product lookup

1. **Goal.** Find products by scan, PLU, name; keep an offline copy fresh.
2. **Who/settings.** CASHIER/ADMIN. Admin sets per-product counter attributes (`soldBy`, `plu`, `gstPercent`, `hsn`, barcodes) at `PUT /admin/products/{id}/pos`. Weighed products are counter-only. Shop `weighedPrefix` (1-2 digits, default 2) and `weighedMode` come from `/pos/shop`.
3. **Screens and fields.**

| Field | Type | Required | Validation |
|---|---|---|---|
| scan input `code` (path) | string | yes | URL-encode it. Empty -> 400 `code` "code is empty" |
| q (search) | string | no | max 100 chars (400 `q`); matches English/Gujarati name "contains" (case-insensitive), plus an exact barcode or PLU (digits 1-6, leading zeros ignored) listed first |
| page / size | int | no | page >= 0; size clamped 1..50 (default 20) |
| updatedSince | instant | no | `2026-11-08T10:00:00Z`, `+05:30` offset, zone-less date-time (India time), or plain date (start of day India time); garbage -> 400 |

4. **API sequence.**
   1. **Scan:** `GET /pos/barcode/{code}` -> `{ product: PosProduct, qty, linePaise }`.
      - Pack barcode (4-32 letters/digits/dashes registered on a product): `qty` 1.
      - Weighed scale label (13 digits, starts with `weighedPrefix`, valid EAN-13): `qty` = **grams**; with `weighedMode=PRICE_PAISE` the server converts the price on the label to grams. `linePaise` = round-half-up(`pricePaise` x grams / 1000).
      - A saved barcode that happens to start with the scale prefix is treated as a plain pack code.
      - Add `qty` of `product.productId` to the basket (merge into an existing row if the product is already there).
      - Errors: 404 `NOT_FOUND` ("Barcode" / "Item code" / "Product", inactive products too) -> open name search; 400 `VALIDATION_FAILED` field `code`: "weighed item: enter the weight" (a WEIGHT product scanned by pack code: show a grams keypad), "this item code is not a weighed item", "weight on the label is out of range" (grams must be 1..100000), "Invalid check digit on weighed label".
   2. **Search:** `GET /pos/products?q=bas&page=0&size=20` -> Page of `PosProduct`, sorted by English name. `q=<exact barcode>` or `q=101` (PLU) returns that product first. Only ACTIVE products are returned.
   3. **Weighed items typed by hand:** quantity entered in kg must be converted to integer grams (1.5 kg = 1500). Allowed 1..100000 grams. Counted items: 1..9999.
   4. **Offline copy / incremental sync:**
      1. First install or after "catalogVersion" jump: page `GET /pos/products?page=N&size=50` until `hasMore` is false (there is no bulk endpoint).
      2. Save `catalogVersion` from `GET /pos/sync/status` BEFORE the download starts.
      3. Later: poll `/pos/sync/status`. If `catalogVersion` is newer than saved, run `GET /pos/products?updatedSince=<saved catalogVersion>&size=50` over all pages and upsert by `productId`. Then store the new version. `updatedSince` is inclusive (`>=`), so overlaps are harmless.
      4. `updatedSince` also catches changes to counter attributes and newly added barcodes. It does NOT report deleted barcodes, and it never returns a product that was switched off (only active products are listed). So do a full re-download at least once a day (or when the cashier presses "refresh catalog") and drop local rows that are missing from it.
5. **Derived values.** `PosProduct.displayName` ("Basmati Rice Premium 1 kg" = name + variant + pack size, no repeated words): show this in the basket. `pricePaise` is the LIST price (per piece, or per 1000 g for weighed); flash sale prices are applied by the server at quote time (see F12). `gstPercent` is GST already inside the price. The app never computes tax, discounts or round-off for an online bill.
6. **Edge cases.** Two products with the same name differ by `packSize`/`barcodes`: always key by `productId`. `qtyOnHand` is informational (weighed products count grams); an online sale of more than the stock is refused with 409 `OUT_OF_STOCK` at sale time. Scan twice quickly: merge (increase qty), the server also merges duplicate lines. Empty search: show "No product, try another word"; `total` 0.
7. **Dart.**
```dart
class PosProduct { String productId, name, nameGu, hsn, plu, imageUrl, packSize, unit, variantName, displayName;
  int pricePaise, mrpPaise, gstPercent, qtyOnHand; String soldBy; /* UNIT | WEIGHT */
  bool posOnly, coinRedeemable, earnsCoins; List<String> barcodes; }
class Scan { PosProduct product; int qty; int linePaise; }
class PageOf<T> { List<T> items; int page, size, total; bool totalExact, hasMore; }
```
8. **Acceptance.**
   - Given barcode of "Basmati Rice 1 kg" (Rs 125), when scanned, then `qty` 1 and `linePaise` 12500.
   - Given label `2 000101 01500 c` for Loose Sugar at Rs 45/kg in grams mode, when scanned, then `qty` 1500 and `linePaise` 6750.
   - Given an unknown barcode, when scanned, then 404 and the search box opens.
   - Given `updatedSince` in the future, when listing, then `total` 0.
   - Given a barcode added to a product after time T, when `updatedSince=T`, then that product is returned.

---

## F3 Cart and quote at the counter

1. **Goal.** Price the basket exactly as the sale will, show GST per line, discounts, coins, round-off.
2. **Who/settings.** Cashier. Settings that change the maths: `gst.roundOff` (NONE | NEAREST_RUPEE), `pos.cashierDiscountPercent` (0-100, default 0 = every manual discount needs a manager PIN), coin settings, coupon rules, running flash sales.
3. **Screens and fields.**

| Field | Type | Required | Validation |
|---|---|---|---|
| items[].productId | uuid | yes | active product, else 400 `productId` "product is not available" |
| items[].qty | int | yes | > 0 (400 `qty`); max 9999 pieces or 100000 grams for weighed (400 "quantity too large for <name>") |
| customerMobile | string | only with coupon/coins/credit | spaces, dashes and a +91 prefix are stripped; must then be 10 digits starting 6-9 (400 `customerMobile` "enter a 10 digit mobile number") |
| couponCode | string | no | trimmed and upper-cased by server; needs customerMobile (400 `customerMobile` "customer mobile is needed to use a coupon or coins") |
| coinMode | NONE / ALL / PERCENT | no | needs customerMobile unless NONE |
| percent | int 0..100 | only for PERCENT | percent of the allowed maximum |
| manualDiscount.amountPaise | long | no | 0 = ignored; >= 0; <= what the bill still costs after coupon and coins (400 `manualDiscount` "must not be negative" / "is more than the bill") |
| manualDiscount.reason | string | at SALE time, if amount > 0 | trimmed, 1..100 chars (the annotation says 200, the service enforces 100; send at most 100). Not needed for quote |
| manualDiscount.approverPin | string | at SALE time if cashier is over limit | max 20 chars; the manager's 4-8 digit PIN |

4. **API sequence.**
   1. Keep a local basket of `{productId, qty}` (+ customer mobile, coupon, coin choice, manual discount). Show prices from `PosProduct` for responsiveness only.
   2. After EVERY change (add/remove/qty, mobile, coupon, coin mode, discount): `POST /pos/quote`
```json
{ "items": [ { "productId": "<rice>", "qty": 10 } ],
  "customerMobile": "9876543210", "couponCode": "FESTIVE50", "coinMode": "NONE",
  "manualDiscount": { "amountPaise": 1000 } }
```
      Debounce 250 ms and drop answers of superseded requests. Response `BillView`:
```json
{ "lines": [ { "productId": "...", "name": "Basmati Rice", "soldBy": "UNIT", "qty": 10, "unitPricePaise": 12500,
               "linePaise": 125000, "discountPaise": 5000, "netPaise": 120000, "gstPercent": 5 } ],
  "subtotalPaise": 125000, "couponCode": "FESTIVE50", "couponDiscountPaise": 5000,
  "coinsUsed": 0, "coinDiscountPaise": 0, "totalPaise": 120000,
  "taxablePaise": 114286, "cgstPaise": 1857, "sgstPaise": 1857,
  "coinsToEarn": 12, "customerCoinBalance": 0, "warnings": [], "roundOffPaise": 0, "manualDiscountPaise": 0 }
```
      (Example numbers: 2 x Rs 125 rice at 5% GST quotes `totalPaise 25000, taxablePaise 23810, cgstPaise 595, sgstPaise 595`; with a manual discount of 1000 `totalPaise` is 24000. The 10-rice coupon example is from `PosFlowIT`: `couponDiscountPaise 5000, totalPaise 120000, coinsToEarn 12`.)
   3. Render: one row per `lines[]` entry (see below), subtotal, "Coupon -", "Coins -", "Discount -", "Round off +/-", big TOTAL = `totalPaise`, GST split (taxable, CGST, SGST), "Coins you will earn: N", every string in `warnings` (e.g. "Coins cannot be used together with coupon X", "Some items cannot be paid with coins").
   4. **Manual discount flow.** Cashier types amount; call quote with it (no reason or PIN needed to preview). At payment the app sends reason and, if needed, PIN:
      - The server decides who needs a PIN: ADMIN token: never. CASHIER: no PIN only if `amount*100 <= subtotal * pos.cashierDiscountPercent` (default 0%: always needs PIN).
      - First try WITHOUT a PIN. On 422 `DISCOUNT_NEEDS_APPROVAL` ("A manager's approval PIN is needed for this discount" or "That approval PIN is not valid") open the manager-PIN modal (4-8 digits, any admin's PIN), resend the SAME sale request with `approverPin` and the SAME Idempotency-Key (nothing was stored). The server stores who approved.
      - PIN guesses are limited to 5 per 10 minutes per cashier: then 429 `RATE_LIMITED`; show "Too many tries, ask the manager to sign in".
   5. **Customer mobile.** Ask only when the cashier wants coupon, coins, to earn coins, or credit. An unknown number is fine: the server creates a counter customer on sale (quote with an unknown number works, with no coin balance).
   6. **Coupon applied at bill time.** The discount falls ONLY on the lines the coupon covers (category/item coupons); the minimum order is checked against the covered lines. The quote shows it per line in `lines[].discountPaise`, and GST of covered lines drops accordingly. 422 `COUPON_INVALID` with `details.reason`: `NOT_FOUND`, `EXPIRED`, `MIN_ORDER` (`details.minOrderPaise`, `details.shortfallPaise`: show "Add Rs X more"), `CATEGORY`, `ITEM`, `FLASH_ITEMS`, `LIMIT_REACHED`, `FIRST_ORDER_ONLY`. Show `message`; keep the basket and clear only the coupon field.
   7. **Coins.** `coinMode: ALL` spends the allowed maximum of the customer's balance, `PERCENT` + `percent`. The server returns `coinsUsed`, `coinDiscountPaise`. A coupon that does not work with coins blocks them and adds a `warnings` entry.
   8. **Round-off.** If `/pos/shop.roundOffMode == NEAREST_RUPEE`, `totalPaise` already includes `roundOffPaise` (can be negative; half a rupee rounds up). Payments must add up to `totalPaise`.
   9. **Re-quote rule.** Any change of basket, customer, coupon, coins or discount invalidates the last quote. The Pay button must be disabled until the latest quote has returned. Hold the quote's `totalPaise` as the amount to collect; never compute totals on the device.
5. **Derived values (never compute on the device).** Line GST (`taxable = round(net*100/(100+gst))`, CGST = floor(tax/2), SGST = rest), the discount order (coupon first on covered lines, then coins, then the manual discount, each spread over the remaining line amounts so paise add up), weighed line prices, coins to earn, round-off. Server may return MORE or FEWER lines than the basket rows: duplicate products are merged, and a flash sale splits one row into a sale-price line and a list-price line (F12). Never map `lines[i]` to basket row `i`; show the response lines.
6. **Edge cases.** Empty basket: `items` must be non-empty (400), do not call quote. Product switched off meanwhile: 400 `productId`; remove the row. Network lost during quote: keep the old total but disable Pay and show "Price not confirmed". Double tap: harmless (quote changes nothing). Quote total can change between quote and sale (price edit by admin, flash sale ending): the sale answers 400 "payments add up to X but the bill is Y"; re-quote and re-collect.
7. **Dart.**
```dart
class Item { String productId; int qty; }
class Cart { List<Item> items; String? customerMobile, couponCode; String? coinMode; int? percent; }
class ManualDiscount { int amountPaise; String? reason, approverPin; }
class BillLine { String productId, name, soldBy; int qty, unitPricePaise, linePaise, discountPaise, netPaise, gstPercent; }
class BillView { List<BillLine> lines; int subtotalPaise, couponDiscountPaise, coinsUsed, coinDiscountPaise, totalPaise,
  taxablePaise, cgstPaise, sgstPaise, coinsToEarn, customerCoinBalance, roundOffPaise, manualDiscountPaise;
  String? couponCode; List<String> warnings; }
```
8. **Acceptance.**
   - Given 2 rice at Rs 125 (5% GST), when quoted, then total 25000, taxable 23810, CGST 595, SGST 595.
   - Given a coupon and no mobile, when quoted, then 400 `customerMobile` and the mobile field is focused.
   - Given a manual discount of 1000, when quoted, then `manualDiscountPaise` 1000 and total 24000 with no PIN asked.
   - Given a cashier with `cashierDiscountPercent` 0, when selling with a discount and no PIN, then 422 `DISCOUNT_NEEDS_APPROVAL`; resending with a valid PIN and the same key succeeds and the bill shows `manualDiscountApprover`.
   - Given round-off on, when quoted, then `totalPaise % 100 == 0`.
   - Given a coupon for one category, when quoted, then only the covered lines have `discountPaise > 0`.

---

## F4 Payment lines and completing the sale

1. **Goal.** Take payment (split allowed) and create the bill with its invoice number.
2. **Who/settings.** Cashier/admin. `pos.requireOpenShift` may demand an open shift.
3. **Screens and fields.**

| Field | Type | Required | Validation |
|---|---|---|---|
| cart | object (as F3) | yes | non-empty items |
| payments[].mode | string | yes | trimmed and upper-cased by server: CASH, UPI, CARD, or CREDIT (at most one CREDIT line per bill). Else 400 `payments[i].mode`: `payment mode must be CASH, UPI or CARD, or CREDIT on a bill (got "xyz")` (`got none` when missing) |
| payments[].amountPaise | long | yes | > 0 (service: "payment amounts must be positive"), <= 100000000 |
| payments[].reference | string | no | max 100 chars (UPI txn id, card slip no.) |
| payments[].terminalPaymentId | uuid | no | only on CARD/UPI lines; see F6 |
| cashTenderedPaise | long | only when paying cash | <= 100000000; needs a CASH line ("cash tendered given but no cash payment"); must be >= the CASH amount ("cash tendered is less than the cash payment") |
| manualDiscount | object | no | see F3 |

   Rule: the payment amounts must add up EXACTLY to the quoted `totalPaise` (including round-off). A zero-value bill takes NO payment lines. Wrong sum: 400 `payments` "payments add up to 100.00 but the bill is 250.00" (rupees with 2 decimals).
4. **API sequence.**
   1. Pay screen shows the quote total; the cashier adds lines. UI helper: `remaining = total - sum(lines)`; Confirm enabled only when `remaining == 0`. For cash: field "Cash received" -> send `cashTenderedPaise`; show "Change" = received - cash line (the server returns `changePaise`; show the server value on the receipt screen).
   2. UPI: either the customer pays the shop QR and the cashier confirms on their own phone, typing the UTR into `reference`; or use a card/UPI machine channel (F6). QR: `GET /pos/upi-link?amountPaise=12345&note=bill` -> `{ "link": "upi://pay?pa=vijay%40upi&...&am=123.45" }`; render as QR. 422 `UPI_NOT_CONFIGURED` when the shop has no UPI id. A static QR payment cannot be verified by the server: only a Paytm machine payment is confirmed automatically (a manual machine is confirmed by the cashier typing the approval code). Before choosing the line, load `GET /pos/payment-channels` (F6 step 1) and send the chosen channel's `moneyAccountId` on the line.
   3. Generate `Idempotency-Key = uuid` when the cashier first taps Pay; persist `{key, request body}` locally.
   4. `POST /pos/sales` with the key:
```json
{ "cart": { "items": [ { "productId": "<rice>", "qty": 2 } ] },
  "cashTenderedPaise": 12000,
  "payments": [ { "mode": "CASH", "amountPaise": 10000 }, { "mode": "UPI", "amountPaise": 15000, "reference": "UTR1" } ] }
```
      -> 201 `SaleRecord` (fields below). Example result: `invoiceNo "VSM/2026-27/000042"`, `changePaise 2000`, `totalPaise 25000`, `paymentType "SPLIT"`.
   5. On 201: clear the basket, delete a held basket if the sale came from one (`DELETE /pos/holds/{id}`; the server does not do it), go to the receipt screen (F7). Persist the `saleId`.
   6. Errors and UI:

| HTTP / code | Meaning | UI |
|---|---|---|
| 400 `VALIDATION_FAILED` `payments` | sum mismatch, zero-value bill with lines, bad cash tendered, two CREDIT lines | show message, fix the lines; if the sum is off by the quote, re-quote |
| 400 `payments[i].mode` | bad mode | cannot happen with a fixed picker |
| 400 `customerMobile` | credit or coupon/coins without a mobile | ask for mobile |
| 400 `payments[i].terminalPaymentId` / `payments[i].moneyAccountId` | unknown id, wrong line mode, another machine's account | see F6 |
| 409 `OUT_OF_STOCK` | stock short (`details.items[{productId, available}]`, per the older guide) | show the product, reduce qty, re-quote; nothing was stored |
| 422 `SHIFT_NOT_OPEN` | shop requires a shift | go to open shift, then resend with the same key |
| 422 `DISCOUNT_NEEDS_APPROVAL` | PIN needed/invalid | PIN modal (F3) |
| 422 `COUPON_INVALID` | coupon rules failed at sale time | drop the coupon, re-quote |
| 422 `CREDIT_NOT_ALLOWED` / `CUSTOMER_BLOCKED` / `CREDIT_LIMIT_EXCEEDED` | see F5 | |
| 422 `TERMINAL_PAYMENT_NOT_PAID` / `TERMINAL_PAYMENT_AMOUNT` / `TERMINAL_PAYMENT_MODE`, 409 `TERMINAL_PAYMENT_USED` | see F6 | |
| 429 `RATE_LIMITED` | too many PIN guesses | wait |
| network error / timeout / 5xx | unknown outcome | RETRY WITH THE SAME KEY (see edge cases) |

5. **Derived values.** `invoiceNo` (`<prefix>/<financial year>/<6 digits>`, assigned by server; the financial year is that of the sale time), `changePaise`, `paymentType` (`NONE`, `CASH`, `UPI`, `CARD`, `SPLIT`, `CREDIT`, `HYBRID`), `coinsEarned`, GST totals, stock reserved, coupon/coins consumed. A failed sale stores nothing (all or nothing, including a card machine payment attach and a credit charge).
6. **Edge cases.**
   - Timeout/crash after tapping Pay: on restart, if a persisted `{key, body}` has no confirmed sale, resend the SAME request with the SAME key. You get the original bill (201) if it was recorded, or a normal result if it was not. Never generate a new key for a retry, never reuse a key for a different basket.
   - After a successful sale with a key, a later request with that key and a different body returns the OLD sale (verified in `PosService.saleOnce`: lookup by key only). New basket = new key.
   - Double tap on Pay: disable the button on first tap; same key makes a second tap harmless anyway.
   - Cash drawer: `paidByMode.CASH` in the day report counts the cash share, not the tendered amount.
   - Customer name/mobile may be absent (walk-in): `customerId`/`customerMobile` null; no coins earned.
7. **Dart.**
```dart
class Pay { String mode; int amountPaise; String? reference; String? terminalPaymentId; }
class SaleInput { Cart cart; List<Pay> payments; int? cashTenderedPaise; ManualDiscount? manualDiscount; }
class SaleItem { String id, productId, name, soldBy, hsn; int qty, unitPricePaise, linePaise, discountPaise, netPaise,
  gstPercent, taxablePaise, cgstPaise, sgstPaise, returnedQty, couponSharePaise; int get returnable => qty - returnedQty; }
class PaymentRecord { String mode; int amountPaise; String? reference, terminalPaymentId; }
class SaleRecord { String id, invoiceNo, cashierId, paymentType, manualDiscountReason; String? customerId, customerMobile, couponCode, manualDiscountApprover;
  int subtotalPaise, couponDiscountPaise, coinsUsed, coinDiscountPaise, totalPaise, taxablePaise, cgstPaise, sgstPaise,
      coinsEarned, changePaise, manualDiscountPaise, roundOffPaise, couponMinOrderPaise; int? tenderedPaise;
  DateTime createdAt; List<SaleItem> items; List<PaymentRecord> payments; }
```
8. **Acceptance.**
   - Given 2 rice and CASH 10000 + UPI 15000 with cash tendered 12000, when Pay, then 201, `changePaise` 2000, `invoiceNo` matches `VSM/\d{4}-\d{2}/\d{6}`.
   - Given CASH 100 against a Rs 125 bill, when Pay, then 400 `payments` and no stock moves.
   - Given the same key and body sent twice, when Pay, then both calls return the same `id` and stock drops once.
   - Given payment mode " cash ", when Pay, then stored mode is `CASH`.
   - Given round-off on and payments = total + 1, when Pay, then 400.

---

## F5 Credit (udhar) sales and the credit picker

1. **Goal.** Put all or part of a bill on the customer's account.
2. **Who/settings.** Cashiers can SELL on credit and LOOK UP accounts; only the admin sets limits/blocks (`PUT /admin/credit/limit`, 403 for a cashier; no manager-PIN path exists). Credit is only for counter bills.
3. **Screens and fields.** Credit picker: search box `q` (max 100 chars), toggle "all customers" (`all=true`, then `q` needs >= 2 chars, else 400 `q`); list rows. Pay screen: a CREDIT payment line.
4. **API sequence.**
   1. Cashier chooses "Credit" and must enter the customer's full mobile (the CREDIT line needs `cart.customerMobile`; without it 400 `customerMobile` "customer mobile is needed to sell on credit").
   2. `GET /pos/credit/account?mobile=9876543210` -> `{ customerId, name, mobile, balancePaise, limitPaise, availablePaise, blocked }`. 404 `NOT_FOUND` "Customer" when the shop has never seen this number (then credit cannot be given: the admin must first approve a limit). Show: limit, owed, available. Disable CREDIT when `limitPaise == 0` ("Credit not enabled"), `blocked` ("Customer blocked") or `availablePaise < wanted`.
   3. Optional picker: `GET /pos/credit/customers?q=sha&all=false&page=0&size=20` -> Page of `{ customerId, name, maskedMobile ("98XXXXXX10"), limitPaise, balancePaise, availablePaise, blocked }`. By default only customers with a limit above 0. The row has only a MASKED number; the cashier must still type the full mobile (the account lookup takes a mobile, not an id). Use the picker to see who has credit and their available amount.
   4. Add payment line `{ "mode": "CREDIT", "amountPaise": 10000 }` (at most one per bill; may be combined with CASH/UPI/CARD: `paymentType` becomes `HYBRID`, all credit = `CREDIT`). Amounts must still sum to the total.
   5. `POST /pos/sales` as in F4. Errors (all 422, nothing stored, stock untouched):
      - `CREDIT_NOT_ALLOWED` ("Credit is not enabled for this customer", limit 0 or never approved): checked first.
      - `CUSTOMER_BLOCKED` ("This customer is blocked, so credit cannot be given").
      - `CREDIT_LIMIT_EXCEEDED` ("More than the customer's available credit of Rs 100.00"), `details`: `limitPaise, balancePaise, availablePaise, requestedPaise`. Offer to move the excess to cash/UPI/card.
   6. `GET /pos/credit/ledger?mobile=...&page=0&size=20` -> Page of `{ id, kind (SALE|RETURN|PAYMENT|ADJUST), deltaPaise (+ owes more, - owes less), balanceAfterPaise, refNo, note, at }`.
5. **Derived values.** `availablePaise` = limit - balance (>= 0), balance, ledger. The app never keeps balances; re-read the account before each credit sale.
6. **Edge cases.** The limit can change between lookup and sale: always handle the 422s. Offline: credit is refused (F11). Returns from a credit bill first reduce what is owed (F9).
7. **Dart.**
```dart
class CreditAccount { String customerId, name, mobile; int balancePaise, limitPaise, availablePaise; bool blocked; }
class CreditPick { String customerId, name, maskedMobile; int limitPaise, balancePaise, availablePaise; bool blocked; }
class LedgerEntry { String id, kind, refNo, note; int deltaPaise, balanceAfterPaise; DateTime at; }
```
8. **Acceptance.**
   - Given a customer with limit 50000, when selling Rs 250 fully on CREDIT, then 201, `paymentType` CREDIT and account balance 25000.
   - Given a customer without a limit, when selling on CREDIT, then 422 `CREDIT_NOT_ALLOWED`.
   - Given limit 10000 and CREDIT 25000, then 422 `CREDIT_LIMIT_EXCEEDED` with `details.availablePaise` 10000.
   - Given CREDIT with no mobile, then 400.
   - Given two CREDIT lines, then 400 "only one credit payment is allowed on a bill".
   - Given CASH 10000 + UPI 5000 + CREDIT 10000, then `paymentType` HYBRID and balance rises by 10000.

---

## F6 Card and UPI on a card machine (one flow for every vendor, Paytm wireless included)

> Status: the Paytm adapter is tested against an in-JVM fake of Paytm only. Do not tell the owner that it works with real Paytm until the sandbox run is done (`73-paytm-terminal.md` section 8). A machine payment needs the server: it is online-only.
>
> One source of truth: the session JSON, the Paytm state table, all cancel/void rules and the admin set-up are in `71-bank-and-settlements.md` (sections 3 and 4.2) and `73-paytm-terminal.md` (sections 2 and 3). This section is the till developer's recipe; it does not repeat those tables.

1. **Goal.** Let the cashier choose how a card/UPI amount is taken (Paytm machine, a bank machine with a manual confirm, a QR, cash drawer, ...), send the amount to that machine, wait for the result, and put the finished payment on the bill.
2. **Who/settings.** CASHIER/ADMIN use `GET /pos/payment-channels` and `/pos/terminal/payments*`. The admin sets up the money accounts (kind `CARD_TERMINAL`, `provider` = `paytm` or `manual`...), the Paytm config group (`paytm.enabled`, `paytm.pollSeconds`, `paytm.waitSeconds`) and the machines master (`/admin/payment-terminals`; adding a Paytm machine creates its channel). `pos.terminalProvider` (default `manual`) is only the fallback for a channel whose provider has no adapter. Not set up: 503 `TERMINAL_NOT_CONFIGURED` -> offer "Enter reference manually" (a plain CARD/UPI line, see step 4.8).
3. **Screens and fields.**

| Field | Type | Required | Validation |
|---|---|---|---|
| moneyAccountId (body) | uuid | no, but always send it | `channel.moneyAccountId` of the chosen channel. Unknown or switched-off account, or one that cannot take CARD: 400 `fields.moneyAccountId`. Without it the server uses the shop default card account and the `pos.terminalProvider` adapter |
| amountPaise | long | yes | 1..100000000. MUST equal the bill's card/UPI line amount |
| reference | string | no (send it) | max 60 chars (a Paytm machine: 50). The key of the payment: new for every attempt, kept with the cart |
| Idempotency-Key | header | optional | only used to make a reference when you send none; the reference does the de-duplication |

4. **API sequence.**
   1. **Load the channels** when the payment step opens (cache per shift): `GET /pos/payment-channels` -> array of `{ mode: "CASH|UPI|CARD", moneyAccountId, provider, name, terminalId, kind, default }`. Example CARD rows: `{ "mode":"CARD", "provider":"paytm", "name":"Counter 1", "terminalId":"AB12CD34", "kind":"CARD_TERMINAL", "default":true }` and `{ "mode":"CARD", "provider":"manual", "name":"Bank machine", "terminalId":null, "kind":"CARD_TERMINAL", "default":false }`. `default` is the saved default account of the mode, else the only channel of the mode; a UPI or CARD mode may have NO default (then the cashier must pick). Several channels of a mode are several vendors active at once: show a chooser, pre-select `default`. An empty CARD list: hide "Card on machine" and keep the manual-reference line.
   2. **Freeze the basket and the card amount.** The session amount must equal the CARD/UPI payment line (else the attach fails with `TERMINAL_PAYMENT_AMOUNT`). If the cashier must change the basket, cancel/void first.
   3. **Start.** `POST /pos/terminal/payments`, body `{ "amountPaise": 12500, "reference": "INV-1042-1", "moneyAccountId": "<channel.moneyAccountId>" }` -> 201 session (shape: `71` section 3; Paytm fields: `73` section 2). The channel's provider picks the adapter. A repeat with the same reference and amount returns the same session (still 201), so a crash retry is safe; the same reference with another amount or on another channel is 409 `REFERENCE_REUSED` (use a new reference). Persist `{sessionId, reference, cartRef}` immediately.

| HTTP / code | Meaning | UI |
|---|---|---|
| 400 `VALIDATION_FAILED` | amount outside 1..100000000, reference too long, bad `moneyAccountId` (`details.fields`) | fix input |
| 409 `REFERENCE_REUSED` | reference used for another amount/machine | new reference |
| 409 `TERMINAL_BUSY` | a Paytm machine already has an open payment; `details.openPaymentId` | resume polling that payment; another channel stays usable |
| 422 `TERMINAL_REJECTED` | Paytm refused; `message` is the plain reason; `details.reason` is `TERMINAL_INACTIVE`, `TERMINAL_BUSY_AT_PAYTM` or `BAD_CHECKSUM` (admin problem) | show message; the payment is stored as FAILED and the machine is free; start again with a NEW reference |
| 503 `TERMINAL_NOT_CONFIGURED` | adapter missing, or `paytm.enabled` off/incomplete | ask admin / manual reference |
| 429 `RATE_LIMITED` | more than 30 card payments a minute per user | wait |

      A lost or unreadable answer from Paytm is NOT an error: you get 201 with `providerStatus: "UNKNOWN"` (`state` PENDING). Keep polling; never start another payment on that machine.
   4. **Poll.** `GET /pos/terminal/payments/{id}` every `pollAfterSeconds` while `open` is true. Read `state` (generic: PENDING, APPROVED, DECLINED, CANCELLED, EXPIRED) for the logic and `providerStatus` / `message` for the text. Fields to show: `terminalName`, `terminalId`, `payMethod`, `cardScheme`, `maskedCard`, `rrn`, `approvalCode`, `issuingBank`, `upiVpa` (masked). Each GET may itself check Paytm; the server also polls. A Paytm `EXPIRED` session is still watched for 15 minutes and may flip to `APPROVED`: offer "Check again" and do not take another payment from that customer until the cashier checked the machine. 404 = unknown id.
   5. **Confirm (manual channels only).** A `manual` session never changes by itself: after the cashier charged the bank machine, `POST /pos/terminal/payments/{id}/confirm` `{ "approvalCode": "A1B2C3", "rrn": "optional" }` (code 1..40 chars, rrn max 40) -> session `APPROVED`. On a Paytm session it answers 409 `TERMINAL_CONFIRMS_ITSELF` (hide the button when `provider != "manual"`); on a cancelled/declined one 409 `TERMINAL_PAYMENT_CLOSED`.
   6. **Cancel.** `POST /pos/terminal/payments/{id}/cancel` while `canCancel` (equals `open`). A finished session comes back unchanged (200). Manual: becomes CANCELLED. Paytm has no call that removes a sale already on the machine: 409 `CANCEL_ON_MACHINE` -> show "Press Cancel on the machine. This payment closes by itself once the machine reports it." and KEEP POLLING (`cancelOnMachine` is true; it turns CANCELLED or EXPIRED); 409 `ALREADY_PAID` -> the customer paid, use it or void it today.
   7. **Void (Paytm, same day).** `POST /pos/terminal/payments/{id}/void`, button when `canVoid` (APPROVED, taken today India time, not on a bill) -> 200, `state` CANCELLED / `providerStatus` VOIDED. Errors: 409 `NOT_VOIDABLE` (not paid, or a manual session: a manual machine cannot void from here), 409 `PAYMENT_IN_USE` (a bill has it: return the bill instead), 422 `VOID_WINDOW_PASSED` ("Refund it from the Paytm dashboard instead"), 422 `VOID_REJECTED` (reason in `message`), 502 `VOID_UNKNOWN` (check the machine before trying again; while unresolved it cannot go on a bill). After the day ends, or once a bill used it, a card refund is not done by the app: refund on the machine/Paytm dashboard and record a CARD refund line with a reference (F9).
   8. **Attach to the sale.** When `state == APPROVED`, add the line `{ "mode": billMode, "amountPaise": <session.amountPaise>, "terminalPaymentId": "<session id>" }` and `POST /pos/sales` as in F4. `billMode` = `UPI` if `payMethod` contains "UPI", else `CARD`; a `manual` session has no `payMethod`, so use the mode the cashier chose. **That one id is all the sale needs**: the server takes the machine's money account, terminal id, a `reference` (Paytm: the RRN, else `PAYTM <transaction id>`; manual: the RRN, else the approval code) and the masked payer detail from the session. You may omit `moneyAccountId`, `terminalId` and `reference`; a `reference` you send is kept as sent; naming another account than the machine's is 400 on `payments[i].moneyAccountId`. Rules are checked inside the sale (the whole sale fails, nothing is attached):

| code | HTTP | meaning / UI |
|---|---|---|
| `TERMINAL_PAYMENT_NOT_PAID` | 422 | session not APPROVED (or being voided) -> keep polling / choose another way |
| `TERMINAL_PAYMENT_USED` | 409 | already on a bill -> refresh; do not retry |
| `TERMINAL_PAYMENT_AMOUNT` | 422 | line amount differs from the session amount -> set the line to it |
| `TERMINAL_PAYMENT_MODE` | 422 | Paytm payment taken by UPI on a CARD line or the reverse -> change the line mode |
| `VALIDATION_FAILED` `payments[i].terminalPaymentId` | 400 | unknown id, line is not CARD/UPI, or the bill is offline |

      Without a machine (old card machine, customer shows a UPI reference, offline): send a plain CARD/UPI line with the `reference` typed in and, if you know it, the chosen channel's `moneyAccountId`/`terminalId` (`71` section 3 step 3).
5. **State machine and server-computed values.** Generic: `PENDING -> APPROVED | DECLINED | CANCELLED | EXPIRED`; Paytm only: `EXPIRED -> APPROVED` (late payment, 15 minutes), `APPROVED -> CANCELLED (VOIDED)`. Mapping of the Paytm words (REQUESTED, PENDING, UNKNOWN, SUCCESS, FAILED, CANCELLED, EXPIRED, VOIDED) is the table in `73` section 2. Never decide a payment is final yourself: only `open == false` and `state` say so. Never mark a card line paid from the app's own belief. A Paytm machine holds at most one open payment (`TERMINAL_BUSY`).
6. **Edge cases (crash and network).**
   - App crash mid-payment: on restart read the persisted `{sessionId, reference, cartRef}` and `GET /pos/terminal/payments/{sessionId}`.
     - `open` -> resume the waiting screen and the poll loop.
     - `APPROVED` and `posSaleId == null` -> money is taken but there is no bill: restore the basket (hold the basket BEFORE starting the machine) and complete the sale with `terminalPaymentId`; or void it today if the customer left (`canVoid`, Paytm).
     - `APPROVED` and `posSaleId != null` -> the bill exists: `GET /pos/sales/{posSaleId}`, go to the receipt.
     - `EXPIRED` -> "Check again"; no second payment on the same customer until the 15-minute watch ends.
     - DECLINED/CANCELLED -> offer retry/other channel.
   - Crash during the start request (no id saved): resend the start with the SAME reference and amount: you get the same session. If the reference was lost, a Paytm machine answers 409 `TERMINAL_BUSY` with `details.openPaymentId`; resume that.
   - Crash after the sale request: resend the sale with the saved sale key (F4).
   - Network down while polling: keep the id, retry the GET with back-off; the server keeps polling Paytm. Do not start a new payment.
   - Double tap on "Card": same reference returns the same session; disable the button anyway.
   - Customer pays a different amount than requested (Paytm): the session shows `providerStatus` UNKNOWN with a message to check the slip; the manager decides (void if possible).
   - Split bill (CASH 5000 + CARD 7500): start the machine for 7500 only; sale lines CASH 5000 + CARD 7500 with `terminalPaymentId`.
   - Offline: do not use a machine; use a manual CARD/UPI line with `reference` (F11). `terminalPaymentId` on an offline bill is refused (400) by the as-rung path; do not rely on the legacy path accepting it.
   - The old Paytm-only calls (`GET /pos/terminals`, `POST /pos/terminal-payments` with an Idempotency-Key and `terminalId`, `GET/POST /pos/terminal-payments/{id}[/cancel|/void]`) still work on the very same rows (a payment started on one can be polled, cancelled, voided or put on a bill through the other), but a new till should use only the generic calls above. Their payload is the Paytm view (`status` = `providerStatus`, `authCode`, `terminalRowId`; reference max 50); see `73` section 2.
7. **Dart.**
```dart
class PayChannel { String mode, moneyAccountId, provider, name, kind; String? terminalId; bool isDefault; /* json key: "default" */ }
class TerminalSession {
  String id, reference, provider, state, providerStatus, message;
  String? moneyAccountId, terminalId, terminalName, payMethod, cardScheme, maskedCard, rrn, approvalCode, issuingBank, upiVpa, posSaleId;
  int amountPaise, pollAfterSeconds; bool open, canCancel, cancelOnMachine, canVoid; DateTime createdAt; DateTime? finishedAt;
  bool get paid => state == 'APPROVED';
  bool get needsManualConfirm => provider == 'manual' && open;
  String billMode(String chosen) => (payMethod ?? '').toUpperCase().contains('UPI') ? 'UPI' : (payMethod == null ? chosen : 'CARD');
}
```
8. **Acceptance.**
   - Given one Paytm CARD channel with `default: true`, when the cashier taps "Card on machine", then no picker is shown and `POST /pos/terminal/payments` is sent with that channel's `moneyAccountId` and a new reference.
   - Given a Paytm and a manual CARD channel, when the cashier picks the manual one, then the session stays PENDING until `confirm` is posted with an approval code, then `APPROVED`.
   - Given a PENDING Paytm payment, when another start is made on the same machine, then 409 `TERMINAL_BUSY` and the app resumes `details.openPaymentId`; a start on the manual channel still works.
   - Given a PENDING Paytm payment, when the cashier presses Cancel in the app, then 409 `CANCEL_ON_MACHINE` and polling continues.
   - Given APPROVED 12500, when the bill's CARD line is 12500 with that `terminalPaymentId` and no `reference`, then the sale is 201 and the stored line `reference` is the RRN; a second bill using it gets 409 `TERMINAL_PAYMENT_USED`.
   - Given an APPROVED Paytm payment taken today and not on a bill, when Void, then `providerStatus` VOIDED; after the bill used it, `PAYMENT_IN_USE`. Void on a manual session: `NOT_VOIDABLE`.
   - Given EXPIRED then the customer pays, when polling again within 15 minutes, then it becomes APPROVED.
   - Given the app was killed while PENDING, when restarted, then it resumes polling the saved id.
   - Given the same reference and amount sent twice, then the same session id comes back and the machine is asked once.

---

## F7 Receipts and printing

1. **Goal.** Print the bill, credit note and repayment receipt on a thermal printer.
2. **Who/settings.** Cashier/admin. Shop header/footer come from admin shop settings.
3. **Fields.** `width` query: only `32` (58 mm paper) or `48` (80 mm); default 32. Any other value: 400 `VALIDATION_FAILED` field `width` ("width must be 32 or 48").
4. **API sequence.**
   1. Bill: `GET /pos/sales/{id}/receipt?width=32` -> `{ "width": 32, "text": "...\n" }`. Contains shop header, `TAX INVOICE`, `Inv:`, date `dd-MM-yyyy HH:mm` (shop time), `Cashier:` / `Cust:`, item lines (weighed items print `1.500 kg`; counted print `2 x 125.00`, with GST % and net amount), Subtotal, Coupon, Coins, Discount (manual), Round off, TOTAL, Taxable/CGST/SGST, one line per payment mode, Change, `Coins earned`, footer.
   2. Credit note: `GET /pos/sales/{id}/returns/{returnId}/receipt?width=32` (404 if the return is not on that bill). Lines `CREDIT NOTE`, `CN:`, `Orig Inv:`, `Returned N`, Taxable/CGST/SGST, `Coupon share`, `Coupon taken back`, `Refund total`, `Credit reduced`, `Refund <MODE>`, `Reason:`.
   3. Repayment: `GET /pos/credit/payments/{id}/receipt?width=32`: `CREDIT PAYMENT RECEIPT`, `Receipt:`, `Customer:`, `Balance before`, `Paid <MODE>`, `Amount paid`, `Balance after`, `Note:`.
   4. Send `text` to the printer unchanged in a monospace font (ESC/POS text mode; lines end with `\n`, no extra wrapping).
5. **Derived values.** Everything in the text is produced by the server. The text does NOT contain card RRN/approval code: if you want them on paper, read `payments[].reference` of the sale (the RRN, or `PAYTM <transaction id>` when Paytm gave none) and the session you used, and print an extra line yourself. For offline bills the temporary receipt is local; after sync the server receipt carries the real `invoiceNo`.
6. **Edge cases.** Printer offline: keep the sale, offer "Reprint" (fetch again; it is a pure read). Reprint after the day is fine. The footer text may be empty. Receipt of a missing id: 404.
7. **Dart.** `class Receipt { int width; String text; }`
8. **Acceptance.**
   - Given a sale, when `receipt?width=32`, then each line is at most 32 characters and contains `TAX INVOICE` and the invoice number.
   - Given `width=40`, then 400 with `fields.width`.
   - Given a return, when the credit-note receipt is fetched with width 48, then it contains `CREDIT NOTE` and the credit note number.

---

## F8 Holds (parked baskets)

1. **Goal.** Park a basket to serve someone else, resume later.
2. **Who/settings.** Any cashier/admin. Holds are SHARED by all cashiers on the shop (the 100 limit is global, newest first). Parked baskets are removed automatically after `app.pos.hold-expiry-days` (default 7; 0 = never) by a 03:00 job; `expiresAt` is on each hold (null if never).
3. **Fields.**

| Field | Type | Required | Validation |
|---|---|---|---|
| label | string | no | max 60 chars |
| cart.items | list | yes | 1..50 lines (400 `items` "must have at most 50 lines"); each qty 1..9999 (grams 1..100000 for weighed): 400 `cart.items[i].qty` "must be 1 to 9999" |
| cart.customerMobile / couponCode / coinMode / percent | as F3 | no | mobile format checked; products are not checked to exist |

4. **API sequence.**
   1. Park: `POST /pos/holds` `{ "label": "Mrs Shah", "cart": { "items": [ { "productId": "...", "qty": 3 } ], "customerMobile": "9876543210" } }` -> 201 `{ id, label, customerMobile, cashierId, createdAt, cart, expiresAt }`. Clear the screen.
   2. List: `GET /pos/holds` -> plain array, newest first (at most 100 so none is hidden). Show label, customer, item count, age, `expiresAt`.
   3. Resume: `GET /pos/holds/{id}` (404 if resumed/discarded elsewhere or expired) -> load `cart` into the basket and RE-QUOTE (prices may have changed). A hold is not removed on resume.
   4. Delete: `DELETE /pos/holds/{id}` -> 204 (404 if gone). Call it after the resumed basket is paid or abandoned.
   5. Errors: 409 `HOLD_LIMIT` (`details.max` 100, `details.expiryDays`; message "Too many parked baskets: resume or discard some first. Parked baskets are removed automatically after 7 days") -> open the holds list to discard some.
5. **Derived values.** No prices are stored; the server never re-prices a hold until you quote it.
6. **Edge cases.** Two cashiers resume the same hold: both succeed in reading; the second `DELETE` answers 404, ignore it. A hold with a product that has since been switched off: quote answers 400 `productId`; remove the row. Hold BEFORE starting a card payment so a crash can restore the basket (F6). Holds need the server: offline, keep parked baskets locally.
7. **Dart.** `class HoldView { String id, label; String? customerMobile; String cashierId; DateTime createdAt; Cart cart; DateTime? expiresAt; }`
8. **Acceptance.**
   - Given a basket, when parked and listed, then the hold appears with `expiresAt` 7 days ahead.
   - Given 100 holds, when parking one more, then 409 `HOLD_LIMIT`; after deleting one, parking works.
   - Given qty 10000, when parking, then 400 `cart.items[0].qty` "must be 1 to 9999".

---

## F9 Returns at the counter

1. **Goal.** Take goods back against a bill: credit note, stock, coins, refund.
2. **Who/settings.** Cashier may return only bills newer than `pos.cashierReturnDays` (default 7, 0 = no limit): older -> 422 `RETURN_NEEDS_ADMIN` (`details.days`) -> a manager must sign in on this till as admin. Admin is never limited. Config `returns.couponPolicy`: `KEEP` (default) or `CLAWBACK_BELOW_MINIMUM`.
3. **Fields.**

| Field | Type | Required | Validation |
|---|---|---|---|
| items[].saleItemId | uuid | yes | a line of this bill (400 `saleItemId` "item is not on this bill") |
| items[].qty | int | yes | 1..100000 and <= returnable (409 `RETURN_EXCEEDS_SOLD`, `details.saleItemId`, `details.returnable`); grams for weighed |
| items[].restock | bool | no | null/true = back on the shelf; false = damaged: refund is given but units go to the "returned goods to check" queue |
| reason | string | no | max 200 |
| refundMode | CASH / UPI / CARD | no | one mode for the whole payout; cannot be combined with `refunds` (400 `refundMode`) |
| refunds[] | list of `{mode, amountPaise, reference}` | no | explicit split; modes CASH/UPI/CARD only (CREDIT -> 400 `refunds[i].mode`); must add up exactly to the payout |

   If neither `refunds` nor `refundMode` is sent, the refund goes out in the bill's own mode when it was paid in exactly one of CASH/UPI/CARD, else CASH.
4. **API sequence.**
   1. Find the bill: `GET /pos/sales?invoiceNo=VSM%2F2026-27%2F000042` (URL-encode `/`), or `?mobile=9876543210`, or `?date=2026-11-09` (default today, newest first) -> array of `SaleRecord`. `GET /pos/sales/{id}` for one. 404 for an unknown id.
   2. Show lines with `returnableQty = qty - returnedQty`; cashier picks quantities (weighed: grams).
   3. **Preview (always before confirming):** `POST /pos/sales/{id}/returns/preview` with the same body (only `items` is used) -> `Preview`:
```json
{ "saleId": "...", "invoiceNo": "VSM/2026-27/000042", "couponPolicy": "KEEP", "refundPaise": 12500,
  "couponShareRemovedPaise": 0, "otherShareRemovedPaise": 0, "couponClawbackPaise": 0,
  "taxablePaise": 11905, "cgstPaise": 298, "sgstPaise": 297,
  "lines": [ { "saleItemId": "...", "productId": "...", "name": "Basmati Rice", "qty": 1, "grossPaise": 12500,
               "couponSharePaise": 0, "otherSharePaise": 0, "clawbackPaise": 0, "refundPaise": 12500,
               "taxablePaise": 11905, "cgstPaise": 298, "sgstPaise": 297 } ] }
```
      Per line: refund = gross price of returned units - coupon share - other share (coins and counter discount) - clawback. The coupon share leaves with the units (customer does not get it back as cash). With `CLAWBACK_BELOW_MINIMUM`, if what is left of the coupon-covered items falls below the coupon's minimum order, the coupon discount on the kept items is taken back out of this refund (`couponClawbackPaise`, never below zero). Show these lines to the cashier. The preview does not show the credit adjustment (below).
   4. **Confirm:** `POST /pos/sales/{id}/returns` + `Idempotency-Key`:
```json
{ "items": [ { "saleItemId": "<line>", "qty": 1 } ], "reason": "wrong pack", "refundMode": "CASH" }
```
      -> 201 `ReturnRecord`: `{ id, creditNoteNo ("VSM-CN/2026-27/000001"), saleId, cashierId, reason, refundPaise, taxablePaise, cgstPaise, sgstPaise, coinsClawedBack, coinsRestored, createdAt, items[{saleItemId, qty, refundPaise, taxablePaise, cgstPaise, sgstPaise, couponSharePaise}], refunds[{mode, amountPaise, reference}], creditAdjustedPaise, couponClawbackPaise }`. Repeat key returns the first return. Then print the credit note (F7) and give the money: pay out `refunds` (cash from the drawer, UPI/card as listed).
   5. Errors: 400 `refunds` (split does not add up: message names both numbers, and adds "(Rs X of this return reduces the customer's credit)" when relevant), 400 `refundMode`/`refunds[i].mode`, 409 `RETURN_EXCEEDS_SOLD`, 422 `RETURN_NEEDS_ADMIN`, 404 bill.
   6. `GET /pos/sales/{id}/returns` lists returns already made (oldest first).
   7. **Defective goods:** lines sent with `restock:false` are not added to sellable stock; they wait in the queue: `GET /pos/stock/defective?status=PENDING_REVIEW&page=0&size=20` and `POST /pos/stock/defective/{id}/review` `{ "decision": "CONFIRM_DEFECTIVE" | "RESTOCK", "note": "..." }` (note max 200). A person cannot `CONFIRM_DEFECTIVE` the batch they took back themselves (422 `DEFECTIVE_SELF_REVIEW`); 409 `DEFECTIVE_ALREADY_REVIEWED`. Final write-off/return-to-supplier is admin only.
5. **Derived values (server only).** Refund per line, GST reversal, coupon share/clawback, coins clawed back (earned coins) and restored (spent coins) in proportion, credit adjustment, credit note number. The app must not compute refunds; use the preview.
   - Credit bills: the refund first cancels what the customer still owes for that bill (`creditAdjustedPaise`, ledger kind `RETURN`); only `refundPaise - creditAdjustedPaise` is paid out and `refunds[]` shows exactly that. For a bill with a credit part, DO NOT send an explicit `refunds` split (you cannot know the payout): send nothing or only `refundMode`.
   - A fully credit-cancelled return has no refund lines (the payout is 0).
   - Flash-sale units are not given back to the flash sale's stock.
6. **Edge cases.** Same key resent: same credit note. New key + same lines after success: 409 `RETURN_EXCEEDS_SOLD` once everything is returned. Card refunds are NOT sent to the machine: the CARD refund line is a record (it follows the money account of the bill's CARD line); do the physical refund on the machine/Paytm dashboard. A bill paid by card machine cannot be un-used by void (PAYMENT_IN_USE). A bill from the offline queue that is not yet synced cannot be returned (no id yet).
7. **Dart.**
```dart
class ReturnLine { String saleItemId; int qty; bool? restock; }
class ReturnBody { List<ReturnLine> items; String? reason, refundMode; List<Pay>? refunds; }
class ReturnRecord { String id, creditNoteNo, saleId, cashierId, reason; int refundPaise, taxablePaise, cgstPaise, sgstPaise,
  coinsClawedBack, coinsRestored, creditAdjustedPaise, couponClawbackPaise; DateTime createdAt; List<ReturnItem> items; List<PaymentRecord> refunds; }
class ReturnItem { String saleItemId; int qty, refundPaise, taxablePaise, cgstPaise, sgstPaise, couponSharePaise; }
class ReturnPreview { String saleId, invoiceNo, couponPolicy; int refundPaise, couponShareRemovedPaise, otherShareRemovedPaise,
  couponClawbackPaise, taxablePaise, cgstPaise, sgstPaise; List<PreviewLine> lines; }
class PreviewLine { String saleItemId, productId, name; int qty, grossPaise, couponSharePaise, otherSharePaise, clawbackPaise,
  refundPaise, taxablePaise, cgstPaise, sgstPaise; }
```
8. **Acceptance.**
   - Given a UPI bill, when a return is confirmed with no refund fields, then the refund line mode is `UPI`.
   - Given a CASH+UPI split bill, when returned with no fields, then the refund is `CASH`.
   - Given `refundMode` and `refunds` together, then 400 `refundMode`.
   - Given 10 rice sold and 10 returned, when 1 more is returned, then 409 `RETURN_EXCEEDS_SOLD`.
   - Given a bill older than 7 days and a cashier token, then 422 `RETURN_NEEDS_ADMIN`.
   - Given a bill with coupon share, when previewed, then `couponSharePaise` > 0 and `refundPaise` < `grossPaise`.
   - Given a bill bought on credit, when returned, then `creditAdjustedPaise` > 0 and only the rest is paid out.
   - Given the same key sent twice, then one return exists.

---

## F10 Credit repayments

1. **Goal.** The customer pays some or all of what they owe.
2. **Who/settings.** Cashier/admin. Not gated by shifts. Cash repayments go into the drawer (expected cash includes them).
3. **Fields.**

| Field | Type | Required | Validation |
|---|---|---|---|
| customerMobile | string | yes | 10-digit Indian mobile (400 `mobile`); unknown number -> 404 `NOT_FOUND` |
| payments[] | list | yes, non-empty | modes CASH / UPI / CARD only (CREDIT -> 400 `payments[i].mode` "payment mode must be CASH, UPI or CARD (got ...)"), each amount > 0 |
| note | string | no | max 200 |

4. **API sequence.**
   1. `GET /pos/credit/account?mobile=` -> show what is owed (`balancePaise`).
   2. `POST /pos/credit/payments` + `Idempotency-Key`:
```json
{ "customerMobile": "9876543210", "payments": [ { "mode": "CASH", "amountPaise": 5000 }, { "mode": "UPI", "amountPaise": 5000, "reference": "UTR9" } ], "note": "part payment" }
```
      -> 201 `{ id, receiptNo ("VSM-CR/2026-27/000001"), customerId, customerMobile, amountPaise, payments[], balanceDuePaise, note, at }`. Repeat key returns the first receipt.
   3. 422 `CREDIT_OVERPAYMENT` ("The customer owes only Rs 150.00"; `details.balancePaise`, `details.paidPaise`) -> limit the amount to the balance.
   4. Print: `GET /pos/credit/payments/{id}/receipt?width=32` (F7).
5. **Derived values.** `balanceDuePaise` after payment, receipt number, ledger line. A `terminalPaymentId` on a repayment line is accepted by the schema but IGNORED (not attached); a card repayment is recorded with a manual reference only.
6. **Edge cases.** Retry after timeout: same key. Offline: not available (online only).
7. **Dart.** `class Repayment { String id, receiptNo, customerId, customerMobile; int amountPaise, balanceDuePaise; String? note; List<PaymentRecord> payments; DateTime at; }`
8. **Acceptance.**
   - Given balance 25000, when paying CASH 5000 + UPI 5000, then 201, `amountPaise` 10000, `balanceDuePaise` 15000.
   - Given payment 15001 against 15000, then 422 `CREDIT_OVERPAYMENT`.
   - Given a CREDIT mode line, then 400.
   - Given the same key twice, then the same `id` and balance changes once.

---

## F11 Offline billing and sync

1. **Goal.** Keep selling when the internet is down; upload later without double billing.
2. **Who/settings.** Cashier/admin. `maxOfflineDays` 7, `batchLimit` 50 (from `/pos/sync/status`). Uploading user is recorded as the cashier of the bill.
3. **What is allowed offline.** Cash, UPI and CARD lines (card/UPI with a manual `reference`, since the machine needs the server), split payments, cash tendered/change, a customer mobile (attribution only), line discounts and a manual discount (recorded as an exception for the manager; no PIN is checked), weighed items, GST per line as the till computed.
   **Refused offline (design the UI to block them):** coupons and coin redemption (400 `cart`: "a coupon or coin redemption cannot be checked offline: ring this bill without it"), CREDIT lines (400 `payments`: "credit (udhar) cannot be given on an offline bill: it needs the customer's account"), card machine payments / `terminalPaymentId` (400, any vendor), returns, holds on the server, credit repayments, shift open/close, quote (compute locally), PIN-approved discounts. Earning coins: none for an as-rung bill.
4. **Fields of an offline bill (as rung, the current standard).**

| Field | Type | Required | Validation |
|---|---|---|---|
| idempotencyKey | string | yes | generate ONCE per bill when it is rung, never regenerate; blank -> REJECTED `VALIDATION_FAILED` "idempotencyKey is required" |
| soldAt | instant | strongly yes | when it really happened; not more than 5 minutes in the future, not older than 7 days (else REJECTED field `soldAt`). Omitted = upload time |
| rung.lines[] | list | yes, non-empty | see below |
| rung.lines[].productId | uuid | yes | must exist (inactive allowed, flagged) |
| .qty | int | yes | > 0; <= 9999 pieces / 100000 grams |
| .unitPricePaise | long | yes | >= 0; per piece, or per 1000 g for weighed items |
| .gstPercent | int | yes | one of 0, 3, 5, 12, 18, 28 |
| .discountPaise | long | no | >= 0 and <= the line amount |
| .name | string | no | printed name; catalog name if blank |
| .linePaise | long | no | if given must equal price x qty (weighed: round-half-up(price x grams / 1000)) |
| payments[] | list | yes unless zero bill | CASH/UPI/CARD; sum must equal the payable total exactly |
| cashTenderedPaise | long | no | as F4 |
| manualDiscount | `{amountPaise, reason}` | no | 0 <= amount <= bill after line discounts |
| cart.customerMobile | string | no | valid mobile; `cart` may be omitted |

5. **API sequence.**
   1. Online beforehand: download the catalog (F2) and `GET /pos/shop` (store `weighedPrefix`, `weighedMode`, `roundOffMode`, footer, UPI id). Save `catalogVersion`.
   2. Offline: the till prices locally with the cached catalog: line = price x qty (weighed: per 1000 g, half-up), GST inclusive, discounts, and round-off if `roundOffMode == NEAREST_RUPEE` (half a rupee rounds up). Print a temporary receipt with a local number. Queue the bill with its `idempotencyKey`.
   3. Back online: first re-download the catalog if `catalogVersion` moved, then upload oldest first, at most 50 per call: `POST /pos/sync/sales`
```json
{ "sales": [ {
  "idempotencyKey": "8c6e...", "soldAt": "2026-11-09T09:15:00Z",
  "rung": { "lines": [ { "productId": "<rice>", "qty": 2, "unitPricePaise": 12500, "gstPercent": 5, "discountPaise": 0, "name": "Basmati Rice 1 kg" } ] },
  "payments": [ { "mode": "CASH", "amountPaise": 25000 } ],
  "cashTenderedPaise": 30000,
  "cart": { "customerMobile": "9876543210" } } ] }
```
      Always 200 `{ "results": [ { idempotencyKey, status, saleId, invoiceNo, errorCode, message, exceptions: [ {kind, detail} ] } ] }`, one per bill, same order.
   4. Per result:
      - `CREATED`: replace the temporary receipt number with `invoiceNo`, keep `saleId`, delete the queued bill. `exceptions` (can be non-empty) lists what a manager will review: `PRICE_CHANGED`, `GST_CHANGED`, `OVERSOLD`, `DISCOUNT`, `PRODUCT_INACTIVE`. The bill is recorded as rung either way; show a small "flagged for manager" note, not an error.
      - `DUPLICATE`: already recorded earlier (including twice in one batch); treat as success, use `saleId`/`invoiceNo`.
      - `REJECTED`: nothing recorded. `errorCode` `INTERNAL_ERROR` is transient -> retry the same bill with the same key later. Anything else (`VALIDATION_FAILED` with the message, e.g. "payments add up to X but the bill is Y", bad `soldAt`, `linePaise` not price x qty, unknown product, unknown GST rate, coupon/credit/terminal attempts) -> move to a "needs attention" list for the manager; do not auto-retry.
   5. A whole-request error (empty list or more than 50 bills) is 400.
6. **Derived values (server).** Invoice number (financial year taken from `soldAt`), tax split per line from the till's prices and rates, round-off (using the server's CURRENT `gst.roundOff` setting, so the till's cached `roundOffMode` must match or payments will not add up), stock deduction ("take what is left": stock never goes below 0, the shortfall becomes an OVERSOLD exception), `createdAt = soldAt`. Never invent invoice numbers on the device.
7. **Edge cases.** Upload twice / batch half-arrived: safe, DUPLICATE. Unsynced bills when the shift closes: upload before closing. Clock wrong on the till: compare `serverTime`; refuse to ring offline bills if the clock is off by more than a few minutes. Legacy path: a bill without `rung` is re-priced by the server at today's prices and is REJECTED if payments no longer fit (and may take coupons/credit): do not use it in the new till. Offline bills are tied to the idempotency key space of online sales: never reuse a sale key.
8. **Dart.**
```dart
class RungLine { String productId; int qty, unitPricePaise, gstPercent; int? discountPaise, linePaise; String? name; }
class OfflineSale { String idempotencyKey; DateTime soldAt; List<RungLine> rungLines; List<Pay> payments;
  int? cashTenderedPaise; ManualDiscount? manualDiscount; String? customerMobile; }
class SyncResult { String idempotencyKey, status; String? saleId, invoiceNo, errorCode, message; List<ExceptionView> exceptions; }
class ExceptionView { String kind, detail; }
```
9. **Acceptance.**
   - Given a bill rung at an old price while the catalog price rose, when uploaded, then `CREATED` at the rung price with exception `PRICE_CHANGED`.
   - Given the same bill twice, then the second is `DUPLICATE` with the same `saleId` and stock moves once.
   - Given payments 1 paisa short, then `REJECTED` `VALIDATION_FAILED` and nothing is stored.
   - Given a coupon or CREDIT line, then `REJECTED` with a message mentioning coupon/credit.
   - Given `soldAt` 10 days old or in the future, then `REJECTED` on `soldAt`.
   - Given 51 bills in one request, then 400.
   - Given sold 3 when the system had 1, then `CREATED` with `OVERSOLD` and stock 0.

---

## F12 Flash sale prices at the counter

1. **Goal.** Show and bill flash sale prices correctly.
2. **Who/settings.** Admin/cashier create sales at `/admin/flash-sales` (open to CASHIER, file 40) with channel ONLINE, POS or both. Config `coupon.allowOnFlashItems` (default on).
3. **Fields.** None new: the same basket.
4. **API sequence.** Nothing to call. Quote and sale apply a running POS-channel sale automatically to counted (`UNIT`) items: the units that fit the sale's remaining quantity (and the customer's per-customer limit when a mobile is known) go at the sale price, the rest at the list price. The quote then has TWO lines for that product (sale-price line and list-price line). Example (`FlashSaleIT`): list Rs 100, sale 25% off, 3 units -> `unitPricePaise 7500`, `totalPaise 22500`. An ONLINE-only sale does not apply at the counter and vice versa. Weighed items are never discounted.
5. **Derived values.** Which units get the sale price; sale price; consumption of the sale quantity at sale time. The app must not apply flash prices itself (online) and must not assume `lines.length == basket rows`. To show a "SALE" badge compare `lines[].unitPricePaise` with the cached list price of that product.
6. **Edge cases.** A sale runs out between quote and sale: online sale re-prices and may fail with the usual 400 sum mismatch -> re-quote. Offline: the as-rung bill is recorded at the price the till charged and flash consumption is not taken (a manager sees a PRICE_CHANGED exception if the catalog list price differs). Coupons on flash lines: when `coupon.allowOnFlashItems` is off they get no coupon share and `COUPON_INVALID` reason `FLASH_ITEMS` can appear. Returns do not put units back into the flash sale.
7. **Dart.** Use `BillLine` from F3.
8. **Acceptance.**
   - Given a running POS flash sale at 25% off for a Rs 100 item, when 3 are quoted, then total 22500.
   - Given an ONLINE-only sale, when quoted at the counter, then the list price applies.
   - Given a weighed item with a POS sale, then 1 kg at Rs 45 quotes Rs 45.

---

## F13 Reports, lookups and audit-type screens a cashier can use

1. **Goal.** End-of-day cash-up and finding old bills.
2. **Who.** A cashier always sees their OWN figures (`cashierId` is ignored for a cashier); an admin sees everyone's unless `cashierId` is given.
3. **Fields.** `date` (`YYYY-MM-DD`, shop time, default today), `cashierId` (admin only).
4. **API sequence.**
   1. `GET /pos/reports/day?date=2026-11-09` -> `DayReport`:
```json
{ "date": "2026-11-09", "bills": 12, "salesPaise": 540000, "taxablePaise": 514000, "cgstPaise": 13000, "sgstPaise": 13000,
  "coinsRedeemed": 0, "creditNotes": 1, "refundsPaise": 12500, "netPaise": 527500,
  "paidByMode": { "CARD": 100000, "CASH": 300000, "UPI": 140000 },
  "refundedByMode": { "CARD": 0, "CASH": 12500, "UPI": 0 },
  "cashExpectedPaise": 287500, "creditGivenPaise": 0, "creditReturnedPaise": 0,
  "creditRecoveredPaise": 5000, "creditRecoveredByMode": { "CARD": 0, "CASH": 5000, "UPI": 0 } }
```
      (shape and relations from `PosFlowIT`; values illustrative). Relations: `netPaise = salesPaise - refundsPaise`; `cashExpectedPaise = paidByMode.CASH - refundedByMode.CASH + creditRecoveredByMode.CASH`. This is the day's cash since midnight; the drawer figure for a shift is `expectedCashPaise` of the shift (it includes the opening float).
   2. Bill lookups: `GET /pos/sales` (F9), `GET /pos/sales/{id}`.
   3. Credit: `GET /pos/credit/ledger`, `GET /pos/credit/account` (F5).
   4. Shift: `GET /pos/shifts/current` (own only).
5. **Derived values.** All totals are server-computed. `paidByMode` has the keys CARD, CASH, UPI always (zeros included); credit given is separate (`creditGivenPaise`).
6. **Edge cases.** A date with no bills returns zeros (200). Offline bills uploaded late appear on their `soldAt` day. Do not offer admin reports to a cashier (403).
7. **Dart.** `class DayReport { String date; int bills, salesPaise, taxablePaise, cgstPaise, sgstPaise, coinsRedeemed, creditNotes, refundsPaise, netPaise, cashExpectedPaise, creditGivenPaise, creditReturnedPaise, creditRecoveredPaise; Map<String,int> paidByMode, refundedByMode, creditRecoveredByMode; }`
8. **Acceptance.**
   - Given a cash bill of 12500, when the day report is read, then `bills` >= 1 and `netPaise == salesPaise - refundsPaise`.
   - Given a date in 2020, then `bills` 0.
   - Given a cashier token with another `cashierId`, then the report is still the caller's own.

---

## Error code cheat sheet

| code | HTTP | where | UI |
|---|---|---|---|
| VALIDATION_FAILED | 400 | everywhere | `details.fields` under inputs |
| UNAUTHENTICATED / FORBIDDEN | 401 / 403 | everywhere | refresh once / "not allowed" |
| NOT_FOUND | 404 | scan, bill, hold, account, shift, terminal payment | message |
| OUT_OF_STOCK | 409 | sale | reduce quantity |
| SHIFT_ALREADY_OPEN | 409 | open shift | go to current shift |
| SHIFT_NOT_OPEN | 422 | sale | open shift |
| VARIANCE_NEEDS_NOTE | 422 | close shift | note field |
| DISCOUNT_NEEDS_APPROVAL | 422 | sale | PIN modal |
| COUPON_INVALID | 422 | quote, sale | `details.reason`; MIN_ORDER has `shortfallPaise` |
| CREDIT_NOT_ALLOWED / CUSTOMER_BLOCKED / CREDIT_LIMIT_EXCEEDED / CREDIT_OVERPAYMENT | 422 | sale, repayment | F5 / F10 |
| UPI_NOT_CONFIGURED | 422 | upi-link | hide QR |
| RETURN_NEEDS_ADMIN / RETURN_EXCEEDS_SOLD | 422 / 409 | return | F9 |
| HOLD_LIMIT | 409 | hold | discard some |
| TERMINAL_BUSY / TERMINAL_REJECTED / TERMINAL_NOT_CONFIGURED / REFERENCE_REUSED | 409 / 422 / 503 / 409 | start card payment (generic) | F6 |
| TERMINAL_INACTIVE / PAYTM_NOT_CONFIGURED | 422 / 503 | start on the old Paytm-only endpoint | F6 |
| TERMINAL_CONFIRMS_ITSELF / TERMINAL_PAYMENT_CLOSED | 409 / 409 | confirm (manual channels only) | F6 |
| CANCEL_ON_MACHINE / ALREADY_PAID | 409 | cancel (Paytm) | F6 |
| NOT_VOIDABLE (also: any manual session) / PAYMENT_IN_USE / VOID_WINDOW_PASSED / VOID_REJECTED / VOID_UNKNOWN | 409 / 409 / 422 / 422 / 502 | void | F6 |
| TERMINAL_PAYMENT_NOT_PAID / _USED / _AMOUNT / _MODE | 422 / 409 / 422 / 422 | sale (_MODE only for Paytm) | F6 |
| DEFECTIVE_SELF_REVIEW / DEFECTIVE_ALREADY_REVIEWED | 422 / 409 | review | F9 |
| RATE_LIMITED | 429 | PIN guesses, card starts | wait |
| SERVICE_BUSY | 503 | any | retry after `Retry-After` |

---

## Doc vs code differences

Items 1 to 12 were corrected in place in `30` and `73` (and `71`) afterwards (see `README.md`); item 13 was already right in `30`.

Items where an older guide disagrees with the code (code wins above):

1. `30-pos-reports-purchasing.md` (Receipt section, "UI notes"): says there is no receipt endpoint for returns or credit repayments. Code has both: `GET /pos/sales/{id}/returns/{returnId}/receipt` and `GET /pos/credit/payments/{id}/receipt` (the same file's later addendum already documents them).
2. `30-...` (Business rules, Round-off): says `POST /pos/quote` has no manual discount input and the UI must compute the total itself. Code: the quote body accepts `manualDiscount` and returns `manualDiscountPaise`; use the server total.
3. `30-...` (Invoice numbers): says the financial year comes from server time, not `soldAt`. Code: `InvoiceNumbers.financialYear(soldAt)`, so an offline bill belongs to the year it was sold in.
4. `30-...` (Offline mode step 5a): says the upload response does not list shortfalls. Code: each result carries `exceptions` (including `OVERSOLD`).
5. `30-...` (Limits table): manual discount `reason` at most 200 characters. The request annotation allows 200 but the service rejects anything over 100 (400 `manualDiscount`); the same file's business rules say 1-100. Send at most 100.
6. `30-...` (credit picker addendum; fixed in 30): "Pick one and call `GET /pos/credit/account?mobile=` for the full number". The picker returns only a masked mobile and `customerId`, and the account lookup takes only `mobile`; there is no cashier endpoint to resolve a full number from an id. The cashier must type the full mobile.
7. (Resolved) `73` once mentioned `GET /pos/payment-channels` before it was built. It now exists and the Paytm machine is one of its channels (kind `CARD_TERMINAL`, provider `paytm`); `73` and `71` were rewritten for the unified flow and this playbook's F6 follows them. Old statements that the Paytm flow and the generic `manual` flow use different tables or JSON are gone: one row, one session id.
8. `73-...` ("not on an offline bill"): enforced only for as-rung offline bills (`rung` present: 400 on `payments[i].terminalPaymentId`). The legacy offline path (no `rung`) would still accept a `terminalPaymentId`, a coupon, coins and CREDIT. Do not rely on it; never send them offline.
9. `73-...` (Start): "201, or 200-ish replay". The code always answers 201 for a replayed Idempotency-Key. The controller header is declared optional but the service rejects a missing/blank/over-100-char key with 400 `VALIDATION_FAILED` (`Idempotency-Key`).
10. `73-...` (mode rule): the code decides UPI vs CARD by whether `payMethod` contains "UPI" (not equality), and a payment with no `payMethod` details is accepted on either mode.
11. `73-...` / `30-...`: neither says that a `terminalPaymentId` on a credit repayment line is silently ignored (repayments keep only mode/amount/reference).
12. `30-...` / F2: the guides present `updatedSince` as the way to refresh an offline copy, but `GET /pos/products` lists only ACTIVE products, so a product switched off after the last download never comes back through `updatedSince` (deleted barcodes also do not). The till must do periodic full downloads (this playbook says daily) and a scanned inactive product answers 404.
13. `30-...` holds: written per cashier in places; the code's hold list and the 100 limit are shared across all cashiers.

## Not verified

- `OUT_OF_STOCK` `details.items` shape is taken from the older guide (the 409 itself is confirmed by `PosOfflineSyncIT`); `FlashSaleIT` per-customer limit behaviour for a mobile-less counter customer; the Paytm integration against a real Paytm server (only the in-JVM fake is tested); exact `warnings` texts beyond the three coin warnings.
