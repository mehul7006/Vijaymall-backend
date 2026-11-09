# 20. Orders, payments, shop details, admin configuration, caches, live events

Audience: Flutter and web teams and the LLM assistants they use. Read `00-overview.md` first (auth, error envelope, pagination, money, idempotency). This file is derived from the backend source; where the code is silent, this file says so rather than guessing.

Global conventions used below (do not repeat them per endpoint):

- Base path `/api/v1`. JSON UTF-8. Money is an integer number of **paise** (100 paise = Rs 1) in fields ending `Paise`. Timestamps are ISO-8601 UTC instants (`2026-11-08T10:15:30.123Z`). IDs are UUID strings.
- Error body for every non-2xx: `{ "code": "STRING", "message": "human text", "traceId": "…", "details": { … } }`. Validation errors are HTTP 400, code `VALIDATION_FAILED`, with `details.fields = { "<field>": "<message>" }`. A malformed body, a wrong-typed value, an unknown enum value or a missing required header is HTTP 400 `VALIDATION_FAILED` with message `Bad request` (no field details).
- **Common errors on every authenticated endpoint** (not repeated in the tables): 401 `UNAUTHENTICATED` (missing/expired token: refresh once, retry once), 403 `FORBIDDEN` (role not allowed), 429 `RATE_LIMITED` (300 API calls per minute per client IP; `details.retryAfterSeconds`; wait that long), 500 `INTERNAL_ERROR`.
- Page shape: `{ "items": [...], "page": 0, "size": 20, "total": 123 }`. `page` is 0-based (negative is treated as 0). `size` is clamped to 1..50 (default 20); a larger value silently becomes 50.
- "customer" below means any valid access token (the endpoint only calls `authenticated()` in SecurityConfig and uses the token's user id). In practice only CUSTOMER accounts use them.
- Role gate summary from `SecurityConfig`: `/api/v1/admin/orders` and `/api/v1/admin/orders/**` = ADMIN or CASHIER; every other `/api/v1/admin/**` = ADMIN only; `/api/v1/auth/**`, `/api/v1/payments/webhook/**` and `GET /api/v1/shop-settings` are public; `GET /api/v1/payment-methods`, all of `/api/v1/cart*` and `/api/v1/orders*` need a sign-in.

---

## Table of contents

| # | Endpoint | One-line purpose |
|---|---|---|
| 1 | `POST /api/v1/cart/quote` | Price a cart (coupon, coins, delivery fee) without placing an order |
| 2 | `GET /api/v1/cart` | Read the customer's saved basket (syncs across devices) |
| 3 | `PUT /api/v1/cart` | Replace the saved basket |
| 4 | `DELETE /api/v1/cart` | Empty the saved basket |
| 5 | `POST /api/v1/orders` | Place an online order (COD or ONLINE); needs `Idempotency-Key` |
| 6 | `GET /api/v1/orders` | The customer's order history (paged) |
| 7 | `GET /api/v1/orders/{id}` | One order with items, timeline, payment session |
| 8 | `POST /api/v1/orders/{id}/cancel` | Customer cancels before packing starts |
| 9 | `GET /api/v1/orders/{id}/reorder` | "Order again": what of an old order can be bought now |
| 10 | `GET /api/v1/orders/{id}/returns` | Customer reads returns recorded on their order |
| 11 | `GET /api/v1/orders/stream` | SSE: live updates for the customer (orders, wallet, credit, stock, catalog) |
| 12 | `GET /api/v1/admin/orders` | Staff order list (filter by status or order number) |
| 13 | `GET /api/v1/admin/orders/{id}` | Staff order detail with customer contact |
| 14 | `POST /api/v1/admin/orders/{id}/reject` | Staff turn an order down with a reason (refunds everything) |
| 15 | `POST /api/v1/admin/orders/{id}/status` | Staff move an order to another status |
| 16 | `POST /api/v1/admin/orders/{id}/returns` | Staff record goods returned from a delivered order; needs `Idempotency-Key` |
| 17 | `GET /api/v1/admin/orders/{id}/returns` | Staff list returns of an order |
| 18 | `GET /api/v1/admin/orders/stream` | SSE: every order change for staff screens |
| 19 | `GET /api/v1/payment-methods` | Payment methods the customer may pick at checkout |
| 20 | `POST /api/v1/payments/webhook/{provider}` | Razorpay to server only; the app never calls it |
| 21 | `GET /api/v1/admin/payment-methods` | Admin: methods with enabled/configured flags |
| 22 | `PUT /api/v1/admin/payment-methods` | Admin: switch COD / ONLINE on or off |
| 23 | `GET /api/v1/admin/refunds` | Admin: refunds sent back to customers (paged, by state) |
| 24 | `POST /api/v1/admin/refunds/{id}/retry` | Admin: retry a FAILED refund |
| 25 | `GET /api/v1/shop-settings` | Public shop details, opening hours, openNow, online-ordering switch (ETag) |
| 26 | `GET /api/v1/admin/shop-profile` | Admin: logo, contact links, hours, pause switch |
| 27 | `PUT /api/v1/admin/shop-profile` | Admin: save contact links, hours, pause online orders |
| 28 | `POST /api/v1/admin/shop-profile/logo` | Admin: upload shop logo |
| 29 | `DELETE /api/v1/admin/shop-profile/logo` | Admin: remove shop logo |
| 30 | `GET /api/v1/admin/config` | Admin: configuration screen (groups, items, issues) |
| 31 | `PUT /api/v1/admin/config` | Admin: save changed settings atomically |
| 32 | `GET /api/v1/admin/config/bootstrap` | Admin: read-only health of start-up settings |
| 33 | `POST /api/v1/admin/config/test/{target}` | Admin: "Test" buttons (razorpay, fcm, sms, email) |
| 34 | `GET /api/v1/admin/logs/status` | Admin: log levels and log files in force |
| 35 | `GET /api/v1/admin/cache` | Admin: cache statistics |
| 36 | `POST /api/v1/admin/cache/refresh` | Admin: hard refresh, empty every cache |
| 37 | `POST /api/v1/admin/cache/refresh/{name}` | Admin: empty one cache (or reload settings) |

37 endpoints. Sections after the endpoints: Live events reference, Flows.

---

## ORDER STATUS (state machine)

Statuses (exact strings in `status` fields): `PAYMENT_PENDING`, `PLACED`, `CONFIRMED`, `PACKED`, `OUT_FOR_DELIVERY`, `READY_FOR_PICKUP`, `DELIVERED`, `PICKED_UP`, `CANCELLED`.

| Status | Meaning for the customer | Who sets it | Holds stock? | Final? | Customer can cancel? |
|---|---|---|---|---|---|
| `PAYMENT_PENDING` | ONLINE order waiting for payment | System at place time (only when method is ONLINE and total > 0) | No | No | Yes |
| `PLACED` | Order received, shop has not accepted it | System at place time (COD, or ONLINE with total 0); or system after payment captured | No | No | Yes |
| `CONFIRMED` | Shop accepted it. **Stock is deducted now** | Staff | Yes | No | Yes |
| `PACKED` | Being/packed | Staff | Yes | No | No |
| `OUT_FOR_DELIVERY` | On the way (delivery) | Staff | Yes | No | No |
| `READY_FOR_PICKUP` | Ready at the shop (pickup) | Staff | Yes | No | No |
| `DELIVERED` | Delivered; coins earned are credited | Staff | Yes | **Yes** | No |
| `PICKED_UP` | Picked up; coins earned are credited | Staff | Yes | **Yes** | No |
| `CANCELLED` | Cancelled/rejected/expired/payment problem | Customer, staff, or system | No (stock given back) | **Yes** | n/a |

Transition rules (from `OrderStatus.next`):

- From `PAYMENT_PENDING` only `PLACED` (done by the system when the payment is captured) and `CANCELLED` are possible. Staff can cancel/reject it but cannot push it forward: `POST …/status` with any target other than `CANCELLED` answers 409 `AWAITING_PAYMENT`.
- From `PLACED`, `CONFIRMED`, `PACKED`, `OUT_FOR_DELIVERY`, `READY_FOR_PICKUP` staff may move to **any other status except `PAYMENT_PENDING`**, in any order, forwards or backwards (the code enforces no fixed sequence and does not tie `OUT_FOR_DELIVERY` to DELIVERY or `READY_FOR_PICKUP` to PICKUP; the UI should offer sensible next steps only: DELIVERY: PLACED to CONFIRMED to PACKED to OUT_FOR_DELIVERY to DELIVERED; PICKUP: PLACED to CONFIRMED to PACKED to READY_FOR_PICKUP to PICKED_UP).
- `DELIVERED`, `PICKED_UP`, `CANCELLED` are final: any change answers 409 `ILLEGAL_TRANSITION`. Requesting the status the order is already in via `…/status` is a no-op that returns 200 with the current order (not an error).
- Customer cancel: only from `PAYMENT_PENDING`, `PLACED`, `CONFIRMED` (`customerCanCancel`). Otherwise 409 `ILLEGAL_TRANSITION` "This order can no longer be cancelled".

Stock rules:

- Placing an order does **not** deduct stock. It only checks availability (409 `OUT_OF_STOCK` if short).
- Stock is deducted (reserved) the first time the order enters any status that holds stock (`CONFIRMED`, `PACKED`, `OUT_FOR_DELIVERY`, `READY_FOR_PICKUP`, `DELIVERED`, `PICKED_UP`); in practice at `CONFIRMED`. If stock ran out meanwhile, that status change fails with 409 `OUT_OF_STOCK`, the order stays where it was, and staff should reject it (which refunds the customer).
- Cancelling or rejecting gives stock back only if it had been deducted. Cancelling always also releases the coupon use, reverses coins spent on the order, and queues a refund when an online payment was captured.
- Entering `DELIVERED`/`PICKED_UP` credits the coins the order earns (`coinsEarned`) once, and marks a COD order's `paymentStatus` as `CAPTURED`.
- Automatic cancellations by the system: `PAYMENT_PENDING` orders unpaid after the timeout (admin setting `payment.onlineTimeoutMinutes`, default 15, allowed 5..120; the expiry job runs every 60 s so it can be a minute late) become `CANCELLED` with timeline note "payment timeout".

`paymentStatus` values on orders: `NONE` (COD, or total 0), `INITIATED` (ONLINE waiting), `CAPTURED` (money received; for COD set when delivered/picked up), `REFUNDED` (refund queued/sent after cancel or full return), `FAILED` (ONLINE order cancelled without ever being paid).

`fulfilment` values: `DELIVERY` or `PICKUP`.

---

## PAYMENT

Methods: only `COD` and `ONLINE` exist for the app. (`CREDIT` and `HYBRID` are counter-only; sending them to `POST /orders` is 400.)

- **COD**: order is created as `PLACED`; nothing to pay in the app. `paymentStatus` = `NONE` until delivered/picked up, then `CAPTURED`.
- **ONLINE** (Razorpay): if total > 0 the order is created as `PAYMENT_PENDING`, `paymentStatus` `INITIATED`, and the response carries `paymentSession`. If total is 0 (fully covered by coupon/coins and no delivery fee) it is treated like COD (`PLACED`, no payment session).

`paymentSession` (only present while status is `PAYMENT_PENDING`, otherwise `null`):

```json
{ "type": "RAZORPAY",
  "payload": { "keyId": "rzp_test_xxxxxxxx", "razorpayOrderId": "order_Abc123", "amountPaise": 54900, "currency": "INR" } }
```

Checkout steps the app performs:

1. Place the order with `paymentMethod: "ONLINE"`. Response status `PAYMENT_PENDING` with `paymentSession`.
2. Open the Razorpay Checkout SDK with `key = payload.keyId`, `order_id = payload.razorpayOrderId`, `amount = payload.amountPaise`, `currency = payload.currency` (Flutter: `razorpay_flutter`; web: Checkout.js). Prefill customer details if you like. Show a countdown to the payment deadline: place time + the timeout (the API does not return the deadline and the timeout setting is admin-only, so show a generic message such as "Pay within 15 minutes" or none; the server cancels the order when time runs out).
3. When the SDK reports success, **do not mark the order paid in the app**. Show "Confirming payment…" and wait for the server to confirm, by SSE (`order` event for this order) and/or polling `GET /orders/{id}` every 2-3 s for up to about 60-90 s, until `status` is no longer `PAYMENT_PENDING` (becomes `PLACED`, `paymentStatus` `CAPTURED`). There is no endpoint where the app sends the Razorpay signature; the server learns of payment only from Razorpay's webhook (and a fallback check every 5 minutes for payments older than 5 minutes whose webhook never arrived). If after about 90 s it is still pending, show "We are still confirming your payment. You will be notified; do not pay again" and keep the order screen refreshable.
4. If the customer dismisses or fails checkout, the order stays `PAYMENT_PENDING` until it expires. A failed attempt is only recorded server-side (it does **not** cancel the order), so the customer may retry: reopen checkout using the `paymentSession` from `GET /orders/{id}` (same Razorpay order). Offer "Retry payment" and "Cancel order" (`POST /orders/{id}/cancel`).
5. If the order becomes `CANCELLED` (timeout, cancel) and money was captured after that, the server automatically refunds it ("paid after expiry"). The client never needs to do anything for that.

What the client must NOT trust: the Razorpay success callback, any local "paid" state, the order total it computed, or an amount in the payload other than what it displays from the server. Only `GET /orders/{id}` (`status`, `paymentStatus`) or an SSE `order` event followed by a refetch is truth. Never send or store the Razorpay key secret (the app only ever gets the public `keyId`).

Webhook: `POST /api/v1/payments/webhook/razorpay` is called by Razorpay servers only, verified with the `X-Razorpay-Signature` header and the saved webhook secret. It is public in SecurityConfig (no token) but the app and web must never call it. Events handled: `payment.captured`/`order.paid` (marks paid), `payment.failed` (recorded, order stays pending), all others ignored. Duplicate events are ignored. An amount that does not match the order is not accepted: the payment is flagged and the full amount is queued for refund.

Refund lifecycle (admin sees these in `GET /admin/refunds`; customers do not see refund rows, they see `paymentStatus: "REFUNDED"` on the order and, for returns, `refundMode`/`refundPaise`):

| State | Meaning | Next |
|---|---|---|
| `PENDING` | Queued; not yet sent to Razorpay | Picked up within seconds (immediately after commit, then a job every 30 s) |
| `PROCESSING` | Being sent to Razorpay right now | `ISSUED` on success; back to `PENDING` on error (attempts+1); a refund stuck in PROCESSING over 10 minutes goes back to `PENDING` |
| `ISSUED` | Razorpay accepted the refund (`providerRef` stored server-side) | Final |
| `FAILED` | 10 attempts failed; needs a person | Admin presses retry (`POST /admin/refunds/{id}/retry`) which resets attempts and requeues |

A cancel or reject never waits for or fails because of Razorpay: the refund is queued and `paymentStatus` becomes `REFUNDED` at once even though the money may still be `PENDING` at Razorpay.

---

# Cart and quote

### POST /api/v1/cart/quote
- **Who**: customer (any signed-in user; `anyRequest().authenticated()`).
- **Purpose / when the UI calls it**: On the cart and checkout screens, every time lines, coupon, coin choice, or delivery/pickup change. It returns the only trustworthy price breakdown. Nothing is stored or reserved.
- **Request**: no path/query params. Body:

```json
{
  "items": [ { "productId": "0b0e8c0e-5a52-4a43-a2e1-2d7f0d4a9b11", "qty": 2 } ],
  "couponCode": "WELCOME50",
  "coinMode": "PERCENT",
  "percent": 50,
  "fulfilment": "DELIVERY"
}
```

| field | type | required | validation | meaning |
|---|---|---|---|---|
| `items` | array | yes | non-empty (`@NotEmpty`, else 400 `VALIDATION_FAILED` field `items`) | cart lines |
| `items[].productId` | UUID | yes | `@NotNull`; must be an active, customer-visible product (not counter-only), else 400 `VALIDATION_FAILED` field `productId` "product is not available: <id>" | product (pack) id |
| `items[].qty` | int | yes (default 0 if absent, which is rejected) | must be 1..99, else 400 field `qty` "quantity must be 1 to 99". Repeated productIds are merged (quantities summed) before the 1..99 check is NOT re-applied, so the merged sum can exceed 99 | quantity |
| `couponCode` | string | no | blank/null = none. Case-insensitive (normalised). Unknown/unusable coupon gives 422 `COUPON_INVALID` | coupon |
| `coinMode` | enum `NONE`/`ALL`/`PERCENT` | no | null = `NONE`. Other value = 400 `Bad request` | how many coins to spend |
| `percent` | int | no | used only for `PERCENT`; null = 100; values are clamped to 0..100 by the maths (no 400) | percent of the maximum redeemable coins |
| `fulfilment` | enum `DELIVERY`/`PICKUP` | no | null = `DELIVERY` | PICKUP has no delivery fee |

- **Response** 200:

```json
{
  "lines": [ { "productId": "0b0e8c0e-…", "name": "Basmati Rice 5kg", "qty": 2, "unitPricePaise": 45000,
               "lineTotalPaise": 90000, "coinRedeemable": true, "earnsCoins": true,
               "nameGu": "બાસમતી ચોખા 5kg", "unitLabel": "5 kg", "categoryId": "…", "couponDiscountPaise": 5000 } ],
  "subtotalPaise": 90000,
  "couponCode": "WELCOME50",
  "couponDiscountPaise": 5000,
  "coinsUsed": 100,
  "coinDiscountPaise": 1000,
  "deliveryFeePaise": 4000,
  "totalPaise": 88000,
  "coinsToEarn": 85,
  "warnings": [ "Some items cannot be paid with coins" ],
  "couponByCategory": [ { "categoryId": "…", "subtotalPaise": 90000, "discountPaise": 5000 } ]
}
```

| field | type | meaning |
|---|---|---|
| `lines[]` | array | priced lines (merged duplicates, in request order) |
| `lines[].unitPricePaise`, `lineTotalPaise` | int | current price; line total = unit x qty |
| `lines[].coinRedeemable` / `earnsCoins` | bool | whether the line can be paid with coins / earns coins |
| `lines[].nameGu`, `unitLabel` | string | Gujarati name, pack label (e.g. "5 kg") |
| `lines[].categoryId` | UUID or null | category for per-category coupon display |
| `lines[].couponDiscountPaise` | int | this line's share of the coupon discount (0 if not covered) |
| `subtotalPaise` | int | sum of line totals |
| `couponCode` | string or null | normalised code if one was sent |
| `couponDiscountPaise` | int | discount; never above subtotal |
| `coinsUsed` | int | coins that will be spent (never more than balance) |
| `coinDiscountPaise` | int | `coinsUsed` x paise-per-coin |
| `deliveryFeePaise` | int | 0 for PICKUP; for DELIVERY: 0 if (subtotal - coupon - coin discount) >= free-above threshold, else the fee. Fee and threshold are admin settings `delivery.feePaise` (default 0) and `delivery.freeAbovePaise` (default 49900) |
| `totalPaise` | int | `max(0, subtotal - coupon - coin + delivery)`. This is the amount to charge |
| `coinsToEarn` | int | coins credited on delivery (after coupon and coin discounts; delivery fee earns nothing) |
| `warnings` | string[] | human messages, e.g. coupon cannot be combined with coins (then `coinsUsed` is 0), or some items cannot be paid with coins. Display them |
| `couponByCategory` | array | per cart category: `subtotalPaise` and the coupon `discountPaise` it received; empty without a coupon discount |

Calculation order: subtotal, coupon, coins (max redeemable limited by a coin-settings percentage and by coin-redeemable lines), delivery fee, total, coins to earn.

- **Errors**:

| status | code | when |
|---|---|---|
| 400 | `VALIDATION_FAILED` | empty items; qty outside 1..99; product not available/unknown/counter-only; bad enum or JSON |
| 422 | `COUPON_INVALID` | coupon unusable; `details.reason` is one of `NOT_FOUND`, `EXPIRED` (inactive or outside dates), `MIN_ORDER`, `CATEGORY` (no cart item in the coupon's categories), `LIMIT_REACHED`, `FIRST_ORDER_ONLY` |

- **UI notes**: Debounce calls (quantity steppers). Never compute totals in the app; render the response. If the call fails with `COUPON_INVALID`, show the reason and offer to remove the coupon, then re-quote. Always keep the last `totalPaise` to send as `expectedTotalPaise` when placing. Cap qty at 99 in the stepper (the saved basket allows more but the quote does not).

### GET /api/v1/cart
- **Who**: customer.
- **Purpose / when the UI calls it**: On app start after sign-in, to restore the basket saved from another device. Not a price source.
- **Request**: none.
- **Response** 200 (always 200, never 404; empty basket returns the empty shape):

```json
{ "items": [ { "productId": "0b0e8c0e-…", "qty": 2 } ], "couponCode": "WELCOME50", "coinMode": "PERCENT", "percent": 50, "updatedAt": "2026-11-08T10:15:30Z" }
```

| field | type | meaning |
|---|---|---|
| `items[]` | array of `{productId, qty}` | pack ids exactly as `POST /cart/quote` accepts |
| `couponCode` | string or null | saved coupon (upper-cased) |
| `coinMode` | `NONE`/`ALL`/`PERCENT` | saved choice (`NONE` when empty) |
| `percent` | int or null | only non-null when `coinMode` is `PERCENT` |
| `updatedAt` | ISO instant or null | null for a never-saved basket |

- **Errors**: only the common ones.
- **UI notes**: Saved products may since have become unavailable or out of stock; always run the quote and drop lines that fail (a 400 `productId` from the quote names the bad product id in the message).

### PUT /api/v1/cart
- **Who**: customer.
- **Purpose / when the UI calls it**: After each cart change (debounced) so the basket follows the customer across devices. Last write wins; the stored basket becomes exactly what is sent.
- **Request**: body:

```json
{ "items": [ { "productId": "0b0e8c0e-…", "qty": 2 } ], "couponCode": "WELCOME50", "coinMode": "PERCENT", "percent": 50 }
```

| field | type | required | validation | meaning |
|---|---|---|---|---|
| `items` | array | yes | `@NotNull`, at most 100 lines (else 400 `items`); an empty array is allowed | lines |
| `items[].productId` | UUID | yes | `@NotNull` | pack id (existence is not checked here) |
| `items[].qty` | int | yes | `@Min(1)`; the total for one product (lines of the same product are added together) must be at most **99**, else 400 field `qty` "quantity must be 1 to 99" | quantity |
| `couponCode` | string | no | blank becomes null; trimmed and upper-cased; not validated against coupons | saved coupon |
| `coinMode` | enum | no | null becomes `NONE` | saved coin choice |
| `percent` | int | no | `@Min(0) @Max(100)`; kept only when `coinMode` is `PERCENT` (null stored as 0 then) | saved percent |

Server normalisation: repeated `productId`s are merged (first position wins, quantities summed). The limit of 99 applies to the merged total (and the saved basket `PUT /cart` allows at most 99 per line).

- **Response** 200: the stored basket, same shape as `GET /cart`.
- **Errors**: 400 `VALIDATION_FAILED` (rules above).
- **UI notes**: Do not use as a stock/price check. Treat the response as the canonical basket (merged lines).

### DELETE /api/v1/cart
- **Who**: customer.
- **Purpose / when the UI calls it**: After an order is placed successfully, or when the customer clears the cart.
- **Request**: none.
- **Response**: 204 No Content (also when nothing was stored).
- **Errors**: only the common ones.
- **UI notes**: Call it after `POST /orders` returns 201 so the other devices do not show a stale basket.

---

# Orders (customer)

### POST /api/v1/orders
- **Who**: customer (signed-in; the order belongs to the token's user).
- **Purpose / when the UI calls it**: The "Place order" button on checkout. Creates the order from the cart lines, re-prices it on the server, applies coupon/coins, and for ONLINE starts the Razorpay payment.
- **Request**:
  - Header `Idempotency-Key: <string>` is **mandatory** (missing header = 400 `VALIDATION_FAILED` "Bad request"; blank = 400 field `Idempotency-Key`). Generate one random UUID v4 per checkout attempt, keep it while the user is retrying the same cart, and create a new one when the user changes the cart or after a definitive failure. The key is scoped to the user.
  - **Replay behaviour**: if an order already exists for (user, key), the server returns that order (HTTP 201 again, same shape, current state) without creating anything, without re-checking prices, stock, ordering-paused or payment settings, and ignoring any differences in the new body. This makes network retries safe.
  - Body:

```json
{
  "items": [ { "productId": "0b0e8c0e-…", "qty": 2 } ],
  "couponCode": "WELCOME50",
  "coinMode": "PERCENT",
  "percent": 50,
  "fulfilment": "DELIVERY",
  "addressId": "6a2f1c1d-…",
  "paymentMethod": "ONLINE",
  "expectedTotalPaise": 88000,
  "slot": "Today 6-8 PM"
}
```

| field | type | required | validation | meaning |
|---|---|---|---|---|
| `items`, `couponCode`, `coinMode`, `percent`, `fulfilment` | | | same as `POST /cart/quote` (including qty 1..99 and the product rules) | exactly the cart you quoted |
| `addressId` | UUID | required when `fulfilment` is `DELIVERY` (or omitted, the default) | missing: 400 field `addressId` "address is required for delivery"; not the user's address: 404 `NOT_FOUND` "Address not found". Ignored for PICKUP | delivery address; a snapshot is stored on the order |
| `paymentMethod` | string | yes | `@NotNull`; must be exactly `COD` or `ONLINE`. `CREDIT`/`HYBRID` give 400 field `paymentMethod`; other values 400 "COD or ONLINE"; a method switched off by the admin (or ONLINE without Razorpay keys) gives 400 field `paymentMethod` "<method> is not available" | payment choice |
| `expectedTotalPaise` | long | yes (absent = 0) | must equal the server's freshly computed total, else 409 `PRICE_CHANGED` | the `totalPaise` the customer saw |
| `slot` | string | no | max 40 chars after trimming (400 field `slot`); blank = none | free text time window, stored and shown back |

- **Response** 201 (`OrderView`, see GET one below). Example for ONLINE:

```json
{
  "id": "e3b0…", "orderNo": "1001", "status": "PAYMENT_PENDING", "fulfilment": "DELIVERY",
  "paymentMethod": "ONLINE", "paymentStatus": "INITIATED",
  "items": [ { "productId": "0b0e8c0e-…", "name": "Basmati Rice 5kg", "qty": 2, "unitPricePaise": 45000, "lineTotalPaise": 90000,
               "nameGu": "…", "unitLabel": "5 kg", "couponDiscountPaise": 5000 } ],
  "subtotalPaise": 90000, "couponCode": "WELCOME50", "couponDiscountPaise": 5000,
  "coinsUsed": 100, "coinDiscountPaise": 1000, "deliveryFeePaise": 4000, "totalPaise": 88000, "coinsEarned": 85,
  "address": { "id": "6a2f…", "label": "Home", "line1": "12 Main Rd", "area": "Navrangpura", "pincode": "380009", "isDefault": true },
  "paymentSession": { "type": "RAZORPAY", "payload": { "keyId": "rzp_test_xxxxxxxx", "razorpayOrderId": "order_Abc123", "amountPaise": 88000, "currency": "INR" } },
  "timeline": [ { "status": "PAYMENT_PENDING", "at": "2026-11-08T10:15:30Z" } ],
  "placedAt": "2026-11-08T10:15:30Z", "slot": "Today 6-8 PM", "cancelReason": null, "coinBalance": 340
}
```

- **Errors**:

| status | code | when |
|---|---|---|
| 400 | `VALIDATION_FAILED` | missing/blank Idempotency-Key; empty items; qty/product problems; missing addressId for delivery; `paymentMethod` invalid/unavailable; `slot` too long; `coins`: "not enough coins" (balance dropped since the quote) |
| 404 | `NOT_FOUND` | `addressId` not found for this user |
| 409 | `PRICE_CHANGED` | server total differs from `expectedTotalPaise`. `details.quote` holds the fresh full quote (same shape as the quote response) |
| 409 | `OUT_OF_STOCK` | availability short at place time. `details.items = [{ "productId": "…", "available": 1 }]` |
| 422 | `ORDERS_PAUSED` | admin paused online ordering; `message` is the shop's own text, show it as is |
| 422 | `COUPON_INVALID` | coupon unusable (see quote) or its limit was reached by a concurrent order (`reason` `LIMIT_REACHED`) |
| 502/503/500 | `PAYMENT_PROVIDER_ERROR` / `PAYMENT_NOT_CONFIGURED` | Razorpay order could not be created / keys missing. Whole placement is rolled back; nothing was created |

- **UI notes**: Disable the button after tap and reuse the same Idempotency-Key for automatic retries (timeouts, no response). On 409 `PRICE_CHANGED`, replace the shown totals from `details.quote`, tell the customer prices changed, and let them confirm again (this time send `expectedTotalPaise = details.quote.totalPaise`, with a **new** Idempotency-Key because no order was created and the new body differs; reusing the old key is also safe because no order exists, but a fresh key is cleaner). On `OUT_OF_STOCK` mark the listed products and re-quote. On `ORDERS_PAUSED` show the message and block checkout (also see `onlineOrdersOpen` in `GET /shop-settings` to disable checkout beforehand). After 201: clear the basket (`DELETE /cart`), go to the order screen; if `status` is `PAYMENT_PENDING` open Razorpay checkout from `paymentSession`. Coins are reserved at place time (deducted from balance, restored on cancel); the customer's coupon use is counted at place time.

### GET /api/v1/orders
- **Who**: customer (only their own orders).
- **Purpose / when the UI calls it**: "My orders" list.
- **Request**: query `page` (int, default 0), `size` (int, default 20, clamped to 1..50). Newest first (by `placedAt`).
- **Response** 200: page of summaries:

```json
{ "items": [ { "id": "e3b0…", "orderNo": "1001", "status": "PLACED", "fulfilment": "DELIVERY", "totalPaise": 88000, "itemCount": 2,
               "placedAt": "2026-11-08T10:15:30Z", "subtotalPaise": 90000, "couponCode": "WELCOME50", "couponDiscountPaise": 5000,
               "coinsUsed": 100, "coinDiscountPaise": 1000, "deliveryFeePaise": 4000, "paymentMethod": "COD", "paymentStatus": "NONE" } ],
  "page": 0, "size": 20, "total": 1 }
```

| field | type | meaning |
|---|---|---|
| `id`, `orderNo` | UUID, string | id for API paths; number shown to humans (same number in the shop's records) |
| `status`, `fulfilment`, `paymentMethod`, `paymentStatus` | string | see enums above |
| `totalPaise`, `subtotalPaise`, `couponDiscountPaise`, `coinDiscountPaise`, `deliveryFeePaise` | long | money; `totalPaise` = what was charged |
| `itemCount` | int | number of lines |
| `couponCode` | string or null | coupon applied |
| `coinsUsed` | int | coins spent |
| `placedAt` | ISO instant | when placed |

- **Errors**: only the common ones.
- **UI notes**: Refetch page 0 on an SSE `order` event and after any reconnect. Group "open" vs "past" client-side from `status`.

### GET /api/v1/orders/{id}
- **Who**: customer (own order only; someone else's id gives 404, not 403).
- **Purpose / when the UI calls it**: Order detail/tracking screen; payment confirmation polling; reopening a pending payment.
- **Request**: path `id` (UUID).
- **Response** 200: `OrderView`:

| field | type | meaning |
|---|---|---|
| `id`, `orderNo` | UUID, string | identifiers |
| `status` | string | see ORDER STATUS |
| `fulfilment` | `DELIVERY`/`PICKUP` | |
| `paymentMethod` | `COD`/`ONLINE` | |
| `paymentStatus` | `NONE`/`INITIATED`/`CAPTURED`/`REFUNDED`/`FAILED` | |
| `items[]` | array | `productId, name, qty, unitPricePaise, lineTotalPaise, nameGu, unitLabel, couponDiscountPaise`, frozen at order time |
| `subtotalPaise`, `couponCode` (nullable), `couponDiscountPaise`, `coinsUsed`, `coinDiscountPaise`, `deliveryFeePaise`, `totalPaise` | | same as the quote at order time |
| `coinsEarned` | int | coins this order will credit (credited on DELIVERED/PICKED_UP) |
| `address` | object or null | `{id,label,line1,area,pincode,isDefault}` snapshot; null for PICKUP |
| `paymentSession` | object or null | only while `PAYMENT_PENDING` (and the payment is still open): `{type:"RAZORPAY", payload:{keyId, razorpayOrderId, amountPaise, currency}}` |
| `timeline[]` | array | `{status, at}` oldest first; every status the order has entered |
| `placedAt` | ISO instant | |
| `slot` | string or null | the customer's chosen window |
| `cancelReason` | string or null | the customer's reason, or `"Rejected by shop: <reason>"` when staff rejected |
| `coinBalance` | int or null | the customer's current coin balance (use to refresh the wallet badge) |

- **Errors**: 404 `NOT_FOUND` ("Order not found") for unknown or other users' orders.
- **UI notes**: Show a stepper from `timeline`/`status` (DELIVERY vs PICKUP final step differs). `CANCELLED` with `cancelReason` starting "Rejected by shop:" should be presented as "Shop could not fulfil your order" with the reason; a timeline note is not returned to customers. Show "Refund on its way" when `status` is `CANCELLED` and `paymentStatus` is `REFUNDED`. If a payment is pending but `paymentSession` is null, the payment attempt is closed; show "Payment expired or failed" and expect the order to be cancelled.

### POST /api/v1/orders/{id}/cancel
- **Who**: customer (own order).
- **Purpose / when the UI calls it**: "Cancel order" button, available only while `status` is `PAYMENT_PENDING`, `PLACED` or `CONFIRMED`.
- **Request**: path `id`. Body optional (may be absent or `{}`):

```json
{ "reason": "Ordered by mistake" }
```

| field | type | required | validation | meaning |
|---|---|---|---|---|
| `reason` | string | no | max 200 chars (400 field `reason`); blank = none | shown back as `cancelReason`; also stored in the timeline note |

- **Response** 200: `OrderView` (status `CANCELLED`). Side effects: stock given back if it had been deducted, coupon use released, coins spent restored, captured online payment queued for refund (`paymentStatus` becomes `REFUNDED`), unpaid ONLINE becomes `paymentStatus` `FAILED`. No coins are earned.
- **Errors**:

| status | code | when |
|---|---|---|
| 404 | `NOT_FOUND` | not this user's order |
| 409 | `ILLEGAL_TRANSITION` | status is `PACKED` or later, or already final |
| 409 | `VERSION_CONFLICT` | staff changed the order at the same moment; reload and retry |
| 400 | `VALIDATION_FAILED` | reason longer than 200 |

- **UI notes**: Show a confirmation dialog with optional reason chips. On 409 reload the order and hide the button. Also refresh the wallet.

### GET /api/v1/orders/{id}/reorder
- **Who**: customer (own order; 404 otherwise).
- **Purpose / when the UI calls it**: "Order again" on a past order. Checks each earlier line against today's catalog and stock so the app can fill the basket.
- **Request**: path `id`.
- **Response** 200:

```json
{ "orderId": "e3b0…", "orderNo": "1001", "allAvailable": false,
  "items": [ { "productId": "0b0e…", "name": "Basmati Rice 5kg", "nameGu": "…", "unitLabel": "5 kg", "requestedQty": 3, "qty": 2,
               "unitPricePaise": 46000, "priceChanged": true, "available": true, "reason": "LOW_STOCK" } ] }
```

| field | type | meaning |
|---|---|---|
| `items[].requestedQty` | int | quantity in the old order |
| `items[].qty` | int | quantity that can be added now (min of requested and stock; 0 if none) |
| `items[].unitPricePaise` | long | today's price (old price when the product is unavailable) |
| `items[].priceChanged` | bool | today's price differs from the old order's price |
| `items[].available` | bool | `qty > 0` |
| `items[].reason` | string or null | null = full quantity available; `LOW_STOCK` = only part; `OUT_OF_STOCK`; `UNAVAILABLE` (removed/hidden/counter-only) |
| `allAvailable` | bool | true only when every line is available in full |

- **Errors**: 404 `NOT_FOUND`.
- **UI notes**: Add lines with `qty > 0` to the basket using `qty`, tell the customer about the rest, then go to the cart and quote normally.

### GET /api/v1/orders/{id}/returns
- **Who**: customer (own order only; 404 for someone else's).
- **Purpose / when the UI calls it**: Order detail, to show goods returned and refunds recorded by the shop.
- **Request**: path `id`.
- **Response** 200: array of `ReturnView`, oldest first (empty array if none):

```json
[ { "id": "…", "orderId": "e3b0…", "orderNo": "1001", "reason": "Damaged", "refundPaise": 45000, "refundMode": "ONLINE",
    "coinsClawedBack": 0, "coinsRestored": 0, "createdAt": "2026-11-09T09:00:00Z",
    "items": [ { "productId": "0b0e…", "name": "Basmati Rice 5kg", "qty": 1, "refundPaise": 45000, "restocked": true } ] } ]
```

| field | type | meaning |
|---|---|---|
| `refundPaise` | long | money back for this return (line share of what was really paid after coupon/coin; delivery fee excluded) |
| `refundMode` | `ONLINE`/`CASH`/`NONE` | `ONLINE` = refunded to the customer's payment method; `CASH` = the shop hands cash (COD orders, or refund queue not applicable); `NONE` = 0 refund |
| `coinsClawedBack` | int | earned coins taken back |
| `coinsRestored` | int | spent coins returned to the customer |
| `items[]` | array | `productId, name, qty, refundPaise, restocked` |
| `reason`, `createdAt` | string, ISO instant | |

- **Errors**: 404 `NOT_FOUND`.
- **UI notes**: Customers cannot create returns; only staff can (endpoint below).

---

# Live events (SSE)

### GET /api/v1/orders/stream
- **Who**: customer (signed in). The browser `EventSource` cannot send headers, so for **this path (and the admin stream) only** the token may be passed as `?access_token=<accessToken>`; on every other path the query token is ignored. The Authorization header is accepted too. Prefer the header when the HTTP client allows it (Flutter `http`/`dio` with a streaming response).
- **Purpose / when the UI calls it**: Open once after sign-in and keep open while the app is in the foreground. It pushes changes to the customer's own orders, wallet and credit, and global stock/catalog changes, so screens refresh without pulling.
- **Request**: no params. Response `Content-Type: text/event-stream`, with `Cache-Control: no-cache` and `X-Accel-Buffering: no`.
- **Response**: events (each `event:` name then `data:` JSON). Full list in the "Live events reference" below. Immediate `ready` event on connect.
- **Errors**: 401 `UNAUTHENTICATED` when the token is missing/expired at connect time (the stream is authorised only when it opens).
- **UI notes**: See the reference section: streams last at most 10 minutes, then end; at most 5 simultaneous streams per customer (the oldest is closed when a 6th opens); events are **not** replayed, so on every (re)connect reload the screens once; reconnect with a fresh access token (refresh first if expired) and back off (1 s, 2 s, 5 s … max 30 s). Treat an event only as a hint: refetch the data.

### GET /api/v1/admin/orders/stream
- **Who**: cashier or admin (path is under `/admin/orders/**`). `?access_token=` allowed as above.
- **Purpose / when the UI calls it**: Staff order board/counter screen: shows new orders instantly and status changes made by other staff.
- **Request / Response**: as above. Staff streams receive every `order` event for all customers plus `stock` and `catalog`, but not `wallet` or `credit`. Max 200 concurrent staff streams (oldest closed beyond that).
- **Errors**: 401 / 403 (customer token).
- **UI notes**: On an `order` event with `previousStatus` null (a new order) play a sound/badge and refetch `GET /admin/orders`. Reload the list after reconnects.

---

# Orders (staff: ADMIN and CASHIER)

### GET /api/v1/admin/orders
- **Who**: admin or cashier (SecurityConfig: `/api/v1/admin/orders/**` = ADMIN, CASHIER).
- **Purpose / when the UI calls it**: The staff order list (inbox) with status tabs and a search box.
- **Request**: query:

| param | type | default | rules |
|---|---|---|---|
| `status` | string | none | one of the status names; blank = all. It is not validated: an unknown value returns an empty page |
| `q` | string | none | order number or part of it (e.g. `1001`); case-insensitive; `%` and `_` characters are ignored |
| `page` | int | 0 | |
| `size` | int | 20 | clamped to 1..50 |

Newest first by `placedAt`.

- **Response** 200: page of `AdminSummary` (all fields of the customer summary plus `customerName`, `customerEmail`, `customerMobile`, each nullable):

```json
{ "items": [ { "id": "…", "orderNo": "1001", "status": "PLACED", "fulfilment": "DELIVERY", "totalPaise": 88000, "itemCount": 2,
               "placedAt": "2026-11-08T10:15:30Z", "customerName": "Asha", "customerEmail": "a@b.com", "customerMobile": "9876543210",
               "subtotalPaise": 90000, "couponCode": null, "couponDiscountPaise": 0, "coinsUsed": 0, "coinDiscountPaise": 0,
               "deliveryFeePaise": 4000, "paymentMethod": "COD", "paymentStatus": "NONE" } ],
  "page": 0, "size": 20, "total": 1 }
```

- **Errors**: only the common ones.
- **UI notes**: Show `PAYMENT_PENDING` orders in a separate "awaiting payment" tab (staff cannot confirm those). Refetch on SSE `order` events.

### GET /api/v1/admin/orders/{id}
- **Who**: admin or cashier.
- **Purpose / when the UI calls it**: Staff order detail.
- **Request**: path `id`.
- **Response** 200: all `OrderView` fields (see customer GET one; they are flattened at the top level) plus `customerName`, `customerEmail`, `customerMobile`. `paymentSession` is included while `PAYMENT_PENDING`.
- **Errors**: 404 `NOT_FOUND`.
- **UI notes**: Offer only valid next statuses (see state machine). Show `cancelReason`, `slot`, and `address` for delivery.

### POST /api/v1/admin/orders/{id}/reject
- **Who**: admin or cashier.
- **Purpose / when the UI calls it**: "Reject order" (item not available, cannot deliver, stock ran out at confirm time). The customer sees the reason and gets everything back.
- **Request**: path `id`. Body:

```json
{ "reason": "Item out of stock" }
```

| field | type | required | validation | meaning |
|---|---|---|---|---|
| `reason` | string | yes | `@NotNull @Size(1..200)`; also blank after trimming = 400 field `reason` "tell the customer why the order is rejected"; > 200 = 400 | shown to the customer as `cancelReason` = "Rejected by shop: <reason>" and in the notification |

- **Response** 200: `AdminView` (status `CANCELLED`). Effects: stock restored if deducted, coupon use released, coins restored, online payment refunded (queued).
- **Errors**:

| status | code | when |
|---|---|---|
| 400 | `VALIDATION_FAILED` | reason missing/blank/over 200 |
| 404 | `NOT_FOUND` | unknown order |
| 409 | `ILLEGAL_TRANSITION` | order already `DELIVERED`, `PICKED_UP` or `CANCELLED` |
| 409 | `VERSION_CONFLICT` | another change at the same moment; reload and retry |

- **UI notes**: Works from any non-final status including `PAYMENT_PENDING`. Use a reason picker plus free text; confirm before sending (irreversible).

### POST /api/v1/admin/orders/{id}/status
- **Who**: admin or cashier.
- **Purpose / when the UI calls it**: Move an order along (Confirm, Pack, Out for delivery, Ready for pickup, Delivered, Picked up) or cancel it.
- **Request**: path `id`. Body:

```json
{ "status": "CONFIRMED", "note": "optional text" }
```

| field | type | required | validation | meaning |
|---|---|---|---|---|
| `status` | enum (the 9 statuses) | yes | `@NotNull`; unknown value = 400 `Bad request` | target |
| `note` | string | no | no length check in the controller; stored in the timeline history (not shown to customers in `OrderView`) | internal note |

- **Response** 200: `AdminView` after the change (if already in that status: unchanged order, no error).
- **Errors**:

| status | code | when |
|---|---|---|
| 404 | `NOT_FOUND` | unknown order |
| 409 | `AWAITING_PAYMENT` | order is `PAYMENT_PENDING` and target is not `CANCELLED` |
| 409 | `ILLEGAL_TRANSITION` | order is final, or target is `PAYMENT_PENDING` |
| 409 | `OUT_OF_STOCK` | moving to a stock-holding status (usually `CONFIRMED`) but shelf stock is short; `details.items` lists `{productId, available}`; order unchanged. Offer "Reject" |
| 409 | `VERSION_CONFLICT` | order changed concurrently; reload and retry |

- **UI notes**: Prefer `reject` over `status: CANCELLED` (it records a customer-visible reason; cancelling through `status` leaves `cancelReason` empty). Moving to `DELIVERED`/`PICKED_UP` is irreversible (coins are paid out). Moving to `CANCELLED` refunds like reject.

### POST /api/v1/admin/orders/{id}/returns
- **Who**: admin or cashier (path under `/admin/orders/**`). The service applies no extra role or time-window rule.
- **Purpose / when the UI calls it**: Staff records goods returned after delivery (damaged, wrong, missing items) and refunds the share.
- **Request**: path `id`. Header `Idempotency-Key` is **mandatory** (missing = 400 `Bad request`; blank = 400 field `Idempotency-Key`). The key is global (not per user), so use a random UUID per submission. **Replay**: a repeated key returns the first stored return (HTTP 201) without refunding again, even if the body differs. Body:

```json
{ "reason": "Packet damaged", "items": [ { "productId": "0b0e8c0e-…", "qty": 1, "restock": false } ] }
```

| field | type | required | validation | meaning |
|---|---|---|---|---|
| `reason` | string | yes (checked in the service) | after trimming 1..200 chars else 400 field `reason` | why |
| `items` | array | yes | `@NotEmpty`; 1..100 entries else 400 field `items` | lines returned |
| `items[].productId` | UUID | yes | must be on this order, else 400 field `productId` "is not on this order"; no duplicates (400 field `items`) | product |
| `items[].qty` | int | yes | > 0 (else 400 field `qty`); not more than `sold qty - already returned` else 409 `RETURN_EXCEEDS_SOLD` | quantity coming back |
| `items[].restock` | bool | no | null = true | `true` puts the goods back on the shelf (stock released with reason ORDER_RETURN); `false` = "claimed defective": the customer is still refunded, but the units are **not** added to sellable stock; they wait in a review queue (`PENDING_REVIEW`) where staff or the owner confirm it is defective or put it back on sale (see `GET /api/v1/pos/stock/defective` and the review flow in file 30) |

- **Response** 201: `ReturnView` (same as customer GET returns, a single object).
- **Errors**:

| status | code | when |
|---|---|---|
| 400 | `VALIDATION_FAILED` | key missing; reason; items; productId; qty |
| 404 | `NOT_FOUND` | unknown order |
| 409 | `ORDER_NOT_DELIVERED` | order status is not `DELIVERED` or `PICKED_UP` |
| 409 | `RETURN_EXCEEDS_SOLD` | more than remains returnable; `details.productId`, `details.returnable` |
| 409 | `REFUND_EXCEEDS_ORDER` | total refunds would exceed the order total; `details.totalPaise` |
| 409 | `REFUND_EXCEEDS_PAYMENT` | online refund would exceed what was paid; `details.paidPaise`, `alreadyRefundedPaise`, `requestedPaise` |

- **UI notes**: Show the refund amount from the response (`refundPaise`, `refundMode`). For `CASH` tell the cashier to hand cash. For `ONLINE` the refund goes to the customer's payment method asynchronously. Only DELIVERED/PICKED_UP orders: hide the button otherwise. Coins earned on returned goods are taken back and spent coins are restored proportionally.

### GET /api/v1/admin/orders/{id}/returns
- **Who**: admin or cashier.
- **Purpose / when the UI calls it**: Show previous returns on the staff order detail and compute what is still returnable.
- **Request**: path `id`.
- **Response** 200: array of `ReturnView` (see above), oldest first.
- **Errors**: 404 `NOT_FOUND`.
- **UI notes**: Remaining returnable qty per line = qty on the order minus sum of `items[].qty` already returned for that product.

---

# Payments

### GET /api/v1/payment-methods
- **Who**: customer (signed-in; not in the public list in SecurityConfig).
- **Purpose / when the UI calls it**: Checkout screen: which of COD / ONLINE to show.
- **Request**: none.
- **Response** 200: only methods that are switched on by the admin and usable (ONLINE appears only if Razorpay key id and secret are configured):

```json
[ { "code": "COD", "enabled": true }, { "code": "ONLINE", "enabled": true } ]
```

- **Errors**: only the common ones.
- **UI notes**: Hide missing methods; do not offer a method that is not listed (placing it gives 400 `paymentMethod`). Refresh when an SSE `catalog` event with `area: "PAYMENT_METHODS"` arrives.

### POST /api/v1/payments/webhook/{provider}
- **Who**: public in SecurityConfig, but **server-to-server only** (Razorpay calls it). Frontend must never call it.
- **Purpose / when the UI calls it**: Never from a UI. Razorpay posts payment events here with the header `X-Razorpay-Signature`; the only supported provider path value is `razorpay` (`cod` has no webhook).
- **Request**: raw JSON body exactly as Razorpay sent it (signature is computed on the raw bytes). Webhook URL to configure at Razorpay: `https://<your-domain>/api/v1/payments/webhook/razorpay` (events `payment.captured`, `payment.failed`).
- **Response**: 200 empty body on accepted or duplicate events.
- **Errors**:

| status | code | when |
|---|---|---|
| 400 | `BAD_SIGNATURE` | signature missing/invalid (or body unreadable) |
| 404 | `NOT_FOUND` | unknown provider name |

- **UI notes**: Nothing to implement. Document for ops only.

### GET /api/v1/admin/payment-methods
- **Who**: admin.
- **Purpose / when the UI calls it**: Admin "Payment methods" settings screen.
- **Request**: none.
- **Response** 200:

```json
[ { "code": "COD", "enabled": true, "configured": true }, { "code": "ONLINE", "enabled": false, "configured": false } ]
```

| field | type | meaning |
|---|---|---|
| `code` | `COD`/`ONLINE` | |
| `enabled` | bool | admin switch |
| `configured` | bool | for ONLINE: Razorpay key id and secret are both saved; COD is always true |

- **Errors**: only the common ones.
- **UI notes**: Disable the ONLINE toggle and show "Add Razorpay keys in Configuration" when `configured` is false.

### PUT /api/v1/admin/payment-methods
- **Who**: admin.
- **Purpose / when the UI calls it**: Save the on/off switches.
- **Request**: body is a JSON **array** of settings (can list one or both):

```json
[ { "code": "ONLINE", "enabled": true }, { "code": "COD", "enabled": true } ]
```

| field | type | required | validation | meaning |
|---|---|---|---|---|
| `code` | string | yes | must be `COD` or `ONLINE` else 400 field `code` "unknown method" | method |
| `enabled` | boolean | yes (absent = false) | enabling ONLINE needs configured keys | switch |

- **Response** 200: same array shape as the admin GET (re-read after save).
- **Errors**:

| status | code | when |
|---|---|---|
| 400 | `VALIDATION_FAILED` | unknown code; or the result would leave no method on (field `methods` "keep at least one payment method on") |
| 422 | `PAYMENT_NOT_CONFIGURED` | enabling ONLINE without Razorpay key id and secret |

- **UI notes**: Customers' apps are told through the live `catalog` event (`PAYMENT_METHODS`).

### GET /api/v1/admin/refunds
- **Who**: admin only.
- **Purpose / when the UI calls it**: Admin "Refunds" screen: money sent back to customers; the FAILED tab is the to-do list.
- **Request**: query `status` (optional: `PENDING`, `PROCESSING`, `ISSUED`, `FAILED`; case-insensitive; blank = all; other value = 400 field `status` "PENDING, PROCESSING, ISSUED or FAILED"), `page` (default 0), `size` (default 20, max 50). Newest first.
- **Response** 200: page of:

```json
{ "items": [ { "id": "…", "paymentId": "…", "orderId": "e3b0…", "amountPaise": 88000, "status": "FAILED", "attempts": 10,
               "lastError": "HttpClientErrorException", "reason": "order cancelled", "createdAt": "2026-11-08T10:30:00Z" } ],
  "page": 0, "size": 20, "total": 1 }
```

| field | type | meaning |
|---|---|---|
| `id` | UUID | refund id for retry |
| `paymentId`, `orderId` | UUID | related payment and order (orderId may be null if the payment row is gone) |
| `amountPaise` | long | amount refunded |
| `status` | string | `PENDING`/`PROCESSING`/`ISSUED`/`FAILED` |
| `attempts` | int | failed send attempts so far (max 10) |
| `lastError` | string or null | last provider error text (up to 500 chars) |
| `reason` | string | why (e.g. "order cancelled", "paid after expiry", "amount mismatch", "return <orderNo>") |
| `createdAt` | ISO instant | |

- **Errors**: 400 as above.
- **UI notes**: Show a badge count of FAILED refunds on the admin home; link order via `orderId`.

### POST /api/v1/admin/refunds/{id}/retry
- **Who**: admin only.
- **Purpose / when the UI calls it**: "Retry" on a FAILED refund after the cause (Razorpay outage, wrong keys) is fixed.
- **Request**: path `id` (refund UUID), no body.
- **Response**: 204 No Content. The refund returns to `PENDING` with attempts reset to 0 and is sent shortly.
- **Errors**: 409 `REFUND_NOT_FAILED` "Only a failed refund can be retried" (also returned for an unknown id, since the update matches no row).
- **UI notes**: Show retry only for `FAILED`; afterwards refetch the list.

---

# Public shop details and shop profile

### GET /api/v1/shop-settings
- **Who**: public (no token needed; listed in SecurityConfig permitAll for GET).
- **Purpose / when the UI calls it**: App start, shop/about/contact screen, checkout header, and to decide whether to enable the "Place order" button. Everything shown about the shop comes from here; nothing about the shop is hard-coded in the app.
- **Request**: optional header `If-None-Match: "<etag from last response>"`.
- **Response**: 200 with `ETag` header (quoted hex string) and `Cache-Control: no-cache` (so revalidate on each use), or **304 Not Modified with no body** when the ETag still matches. Body:

```json
{
  "shopName": "Vijay Super Mall", "phone": "9876543210", "address": "…", "promise": "Fresh every day", "details": "About text…",
  "logoUrl": "/img/shop/abc_400.webp", "email": "shop@example.com", "whatsapp": "919876543210", "mapUrl": "https://maps.example/…",
  "website": "https://…", "instagram": "https://…", "facebook": "https://…",
  "hours": [ { "day": "MON", "open": "09:00", "close": "21:00", "closed": false }, { "day": "SUN", "open": "00:00", "close": "00:00", "closed": true } ],
  "holidayNote": "Closed on Diwali", "openNow": true, "todayHours": "09:00-21:00",
  "onlineOrdersOpen": true, "onlineOrdersMessage": "", "timezone": "Asia/Kolkata"
}
```

| field | type | meaning |
|---|---|---|
| `shopName`, `phone`, `address`, `promise` | string | from admin Shop settings; `promise` is the tagline, `""` if none |
| `details` | string | longer "about the shop" text, `""` if none |
| `logoUrl` | string | relative image address of the small (400 px) logo, `""` when none (swap `_400` for `_1000` for the large one; prefix with the API host) |
| `email`, `whatsapp`, `mapUrl`, `website`, `instagram`, `facebook` | string | contact links, `""` when not set; links are https URLs |
| `hours[]` | array of 7 | Monday to Sunday: `day` (`MON`..`SUN`), `open`/`close` as `HH:mm` 24 h shop time, `closed` bool (a closed day has `00:00`/`00:00`) |
| `holidayNote` | string | message shown on special closures |
| `openNow` | bool | computed by the server right now in shop time (`Asia/Kolkata`) from today's hours (open <= now < close) |
| `todayHours` | string | `"09:00-21:00"` or `"Closed"` |
| `onlineOrdersOpen` | bool | false when the admin paused online orders |
| `onlineOrdersMessage` | string | the admin's message when paused (`""` when open); show it as is |
| `timezone` | string | always the shop's zone id (`Asia/Kolkata`) |

- **Errors**: only 429/500.
- **UI notes**: Send `If-None-Match` with the stored ETag; on 304 reuse the cached body. `openNow` is part of the ETag, so the response changes when the shop opens/closes. Refetch also on an SSE `catalog` event with `area: "SHOP"`. `openNow` is informational (the shop being closed does not block orders); only `onlineOrdersOpen == false` blocks ordering: disable checkout and show `onlineOrdersMessage`. If the server still gets an order while paused it answers 422 `ORDERS_PAUSED`.

### GET /api/v1/admin/shop-profile
- **Who**: admin.
- **Purpose / when the UI calls it**: Admin "Shop profile" screen (logo, contact links, hours, pause switch). Name, address, phone and billing details are edited elsewhere (`/admin/shop-settings`, documented in the POS/other guide).
- **Request**: none.
- **Response** 200:

```json
{ "logoUrl": "/img/shop/abc_400.webp", "email": "", "whatsapp": "", "mapUrl": "", "website": "", "instagram": "", "facebook": "",
  "hours": [ { "day": "MON", "open": "09:00", "close": "21:00", "closed": false } ],
  "holidayNote": "", "onlineOrdersEnabled": true, "onlineOrdersMessage": "", "updatedAt": "2026-11-08T10:00:00Z" }
```

Fields as in the public view plus `onlineOrdersEnabled` (bool; the pause switch) and `updatedAt` (ISO instant or null). `hours` is an empty array if never saved.

- **Errors**: only the common ones.
- **UI notes**: Seven-day editor with a "closed" checkbox per day.

### PUT /api/v1/admin/shop-profile
- **Who**: admin.
- **Purpose / when the UI calls it**: Save contact links, opening hours, holiday note and the **pause online orders** switch. The logo has its own calls. This is a full replace of these fields: any text field left out or sent blank is cleared, and `hours` must always be sent.
- **Request**: body:

```json
{ "email": "shop@example.com", "whatsapp": "+919876543210", "mapUrl": "https://maps.example/x", "website": "https://example.com",
  "instagram": "https://instagram.com/x", "facebook": "https://facebook.com/x",
  "hours": [ { "day": "MON", "open": "09:00", "close": "21:00", "closed": false }, … 7 entries … ],
  "holidayNote": "Closed on Diwali", "onlineOrdersEnabled": false, "onlineOrdersMessage": "Back tomorrow at 9 AM" }
```

| field | type | required | validation (all errors 400 `VALIDATION_FAILED`, field named) | meaning |
|---|---|---|---|---|
| `email` | string | no | trimmed; max 200; must look like `a@b.c` if not blank | contact e-mail |
| `whatsapp` | string | no | max 20; spaces removed; regex `^\+?[0-9]{10,15}$` if not blank | WhatsApp number |
| `mapUrl`, `website`, `instagram`, `facebook` | string | no | max 300 each; if not blank must start with `https://` (case-insensitive) | links |
| `hours` | array | **yes** | exactly 7 entries, each day `MON`..`SUN` exactly once (case-insensitive; stored upper-case, sorted Mon to Sun) else field `hours` "give all seven days" / "each day MON to SUN exactly once" | opening hours |
| `hours[].open`, `close` | string | when `closed` is false | regex `^([01]\d|2[0-3]):[0-5]\d$` (`HH:mm`); open must be strictly before close (no overnight spans) else field `hours` "<DAY>: …" | times |
| `hours[].closed` | bool | no (default false) | when true, times are ignored and stored as `00:00` | closed all day |
| `holidayNote` | string | no | max 200 | holiday message |
| `onlineOrdersEnabled` | bool | no | **null or omitted = true (ordering on)**; send `false` to pause | pause switch |
| `onlineOrdersMessage` | string | no | max 200; shown to customers while paused; blank uses the default "We are not taking online orders right now. Please visit the shop or try again later." | pause message |

- **Response** 200: the saved profile (same shape as the admin GET).
- **Errors**: 400 `VALIDATION_FAILED` only (rules above).
- **UI notes**: Pre-fill the form from the admin GET and send everything back (so nothing is accidentally cleared). Make the pause switch prominent with a confirmation; while paused, customers get 422 `ORDERS_PAUSED` when placing. The change reaches customer apps through the `catalog` live event (`SHOP`) and on next fetch of `/shop-settings`.

### POST /api/v1/admin/shop-profile/logo
- **Who**: admin.
- **Purpose / when the UI calls it**: Upload or replace the logo.
- **Request**: `multipart/form-data` with part `file` (an image). Empty file = 400 field `file` "file is empty". Other format/size limits come from the image store (a bad image gives 400 `VALIDATION_FAILED`; a file over the multipart limit gives 400 "File too large"). The server stores two sizes.
- **Response** 200: the saved profile (new `logoUrl`, the small version).
- **Errors**: 400 `VALIDATION_FAILED`.
- **UI notes**: Show a preview from `logoUrl`; the large version is the same address with `_400` replaced by `_1000`.

### DELETE /api/v1/admin/shop-profile/logo
- **Who**: admin.
- **Purpose / when the UI calls it**: Remove the logo.
- **Request**: none.
- **Response** 200: the saved profile with `logoUrl` `""`.
- **Errors**: only the common ones.
- **UI notes**: Confirm first.

---

# Admin configuration screen

The config screen is generic: the server lists every adjustable setting with its type and limits, so the UI is built by looping over the response. No setting keys need to be hard-coded.

### GET /api/v1/admin/config
- **Who**: admin only.
- **Purpose / when the UI calls it**: Open the "Configuration" screen (payment keys, push, SMS, e-mail, logging, caching, delivery, counter rules, GST, security).
- **Request**: none.
- **Response** 200:

```json
{
  "groups": [
    { "group": "PAYMENT", "label": "Online payment",
      "items": [
        { "key": "razorpay.keyId", "group": "PAYMENT", "groupLabel": "Online payment", "type": "STRING", "label": "Razorpay key id",
          "help": "From the Razorpay dashboard …", "secret": false, "set": true, "value": "rzp_test_xxxxxxxx", "source": "DATABASE",
          "allowed": [], "min": null, "max": null, "restart": false },
        { "key": "razorpay.keySecret", "group": "PAYMENT", "groupLabel": "Online payment", "type": "SECRET", "label": "Razorpay key secret",
          "help": "…", "secret": true, "set": true, "value": null, "source": "DATABASE", "allowed": [], "min": null, "max": null, "restart": false },
        { "key": "payment.onlineTimeoutMinutes", "group": "PAYMENT", "groupLabel": "Online payment", "type": "INT", "label": "Minutes to pay an online order",
          "help": "…", "secret": false, "set": true, "value": "15", "source": "DEFAULT", "allowed": [], "min": 5, "max": 120, "restart": false }
      ] }
  ],
  "issues": [ "Razorpay needs both the key id and the key secret: online payment stays off until both are saved." ]
}
```

| field (per item) | type | meaning |
|---|---|---|
| `key` | string | stable setting id; use it in the save request |
| `group`, `groupLabel` | string | grouping (the response is already grouped; groups appear in this order: PAYMENT, PUSH, SMS, EMAIL, LOGGING, CACHE, DELIVERY, COUNTER, GST, SECURITY) |
| `type` | `STRING`/`SECRET`/`INT`/`BOOLEAN`/`ENUM` | selects the input widget |
| `label`, `help` | string | field label and helper text (`help` may be empty) |
| `secret` | bool | true for `SECRET` type |
| `set` | bool | a value is in force (from database, environment or default). For secrets this is the only thing the server reveals |
| `value` | string or **null** | current effective value as text; **always null for secrets**; numbers and booleans are strings (`"15"`, `"true"`) |
| `source` | `DATABASE`/`ENVIRONMENT`/`DEFAULT` | where the effective value comes from (admin-saved; server environment; built-in) |
| `allowed` | string[] | the only valid values for `ENUM` and `BOOLEAN` (`["true","false"]`); empty otherwise |
| `min`, `max` | long or null | inclusive limits, only for `INT` |
| `restart` | bool | true if a change only applies after a server restart (currently false for every setting; show "restart required" if it ever is true) |

Rendering rules:

- `STRING`: text field showing `value`. `SECRET`: password-style field, never prefilled; show a "Saved" chip when `set` is true and a placeholder "leave blank to keep". `INT`: number field with `min`/`max`. `BOOLEAN`: switch (`"true"`/`"false"`). `ENUM`: dropdown from `allowed` (e.g. `log.retentionDays` is an ENUM with values `"7"`, `"15"`, `"30"`).
- `issues` is a list of plain-language warnings (half-entered Razorpay keys, missing webhook secret, SMS/e-mail misconfiguration, undecryptable secrets, missing `CONFIG_ENCRYPTION_KEY`). Show them as a banner at the top.
- Groups and settings known today (keys): PAYMENT `razorpay.keyId`, `razorpay.keySecret`, `razorpay.webhookSecret`, `payment.onlineTimeoutMinutes` (INT 5..120, default 15); PUSH `fcm.serviceAccount` (SECRET, the whole Firebase service-account JSON); SMS `sms.channel` (ENUM `none`/`console`/`http`), `sms.http.url`, `sms.http.method` (ENUM `POST`/`GET`), `sms.http.headers` (SECRET JSON object), `sms.http.body`, `sms.senderId`, `sms.dltTemplateId`, `sms.message`; EMAIL `otp.channel` (ENUM `console`/`email`), `smtp.host`, `smtp.port` (INT 1..65535), `smtp.user`, `smtp.password` (SECRET), `otp.from`; LOGGING `log.level.app`, `log.level.root`, `log.level.sql`, `log.retentionDays`; CACHE `cache.enabled` (BOOLEAN), and one INT per cache `cache.ttl.<cacheName>` (0..3600 seconds, 0 = do not keep); DELIVERY `delivery.feePaise` (INT 0..1,000,000, paise, 0 = free), `delivery.freeAbovePaise` (INT 0..100,000,000, paise); COUNTER `pos.cashierReturnDays` (0..365), `pos.cashierDiscountPercent` (0..100), `pos.requireOpenShift` (BOOLEAN); GST `gst.roundOff` (ENUM `NONE`/`NEAREST_RUPEE`); SECURITY `security.clientIpHeader`. Money fields are in paise: show them as rupees in the UI but send paise.
- The UI should still render unknown future keys generically from `type`.

- **Errors**: only the common ones.
- **UI notes**: Keep the original response to compute which items the user actually changed.

### PUT /api/v1/admin/config
- **Who**: admin only.
- **Purpose / when the UI calls it**: "Save" on the configuration screen. Takes effect immediately (no restart), on every server within about 30 seconds.
- **Request**: body is `{ "changes": [ { "key": "...", "value": "..." } ] }`. Send **only the settings the admin edited**.

```json
{ "changes": [ { "key": "razorpay.keyId", "value": "rzp_test_abc" }, { "key": "razorpay.keySecret", "value": "…typed secret…" },
               { "key": "delivery.feePaise", "value": "4000" }, { "key": "sms.http.url", "value": "" } ] }
```

| field | type | required | validation | meaning |
|---|---|---|---|---|
| `changes` | array | yes | `@NotEmpty` (empty = 400 field `changes`) | list of edits |
| `changes[].key` | string | yes | must be a known key else 400 field `key` "unknown setting: <key>"; each key at most once else 400 "listed twice" | setting |
| `changes[].value` | string | no (null = blank) | **blank (empty or spaces) clears the setting**, which returns to the server's environment value or built-in default. Otherwise: trimmed; at most the item's max length (e.g. 100/200/500; reported in the error); no control characters (newline/tab allowed); `INT` must be a whole number within `min..max`; `BOOLEAN`/`ENUM` must match one of `allowed` (case-insensitive, stored in canonical case) | new value as a string |

Extra per-key rules (errors are 400 with the key as the field name): `sms.http.url` must start with `https://` (or `http://localhost` / `http://127.0.0.1`); `sms.http.headers` must be a JSON object; `fcm.serviceAccount` must be JSON containing non-empty `project_id`, `client_email`, `private_key`; `razorpay.keyId` must start with `rzp_`; `security.clientIpHeader` must match `[A-Za-z0-9-]+`.

Saving semantics: **atomic**: all changes are validated first; if any is invalid, nothing is saved and the first error is returned. Valid changes are saved in one transaction. A secret that is not listed is untouched; a secret listed with a blank value is **deleted**. Saving a value equal to the stored one is a no-op. Each change is audited without revealing secret values.

- **Response** 200: the full configuration view (same shape as GET), reflecting the saved state. Replace the screen state with it.
- **Errors**:

| status | code | when |
|---|---|---|
| 400 | `VALIDATION_FAILED` | empty list; unknown/duplicate key; value fails a rule above. `details.fields` = `{ "<key>": "<Label> <reason>" }` (e.g. "Delivery fee (paise) must be between 0 and 1000000") |
| 422 | `CONFIG_KEY_MISSING` | a non-blank secret is being saved but the server has no `CONFIG_ENCRYPTION_KEY` (see bootstrap) |

- **UI notes**: Track dirty fields per item; never send untouched secret fields (they have `value: null`, and sending an empty string would erase them). Provide a separate "Clear" action for secrets that sends `""`. Disable Save when nothing changed. After a successful save, offer "Test" buttons (below). Saving a cache TTL or `cache.enabled` empties all caches.

### GET /api/v1/admin/config/bootstrap
- **Who**: admin only.
- **Purpose / when the UI calls it**: A read-only "Server start-up settings" panel showing whether the settings needed before the database opens are safe. They are set in the server environment and cannot be edited from the app.
- **Request**: none.
- **Response** 200: array (values never shown):

```json
[ { "name": "Sign-in signing secret", "setBy": "JWT_SECRET", "state": "OK", "detail": "set" },
  { "name": "Fixed login code", "setBy": "OTP_FIXED_CODE", "state": "UNSAFE", "detail": "ON: every login code is the same. Never in production" } ]
```

| field | type | meaning |
|---|---|---|
| `name` | string | readable name (database address, DB user/password, sign-in secret, OTP pepper, fixed login code, secrets encryption key, private metrics port, admin e-mail, image folder, server mode) |
| `setBy` | string | environment variable name that controls it |
| `state` | `OK`/`DEFAULT`/`MISSING`/`UNSAFE` | colour it green/grey/amber/red; `UNSAFE` and `MISSING` need action |
| `detail` | string | plain-language explanation (no secret values) |

- **Errors**: only the common ones.
- **UI notes**: Show a red badge on the Configuration menu when any item is `UNSAFE` or `MISSING`. The "Secrets encryption key" item being `MISSING` explains why saving secrets fails with `CONFIG_KEY_MISSING`.

### POST /api/v1/admin/config/test/{target}
- **Who**: admin only.
- **Purpose / when the UI calls it**: The "Test" buttons. They use the **saved** values (save first), try them for real, and say whether they work.
- **Request**: path `target` is one of `razorpay`, `fcm`, `sms`, `email` (other values = 404 `NOT_FOUND` "Test not found"). Body optional: `{ "args": { … } }`, `args` is a string map:

| target | args | what it does |
|---|---|---|
| `razorpay` | none | calls Razorpay with the saved keys; reports test/LIVE mode and whether the webhook secret is missing |
| `fcm` | none | signs in to Firebase with the saved service account and validates (does not deliver) a message |
| `sms` | `mobile`: exactly 10 digits (`[0-9]{10}`) | sends a real test SMS through the saved provider (requires `sms.channel` = `http` and a URL) |
| `email` | `to`: e-mail address | sends a real test e-mail (requires `otp.channel` = `email`, SMTP host and From saved) |

```json
{ "args": { "mobile": "9876543210" } }
```

- **Response** 200 always (even when the test fails):

```json
{ "ok": false, "message": "Razorpay refused the keys (wrong key id or secret)." }
```

| field | type | meaning |
|---|---|---|
| `ok` | bool | the check passed |
| `message` | string | plain-language result to show in a toast/dialog |

- **Errors**: 404 `NOT_FOUND` for an unknown target.
- **UI notes**: Ask for the mobile/e-mail in a small dialog for sms/email. Show `message` in green when `ok`, red otherwise. Disable the button while running (these call external services and may take seconds).

### GET /api/v1/admin/logs/status
- **Who**: admin only.
- **Purpose / when the UI calls it**: A "Logs" panel beside the Logging settings: which levels are active and which log files exist. Log contents are never returned.
- **Request**: none.
- **Response** 200:

```json
{ "levelApp": "INFO", "levelRoot": "INFO", "levelSql": "OFF", "retentionDays": 30, "fileLogging": true, "folder": "/var/log/mall",
  "files": [ { "name": "mall.2026-11-08.log", "sizeBytes": 123456, "lastModified": "2026-11-08T10:00:00Z" } ] }
```

| field | type | meaning |
|---|---|---|
| `levelApp`, `levelRoot`, `levelSql` | string | effective levels now (`levelSql` is `OFF` unless SQL logging is on) |
| `retentionDays` | int | days of rotated files kept |
| `fileLogging` | bool | whether a log file is being written |
| `folder` | string or null | server folder holding logs |
| `files` | array | up to 60 newest files: `name`, `sizeBytes`, `lastModified` |

- **Errors**: only the common ones.
- **UI notes**: Change levels through `PUT /admin/config` (`log.level.*`), then refresh this panel.

---

# Caches

### GET /api/v1/admin/cache
- **Who**: admin only.
- **Purpose / when the UI calls it**: "Caching" screen: shows what the server keeps in memory and how well it works. Products, prices with stock, carts and orders are never cached.
- **Request**: none.
- **Response** 200: array, one entry per cache:

```json
[ { "name": "shop", "description": "Shop name, address, phone, billing details …", "ttlSeconds": 300, "entries": 1,
    "hits": 1200, "misses": 4, "bypassed": 0, "clears": 2, "lastClearedAt": 1762596000000 } ]
```

| field | type | meaning |
|---|---|---|
| `name` | string | cache key; one of `shop`, `shop-profile`, `categories`, `banners`, `units`, `coin-settings`, `coin-rules`, `payment-methods`, `coupons`, `dashboard`, `report-summary` |
| `description` | string | what it holds |
| `ttlSeconds` | int | how long values are kept; 0 when caching is switched off or the TTL is 0 |
| `entries` | int | items held |
| `hits`, `misses`, `bypassed`, `clears` | long | counters since server start (`bypassed` = reads that skipped the cache because it is off) |
| `lastClearedAt` | long or null | epoch milliseconds of the last clear; null if never |

- **Errors**: only the common ones.
- **UI notes**: Hit ratio = hits / (hits + misses). Counters are per server instance.

### POST /api/v1/admin/cache/refresh
- **Who**: admin only.
- **Purpose / when the UI calls it**: "Hard refresh" button: empties every cache and reloads the config screen values, so the next requests read fresh data. Use after editing data directly in the database or when something looks stale.
- **Request**: no body.
- **Response** 200: the stats array (as above) after clearing.
- **Errors**: only the common ones.
- **UI notes**: Confirm first (briefly slows the next requests). Audited.

### POST /api/v1/admin/cache/refresh/{name}
- **Who**: admin only.
- **Purpose / when the UI calls it**: Per-row refresh button on the caching screen.
- **Request**: path `name`: one of the cache names above, or `settings` to reload the configuration values from the database.
- **Response** 200: the stats array.
- **Errors**: 404 `NOT_FOUND` ("Cache not found") for an unknown name.
- **UI notes**: Use the `name` values from `GET /admin/cache`.

---

# Live events reference (SSE)

Both streams (`GET /api/v1/orders/stream`, `GET /api/v1/admin/orders/stream`) use the same format.

Connection rules:

- Authentication when opening: `Authorization: Bearer <token>` or, for browsers only, `?access_token=<token>`. Role: customer stream = any signed-in user; admin stream = ADMIN/CASHIER.
- A stream ends by itself after **10 minutes** (shorter than the 15 minute token). A stream that cannot keep up (more than 1000 queued messages) is closed by the server. Events are in memory and **not replayed**; the server is single-instance for streams. A comment line `: keepalive` is sent every 25 s (ignore it; it keeps proxies from closing the connection).
- On every disconnect, reconnect with a valid (refreshed) token, and after the new `ready` event reload the screens you show (orders list, wallet, catalog parts). Do not hammer: use exponential back-off.
- Limits: 5 streams per customer (6th evicts the oldest), 200 staff streams.

Events (`event:` name, then `data:` is JSON):

| event | sent to | data | what the UI does |
|---|---|---|---|
| `ready` | the connecting client | `{"connected":true}` | mark live; reload screens once |
| `order` | the order's customer; all staff streams | `{ "orderId", "orderNo", "status", "previousStatus", "at" }`. `previousStatus` is null for a newly placed order. Also sent with `status == previousStatus` when something on the order changed without a status change (payment recorded, return recorded). SSE `id` is `<orderId>:<status>` | refetch that order (and the list); show a toast for status changes; for pending payments this is the confirmation signal |
| `wallet` | that customer's streams only | `{ "userId", "reason", "delta", "at" }` (`delta` in coins) | refetch the coin balance/ledger |
| `credit` | that customer's streams only | `{ "customerId", "reason", "deltaPaise", "at" }` | refetch the credit (udhar) account |
| `stock` | every stream | `{ "productIds": [UUID…], "reason": "…", "at" }`; empty `productIds` means many products changed (also used when over 200 products changed) | refetch the visible product lists or just those products' stock |
| `catalog` | every stream | `{ "area": "CATALOG" or "COUPONS" or "UNITS" or "SHOP" or "PAYMENT_METHODS", "at" }` | refetch that area: CATALOG = products/categories/banners; COUPONS = `GET /coupons`; UNITS = `GET /units`; SHOP = `GET /shop-settings`; PAYMENT_METHODS = `GET /payment-methods` |

`stock` and `catalog` events are merged and sent at most twice per second (one message per window and area). None of this requires a new login.

---

# Flows

## Flow A: quote, place, pay, track, cancel/return (customer)

1. **Load checkout data**: `GET /payment-methods` (which of COD/ONLINE), `GET /shop-settings` (use ETag; check `onlineOrdersOpen`), customer addresses (see file 10), `GET /cart` (restore basket).
2. **Quote**: `POST /cart/quote` with current lines, `couponCode`, `coinMode`/`percent`, `fulfilment`. Show lines, discounts, delivery fee, total, `coinsToEarn`, and `warnings`. Re-quote on every change. Optionally `PUT /cart` to save the basket.
3. **Place**: customer picks address (DELIVERY) and payment method. Generate `Idempotency-Key` (UUID). `POST /orders` with the same cart plus `paymentMethod`, `addressId`, `expectedTotalPaise` = last quoted `totalPaise`, optional `slot`.
   - 409 `PRICE_CHANGED`: take `details.quote`, show the new total, ask the customer to confirm, post again with the new `expectedTotalPaise`.
   - 409 `OUT_OF_STOCK`: show which items, re-quote.
   - 422 `ORDERS_PAUSED`: show message.
   - Timeout/no response: retry with the **same** key; you get the same order back.
4. **After 201**: `DELETE /cart`; open the order screen.
5. **Pay (ONLINE only)**: if `status == PAYMENT_PENDING`, open Razorpay Checkout with `paymentSession.payload`. After the SDK says success, show "Confirming payment" and wait for the SSE `order` event for that `orderId` or poll `GET /orders/{id}` until `status != PAYMENT_PENDING` (expect `PLACED` + `paymentStatus: CAPTURED`). On SDK failure/dismiss: offer Retry (reopen with `paymentSession` from `GET /orders/{id}`) or Cancel (`POST /orders/{id}/cancel`). If it is never paid the server cancels the order after the payment timeout (default 15 minutes).
6. **Track**: keep `GET /orders/stream` open; on `order` events refetch `GET /orders/{id}`. Status steps: PLACED, CONFIRMED, PACKED, then OUT_FOR_DELIVERY, DELIVERED (delivery) or READY_FOR_PICKUP, PICKED_UP (pickup). A `CANCELLED` order with `cancelReason` "Rejected by shop: …" was refused by the shop; a refund (if paid) follows automatically. When DELIVERED/PICKED_UP, `coinsEarned` is credited (a `wallet` event follows).
7. **Cancel**: while `PAYMENT_PENDING`, `PLACED` or `CONFIRMED`: `POST /orders/{id}/cancel` with optional `reason`. After `PACKED` the customer cannot cancel; tell them to contact the shop.
8. **Return**: customers cannot request returns in the app API. After delivery the shop records returns; the customer sees them via `GET /orders/{id}/returns` (refund amount, mode, coins adjusted). Reorder via `GET /orders/{id}/reorder`.

## Flow B: staff order handling (cashier/admin)

1. Open `GET /admin/orders/stream` and load `GET /admin/orders?status=PLACED`.
2. On a new `order` event refetch the list; open `GET /admin/orders/{id}`.
3. **Accept**: `POST /admin/orders/{id}/status {"status":"CONFIRMED"}` (stock is deducted now; 409 `OUT_OF_STOCK` means reject it instead). Then PACKED, then OUT_FOR_DELIVERY / READY_FOR_PICKUP, then DELIVERED / PICKED_UP.
4. **Reject**: `POST /admin/orders/{id}/reject {"reason":"…"}` (customer told; stock/coins/coupon/money returned).
5. **Return after delivery**: `POST /admin/orders/{id}/returns` with a new `Idempotency-Key`; show `refundPaise`/`refundMode`.
6. `PAYMENT_PENDING` orders cannot be confirmed; wait for payment or reject.

## Flow C: admin configuration

1. `GET /admin/config/bootstrap` for start-up health; `GET /admin/config` to build the form from `groups[].items[]` using `type`, `allowed`, `min`/`max`, `secret`/`set`, `source`, `restart`; show `issues` on top.
2. Collect only edited items into `changes`; `PUT /admin/config`. On 400 highlight the field named in `details.fields` (the key). On success replace the form state with the returned view.
3. Use `POST /admin/config/test/{target}` to verify Razorpay, Firebase, SMS and e-mail. Then enable ONLINE in `PUT /admin/payment-methods`.
4. To pause orders, use `PUT /admin/shop-profile` with `onlineOrdersEnabled:false` and a message. For stale data use `POST /admin/cache/refresh`.

---

## Things the code does not settle (flagged, not guessed)

- The order response now carries `paymentExpiresAt` (ISO instant) while the order is `PAYMENT_PENDING`; it is `null` otherwise. Show a countdown to it; the server cancels the order when it passes.
- Image upload limits for the logo (accepted formats, max size) are enforced by the shared image store, not read here.
- `restart` is currently always false.


---
## Addendum: order response field and returns on split flash-sale lines

- `paymentExpiresAt` (Instant|null): present on the order object only while `status` is `PAYMENT_PENDING` (online order waiting for payment). Use it for the "pay within mm:ss" countdown. After it passes the server cancels the order and releases everything.
- When a flash sale splits one product into two order lines (sale price and list price), `POST /admin/orders/{id}/returns` still takes one entry per product: `{ "productId", "qty" }`. Units are taken from the order's lines for that product in the order they were sold (the flash-sale line first, then the normal-price line). `RETURN_EXCEEDS_SOLD` reports `returnable` as the total left across both lines.


---
## Addendum: wrong-amount and duplicate online payments

Server-side behaviour (the app does not call anything new; it only has to react to the notifications and keep the order screen refreshed):

- **Wrong amount**: when the provider reports a captured payment whose amount differs from the order total, the order is **not** marked paid and stays `PAYMENT_PENDING`. The money received is refunded in full to the customer's original payment method, automatically. The customer can pay again on the same order until `paymentExpiresAt`; a later correct payment is accepted normally.
- **Duplicate payment**: a second captured payment (different provider payment id) on an order that is already paid is refunded in full; the first payment stands and the order is unaffected.
- **Notifications** (push + in-app, produced by the server): to the customer, title `Payment amount did not match` ("We received Rs 400.50 instead of Rs 500.00 for order VSM-5. It is being refunded to your original payment method. Please pay again." - the last sentence only while the order is still waiting for payment) or `Duplicate payment` ("We received a second payment of Rs 25.00 for order VSM-7. It is being refunded to your original payment method."); to all staff, title `Payment problem`. Open the order screen when the notification is tapped and re-fetch it.
- **UI**: on an order in `PAYMENT_PENDING` keep the "Pay now" button available until `paymentExpiresAt`; after a wrong-amount notice the same button works again.

### GET /api/v1/admin/payments/anomalies
- **Who**: admin only. **Purpose**: the "payment problems" list for the owner.
- **Request**: query `page` (default 0), `size` (default 20, max 50).
- **Response**: `{ items: [ { id, paymentId, orderId, kind ("MISMATCH"|"DUPLICATE"), expectedPaise, receivedPaise, providerRef, createdAt } ], page, size, total }`, newest first. The refunds they caused are in the refund monitor (`GET /api/v1/admin/refunds`) with reason `amount mismatch` or `duplicate payment`.
- **Ops**: metric `mall_payments_anomalies_total{kind}`; alert `PaymentAnomaly`.
