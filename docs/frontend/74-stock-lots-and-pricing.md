# 74 - Stock lots, lot prices, cost and margin

Audience: the Flutter admin app, the till (POS) app and the shop app. All money is integer paise. Admin endpoints need an ADMIN token; the till endpoints are unchanged except for the extra fields named below.

## 1. What a lot is

Every delivery line, opening balance, correction or return creates a **lot**: a batch of one variant with quantity received, quantity left, unit cost (landed cost, four decimals of a paisa), received date, optional MRP, selling price, batch number and expiry date. The units left in a variant's lots always add up to its stock on hand. Sales take from the **oldest lot first** (FIFO). A variant with **track expiry** on sells the lot with the **earliest expiry first** (FEFO).

Two switches per variant (read with `GET /admin/variants/{id}/lot-settings`, set by `PUT` on the same path):
- `lotPriced`: the variant's selling price and MRP follow the lot that sells next (the lot's own price/MRP when it has one). Off by default: the variant price stays and lots only carry cost. Turning it on takes effect at the variant's NEXT stock movement (receive, sale, return), not instantly.
- `trackExpiry`: FEFO.

Legacy stock became one `OPENING` lot per variant (cost = last purchase landed cost, else `costKnown=false`).

## 2. Admin screens (suggested flow)

1. Variant screen: a "Lots" tab (`GET /admin/variants/{id}/lots`), two switches (lot priced, track expiry).
2. Purchase screen: unchanged; each line may carry `mrpPaise`, `sellingPricePaise`, `batchNo`, `expiryDate`. These now also become the lot's MRP/price/batch/expiry. `applyToVariant` still changes the variant now (for a lot-priced variant the price follows the lot anyway).
3. Stock report screens: valuation, near-expiry list (with "write off"), margin report, lot check (diagnostic).
4. Settings screen (Stock and lots group): see section 8.

## 3. Till (counter) behaviour

- **Scan** (`GET /pos/barcode/{code}`) answers as before plus `lot`:
```json
{"product": {"productId": "...", "pricePaise": 15000, "mrpPaise": 16000, "lot": {"lotId": "...", "qtyLeftInLot": 3, "batchNo": "", "expiryDate": null,
  "nextPriceChange": {"afterQty": 3, "newPricePaise": 17000, "newMrpPaise": 18000}}},
 "qty": 1, "linePaise": 15000,
 "lot": {"lotId": "...", "qtyLeftInLot": 3, "batchNo": "", "expiryDate": null, "nextPriceChange": {"afterQty": 3, "newPricePaise": 17000, "newMrpPaise": 18000}}}
```
  `lot` is present for any product with stock (null without stock); `nextPriceChange` is null when the price stays the same for all stock (and always for variants that are not lot priced). Show a notice: "after 3 more the price is 170.00".
- **Quote and sale** (`POST /pos/quote`, `/pos/sales`; the counter only, the online quote never splits, it caps, see section 4; at the till only unit-sold, non-flash lines are split): when a line's quantity spans lots with different prices the server returns **one bill line per price run**, same `productId`: 5 pieces become `{qty:3, unitPricePaise:15000}` and `{qty:2, unitPricePaise:17000}`. Group lines of the same `productId` to print "3 x 150 + 2 x 170". Totals, coupons, coins and GST use these quoted prices. Sale lines never expose cost.
- **Flash sales win**: while a flash sale runs, its units are sold at the sale price whatever the lot price is; lot pricing applies to the remaining units, which start counting after the flash units.
- **Offline**: the item list (`GET /pos/products`, `updatedSince`) carries `lot` only for lot-priced products that have stock (the scan answer has it for any product with stock) (current lot price in `pricePaise`, `lot.qtyLeftInLot`, `lot.nextPriceChange`). The offline bill sends what it rang (`rung` snapshot). On upload the server allocates lots at that time; a price that differs from the current catalog price (including because the lot rolled over meanwhile; the check is per rung line against the catalog price, lots are not checked per line) is flagged with the existing `PRICE_CHANGED` offline exception, a shortfall as `OVERSOLD`. The bill is always recorded.
- Returns put restocked units back into the lot they came from and reverse their cost; units marked `restock=false` stay in the defective flow (their cost stays as a loss).

## 4. Online (shop app) behaviour

- The shop shows **one price**: the price of the current lot. Prices roll over automatically (a `CatalogChanged` live event is sent and product caches are emptied).
- **CAP policy**: when a lot-priced item has more than one price in stock, a basket may not hold more units than remain at the current price. `POST /cart/quote` (and order placing) answers **422**:
```json
{"code": "QTY_EXCEEDS_LOT_PRICE", "message": "Only 3 available at this price, order the rest after it sells",
 "details": {"fields": {"qty": "Only 3 available ..."}, "maxQty": 3, "availableAtThisPrice": 3,
             "items": [{"productId": "...", "maxQty": 3, "availableAtThisPrice": 3}]}}
```
  Show the message and clamp the stepper to `maxQty`. Units under a running flash sale are on top of the cap.
- The order is priced at placing; stock is taken (and its lot cost recorded) when staff confirm; cancelling puts units back in the lots they came from.

## 5. Admin endpoints

| Method and path | Purpose |
|---|---|
| `GET /admin/variants/{id}/lots?onlyOpen=false&page=0&size=20` | Lots of a variant, newest first (paged); an unknown variant gives an empty page, not 404 |
| `GET /admin/variants/{id}/lot-settings` | The saved switches `{"variantId","lotPriced","trackExpiry"}` (404 unknown variant). Use it to show the two switches when the screen opens |
| `PUT /admin/variants/{id}/lot-settings` body `{"lotPriced":true,"trackExpiry":false}` | Switches (audited); answers the same `{variantId, lotPriced, trackExpiry}`; both flags required (400 `lotPriced`/`trackExpiry`). 404 unknown variant |
| `GET /admin/stock/expiring?days=30` | `{days, until, lots, truncated}`: lots with stock expiring within `days` (0-365, default the setting), expired included, at most 200 (`truncated`) |
| `POST /admin/stock/lots/{id}/write-off` body `{"reason":"..."}` optional (max 200 chars) | Takes the lot's remaining units out of stock (history reason `EXPIRY_WRITE_OFF`, audited). Answers `{lotId, unitsWrittenOff}`. 404 unknown, 409 `LOT_EMPTY` |
| `GET /admin/stock/valuation?groupBy=product|category&page&size` | Stock value at cost |
| `GET /admin/reports/margin?groupBy=product|category|day&from&to&page&size` | Margin (India dates, default this month, at most one year) |
| `GET /admin/stock/lot-check` | Variants whose lots differ from stock on hand; should be `{"drifts":[],"truncated":false}` |

Lot row (`unitCost` is in PAISE per unit with up to four decimals, not rupees: a cost of Rs 120 per unit is `12000.0` or `12000.0000`; divide by 100 for rupees): `{"id","productId","productName","receivedAt","qtyReceived","qtyRemaining","unitCost":12500.0000,"costKnown":true,"mrpPaise","pricePaise","batchNo","expiryDate","source":"PURCHASE|OPENING|ADJUSTMENT|RETURN|TRANSFER","purchaseItemId"}`.

Valuation (lots with unknown cost count as 0 value, so `total.valuePaise` is a lower bound while `costUnknownQty > 0`): `{"total":{"qty","valuePaise","costUnknownProducts","costUnknownQty"},"rows":{"items":[{"id","name","qty","valuePaise","costUnknownQty"}],"page","size","total","totalExact","hasMore"}}`.

Margin row: `{"key","name","revenuePaise","costPaise","marginPaise","marginPercent":31.65,"uncostedLines"}`. Revenue is before tax and net of returned units; cost is the FIFO lot cost less cost of units back on the shelf. Lines sold before lots existed have no cost: they are counted in `uncostedLines` and left out of the money. Lines sold from a lot with `costKnown:false` are NOT in `uncostedLines`: they are costed at 0 and so raise the margin; treat the margin as an upper bound while unknown-cost lots exist. `marginPercent` is null when revenue is 0; `day` groups are sorted by date.

## 6. Costing rules

FIFO. A line's cost is the exact sum of units x lot unit cost, rounded half up to whole paise once; the rounding remainder sits on the last lot used. Returns reverse cost proportionally (the pieces of a fully returned allocation add up to its cost). Positive stock corrections open an `ADJUSTMENT` lot at the last known cost; negative ones and stock-take shortfalls consume FIFO. Purchase returns consume the lots of the returned purchase first, then FIFO. Editing or cancelling a recorded purchase is not supported (it would need a check that none of its lots was sold or returned).

## 7. Notifications

Every day at 03:15 (shop time) staff get "Stock close to expiry" for lots expiring inside the window. With `inventory.autoWriteOffExpired` on, lots already past their date are written off first.

## 8. Settings (config screen, group "Stock and lots")

- `inventory.lotCrossPolicy` = `CAP` (only value). `SPLIT` (price an order per lot) is **not supported yet**; saving it is refused with the usual 400 "must be one of: CAP".
- `inventory.costingMethod` = `FIFO` (only value; weighted average may come later).
- `inventory.expiryAlertDays` default 30 (1-365).
- `inventory.autoWriteOffExpired` default false.

## 9. Errors

`QTY_EXCEEDS_LOT_PRICE` (422), `LOT_EMPTY` (409), `STOCK_MISMATCH` (409, write-off when stock is below the lot: run the lot check), `OUT_OF_STOCK` (unchanged), validation 400 with `fields` for `days`, `groupBy`, `from`, `to`.

## 10. Dart sketches

```dart
class NextPriceChange { final int afterQty; final int newPricePaise; final int newMrpPaise; ... }
class LotInfo { final String lotId; final int qtyLeftInLot; final NextPriceChange? nextPriceChange; ... }

String? priceNotice(LotInfo? lot) => lot?.nextPriceChange == null ? null
    : 'After ${lot!.nextPriceChange!.afterQty} more the price is ${rupees(lot.nextPriceChange!.newPricePaise)}';

// bill lines: group by productId to print "3 x 150 + 2 x 170"
String splitText(List<BillLine> ls) => ls.map((l) => '${l.qty} x ${rupees(l.unitPricePaise)}').join(' + ');

// online: handle the cap
if (e.code == 'QTY_EXCEEDS_LOT_PRICE') { stepper.max = e.details['maxQty']; toast(e.message); }
```

## 11. Checklist

- [ ] Variant screen: lots tab and the two switches
- [ ] Till: show `lot.nextPriceChange` notice after a scan; group same-product bill lines
- [ ] Offline till: store `lot` from the item list; keep sending the `rung` snapshot
- [ ] Shop app: handle 422 `QTY_EXCEEDS_LOT_PRICE`, refresh on the CATALOG live event
- [ ] Admin: valuation, margin, expiring + write-off, lot check, settings group
- [ ] Never show cost to cashiers (the API does not send it on sale lines)
