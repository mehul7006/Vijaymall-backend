# 70. Purchases v2: suppliers, GST purchases, payments, returns (debit notes), payables and reports

Audience: Flutter and web teams and the LLM assistants they use. Read `00-overview.md` first (auth, error envelope, pagination, money, idempotency). This file replaces the purchasing sections of `30-pos-reports-purchasing.md` (`POST/GET /admin/purchases`); everything is **admin only** (`/api/v1/admin/**`, role ADMIN). Old clients that only send `supplierName`, `invoiceNo`, `note` and `items` keep working, with the two visible differences listed in "What changes for existing clients".

Conventions used below:

- Money is an integer number of **paise** in fields ending `Paise`. Dates (`invoiceDate`, `dueDate`...) are `yyyy-MM-dd` India dates. Timestamps are ISO-8601 UTC. IDs are UUID strings.
- Error body: `{ "code", "message", "traceId", "details": { "fields": { "<field>": "<text>" }, ... } }`. Validation is 400 `VALIDATION_FAILED`. Business refusals use the codes in each endpoint (409/422).
- Lists use the standard page: `{ items, page, size, total, totalExact, hasMore }` (`size` 1..50; `total` stops counting at 10,000: show "10,000+" when `totalExact` is false).
- Every write needs an `Idempotency-Key` header (a fresh UUID per tap, reused on retries) unless stated. A repeat with the same key and body returns the first answer and changes nothing; the same key with another body is `409 IDEMPOTENCY_KEY_REUSED`.

---

## 1. Purpose and screen flow

1. **Suppliers** (master list, add/edit/switch off, search). Pick a supplier in the purchase form (autocomplete `GET /admin/suppliers?q=&size=`), or just type a name: the backend finds the supplier by name or creates it.
2. **New purchase**: pick products (call `GET /admin/purchases/lookup?productId=` for the GST rate, HSN, current MRP/price and the last purchase cost), fill quantities, free quantities, unit cost before tax, discounts, GST, batch/expiry, optional "apply MRP/price to the product". The server works out every figure; show them from the answer. Optionally record the payment now (paid in full, part, or later) and the due date.
3. **Purchase list** with filters (payment status, supplier, overdue, dates) and a **payables** header (total owed, overdue).
4. **Purchase detail**: lines with landed cost, payments list, "Add payment" and "Return goods" buttons.
5. **Returns (debit notes)** list and detail.
6. **Reports**: purchase register, input GST, discounts, payables ageing, product price history.

---

## 2. Rules the app must know

### 2.1 GST mode and the cost of goods
`gstMode` is chosen per purchase: `REGULAR` (default, from the shop setting `purchase.gstMode`, group "GST and bills"; the admin can change the default in the config screen), `COMPOSITION`, or `NONE`.

| mode | tax charged | in the unit cost? |
|---|---|---|
| `REGULAR` | yes (CGST+SGST or IGST, plus cess) | no: the tax is claimed back as input credit, cost = taxable value (+ freight share) |
| `COMPOSITION` | yes | yes: all tax and cess is part of the cost |
| `NONE` | no: every GST and cess rate is treated as 0 | not applicable |

**Landed cost per unit** = (line taxable value + non-creditable tax on the line + the line's share of freight) / (qty + freeQty). Freight and its GST are shared over lines in proportion to taxable value (by quantity when every taxable value is 0). Under REGULAR the freight GST is creditable (not in cost); under COMPOSITION it is in the cost.

### 2.2 Order of the maths (all in exact paise)
1. `gross = qty * unitCostPaise` (free units cost nothing).
2. Line discount: `discountPercent` (if above 0) or `discountPaise`. `taxable_before_doc = gross - discount`.
3. Document discount: `docDiscountPercent` (if above 0, of the sum of `taxable_before_doc`) or `docDiscountPaise`. It is spread over the lines in proportion to `taxable_before_doc` (largest remainder, the shares add up exactly): `taxablePaise = taxable_before_doc - share`. So the discount reduces the GST base.
4. Tax on `taxablePaise`: `gst = round_half_up(taxable * gstPercent / 100)`; same for cess with `cessPercent`. Intra-state: `cgst = floor(gst/2)`, `sgst = gst - cgst` (the odd paisa goes to SGST); inter-state: all `igst`.
5. `lineTotalPaise = taxablePaise + cgst + sgst + igst + cessPaise`.
6. Freight: `freightPaise` plus `freightGstPaise = round_half_up(freight * freightGstPercent / 100)`, split like the line GST.
7. `subtotal = sum(lineTotalPaise) + freightPaise + freightGstPaise`. If `roundToRupee` is true, `roundOffPaise = round_half_up_to_100(subtotal) - subtotal` (negative or positive) and `totalCostPaise = subtotal + roundOffPaise`; otherwise `roundOffPaise = 0`.
8. If the app sends `expectedTotalPaise` and it differs from the server's total the purchase is refused with `422 TOTAL_MISMATCH` (`details.totalPaise` is the server's number). The server always computes; never send line totals.

**`totalCostPaise` is what the supplier is owed for the document** (goods, tax, freight, round-off). Old apps showed it as "cost": with a GST product it is now higher than quantity times unit cost. `taxablePaise` is the old number.

### 2.3 Intra-state or inter-state (IGST)
The shop's state is the `stateCode` in shop settings (default `24`). The supplier's state is, in order: `supplierStateCode` sent with the purchase; the supplier's stored state; the first two digits of its GSTIN. If a supplier state is known and differs from the shop's state the purchase is inter-state (`interState: true`, IGST only); otherwise CGST+SGST. If the state is unknown the purchase is treated as intra-state. A `supplierStateCode` that disagrees with the GSTIN's first two digits is `400` on `supplierStateCode`.

### 2.4 Unit cost precision and rounding
- `unitCostPaise` (the invoice price) is a whole number of paise.
- The landed unit cost is stored with **4 decimals of a paisa** (`numeric(18,4)`), e.g. 10 paid + 2 free units at Rs 100 gives `8333.3333`. Totals stay exact paise: `landedTotalPaise` is the exact total the unit cost came from.
- Views return both `landedUnitCostPaise` (always exact, 4 decimals, JSON number) and `landedUnitCostRoundedPaise`, rounded by the request parameter `rounding` on the GET views: `EXACT` (4 decimals), `PAISE` (nearest paisa, half up; the default), `RUPEE` (nearest rupee, still in paise, e.g. `8300`). An unknown value is `400` on `rounding`. The answer carries `rounding` so the screen can label it. The same parameter exists on the list, the detail and the product price history.
- Dart: parse these two fields as `double` (or `Decimal`), never `int`.

### 2.5 Payment status is derived
`paymentStatus` is computed by the database from totals and payments, never trusted from the client (except at creation, where the paid amount becomes the first payment row):
- `due = totalCostPaise - returnedPaise - paidPaise` (`duePaise`; **negative means the supplier owes the shop**, e.g. goods returned after full payment).
- `PENDING`: nothing paid and something due. `PARTIAL`: something paid and something due. `PAID`: nothing (or less than nothing) due.
- `overdue` = `dueDate` is before today (India time) and `duePaise > 0`.
- Purchases recorded before this release were treated as paid in full when they were migrated (one `CASH` payment with reference `LEGACY`).

### 2.6 Price changes from a purchase
A line with `applyToVariant: true` and `mrpPaise` and/or `sellingPricePaise` updates that product's MRP/price in the same transaction (same rules as editing the product: price may not exceed MRP). The admin decides, every change is audited (`PURCHASE_PRICE_APPLY`, "mrp a -> b, price c -> d (purchase id)"). Without the flag the MRP/price are only stored on the line.

### 2.7 Stock
Receiving adds `qty + freeQty` to stock (reason `PURCHASE`, linked to the purchase) exactly once per idempotency key. A debit note takes the units out again (reason `PURCHASE_RETURN`). Lots/batches as separate stock records are not built yet: `batchNo` and `expiryDate` are only stored on the line.

---

## 3. Endpoint list

| # | Endpoint | Purpose |
|---|---|---|
| 1 | `GET /admin/suppliers` | Search/list suppliers |
| 2 | `POST /admin/suppliers` | Add a supplier |
| 3 | `GET /admin/suppliers/{id}` | One supplier |
| 4 | `PUT /admin/suppliers/{id}` | Edit a supplier |
| 5 | `POST /admin/suppliers/{id}/deactivate` | Switch off |
| 6 | `POST /admin/suppliers/{id}/activate` | Switch on |
| 7 | `POST /admin/purchases` | Record a purchase |
| 8 | `GET /admin/purchases` | List with filters |
| 9 | `GET /admin/purchases/{id}` | One purchase |
| 10 | `POST /admin/purchases/{id}/payments` | Pay the supplier |
| 11 | `GET /admin/purchases/payables` | What we owe |
| 12 | `GET /admin/purchases/lookup?productId=` | Defaults and last cost for the form |
| 13 | `POST /admin/purchases/{id}/returns` | Return goods (debit note) |
| 14 | `GET /admin/purchases/{id}/returns` | Debit notes of a purchase |
| 15 | `GET /admin/purchase-returns` | All debit notes |
| 16 | `GET /admin/purchase-returns/{id}` | One debit note |
| 17 | `GET /admin/products/{id}/purchase-prices` | Price history of a product |
| 18 | `GET /admin/reports/purchases/register` | Purchase register |
| 19 | `GET /admin/reports/purchases/input-gst` | Input GST by rate |
| 20 | `GET /admin/reports/purchases/discounts` | Discount summary |
| 21 | `GET /admin/reports/purchases/ageing` | Payables ageing |

---

## 4. Suppliers

A supplier is identified by its name compared **trimmed, with runs of spaces as one space, ignoring case** ("Agro  Traders " = "agro traders"). Names are unique.

### GET /api/v1/admin/suppliers
Query: `q` (matches name, phone or GSTIN, contains, any case; `%` and `_` are literal), `active` (true/false; omit for both), `page`, `size`. Sorted by name. Response: page of

```json
{ "id": "uuid", "name": "Agro Traders", "phone": "98250 11111", "gstin": "24ABCDE1234F1Z5", "stateCode": "24",
  "address": "Rajkot", "paymentTermsDays": 30, "active": true, "createdAt": "2026-11-09T05:00:00Z",
  "lastInvoiceNo": "INV-77", "lastPurchaseAt": "2026-11-09T05:30:00Z", "purchaseCount": 4, "totalDuePaise": 68000 }
```
`lastInvoiceNo` is `""` and `lastPurchaseAt` null for a supplier without purchases. `totalDuePaise` adds up `duePaise` of all its purchases (negative = supplier owes us). The figures are fetched in two queries per page, not per row.

### POST /api/v1/admin/suppliers
Header `Idempotency-Key` is optional (a repeat with the key returns the first answer). Body (PUT takes the same body and replaces all fields):

| field | rules |
|---|---|
| name | required, 1..120 after trimming |
| gstin | optional, upper-cased, must match the GSTIN pattern (15 chars) |
| stateCode | optional 2 digits; if a GSTIN is given it must equal its first two digits (otherwise left out it is taken from the GSTIN) |
| phone | optional, digits, spaces, `+`, `-`, at most 20 |
| address | optional, at most 300 |
| paymentTermsDays | optional 0..365 (default 0); used for the due date of new purchases when none is sent |

201 with the supplier view. Errors: 400 `VALIDATION_FAILED` (fields as above), 409 `SUPPLIER_EXISTS` (`details.supplierId` is the existing one, `details.fields.name`).

### GET /api/v1/admin/suppliers/{id}, PUT /api/v1/admin/suppliers/{id}
404 `NOT_FOUND` when unknown; PUT also 409 `SUPPLIER_EXISTS` when the new name belongs to another supplier.

### POST /api/v1/admin/suppliers/{id}/deactivate, /activate
200 with the supplier. A switched-off supplier cannot be used on a new purchase (400 on `supplierId` or `supplierName`: "this supplier is switched off"); its history stays. Repeating is harmless.

### Suppliers created from a purchase
If a purchase is sent without `supplierId`, the supplier is found by name, or created on the fly (audit `SUPPLIER_CREATE`). `supplierPhone`, `supplierGstin` and `supplierStateCode` sent with the purchase **fill in what the supplier is missing**; values already stored are never overwritten (edit the supplier for that).

---

## 5. Purchases

### POST /api/v1/admin/purchases
Header `Idempotency-Key` mandatory. Only `items` and one of `supplierId` / `supplierName` are required.

```json
{
  "supplierId": "uuid-or-omit", "supplierName": "Agro Traders",
  "supplierPhone": "98250 11111", "supplierGstin": "24ABCDE1234F1Z5", "supplierStateCode": "24",
  "invoiceNo": "INV-77", "invoiceDate": "2026-11-09", "receivedDate": "2026-11-09",
  "note": "Morning delivery", "attachmentNote": "https://drive.example/inv77.pdf",
  "gstMode": "REGULAR",
  "docDiscountPercent": 2.5, "docDiscountPaise": null,
  "freightPaise": 5000, "freightGstPercent": 18,
  "roundToRupee": true, "expectedTotalPaise": null,
  "paymentStatus": "PARTIAL", "paidPaise": 50000, "paymentMode": "UPI", "paymentRef": "UTR123", "moneyAccountId": null,
  "dueDate": "2026-12-09",
  "items": [ {
    "productId": "uuid", "qty": 10, "freeQty": 2, "unitCostPaise": 10000,
    "discountPercent": 5, "discountPaise": null, "gstPercent": 18, "cessPercent": 0, "hsn": "3401",
    "mrpPaise": 15000, "sellingPricePaise": 14000, "batchNo": "B-1", "expiryDate": "2027-01-31", "applyToVariant": true } ]
}
```

| field | rules |
|---|---|
| supplierId / supplierName | one required; id must exist and be active; name 1..120 |
| invoiceNo | max 60; a repeated invoice number for the same supplier only adds a `warnings` entry |
| invoiceDate, receivedDate | default today (India); not in the future |
| attachmentNote | max 500: free text or a link; **no file upload yet** |
| gstMode | `REGULAR`, `COMPOSITION`, `NONE` (any case); default from the shop setting |
| docDiscountPercent / docDiscountPaise | give one (percent 0..100, at most 2 decimals) |
| freightPaise / freightGstPercent | freight >= 0; GST percent one of 0, 3, 5, 12, 18, 28 (ignored when freight is 0) |
| roundToRupee | default false |
| expectedTotalPaise | optional guard, see 2.2 |
| paymentStatus | `PAID`, `PARTIAL`, `PENDING`; default: derived from `paidPaise` (nothing = PENDING, equal to total = PAID, otherwise PARTIAL) |
| paidPaise | PENDING: 0 or omitted; PAID: omitted or equal to the server total; PARTIAL: more than 0 and less than the total |
| paymentMode | `CASH`, `UPI`, `CARD`, `BANK`, `CHEQUE`; required when something is paid |
| paymentRef | max 80 (UTR, cheque number) |
| moneyAccountId | uuid, **accepted and stored but not validated yet** (the money-account module is separate; any uuid is kept as is) |
| dueDate | not before `invoiceDate`; default `invoiceDate + supplier.paymentTermsDays` when the terms are above 0, else null |
| items | 1..200, a product at most once |
| items[].qty | 1..1,000,000; `qty + freeQty` at most 1,000,000 |
| items[].freeQty | default 0; units that cost nothing but are added to stock and shared in the landed cost |
| items[].unitCostPaise | 0..100,000,000, before tax |
| items[].discountPercent / discountPaise | one of them; percent 0..100 (max 2 decimals); amount not above the line amount |
| items[].gstPercent | one of 0, 3, 5, 12, 18, 28; default **the product's GST percent** (product counter attributes) |
| items[].cessPercent | 0..100, default 0 |
| items[].hsn | blank or 4..8 digits; default the product's HSN |
| items[].mrpPaise / sellingPricePaise | optional, `sellingPricePaise` not above `mrpPaise` |
| items[].batchNo | max 40 |
| items[].applyToVariant | default false; needs `mrpPaise` or `sellingPricePaise` |

Response 201 (the same object as `GET /admin/purchases/{id}`, plus `warnings`):

```json
{
  "id": "uuid", "supplierId": "uuid", "supplierName": "Agro Traders", "supplierPhone": "98250 11111", "supplierGstin": "24ABCDE1234F1Z5",
  "supplierStateCode": "24", "interState": false, "gstMode": "REGULAR",
  "invoiceNo": "INV-77", "invoiceDate": "2026-11-09", "receivedDate": "2026-11-09", "note": "Morning delivery", "attachmentNote": "",
  "lineDiscountPaise": 5000, "docDiscountPercent": 2.5, "docDiscountPaise": 2375, "taxablePaise": 92625,
  "cgstPaise": 8336, "sgstPaise": 8337, "igstPaise": 0, "cessPaise": 0,
  "freightPaise": 5000, "freightGstPercent": 18, "freightGstPaise": 900, "roundOffPaise": 2, "totalCostPaise": 118200,
  "paymentStatus": "PARTIAL", "paidPaise": 50000, "returnedPaise": 0, "duePaise": 68200, "dueDate": "2026-12-09", "overdue": false,
  "payments": [ { "id": "uuid", "amountPaise": 50000, "mode": "UPI", "reference": "UTR123", "note": "", "moneyAccountId": null,
                  "paidAt": "2026-11-09T05:00:00Z", "createdBy": "uuid" } ],
  "rounding": "PAISE", "createdBy": "uuid", "createdAt": "2026-11-09T05:00:00Z",
  "items": [ {
    "id": "uuid", "productId": "uuid", "name": "Bath Soap", "qty": 10, "freeQty": 2, "unitCostPaise": 10000, "lineCostPaise": 100000,
    "discountPercent": 5, "lineDiscountPaise": 5000, "docDiscountSharePaise": 2375, "taxablePaise": 92625, "gstPercent": 18,
    "cgstPaise": 8336, "sgstPaise": 8337, "igstPaise": 0, "cessPercent": 0, "cessPaise": 0, "lineTotalPaise": 109298, "hsn": "3401",
    "mrpPaise": 15000, "sellingPricePaise": 14000, "batchNo": "B-1", "expiryDate": "2027-01-31", "applyToVariant": true, "returnedQty": 0,
    "landedTotalPaise": 97625, "landedUnitCostPaise": 8135.4167, "landedUnitCostRoundedPaise": 8135 } ],
  "warnings": [] }
```
(The numbers above only illustrate the shape.) `lineCostPaise` is `qty * unitCostPaise` (gross, as before). `docDiscountPercent` is null when an amount was used.

Errors: 400 `VALIDATION_FAILED` (every field above, plus `Idempotency-Key` missing, "a product appears twice", "a product does not exist", "this supplier is switched off"), 409 `IDEMPOTENCY_KEY_REUSED`, 422 `TOTAL_MISMATCH`.

UI notes: generate the key when the form opens and keep it until success. Show the server's figures in a read-only summary; to preview before saving, compute with the rules in 2.2 but treat the answer as the truth.

### GET /api/v1/admin/purchases
Newest first. Query: `page`, `size`, `paymentStatus` (`PENDING|PARTIAL|PAID`, any case), `supplierId`, `supplier` (name contains, any case), `overdue` (true = only overdue), `from`, `to` (invoice dates, inclusive, `from` not after `to`), `rounding`. Response: page of the purchase object above (with `items` and `payments`; `warnings` is `[]`). Lists are loaded with one query for lines and one for payments per page.

### GET /api/v1/admin/purchases/{id}?rounding=
200 purchase, 404 `NOT_FOUND`.

### GET /api/v1/admin/purchases/payables
```json
{ "totalPurchasedPaise": 900000, "totalReturnedPaise": 30000, "totalPaidPaise": 600000, "totalDuePaise": 300000,
  "overduePaise": 120000, "overdueCount": 3,
  "bySupplier": [ { "supplierId": "uuid", "name": "Agro Traders", "totalPurchasedPaise": 500000, "totalPaidPaise": 380000,
                    "totalDuePaise": 120000, "overduePaise": 120000, "overdueCount": 3 } ] }
```
`totalPurchasedPaise` is **net of debit notes** (so purchased minus paid equals due); `totalReturnedPaise` shows the debit notes. `bySupplier` lists suppliers with a non-zero balance, most owed first, at most 200 (a negative `totalDuePaise` means the supplier owes us).

### GET /api/v1/admin/purchases/lookup?productId=
For the form, when a product is picked. 404 for an unknown product.
```json
{ "productId": "uuid", "name": "Bath Soap", "gstPercent": 18, "hsn": "3401", "mrpPaise": 15000, "pricePaise": 14000, "qtyOnHand": 12,
  "lastPurchaseCostPaise": 10000, "lastLandedUnitCostPaise": 8333.3333, "lastSupplierName": "Agro Traders",
  "lastInvoiceDate": "2026-11-09", "lastPurchaseId": "uuid" }
```
The `last*` fields are null when the product was never purchased. `lastPurchaseCostPaise` is the invoice price before tax.

### GET /api/v1/admin/products/{id}/purchase-prices?limit=20&rounding=
The last `limit` (1..100) purchases of the product, newest first, so the admin can compare suppliers over time. A plain array:
```json
[ { "purchaseId": "uuid", "supplierId": "uuid", "supplierName": "Agro Traders", "invoiceNo": "INV-77", "invoiceDate": "2026-11-09",
    "qty": 10, "freeQty": 2, "unitCostPaise": 10000, "landedUnitCostPaise": 8333.3333, "landedUnitCostRoundedPaise": 8333,
    "mrpPaise": 15000, "sellingPricePaise": 14000, "createdAt": "2026-11-09T05:00:00Z" } ]
```
404 for an unknown product.

---

## 6. Payments

### POST /api/v1/admin/purchases/{id}/payments
Header `Idempotency-Key` mandatory. Body: `{ "amountPaise": 4000, "mode": "CASH", "reference": "UTR1", "note": "", "paidAt": "2026-11-09T05:00:00Z", "moneyAccountId": null }`.

| field | rules |
|---|---|
| amountPaise | required, more than 0 and not above the due amount |
| mode | `CASH`, `UPI`, `CARD`, `BANK`, `CHEQUE` (any case) |
| reference | max 80 |
| note | max 200 |
| paidAt | default now; not more than 10 minutes in the future |
| moneyAccountId | uuid, accepted and stored, **not validated yet** |

201 with the purchase as it is now (new `payments` row, `paidPaise`, `duePaise`, `paymentStatus`). A repeat with the same key returns the same answer as the first call and adds no payment.

Errors: 400 `VALIDATION_FAILED` (`amountPaise`, `mode`, `reference`, `note`, `paidAt`, `Idempotency-Key`), 404 `NOT_FOUND`, **409 `ALREADY_PAID`** (nothing is due; `details.duePaise`), **422 `PAYMENT_EXCEEDS_DUE`** (`details.duePaise`, `details.fields.amountPaise` "at most N"), 409 `IDEMPOTENCY_KEY_REUSED`. "Mark paid" = send `amountPaise = duePaise`.

---

## 7. Purchase returns (debit notes)

### POST /api/v1/admin/purchases/{id}/returns
Header `Idempotency-Key` mandatory. Body:
```json
{ "reason": "Damaged in transit", "items": [ { "purchaseItemId": "uuid-of-purchase-line", "qty": 3 } ] }
```
`purchaseItemId` is `items[].id` of the purchase. `reason` 1..200, `items` 1..200, a line at most once, `qty` at least 1.

What happens, in one transaction: the units leave stock (reason `PURCHASE_RETURN`); a debit note number `DN-000001`... is assigned; `returnedPaise` of the purchase grows by the credit (so `duePaise` falls, possibly below 0 = supplier credit); the tax of the returned units is reversed in the input GST report. **Credit of a unit** = its share of the line (taxable value, CGST, SGST, IGST and cess each divided over `qty + freeQty` units, rounded half up); the return that takes the **last** units of the line gets the exact remainder, so a fully returned line credits exactly what it cost. Freight, round-off and the document discount are not returned separately (the discount is already inside the line's taxable value).

Response 201:
```json
{ "id": "uuid", "purchaseId": "uuid", "supplierId": "uuid", "supplierName": "Agro Traders", "invoiceNo": "INV-77", "noteNo": "DN-000001",
  "reason": "Damaged in transit", "taxablePaise": 25000, "cgstPaise": 2250, "sgstPaise": 2250, "igstPaise": 0, "cessPaise": 0, "totalPaise": 29500,
  "createdBy": "uuid", "createdAt": "2026-11-09T06:00:00Z",
  "items": [ { "purchaseItemId": "uuid", "productId": "uuid", "name": "Bath Soap", "qty": 3, "taxablePaise": 25000, "cgstPaise": 2250,
               "sgstPaise": 2250, "igstPaise": 0, "cessPaise": 0, "totalPaise": 29500 } ] }
```
Errors: 400 `VALIDATION_FAILED` (`reason`, `items`, `purchaseItemId` "not a line of this purchase", `qty`, key), 404 `NOT_FOUND`, **422 `RETURN_EXCEEDS_RECEIVED`** (more than `qty + freeQty - returnedQty`; `details.purchaseItemId`), **422 `INSUFFICIENT_STOCK_FOR_RETURN`** (the shop no longer has the units; `details.productId`, `details.onHand`), 409 `IDEMPOTENCY_KEY_REUSED`. Nothing is changed on any error. A repeat with the same key returns the first debit note.

A debit note can be recorded against a fully paid purchase: `duePaise` becomes negative and further payments are refused with `ALREADY_PAID`. The refund from the supplier is not modelled yet (it shows as a negative balance in payables).

### GET /api/v1/admin/purchases/{id}/returns, GET /api/v1/admin/purchase-returns, GET /api/v1/admin/purchase-returns/{id}
Pages of the debit note object above, newest first. `purchase-returns` filters: `supplierId`, `from`, `to` (India dates of the debit note). 404 `NOT_FOUND` for an unknown purchase or note.

---

## 8. Reports

All take India dates `from`/`to` (inclusive). Defaults: `to` = today, `from` = first day of that month; a range may span at most 366 days (400 on `from` otherwise, or when `from` is after `to`).

### GET /api/v1/admin/reports/purchases/register
Filters: `supplierId`, `productId`, `gstRate`, `page`, `size`. One row per purchase line, newest first (by invoice date):
```json
{ "purchaseId": "uuid", "invoiceNo": "INV-77", "invoiceDate": "2026-11-09", "supplierId": "uuid", "supplierName": "Agro Traders", "gstMode": "REGULAR",
  "productId": "uuid", "productName": "Bath Soap", "qty": 10, "freeQty": 2, "unitCostPaise": 10000, "discountPaise": 7000, "taxablePaise": 93000,
  "gstPercent": 18, "cgstPaise": 8370, "sgstPaise": 8370, "igstPaise": 0, "cessPaise": 0, "lineTotalPaise": 109740, "landedUnitCostPaise": 7750.0000 }
```
`discountPaise` = line discount + the line's share of the document discount.

### GET /api/v1/admin/reports/purchases/input-gst
```json
{ "from": "2026-11-01", "to": "2026-11-09",
  "lines": [ { "gstMode": "REGULAR", "gstPercent": 18, "taxablePaise": 93000, "cgstPaise": 8370, "sgstPaise": 8370, "igstPaise": 0, "cessPaise": 0,
               "creditablePaise": 16740, "nonCreditablePaise": 0 } ],
  "taxablePaise": 93000, "cgstPaise": 8370, "sgstPaise": 8370, "igstPaise": 0, "cessPaise": 0, "creditablePaise": 16740, "nonCreditablePaise": 0 }
```
One line per (mode, rate). Purchase lines and freight count by **invoice date**; debit notes are subtracted by the day they were made. `creditablePaise` (tax claimable as input credit) is the tax of `REGULAR` lines; the tax of `COMPOSITION` lines is `nonCreditablePaise`; `NONE` has no tax.

### GET /api/v1/admin/reports/purchases/discounts
```json
{ "from": "2026-11-01", "to": "2026-11-09", "grossPaise": 500000, "lineDiscountPaise": 20000, "docDiscountPaise": 5000,
  "bySupplier": [ { "supplierId": "uuid", "supplierName": "Agro Traders", "purchaseCount": 3, "grossPaise": 300000, "lineDiscountPaise": 12000, "docDiscountPaise": 3000 } ] }
```
Suppliers with a discount only, biggest first (at most 500). `grossPaise` = goods value before discounts.

### GET /api/v1/admin/reports/purchases/ageing?asOf=2026-11-09
Age = `asOf` minus the invoice date, only for purchases with something due.
```json
{ "asOf": "2026-11-09", "days0to30Paise": 100000, "days31to60Paise": 50000, "days61PlusPaise": 20000, "creditPaise": -3000, "totalDuePaise": 167000,
  "bySupplier": [ { "supplierId": "uuid", "supplierName": "Agro Traders", "days0to30Paise": 100000, "days31to60Paise": 50000, "days61PlusPaise": 20000,
                    "creditPaise": -3000, "totalDuePaise": 167000 } ] }
```
`creditPaise` adds up purchases whose due is negative (supplier owes us) and is included in `totalDuePaise`. At most 500 suppliers, most owed first.

---

## 9. Dart-friendly model sketches

```dart
enum GstMode { regular, composition, none }          // JSON: REGULAR, COMPOSITION, NONE
enum PayStatus { pending, partial, paid }            // JSON: PENDING, PARTIAL, PAID
enum PayMode { cash, upi, card, bank, cheque }       // JSON: CASH, UPI, CARD, BANK, CHEQUE

class Supplier {
  final String id, name, phone, gstin, stateCode, address, lastInvoiceNo;
  final int paymentTermsDays, purchaseCount, totalDuePaise;
  final bool active;
  final DateTime? lastPurchaseAt;
}

class PurchaseLine {
  final String id, productId, name, hsn, batchNo;
  final int qty, freeQty, unitCostPaise, gstPercent, taxablePaise, lineTotalPaise, returnedQty;
  final double landedUnitCostPaise, landedUnitCostRoundedPaise;   // 4 decimals: never int
  final int? mrpPaise, sellingPricePaise;
  final DateTime? expiryDate;
}

class Purchase {
  final String id, supplierId, supplierName, invoiceNo, rounding;
  final GstMode gstMode;
  final bool interState, overdue;
  final DateTime invoiceDate, receivedDate;
  final DateTime? dueDate;
  final int taxablePaise, cgstPaise, sgstPaise, igstPaise, cessPaise, freightPaise, roundOffPaise, totalCostPaise;
  final int paidPaise, returnedPaise, duePaise;      // duePaise may be negative
  final PayStatus paymentStatus;
  final List<PurchasePayment> payments;
  final List<PurchaseLine> items;
  final List<String> warnings;
}
```
Use `int` for every `*Paise` money field (Dart ints are 64-bit); only the two `landedUnitCost*` fields are fractional.

---

## 10. What changes for existing clients

- `POST /admin/purchases` with the old body still works. Differences: (1) `totalCostPaise` now includes GST for products that have a GST rate (the old number is `taxablePaise`); send `"gstMode": "NONE"` to get the old behaviour; (2) a purchase now also returns the new fields. A missing `supplierName` with no `supplierId` is still `400` on `supplierName`.
- The supplier text is matched to a supplier (case-insensitive, trimmed); a switched-off supplier is refused. The duplicate invoice warning works as before.
- Purchases recorded before this release are PAID (see 2.5). `payments` shows one `LEGACY` row for them.
- New stock movement reason: `PURCHASE_RETURN` in `GET /admin/stock-movements`.
- New audit actions: `PURCHASE`, `PURCHASE_PAYMENT`, `PURCHASE_RETURN`, `PURCHASE_PRICE_APPLY`, `SUPPLIER_CREATE`, `SUPPLIER_UPDATE`, `SUPPLIER_ACTIVATE`, `SUPPLIER_DEACTIVATE`.
- New config key `purchase.gstMode` (REGULAR, COMPOSITION, NONE; default REGULAR) in `GET/PUT /admin/config`, group "GST and bills".

## 11. Not built yet (so the app does not wait for it)

Stock lots and per-lot cost/price (the line stores the landed cost for it), file upload of the invoice (only the `attachmentNote` text), validation of `moneyAccountId`, a supplier statement/ledger screen with opening balances, supplier refunds against a negative balance, purchase orders, editing or cancelling a recorded purchase.

## 12. Implementation checklist

1. Supplier list/autocomplete and the add/edit screen; show `totalDuePaise`.
2. Purchase form: `lookup` on product pick; free qty, discounts, GST, MRP/price with the "apply to product" switch; GST mode picker (default REGULAR); payment block (status, amount, mode, reference, due date); read-only totals from the answer; generate the idempotency key on open.
3. Handle `warnings` (duplicate invoice), `TOTAL_MISMATCH`, field errors in `details.fields`.
4. Purchase list with filters and the payables header; overdue badge from `overdue`.
5. Detail: lines with exact and rounded landed cost (rounding switch), payments, Add payment (`ALREADY_PAID`, `PAYMENT_EXCEEDS_DUE`), Return goods (`RETURN_EXCEEDS_RECEIVED`, `INSUFFICIENT_STOCK_FOR_RETURN`).
6. Show a negative `duePaise` as "supplier credit".
7. Reports screens; product screen shows `lastPurchaseCostPaise` and the price history.
8. Parse `landedUnitCost*` as `double`, everything else money as `int`.

---

## Addendum 1: supplier statement, supplier credit and refunds, invoice file, editing a recorded purchase

This addendum builds four items of section 11. Same conventions as above: admin only, money in integer paise, error envelope `{ code, message, traceId, details: { fields } }`, lists are the standard bounded page. Migration `V106`.

### A1.1 Supplier statement (ledger)

`GET /admin/suppliers/{id}/statement?from=2026-04-01&to=2026-04-30&page=0&size=20`

`from` and `to` are optional inclusive India dates (`size` 1..50). Without them the statement covers everything and its closing balance is the supplier's current balance. `400` when `from` is after `to`, `404` for an unknown supplier.

```json
{
  "supplierId": "...", "supplierName": "Agro Traders", "from": "2026-04-01", "to": "2026-04-30",
  "openingBalancePaise": -40000,
  "closingBalancePaise": 10000,
  "rows": { "items": [
    { "date": "2026-04-02", "at": "2026-04-02T06:10:00Z", "kind": "PURCHASE", "refId": "<purchaseId>", "purchaseId": "<purchaseId>",
      "invoiceNo": "INV-77", "mode": "", "reference": "INV-77", "amountPaise": 50000, "balancePaise": 10000 },
    { "kind": "PAYMENT", "mode": "CREDIT_NOTE", "reference": "DN-000012", "amountPaise": -20000, "balancePaise": -10000, "...": "..." },
    { "kind": "CREDIT_USED", "amountPaise": 20000, "balancePaise": 10000, "...": "..." }
  ], "page": 0, "size": 20, "total": 3, "totalExact": true, "hasMore": false }
}
```

- A balance is what the shop owes the supplier; negative means the supplier owes the shop. `amountPaise` is signed: plus raises what the shop owes, minus lowers it. `balancePaise` is the running balance after that row (starts at `openingBalancePaise`, exact on every page). `closingBalancePaise` = opening + the sum of all rows in the range, not only the page.
- `kind`: `PURCHASE` (+ the document total, dated by invoice date), `PAYMENT` (- any payment, also one made with credit), `DEBIT_NOTE` (- a purchase return), `SUPPLIER_REFUND` (+ money the supplier paid back), `CREDIT_USED` (+ the other half of a payment made with credit: see A1.2; the two rows cancel out). Rows are ordered by date, then in that order of kinds, then by time.
- With no `to`, `closingBalancePaise` equals `balancePaise` on `GET /admin/suppliers/{id}`, the supplier's `totalDuePaise` and its line in `GET /admin/purchases/payables`.
- `GET /admin/suppliers/{id}` and the list now also carry `balancePaise` (same number as `totalDuePaise`) and `creditPaise` (the part of a negative balance that sits on purchases and can be used or refunded; always `>= 0`).

### A1.2 Supplier credit: use it on another purchase, or take it back as money

Credit appears when a debit note (return) is bigger than what is still unpaid on its purchase: that purchase gets a negative `duePaise`. `creditPaise` of the supplier is the sum of those negatives. It can be used in two ways, both cannot exceed the credit.

**(a) Pay another purchase of the same supplier with the credit.** Same endpoint as payments, `POST /admin/purchases/{id}/payments`, with `Idempotency-Key`:

```json
{ "amountPaise": 30000, "mode": "CREDIT_NOTE", "debitNoteId": "<purchase return id>", "note": "adjusted", "paidAt": null }
```

Answer: `201` and the paid purchase (the payment row has `mode: "CREDIT_NOTE"` and `reference` = the debit note number). The credit is taken from the purchase the debit note belongs to, so the supplier's balance does not change, only which purchase carries it. Rules and errors: `debitNoteId` is required and must exist and belong to the same supplier (`400` on `debitNoteId`; also `400` when it is a return on the purchase being paid); `409 ALREADY_PAID` when nothing is due; `422 PAYMENT_EXCEEDS_DUE` above the due amount; `422 CREDIT_EXCEEDS_AVAILABLE` above the credit left on that debit note and its purchase (`details.availablePaise`, `details.fields.amountPaise`). Partial use is fine: use 30,000 of 40,000 now, the rest later. Two requests that use the same credit at the same time cannot both succeed: the second gets `CREDIT_EXCEEDS_AVAILABLE`. Show the debit notes with `GET /admin/purchases/{id}/returns`; offer "pay with credit" only when `creditPaise > 0`. `moneyAccountId` is not used by this mode.

**(b) Refund: the supplier paid the credit back in cash or bank.** `POST /admin/suppliers/{id}/refunds` with `Idempotency-Key`:

```json
{ "amountPaise": 25000, "mode": "BANK", "reference": "UTR123", "receivedAt": "2026-04-20T09:30:00Z", "moneyAccountId": "<uuid or null>" }
```

`mode` is CASH, UPI, CARD, BANK or CHEQUE; `receivedAt` defaults to now; `moneyAccountId` is stored without validation, like payments. Answer `201`:

```json
{ "id": "...", "supplierId": "...", "amountPaise": 25000, "mode": "BANK", "reference": "UTR123", "moneyAccountId": null,
  "receivedAt": "...", "createdBy": "...", "createdAt": "...", "creditPaise": 15000, "balancePaise": -15000 }
```

`creditPaise` and `balancePaise` are the supplier's figures after the refund. In `GET /admin/purchases/payables` the paid figures are net of refunds (a refund lowers `totalPaidPaise`), so purchased minus paid is still the due amount. Errors: `422 REFUND_EXCEEDS_CREDIT` (`details.creditPaise`, `details.fields.amountPaise`), `400` for a bad mode or a future `receivedAt`, `404` unknown supplier, `409 IDEMPOTENCY_KEY_REUSED`. A repeat with the same key and body returns the first refund and changes nothing. The refund is taken from the supplier's credit purchases, oldest invoice first, so their `duePaise` moves up towards 0. `GET /admin/suppliers/{id}/refunds` lists the latest 50 (same shape, newest first). The statement shows a refund as `SUPPLIER_REFUND` with a plus amount.

Example: bought 100,000 and paid it; returned goods worth 40,000, so credit 40,000 and balance -40,000. A new purchase of 50,000: balance +10,000, credit still 40,000. Pay it with 30,000 credit: new purchase due 20,000, old purchase due -10,000, balance still +10,000. Or refund 10,000 in cash: old purchase due 0, balance +20,000.

### A1.3 Invoice file

- `POST /admin/purchases/{id}/attachment`, `multipart/form-data`, part `file`: PDF, JPEG, PNG or WebP, at most 5 MB. The type is decided from the file's first bytes, not from its name or sent type. Answer `200` and the purchase. A second upload replaces the first (the old file is deleted). No Idempotency-Key is needed: repeating it is harmless.
- `DELETE /admin/purchases/{id}/attachment` removes it (`200` and the purchase, also when there was none).
- `GET /admin/purchases/{id}/attachment` streams the file with its real `Content-Type` and `Content-Disposition: inline`; `404` when there is none. It needs the admin token, so a plain `<img src>` or link does not work: fetch the bytes with the `Authorization` header and show them (Flutter: `Image.memory`, or a PDF viewer on the bytes).
- The purchase view has `hasAttachment` (bool) and `attachmentUrl` (`/api/v1/admin/purchases/{id}/attachment` or `null`). `attachmentNote` stays as a free text field.
- Errors (`400`, `details.fields.file`): `UNSUPPORTED_FILE` (not one of the four types), `FILE_TOO_LARGE` (over 5 MB), `VALIDATION_FAILED` (empty file or not sent as multipart), `404` unknown purchase.
- The files are in a private folder (`FILES_DIR`, default `./data/files`, a separate volume in `docker-compose.prod.yml`), never under `/img`, with random names. Back that volume up with the database.

### A1.4 Edit a recorded purchase (non-stock fields only)

`PATCH /admin/purchases/{id}` with a JSON body of only the fields to change:

```json
{ "invoiceNo": "INV-78", "invoiceDate": "2026-04-02", "dueDate": null, "note": "checked", "attachmentNote": "folder 4",
  "supplierGstin": "24ABCDE1234F1Z5", "supplierStateCode": "24" }
```

- Left out = unchanged. Text fields: an empty string clears. `dueDate: null` clears the due date. `invoiceDate` cannot be cleared or be in the future; `dueDate` cannot be before the invoice date. `supplierGstin` must be a valid GSTIN (or empty); it fixes the text on this document only, not the supplier master, and when `supplierStateCode` is not sent the state follows the GSTIN. A state sent must match the GSTIN. Changing the state text does not recompute the tax (`interState` and the amounts stay as recorded).
- Anything else (`items`, `qty`, `unitCostPaise`, totals, tax, discount, `supplierId`, `gstMode`, payments...) is refused with `400` on that field; the message says to use a purchase return and a new purchase. That is the rule for a wrong quantity or cost: **return the purchase (debit note) and record a new one**, because those change stock, lots and the money owed.
- Answer `200` and the purchase, with `warnings` filled when the new invoice number was already recorded for this supplier (same check as when recording). Sending the same values again changes nothing. One audit entry `PURCHASE_EDIT` lists each changed field as `field: 'before' -> 'after'`. Moving `invoiceDate` moves the purchase between report periods (purchase register, input GST).

### A1.5 Dart sketches

```dart
class StatementRow {
  final DateTime date; final String kind; final String? purchaseId; final String invoiceNo;
  final String mode, reference; final int amountPaise, balancePaise;
  StatementRow.fromJson(Map<String, dynamic> j)
      : date = DateTime.parse(j['date']), kind = j['kind'], purchaseId = j['purchaseId'], invoiceNo = j['invoiceNo'] ?? '',
        mode = j['mode'] ?? '', reference = j['reference'] ?? '', amountPaise = j['amountPaise'], balancePaise = j['balancePaise'];
}

Future<Map<String, dynamic>> payWithCredit(Dio dio, String purchaseId, String noteId, int paise) async {
  final r = await dio.post('/admin/purchases/$purchaseId/payments',
      data: {'amountPaise': paise, 'mode': 'CREDIT_NOTE', 'debitNoteId': noteId},
      options: Options(headers: {'Idempotency-Key': const Uuid().v4()}));
  return r.data; // 422 CREDIT_EXCEEDS_AVAILABLE: details.availablePaise
}

Future<void> refund(Dio dio, String supplierId, int paise) => dio.post('/admin/suppliers/$supplierId/refunds',
    data: {'amountPaise': paise, 'mode': 'CASH'}, options: Options(headers: {'Idempotency-Key': const Uuid().v4()}));

Future<void> attach(Dio dio, String purchaseId, Uint8List bytes, String name) => dio.post('/admin/purchases/$purchaseId/attachment',
    data: FormData.fromMap({'file': MultipartFile.fromBytes(bytes, filename: name)}));

Future<Uint8List> invoiceBytes(Dio dio, String purchaseId) async {
  final r = await dio.get<List<int>>('/admin/purchases/$purchaseId/attachment', options: Options(responseType: ResponseType.bytes));
  return Uint8List.fromList(r.data!);
}

Future<Map<String, dynamic>> editPurchase(Dio dio, String id, Map<String, dynamic> onlyChanged) async =>
    (await dio.patch('/admin/purchases/$id', data: onlyChanged)).data;
```

New audit actions: `SUPPLIER_REFUND`, `PURCHASE_CREDIT_USE`, `PURCHASE_ATTACHMENT`, `PURCHASE_EDIT`. New payment mode value in `payments[].mode`: `CREDIT_NOTE` (not money: do not count it as cash out in a cash report). Still not built: stock lots, purchase orders, cancelling a purchase, validating `moneyAccountId`.
