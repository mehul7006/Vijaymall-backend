# 50. Backend response to the Flutter team's gap review (2026-11-09)

Each ID is the ID in your review. **Status** is one of: **Fixed** (new in this release), **Already fixed** (in the earlier release your review predates), **Not a bug** (explanation), **Doc fixed**, **Decision** (we chose a behaviour). Endpoint details for everything marked Fixed are in the guide files named in the last column. All money is paise.

## Priority 1

| ID | Status | What changed / what to do | Details |
|---|---|---|---|
| B20-01 | **Fixed** | `POST /admin/orders/{id}/status` with `CANCELLED` now stores the note as the order's `cancelReason` (raw text) and records who cancelled. `/reject` still works and is the recommended call. | file 20 addendum |
| B20-02 | **Fixed** | Order views have `cancelledBy` (`CUSTOMER` / `SHOP` / `SYSTEM`) and `cancelCode` (`CUSTOMER_CANCELLED`, `REJECTED_BY_SHOP`, `CANCELLED_BY_SHOP`, `PAYMENT_TIMEOUT`, `PAYMENT_FAILED`). `cancelReason` is raw text with no "Rejected by shop:" prefix: translate from `cancelCode` yourself. | file 20 addendum |
| B30-01 | **Fixed** | Payment mode is trimmed and upper-cased; null no longer 500s. The 400 says `payment mode must be CASH, UPI or CARD (got "xyz")` and reports the field as `details.fields['payments[i].mode']` (`'refunds[i].mode'` for refunds). Applies to sales, return refunds, credit repayments and offline sync lines. | file 30 addendum |
| B10-01 | **Fixed** | `POST /admin/products/{id}/image?variants=one|missing|all` (default `one`). Customer `Variant.imageUrl` falls back to a sibling's image. | file 10 addendum |
| B10-02 | **Fixed** | `DELETE /admin/products/{id}`: hard delete if never used, otherwise deactivated; always `200 {deleted, deactivated, reason}`. | file 10 addendum |
| B10-04 | **Already fixed** + **Fixed** | An omitted/null `name` on `PUT /me` keeps the saved name (earlier release). A blank non-null name is now `400 fields.name`. | file 10 |
| B10-13 | **Already fixed** + **Fixed** | `PUT /admin/products/{id}`: omitted `qtyOnHand`/`lowThreshold` keep their values (earlier release). New `PATCH /admin/products/{id}` changes only the keys you send (not stock: use `POST /products/{id}/stock`). | file 10 addendum |
| B10-15 | **Fixed** | A blocked customer is rejected within 10 seconds (blocking through the admin endpoint takes effect on the next call). Staff are rejected immediately, as before. | file 00 / 10 addendum |
| B30-04 | **Fixed** | `POST /pos/sales/{id}/returns` accepts `refundMode` (`CASH`/`UPI`/`CARD`). Default when nothing is sent: the bill's mode if it was paid in exactly one of CASH/UPI/CARD, else cash. `refundMode` together with `refunds[]` is 400. | file 30 addendum |
| B20-04 | **Fixed** | `GET /admin/orders?q=` matches order number **and** the customer's name, mobile and e-mail (contains, case-insensitive). | file 20 addendum |
| B20-03 | **Already fixed** | Orders waiting for payment carry `paymentExpiresAt`. | file 20 addendum |
| B00-02 | **Fixed** | CORS allows `If-None-Match`; exposes `ETag`, `Retry-After`, `X-Trace-Id`. | file 00 |

## Priority 2

| ID | Status | What changed / what to do | Details |
|---|---|---|---|
| B00-01 / B10-03 | **Fixed** | `DELETE /me/devices/{fcmToken}` (204, idempotent, own token only) and an optional `fcmToken` in the `POST /auth/logout` body. **FCM go-live:** push is real, not a log stub: the backend sends through Firebase HTTP v1 as soon as the owner saves the Firebase service-account JSON on the admin config screen (Push group) and a device token is registered. | file 10 addendum |
| B10-05 | **Fixed** | Optional `phone` (blank or 10 digits) on addresses (`400 fields.phone` otherwise), returned in lists and copied into the order's address snapshot. `PUT` replaces the whole address, so omit `phone` = cleared. | file 10 addendum |
| B10-06 | **Fixed** | `GET /admin/categories` (all, incl. inactive), `DELETE /admin/categories/{id}` (409 `CATEGORY_IN_USE`), `POST /admin/categories/{id}/image`. | file 10 addendum |
| B10-17 | **Fixed** | `GET /admin/customers/{id}/wallet-ledger` and `GET /admin/customers/{id}/orders` (paged, newest first). Ledger rows have no "remaining" field. | file 10 addendum |
| B10-16 | **Fixed** | `GET /hot-sale?page&size` pages the products part (default 20, max 50), ordered by soonest `hotEnd` then name; response adds `page`, `size`, `total`. | file 10 addendum |
| B20-05 | **Fixed** | Order line items carry `imageUrl` (snapshot taken when the order was placed; existing orders backfilled from the product). | file 20 addendum |
| B30-03 | **Fixed** | `/pos/shop` has `roundOffMode` (`NONE`/`NEAREST_RUPEE`). `POST /pos/quote` accepts the same `manualDiscount` as a sale (preview only: no PIN needed to preview, PIN enforced on the sale) and returns `manualDiscountPaise`. | file 30 addendum |
| B30-06 | **Fixed** | `GET /pos/sales/{id}/returns/{returnId}/receipt?width=32|48` and `GET /pos/credit/payments/{id}/receipt?width=32|48`, same `{width, text}` shape as the sale receipt. | file 30 addendum |
| B30-08 | **Fixed** | `GET /pos/products?q=` also matches a barcode or PLU exactly (exact matches first); optional `updatedSince` (ISO instant) for incremental refresh; products carry `packSize`, `unit`, `variantName`, `displayName`. Limitation: a barcode that was only *deleted* is not picked up by `updatedSince`. | file 30 addendum |
| B10-14 | **Fixed** | `ETag` + `304` on `GET /categories`, `/product-groups`, `/units`, `/hot-sale` (exact paths; send `If-None-Match`). | file 00 |

## Priority 3

| ID | Status | What changed / what to do | Details |
|---|---|---|---|
| B00-03 | **Fixed** | Every 429 carries a `Retry-After` header (seconds) equal to `details.retryAfterSeconds` (now documented). | file 00 |
| B00-04 | **Fixed** | Upload errors are 400 with `details.fields.file` and codes `UNSUPPORTED_IMAGE` / `FILE_TOO_LARGE` ("must be an image (JPEG, PNG or WebP)", "must be 5 MB or smaller", "image is too large (max 25000000 pixels)"). A non-multipart upload is `400 VALIDATION_FAILED` (it used to be a 500). | file 00 |
| B10-09 | **Fixed** | Reason-specific OTP messages (wrong / expired / too many attempts / "Try again in N seconds"); codes and `details.reason` unchanged. | file 10 |
| B10-10 | **Fixed** | Admin coin adjustment validation errors appear under both `fields.delta` and `fields.coins`. | file 10 |
| B10-11 | **Not a bug** | An omitted `active` on `PUT /admin/product-groups/{id}` keeps the current value on every variant (the server only writes it when sent). Documented. | file 10 |

## Documentation corrections

| ID | Status | Note |
|---|---|---|
| B10-07 | **Doc fixed** | A negative `minOrderPaise` on a coin rule is `400` (never 500). |
| B10-08 | **Doc fixed** | `GET /wallet/ledger` is newest first (contract). |
| B10-12 | **Fixed** | Request `Instant` fields accept ISO with `Z`/offset, a zone-less date-time (read as India time) and a plain date `2026-11-09` (start of that day, India time). Zone-less values were rejected before; now accepted. Responses are always UTC with `Z`. |
| B20-06 | **Doc fixed** | SSE `catalog` event `area` can also be `FLASH`. |
| B30-02 | **Doc fixed** | Offline sync section: `rung` snapshot, `exceptions[]`, coupons/coins/credit refused offline, admin offline-exception endpoints (see file 30 addendum). |
| B30-05 | **Doc fixed** | Offline uploads never get `SHIFT_NOT_OPEN`. |
| B30-07 | **Doc fixed** | Flash-sale prices at the counter documented (file 30 addendum, file 40). |

## Your confirmation questions

1. **`paymentMethod` on `POST /orders`**: only `COD` and `ONLINE`. Pay-at-store is `COD` with `PICKUP`. No other values are planned.
2. **Which build is deployed?** The till error text "payment mode must be cash, card, upi" is **not** in this source (ours says "CASH, UPI or CARD"), so your till was talking to an older or different build. Please check the deployed commit; this release also adds `variantName`, `packSize`, `unit` and `displayName` to `/pos/products`, which your app already reads.
3. **Zone-less timestamps**: they were rejected with 400 before; now accepted as India time (see B10-12). Keep sending `Z`/offset where you can.
4. **Razorpay native mobile**: no backend change needed. The order response carries the `paymentSession`; the app opens checkout with it and then waits for the server to confirm (poll `GET /orders/{id}` until `paymentStatus` changes). There is no endpoint to send the Razorpay signature: the provider's webhook confirms the payment server-side.

## Two more things that affect the app

- **Coupon "save 0"**: see the coupon addendum in file 20: the saving is only ever `couponDiscountPaise` in the quote; a coupon that cannot be used makes the quote answer `422 COUPON_INVALID` with a plain message (and `shortfallPaise` for a minimum order), and `POST /cart/coupons` previews what every coupon would save on the cart.
- **Lists and totals**: paged responses now also carry `totalExact` and `hasMore`. `total` is always exact up to 10,000 rows; beyond that `total` is 10000 and `totalExact` is `false` (show "10,000+"); `hasMore` is always exact, so page until `hasMore` is false.

## Round 2 (2026-11-09 evening)

The full explanation, before/after examples and what changes for the app is in [51-backend-response-round-2.md](51-backend-response-round-2.md). The table below is the short form.

| ID | Status | What changed / what to do | Details |
|---|---|---|---|
| E1-E11 | **Fixed (error messages)** | Error messages and validation fields reworked; see the error section of file 00 and file 20. | file 00, file 20 |
| R2-03 | **Documented** | `PUT /admin/products/{id}` is a full replacement (decision). Omitted: `hotStart`, `hotEnd`, `description`, `packSize` are cleared; `active`/`earnsCoins`/`coinRedeemable` reset to true, `hotSale` to false; `qtyOnHand`, `lowThreshold`, `unit`, `productGroupId`, `variantName`, `variantOrder` are kept. Use `PATCH` for partial edits. Behaviour unchanged. | file 10 |
| R2-04 | **Fixed** | `PATCH /admin/products/{id}` publishes the typed schema `ProductPatchBody` in Swagger (keys, types, descriptions). Semantics unchanged: absent key keeps, explicit `null` clears `hotStart`, `hotEnd`, `description`. | file 10 addendum |
| R2-05 | **Fixed** | `POST` and `PUT /admin/categories` return the full category (`id`, `nameEn`, `nameGu`, `imageUrl`, `sortOrder`, `active`), same shape as the admin list item. | file 10 |
| R2-06 | **Fixed (error messages)** | See the error section. | file 00 |
| R2-07 | **Fixed (error messages)** | See the error section. | file 00 |
| R2-08 | **Fixed (error messages)** | See the error section. | file 00 |
| R2-10 | **Fixed** | Duplicate Swagger schema names (`View`, `Row`, `Report`, `ReviewBody`, `Result`, `Status`, `Change`, `ItemInput`, `ItemView`, `QuoteBody`, `ReturnBody`, `ActiveBody`) now have unique names, for example `OrderView`, `ProductReviewBody`, `DefectiveReviewBody`, `CounterControlsReport`. A test fails if two API models share a name. Wire JSON unchanged. | file 10 addendum |
| R2-11 | **Fixed** | `GET /pos/products?updatedSince=` (and the `PATCH` hot window) accept `Z`/offset, zone-less (India time) and a plain date, using the same parser as JSON bodies. | file 00, 10, 30 |
| R2-12 | **Fixed** | `GET /hot-sale` returns `totalExact` and `hasMore` next to `page`, `size`, `total`. | file 10 |
| R2-14 | **Documented** | `GET /admin/stock/low` stays a plain array. Cap 200 (`limit` 1..200, default 50), sorted by `qty` ascending, no paging. | file 30 |
| R2-15 | **Fixed (error messages)** | See the error section. | file 00 |
| D1 | **Documented** | POS payment-mode error text depends on context: bills `payment mode must be CASH, UPI or CARD, or CREDIT on a bill (got "x")`; refunds and repayments `payment mode must be CASH, UPI or CARD (got "x")`. | file 30 |
| D2 | **Documented** | Request date-times accept `Z`/offset, zone-less (India time) and a plain date; formerly stricter: `updatedSince`, PATCH hot window. | file 00, 10 |
| D3 | **Documented** | Customer order view `OrderViews.View` is the schema `OrderView`. | file 10 addendum |
| Docs (file 30) | **Documented** | Added complete text for `roundOffMode`, `refundMode`, the return (credit note) and credit repayment receipts with real sample text, `updatedSince`, and the `/pos/products` fields `packSize`, `unit`, `variantName`, `displayName`. | file 30 addendum |
| Test env | **Documented** | The seeded dev admin is `ADMIN_EMAIL` (default `admin@vijaysupermall.local`; `admin@mall.com` on instances that set it); dev code `12345`. | file 00 section 6, deploy/README.md |

