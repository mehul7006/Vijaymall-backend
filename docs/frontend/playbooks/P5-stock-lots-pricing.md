# P5. Stock lots, lot prices, expiry, valuation and margin (implementation playbook)

Audience: a developer or LLM coding agent who must build the ADMIN app screens, the till (counter) parts and the shop-app (customer) handling for stock lots WITHOUT reading backend code. Everything below was checked against the code: `AdminLotController`, `LotAdminService`, `LotReportService`, `LotReportRepository`, `LotService`, `LotInfo`, `LotAllocator`, `Lot`, `InventoryService`, `SubtotalStep`, `PosService` (price runs, offline as-rung bills), `PosCatalogService`, `PosViews`, `PurchaseService`, `Settings`, migration `V80__stock_lots.sql`, and the integration test `StockLotsIT`. Where `74-stock-lots-and-pricing.md` (or `P2`) disagrees with the code, the code wins and the disagreement is listed in the last section.

## Conventions (confirmed in code)

- Base path `/api/v1`. JSON, camelCase. `Authorization: Bearer <accessToken>`. Everything under `/admin/**` is ADMIN only (a CASHIER gets 403, no token 401). `/pos/**` is CASHIER and ADMIN. The customer endpoints (`/cart/quote`, `/orders`) take any signed-in customer.
- Money is an integer in **paise** (Dart `int`), with ONE exception: a lot's `unitCost` is a JSON number in paise with up to 4 decimals (landed cost per unit). Parse it as `Decimal`/`double` for display only; never add it up yourself.
- Error body: `{ "code", "message", "traceId", "details": { "fields": { "<field>": "<text>" } } }`. Switch on `code`; put `details.fields[x]` under input `x`.
- Paged list: `{ items, page, size, total, totalExact, hasMore }`; `page` starts at 0; `size` is clamped to 1..50 (larger values silently become 50). `totalExact:false` -> show "10,000+".
- Dates `yyyy-MM-dd` are India shop dates (`expiryDate`, `from`, `to`). Instants are UTC ISO-8601 ending `Z`.
- `Idempotency-Key` is NOT used by any lot endpoint. Switch and write-off calls are naturally safe to repeat (see edge cases).

## Table of contents

1. [What lots are, and the invariant](#1-what-lots-are-and-the-invariant)
2. [Lot list per variant](#2-lot-list-per-variant)
3. [Lot settings: lotPriced and trackExpiry](#3-lot-settings-lotpriced-and-trackexpiry)
4. [Purchase receiving: how a purchase line opens a lot](#4-purchase-receiving-how-a-purchase-line-opens-a-lot)
5. [Till: lot-aware scan, quote and split price runs](#5-till-lot-aware-scan-quote-and-split-price-runs)
6. [Online: single price, CAP policy and QTY_EXCEEDS_LOT_PRICE](#6-online-single-price-cap-policy-and-qty_exceeds_lot_price)
7. [Flash sale precedence](#7-flash-sale-precedence)
8. [Expiring list and write-off](#8-expiring-list-and-write-off)
9. [Stock valuation](#9-stock-valuation)
10. [Margin report](#10-margin-report)
11. [Lot-check drift report](#11-lot-check-drift-report)
12. [Settings group "Stock and lots" (inventory.*)](#12-settings-group-stock-and-lots-inventory)
13. [Offline till: item list `lot` field and offline exceptions](#13-offline-till-item-list-lot-field-and-offline-exceptions)
14. [Error code cheat sheet](#14-error-code-cheat-sheet)
15. [Build order](#15-build-order)
16. [Doc vs code differences](#16-doc-vs-code-differences)

---

## 1. What lots are, and the invariant

### 1.1 Goal
Understand the model once so every screen shows the right numbers.

### 1.2 Who / settings
Nothing to build here; it explains sections 2 to 13.

### 1.3 The model
- A **lot** is one batch of one variant (a product row; "variant" and "product" are the same id everywhere): `qtyReceived`, `qtyRemaining`, `unitCost` (landed cost per unit, paise, 4 decimals), `receivedAt`, optional `mrpPaise`, `pricePaise`, `batchNo`, `expiryDate`, and `source`: `PURCHASE`, `OPENING`, `ADJUSTMENT`, `RETURN` or `TRANSFER`.
- Lots are created by: a purchase line (`PURCHASE`), existing stock when lots were introduced (`OPENING`; cost = last purchase landed cost, else cost unknown), a positive stock correction or stock-take surplus (`ADJUSTMENT`, at the last known cost), a return of goods that came from no lot (`RETURN`).
- **Invariant: the units left in a variant's lots always add up to its stock on hand.** Sales, online confirms, returns, supplier returns, stock takes and write-offs all move both in one transaction. If this ever fails the lot-check report (section 11) lists it.
- **Order of use:** oldest `receivedAt` first (FIFO). For a variant with `trackExpiry` on: earliest `expiryDate` first, lots without a date last (FEFO).
- **Two switches per variant:** `lotPriced` (the variant's selling price and MRP follow the lot that sells next, when that lot has its own price) and `trackExpiry` (FEFO). Both default off. Off = the variant keeps one price and lots only carry cost.
- Cost never reaches the till or the shop: sale lines expose no cost.

### 1.4 What the app must never compute
Lot remaining quantities, which lot sells next, the price of a line that spans lots, the cost of a sale, stock value, margin, `nextPriceChange`. Always show what the server returned.

---

## 2. Lot list per variant

### 2.1 Goal
A "Lots" tab on the admin variant/product screen: what was received, what is left, at what cost and price, with batch and expiry.

### 2.2 Who / settings
ADMIN.

### 2.3 Screens and fields

| Query | Type | Default | Validation |
|---|---|---|---|
| `onlyOpen` | bool | false | true = only lots with `qtyRemaining > 0`. Offer a toggle "Hide empty lots" |
| `page`, `size` | int | 0 / 20 | size clamped 1..50 |

Columns: Received (`receivedAt`), Source, Qty received / left / sold, Unit cost, MRP, Price, Batch, Expiry, Purchase link.

### 2.4 API sequence
1. `GET /admin/variants/{id}/lots?onlyOpen=false&page=0&size=20` -> Page of newest first:
```json
{ "id": "…", "productId": "…", "productName": "Basmati Rice", "receivedAt": "…Z", "qtyReceived": 10, "qtyRemaining": 8,
  "unitCost": 12000.0000, "costKnown": true, "mrpPaise": 18000, "pricePaise": 17000, "batchNo": "B12", "expiryDate": "2027-03-01",
  "source": "PURCHASE", "purchaseItemId": "…" }
```
   Unknown variant id returns an EMPTY page (not 404).
2. Row actions: "Write off remaining" (only when `qtyRemaining > 0`), section 8; "Open purchase" when `purchaseItemId` is not null.

### 2.5 State / derived
- `sold` is not sent: show `qtyReceived - qtyRemaining` (display arithmetic only).
- `costKnown:false` means the lot's cost is unknown: it was made from stock that had no purchase behind it. Its `unitCost` is stored as 0, shows as "Cost unknown", and it counts as zero value in valuation and zero cost in margin (section 9 and 10).
- `mrpPaise` / `pricePaise` null = the lot has no price of its own (the variant price applies). Show "Variant price".
- The row the next sale uses is the first with stock in use order (oldest, or earliest expiry when tracking). Do not mark it client-side if you do not have all pages; use the till scan's `lot.lotId` if you need it.

### 2.6 Edge cases
- Empty state: "No lots yet. Receive a purchase to create one."
- Many lots: always page; newest first.
- Money formatting: `unitCost` is paise, so 12000.0000 = Rs 120.0000; show 2 to 4 decimals of rupees.

### 2.7 Dart
```dart
class LotRow {
  final String id, productId, productName, source, batchNo; // batchNo "" when none
  final DateTime receivedAt;
  final int qtyReceived, qtyRemaining; final double unitCost; final bool costKnown; // unitCost in paise
  final int? mrpPaise, pricePaise; final String? expiryDate /* yyyy-MM-dd */, purchaseItemId;
}
```

### 2.8 Acceptance
- Given two purchase lines for a variant, when I open Lots, then two rows show newest first.
- Given `onlyOpen` on, then fully sold lots are hidden.
- Given a lot with no purchase behind it, then it shows "Cost unknown".

---

## 3. Lot settings: lotPriced and trackExpiry

### 3.1 Goal
Switch a variant to lot pricing (price follows the lot being sold) and/or expiry-first selling.

### 3.2 Who / settings
ADMIN. Audited. Turning `lotPriced` on takes effect at the variant's next stock movement (a receive, sale, return); it does not instantly re-price. Turning it off keeps the current price as the single price.

### 3.3 Screens and fields

| Field | Type | Required | Validation |
|---|---|---|---|
| `lotPriced` | bool | yes | null -> 400 `lotPriced` |
| `trackExpiry` | bool | yes | null -> 400 `trackExpiry` |

### 3.4 API sequence
1. Show two switches on the variant screen. When the screen opens read the stored state: `GET /admin/variants/{id}/lot-settings` -> 200 `{ "variantId": "…", "lotPriced": false, "trackExpiry": false }` (ADMIN; 404 `NOT_FOUND` for an unknown variant). It complements the `PUT`; read it again after a restart or when another admin may have changed it.
2. `PUT /admin/variants/{id}/lot-settings` body `{ "lotPriced": true, "trackExpiry": false }` -> 200 `{ "variantId": "…", "lotPriced": true, "trackExpiry": false }`. Always send BOTH values (send the other switch's last known value). 404 `NOT_FOUND` for an unknown variant.
3. After saving, refresh the lot list and the variant price (the server also sends a live `CatalogChanged` event and empties product caches).

### 3.5 State / derived
- With `lotPriced` on and the next lot having its own `pricePaise`, the server sets the variant's `pricePaise`/`mrpPaise` to that lot's. If the next lot has no price of its own, the variant price stays.
- The price changes by itself when a lot sells out or an older lot comes back (return, cancelled order). Do not write the product price from the app for a lot-priced variant: it would be overwritten at the next movement.

### 3.6 Edge cases
- Two admins: last write wins; each response is the stored state.
- Switch on with lots that have no prices: nothing changes visibly; tell the admin to give purchase lines a selling price (section 4).

### 3.7 Dart
```dart
class LotSettingsBody { bool lotPriced; bool trackExpiry; }
class LotSettingsResult { final String variantId; final bool lotPriced, trackExpiry; } // the same shape from GET and PUT
```

### 3.8 Acceptance
- Given a variant that was saved with `lotPriced: true`, when I open its screen (also after an app restart), then `GET lot-settings` returns the saved flags and the switches show them.
- Given an unknown variant id, then `GET lot-settings` answers 404.
- Given I enable lot pricing and save, then I get 200 echoing both flags.
- Given an unknown variant id, then 404 and no change.
- Given a null flag, then a 400 field error shows under it.

---

## 4. Purchase receiving: how a purchase line opens a lot

### 4.1 Goal
Make sure the purchase form feeds lots correctly. The purchase screen itself is in `P2`; this section only adds the lot meaning of its fields.

### 4.2 Who / settings
ADMIN. A recorded purchase line ALWAYS opens one lot (paid plus free units) when the delivery is recorded. Editing or cancelling the lots of a recorded purchase is not supported.

### 4.3 Screens and fields (purchase line, `POST /admin/purchases` `items[]`)

| Field | Type | Required | Validation | Lot meaning |
|---|---|---|---|---|
| `productId` | uuid | yes | | the variant |
| `qty` | int | yes | 1..1,000,000 | with `freeQty`, becomes `qtyReceived` |
| `freeQty` | int | no | 0..; total with qty at most 1,000,000 | included in the lot (cost spread over all units) |
| `unitCostPaise` | int | yes | 0..100,000,000 | the server turns it into the landed unit cost (tax, discount, freight, free units) with 4 decimals: `unitCost` of the lot |
| `mrpPaise` | int | no | >= 0 | lot MRP |
| `sellingPricePaise` | int | no | >= 0; if both given it cannot exceed `mrpPaise` (400 `sellingPricePaise`) | lot selling price; used for lot-priced variants |
| `batchNo` | string | no | max 40 | lot batch |
| `expiryDate` | date | no | | lot expiry (needed for FEFO and the expiring list) |
| `applyToVariant` | bool | no | true requires `mrpPaise` or `sellingPricePaise` (400 `applyToVariant`) | also sets the VARIANT's MRP/price now |

The lot is dated by the delivery's received date (the moment of recording when that is today). Receiving back-dated stock therefore puts the lot BEFORE newer lots in FIFO order.

### 4.4 API sequence
1. Fill the purchase form as in `P2` (needs `Idempotency-Key`).
2. On 201, the lots exist. Optionally reload the variant's lots (section 2) to show them.

### 4.5 State / derived
- `applyToVariant` changes the variant's price immediately. For a lot-priced variant the price follows the lot anyway at the next stock movement, so `applyToVariant` is mostly for non-lot-priced variants. The lot's own `mrpPaise`/`sellingPricePaise` are stored regardless.
- Landed unit cost and total cost are computed by the server; the app only shows them.
- Purchase returns take units from the lots of the returned purchase first, then oldest first.

### 4.6 Edge cases
- A line without prices on a lot-priced variant leaves that lot "Variant price".
- Expiry in the past is accepted; the lot appears in the expiring list at once.
- A retry of the purchase with the same `Idempotency-Key` does not create a second lot.

### 4.7 Dart
See `P2` for `PurchaseItemInput`; the lot fields are `mrpPaise`, `sellingPricePaise`, `batchNo`, `expiryDate`, `applyToVariant`.

### 4.8 Acceptance
- Given lines 3 units at price 150 and 10 units at price 170 on a lot-priced variant, when recorded, then two lots exist and the variant price is 150.
- Given price above MRP, then 400 under `sellingPricePaise`.
- Given `applyToVariant` true with no MRP or price, then 400 under `applyToVariant`.

---

## 5. Till: lot-aware scan, quote and split price runs

### 5.1 Goal
Show the cashier which price a unit sells at, warn when the price changes soon, and print bills whose quantity spans two prices.

### 5.2 Who / settings
CASHIER and ADMIN. Applies to variants with `lotPriced` on and stock in more than one price.

### 5.3 Screens and fields (read only)
Scan result extra notice; basket lines may repeat the same product at different prices; receipt prints `3 x 150 + 2 x 170`.

### 5.4 API sequence
1. **Scan.** `GET /pos/barcode/{code}` -> (existing fields plus `lot`):
```json
{ "product": { "productId": "…", "pricePaise": 15000, "mrpPaise": 16000,
               "lot": { "lotId": "…", "qtyLeftInLot": 3, "batchNo": "", "expiryDate": null,
                        "nextPriceChange": { "afterQty": 3, "newPricePaise": 17000, "newMrpPaise": 18000 } } },
  "qty": 1, "linePaise": 15000,
  "lot": { "lotId": "…", "qtyLeftInLot": 3, "batchNo": "", "expiryDate": null, "nextPriceChange": { "afterQty": 3, "newPricePaise": 17000, "newMrpPaise": 18000 } } }
```
   `lot` (top level) is null when the product has no stock; it is set for ANY product with stock, with `nextPriceChange: null` when the variant is not lot priced or all stock has one price. `product.lot` inside the scan is the same object for lot-priced products only.
2. If `nextPriceChange != null`, show a notice: "After {afterQty} more the price is {rupees(newPricePaise)}" (also MRP `newMrpPaise` if you print MRP). `qtyLeftInLot` is the units left at the current lot's price run start; `afterQty` = units that still go at the current price (it can exceed `qtyLeftInLot` when the next lot has the same price).
3. **Quote.** `POST /pos/quote` with the cart `{ "items": [ { "productId": "…", "qty": 5 } ] }` -> `lines` where a quantity spanning two prices comes back as TWO lines of the same `productId`:
```json
{ "lines": [ { "productId": "P", "name": "…", "qty": 3, "unitPricePaise": 15000, "linePaise": 45000, "discountPaise": 0, "netPaise": 45000, "gstPercent": 0 },
             { "productId": "P", "name": "…", "qty": 2, "unitPricePaise": 17000, "linePaise": 34000, "discountPaise": 0, "netPaise": 34000, "gstPercent": 0 } ],
  "totalPaise": 79000 }
```
   Totals, coupons, coins and GST already use these quoted prices.
4. **Sale.** `POST /pos/sales` with the same cart (as in `P3` F4) -> the bill `items[]` has the same two lines (each with its own line `id`). Returns refer to a line `id`; a line is returned at the price it was sold at.
5. **Printing.** Group the bill's lines by `productId` (keep order) and print one row per product: name, then `qty x price` joined by ` + `, with the total of the group's `linePaise`. Example: `3 x 150.00 + 2 x 170.00 = 790.00`.

### 5.5 State / derived
- Which lot each unit comes from and the split are decided by the server on the quote AND again on the sale. Quote and sale usually agree; if stock moved in between (another till sold the cheap units) the sale may price differently from the quote. After a sale always show the server's returned bill, and when the sale total differs from your quote answer re-quote (see `P3` F3/F4 for the sum-mismatch 400).
- Only unit-sold items are split (weighed items are never split). A product with one price across all stock is never split.
- The notice and the price on the scan card are the CURRENT lot's; do not add up lot data to predict a basket total, call the quote.

### 5.6 Edge cases
- Two basket rows of the same product (scanned twice) are priced in order: the second row starts counting after the first.
- Quantity above stock: the usual `OUT_OF_STOCK` behaviour is unchanged.
- Lines with a running flash sale are not split (section 7).
- Cashiers must never see cost: no endpoint here sends it on a sale line.
- A scan whose `lot` is null: show the item as out of stock if `qtyOnHand` is 0, otherwise sell normally.

### 5.7 Dart
```dart
class NextPriceChange { final int afterQty, newPricePaise, newMrpPaise; }
class LotInfo { final String lotId, batchNo; final int qtyLeftInLot; final String? expiryDate; final NextPriceChange? nextPriceChange; }
String? priceNotice(LotInfo? lot) => lot?.nextPriceChange == null ? null
    : 'After ${lot!.nextPriceChange!.afterQty} more the price is ${rupees(lot.nextPriceChange!.newPricePaise)}';
String splitText(List<BillLine> ls) => ls.map((l) => '${l.qty} x ${rupees(l.unitPricePaise)}').join(' + ');
```

### 5.8 Acceptance
- Given 3 units at 150 and 10 at 170, when I scan, then the card shows 150.00 and "After 3 more the price is 170.00".
- Given qty 5, when I quote, then two lines (3 at 150, 2 at 170) and total 790.00.
- Given the sale completes, then the receipt prints `3 x 150.00 + 2 x 170.00`.
- Given a return of the 170 line, then the refund is at 170.

---

## 6. Online: single price, CAP policy and QTY_EXCEEDS_LOT_PRICE

### 6.1 Goal
The shop app shows ONE price per item (the current lot's) and never mixes two prices in one order line.

### 6.2 Who / settings
Customers. Setting `inventory.lotCrossPolicy` = `CAP` (the only value).

### 6.3 Screens and fields
Product detail and list show `pricePaise` as usual; cart quantity stepper; checkout.

### 6.4 API sequence
1. Product price: from the normal product endpoints (`pricePaise`, `mrpPaise`). It rolls over by itself; refresh on the live `CATALOG` event (`CatalogChanged`) and when re-opening a screen.
2. `POST /cart/quote` (and `POST /orders` when placing) with a quantity above what is left at the current price -> **422**:
```json
{ "code": "QTY_EXCEEDS_LOT_PRICE", "message": "Only 3 available at this price, order the rest after it sells",
  "details": { "fields": { "qty": "Only 3 available at this price, order the rest after it sells" },
               "maxQty": 3, "availableAtThisPrice": 3,
               "items": [ { "productId": "…", "maxQty": 3, "availableAtThisPrice": 3 } ] } }
```
3. UI: show `message` as a toast on the cart; for each entry in `details.items` set that product's stepper maximum to `maxQty` and clamp the quantity; the top-level `maxQty` is the FIRST item's value (use `items` when several products are over).
4. Placing the order prices it; stock is taken (and lot cost recorded) when staff confirm; cancelling returns units to the lots they came from, so the price may roll back to the older lot.

### 6.5 State / derived
- `maxQty`/`availableAtThisPrice` are equal today and are computed by the server: units left at the current price (rest of the current lot plus following lots at the same price), plus any flash-sale units in the basket (section 7). Products whose whole stock has one price are never capped by this rule (only the usual stock check).
- The app never computes the cap. It does not know lot data.

### 6.6 Edge cases
- Race: the quote passes, the price rolls, and `POST /orders` then answers 422 or a total mismatch: re-quote and show the new price.
- After the cheap lot sells out, `maxQty` jumps to the next price's quantity; the same basket then quotes at the new price.
- A customer who adds the item again after a clamp should be allowed; do not block the product.
- Delivered/open order prices are fixed at placing; later lot changes never re-price an order.

### 6.7 Dart
```dart
if (e.code == 'QTY_EXCEEDS_LOT_PRICE') {
  for (final it in (e.details['items'] as List)) { cart.setMax(it['productId'], it['maxQty']); }
  toast(e.message);
}
```

### 6.8 Acceptance
- Given 3 units at 150 and 5 at 170, when a customer quotes 4, then 422 with `maxQty` 3 and the stepper is clamped to 3.
- Given 3 is quoted, then the total is 3 x 150.
- Given the order is confirmed by staff and later cancelled, then the 150 lot has its units back and the shop shows 150 again.

---

## 7. Flash sale precedence

### 7.1 Goal
Know which price wins when a flash sale and lot prices both apply.

### 7.2 Rules (from code)
- While a flash sale runs for a product, its sale units sell at the sale price whatever the lot price is; lot pricing applies to the REMAINING units, which start counting AFTER the flash units (flash units are skipped first when walking the lots).
- At the till, a line covered by a flash sale is not split by lot; only the non-flash rest is split.
- Online, flash units are ON TOP of the CAP: `maxQty = unitsAtThisPrice + flash units in the basket`.
- An offline bill is recorded at the rung price and does not consume flash units (see `P3` F11).

### 7.3 UI
Show the flash price badge as today (`40-flash-sales.md`); do not show lot notices on flash lines. Everything else follows sections 5 and 6.

### 7.4 Acceptance
- Given a flash sale of 2 units on a lot-priced item, when I quote 5, then 2 at the flash price and the other 3 priced by lot runs.

---

## 8. Expiring list and write-off

### 8.1 Goal
See lots close to or past expiry and take the remaining units out of stock.

### 8.2 Who / settings
ADMIN. Setting `inventory.expiryAlertDays` (default 30) is the default window; a daily job at 03:15 (shop time) sends staff the "Stock close to expiry" notification, and with `inventory.autoWriteOffExpired` on it first writes off lots already past their date.

### 8.3 Screens and fields

| Field | Type | Default | Validation |
|---|---|---|---|
| `days` (query) | int | `inventory.expiryAlertDays` | 0..365 (400 `days`); 0 = expiring today or already expired |
| `reason` (write-off body) | string | none | optional, max 200 |

### 8.4 API sequence
1. `GET /admin/stock/expiring?days=30` -> 
```json
{ "days": 30, "until": "2026-12-09", "truncated": false, "lots": [ { "id": "…", "productId": "…", "productName": "Milk", "qtyRemaining": 3, "expiryDate": "2026-11-12", "batchNo": "B1", "unitCost": 5000.0, "costKnown": true, "…": "…" } ] }
```
   Lots with stock only, soonest first, expired ones included, at most 200 (`truncated:true` -> "Showing first 200; narrow the window").
2. Highlight rows with `expiryDate` before today (India) red.
3. Write off: `POST /admin/stock/lots/{id}/write-off` body `{ "reason": "expired" }` (the body may be omitted) -> 200 `{ "lotId": "…", "unitsWrittenOff": 3 }`. Confirm with a dialog first: it cannot be undone from the app.
4. Errors: 404 `NOT_FOUND`; 409 `LOT_EMPTY` (someone else already sold or wrote it off: refresh); 409 `STOCK_MISMATCH` (stock on hand is lower than the lot: run the lot check, section 11).

### 8.5 State / derived
The write-off lowers stock on hand by the lot's remaining units, records a stock movement `EXPIRY_WRITE_OFF` and an audit entry, and (for a lot-priced variant) lets the price move to the next lot. The app never edits stock itself.

### 8.6 Edge cases
- Double tap: the second call returns 409 `LOT_EMPTY`; treat it as "already done" and refresh.
- Items without an expiry date never appear here.
- Auto write-off on: expired lots disappear overnight; the notification says how many were written off.
- Unknown `days` above 365 is a 400, not clamped.

### 8.7 Dart
```dart
class ExpiringResult { final int days; final String until; final bool truncated; final List<LotRow> lots; }
class WriteOffResult { final String lotId; final int unitsWrittenOff; }
```

### 8.8 Acceptance
- Given a lot expiring in 10 days, when I load the list with days 30, then it appears.
- Given I write off a lot with 3 units left, then 200 with `unitsWrittenOff: 3` and stock drops by 3.
- Given I write it off again, then 409 `LOT_EMPTY` and the list refreshes.
- Given `days=999`, then a field error on `days`.

---

## 9. Stock valuation

### 9.1 Goal
What the stock on the shelf is worth at cost.

### 9.2 Who / settings
ADMIN.

### 9.3 Screens and fields

| Query | Type | Default | Validation |
|---|---|---|---|
| `groupBy` | `product` / `category` | `product` | else 400 `groupBy` ("product or category") |
| `page`, `size` | int | 0 / 20 | size 1..50 |

### 9.4 API sequence
`GET /admin/stock/valuation?groupBy=product&page=0&size=20` ->
```json
{ "total": { "qty": 120, "valuePaise": 640000, "costUnknownProducts": 4, "costUnknownQty": 35 },
  "rows": { "items": [ { "id": "…", "name": "Basmati Rice", "qty": 40, "valuePaise": 480000, "costUnknownQty": 0 } ],
            "page": 0, "size": 20, "total": 1, "totalExact": true, "hasMore": false } }
```
Rows are largest value first. Only lots with stock count.

### 9.5 State / derived
- `valuePaise` = sum over lots of remaining units x unit cost, rounded to whole paise per group. Lots with `costKnown:false` count as 0 value, so the total is a LOWER bound while `costUnknownQty > 0`.
- Show a banner when `total.costUnknownQty > 0`: "{costUnknownQty} units in {costUnknownProducts} products have no known cost; the value is understated. Receive a purchase or correct the cost to fix it."
- `total` covers the whole shop regardless of paging.

### 9.6 Edge cases
Empty: "No stock". A category group's `id` is the category id. `total.qty` equals the sum of stock on hand only when the lot-check is clean.

### 9.7 Dart
```dart
class ValuationTotal { final int qty, valuePaise, costUnknownProducts, costUnknownQty; }
class ValuationRow { final String id, name; final int qty, valuePaise, costUnknownQty; }
class Valuation { final ValuationTotal total; final Page<ValuationRow> rows; }
```

### 9.8 Acceptance
- Given 4 units bought at Rs 25, then the total value is at least 10,000 paise.
- Given unknown-cost stock exists, then the banner shows.
- Given `groupBy=bad`, then a 400 on `groupBy`.

---

## 10. Margin report

### 10.1 Goal
Revenue (before tax), cost and margin by product, category or day.

### 10.2 Who / settings
ADMIN.

### 10.3 Screens and fields

| Query | Type | Default | Validation |
|---|---|---|---|
| `groupBy` | `product` / `category` / `day` | `product` | else 400 `groupBy` |
| `from`, `to` | date (India) | `from` = first of the month, `to` = today | `from` after `to` -> 400 `from`; more than one year (366 days) -> 400 `to` |
| `page`, `size` | int | 0 / 20 | size 1..50 |

### 10.4 API sequence
`GET /admin/reports/margin?groupBy=product&from=2026-11-01&to=2026-11-09&page=0&size=20` -> Page:
```json
{ "key": "<product id | category id | yyyy-MM-dd>", "name": "Basmati Rice", "revenuePaise": 79000, "costPaise": 54000, "marginPaise": 25000, "marginPercent": 31.65, "uncostedLines": 0 }
```
Product and category groups are ordered by margin descending; `day` is ordered by date ascending.

### 10.5 State / derived
- Revenue is before tax, net of returned units. Cost is the lot cost of the goods sold less the cost of units that went back on the shelf. Sources: counter bills and online orders that are not cancelled (online orders count from their placing date).
- `marginPercent` is null when revenue is 0 (show "-").
- **`uncostedLines`**: number of bill/order lines sold with NO cost recorded at all (sold before lots existed). Those lines are left out of revenue AND cost, so a non-zero value means the row understates the shop's real sales: show a warning "N lines have no cost data and are excluded".
- **`costKnown=false` lots**: a sale from such a lot has a recorded cost of 0 (the lot's unit cost is stored as 0), so it is NOT counted in `uncostedLines`; it is included with cost 0 and so makes the margin look better than it is. Use the valuation banner (section 9) to find products whose stock has unknown cost, and treat their margin as an upper bound.
- Never compute margin or percent in the app.

### 10.6 Edge cases
Empty range: empty page. `groupBy=day` dates are India days. Large ranges are paged. Revenue per row rounds to whole paise.

### 10.7 Dart
```dart
class MarginRow { final String key, name; final int revenuePaise, costPaise, marginPaise, uncostedLines; final double? marginPercent; }
```

### 10.8 Acceptance
- Given a sale of 3 at 150 (cost 100) and 2 at 170 (cost 120), then revenue 79,000, cost 54,000, margin 25,000.
- Given a line sold before lots existed, then `uncostedLines` is 1 and a warning shows.
- Given `from=2020-01-01&to=2026-01-01`, then 400 on `to`.

---

## 11. Lot-check drift report

### 11.1 Goal
A diagnostic: variants whose lots do not add up to stock on hand. The list should be empty.

### 11.2 Who / settings
ADMIN. Put it behind a "Stock health" row; show a green tick when empty.

### 11.3 API sequence
`GET /admin/stock/lot-check` -> `{ "drifts": [ { "productId": "…", "name": "…", "onHand": 10, "inLots": 9 } ], "truncated": false }` (at most 200 rows; `truncated:true` = more exist).

### 11.4 State / derived
`onHand` is the stock row; `inLots` is the sum of lot remaining. A drift means a bug or a manual database change. The app cannot fix it; show the list and tell the admin to contact support with the product ids. A stock-take (admin stock count) or positive correction creates an ADJUSTMENT lot and is the normal operational route.

### 11.5 Edge cases
Write-off on a drifted lot fails with 409 `STOCK_MISMATCH`. Drift is not auto-healed.

### 11.6 Dart
```dart
class Drift { final String productId, name; final int onHand, inLots; }
class LotCheck { final List<Drift> drifts; final bool truncated; }
```

### 11.7 Acceptance
- Given a healthy shop, then the list is empty and the row shows OK.
- Given a drift row, then product name, onHand and inLots show.

---

## 12. Settings group "Stock and lots" (inventory.*)

On `GET/PUT /admin/config` (ADMIN), group `INVENTORY` ("Stock and lots"). Body: `{ "changes": [ { "key": "inventory.expiryAlertDays", "value": "14" } ] }`.

| Key | Type | Default | Allowed | Meaning |
|---|---|---|---|---|
| `inventory.lotCrossPolicy` | ENUM | `CAP` | `CAP` only | Online orders across lots. `SPLIT` is refused (400 "must be one of: CAP" under `details.fields["inventory.lotCrossPolicy"]`) |
| `inventory.costingMethod` | ENUM | `FIFO` | `FIFO` only | Cost of goods sold. Show disabled |
| `inventory.expiryAlertDays` | INT | 30 | 1..365 | Window of the daily notification and default of the expiring list |
| `inventory.autoWriteOffExpired` | BOOLEAN | false | | Daily job (03:15) writes off lots past their expiry date |

Render these generically from the config response (each row carries label, help, type, allowed values). Edge: enabling auto write-off removes expired stock without a person looking: show a confirmation.

Acceptance: Given I save `SPLIT`, then 400 is shown under the key and the value is unchanged. Given `expiryAlertDays` 0, then 400.

---

## 13. Offline till: item list `lot` field and offline exceptions

### 13.1 Goal
Let the offline till show the price-change notice and record bills that were rung from a stale catalog.

### 13.2 Who / settings
CASHIER and ADMIN. The full offline bill flow is in `P3` F11; this section covers only lot effects.

### 13.3 Item list
`GET /pos/products?updatedSince=<instant>&page&size` returns products with `lot` (same object as section 5) ONLY for lot-priced products that have stock; otherwise `lot: null`. `pricePaise` is the CURRENT lot's price. `lot.qtyLeftInLot` and `lot.nextPriceChange` are the snapshot at download time. A lot rollover or stock change updates the product, so it appears again in the next `updatedSince` download: refresh often when online.

### 13.4 API sequence
1. Store `lot` in the local item table next to `pricePaise`.
2. Offline, show the price notice from the stored `nextPriceChange`, and (recommended, not enforced by the server) split a line that spans the boundary yourself: the first `afterQty` units at `pricePaise`, the rest at `newPricePaise`, as two `rung.lines` of the same `productId`. Decrement the stored snapshot locally as you sell.
3. Upload as in `P3` F11: `POST /pos/sync/sales`, each bill carries the `rung` snapshot (`rung.lines[]` with the price you charged). The server records your numbers, takes the lots at upload time, and does NOT re-price.
4. Read the per-bill `exceptions` in the response: `PRICE_CHANGED` (a rung line's price differs from the server's CURRENT catalog price, which for a lot-priced item is the current lot's price, so a lot rollover while offline triggers it), `OVERSOLD` (more units sold than the system had; the shortfall is recorded, the bill is still created and stock/lots are floored at zero). The bill is always recorded; the manager reviews exceptions in the admin offline-exceptions screen (`P3`).

### 13.5 State / derived
- Which lot the units were taken from, their cost, and the stock result are server decisions at upload.
- The server compares each rung line only with the current catalog price, not with the individual lots: a correctly split second line at the next lot's price is flagged `PRICE_CHANGED` only while the catalog price is still the first lot's price. Treat these flags as "please review", never as errors.

### 13.6 Edge cases
- A product with `lot: null` offline: no notice; sell at `pricePaise`.
- Offline quantity above the downloaded stock is allowed by the till rules of `P3`; the server records OVERSOLD.
- Do not use the `lot.lotId` as an input to any call: it is informational.

### 13.7 Dart
Reuse `LotInfo` and `NextPriceChange` from section 5. Local table: `productId, pricePaise, mrpPaise, lotJson`.

### 13.8 Acceptance
- Given the catalog download had a lot with 3 left and a next price 170, when offline I sell 5, then the till prints `3 x 150 + 2 x 170` and uploads two rung lines.
- Given the lot rolled over before upload, then the bill is `CREATED` with a `PRICE_CHANGED` exception.
- Given I sold more than the system had, then `CREATED` with `OVERSOLD` and no error shown to the cashier beyond a "to review" tag.

---

## 14. Error code cheat sheet

| HTTP / code | Where | UI |
|---|---|---|
| 400 `VALIDATION_FAILED` | days, groupBy, from, to, lot-settings flags, config values | map `details.fields` |
| 404 `NOT_FOUND` | unknown variant (lot-settings), unknown lot (write-off) | refresh |
| 409 `LOT_EMPTY` | write-off of an empty lot | refresh, "already done" |
| 409 `STOCK_MISMATCH` | write-off when stock is below the lot | show lot-check |
| 422 `QTY_EXCEEDS_LOT_PRICE` | `/cart/quote`, `/orders` | clamp steppers to `maxQty` |
| 400 / existing `OUT_OF_STOCK` | till and shop | unchanged |
| 403 | cashier on admin lot routes | hide the screens |

---

## 15. Build order

1. Lot list tab (2) and lot-settings switches (3).
2. Till scan notice and bill grouping/printing (5).
3. Shop app 422 handling (6).
4. Expiring list + write-off (8), settings group (12).
5. Valuation (9), margin (10), lot-check (11).
6. Offline till `lot` storage and exception handling (13).

---

## 16. Doc vs code differences

Items where `74-stock-lots-and-pricing.md` (or `P2`) disagreed with, or omitted something from, the code. The code won; `74` was corrected for items 1 to 9 and 11, and `P2` for item 10 (see `README.md`):

1. **Switch state is readable** (resolved by `GET /admin/variants/{id}/lot-settings`, `{variantId, lotPriced, trackExpiry}`, 404 for an unknown variant). `74` was corrected.
2. **Response bodies missing in `74`:** lot-settings returns `{ variantId, lotPriced, trackExpiry }`; write-off returns `{ lotId, unitsWrittenOff }`; expiring returns `{ days, until, lots, truncated }`. Write-off `reason` max 200 characters.
3. **Unit cost unit.** `74` example `"unitCost":12.5000` reads like rupees; it is paise with 4 decimals (cost 12000 paise per unit appears as 12000.0).
4. **Split lines are POS only.** `74` says "quote and sale" without saying the online quote never splits (it caps instead); at the till only unit-sold, non-flash lines are split.
5. **`lot` on scan vs item list.** `74` implies `lot` always signals lot pricing. Code: scan `lot` is present for any product with stock (with `nextPriceChange:null` when not lot priced); the `GET /pos/products` list carries `lot` only for lot-priced products with stock.
6. **Offline `PRICE_CHANGED`.** `74` says a price that "differs from the catalog (including because the lot rolled over)" is flagged: correct, but the comparison is per rung line against the current catalog price only; lots are not checked per line.
7. **Turning on `lotPriced`.** `74` is silent: the price follows the lot at the variant's NEXT stock movement, not immediately.
8. **Lot list of an unknown variant** returns an empty page, not 404.
9. **Margin and unknown cost.** `74` says lines sold before lots existed are in `uncostedLines`. Lines sold from lots with `costKnown:false` are NOT: they are costed at 0 and inflate margin. `marginPercent` is null at zero revenue; `day` groups sort by date.
10. **`P2` line for `expiryDate`** says "none enforced (stored on the line only; no lot stock yet)". Stale: the date now becomes the lot's expiry and drives FEFO and the expiring list.
11. **Valuation total.** `74` does not say unknown-cost lots count as 0 value (total is a lower bound while `costUnknownQty > 0`).

Not verified: the exact JSON number formatting of `unitCost` (shown as 12000.0 or 12000.0000); that the live event name for the shop app is literally `CATALOG` (the code publishes `CatalogChanged(CATALOG)`, the client event envelope is in `00-overview.md`); the recommended offline split in section 13 (the server does not enforce it).
