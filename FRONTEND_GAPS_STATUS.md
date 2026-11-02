# Answer to BACKEND_INTEGRATION_GAPS.md

Status of every item in section 2 and 3, with the exact API the Flutter app should call.
New migration: `V4__frontend_gaps.sql` (Flyway runs it on start). Not yet run against a live database or compiled with Maven (see "Verification" at the end).

| # | Item | Status | API |
|---|------|--------|-----|
| 2.1 | Mobile OTP login | Done | `POST /auth/otp/send-mobile {mobile}` -> 204; `POST /auth/otp/verify-mobile {mobile, otp, deviceId}` -> same body as `/auth/otp/verify`. |
| 2.2 | Product variants | Done (Option A) | `productGroupId` on every product (customer and admin). Defaults to the product's own id. Admin create/update accept `productGroupId` to put a pack in an existing group. |
| 2.3 | Order lines self-describing | Mostly existed | `items[]` already returned `name`, `unitPricePaise`, `lineTotalPaise` (note: `lineTotalPaise`, not `linePaise`). Added `nameGu` and `unitLabel`. All frozen at order time. Older orders were back-filled from today's product data. |
| 2.4 | Cancel reason | Done | `POST /orders/{id}/cancel` body optional `{reason}` (max 200). Returned as `cancelReason` on the order. |
| 2.5 | Coupon flags | Done | `worksWithCoins`, `firstOrderOnly` on admin coupon create/update/list. |
| 2.6 | Stock history | Done | `GET /admin/stock-movements?productId=&page=&size=` -> `{id, productId, name, packLabel, delta, reason, at, cashierId}` (`cashierId` = whoever made the change; `productId` optional). |
| 2.7 | Period reports | Done | `GET /admin/reports/summary?period=week\|month\|quarter` or `?from=yyyy-MM-dd&to=yyyy-MM-dd`. |
| 2.8 | Delivery slot | Done | `slot` (max 40 chars) on `POST /orders`; echoed as `slot` on the order. Free text: the app owns the labels. |
| 2.9 | Notifications | API done, push not | `GET /me/notifications`, `POST /me/notifications/{id}/read`, `POST /me/notifications/read-all`. FCM push is still the logging stub. |
| 2.10 | Wishlist | Done | `GET /me/wishlist` (full products), `POST /me/wishlist/{productId}`, `DELETE ...` (both 204, repeatable). Max 200 items. |
| 2.11 | Ratings and reviews | Done | `rating`, `reviewCount` on products. `GET /products/{id}/reviews` (public), `POST /products/{id}/reviews {rating 1-5, comment}` (sign-in; writing again replaces). |
| 2.12 | CORS | Done | Config below. |

## Details worth knowing

**Mobile login.** A mobile-only user has `email: null`. `+91`, spaces and a leading 0 are accepted; the number must be a 10-digit Indian mobile (starts 6-9). If the shop already created a counter customer with that number, the first OTP login claims that account, so coins earned at the counter show up in the app. A mobile typed into the profile (`PUT /me`) is **not** trusted for login; only an OTP-verified number is. Once verified it cannot be changed through `PUT /me` (409 `MOBILE_VERIFIED`). Admin and cashier accounts keep using email. **SMS is not wired to a provider**: with `SMS_CHANNEL` unset the endpoint answers 503 `SMS_NOT_CONFIGURED`; dev uses the console and code `12345`. To go live, add one class implementing `SmsOtpSender` for your provider (India needs a DLT-registered template).

**Product groups.** Group packs by `productGroupId`. Reviews and ratings belong to a single pack row; for a group-level rating, average the packs' `rating` weighted by `reviewCount`.

**Coupons.** `firstOrderOnly` fails with `COUPON_INVALID` reason `FIRST_ORDER_ONLY` when the customer has any non-cancelled online order (counter bills are not counted). `worksWithCoins: false` does not fail the quote: coins are skipped and a `warnings` entry says so, so show it and `coinsUsed` will be 0. Existing coupons default to works-with-coins and not first-order-only; switch any that used `perUserLimit = 1` as a stand-in.

**Reports.** `salesPaise` and `orders` add online orders and counter bills together; `onlineSalesPaise/onlineOrders` and `posSalesPaise/posBills` give the split, `posRefundsPaise` is shown apart (not subtracted). `byDay` has one entry per day with zeros filled in. `topProducts.qty` counts units only (items weighed at the counter add to sales, not qty). Days use Asia/Kolkata. Maximum 366 days.

**CORS.** `app.cors.allowed-origin-patterns` (env `CORS_ORIGINS`, comma separated). Dev allows `http://localhost:*` and `http://127.0.0.1:*`, so `flutter run -d chrome` works with no setup. Production: set e.g. `CORS_ORIGINS=https://shop.example.com`. Allowed headers: `Authorization, Content-Type, Idempotency-Key, Accept`; `X-Trace-Id` is exposed. `/img/**` has the same origins (Flutter web fetches images as bytes).

## Section 3 answers

- **Guest browsing: this was a bug, now fixed.** `SecurityIT.catalogIsPublic` always expected guests to read the catalog, but `SecurityConfig` had no rule for it. `GET /categories`, `/products`, `/products/{id}`, `/products/{id}/reviews`, `/hot-sale` are now public. Everything else still needs sign-in.
- **Order number:** `VSM-<n>` starting at `VSM-1001`, from a database sequence. Use `orderNo` for display and `id` for calls.
- **Image URLs:** relative, `/img/<folder>/<uuid>_400.jpg`; prefix your API host. Swap `_400` for `_1000` on the detail view.
- **Delivery fee:** free for now (`deliveryFeePaise` is 0). The fee setting defaults to 0 (`DELIVERY_FEE_PAISE`, in paise); an admin-editable rule is planned. Always read `deliveryFeePaise` from the quote rather than assuming.
- **Online payment:** the provider is Razorpay (`paymentSession` on the order while `PAYMENT_PENDING`; webhook `POST /api/v1/payments/webhook/razorpay`). It was written against Razorpay's docs and has not run against a live account. Unpaid online orders auto-cancel after 15 minutes.
- **`Idempotency-Key`, `expectedTotalPaise`:** as you described.

## Verification

Written in an environment with no Maven Central, so I could not run `mvn`. What was checked: the whole tree parses and has no constructor or type mismatches against the changed records (javac with libraries absent). What was not: compilation against Spring, the Flyway migration, any test run. Run `mvn verify` (Docker needed for `*IT`). `FrontendGapsIT` covers every endpoint above. Since the build enforces 98%/95% coverage, send me the uncovered lines if it fails.

# Round 2: answers to BACKEND_INTEGRATION_GAPS v2

| v2 item | Verdict | What changed |
|---------|---------|--------------|
| 3.1 CORS headers absent | Configuration, not a bug | CORS is off unless origins are set. The dev origins (`http://localhost:*`) live in the `dev` profile only. Your backend answered `503 SMS_NOT_CONFIGURED`, which means `dev` is not active (dev sets `sms-channel: console`). Run with `SPRING_PROFILES_ACTIVE=dev`, or set `CORS_ORIGINS=http://localhost:*,http://127.0.0.1:*` (production: your real origin). Then `OPTIONS` and `GET` with an `Origin` return `Access-Control-*`. |
| 3.2 order `items` only `{productId, qty}` | Spec bug, data was right | Two classes were both named `Item` (`OrderViews.Item` and the POS cart `PosViews.Item`) and the OpenAPI generator keeps one schema per name, so the spec showed the POS one. The JSON already carried `name, nameGu, unitLabel, qty, unitPricePaise, lineTotalPaise`. Fixed by naming the schemas. |
| 3.3 `/admin/reports/summary` shape | Same cause | Two `Summary` classes (orders list vs sales report). The report is now schema `SalesSummary`. |
| 3.4 staff `active` | Done | `StaffView` now has `active`. |
| 3.5 public shop settings | Done | `GET /shop-settings` (public) -> `{shopName, phone, address, promise}`. `promise` is new (migration `V5`), set by the admin via `PUT /admin/shop-settings {promise}` (max 200). A save that leaves `promise` out keeps the current one. |
| 3.6 SMS | Config | Needs `SMS_CHANNEL` plus a provider class and DLT template. Not code you can flip on yet. |
| 3.7 FCM push | Not done | Still the log stub; needs Firebase credentials. |

## OpenAPI schema renames (no JSON change)

Only matters if your DTOs are generated from the spec by name:
`Item` -> `OrderItem` (orders) and `PosItem` (POS cart); `Summary` -> `OrderSummary` (order list) and `SalesSummary` (report); `Line` -> `QuoteLine`; `RuleView` -> `WalletRule` (customer `/wallet`) and `CoinRuleView` (admin coin rules).

## Section 4 answers

- **Order number:** `VSM-<n>` from `VSM-1001`.
- **Image URLs:** relative (`/img/<folder>/<uuid>_400.jpg`); prefix the API host. Swap `_400` for `_1000` on detail views.
- **Payment session:** Razorpay (`paymentSession` while `PAYMENT_PENDING`; webhook `POST /api/v1/payments/webhook/razorpay`). Untested against a live account. Unpaid online orders auto-cancel after 15 minutes.
- **Roles:** `/api/v1/admin/**` is ADMIN only. `/api/v1/pos/**` is CASHIER or ADMIN. Everything else (customer endpoints) needs any signed-in user; the public catalog, `/shop-settings`, `/auth/**` and `/img/**` need none. Cashiers sign in by email.
- **Weighed quantity:** `qty` is **grams** for `soldBy: WEIGHT` items (price is per 1000 g, line amount rounded to the paisa). For `UNIT` items it is a piece count. Weighed items are counter-only and not in the online catalog.
- **POS returns:** `POST /pos/sales/{id}/returns` is per line (sale item id and quantity), so a full-receipt return is the same call with every line. Needs `Idempotency-Key`. The screen decision is yours.
- **Grouped rating:** a product rating is stored per pack. Showing max pack rating with summed count works; a review-weighted average is more accurate if you want it.
