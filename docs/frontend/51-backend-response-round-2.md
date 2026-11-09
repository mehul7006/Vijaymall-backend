# 51. Backend response to the Flutter team's consolidated review, round 2 (2026-11-09)

This answers your document "Frontend Review, Consolidated for the Backend Team". Thank you for the live validation: it found one real contract bug (E1) and a pattern (bare 400s) that we fixed at the root. The per-item table is also in file 50 (section "Round 2"); this file explains the reasoning, shows before/after, and lists what changes for the app.

All money is paise. Times are UTC with `Z` in responses.

## 1. Where we stand

| Your item | Our verdict | Result |
|---|---|---|
| E1 `expectedTotalPaise` missing gives a bare 400 | **Our bug.** The guide said optional; the code made it mandatory. | Fixed: optional. When absent the server skips the price check (`PRICE_CHANGED`). When present, behaviour is unchanged. |
| E2-E6, E9, E10 enum / type / UUID / header mistakes | **Our gap.** One handler turned four different exceptions into the same answer. | Fixed: each names the field and the expected values (section 2). |
| E7, E8 `{}` bodies on coupon and product create | **Our gap.** | Fixed: all missing required fields are reported together; numeric limits say `must be 0 or more`. |
| E3 malformed JSON | Our gap | `message` is `The request body is not valid JSON`. |
| E11 ONLINE order 400 (traceId d33057a5) | Same cause as E1 | Resolved by E1. Please retest. |
| paymentMethod `CASH` wording | Our gap | `must be COD or ONLINE (got "CASH")`. |
| R2-15 dev OTP limits | **Our gap** (dev testing only) | Dev profile now allows 1000 codes per e-mail and 10000 per address per hour. Production keeps 5 and 20. Both are environment-overridable. |
| R2-07 pincode `12` accepted | **Our gap** | `400 fields.pincode = "must be 6 digits"`. |
| R2-06 upload over 5 MB | Our gap | `message` now says `File too large: must be 5 MB or smaller`; `fields.file` and code `FILE_TOO_LARGE` unchanged. |
| R2-05 category POST/PUT omit `active` | Our gap | Both return the full category (same shape as the admin list item). |
| R2-04 PATCH schema is a free object | Our gap | Typed `ProductPatchBody` in Swagger. Semantics unchanged: an absent key keeps the value, an explicit `null` clears `hotStart`, `hotEnd`, `description`. |
| R2-10 Swagger schema-name collisions | Our gap | 12 clashing names now unique (`OrderView`, `ProductReviewBody`, `DefectiveReviewBody`, `CounterControlsReport` ...). A test fails if two API models share a name. The JSON on the wire did not change. |
| R2-11 `updatedSince` too strict | Our gap | Accepts `Z`/offset, zone-less (India time) and a plain date, like JSON bodies. |
| R2-12 hot-sale lacks `totalExact`/`hasMore` | Our gap | Added. |
| R2-08 `coinMode: PERCENT` without `percent` | **Clarification plus one fix.** The default is 100 (all coins). | Documented. We also found the saved basket stored 0 instead of 100; it now defaults to 100 like the quote and the order. |
| R2-03 PUT product clears `hotEnd` | **Clarification (by design).** PUT replaces the whole record. | Documented per field (file 10): omitted `hotStart`, `hotEnd`, `description`, `packSize` are cleared; `active`, `earnsCoins`, `coinRedeemable` reset to true and `hotSale` to false; `qtyOnHand`, `lowThreshold`, `unit`, `productGroupId`, `variantName`, `variantOrder` are kept. For partial edits use `PATCH`, which you already do. |
| R2-14 low stock is a plain array | **Clarification.** We keep the array because the app already reads it. | Documented: cap 200 (`limit` 1..200, default 50), sorted by `qty` ascending, no paging. Update (round 3): inactive products are now filtered out before the cap, so every row can still be sold. |
| R2-13, D1, D2, D3 documentation | Our gap | File 30 now has `refundMode`, `roundOffMode`, the return and credit-repayment receipts with real sample text, `updatedSince` and the `/pos/products` fields. Both POS payment-mode error texts are listed. Date formats are in files 00 and 10. |
| Test environment (admin e-mail) | **Configuration, not a bug.** | The seeded dev admin is whatever `ADMIN_EMAIL` is (default `admin@vijaysupermall.local`; your instance sets `admin@mall.com`). Documented in file 00 section 6 and `deploy/README.md`. |

Nothing in your report was rejected.

## 2. The error messages, before and after

Code stays `VALIDATION_FAILED`, status 400. Switch on `code` and `details`; `message` is the English fallback.

| Request | Before | Now |
|---|---|---|
| `POST /orders` with `"fulfilment":"XYZ"` | `Bad request`, `details: {}` | `details.fields.fulfilment = "must be DELIVERY or PICKUP"` |
| `POST /orders` with `"coinMode":"x"` | same | `details.fields.coinMode = "must be NONE, ALL or PERCENT"` |
| `items[0].qty: "two"` | same | `details.fields["items[0].qty"] = "must be a whole number"` |
| `items[0].productId: "abc"` | same | `details.fields["items[0].productId"] = "must be a valid id"` |
| No `Idempotency-Key` header | same | `message = "Missing header Idempotency-Key"`, `fields["Idempotency-Key"] = "is required"` |
| Broken JSON | same | `message = "The request body is not valid JSON"` |
| `POST /admin/coupons` `{}` | partial | `fields.code`, `fields.kind`, `fields.value` = `is required` |
| `POST /admin/products` `{}` / `pricePaise: -5` | partial | `fields.nameEn = "is required"`, `fields.pricePaise = "must be 0 or more"` |
| `POST /admin/orders/{id}/status` `"status":"NOPE"` | `Bad request` | `fields.status = "must be one of PLACED, CONFIRMED, ..."` |
| `GET /admin/orders/not-a-uuid` | `Bad request` | `fields.id = "must be a valid id"` |

The full table is in file 00 ("Request mistakes: what you get"). One limit to know: when a body has two wrong values of the JSON kind (for example a bad `fulfilment` and a bad `coinMode`), the server reports the first it meets; validation failures (missing, too small, too long) are reported all together. Fix, resend, and the second one shows up.

For translation you asked for stable machine-readable details: `code`, the field keys, and values such as `details.shortfallPaise` (coupons) and `details.retryAfterSeconds` (429/503) are stable. Treat `message` text as English-only fallback.

## 3. What changes for the app (please check these)

1. `expectedTotalPaise` may be omitted; keep sending it so the customer is protected from a changed price.
2. The saved basket's percent default is now 100, not 0. If you stored `coinMode: PERCENT` without `percent` in a basket before, the next read shows 100.
3. Pincodes must be exactly 6 digits on create and update.
4. Category create/update responses now include `active`.
5. `GET /hot-sale` has `totalExact` and `hasMore`; page until `hasMore` is `false`.
6. `GET /pos/holds` returns at most the newest **100** held baskets. Discard baskets you no longer need; older ones are not listed (they can still be fetched or deleted by id).
7. A full database connection pool answers **503 `SERVICE_BUSY`** with a `Retry-After` header and `details.retryAfterSeconds` (about 2 s). It is not the same as the 503 for an unconfigured SMS or payment provider (`SMS_NOT_CONFIGURED` and similar). Retry after the delay; do not treat it as a feature being off.
8. Paged lists carry `totalExact` and `hasMore`. `total` is exact up to 10,000; above that it is `10000` with `totalExact: false` (show "10,000+"). `hasMore` is always exact.
9. Plain dates in request Instant fields mean the **start** of that day in India time. For "through the end of the day" (for example `hotEnd`) send a full timestamp.

## 4. Answers to what you could not verify

| Item | What to expect and how to check |
|---|---|
| B20-03 `paymentExpiresAt` | Present only while an online order is `PAYMENT_PENDING`; `null` otherwise. Place an ONLINE order and read `GET /orders/{id}`. |
| B20-05 order line `imageUrl` | Filled from the product's image; `null` when the product has none. Present on the order lines of the customer and admin order views (see the file 20 addendum). |
| B10-15 blocked customer | Blocking through the admin screen (`PATCH /admin/customers/{id}` with `active:false`) takes effect at once on the same server instance; a change made directly in the database can take up to 10 seconds. A blocked customer's calls answer **401 `UNAUTHENTICATED`** (treated as signed out), and unblocking restores access without a new sign-in. Covered by an integration test. |
| B10-17 customer ledger / orders (admin) | `GET /admin/customers/{id}/wallet-ledger` and `GET /admin/customers/{id}/orders`; the orders list now keeps `cancelledBy` and `cancelCode` for cancelled orders (previously `null`). |
| B00-01 / B10-03 device removal | Both are documented in the file 10 addendum (device removal endpoint, `fcmToken` on logout); please test against those exact paths. |
| B10-09 OTP reasons | Wrong / expired / too many attempts / `Try again in N seconds`; codes and `details.reason` unchanged. The rate-limit wording you saw is one of them. |
| `roundOffMode: NEAREST_RUPEE` | Set it in admin shop settings on a dev server and quote a counter bill with paise; the behaviour and examples are in the file 30 addendum. |
| CORS exposure of `ETag` / `Retry-After` | Confirm in the web build as you planned; the server exposes both. |

## 5. Backend findings that also affect you

Our own code review (reading only, then fixing) found things worth sharing:

- A product's cached rating or availability could stay stale for minutes in the shop list; we are making every change that can alter a listed product clear the cache immediately. No app change needed.
- Admin order search by customer name, mobile or e-mail used only the 200 newest matching customers, so an old customer's orders could be missed. The limit is removed: every matching customer now counts (measured at about 30 ms on 257,000 orders). Released.
- The coupon picker (`POST /cart/coupons`) prices the basket once per offered coupon; it now tries at most 20 coupons (those open to every category with the lowest minimum order first), and one failing coupon no longer breaks the list. Released.
- The new admin push-notification module (broadcasts and segments) has its own guide: file 60.

All three items above are now in the code; the stale-cache fix needs no app change.

## 6. Your leftover test data

The POS sale `VSM/2026-27/000001`, credit note `VSM-CN/2026-27/000001`, orders `VSM-1022` and `VSM-1023` and the two test customers cannot be deleted through the API by design (financial records). Tell us if you want them removed from the **dev** database directly; we will not do that on any shared or production instance.

## 7. What we need from you

1. Retest E1-E11 against the new build and tell us any case that still answers a bare 400.
2. Confirm the web build sees `ETag` and `Retry-After`.
3. Tell us if the 100-basket cap on `GET /pos/holds` affects a real workflow.
