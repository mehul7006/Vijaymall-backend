# P2. Playbook: suppliers, purchases, payments, returns, supplier account, reports and barcode labels (admin app)

Audience: a developer or LLM coding agent implementing the **admin app screens** for these features without reading backend code.
Verified against: `PurchasingController`, `SupplierController`, `PurchaseReportController`, `AdminLabelController`, `AdminLabelTemplateController`, `AdminBarcodeController`, the services/records behind them, migrations `V40`, `V70`, `V106`, `SecurityConfig`, and the integration tests `PurchasingV2IT`, `PurchasingCreditIT`, `LabelPrintingIT`. Where `70-purchases.md` / `72-barcode-labels.md` / `30-pos-reports-purchasing.md` disagree with the code, this file follows the code (list in section 16).

## Table of contents

0. Shared conventions (read first)
1. Suppliers (list, search, create, edit, deactivate)
2. New purchase form
3. Purchase list and detail (payment chips)
4. Recording payments (CASH/UPI/CARD/BANK/CHEQUE and CREDIT_NOTE)
5. Payables dashboard and ageing
6. Purchase returns (debit notes)
7. Supplier statement, supplier credit use, supplier refunds
8. Invoice attachment (upload, view, remove)
9. Editing a recorded purchase
10. Purchase reports (register, input GST, discounts, price history, lookup)
11. Store barcode generation
12. Label templates (CRUD)
13. Label printing (PDF/TSPL/ZPL/ESCPOS/JSON) and history
14. Error code cheat sheet
15. Build order
16. Doc vs code differences

---

## 0. Shared conventions

| Topic | Rule |
|---|---|
| Base | `/api/v1`. All routes below start with `/api/v1`. Bearer token in `Authorization`. |
| Roles | Everything under `/admin/suppliers`, `/admin/purchases`, `/admin/purchase-returns`, `/admin/reports/purchases`, `/admin/products/{id}/purchase-prices`: **ADMIN only** (CASHIER gets 403 `FORBIDDEN`). Labels (`/admin/labels*`, `/admin/variants/{id}/barcode/generate`) and **reading** label templates: ADMIN and CASHIER. Creating/changing/deleting templates: ADMIN only. CUSTOMER 403, no token 401. |
| Money | Integer **paise** in every field named `*Paise`. Dart `int`. The only fractional money fields are the landed unit costs (`landedUnitCostPaise`, `landedUnitCostRoundedPaise`, `lastLandedUnitCostPaise`): JSON numbers with up to 4 decimals of a paisa. Parse as `double` (or `Decimal`), never `int`. |
| Dates | `yyyy-MM-dd` India shop dates for `invoiceDate`, `receivedDate`, `dueDate`, `expiryDate`, `from`, `to`, `asOf`. Timestamps (`paidAt`, `createdAt`, `at`...) are ISO-8601 UTC instants ending `Z`. IDs are UUID strings. |
| Error body | `{ "code", "message", "traceId", "details": { "fields": { "<jsonPath>": "<text>" }, ... } }`. Switch on `code`; show `details.fields[x]` under the matching input; show `message` as toast; show `traceId` on "something went wrong". Field paths look like `items[2].qty`; header problems use the header name (`Idempotency-Key`). |
| Paged list | `{ items, page, size, total, totalExact, hasMore }`; `page` starts 0; `size` is clamped 1..50 (default 20 unless stated); `total` caps at 10000 (`totalExact:false` -> show "10,000+"); page until `hasMore == false`. |
| Idempotency-Key | Mandatory header (400 `VALIDATION_FAILED` on field `Idempotency-Key`, also when blank) on: `POST /admin/purchases`, `POST /admin/purchases/{id}/payments`, `POST /admin/purchases/{id}/returns`, `POST /admin/suppliers/{id}/refunds`. Optional on `POST /admin/suppliers`. Not used by PUT/PATCH/activate/deactivate/attachment/labels. Generate a UUID v4 when the screen opens (or when the user taps Save), persist it with the pending action, reuse it for byte-identical retries. Same key + same body returns the first answer (status 201, same body) and changes nothing. Same key + different body = 409 `IDEMPOTENCY_KEY_REUSED`. A rejected request (any 4xx) stores nothing, so the key stays free. |
| Not found | 404 `NOT_FOUND` for unknown ids. |

Dart helpers used below:

```dart
String newKey() => const Uuid().v4();
Options idem(String key) => Options(headers: {'Idempotency-Key': key});
Page<T> pageOf<T>(Map j, T Function(Map) f) => Page(
  (j['items'] as List).map((e) => f(e as Map)).toList(), j['page'], j['size'], j['total'], j['totalExact'], j['hasMore']);
class ApiError { final int status; final String code, message, traceId; final Map<String,String> fields; final Map details; }
```

---

## 1. Suppliers

### 1.1 Goal
Maintain the supplier master: search/list, add, edit, switch off/on. Show each supplier's money position (`totalDuePaise`, `creditPaise`) so the admin knows whom to pay.

### 1.2 Who / settings
ADMIN. No settings. Names are unique after trimming, collapsing runs of spaces and ignoring case ("Agro  Traders " == "agro traders").

### 1.3 Screens and fields

**Supplier list** (search box + "Show switched-off" toggle + infinite scroll)

| field | type | required | validation / default |
|---|---|---|---|
| `q` | string query | no | matches name, phone, GSTIN (contains, any case; `%` and `_` are literal). Debounce 300 ms. |
| `active` | bool query | no | omit = both; the purchase-form picker must send `active=true` |
| `page`,`size` | int | no | 0 / 20 (max 50) |

Row shows `name`, `phone`, `gstin`, `purchaseCount`, `lastInvoiceNo` (`""` when none), `lastPurchaseAt` (null when none), `totalDuePaise` (negative = supplier owes shop; display as "Credit"), `creditPaise`, an "Inactive" chip when `active == false`.

**Supplier form** (create = POST, edit = PUT; PUT replaces ALL fields, so always send the whole form)

| field | type | required | server validation | default |
|---|---|---|---|---|
| `name` | string | yes | not blank, trimmed, max 120 | |
| `gstin` | string | no | max 15; upper-cased by server; if non-empty must match `^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$` else 400 on `gstin` ("not a valid GSTIN") | `""` |
| `stateCode` | string | no | exactly 2 digits (400 "2 digit GST state code"); if `gstin` present it must equal `gstin[0..2]` (400 "does not match the state in the GSTIN (24)"); if left blank it is taken from the GSTIN | `""` |
| `phone` | string | no | max 20, only digits, space, `+`, `-` (400 on `phone`) | `""` |
| `address` | string | no | max 300 | `""` |
| `paymentTermsDays` | int | no | 0..365 (400 otherwise). Sending null on PUT resets it to **0** | 0 |

Client mirror: uppercase GSTIN as typed; auto-fill `stateCode` from the first two GSTIN digits and make the field read-only while a GSTIN is present.

### 1.4 API sequence
1. `GET /admin/suppliers?q=&active=&page=0&size=20` -> page of SupplierView (fields in 1.7). Re-query on each `q` change.
2. Open a supplier: `GET /admin/suppliers/{id}` (404 `NOT_FOUND`).
3. Create: `POST /admin/suppliers`, optional `Idempotency-Key`, body:
```json
{ "name": "Agro Traders", "gstin": "24ABCDE1234F1Z5", "stateCode": "24", "phone": "98250 11111", "address": "Rajkot", "paymentTermsDays": 30 }
```
   -> 201 SupplierView. Errors: 400 `VALIDATION_FAILED` (map `details.fields`); **409 `SUPPLIER_EXISTS`** with `details.supplierId` (existing supplier) and `details.fields.name`: show "Already exists - open it" linking to `details.supplierId`.
4. Edit: `PUT /admin/suppliers/{id}` same body -> 200. Errors as above plus 404, 409 `SUPPLIER_EXISTS` (name belongs to another supplier).
5. Switch off: `POST /admin/suppliers/{id}/deactivate` (no body) -> 200 supplier with `active:false`. Switch on: `POST /admin/suppliers/{id}/activate`. Both idempotent; unknown id 404.

### 1.5 State / derived
- `active` is the only state. Inactive suppliers keep their history but a new purchase naming them (by `supplierId` or by name) fails 400 on `supplierId`/`supplierName`: "this supplier is switched off". Hide inactive suppliers from pickers.
- `totalDuePaise == balancePaise` (same number), computed by server from all purchases. `creditPaise >= 0` is the part of a negative balance that can still be used or refunded. Never compute them in the app.
- A supplier can also be created implicitly by a purchase that sends only `supplierName`; phone/GSTIN/state sent with that purchase fill only **missing** supplier values, never overwrite.

### 1.6 Edge cases
- Duplicate-name race: handled by 409 `SUPPLIER_EXISTS`; offer "open existing".
- Retry of create: send an `Idempotency-Key`; replay returns the first supplier (201).
- Empty list: "No suppliers yet - add one"; search with no match: show query.
- Large lists: always paged; do not prefetch everything.
- Editing the GSTIN of the master does not change past purchases (their GSTIN text is per document, see section 9).
- No delete endpoint: only switch off.

### 1.7 Dart models
```dart
class Supplier {
  final String id, name, phone, gstin, stateCode, address, lastInvoiceNo; // strings never null ("" when empty)
  final int paymentTermsDays, purchaseCount, totalDuePaise, balancePaise, creditPaise;
  final bool active;
  final DateTime createdAt;
  final DateTime? lastPurchaseAt; // null when no purchases
}
class SupplierBody { String name; String? gstin, stateCode, phone, address; int? paymentTermsDays; }
```

### 1.8 Acceptance
- Given a supplier named "Agro Traders", When I create "  agro   traders ", Then I get 409 `SUPPLIER_EXISTS` and the UI offers to open `details.supplierId`.
- Given GSTIN `24ABCDE1234F1Z5`, When I type stateCode `27`, Then the field is blocked client-side; if sent, server answers 400 on `stateCode`.
- Given phone "abc", Then 400 on `phone`.
- Given `paymentTermsDays: 400`, Then 400 on `paymentTermsDays`.
- Given an active supplier, When I tap Switch off twice, Then both return 200 `active:false`.
- Given an inactive supplier, When a purchase is saved with its `supplierId`, Then 400 on `supplierId` and the form shows "switched off".
- Given a search `q=22222`, Then suppliers whose phone contains 22222 are listed.
- Given `q=%_`, Then no results (literal match).

---

## 2. New purchase form

### 2.1 Goal
Record a supplier delivery in one request: adds stock (`qty + freeQty`), computes GST/discount/freight/round-off, optionally records the first payment, optionally updates product MRP/price.

### 2.2 Who / settings
ADMIN. Settings that matter:
- `purchase.gstMode` (REGULAR | COMPOSITION | NONE, default REGULAR) in `GET/PUT /admin/config`, group "GST and bills": the form's default GST mode. Read it from the config response once and preselect; the field can always be overridden per purchase.
- Shop `stateCode` (`/admin/shop-settings`, default `24`): decides IGST vs CGST+SGST (2.5.3).
- Per product (`/admin/products/{id}/pos`): `gstPercent` and `hsn` defaults for lines.

### 2.3 Screens and fields

**Step A: header**

| field | type | required | server validation | default |
|---|---|---|---|---|
| `supplierId` | uuid | one of the two | must exist and be active | |
| `supplierName` | string | one of the two | 1..120; found by name or created on the fly | |
| `supplierPhone` | string | no | max 20, digits/space/+/- | |
| `supplierGstin` | string | no | max 15, GSTIN pattern | |
| `supplierStateCode` | string | no | 2 digits; must match GSTIN's first two digits when both given | supplier's stored state |
| `invoiceNo` | string | no | max 60. A repeat for the same supplier only adds a `warnings` entry (never blocks) | `""` |
| `invoiceDate` | date | no | not in the future | today (India) |
| `receivedDate` | date | no | not in the future | today |
| `note` | string | no | max 200 | |
| `attachmentNote` | string | no | max 500 (free text/link). The invoice **file** is uploaded after saving (section 8) | |
| `gstMode` | enum | no | `REGULAR`,`COMPOSITION`,`NONE` (any case); other = 400 on `gstMode` | config `purchase.gstMode` |
| `docDiscountPercent` | decimal | no | 0..100, max 2 decimals | |
| `docDiscountPaise` | int | no | >= 0; give only one of percent/amount (else 400 on `docDiscountPaise`); not above the goods value after line discounts | |
| `freightPaise` | int | no | >= 0 | 0 |
| `freightGstPercent` | int | no | one of 0,3,5,12,18,28; ignored (treated 0) when freight is 0 | 0 |
| `roundToRupee` | bool | no | | false |
| `expectedTotalPaise` | int | no | guard, see 2.5.6 | omit |
| `paymentStatus` | enum | no | `PAID`,`PARTIAL`,`PENDING` (any case) | derived from `paidPaise` |
| `paidPaise` | int | conditional | PENDING: 0 or omitted; PAID: omitted or equal to the server total; PARTIAL: > 0 and < total; also never negative or above total | |
| `paymentMode` | enum | when something is paid | `CASH`,`UPI`,`CARD`,`BANK`,`CHEQUE` (400 on `paymentMode`) | |
| `paymentRef` | string | no | max 80 (UTR, cheque no.) | |
| `moneyAccountId` | uuid | no | stored, **not validated** | |
| `dueDate` | date | no | not before `invoiceDate` | `invoiceDate + supplier.paymentTermsDays` if terms > 0, else null |
| `items` | list | yes | 1..200 lines; a product at most once (400 on `items`) | |

**Step B: line (one per product)**

| field | type | required | server validation | default |
|---|---|---|---|---|
| `productId` | uuid | yes | must exist (400 on `productId` "a product does not exist") | |
| `qty` | int | yes | 1..1,000,000 | |
| `freeQty` | int | no | >= 0; `qty + freeQty` <= 1,000,000 (400 on `freeQty`) | 0 |
| `unitCostPaise` | int | yes | 0..100,000,000, invoice price **before tax**, whole paise | |
| `discountPercent` | decimal | no | 0..100, max 2 decimals | |
| `discountPaise` | int | no | >= 0, not above `qty*unitCostPaise`; give percent OR amount | |
| `gstPercent` | int | no | one of 0,3,5,12,18,28 (400 on `gstPercent`) | the product's GST % |
| `cessPercent` | decimal | no | 0..100, max 2 decimals | 0 |
| `hsn` | string | no | blank or 4..8 digits (400 on `hsn`) | the product's HSN |
| `mrpPaise` | int | no | >= 0 | |
| `sellingPricePaise` | int | no | >= 0; not above `mrpPaise` when both sent (400 on `sellingPricePaise`) | |
| `applyToVariant` | bool | no | needs `mrpPaise` or `sellingPricePaise` (400 on `applyToVariant`) | false |
| `batchNo` | string | no | max 40 | `""` |
| `expiryDate` | date | no | none enforced on the date itself; it becomes the expiry of the stock lot this line opens and drives first-expiry-first selling and the expiring list (`74`, `P5` section 4) | |

Client mirror: restrict GST/freight GST dropdowns to the six rates; hide `discountPaise` when percent filled (and vice versa); disable Save while `qty < 1`; clamp percent inputs to 2 decimals; when `mrpPaise` and `sellingPricePaise` both set show an inline error if price > MRP; show the "Apply MRP/price to product" switch only when one of them is filled.

### 2.4 API sequence
1. On form open: `GET /admin/config` once (read `purchase.gstMode`), `GET /admin/shop-settings` (shop `stateCode`, for hint text only), generate `idemKey = newKey()`.
2. Supplier picker: `GET /admin/suppliers?q=&active=true&size=10`. If the user picks one, send `supplierId`; if they type a new name send `supplierName`. If you hold the supplier's state/GSTIN, show "Inter-state (IGST)" hint when its state differs from the shop's (hint only; server decides).
3. Product picker: admin product list `GET /admin/products?q=&size=20` (see `10-customer-and-catalog.md`), then on pick `GET /admin/purchases/lookup?productId=` -> prefill:
```json
{ "productId": "…", "name": "Bath Soap", "gstPercent": 18, "hsn": "3401", "mrpPaise": 15000, "pricePaise": 14000, "qtyOnHand": 12,
  "lastPurchaseCostPaise": 10000, "lastLandedUnitCostPaise": 8333.3333, "lastSupplierName": "Agro Traders", "lastInvoiceDate": "2026-11-09", "lastPurchaseId": "…" }
```
   `last*` are null when never purchased. Prefill `gstPercent`, `hsn`; show `lastPurchaseCostPaise` as a hint under the cost field (`lastLandedUnitCostPaise` is fractional). 404 `NOT_FOUND` for an unknown product. Link "Price history" to section 10.5.
4. Save: `POST /admin/purchases` with header `Idempotency-Key: <idemKey>`:
```json
{
  "supplierName": "Agro Traders", "supplierGstin": "24ABCDE1234F1Z5", "supplierPhone": "98250 11111",
  "invoiceNo": "GST-1", "invoiceDate": "2026-11-09", "gstMode": "REGULAR",
  "docDiscountPaise": 1000, "freightPaise": 500, "freightGstPercent": 18, "roundToRupee": true,
  "paymentStatus": "PARTIAL", "paidPaise": 50000, "paymentMode": "UPI", "paymentRef": "UTR-9", "dueDate": "2026-11-19",
  "items": [ { "productId": "…", "qty": 10, "freeQty": 2, "unitCostPaise": 10000, "hsn": "340120",
               "mrpPaise": 15000, "sellingPricePaise": 14000, "batchNo": "B-1", "expiryDate": "2030-01-31", "applyToVariant": true } ]
}
```
   (this body is the shape used by `PurchasingV2IT`: 10 paid + 2 free at Rs 100, GST 18 %, REGULAR gives `taxablePaise 100000`, `cgstPaise 9000`, `sgstPaise 9000`, `totalCostPaise 118000`, line `landedTotalPaise 100000`, `landedUnitCostPaise 8333.3333`, `landedUnitCostRoundedPaise 8333`, stock +12.)
5. 201 -> PurchaseView (section 3.7). Read: `id`, `totalCostPaise`, `duePaise`, `paymentStatus`, `warnings[]`, and each item `id` (needed for returns). Navigate to detail. If `warnings` is non-empty show a banner "Same invoice number was entered before: check you are not counting this delivery twice" (link to the purchase id inside the text is a UUID in parentheses; do not parse, just show) and do not undo.
6. Errors:

| status / code | meaning | UI |
|---|---|---|
| 400 `VALIDATION_FAILED` | field errors in `details.fields` (`items[0].qty`, `gstMode`, `paymentMode`, `paidPaise`, `dueDate`, `supplierName`...). Also "a product appears twice", "a product does not exist", "this supplier is switched off", "the discount cannot be more than the line amount". | highlight; keep the same idem key (nothing was stored) |
| 409 `IDEMPOTENCY_KEY_REUSED` | key already used for a different body | generate a new key; tell user to check the list before re-saving if they edited after an uncertain save |
| 422 `TOTAL_MISMATCH` | `expectedTotalPaise` != server total; `details.totalPaise` = server's, `details.fields.expectedTotalPaise` = "server total is N" | show "Total is Rs N, not Rs M. Accept?"; on accept resend with `expectedTotalPaise = details.totalPaise` |
| 403 | not admin | |
| network timeout / 5xx | outcome unknown | resend the **identical** body with the **same** key; if the user edited the form meanwhile, first search the purchase list by supplier+invoice date before saving again |

### 2.5 State / derived values (the heart of this form)

**2.5.1 Is there a preview/compute endpoint?** No. There is no `/purchases/preview` or `/compute`. The server computes everything at save time. The app must therefore compute **nothing authoritative**: it must never send line totals, tax amounts, landed costs or the final total as facts (the request has no such fields). After saving, display only server figures.

**2.5.2 What the app MAY compute (display-only estimate, label it "approx. - final amount is calculated by the server")**
- `gross = qty * unitCostPaise` per line (this equals the response's `lineCostPaise`).
- A running count of lines and of units `qty + freeQty`.
- An optional approximate total using the rules below. If you implement it, it must use integer paise and the exact order; but the server's spreading of document discount and freight uses largest-remainder allocation, so an estimate can differ by a few paise. **Therefore do not send `expectedTotalPaise` from an estimate** unless the user typed the invoice grand total from the paper bill (see 2.5.6).

Exact server order (for the estimate), all in paise with half-up rounding:
1. `gross = qty*unitCostPaise` (free units cost nothing).
2. Line discount = `round(gross*discountPercent/100)` if percent > 0 else `discountPaise`. `afterLine = gross - lineDiscount`.
3. Document discount = `round(sum(afterLine)*docDiscountPercent/100)` if percent > 0 else `docDiscountPaise`; spread over lines in proportion to `afterLine`; `taxable = afterLine - share`. (So the document discount lowers the GST base.)
4. Per line: rate = 0 if mode is NONE else `gstPercent`; `gst = round(taxable*rate/100)`; `cess = round(taxable*cessPercent/100)` (0 under NONE). Intra-state: `cgst = floor(gst/2)`, `sgst = gst - cgst` (odd paisa to SGST); inter-state: all `igst`. `lineTotal = taxable + gst + cess`.
5. Freight GST = `round(freight*freightGstPercent/100)` (0 under NONE), split like line GST.
6. `subtotal = sum(lineTotal) + freight + freightGst`. If `roundToRupee`: `roundOff = floor((subtotal+50)/100)*100 - subtotal` (can be negative), else 0. `totalCostPaise = subtotal + roundOff`.

**2.5.3 IGST vs CGST+SGST rule.** Supplier state is, in order: `supplierStateCode` sent with the purchase, else the supplier's stored state (supplier master; if it had no state its GSTIN's first two digits were stored on create). If a supplier state is known and differs from the shop `stateCode` -> `interState:true`, tax is IGST only. If the state is unknown -> treated as intra-state (CGST+SGST). A sent `supplierStateCode` that disagrees with a sent GSTIN is 400 on `supplierStateCode`. The response `interState` shows the decision. Note: an *edit* of the state text later (section 9) never recomputes tax.

**2.5.4 GST mode and cost**

| mode | tax charged | tax in unit cost? | input credit |
|---|---|---|---|
| `REGULAR` | yes (CGST+SGST or IGST, plus cess) | no: cost = taxable value + freight share | tax is "creditable" in the input-GST report |
| `COMPOSITION` | yes | yes: GST + cess (and freight GST) are part of cost | none (reported as non-creditable) |
| `NONE` | no: all GST/cess rates treated as 0 (also freight GST) | n/a | none |

Landed unit cost (server) = (line taxable + non-creditable tax + line's share of freight [+ freight GST under COMPOSITION]) / (qty + freeQty), kept to 4 decimals of a paisa. Freight is shared in proportion to taxable value (by units when all taxable are 0). `totalCostPaise` is **what the supplier is owed** (goods, tax, freight, round-off), under every mode.

**2.5.5 Other server-derived values:** `taxablePaise`, `cgstPaise`, `sgstPaise`, `igstPaise`, `cessPaise` (document totals include tax on freight), `lineDiscountPaise`, `docDiscountPaise` (amount, even when a percent was given), `docDiscountPercent` (null when an amount was used), `freightGstPaise`, `roundOffPaise`, line `docDiscountSharePaise`, `lineTotalPaise`, `landedTotalPaise`, `landedUnitCostPaise`, `landedUnitCostRoundedPaise`, `paymentStatus`, `paidPaise`, `duePaise`, `overdue`, `dueDate` default.

**2.5.6 `expectedTotalPaise` and TOTAL_MISMATCH.** Optional guard: if present and different from the server total -> 422 `TOTAL_MISMATCH`, nothing recorded (the whole transaction, including a supplier that would have been created on the fly, is rolled back). Use it only when the user typed the bill's grand total from the paper invoice ("Invoice total" field): then a mismatch tells them their lines do not add up to the bill. Flow: send the typed total; on 422 show both numbers (`details.totalPaise` is the server's), let the user fix the lines, or accept the server total and resend with `expectedTotalPaise = details.totalPaise`. A repeat that changes `expectedTotalPaise` has a different body (new fingerprint), so after a *definite* 422 either keep or rotate the key (nothing was stored); after an *uncertain* outcome keep the key only for identical bodies.

**Optional "Check total" button (not an official preview).** Because the check runs after the maths and before anything is saved, sending `expectedTotalPaise: -1` (never equal to a real total) always returns 422 `TOTAL_MISMATCH` with the authoritative `details.totalPaise` when the rest of the body is valid, and 400 field errors otherwise. It returns only the total, not the breakdown, and is derived from reading the code path (the integration test covers the same path with `expectedTotalPaise: 1`). Use it, if at all, behind a clearly labelled "Check total with server" button; confirm with the backend team before relying on it.

**2.5.7 Payment on creation:** `paymentStatus`/`paidPaise` rules in the table above. `PAID` with no `paidPaise` means paid in full (and requires `paymentMode` unless the total is 0, in which case no payment row is created). Omitting `paymentStatus` and `paidPaise` = `PENDING`. The amount paid becomes the first row of `payments`. Because the server total is unknown to the app before save, the safest UI is three choices: **Unpaid**, **Paid in full** (send `paymentStatus:"PAID"` and no `paidPaise`), **Part paid** (send `PARTIAL` with an amount; a 400 on `paidPaise` says "must be more than 0 and less than the total (N)" - show `N`). Prefer recording part payments afterwards via section 4 when the exact total matters.

**2.5.8 Price apply:** a line with `applyToVariant:true` updates that product's MRP/price in the same transaction (audited as `PURCHASE_PRICE_APPLY`). The catalog patch rules apply (selling price must not exceed MRP, so sending only a price above the product's current MRP can fail with a 400 mentioning the product's MRP; send both together).

### 2.6 Edge cases
- Double tap on Save: same key -> second call returns the first purchase (201) and stock is added once.
- Timeout: retry identical body + key. Never regenerate the key on retry.
- Same product twice: client must merge rows (server 400 on `items`).
- Weighed products are stocked in grams; the purchase `qty` is then grams and unit cost semantics are undefined by the backend; warn or block weighed products if the shop sells by weight (unverified business rule).
- Back-dated invoices: `invoiceDate` may be in the past; future dates 400. `dueDate` before `invoiceDate` 400.
- Large purchases: max 200 lines; paginate nothing, but virtualize the list.
- Offline: do not queue purchases without persisting the key + body together.
- Duplicate invoice warning appears only in the 201 of POST (and in PATCH of the invoice number); GET always returns `warnings: []`.
- Percent inputs with 3 decimals are rejected ("0 to 100 with at most 2 decimals").
- Money inputs: users type rupees; convert to paise with integer math (`(rupees * 100).round()` on a decimal string parse, not on a double multiplication).

### 2.7 Dart models
```dart
enum GstMode { regular, composition, none }       // JSON REGULAR|COMPOSITION|NONE
class PurchaseLineInput {
  String productId; int qty; int unitCostPaise;
  int? freeQty, discountPaise, gstPercent, mrpPaise, sellingPricePaise;
  double? discountPercent, cessPercent;          // send as JSON numbers, max 2 decimals
  String? hsn, batchNo; String? expiryDate;      // yyyy-MM-dd
  bool? applyToVariant;
}
class PurchaseInput {
  String? supplierId, supplierName, supplierPhone, supplierGstin, supplierStateCode;
  String? invoiceNo, invoiceDate, receivedDate, note, attachmentNote, gstMode;
  double? docDiscountPercent; int? docDiscountPaise, freightPaise, freightGstPercent, expectedTotalPaise, paidPaise;
  bool? roundToRupee;
  String? paymentStatus, paymentMode, paymentRef, dueDate, moneyAccountId;
  List<PurchaseLineInput> items;
}
class PurchaseLookup {
  String productId, name, hsn; int gstPercent, mrpPaise, pricePaise, qtyOnHand;
  int? lastPurchaseCostPaise; double? lastLandedUnitCostPaise; String? lastSupplierName, lastInvoiceDate, lastPurchaseId;
}
```

### 2.8 Acceptance
- Given product GST 18 %, REGULAR, qty 10 free 2, cost 10000, When I save, Then `taxablePaise==100000`, `cgstPaise==9000`, `sgstPaise==9000`, `totalCostPaise==118000`, line `landedUnitCostPaise==8333.3333`, and stock rises by 12.
- Given a supplier GSTIN starting `27` and shop state `24`, When I save, Then `interState==true`, `igstPaise>0`, `cgstPaise+sgstPaise==0`.
- Given `gstMode:"composition"` (lowercase), Then response `gstMode=="COMPOSITION"` and landed cost includes tax.
- Given `roundToRupee:true`, Then `totalCostPaise % 100 == 0` and `roundOffPaise` may be negative.
- Given `docDiscountPercent:5` and `docDiscountPaise:5`, Then 400 on `docDiscountPaise`.
- Given `expectedTotalPaise:1`, Then 422 `TOTAL_MISMATCH`, no purchase created, stock unchanged.
- Given the same key and body twice, Then both return the same purchase id and stock is added once.
- Given the same key and `freeQty` changed, Then 409 `IDEMPOTENCY_KEY_REUSED`.
- Given `paymentStatus:"PARTIAL"` without `paidPaise`, Then 400 on `paidPaise`.
- Given `paymentStatus:"PAID"` and `paidPaise:10` (total larger), Then 400 on `paidPaise`.
- Given `paidPaise>0` without `paymentMode`, Then 400 on `paymentMode`.
- Given `gstPercent:7`, Then 400 on `gstPercent` (or `items[0].gstPercent` if validation-layer).
- Given `mrpPaise:100, sellingPricePaise:200`, Then 400 on `sellingPricePaise`.
- Given `applyToVariant:true` with `mrpPaise:25000`, Then the product's MRP becomes 25000.
- Given an invoice number already used for the supplier, Then 201 with a non-empty `warnings`.
- Given a switched-off supplier name, Then 400 on `supplierName` and no stock change.
- Given the supplier has `paymentTermsDays:15` and `invoiceDate` 3 days ago, Then `dueDate` is today + 12.

---

## 3. Purchase list and detail

### 3.1 Goal
Browse purchases with payment-status chips, find what is unpaid/overdue, open a purchase to pay, return goods, attach invoice, edit notes.

### 3.2 Who / settings
ADMIN.

### 3.3 Screens and fields

**List filters** (all optional query params)

| param | type | validation |
|---|---|---|
| `paymentStatus` | enum | `PENDING`,`PARTIAL`,`PAID` (any case). Anything else (including `OVERDUE`) is 400 on `paymentStatus` |
| `overdue` | bool | `true` = only purchases with `dueDate < today(India)` and `duePaise > 0`; default false |
| `supplierId` | uuid | |
| `supplier` | string | name contains, any case |
| `from`,`to` | date | invoice dates inclusive; `from` after `to` = 400 on `from` |
| `rounding` | enum | `EXACT`,`PAISE`(default),`RUPEE`; else 400 on `rounding` |
| `page`,`size` | int | 0 / 20, size 1..50 |

Order: newest first.

**Chips** (derive from the response, never from your own maths)
| chip | rule |
|---|---|
| PAID | `paymentStatus == "PAID"` (includes fully returned/credit: `duePaise <= 0`) |
| PARTIAL | `paymentStatus == "PARTIAL"` |
| PENDING | `paymentStatus == "PENDING"` |
| OVERDUE | **not a status value**: show when `overdue == true` (server field, India date), in addition to PENDING/PARTIAL. The filter for it is `overdue=true`, not `paymentStatus=OVERDUE`. |
| Supplier credit | `duePaise < 0` (display as "Supplier owes you Rs |due|") |

### 3.4 API sequence
1. `GET /admin/purchases?paymentStatus=PENDING&overdue=false&page=0&size=20` -> page of PurchaseView (each has `items` and `payments`; `warnings` always `[]`). Header strip: `GET /admin/purchases/payables` (section 5).
2. Detail: `GET /admin/purchases/{id}?rounding=PAISE` (404 `NOT_FOUND`). Show lines with `landedUnitCostRoundedPaise` and a rounding switch (re-request with `rounding=EXACT|PAISE|RUPEE`; response echoes `rounding`).
3. After any mutation (payment, return, edit, attachment) re-fetch the detail (the mutation endpoints return the updated purchase except returns, which return the debit note).
4. Buttons: Add payment (visible if `duePaise > 0`), Return goods (if any line has `qty + freeQty - returnedQty > 0`), Debit notes (`GET /admin/purchases/{id}/returns`), Invoice file (section 8), Edit (section 9).

### 3.5 State / derived
`duePaise = totalCostPaise - returnedPaise - paidPaise + creditUsedPaise` (the last term is internal; trust `duePaise`). `paymentStatus`: `PAID` if due <= 0; `PENDING` if nothing paid and due > 0; else `PARTIAL`. Computed by the database; the client only reads. `overdue` computed by server. Status machine: PENDING -> PARTIAL -> PAID by payments; a debit note can move any state toward PAID (even past zero into negative due). PAID can show `duePaise < 0`; no further payments are accepted (409 `ALREADY_PAID`).

### 3.6 Edge cases
- Empty state: "No purchases yet".
- A purchase migrated from before v2 shows as PAID with one `CASH` payment, reference `LEGACY`.
- Payments with mode `CREDIT_NOTE` are not money out: in any cash summary exclude them; show as "Adjusted with credit note DN-xxxx" (`reference` holds the note number).
- Concurrent payment from two devices: second one may get `PAYMENT_EXCEEDS_DUE` / `ALREADY_PAID`; refresh.
- `total` caps at 10,000.

### 3.7 Dart models (PurchaseView, copied from the record)
```dart
class PurchaseView {
  final String id, supplierId, supplierName, supplierPhone, supplierGstin, supplierStateCode; // phone/gstin/state: "" when unknown
  final bool interState, overdue, hasAttachment;
  final String gstMode;                 // REGULAR|COMPOSITION|NONE
  final String invoiceNo, note, attachmentNote, rounding;
  final DateTime invoiceDate, receivedDate; final DateTime? dueDate;
  final int lineDiscountPaise, docDiscountPaise, taxablePaise, cgstPaise, sgstPaise, igstPaise, cessPaise,
            freightPaise, freightGstPercent, freightGstPaise, roundOffPaise, totalCostPaise,
            paidPaise, returnedPaise, duePaise;   // duePaise may be negative; roundOffPaise may be negative
  final double? docDiscountPercent;     // null when an amount was used
  final String paymentStatus;           // PENDING|PARTIAL|PAID
  final List<PurchasePayment> payments;
  final String? createdBy; final DateTime createdAt;
  final List<PurchaseItemView> items;
  final List<String> warnings;          // only filled by POST /purchases and PATCH invoiceNo
  final String? attachmentUrl;          // "/api/v1/admin/purchases/{id}/attachment" or null
}
class PurchaseItemView {
  final String id, productId, name, hsn, batchNo;
  final int qty, freeQty, unitCostPaise, lineCostPaise /* qty*unitCost, gross */, lineDiscountPaise, docDiscountSharePaise,
            taxablePaise, gstPercent, cgstPaise, sgstPaise, igstPaise, cessPaise, lineTotalPaise, returnedQty, landedTotalPaise;
  final double? discountPercent; final double cessPercent;
  final double landedUnitCostPaise, landedUnitCostRoundedPaise;  // FRACTIONAL
  final int? mrpPaise, sellingPricePaise; final DateTime? expiryDate; final bool applyToVariant;
}
class PurchasePayment {
  final String id, mode, reference, note; final int amountPaise; final String? moneyAccountId, createdBy; final DateTime paidAt;
}
```

### 3.8 Acceptance
- Given a purchase 40 days old, due 5 days ago, nothing paid, Then it shows PENDING + OVERDUE and appears in `?overdue=true`.
- Given `paymentStatus=NOPE` or `OVERDUE`, Then 400.
- Given `from` later than `to`, Then 400 on `from`.
- Given `rounding=RUPEE`, Then `landedUnitCostRoundedPaise` is a multiple of 100 and `rounding=="RUPEE"`.
- Given a fully paid purchase after a return, Then chip PAID and "Supplier owes you" with the absolute due.
- Given `size=1`, Then `items.length==1` and `hasMore==true` when more exist.

---

## 4. Recording payments

### 4.1 Goal
Pay the supplier in parts or in full against one purchase, either with money (CASH/UPI/CARD/BANK/CHEQUE) or with supplier credit (CREDIT_NOTE).

### 4.2 Who / settings
ADMIN. `moneyAccountId` is accepted and stored but the money-account module does not validate it: send null unless you have a real id.

### 4.3 Fields (`POST /admin/purchases/{id}/payments`)

| field | type | required | validation |
|---|---|---|---|
| `amountPaise` | int | yes | >= 1 (400 `amountPaise`); not above `duePaise` (422) |
| `mode` | enum | yes | `CASH`,`UPI`,`CARD`,`BANK`,`CHEQUE`,`CREDIT_NOTE` (any case); unknown = 400 on `mode`. Max 12 chars |
| `reference` | string | no | max 80 (UTR / cheque no.). For CREDIT_NOTE the server stores the debit note number instead |
| `note` | string | no | max 200 |
| `paidAt` | instant | no | default now; not more than 10 minutes in the future (400 on `paidAt`) |
| `moneyAccountId` | uuid | no | stored only; unused for CREDIT_NOTE |
| `debitNoteId` | uuid | only for CREDIT_NOTE | required then (400 on `debitNoteId`) |

UI: amount field prefilled with `duePaise`; "Mark paid" button = send `amountPaise = duePaise`; quick chips 25/50 %.

### 4.4 API sequence
1. Open sheet from the detail (`duePaise > 0`). Create `key = newKey()`.
2. Money payment: `POST /admin/purchases/{id}/payments`, `Idempotency-Key: key`:
```json
{ "amountPaise": 4000, "mode": "CASH", "reference": "UTR1", "note": "", "paidAt": "2026-11-09T05:00:00Z", "moneyAccountId": null }
```
   -> **201** with the purchase as it is now (new `payments` row, updated `paidPaise`, `duePaise`, `paymentStatus`). Replace local state with it.
3. Credit payment: choose a debit note of the **same supplier** that still has credit (see section 7.4 for listing): body
```json
{ "amountPaise": 30000, "mode": "CREDIT_NOTE", "debitNoteId": "<purchase return id>", "note": "adjusted" }
```
   -> 201 purchase; the new payment has `mode:"CREDIT_NOTE"`, `reference:"DN-000012"`.
4. Errors:

| status / code | cause | UI |
|---|---|---|
| 400 `VALIDATION_FAILED` | `amountPaise`<1, `mode`, `reference`/`note` too long, `paidAt` in future, missing key; CREDIT_NOTE: `debitNoteId` missing/unknown/other supplier's/on the same purchase | field messages |
| 404 `NOT_FOUND` | unknown purchase | back to list |
| 409 `ALREADY_PAID` | nothing is due (`details.duePaise <= 0`) | refresh, hide the button |
| 422 `PAYMENT_EXCEEDS_DUE` | amount > due; `details.duePaise`, `details.fields.amountPaise`="at most N" | set field to N |
| 422 `CREDIT_EXCEEDS_AVAILABLE` | CREDIT_NOTE amount > credit left on that note or its purchase; `details.availablePaise` | cap at `availablePaise`; refresh note list |
| 409 `IDEMPOTENCY_KEY_REUSED` | same key, different body | new key |

### 4.5 State / derived
After payment the server re-derives `paymentStatus` (PENDING->PARTIAL->PAID), `duePaise`. Never update locally. CREDIT_NOTE payments do not change the supplier's balance (credit moves between purchases): the old purchase's negative due shrinks, this purchase's due shrinks.

### 4.6 Edge cases
- Retry after timeout: same key -> same 201 body, one payment row only.
- Two admins paying at once: the row is locked; the loser gets `PAYMENT_EXCEEDS_DUE` or `ALREADY_PAID`.
- Two requests using the same credit at once: exactly one succeeds, the other `CREDIT_EXCEEDS_AVAILABLE`.
- Partial credit use is allowed (use 30,000 of 40,000, rest later).
- A purchase cannot be paid with a debit note that belongs to itself (400).
- Payments cannot be deleted or edited.

### 4.7 Dart models
```dart
enum PayMode { cash, upi, card, bank, cheque, creditNote } // JSON CASH,UPI,CARD,BANK,CHEQUE,CREDIT_NOTE
class PaymentInput { int amountPaise; String mode; String? reference, note, paidAt, moneyAccountId, debitNoteId; }
```

### 4.8 Acceptance
- Given due 10000, When I pay 4000 CASH with key K, Then 201, `paymentStatus=="PARTIAL"`, `duePaise==6000`; repeating K returns the identical body and still one payment.
- Given K reused with amount 5000, Then 409 `IDEMPOTENCY_KEY_REUSED`.
- Given due 6000, When I pay 6001, Then 422 `PAYMENT_EXCEEDS_DUE` with `details.duePaise==6000`.
- Given amount 0 or mode "BARTER", Then 400.
- Given a missing/blank key, Then 400 on `Idempotency-Key`.
- Given `paidAt` tomorrow, Then 400 on `paidAt`.
- Given I pay the remaining 6000 by CHEQUE, Then `paymentStatus=="PAID"`, `overdue==false`; a further payment gives 409 `ALREADY_PAID`.
- Given a debit note with 10000 credit left, When I pay 10001 with CREDIT_NOTE, Then 422 `CREDIT_EXCEEDS_AVAILABLE`.
- Given CREDIT_NOTE without `debitNoteId`, Then 400 on `debitNoteId`.

---

## 5. Payables dashboard and ageing

### 5.1 Goal
Header cards ("You owe Rs X, overdue Rs Y") and per-supplier list; ageing buckets by invoice age.

### 5.2 Who / settings
ADMIN.

### 5.3 Screens
- **Payables card/list**: no inputs. Tap a supplier -> its purchases (`GET /admin/purchases?supplierId=&paymentStatus=PENDING` plus `PARTIAL`, or `overdue=true`) or its statement.
- **Ageing report**: optional `asOf` date (default today).

### 5.4 API sequence
1. `GET /admin/purchases/payables`:
```json
{ "totalPurchasedPaise": 900000, "totalReturnedPaise": 30000, "totalPaidPaise": 600000, "totalDuePaise": 300000,
  "overduePaise": 120000, "overdueCount": 3,
  "bySupplier": [ { "supplierId": "…", "name": "Agro Traders", "totalPurchasedPaise": 500000, "totalPaidPaise": 380000,
                    "totalDuePaise": 120000, "overduePaise": 120000, "overdueCount": 3 } ] }
```
   Read all fields. `totalPurchasedPaise` is **net of debit notes** and `totalPaidPaise` is net of credit used/refunds, so purchased - paid == due (asserted in `PurchasingV2IT`). `bySupplier`: suppliers with a non-zero balance, most owed first, at most 200; negative `totalDuePaise` = supplier owes the shop. No paging.
2. `GET /admin/reports/purchases/ageing?asOf=2026-11-09`:
```json
{ "asOf": "2026-11-09", "days0to30Paise": 100000, "days31to60Paise": 50000, "days61PlusPaise": 20000, "creditPaise": -3000, "totalDuePaise": 167000,
  "bySupplier": [ { "supplierId": "…", "supplierName": "Agro Traders", "days0to30Paise": 100000, "days31to60Paise": 50000, "days61PlusPaise": 20000, "creditPaise": -3000, "totalDuePaise": 167000 } ] }
```
   Age = `asOf` - invoice date, only purchases with something due. `creditPaise` (<= 0) sums purchases with negative due and is included in `totalDuePaise`. At most 500 suppliers, most owed first. Not subject to the 366-day limit. A purchase invoiced 70 days ago lands in `days61PlusPaise`; with `asOf` 60 days earlier it lands in `days0to30Paise` (tested).
3. Errors: 400 `asOf`-format (parameter name) for a bad date; 403 non-admin.

### 5.5 State / derived
Everything server-side. Overdue = due date before today (India) and due > 0 (a purchase with no due date is never overdue). Do not add up purchase lists client-side to build these numbers (lists are paged).

### 5.6 Edge cases
- Zero state: all zeros, empty `bySupplier` -> "Nothing owed".
- >200 suppliers with balance: list truncated; link to supplier search for the rest.
- Time zone: "today" is India time, not device time.

### 5.7 Dart models
```dart
class Payables { int totalPurchasedPaise, totalReturnedPaise, totalPaidPaise, totalDuePaise, overduePaise, overdueCount; List<SupplierDue> bySupplier; }
class SupplierDue { String supplierId, name; int totalPurchasedPaise, totalPaidPaise, totalDuePaise, overduePaise, overdueCount; }
class Ageing { String asOf; int days0to30Paise, days31to60Paise, days61PlusPaise, creditPaise, totalDuePaise; List<AgeingRow> bySupplier; }
class AgeingRow { String supplierId, supplierName; int days0to30Paise, days31to60Paise, days61PlusPaise, creditPaise, totalDuePaise; }
```

### 5.8 Acceptance
- Given any data, Then `totalPurchasedPaise - totalPaidPaise == totalDuePaise`.
- Given an overdue purchase, Then `overdueCount >= 1` and its supplier appears in `bySupplier`.
- Given a purchase with negative due, Then it counts in `creditPaise` of ageing and in `totalDuePaise` (as a negative contribution).
- Given `asOf` = 60 days ago for a 70-day-old invoice, Then it is in the 0-30 bucket.

---

## 6. Purchase returns (debit notes)

### 6.1 Goal
Return goods to the supplier: stock leaves, a debit note `DN-000001...` is issued, what is owed drops (may go negative = supplier credit), input GST is reversed.

### 6.2 Who / settings
ADMIN.

### 6.3 Fields (`POST /admin/purchases/{id}/returns`)

| field | type | required | validation |
|---|---|---|---|
| `reason` | string | yes | not blank, 1..200 |
| `items` | list | yes | 1..200; each line at most once (400 on `items`) |
| `items[].purchaseItemId` | uuid | yes | the purchase's `items[].id`; 400 on `purchaseItemId` "not a line of this purchase" |
| `items[].qty` | int | yes | >= 1 and <= `qty + freeQty - returnedQty` (422 otherwise) |

UI: per line show max returnable = `qty + freeQty - returnedQty` (all from the purchase response); stepper clamps to it; shows nothing about money (server computes).

### 6.4 API sequence
1. From detail tap "Return goods"; create key.
2. `POST /admin/purchases/{id}/returns`, `Idempotency-Key`:
```json
{ "reason": "Damaged in transit", "items": [ { "purchaseItemId": "<items[].id>", "qty": 3 } ] }
```
3. 201 ReturnView (read `id`, `noteNo`, `totalPaise`, line amounts). Example from tests (10 paid + 2 free at Rs 100, GST 18 %, return 3): `taxablePaise 25000, cgstPaise 2250, sgstPaise 2250, totalPaise 29500`; stock 12 -> 9; purchase `returnedPaise 29500`, and for a fully paid purchase `duePaise -29500`.
4. Re-fetch the purchase. Lists: `GET /admin/purchases/{id}/returns?page&size`, `GET /admin/purchase-returns?supplierId=&from=&to=&page&size` (dates = India date of the note; `from` after `to` = 400), `GET /admin/purchase-returns/{id}` (404 unknown).
5. Errors:

| status / code | cause | UI |
|---|---|---|
| 400 `VALIDATION_FAILED` | blank reason, empty items, repeated line, unknown line, qty < 1, missing key | field messages |
| 404 `NOT_FOUND` | unknown purchase | |
| 422 `RETURN_EXCEEDS_RECEIVED` | more than received minus already returned; `details.purchaseItemId`, `details.fields.qty`="at most N" | clamp to N |
| 422 `INSUFFICIENT_STOCK_FOR_RETURN` | shop no longer has the units; `details.productId`, `details.onHand`, `details.fields.qty` | "Only N in stock" |
| 409 `IDEMPOTENCY_KEY_REUSED` | | new key |

### 6.5 State / derived
Credit per line = proportional share (taxable, CGST, SGST, IGST, cess each divided over `qty + freeQty`, rounded half up); the return that takes the **last** units of a line gets the exact remainder, so a fully returned line credits exactly its cost (tested: 118000 - 29500 = 88500). Freight, round-off and document discount are not returned separately. `returnedQty` per line, `returnedPaise` per purchase, `noteNo` are server-assigned. A repeat of the same key returns the first debit note and moves no stock.

### 6.6 Edge cases
- Return on a paid purchase makes `duePaise` negative -> supplier credit (section 7).
- Nothing changes on any error (atomic).
- Free units can be returned (they count in `qty + freeQty`).
- Stock re-checked under lock; concurrent sales may cause `INSUFFICIENT_STOCK_FOR_RETURN`.

### 6.7 Dart models
```dart
class PurchaseReturn { String id, purchaseId, supplierId, supplierName, invoiceNo, noteNo, reason; int taxablePaise, cgstPaise, sgstPaise, igstPaise, cessPaise, totalPaise;
  String? createdBy; DateTime createdAt; List<PurchaseReturnLine> items; }
class PurchaseReturnLine { String purchaseItemId, productId, name; int qty, taxablePaise, cgstPaise, sgstPaise, igstPaise, cessPaise, totalPaise; }
class ReturnInput { String reason; List<({String purchaseItemId, int qty})> items; }
```

### 6.8 Acceptance
- Given a PAID purchase (10+2 units), When I return 3, Then 201, `noteNo` starts `DN-`, `totalPaise==29500`, stock -3, purchase `duePaise==-29500`, `paymentStatus=="PAID"`.
- Given the same key and body, Then the identical body and no extra stock movement.
- Given the same key but qty 4, Then 409 `IDEMPOTENCY_KEY_REUSED`.
- Given 9 more units, Then `totalPaise==88500` (exact remainder) and stock 0.
- Given one more unit, Then 422 `RETURN_EXCEEDS_RECEIVED`.
- Given on-hand 2 and qty 3, Then 422 `INSUFFICIENT_STOCK_FOR_RETURN` and `returnedPaise` unchanged.
- Given reason " ", Then 400.
- Given the same line twice, Then 400 on `items`.

---

## 7. Supplier statement, supplier credit, supplier refunds

### 7.1 Goal
Show the supplier ledger with running balance; let the admin turn supplier credit into a payment of another purchase (section 4) or record a cash/bank refund received from the supplier.

### 7.2 Who / settings
ADMIN.

### 7.3 Screens and fields
**Statement**: `from`, `to` (optional dates, inclusive; `from` after `to` = 400 on `from`), `page`, `size` (1..50, default 20).
**Refund sheet**:

| field | type | required | validation |
|---|---|---|---|
| `amountPaise` | int | yes | >= 1; not above supplier `creditPaise` |
| `mode` | enum | yes | `CASH`,`UPI`,`CARD`,`BANK`,`CHEQUE` (CREDIT_NOTE not allowed here; 400 on `mode`) |
| `reference` | string | no | max 80 |
| `receivedAt` | instant | no | default now; not > 10 min in the future (400 on `receivedAt`) |
| `moneyAccountId` | uuid | no | stored only |

Show the sheet only when the supplier's `creditPaise > 0`; default amount = `creditPaise`.

### 7.4 API sequence
1. `GET /admin/suppliers/{id}` -> read `balancePaise`, `creditPaise`.
2. `GET /admin/suppliers/{id}/statement?from=&to=&page=0&size=20`:
```json
{ "supplierId": "…", "supplierName": "Agro Traders", "from": "2026-04-01", "to": "2026-04-30",
  "openingBalancePaise": -40000, "closingBalancePaise": 10000,
  "rows": { "items": [
    { "date": "2026-04-02", "at": "2026-04-02T06:10:00Z", "kind": "PURCHASE", "refId": "…", "purchaseId": "…", "invoiceNo": "INV-77",
      "mode": "", "reference": "INV-77", "amountPaise": 50000, "balancePaise": 10000 } ],
    "page": 0, "size": 20, "total": 3, "totalExact": true, "hasMore": false } }
```
   `from`/`to` echo the request (null when omitted). Rows are the standard page nested under `rows`.
   - `kind`: `PURCHASE` (+, document total, dated by invoice date), `PAYMENT` (-, any payment incl. CREDIT_NOTE ones), `CREDIT_USED` (+, the other half of a credit payment), `DEBIT_NOTE` (-, `reference` = DN number, `mode` ""), `SUPPLIER_REFUND` (+, `purchaseId` null, `invoiceNo` ""). Order within a day: PURCHASE, PAYMENT, CREDIT_USED, DEBIT_NOTE, SUPPLIER_REFUND, then time.
   - `amountPaise` signed (plus = shop owes more). `balancePaise` = running balance after the row, exact on every page (starts at `openingBalancePaise`). `closingBalancePaise` = opening + sum of **all** rows in range (not only the page). Without `to`, closing == supplier `balancePaise` == `totalDuePaise` == its `payables` line.
   - Sample from tests: bought 100000, paid 100000, returned 40000 -> opening for `from` later = -40000.
3. Credit lookup for "pay with credit": list `GET /admin/purchases?supplierId=<id>&paymentStatus=PAID&size=50` and keep those with `duePaise < 0`, then `GET /admin/purchases/{id}/returns` and offer the notes of such purchases. Available on a note = `min(note.totalPaise - already used, -purchase.duePaise)`; the server enforces it and reports `details.availablePaise` on `CREDIT_EXCEEDS_AVAILABLE` (there is no endpoint listing "available per note", so let the server tell you). Then call section 4.4 step 3.
4. Refund: `POST /admin/suppliers/{id}/refunds`, `Idempotency-Key`:
```json
{ "amountPaise": 45000, "mode": "BANK", "reference": "UTR77", "moneyAccountId": null }
```
   -> **201**:
```json
{ "id": "…", "supplierId": "…", "amountPaise": 45000, "mode": "BANK", "reference": "UTR77", "moneyAccountId": null,
  "receivedAt": "…", "createdBy": "…", "createdAt": "…", "creditPaise": 5000, "balancePaise": -5000 }
```
   `creditPaise`/`balancePaise` are the supplier's figures after the refund (statement closing then equals `balancePaise`). The credit is taken from the oldest credit purchases first.
5. `GET /admin/suppliers/{id}/refunds` -> **plain JSON array** (not a page), newest first, at most 50, same shape (note `creditPaise`/`balancePaise` there are the supplier's *current* figures on every row).
6. Errors: 404 `NOT_FOUND` (supplier); 400 (mode, future `receivedAt`, missing key); **422 `REFUND_EXCEEDS_CREDIT`** (`details.creditPaise`, `details.fields.amountPaise`="at most N"); 422 `CREDIT_EXCEEDS_AVAILABLE` (credit changed concurrently: "try again"); 409 `IDEMPOTENCY_KEY_REUSED`.

### 7.5 State / derived
Balance (positive: shop owes supplier; negative: supplier owes shop) and credit are server numbers. Never recompute from rows (paged). Payables `totalPaidPaise` is net of refunds/credit used.

### 7.6 Edge cases
- Statement on a supplier with no activity: `rows.items` empty, opening = closing = 0 (`total` 0).
- Page through large ledgers by `page`; running balance is correct per page.
- Refund replay returns the first refund (201) and exactly one row exists.
- Date filters use India dates; a row's `date` is the India date.
- A row of `kind` PAYMENT with `mode` CREDIT_NOTE is paired with a CREDIT_USED row of equal amount; show them grouped or labelled "Adjusted".

### 7.7 Dart models
```dart
class SupplierStatement { String supplierId, supplierName; String? from, to; int openingBalancePaise, closingBalancePaise; Page<StatementRow> rows; }
class StatementRow { DateTime date; DateTime at; String kind, invoiceNo, mode, reference; String? refId, purchaseId; int amountPaise, balancePaise; }
class SupplierRefund { String id, supplierId, mode, reference; int amountPaise, creditPaise, balancePaise; String? moneyAccountId, createdBy; DateTime receivedAt, createdAt; }
```

### 7.8 Acceptance
- Given credit 50000, When I refund 50001, Then 422 `REFUND_EXCEEDS_CREDIT` with `details.creditPaise==50000`.
- Given refund 45000 BANK, Then 201, `creditPaise==5000`, `balancePaise==-5000`.
- Given mode "GIFT", Then 400 on `mode`.
- Given the same key+body, Then same refund id; same key with 46000, Then 409.
- Given a statement without `to`, Then `closingBalancePaise == supplier.balancePaise`.
- Given consecutive rows, Then each `balancePaise` equals previous + `amountPaise`.
- Given `from=today, to=yesterday`, Then 400; unknown supplier 404.
- Given `size=2&page=1`, Then 2 rows, `hasMore` true when more exist and the first row's balance is still correct.

---

## 8. Invoice attachment

### 8.1 Goal
Attach a photo/PDF of the supplier invoice to a recorded purchase, view it, replace it, remove it.

### 8.2 Who / settings
ADMIN. Files live in a private server folder, never under `/img`; they are only served through the authenticated endpoint.

### 8.3 Fields
| field | type | required | rules |
|---|---|---|---|
| multipart part `file` | bytes | yes | PDF, JPEG, PNG or WebP, **<= 5 MB**, non-empty. Type decided from the first bytes, not the file name or sent content type |

Client mirror: compress camera images below 5 MB; pre-check size and extension for a friendlier error but trust the server.

### 8.4 API sequence
1. Upload/replace: `POST /admin/purchases/{id}/attachment`, `multipart/form-data`, no Idempotency-Key (repeat is harmless; a second upload replaces the first and the old file is deleted) -> **200** PurchaseView with `hasAttachment:true`, `attachmentUrl:"/api/v1/admin/purchases/{id}/attachment"`.
2. View: `GET /admin/purchases/{id}/attachment` **with the `Authorization` header** (a plain `Image.network`/link cannot send it): fetch bytes with `responseType: bytes`, then `Image.memory` (jpeg/png/webp) or a PDF viewer. Response headers: real `Content-Type`, `Content-Disposition: inline; filename="invoice-<purchaseId>.<ext>"`, `X-Content-Type-Options: nosniff`, `Cache-Control: no-store, private` (do not persist on disk beyond the viewer session unless the user exports). 404 when none (check `hasAttachment` first). 401/403 without admin token.
3. Remove: `DELETE /admin/purchases/{id}/attachment` -> 200 PurchaseView (also 200 when there was none).
4. Errors (400 with `details.fields.file`): `UNSUPPORTED_FILE`, `FILE_TOO_LARGE` (over 5 MB; raised by the multipart limit or the service), `VALIDATION_FAILED` (empty file / not multipart); 404 unknown purchase. Show `message`.

### 8.5 State / derived
`hasAttachment`, `attachmentUrl` come from the purchase. `attachmentNote` is a separate free-text field (still editable, section 9). One file per purchase.

### 8.6 Edge cases
- Upload failure mid-way: the old file stays until the new one is committed.
- Wrong extension but valid bytes (png named .pdf) is accepted and served as PNG; wrong bytes named .png is `UNSUPPORTED_FILE`.
- Cache the bytes in memory per purchase id for the session only; drop on replace/remove.
- Slow networks: show progress; 5 MB limit also applies to the whole request (6 MB max request).

### 8.7 Dart
```dart
Future<void> attach(Dio dio, String id, Uint8List bytes, String name) => dio.post('/admin/purchases/$id/attachment',
    data: FormData.fromMap({'file': MultipartFile.fromBytes(bytes, filename: name)}));
Future<Uint8List> invoiceBytes(Dio dio, String id) async =>
    Uint8List.fromList((await dio.get<List<int>>('/admin/purchases/$id/attachment', options: Options(responseType: ResponseType.bytes))).data!);
```

### 8.8 Acceptance
- Given a PDF under 5 MB, When uploaded, Then 200, `hasAttachment==true`, GET returns `application/pdf` with `inline`.
- Given a PNG after a PDF, Then replaced; the GET returns `image/png`.
- Given text bytes named x.png, Then 400 `UNSUPPORTED_FILE`.
- Given 6 MB, Then 400 `FILE_TOO_LARGE`.
- Given an empty file, Then 400.
- Given DELETE twice, Then both 200, `hasAttachment==false`.
- Given no token on GET, Then 401/403.
- Given a purchase without a file, Then GET 404 and the UI hides the button.

---

## 9. Editing a recorded purchase

### 9.1 Goal
Correct non-stock text on a recorded purchase. Quantities, costs, discounts, tax, items, supplier, GST mode and payments can **not** be edited: fix those with a return (section 6) and a new purchase.

### 9.2 Who / settings
ADMIN.

### 9.3 Fields (`PATCH /admin/purchases/{id}`, body = only the fields to change)

| field | type | rules |
|---|---|---|
| `invoiceNo` | string | must be a string; trimmed; max 60; empty clears |
| `invoiceDate` | date string | `yyyy-MM-dd`; cannot be null/blank, cannot be in the future |
| `dueDate` | date string or **null** | null clears it; not before the (new) invoice date |
| `note` | string | max 200; empty clears |
| `attachmentNote` | string | max 500 |
| `supplierGstin` | string | GSTIN pattern or empty; changes the text on this document only, not the supplier master; state follows the GSTIN unless `supplierStateCode` is sent |
| `supplierStateCode` | string | 2 digits; must match the GSTIN's first two digits |

Any other key (`items`, `qty`, `unitCostPaise`, `totalCostPaise`, `gstMode`, `supplierId`, `paidPaise`...) -> 400 on that key; message says to use a purchase return and a new purchase. Empty body -> 400 on `body`. Wrong JSON type (number for text) -> 400 on the field.

### 9.4 API sequence
1. `PATCH /admin/purchases/{id}` (no Idempotency-Key) `{ "invoiceNo": "NEW-1", "dueDate": null, "note": "checked" }` -> 200 PurchaseView, `warnings` filled when the new invoice number already exists for this supplier ("invoice number new-1 was already recorded...").
2. Errors: 404 unknown purchase; 400 as above.
3. Send only changed fields; sending identical values is a no-op (no audit row).

### 9.5 State / derived
Money, status and stock never change by PATCH. Changing `invoiceDate` moves the purchase between report periods and ageing. Changing the state text does not recompute `interState` or tax. Audit action `PURCHASE_EDIT` lists each change.

### 9.6 Edge cases
- The UI must differentiate "field left out" (unchanged) from `dueDate: null` (clear). Use a map, not a model with default-null fields (it would erase `dueDate` accidentally).
- Show the warning banner like in 2.4.
- Last write wins (no version check).

### 9.7 Dart
```dart
Future<Map<String, dynamic>> editPurchase(Dio dio, String id, Map<String, dynamic> onlyChanged) async =>
    (await dio.patch('/admin/purchases/$id', data: onlyChanged)).data;
```

### 9.8 Acceptance
- Given `{invoiceNo:"NEW-1", note:"checked"}`, Then 200 and `totalCostPaise` unchanged.
- Given `{dueDate:null}`, Then `dueDate` is null in the response.
- Given `{qty:1}`, Then 400 on `qty` with a message mentioning a purchase return.
- Given `{invoiceDate:<tomorrow>}`, Then 400. Given `{dueDate:<before invoice date>}`, Then 400.
- Given `{supplierStateCode:"27"}` on GSTIN `24...`, Then 400.
- Given `{}`, Then 400.
- Given a duplicate invoice number, Then 200 and `warnings` non-empty.

---

## 10. Purchase reports and lookups

### 10.1 Goal
Register, input GST, discounts, ageing (section 5), per-product price history and the form lookup (section 2).

### 10.2 Who / settings
ADMIN. Reports default to `to` = today and `from` = first day of that month; a range may span at most 366 days (400 on `from` when `from` > `to` or the range is longer).

### 10.3 Screens and fields
Common: date range picker (`from`, `to`, inclusive India dates). Register adds `supplierId`, `productId`, `gstRate` (int), `page`, `size`.

### 10.4 API sequence
1. **Register** `GET /admin/reports/purchases/register?from&to&supplierId&productId&gstRate&page&size` -> page, one row per purchase line, newest invoice date first:
```json
{ "purchaseId": "…", "invoiceNo": "INV-77", "invoiceDate": "2026-11-09", "supplierId": "…", "supplierName": "Agro Traders", "gstMode": "REGULAR",
  "productId": "…", "productName": "Bath Soap", "qty": 10, "freeQty": 2, "unitCostPaise": 10000, "discountPaise": 7000, "taxablePaise": 93000,
  "gstPercent": 18, "cgstPaise": 8370, "sgstPaise": 8370, "igstPaise": 0, "cessPaise": 0, "lineTotalPaise": 109740, "landedUnitCostPaise": 7750.0000 }
```
   `discountPaise` = line discount + the line's share of document discount.
2. **Input GST** `GET /admin/reports/purchases/input-gst?from&to`:
```json
{ "from": "2026-11-01", "to": "2026-11-09",
  "lines": [ { "gstMode": "REGULAR", "gstPercent": 18, "taxablePaise": 93000, "cgstPaise": 8370, "sgstPaise": 8370, "igstPaise": 0, "cessPaise": 0, "creditablePaise": 16740, "nonCreditablePaise": 0 } ],
  "taxablePaise": 93000, "cgstPaise": 8370, "sgstPaise": 8370, "igstPaise": 0, "cessPaise": 0, "creditablePaise": 16740, "nonCreditablePaise": 0 }
```
   One line per (mode, rate). Purchases and freight count by invoice date; debit notes are subtracted by the day they were made. REGULAR tax = creditable; COMPOSITION tax = non-creditable; NONE has no tax.
3. **Discounts** `GET /admin/reports/purchases/discounts?from&to` -> `{ from, to, grossPaise, lineDiscountPaise, docDiscountPaise, bySupplier:[{supplierId, supplierName, purchaseCount, grossPaise, lineDiscountPaise, docDiscountPaise}] }` (suppliers with a discount only, biggest first, <= 500).
4. **Ageing**: section 5.
5. **Price history** `GET /admin/products/{id}/purchase-prices?limit=20&rounding=PAISE` -> plain array (`limit` clamped 1..100), newest first; 404 unknown product:
```json
[ { "purchaseId": "…", "supplierId": "…", "supplierName": "Agro Traders", "invoiceNo": "INV-77", "invoiceDate": "2026-11-09",
    "qty": 10, "freeQty": 2, "unitCostPaise": 10000, "landedUnitCostPaise": 8333.3333, "landedUnitCostRoundedPaise": 8333,
    "mrpPaise": 15000, "sellingPricePaise": 14000, "createdAt": "…" } ]
```
   With `rounding=EXACT` the "rounded" field has 4 decimals (8333.3333); `landedUnitCostPaise` is always exact.
6. **Lookup**: section 2.4 step 3.
7. Errors: 400 on `from` (reversed or > 366 days), on `rounding`; 403; 404.

### 10.5 State / derived
All server-side; the app only formats. Totals row of the register is not provided: sum only what you display and label it "this page".

### 10.6 Edge cases
- Empty range: `items: []`, zero totals.
- Register is paged (large); input-gst/discounts/ageing are not.
- Timezone: dates are shop (India) dates.
- Exports: build CSV client-side from fetched pages (no export endpoint).

### 10.7 Dart models
```dart
class RegisterRow { String purchaseId, invoiceNo, supplierId, supplierName, gstMode, productId, productName; DateTime invoiceDate;
  int qty, freeQty, unitCostPaise, discountPaise, taxablePaise, gstPercent, cgstPaise, sgstPaise, igstPaise, cessPaise, lineTotalPaise; double landedUnitCostPaise; }
class GstLine { String gstMode; int gstPercent, taxablePaise, cgstPaise, sgstPaise, igstPaise, cessPaise, creditablePaise, nonCreditablePaise; }
class InputGst { String from, to; List<GstLine> lines; int taxablePaise, cgstPaise, sgstPaise, igstPaise, cessPaise, creditablePaise, nonCreditablePaise; }
class DiscountRow { String supplierId, supplierName; int purchaseCount, grossPaise, lineDiscountPaise, docDiscountPaise; }
class PriceRow { String purchaseId, supplierId, supplierName, invoiceNo; DateTime invoiceDate, createdAt;
  int qty, freeQty, unitCostPaise; double landedUnitCostPaise, landedUnitCostRoundedPaise; int? mrpPaise, sellingPricePaise; }
```

### 10.8 Acceptance
- Given a purchase 70 days ago, When I query its 20-day window, Then register shows one row with `discountPaise==7000`, `taxablePaise==93000`.
- Given `gstRate=5` for an 18 % line, Then no rows.
- Given input-gst over a window, Then `creditablePaise == cgst+sgst+igst+cess` when only REGULAR purchases exist.
- Given `from` 400 days back, Then 400 on `from`.
- Given no params, Then 200 with the current month.
- Given an unknown product, Then price history 404.
- After a return, Then `cgstPaise` in input GST drops by the returned CGST.

---

## 11. Store barcode generation

### 11.1 Goal
Give a variant (a product row; its `id` is the product id) a store-internal EAN-13 so a label scans at the counter.

### 11.2 Who / settings
ADMIN and CASHIER. Server property `barcode.storePrefix` (default `29`, 1..4 digits starting with 2). Not configurable by the app.

### 11.3 Fields
Path `id` (variant/product uuid). No body.

### 11.4 API sequence
1. Variant has no barcode (in the product/pos data) -> button "Generate barcode".
2. `POST /admin/variants/{id}/barcode/generate` (no body, no Idempotency-Key) -> 200:
```json
{ "variantId": "…", "barcode": "2900000000018", "created": true, "barcodes": ["2900000000018"] }
```
   Read `barcode`, `created`, `barcodes` (all barcodes of the variant). Second call returns the same code with `created:false` (safe).
3. Errors: 404 unknown variant; 400 `VALIDATION_FAILED` on `variantId` for an item sold by weight (those use PLU, not a barcode); 403 customers.
4. Related (product screen): `PUT /admin/products/{id}/pos` rejects an 8/12/13-digit barcode with a wrong check digit: 400 with `details.fields.barcodes` = "barcode ... has a wrong last digit: for these digits it should be N...". Other lengths and text codes (Code 128) are not checked. Optional client check (same algorithm): for lengths 8, 12, 13, weights from the right of the body digits alternate 3,1,3,...; `check = (10 - sum % 10) % 10`.

### 11.5 State / derived
The code is `prefix + running number + check digit`, never equal to another barcode. Printing labels also auto-generates one when missing (section 13), so this button is optional.

### 11.6 Edge cases
- Two devices tapping at once: both get the same single code (tested count == 1).
- Weighed item: show "Weighed items use an item code" instead of the button.
- Cashier role: the product admin screens are admin-only, so a cashier gets the variant id from POS data.

### 11.7 Dart
```dart
class GeneratedBarcode { String variantId, barcode; bool created; List<String> barcodes; }
```

### 11.8 Acceptance
- Given a variant without barcode, When generate, Then `barcode` has 13 digits, starts with `29`, `created==true`, and `GET /pos/barcode/{code}` finds the variant.
- Given a second call, Then same code, `created==false`.
- Given two variants, Then different codes.
- Given an unknown id, Then 404; a weighed variant, Then 400.
- Given PUT pos with `4006381333932`, Then 400 on `barcodes` ("wrong last digit").

---

## 12. Label templates (CRUD)

### 12.1 Goal
Choose and manage label layouts. Three built-in templates ship; admins create custom ones.

### 12.2 Who / settings
Read (list/get): ADMIN and CASHIER. Create/update/delete: ADMIN only (CASHIER 403). Built-in IDs: `00000000-0000-0000-0000-00000000a001` (50 x 25 mm thermal), `...a002` (38 x 25 mm thermal; id inferred from the series, confirm by listing), `...a003` (A4 sheet 3 x 8, 64 x 34 mm cells).

### 12.3 Fields (TemplateBody)

| field | type | required | validation |
|---|---|---|---|
| `name` | string | yes | trimmed, 1..60 (400 on `name`); 409 `NAME_TAKEN` if another template has it (case-insensitive) |
| `kind` | enum | yes | `THERMAL` or `SHEET` |
| `widthMm`,`heightMm` | number | yes | 10..200 each (400 "10 to 200 mm"). THERMAL: the label. SHEET: **one cell** |
| `columns` | int | SHEET only | 1..10 (default 1); `left + columns*width + (columns-1)*gapX` must be <= 210 (400 on `columns` "wider than an A4 page") |
| `rows` | int | SHEET only | 1..30 (default 1); `top + rows*height + (rows-1)*gapY` <= 297 (400 on `rows`) |
| `gapXMm`,`gapYMm` | number | SHEET only | 0..50 |
| `marginLeftMm`,`marginTopMm` | number | SHEET only | 0..100 |
| `barcodeType` | enum | no | `EAN13` (default), `CODE128`, `QR` |
| `fields` | object | yes | below |

THERMAL ignores grid values (stored as 1,1,0,0,0,0).

`fields`:

| key | type | rules |
|---|---|---|
| `shopName`,`packSize`,`mrp`,`price`,`packedOn`,`bestBefore`,`batchNo`,`pricePerUnit` | bool | send **all** keys. `pricePerUnit` requires `price` true (400 on `fields.pricePerUnit`) |
| `productName` | enum | `NONE`(default),`EN`,`GU`,`BOTH` |
| `fontSize` | int | 4..24 points; 0 or missing = 7 (price line is 2 pt bigger) (400 on `fields.fontSize`) |
| `alignment` | enum | `LEFT`,`CENTER`(default),`RIGHT` |

Fit check: the server lays out the fullest possible label; if the chosen fields do not fit the height it answers 400 on `fields`: "these fields need about 26.2 mm of height but a label 50 x 25 mm has 25.0 mm; use fewer fields, a smaller font or a taller label". A barcode needs about 7 mm (QR 10 mm) after the text lines. The app cannot know in advance: show the message and let the user adjust.

### 12.4 API sequence
1. `GET /admin/label-templates?page=0&size=50` -> page, built-ins first (default size here is 50).
2. `GET /admin/label-templates/{id}` (404).
3. `POST /admin/label-templates` -> **201** template. Body (from the integration test):
```json
{ "name": "Mine 7f3a", "kind": "THERMAL", "widthMm": 60, "heightMm": 40, "barcodeType": "CODE128",
  "fields": { "shopName": false, "productName": "BOTH", "packSize": true, "mrp": false, "price": true, "packedOn": false,
              "bestBefore": false, "batchNo": false, "pricePerUnit": false, "fontSize": 8, "alignment": "LEFT" } }
```
4. `PUT /admin/label-templates/{id}` same body -> 200 (replaces all). `DELETE /admin/label-templates/{id}` -> 204 (past history keeps the template name).
5. Response shape: `{ id, name, kind, widthMm, heightMm, columns, rows, gapXMm, gapYMm, marginLeftMm, marginTopMm, barcodeType, fields{...}, builtIn }`.
6. Errors: 400 field errors (above); 409 `NAME_TAKEN` (`details.name`); 409 `BUILT_IN_TEMPLATE` (PUT/DELETE a built-in, `details.templateId`): show "Duplicate" instead (POST a copy with a new name); 404; 403 for CASHIER on writes (hide write actions by role).

### 12.5 State / derived
`builtIn` read-only. Numbers returned as JSON decimals (`50.0`). `perPage = columns*rows` (compute client-side for the start-cell grid).

### 12.6 Edge cases
- Editing a template used by history: allowed; history keeps the old name copy.
- Deleting the template currently remembered in local storage: fall back to the first template.
- Duplicate: copy the template's JSON, change `name` (add " copy"), strip `id`/`builtIn`.
- Concurrent rename to the same name: 409 `NAME_TAKEN`.

### 12.7 Dart models
```dart
enum TemplateKind { thermal, sheet } enum BarcodeType { ean13, code128, qr }
class LabelFields { bool shopName, packSize, mrp, price, packedOn, bestBefore, batchNo, pricePerUnit; String productName, alignment; int fontSize; }
class LabelTemplate { String id, name, kind, barcodeType; double widthMm, heightMm, gapXMm, gapYMm, marginLeftMm, marginTopMm;
  int columns, rows; LabelFields fields; bool builtIn; int get perPage => columns * rows; }
```

### 12.8 Acceptance
- Given the built-in list, Then names include "50 x 25 mm thermal", "38 x 25 mm thermal", "A4 sheet 3 x 8", and CASHIER can GET but not POST (403).
- Given a valid body, Then 201; the same name again 409 `NAME_TAKEN`.
- Given `heightMm:8`, Then 400 on `heightMm`.
- Given `pricePerUnit:true, price:false`, Then 400 on `fields.pricePerUnit`.
- Given a PUT/DELETE of a built-in, Then 409 `BUILT_IN_TEMPLATE`.
- Given a SHEET with 10 columns of 64 mm, Then 400 on `columns`.
- Given DELETE of a custom template, Then 204 and GET 404.

---

## 13. Label printing and history

### 13.1 Goal
Print price/barcode labels for one or many variants in PDF, TSPL, ZPL, ESC/POS, or a JSON description for in-app drawing; list past prints.

### 13.2 Who / settings
ADMIN and CASHIER. Server property `label.dpi` (default 203; 150..600) is reported in JSON mode (`dpi`) and used to convert mm to dots. Shop name comes from shop settings.

### 13.3 Screens and fields (`POST /admin/labels`)

| field | type | required | validation |
|---|---|---|---|
| `templateId` | uuid | yes | 404 if unknown |
| `mode` | enum | yes | `PDF`,`TSPL`,`ZPL`,`ESCPOS`,`JSON`; anything else 400 on `mode`. A SHEET template accepts only `PDF` or `JSON` (else 400 on `mode`) |
| `items` | list | yes | 1..200 entries (400 on `items`) |
| `items[].variantId` | uuid | yes | unknown -> 400 on `items[i].variantId`; weighed item -> 400 (no pack barcode) |
| `items[].copies` | int or `"fromStock"` | yes | whole number 1..500, or `"fromStock"` (case-insensitive) = one label per unit in stock, capped at 500; stock 0 -> 400 on `items[i].copies`. A non-whole number (1.5) or text is 400 |
| total labels | | | <= 2000 across items (400 on `items`) |
| `startPosition` | int | SHEET only | 1..`columns*rows`, default 1; for THERMAL anything but 1 is 400 |
| `packedOn`,`bestBefore` | date | no | `bestBefore` not before `packedOn` (400 on `bestBefore`). Printed only if the template has those fields |
| `batchNo` | string | no | max 40; printed only if the template has the field |

UI: template picker (remember last template per device), copies stepper 1..500 + chip "One per unit in stock" (hide at stock 0; warn "capped at 500" above 500), for SHEET a `columns x rows` grid to tap the first free cell (`startPosition`, row by row from top-left = 1), optional dates/batch shown only when the template's `fields` have them, output selector (PDF default; saved printer type).

### 13.4 API sequence
1. `GET /admin/label-templates` -> choose template.
2. (Optional) `POST /admin/variants/{id}/barcode/generate` (section 11). Not required: printing auto-generates when missing (for EAN13 templates a variant with only non-EAN13 codes gets a store EAN-13 added).
3. `POST /admin/labels` with `responseType: bytes` **for every mode** (TSPL may contain raw bitmap bytes; error bodies also arrive as bytes: decode UTF-8 JSON on non-200):
```json
{ "templateId": "00000000-0000-0000-0000-00000000a003", "mode": "PDF", "startPosition": 23,
  "packedOn": "2026-11-09", "bestBefore": "2027-05-09", "batchNo": "B12",
  "items": [ { "variantId": "…", "copies": 12 }, { "variantId": "…", "copies": "fromStock" } ] }
```
4. Response 200 headers: `X-Label-Count`, `X-Label-Pages` (for THERMAL = labels), `Content-Disposition: inline; filename="labels.pdf"` (`.tspl`,`.zpl`,`.escpos`,`.json`), `Content-Type`:

| mode | Content-Type | what to do |
|---|---|---|
| PDF | `application/pdf` | show in viewer / OS print dialog at 100 % (no fit-to-page). THERMAL: one page of label size per label. SHEET: A4 pages, cells filled row by row from `startPosition` |
| TSPL | `text/plain;charset=ISO-8859-1` | send bytes unchanged to the printer (TSC and clones). Starts `SIZE 50 mm,25 mm`, ends blocks with `PRINT copies,1`; if the roll has another gap, replace the `GAP` line |
| ZPL | `text/plain;charset=US-ASCII` | send unchanged (Zebra). `^XA...^XZ` per variant with `^PQ copies` |
| ESCPOS | `application/octet-stream` | send unchanged (58/80 mm receipt printers; use a template no wider than the paper); cut after each label |
| JSON | `application/json` | draw locally (below) |

   Example fixture (tests): 2 labels on `...a001` mode TSPL starts with `SIZE 50 mm,25 mm` and contains `PRINT 2,1` and `EAN13`; ZPL starts `^XA` and contains `^PQ2,0,1,N`.
5. **JSON mode** body:
```json
{ "template": { "id": "…", "name": "50 x 25 mm thermal", "kind": "THERMAL", "widthMm": 50.0, "heightMm": 25.0, "barcodeType": "EAN13" },
  "dpi": 203, "totalLabels": 2,
  "items": [ { "variantId": "…", "name": "Basmati Rice Premium 500 g", "copies": 2, "barcode": { "type": "EAN13", "payload": "2900000000018" },
    "elements": [
      { "type": "text", "xMm": 1.5, "yMm": 1.5, "widthMm": 47.0, "heightMm": 2.96, "text": "Basmati Rice Premium", "fontPt": 7.0, "bold": true, "align": "CENTER", "gujarati": false },
      { "type": "barcode", "xMm": 7.0, "yMm": 14.6, "widthMm": 36.0, "heightMm": 8.9, "barcodeType": "EAN13", "payload": "2900000000018", "humanReadable": true } ] } ] }
```
   For SHEET templates also `sheet: { pageWidthMm:210, pageHeightMm:297, columns, rows, startPosition, pages, placements:[{ item, page, column, row, xMm, yMm }] }` (one placement per label in order; `item` indexes `items`). Draw: positions in mm from the label's top-left; dots = `mm * dpi / 25.4`; a `barcode` box includes the human-readable digits (bottom ~2.7 mm) when `humanReadable`; text with `gujarati:true` must be drawn by the app with a Gujarati-capable font. Prices print as `Rs 59.00`.
6. History: `GET /admin/labels/history?variantId=&page=0&size=20` (newest first, one row per variant per request):
```json
{ "items": [ { "id": "…", "printedAt": "2026-11-09T10:15:30Z", "actorId": "…", "actorName": "Asha", "templateId": "…", "templateName": "50 x 25 mm thermal",
               "variantId": "…", "variantName": "Basmati Rice Premium 500 g", "copies": 12, "mode": "PDF" } ],
  "page": 0, "size": 20, "total": 1, "totalExact": true, "hasMore": false }
```
   `actorName` is null when the account has no name; names are copies from print time.
7. Errors (all 400 `VALIDATION_FAILED` unless noted): `items[i].copies`, `items`, `items[i].variantId`, `mode`, `startPosition`, `bestBefore`, `templateId` ("the label for X does not fit the template: it needs N mm of height and has M mm"); Gujarati on a server that cannot draw it: `mode` = "Gujarati names need PDF or image mode" (thermal) / "Gujarati text cannot be drawn on this server; use JSON mode and let the app draw it" (PDF) -> offer "Use PDF or draw in the app"; 404 `NOT_FOUND` unknown template; 403 customers; 401 no token.

### 13.5 State / derived
Label count, pages, barcode value, prices, pack size, price-per-unit are all server-side. `X-Label-Count`/`X-Label-Pages` are the confirmation numbers to show after printing. Stock for "fromStock" is read by the server at print time (cap 500). A print is recorded in history and the audit log on success only.

### 13.6 Edge cases
- Large request (2000 labels) renders in seconds (tests: 500 labels < 5 s); show a spinner and a generous timeout (>= 30 s).
- Printing is **not idempotent**: no Idempotency-Key; a retry after timeout can print/log twice. Warn before re-sending.
- Gujarati names render as small pictures in printer modes and shrink to half size to fit the width.
- A variant without a Gujarati name prints English.
- EAN13 template + variant with only a Code 128 barcode: server adds and prints a store EAN-13.
- Sheet partly used: `startPosition`; remember the next free cell client-side after a print: `next = ((startPosition-1 + count) % perPage) + 1`.
- Empty history: "Not printed yet".

### 13.7 Dart models
```dart
enum LabelMode { pdf, tspl, zpl, escpos, json }
class LabelItem { String variantId; int? copies; bool fromStock; Map toJson() => {'variantId': variantId, 'copies': fromStock ? 'fromStock' : copies}; }
class LabelHistoryRow { String id, actorId, templateId, templateName, variantId, variantName, mode; String? actorName; DateTime printedAt; int copies; }
Future<Response<List<int>>> printLabels(Dio dio, Map body) =>
    dio.post<List<int>>('/admin/labels', data: body, options: Options(responseType: ResponseType.bytes));
Map errorBody(Response r) => jsonDecode(utf8.decode(r.data as List<int>));
double dots(double mm, int dpi) => mm * dpi / 25.4;
```

### 13.8 Acceptance
- Given 3 copies on a THERMAL template in PDF, Then the PDF has 3 pages, `X-Label-Count==3` and history gets one row with `copies==3`, `mode=="PDF"`.
- Given a CASHIER token, `copies:"fromStock"` with stock 6, SHEET template, `startPosition:23`, Then `X-Label-Count==6`, `X-Label-Pages==2`.
- Given `copies:501`, Then 400 with `details.fields["items[0].copies"]`.
- Given SHEET template with `mode:"ZPL"`, Then 400 on `mode`.
- Given an unknown template id, Then 404; empty `items`, Then 400; `mode:"FAX"`, Then 400.
- Given THERMAL with `startPosition:2`, Then 400 on `startPosition`.
- Given JSON mode, Then `totalLabels==2`, `items[0].copies==2`, `barcode.payload` has 13 chars, `elements` non-empty, `dpi==203`.
- Given a variant without barcode, After print, Then it has exactly one generated barcode.
- Given a CUSTOMER token, Then 403; no token 401.

---

## 14. Error code cheat sheet

| code | status | where | UI |
|---|---|---|---|
| `VALIDATION_FAILED` | 400 | everywhere | `details.fields` |
| `UNSUPPORTED_FILE`, `FILE_TOO_LARGE` | 400 | attachment | show message |
| `NOT_FOUND` | 404 | unknown ids | |
| `SUPPLIER_EXISTS` | 409 | supplier create/rename | open existing (`details.supplierId`) |
| `IDEMPOTENCY_KEY_REUSED` | 409 | purchase, payment, return, refund | new key |
| `ALREADY_PAID` | 409 | payments | refresh |
| `NAME_TAKEN`, `BUILT_IN_TEMPLATE` | 409 | templates | rename / duplicate |
| `TOTAL_MISMATCH` | 422 | purchase | show `details.totalPaise` |
| `PAYMENT_EXCEEDS_DUE` | 422 | payments | `details.duePaise` |
| `CREDIT_EXCEEDS_AVAILABLE` | 422 | CREDIT_NOTE payments, refunds (race) | `details.availablePaise` |
| `REFUND_EXCEEDS_CREDIT` | 422 | refunds | `details.creditPaise` |
| `RETURN_EXCEEDS_RECEIVED`, `INSUFFICIENT_STOCK_FOR_RETURN` | 422 | returns | clamp / show stock |
| `FORBIDDEN` / `UNAUTHENTICATED` | 403 / 401 | | |

## 15. Build order
1. Shared HTTP layer (token, error parser, paging, idempotency helper, bytes responses).
2. Suppliers (list/form/switch).
3. Purchase list/detail read-only, payables strip.
4. New purchase form (lookup, GST mode, save, warnings, TOTAL_MISMATCH).
5. Payments, returns, attachment, edit.
6. Statement, credit use, refunds.
7. Reports.
8. Barcode generate, templates, label print (PDF first, then printer modes, then JSON renderer), history.

---

## 16. Doc vs code differences

Items 2 to 10 were corrected in place in `70`, `72` and `30` afterwards (see `README.md`); items 1, 11 and 12 needed no guide change or cannot be verified from the code.

1. **OVERDUE is not a payment status.** The brief and the UI chips talk about PAID/PARTIAL/PENDING/OVERDUE; code has `paymentStatus` in {PENDING, PARTIAL, PAID} and a separate boolean `overdue`; the list filter is `overdue=true`, and `paymentStatus=OVERDUE` is 400. (70-purchases.md is consistent with the code; this is a clarification.)
2. `70-purchases.md` section 6 lists payment modes `CASH, UPI, CARD, BANK, CHEQUE` only; the code also accepts `CREDIT_NOTE` (with `debitNoteId`) on the same endpoint (documented only in Addendum 1 of file 70).
3. `70-purchases.md` section 5 says `attachmentNote` has "no file upload yet", and section 11 "Not built yet" lists supplier statement, supplier refunds, invoice file upload and editing a recorded purchase. All four are built (Addendum 1 / `V106`). Section 7 also says "the refund from the supplier is not modelled yet": refunds exist (`POST /admin/suppliers/{id}/refunds`).
4. `70-purchases.md` section 5 sample response and section 9 Dart sketch omit `hasAttachment` and `attachmentUrl`, and section 4's supplier sample omits `balancePaise` and `creditPaise`; the records contain all of them.
5. `70-purchases.md` section 4 says "PUT takes the same body and replaces all fields": confirmed, with the consequence that an omitted `paymentTermsDays` resets to 0 and omitted phone/address/gstin become empty. Also `PUT`, `activate`, `deactivate`, `PATCH`, and attachment endpoints take **no** Idempotency-Key although section "Conventions" says every write needs one.
6. `30-pos-reports-purchasing.md` (line ~112) says a repeated Idempotency-Key returns the stored result "even if the body differs"; for purchases, payments, returns and refunds the code answers 409 `IDEMPOTENCY_KEY_REUSED` for a different body (file 30's own addendum and file 70 agree with the code). Legacy purchases without a stored fingerprint are compared by supplier name and invoice number.
7. `72-barcode-labels.md` says a missing true/false key inside `fields` is 400 "is required" on `fields.<key>`. The request record uses primitive booleans/int, and no code path inside the label services enforces it; whether a missing key is 400 or defaults to false depends on the JSON mapper and was **not verified**. This playbook tells the app to always send every key.
8. `72-barcode-labels.md` says `copies` is "the text `fromStock`"; the code compares case-insensitively after trimming (`FromStock`, ` fromstock ` also work). Send the exact word.
9. `70-purchases.md` (Reports) says a range "may span at most 366 days"; code refuses when the day difference between `from` and `to` is 366 or more (so 366 days inclusive is the maximum). `ageing` has no range limit.
10. `70-purchases.md` (UI notes) documents no preview endpoint and suggests the app "compute with the rules in 2.2"; the code confirms there is none, and the distribution of document discount and freight uses largest-remainder allocation, so an app-side estimate can differ by a few paise. This playbook therefore forbids sending an estimated `expectedTotalPaise`.
11. Built-in template `...a002` (38 x 25 mm) id is inferred from the `...a001`/`...a003` series; the tests only reference `a001` and `a003`.
12. `70-purchases.md` Addendum A1.5 Dart sketch builds `StatementRow` with `reference`, `mode`, `invoiceNo` as nullable-with-default; the server always sends them as strings (`""`), while `refId`/`purchaseId` can be null (refund rows).

Could not verify: exact JSON `details` body of 422 `TOTAL_MISMATCH` beyond the test asserting the code (taken from the exception builder: `details.fields.expectedTotalPaise`, `details.expectedTotalPaise`, `details.totalPaise`); the `expectedTotalPaise: -1` dry-run (derived from code reading, not tested); behaviour of missing `fields` keys in template requests (item 7); the largest-remainder tie-break used by the server's allocation helper; whether `apply to product` can fail on the catalog's MRP/price rule in every combination (derived from the documented product rule).
