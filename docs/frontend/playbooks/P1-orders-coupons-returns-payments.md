# P1. Orders, coupons, coins, returns and payments: implementation playbook

Audience: a developer or an LLM coding agent who must build the **customer app** screens (cart, coupons, checkout, payment, order tracking, cancel, reorder, returns view) and the **admin / cashier order desk** (order list, detail, status moves, reject, returns with preview, refunds queue, payment problems) **without reading backend code**.

How this file was made: every endpoint, field, limit and message below was read from the controllers, request and response records, validators, services and migrations, and cross-checked with the integration tests (`OrderApiIT`, `OnlinePaymentIT`, `OrderReturnIT`, `OrderCancelIT`, `CouponItemReturnIT`, `FlashSaleOnlineIT`, `FrontendRoundThreeBIT`, ...). Where the older guides (`00`, `10`, `20`, `40`) disagree with the code, the code wins and the difference is listed in section 14.

Read `00-overview.md` once for the shared conventions (error envelope, paging, idempotency, auth refresh). The ones this playbook depends on are repeated in section 0 so you can work from this file alone.

## Table of contents

0. Shared conventions and building blocks
1. Cart: saved basket, quote, warnings, limits
2. Coupons: list, picker, applying a code, targeting, reason codes
3. Coins: modes, percent, warnings
4. Flash sale prices inside quotes
5. Placing an order (COD or ONLINE)
6. Online payment: gateway session, result, timeout, wrong amount, duplicates
7. Order tracking: statuses, timeline, live events, notifications
8. Customer cancel, rejection and cancel info
9. Reorder
10. Returns: customer view, admin flow, preview, refund maths, policies, defective review
11. Refunds queue and payment problems (admin)
12. Admin order desk: list, filters, detail, transitions, reject, notes
13. Wallet ledger effects of orders and returns
14. Doc vs code differences
15. Dart building blocks (shared models)

Each of sections 1 to 13 uses the same eight parts: 1 Goal; 2 Who and settings; 3 Screens and fields; 4 API sequence; 5 State machines and derived values; 6 Edge cases; 7 Dart model sketches; 8 Acceptance checklist.

---

## 0. Shared conventions and building blocks

| Topic | Rule |
|---|---|
| Base path | `/api/v1`. JSON UTF-8. Bearer token on everything except `GET /shop-settings`, `GET /coupons`, catalog reads and the payment webhook. |
| Money | Integer **paise** in fields ending `Paise`. Never use `double`. `12500` shows as `Rs 125.00`. |
| Times | ISO-8601 UTC (`2026-11-09T10:15:30Z`) in responses. Parse as UTC, show in `Asia/Kolkata`. |
| Ids | UUID strings for API paths. `orderNo` is a display string (it can look like `1001` or `VSM-1001`; show it as given). |
| Error body | `{ "code", "message", "traceId", "details": {...} }`. Switch on `code`. Validation errors: HTTP 400 `VALIDATION_FAILED` with `details.fields["<field>"] = "<text>"`. |
| Roles | Customer endpoints: any signed-in token. `/admin/orders/**`: ADMIN or CASHIER. `/admin/refunds`, `/admin/payments/**`, `/admin/payment-methods`, `/admin/config`: ADMIN only (a cashier gets 403). `/pos/stock/defective**`: CASHIER or ADMIN. |
| Paging | `{ items, page, size, total, totalExact, hasMore }`. `page` from 0, `size` default 20, clamped 1..50. Page until `hasMore == false`. `total` caps at 10000 (`totalExact:false` means show "10,000+"). |
| Idempotency-Key | Required header on `POST /orders` and `POST /admin/orders/{id}/returns`. New random UUID per user action, **same key on every retry of that action**, never reuse for another action. Missing header: 400 with `details.fields["Idempotency-Key"]`. |
| 401 | Refresh the token once, retry the call once, then go to sign-in. |
| 429 | `RATE_LIMITED` with `Retry-After` header (= `details.retryAfterSeconds`). Wait; do not loop. |
| 503 `SERVICE_BUSY` | Retry after `Retry-After`. |

### 0.1 One API client rule set (implement once)

1. Attach `Authorization`. 2. On 401 refresh once. 3. Parse the error envelope into `ApiError(code, message, traceId, details)`. 4. Expose `fields` (`details.fields`) for forms. 5. Never parse `message` to make decisions; use `code` and `details`. 6. Show `traceId` on unexpected errors. 7. For mutating calls that need `Idempotency-Key`, the caller creates the key and passes it in, so a retry re-sends the same key.

### 0.2 Things the app must never compute

Totals, discounts (coupon, coin, flash), delivery fee, coins to earn, refund amounts, coupon shares, payment success, whether a coupon applies, remaining returnable quantity beyond what section 10 describes. Always render server numbers.

### 0.3 Settings that change behaviour (all server-side; the app only reads their effects)

| Setting (admin config key) | Default | Effect |
|---|---|---|
| `delivery.feePaise` | `0` | Delivery fee. Default 0 means delivery is free until the owner sets a fee. |
| `delivery.freeAbovePaise` | `49900` | Delivery is free when subtotal - coupon - coin discount is at least this. Only matters if the fee is above 0. |
| `payment.onlineTimeoutMinutes` | `15` (5..120) | Time to pay an ONLINE order. Reaches the app as `paymentExpiresAt`. |
| `coupon.allowOnFlashItems` | `true` | If `false`, coupons ignore flash sale lines (reason `FLASH_ITEMS`). |
| `returns.couponPolicy` | `KEEP` | `KEEP` or `CLAWBACK_BELOW_MINIMUM` (section 10). |
| Payment method switches (`/admin/payment-methods`) | COD on, **ONLINE off** | `GET /payment-methods` lists only usable methods. ONLINE also needs Razorpay key id and secret. |
| Coin settings (`paisePerCoin`, `maxRedeemPercent`, `expiryMonths`) | admin set | Coin maths (section 3). |
| Online ordering pause (`/admin/shop-profile` `onlineOrdersEnabled`) | on | When paused, placing answers 422 `ORDERS_PAUSED`. Read `onlineOrdersOpen` and `onlineOrdersMessage` from `GET /shop-settings` first. |

---

## 1. Cart: saved basket, quote, warnings, limits

### 1.1 Goal
Show the customer what they will pay: the server cart (`/cart`) follows them across devices, the quote (`/cart/quote`) is the only trusted price breakdown. Nothing is reserved or stored by a quote.

### 1.2 Who and settings
Any signed-in customer. Needs: catalog product ids (pack ids), optional coupon code, coin choice, fulfilment. Delivery fee settings from 0.3.

### 1.3 Screens and fields

Cart screen fields (what the app holds and sends):

| Field | Type | Required | Validation mirrored from the server |
|---|---|---|---|
| `items[]` | list | yes (quote, place); yes for `PUT /cart` (may be empty) | Quote/place: not empty. Saved basket: at most **100 lines**. |
| `items[].productId` | UUID | yes | Must be an **active, customer-visible** product (not counter-only). Else 400 `fields.productId` = `product is not available: <id>`. |
| `items[].qty` | int | yes | **1..99 per product**. Quote/place: 0, negative, or over 99 gives 400 `fields.qty` = `quantity must be 1 to 99`. The same product listed twice is **merged** and the limit applies to the sum (60 + 60 is 400). |
| `couponCode` | string | no | Blank is none. Server trims and upper-cases. See section 2. |
| `coinMode` | `NONE` / `ALL` / `PERCENT` | no | Null is `NONE`. Bad word: 400 `fields.coinMode`. |
| `percent` | int | no | Only for `PERCENT`. Null means **100**. Clamped to 0..100 by the maths. In the saved basket a `percent` outside 0..100 is a 400. |
| `fulfilment` | `DELIVERY` / `PICKUP` | no | Null is `DELIVERY`. `PICKUP` has no delivery fee. |

Screen elements: line list (image, name by language, `unitLabel`, stepper capped at 99), coupon box + "See offers", coin selector (None / All / Percent slider), fulfilment toggle, price summary, warnings strip, "Place order".

Price summary rows come straight from the quote: `subtotalPaise`, `couponDiscountPaise`, `coinDiscountPaise`, `deliveryFeePaise`, `totalPaise`, `coinsToEarn`.

### 1.4 API sequence

1. **App start after sign-in**: `GET /api/v1/cart`.
   - Response (always 200; empty basket is the empty shape, never 404):
     ```json
     { "items": [ { "productId": "0b0e8c0e-5a52-4a43-a2e1-2d7f0d4a9b11", "qty": 2 } ],
       "couponCode": "WELCOME50", "coinMode": "PERCENT", "percent": 50, "updatedAt": "2026-11-08T10:15:30Z" }
     ```
   - Lines whose product is inactive, deleted or **out of stock (stock 0)** are **hidden** from this answer but stay stored, and come back after a restock.
   - Merge with the local cart (server wins on an empty local cart; otherwise ask or merge by summing qty and cap at 99).
2. **Every cart change (debounce 300-500 ms)**: `POST /api/v1/cart/quote`
   ```json
   { "items": [ { "productId": "0b0e8c0e-...", "qty": 2 } ], "couponCode": "WELCOME50",
     "coinMode": "PERCENT", "percent": 50, "fulfilment": "DELIVERY" }
   ```
   Response 200 (fields in 1.7). Render it as is. Keep the last good quote: `totalPaise` becomes `expectedTotalPaise` at checkout.
3. **After the quote succeeds** (debounced, fire and forget): `PUT /api/v1/cart` with the same lines, `couponCode`, `coinMode`, `percent` (no `fulfilment`). Last write wins: the stored basket becomes exactly what you sent. The response is the canonical basket (merged duplicates, uppercase coupon, `percent` kept only for `PERCENT`).
4. **After an order is placed (201)** or "Clear cart": `DELETE /api/v1/cart` (204, also when nothing was stored).

Errors and the UI reaction:

| Call | Status / code | When | UI |
|---|---|---|---|
| quote | 400 `VALIDATION_FAILED` `fields.items` | empty `items` | Show empty-cart state, do not call. |
| quote | 400 `fields.qty` | qty outside 1..99 (or merged sum over 99) | Clamp the stepper; toast the message. |
| quote | 400 `fields.productId` ("product is not available: <id>") | unknown, inactive or counter-only product | Remove that line (the id is in the message) and re-quote. |
| quote | 422 `COUPON_INVALID` | coupon unusable | Section 2: show `message` at the coupon box, keep the last good totals, offer "Remove coupon". |
| put cart | 400 `fields.items` | more than 100 lines | Block adding. |
| put cart | 400 `fields["items[0].qty"]` | one line over 99 (`must be 99 or less`) or under 1 (`must be 1 or more`) | Clamp. |
| put cart | 400 `fields.qty` (`quantity must be 1 to 99`) | duplicates of one product add up to over 99 | Merge locally before sending. |
| any | 429 | rate limit | Wait `Retry-After`. |

### 1.5 State and derived values
**The quote does not check stock.** It prices any active product, even with stock 0 or fewer units than asked. Only `POST /orders` (and moving an order to CONFIRMED) answers `409 OUT_OF_STOCK`. Use the product's `inStock` / stock fields from the catalog to warn on the cart before checkout.

Server computation order (never reproduce it): lines priced (flash applied) -> subtotal -> coupon -> coins -> delivery fee -> total -> coins to earn.
- `couponDiscountPaise` never exceeds `subtotalPaise`.
- `deliveryFeePaise`: 0 for `PICKUP`; for `DELIVERY` 0 when `subtotal - coupon - coinDiscount >= delivery.freeAbovePaise`, else `delivery.feePaise`.
- `totalPaise = max(0, subtotal - coupon - coinDiscount + deliveryFee)`.
- `coinsToEarn` is computed on earning lines after coupon and coin discounts; the delivery fee earns nothing.
- Coins are credited only when the order is DELIVERED or PICKED_UP.

`warnings` (strings, show all; they are English text, show as given):

| Text | Meaning |
|---|---|
| `Coins cannot be used together with coupon <CODE>` | The applied coupon has `worksWithCoins:false`; `coinsUsed` is 0. |
| `None of the items in the cart can be paid with coins` | `coinMode` was `ALL`/`PERCENT` but no line is coin-redeemable; `coinsUsed` 0. |
| `Some items cannot be paid with coins` | Mixed cart; coins were used but only on the redeemable part. |

### 1.6 Edge cases
- **Two devices**: last `PUT /cart` wins. A later save from a device that never saw hidden (out-of-stock) lines **drops them**. Accept this.
- **Saved lines that no longer exist**: the quote 400s with the product id; drop the line and re-quote. Do not loop on the same id.
- **Debounce and ordering**: ignore a quote response if a newer request was sent after it (use a request counter).
- **Offline**: keep the local cart; sync with `PUT /cart` on reconnect.
- **Empty states**: empty `items` -> no quote call; show an empty cart.
- **No paging** on the basket.
- **Price drift**: the cart screen price can be stale; the order call re-prices (section 5).

### 1.7 Dart model sketches (copied from the real records)
```dart
class BasketLine { final String productId; final int qty; BasketLine(this.productId, this.qty); }
class Basket { // GET/PUT /cart
  final List<BasketLine> items; final String? couponCode; final String coinMode; // NONE|ALL|PERCENT
  final int? percent; final DateTime? updatedAt; /* fromJson as in section 15 */ }
class QuoteLine {
  final String productId, name, nameGu, unitLabel; final String? categoryId;
  final int qty; final int unitPricePaise, lineTotalPaise, couponDiscountPaise;
  final bool coinRedeemable, earnsCoins;
  final String? flashSaleId; final int listPricePaise, flashDiscountPaise; // section 4
}
class CategoryDiscount { final String categoryId; final int subtotalPaise, discountPaise; }
class Quote {
  final List<QuoteLine> lines; final int subtotalPaise; final String? couponCode;
  final int couponDiscountPaise, coinsUsed, coinDiscountPaise, deliveryFeePaise, totalPaise, coinsToEarn;
  final List<String> warnings; final List<CategoryDiscount> couponByCategory; // empty without a coupon discount
}
```

### 1.8 Acceptance checklist
- Given a signed-in customer with a saved basket, When the app starts, Then `GET /cart` fills the cart and a quote is requested.
- Given qty 99 of a product, When the customer taps plus, Then the stepper stops at 99 and no call is made with 100.
- Given the same product added twice with 60 each, When quoting, Then the app merges before sending (otherwise 400 `qty`).
- Given a product with stock 0 in the cart, When quoting, Then the quote succeeds (no stock check) and the cart shows an "out of stock" badge from catalog data; placing is blocked or answers `OUT_OF_STOCK`.
- Given a quote with `warnings`, When rendering, Then every warning is visible.
- Given two quick edits, When the first response arrives after the second request was sent, Then it is ignored.
- Given an order was placed (201), When the success screen opens, Then `DELETE /cart` was called.
- Given a 400 `productId` error, When shown, Then that line is removed and the cart re-quoted once.

---

## 2. Coupons: list, picker, applying a code, targeting, reason codes

### 2.1 Goal
Let the customer browse offers, see for each one what it would save on **this exact cart** (or exactly why not), apply a typed code, and understand failures in plain words.

### 2.2 Who and settings
Browsing `GET /coupons` is public. The picker `POST /cart/coupons` and the quote need sign-in. Setting `coupon.allowOnFlashItems` (default true) changes flash behaviour. Admin creates coupons in `/admin/coupons` (other guide).

### 2.3 Screens and fields

| Screen / field | Type | Notes |
|---|---|---|
| Offers list (browse, no cart) | `GET /coupons` items | Show `code`, saving text from `kind`/`value`, `minOrderPaise`, `maxDiscountPaise`, `appliesToText` ("On: Dairy, Basmati Rice 5 kg"), "first order only", "cannot combine with coins" (`worksWithCoins:false`), validity `startAt`/`endAt`. Presence in the list does **not** guarantee the customer can use it. |
| Coupon picker (cart) | `POST /cart/coupons` options | Per option: green "Save Rs X" when `applicable`, else the `message`. Optional "covers: ..." from `coveredLines`. |
| Coupon code box | text | Free text, no client validation except trimming. Server upper-cases. |

Units on `GET /coupons`: `value` is **paise for `FLAT`** (5000 = Rs 50) and a **percent for `PERCENT`** (10 = 10%). `minOrderPaise`, `maxDiscountPaise`: paise, 0 means none. **Never compute the saving from `value`.** The only truth is `couponDiscountPaise` in the quote (and per line).

### 2.4 API sequence

1. **Offers screen**: `GET /api/v1/coupons` (public). Response items (real fields):
   `code, kind, value, minOrderPaise, maxDiscountPaise, categoryId, startAt, endAt, worksWithCoins, firstOrderOnly, perUserLimit, categoryIds[], productIds[], productGroupIds[], appliesTo ("ALL"|"CATEGORIES"|"ITEMS"|"MIXED"), appliesToNames[], appliesToText`.
   Refetch on SSE `catalog` event `area == "COUPONS"` and on pull to refresh.
2. **Cart picker**: `POST /api/v1/cart/coupons` with the same body as the quote. Any `couponCode` in it is **ignored**. Empty `items` is 400. Response is a **JSON array** (not an object):
   ```json
   [ { "code": "FESTIVE50", "kind": "FLAT", "value": 5000, "minOrderPaise": 99900, "maxDiscountPaise": 0,
       "applicable": true, "discountPaise": 5000, "reason": null, "message": null, "shortfallPaise": 0,
       "worksWithCoins": true, "appliesTo": "ALL", "appliesToText": "", "coveredLines": [ { "productId": "5be2...", "name": "Dal", "lineTotalPaise": 25000, "discountPaise": 5000 } ] },
     { "code": "BIG10", "kind": "PERCENT", "value": 10, "minOrderPaise": 200000, "maxDiscountPaise": 50000,
       "applicable": false, "discountPaise": 0, "reason": "MIN_ORDER",
       "message": "Add Rs 1000 more to use this coupon (minimum order Rs 2000).", "shortfallPaise": 100000,
       "worksWithCoins": true, "appliesTo": "ALL", "appliesToText": "", "coveredLines": [] } ]
   ```
   Server rules: at most **20** coupons are tried per call (each is a full quote): those open to every category and with the lowest minimum order go first. Sorted: applicable first, biggest `discountPaise` first, then the rest. A coupon that fails for a business reason is listed as not applicable and never hides the others. `discountPaise` equals what the quote gives when that code is applied. If the **cart itself** cannot be priced (bad product, bad qty) the call answers the same 400 as the quote instead of a list.
3. **Apply**: the customer taps an option (or types a code). Put the code into the cart state and call the quote with `couponCode`. Keep the code in `PUT /cart`.
4. **Remove**: send the quote without `couponCode`.

Quote error for a coupon: `422 COUPON_INVALID`, `message` ready to show, `details.reason`:

| `reason` | Exact `message` | Extra `details` |
|---|---|---|
| `NOT_FOUND` | `That coupon code does not exist.` | |
| `EXPIRED` | `This coupon is not active right now (it has ended, has not started yet or is switched off).` | |
| `MIN_ORDER` | `Add Rs 669 more to use this coupon (minimum order Rs 999).` (rupees; `Rs 669.50` when there are paise) | `minOrderPaise`, `shortfallPaise` |
| `CATEGORY` | `This coupon is for <names>; none are in your cart.` or, with no names, `This coupon works only on certain categories, and none of them is in your cart.` | |
| `ITEM` | `This coupon is for Basmati Rice 5 kg and Dal; none are in your cart.` or `This coupon works only on certain items, and none of them is in your cart.` | |
| `FLASH_ITEMS` | `This coupon does not apply to items that are already on a flash sale.` | |
| `LIMIT_REACHED` | `You have already used this coupon the allowed number of times.` (this customer hit the per-customer limit) or `This coupon has been used up.` | |
| `FIRST_ORDER_ONLY` | `This coupon is only for your first order.` | |
| (unknown) | `This coupon cannot be used` | |

Example error body: `{ "code": "COUPON_INVALID", "message": "Add Rs 669 more to use this coupon (minimum order Rs 999).", "traceId": "...", "details": { "reason": "MIN_ORDER", "minOrderPaise": 99900, "shortfallPaise": 66900 } }`.

In the picker the same `reason`/`message` appear per option (`NOT_FOUND` cannot occur there). `shortfallPaise` is 0 unless `reason == "MIN_ORDER"`.

Localisation tip: the server messages are English. For Gujarati, map `reason` to your own text and fill the numbers from `details`/option fields (`shortfallPaise`, `minOrderPaise`); fall back to `message` for unknown reasons.

### 2.5 State machines and derived values
**Rules the server applies, in this order (first failure wins):** coupon switched off -> outside start/end dates (`EXPIRED`); whole-cart subtotal below minimum (`MIN_ORDER`); no cart line covered (`CATEGORY` / `ITEM` / `FLASH_ITEMS`); covered amount below minimum (`MIN_ORDER`, shortfall measured on covered lines); per-customer limit or total limit (`LIMIT_REACHED`); first-order-only with earlier active orders (`FIRST_ORDER_ONLY`).

**Targeting.** A coupon can be limited by categories, products (variants) and product groups. `appliesTo`: `ALL` (every line), `CATEGORIES`, `ITEMS` (products/groups), `MIXED`. A line is **covered** when its category OR product OR product group is listed. Then:
- The discount base is the sum of **covered lines only**; percent and flat discounts are split over covered lines only, to the paisa; `lines[].couponDiscountPaise` is 0 on uncovered lines.
- The **minimum order counts covered lines only**.
- A flat coupon never exceeds the covered amount; a percent coupon is capped by `maxDiscountPaise` (when above 0).
- If `coupon.allowOnFlashItems` is false, flash lines are not covered and do not count toward the minimum.
- A coupon with `worksWithCoins:false` makes coins skip (warning, section 3).
- Where the discount landed: `lines[].couponDiscountPaise` and `couponByCategory[]` of the quote.

Coupon use is counted when the order is **placed** and released when the order is cancelled, rejected or expires. A partial return does **not** give a use back.

### 2.6 Edge cases
- **Race**: the quote said OK but another order used the last use: `POST /orders` answers 422 `COUPON_INVALID` `LIMIT_REACHED`. Remove the coupon, re-quote, let the customer retry (new key; no order exists).
- A saved coupon in `GET /cart` may be expired by now: the next quote shows the error; show it, keep the totals of the last good quote, offer remove.
- Picker with an empty cart: do not call. Picker on a cart with a bad product: you get the 400, not a list.
- The picker costs up to 20 quotes server-side: debounce at 500 ms or more, call it only when the sheet opens or the cart changes while it is open.
- Case: typed `welcome50` works; the quote's `couponCode` is the normalised code.
- Offers refresh on SSE `catalog`/`COUPONS`.
- The offers list is not paged.

### 2.7 Dart model sketches
```dart
class CoveredLine { final String productId, name; final int lineTotalPaise, discountPaise; }
class CouponOption {
  final String code, kind; // FLAT | PERCENT
  final int value, minOrderPaise, maxDiscountPaise, discountPaise, shortfallPaise;
  final bool applicable, worksWithCoins;
  final String? reason, message; // reason: EXPIRED|MIN_ORDER|CATEGORY|ITEM|FLASH_ITEMS|LIMIT_REACHED|FIRST_ORDER_ONLY
  final String appliesTo, appliesToText; // ALL|CATEGORIES|ITEMS|MIXED
  final List<CoveredLine> coveredLines;
}
class PublicCoupon {
  final String code, kind; final int value, minOrderPaise, maxDiscountPaise, perUserLimit;
  final String? categoryId; final DateTime? startAt, endAt;
  final bool worksWithCoins, firstOrderOnly;
  final List<String> categoryIds, productIds, productGroupIds, appliesToNames;
  final String appliesTo, appliesToText;
}
```

### 2.8 Acceptance checklist
- Given a cart of Rs 600 and a coupon with minimum Rs 999, When the picker loads, Then that option is `applicable:false`, `reason:"MIN_ORDER"`, `shortfallPaise:39900` and shows its `message`.
- Given an applicable flat Rs 50 coupon, When the customer taps it, Then the next quote returns `couponDiscountPaise: 5000` and the summary shows that value (not `value`).
- Given an item coupon for Dal and a cart without Dal, When quoting, Then 422 `COUPON_INVALID` with `reason:"ITEM"` and the message is shown at the coupon box with the last totals kept.
- Given a coupon with `worksWithCoins:false` and `coinMode:"ALL"`, When quoting, Then `coinsUsed` is 0 and the warning `Coins cannot be used together with coupon <CODE>` is visible.
- Given a typed lowercase code, When quoting, Then it is accepted and shown uppercase.
- Given the picker returns 25 possible offers, When shown, Then at most 20 are listed (the likeliest first) and the customer can still type any code.
- Given a `catalog` event for `COUPONS`, When the offers screen is open, Then the list refetches.

---

## 3. Coins: modes, percent, warnings

### 3.1 Goal
Let the customer pay part of the order with loyalty coins and see how many coins they will earn.

### 3.2 Who and settings
Signed-in customer. Coin settings (admin): `paisePerCoin` (1 coin = this many paise), `maxRedeemPercent` (0..100), `expiryMonths`. Earn rules (admin) decide `coinsToEarn`. Balance: `GET /wallet` (`coins`, `valuePaise`, `paisePerCoin`, `maxRedeemPercent`, `rules`, `expiryMonths`) and `coinBalance` in the order view.

### 3.3 Screens and fields

| Field | Type | Required | Validation |
|---|---|---|---|
| `coinMode` | `NONE` / `ALL` / `PERCENT` | no (null = `NONE`) | Other word: 400 `fields.coinMode`. |
| `percent` | int 0..100 | no | Used only for `PERCENT`; null = 100; clamped by the maths. |

UI: a 3-way control. `PERCENT` shows a slider 0..100 (default 100). Show "You will use N coins (Rs X)" from `coinsUsed` and `coinDiscountPaise` of the quote, not from your own maths. Show `coinsToEarn` ("Earn N coins on delivery"). Show the balance from `GET /wallet`.

### 3.4 API sequence
1. `GET /wallet` when the cart opens (balance and settings).
2. Put `coinMode`/`percent` into the same quote call as section 1 (re-quote on every change).
3. Place the order with the same `coinMode`/`percent` (section 5). The order view returns `coinsUsed`, `coinDiscountPaise`, `coinsEarned`, `coinBalance`.

Errors: `400 fields.coins = "not enough coins"` at **place** time (balance dropped since the quote, for example spent in another order). Re-quote and retry with a new key.

### 3.5 Derived values (server only)
- Coupon is applied first. Redeemable base = `min(sum of coin-redeemable lines, subtotal - couponDiscount)`.
- Maximum = `base * maxRedeemPercent / 100`. `ALL` wants the maximum; `PERCENT` wants `maximum * percent / 100`. Coins = `floor(wanted / paisePerCoin)`, never above the balance. Discount = `coins * paisePerCoin`.
- A line with `coinRedeemable:false` is never paid with coins. A line with `earnsCoins:false` earns nothing.
- Earn = best single rule on the earning lines after coupon and coin discounts.
- Coins are **spent at place time** (balance drops at once, oldest-expiring first) and returned when the order is cancelled, rejected or expires. Earned coins are credited when the order is DELIVERED or PICKED_UP, and expire after `expiryMonths`. A CANCELLED order shows `coinsEarned: 0`.
- Warnings: see section 1.5.

### 3.6 Edge cases
- Balance zero: the control may stay visible; the quote returns `coinsUsed: 0` without a warning unless no line is redeemable.
- Rounding: coins are whole numbers; a tiny base can give 0 coins.
- After place: refresh the wallet (an SSE `wallet` event also arrives).
- A coupon with `worksWithCoins:false` forces `coinsUsed` to 0; show the warning and keep the selector visible but explained.
- Delivery fee is never paid with coins (the coin base is the item subtotal).

### 3.7 Dart model sketch
```dart
class Wallet { final int coins, valuePaise, paisePerCoin, maxRedeemPercent, expiryMonths; final List<EarnRule> rules; }
class EarnRule { final String name, mode; final int spendPaise, coins; } // PRO_RATA | SLAB
```

### 3.8 Acceptance checklist
- Given a balance of 120 coins and `coinMode:"ALL"`, When quoting, Then `coinsUsed <= 120` and `coinDiscountPaise == coinsUsed * paisePerCoin`.
- Given `coinMode:"PERCENT"` without `percent`, When quoting, Then it behaves as 100 percent.
- Given a cart of only non-redeemable items, When `coinMode:"ALL"`, Then `coinsUsed` is 0 and the warning `None of the items in the cart can be paid with coins` shows.
- Given coins used, When the order is cancelled, Then the wallet balance returns to its earlier value.
- Given the balance fell below the quoted coins, When placing, Then 400 `coins` is shown and the cart re-quotes.

---

## 4. Flash sale prices inside quotes

### 4.1 Goal
Show sale prices correctly in the cart, including the split of one product into a sale line and a normal-price line.

### 4.2 Who and settings
Sales are created by staff (`40-flash-sales.md`). Only sales whose channel is `ONLINE` or `BOTH` affect the app. A per-customer limit and a units cap apply.

### 4.3 Screens and fields
Cart line: show `unitPricePaise` and, if `flashSaleId != null`, a "Flash sale" tag with `listPricePaise` struck through and "You save Rs X" from `flashDiscountPaise` (the discount for the **whole line**, `(list - unit) * qty`).

### 4.4 API sequence
No new call. `POST /cart/quote` returns up to **two lines for one product**: first the sale units (`flashSaleId` set, `unitPricePaise` = sale price), then the remainder at normal price (`flashSaleId: null`, `listPricePaise == unitPricePaise`, `flashDiscountPaise: 0`). If nothing fits (cap used or the customer's allowance used) there is a single normal line.

### 4.5 Derived values
Do not merge quote lines by `productId` when rendering. Do not compute savings from catalog `flash` data; the quote also knows this customer's allowance. The placed order keeps the two lines as two `items[]` entries with the frozen prices (order items have no flash fields).

### 4.6 Edge cases
- The sale ends or sells out between quote and place: `POST /orders` answers `409 PRICE_CHANGED` with a fresh `details.quote` (section 5). No order exists. Do not auto-retry.
- Coupons also discount flash lines unless `coupon.allowOnFlashItems` is false.
- Refresh the quote on SSE `catalog` events with `area == "FLASH"`.
- Cancelling or expiring an order gives the sale units back.

### 4.7 Dart model sketch
Use `QuoteLine` from 1.7 (`flashSaleId`, `listPricePaise`, `flashDiscountPaise`).

### 4.8 Acceptance checklist
- Given a product with 1 sale unit left and qty 3 in the cart, When quoting, Then two lines render: 1 at sale price with the tag and 2 at normal price.
- Given the sale ended before placing, When placing, Then the 409 `PRICE_CHANGED` quote replaces the cart totals and the customer must confirm again.

---

## 5. Placing an order (COD or ONLINE)

### 5.1 Goal
Turn the quoted cart into an order, once, safely, with the right address, payment method and optional time slot.

### 5.2 Who and settings
Signed-in customer. Needs: a saved address (for delivery), `GET /payment-methods`, `GET /shop-settings` (`onlineOrdersOpen`). ONLINE is off until the owner switches it on and enters Razorpay keys.

### 5.3 Screens and fields (checkout)

| Field | Type | Required | Validation mirrored from the server |
|---|---|---|---|
| `items`, `couponCode`, `coinMode`, `percent`, `fulfilment` | | | Exactly the cart you quoted; same rules as the quote (qty 1..99, product must be available). |
| `addressId` | UUID | yes when `fulfilment` is `DELIVERY` (default) | Missing: 400 `fields.addressId` = `address is required for delivery`. Not this user's address: 404 `NOT_FOUND` ("Address not found"). Ignored for `PICKUP`. The address is **copied** onto the order (later edits do not change it). |
| `paymentMethod` | string | **yes** | Exactly `COD` or `ONLINE`. `CREDIT`/`HYBRID`: 400 (counter only). Other word: 400 `must be COD or ONLINE (got "CASH")`. A method that is switched off (or ONLINE without keys): 400 `fields.paymentMethod` = `<METHOD> is not available`. |
| `expectedTotalPaise` | long | no (null or absent skips the check) | If sent and different from the server's fresh total: 409 `PRICE_CHANGED`. **Always send it** (the last quote's `totalPaise`). |
| `slot` | string | no | Free text window such as `Today 6-8 PM`, trimmed, **max 40 characters** (400 `fields.slot` over that); blank is none. Stored and returned as sent. |
| Header `Idempotency-Key` | string | **yes** | Missing: 400 `fields["Idempotency-Key"]` = `is required`. Scoped to the user. |

Checkout UI: address picker (default address first; `GET /me/addresses`), fulfilment toggle (re-quote on change), slot chips (your own list; the server accepts any text up to 40 chars), payment method radio from `GET /payment-methods`, summary from the latest quote, "Place order" button.

### 5.4 API sequence

1. Before showing checkout: `GET /payment-methods` -> `[ { "code": "COD", "enabled": true }, { "code": "ONLINE", "enabled": true } ]` (only usable methods are listed; hide the others). `GET /shop-settings` -> if `onlineOrdersOpen == false` disable the button and show `onlineOrdersMessage` as given.
2. Quote (section 1). Remember `totalPaise`.
3. On tap: create `key = uuid v4`, store it with the cart snapshot (persist it, e.g. in local storage, so an app restart reuses it). Disable the button.
4. `POST /api/v1/orders`, header `Idempotency-Key: <key>`:
   ```json
   { "items": [ { "productId": "0b0e8c0e-5a52-4a43-a2e1-2d7f0d4a9b11", "qty": 2 } ],
     "couponCode": "WELCOME50", "coinMode": "PERCENT", "percent": 50, "fulfilment": "DELIVERY",
     "addressId": "6a2f1c1d-...", "paymentMethod": "ONLINE", "expectedTotalPaise": 88000, "slot": "Today 6-8 PM" }
   ```
   Response **201** `OrderView` (fields in 7.3). ONLINE example (shortened):
   ```json
   { "id": "e3b0...", "orderNo": "1001", "status": "PAYMENT_PENDING", "fulfilment": "DELIVERY", "paymentMethod": "ONLINE",
     "paymentStatus": "INITIATED", "totalPaise": 88000, "coinsEarned": 85,
     "paymentSession": { "type": "RAZORPAY", "payload": { "keyId": "rzp_test_xxxxxxxx", "razorpayOrderId": "order_Abc123", "amountPaise": 88000, "currency": "INR" } },
     "paymentExpiresAt": "2026-11-08T10:30:30Z", "timeline": [ { "status": "PAYMENT_PENDING", "at": "2026-11-08T10:15:30Z" } ],
     "placedAt": "2026-11-08T10:15:30Z", "slot": "Today 6-8 PM", "cancelReason": null, "cancelledBy": null, "cancelCode": null, "coinBalance": 340 }
   ```
   COD example differences: `status: "PLACED"`, `paymentStatus: "NONE"`, `paymentSession: null`, `paymentExpiresAt: null`.
5. On 201: `DELETE /cart`, refresh wallet, go to the order screen. If `status == "PAYMENT_PENDING"` start section 6.
6. On network error / timeout / app killed: **retry the same call with the same key**. The server returns the order that was created (HTTP 201, current state) without checking prices, stock or pause again and ignoring body differences. Two parallel calls with one key create exactly one order.

Errors and the UI reaction:

| Status / code | When | UI |
|---|---|---|
| 400 `VALIDATION_FAILED` | key missing; empty items; qty/product; `addressId`; `paymentMethod`; `slot`; `coins` "not enough coins" | Highlight `details.fields`. For `coins`/`productId` re-quote. |
| 404 `NOT_FOUND` | `addressId` is not the user's | Reload addresses, ask to choose again. |
| 409 `PRICE_CHANGED` | server total differs from `expectedTotalPaise`, **or** a flash sale changed between quote and place | `details.quote` holds the fresh full quote (same shape as the quote response). Replace the cart totals with it, say "Prices changed, please review", require a second tap which sends `expectedTotalPaise = details.quote.totalPaise`. Use a **new** key (no order was created). Never auto-retry. |
| 409 `OUT_OF_STOCK` | stock short at place time (message `Some items are no longer available`) | `details.items = [ { "productId", "available" } ]`. Mark those lines, cap qty at `available` (0 = remove), re-quote. |
| 422 `ORDERS_PAUSED` | owner paused online orders | Show `message` as given and block checkout. |
| 422 `COUPON_INVALID` | coupon unusable or its last use was just taken (`LIMIT_REACHED`) | Section 2. |
| 502 `PAYMENT_PROVIDER_ERROR` / 503 `PAYMENT_NOT_CONFIGURED` | Razorpay order could not be created / keys missing | Whole placement rolled back, nothing exists. Show `message`, offer COD, allow retry later. |
| 429 / 503 `SERVICE_BUSY` | | Wait `Retry-After`, retry with the **same** key. |

### 5.5 State and derived values
- Order of server checks: pause -> slot -> payment method allowed/enabled -> pricing (items, coupon) -> `PRICE_CHANGED` -> address -> stock check -> coins -> coupon use -> flash units -> payment session. The whole placement is one transaction: any failure leaves nothing behind (no coins spent, no coupon use, no order).
- Initial status: COD -> `PLACED`. ONLINE with total > 0 -> `PAYMENT_PENDING` (`paymentStatus: INITIATED`, `paymentSession` present, `paymentExpiresAt` set). ONLINE with total 0 (fully covered by coupon and coins, no fee) -> treated like COD (`PLACED`, `paymentStatus: NONE`, no session).
- **Stock is not deducted at place time**; it is only checked. Staff confirming the order takes the stock (section 12).
- Coins are deducted from the balance at place time; coupon use is counted at place time.
- The server always re-prices; the client total is only a guard.

### 5.6 Edge cases
- **Retry vs change**: retry with the same key only for the same cart. If the customer edited the cart, create a new key.
- After a definitive failure (400, 409, 422) the key is dead for this body: use a new key for the next attempt.
- **Double tap**: disable the button; the key also protects you.
- **Replay after cancel**: replaying an old key returns that (possibly cancelled) order, not a new one.
- Place with `PICKUP`: no address needed; `address` is null in the response.
- Empty states: no addresses -> prompt to add one before enabling delivery; no payment methods listed -> block.
- Customer app restart between 201 and screen: the order list (`GET /orders`) shows the order; the payment can be resumed from `GET /orders/{id}`.

### 5.7 Dart model sketch
`PlaceOrderRequest` mirrors the JSON above. `OrderView` and `OrderItem` are in section 7.7 and `ApiError` in section 15. Keep `idempotencyKey` inside a persisted `PendingCheckout` object with the cart snapshot.

### 5.8 Acceptance checklist
- Given a valid cart, When the customer taps Place twice quickly, Then only one order exists and both responses carry the same `id`.
- Given the network drops after the request was sent, When the app retries with the same key, Then the original order is returned (201) and no second order is created.
- Given prices changed, When placing with `expectedTotalPaise`, Then the 409 `PRICE_CHANGED` quote is shown and a second confirm sends the new total with a new key.
- Given stock fell, When placing, Then the lines in `details.items` are flagged and nothing is charged.
- Given online ordering is paused, When placing, Then the shop's `message` is shown and the button stays disabled.
- Given ONLINE and a total of 0, When placing, Then the order is `PLACED` with no payment session.
- Given a delivery order without `addressId`, When placing, Then the address field shows `address is required for delivery`.

---

## 6. Online payment: gateway session, result, timeout, wrong amount, duplicates

### 6.1 Goal
Collect the money through Razorpay for an ONLINE order and learn the result **only from the server**.

### 6.2 Who and settings
Customer. Owner must have switched ONLINE on and saved Razorpay key id, key secret and webhook secret. Time allowed: `payment.onlineTimeoutMinutes` (default 15). The webhook (`POST /payments/webhook/razorpay`) is for Razorpay servers only; the app must never call it. The app only ever holds the public `keyId`.

### 6.3 Screens and fields
- **Pay now sheet / screen**: amount (from the order `totalPaise`), countdown to `paymentExpiresAt`, "Pay now" (opens Razorpay), "Cancel order".
- **Confirming payment** overlay after the SDK returns.
- **Result**: paid (status `PLACED`, `paymentStatus: CAPTURED`), expired/cancelled, or "still confirming".

### 6.4 API sequence

1. **Place** (section 5) -> `status: PAYMENT_PENDING` with:
   ```json
   "paymentSession": { "type": "RAZORPAY", "payload": { "keyId": "rzp_test_xxxxxxxx", "razorpayOrderId": "order_Abc123", "amountPaise": 88000, "currency": "INR" } },
   "paymentExpiresAt": "2026-11-08T10:30:30Z"
   ```
   `type` tells the app which gateway screen to open (only `RAZORPAY` exists today; show an "update the app" message for an unknown type).
2. **Open Razorpay Checkout** (Flutter `razorpay_flutter`, web Checkout.js) with `key = payload.keyId`, `order_id = payload.razorpayOrderId`, `amount = payload.amountPaise`, `currency = payload.currency`. Prefill name/mobile/e-mail from the profile if available.
3. **Do not trust the SDK callback.** Whatever the SDK says (success, failure, dismissed) go to "Confirming payment" and watch the server:
   - Listen for the SSE `order` event for this `orderId` (section 7) and/or poll `GET /api/v1/orders/{id}` every 2-3 s for about 60-90 s.
   - **Paid** when `status != "PAYMENT_PENDING"` and `paymentStatus == "CAPTURED"` (status becomes `PLACED`). The app learns of the result through the server's webhook; a fallback check runs every 5 minutes for payments older than 5 minutes whose webhook never arrived.
   - **Cancelled**: `status == "CANCELLED"` (timeout or the customer/staff cancelled).
   - **Still pending after ~90 s**: show "We are still confirming your payment. You will be notified. Do not pay again." and keep the order screen refreshable (pull to refresh, SSE).
4. **If the customer dismissed or the attempt failed**: the order stays `PAYMENT_PENDING`. A failed attempt (webhook `payment.failed`) is only recorded; it does **not** cancel the order. Show "Retry payment" (reopen Checkout with the `paymentSession` from a fresh `GET /orders/{id}`, same Razorpay order) and "Cancel order" (`POST /orders/{id}/cancel`).
5. **Countdown**: remaining = `paymentExpiresAt - now`. The app has no server clock, so compute an offset from the HTTP `Date` header of the response that carried the order (fallback: device clock). The field is present **only while `PAYMENT_PENDING`** and null otherwise. At zero: disable "Pay now", refetch the order and poll. The expiry job runs every 60 seconds, so the order can stay `PAYMENT_PENDING` for up to about a minute after the deadline; treat the server status as final, not the clock.
6. **Reopening later** (customer leaves and returns): `GET /orders/{id}`; if `status == PAYMENT_PENDING` and `paymentSession != null` offer "Pay now". If `paymentSession == null` while pending the payment attempt is closed: show "Payment expired or failed" and expect the order to be cancelled.

### 6.5 State machines and server behaviour

Order x payment status:

| Situation | `status` | `paymentStatus` |
|---|---|---|
| ONLINE placed, unpaid | `PAYMENT_PENDING` | `INITIATED` |
| Payment captured in the right amount | `PLACED` | `CAPTURED` |
| Customer or staff cancels before payment | `CANCELLED` | `FAILED` (nothing to refund) |
| Timeout before payment | `CANCELLED` (`cancelledBy: SYSTEM`, `cancelCode: PAYMENT_TIMEOUT`, `cancelReason: null`) | `FAILED` |
| Cancelled/rejected **after** payment | `CANCELLED` | `REFUNDED` (refund queued; money may still be `PENDING` at Razorpay) |
| Payment arrives after the order was cancelled/expired | stays `CANCELLED` | `REFUNDED` (server refunds with reason `paid after expiry`) |
| COD delivered or picked up | `DELIVERED` / `PICKED_UP` | `CAPTURED` |

Payment events (server side, for your understanding; the app does nothing):
- **Right amount captured** -> order `PLACED`, `CAPTURED`. A repeated event is ignored.
- **Wrong amount captured** (kind `MISMATCH`): the order is **not** marked paid and stays `PAYMENT_PENDING`; the money received is refunded in full to the original payment method automatically. The customer may pay again on the same order until `paymentExpiresAt`; a later correct payment is accepted. Notification to the customer, title `Payment amount did not match`: "We received Rs 400.50 instead of Rs 500.00 for order VSM-5. It is being refunded to your original payment method. Please pay again." (the last sentence only while the order is still waiting for payment). Staff get `Payment problem`.
- **Second payment on an already-paid order** (kind `DUPLICATE`, different provider payment id): refunded in full; the first payment stands; the order is unaffected. Notification title `Duplicate payment`: "We received a second payment of Rs 25.00 for order VSM-7. It is being refunded to your original payment method."
- **Failed attempt**: recorded, order stays pending.
- Refund lifecycle (admin side): section 11.

What the app must never do: mark an order paid from the SDK callback, send the Razorpay signature anywhere, show a total other than the server's, or call the webhook.

### 6.6 Edge cases
- **Paid but app killed**: on next open the order list/detail shows `PLACED`/`CAPTURED`; nothing to resume.
- **Paid twice by double tapping Pay**: the second payment is refunded automatically (`DUPLICATE`); show the notification, no client action.
- **Retry after wrong amount**: the same "Pay now" works again until expiry.
- **Order expired while Checkout was open**: payment may still go through, then it is refunded automatically; show "Order expired. Any money paid will be refunded." when the order shows `CANCELLED` with `PAYMENT_TIMEOUT`.
- **Notifications**: push `data.type = "ORDER"`, `ref = orderNo`. On tap open the order and refetch. In-app inbox titles: `Order placed`, `Order update`, `Payment amount did not match`, `Duplicate payment`.
- **SSE gap**: events are not replayed; after a reconnect refetch the order.
- ONLINE method suddenly disabled by the owner: only affects new orders (400 `paymentMethod`); existing pending orders can still be paid.
- Flash sale units on an unpaid order are returned when it expires or is cancelled.

### 6.7 Dart model sketch
```dart
class PaymentSession { final String type; final Map<String, dynamic> payload; // RAZORPAY: keyId, razorpayOrderId, amountPaise, currency
  String get keyId => payload['keyId']; String get razorpayOrderId => payload['razorpayOrderId'];
  int get amountPaise => payload['amountPaise']; String get currency => payload['currency']; }
enum PayOutcome { paid, cancelled, stillConfirming }
PayOutcome? outcomeOf(OrderView o) => o.status == 'PAYMENT_PENDING' ? null
    : o.status == 'CANCELLED' ? PayOutcome.cancelled : PayOutcome.paid;
```

### 6.8 Acceptance checklist
- Given an ONLINE order, When the SDK reports success, Then the app shows "Confirming payment" and does not mark the order paid until `GET /orders/{id}` says `status != PAYMENT_PENDING`.
- Given the webhook arrived, When the app polls, Then within a few seconds the order shows `PLACED` and `CAPTURED`.
- Given 90 seconds without a change, When still pending, Then the "still confirming, do not pay again" message shows and pull-to-refresh works.
- Given the customer dismissed Checkout, When they return to the order, Then "Retry payment" reopens Checkout from the fresh `paymentSession`.
- Given `paymentExpiresAt` passed, When the countdown ends, Then "Pay now" is disabled and the order is refetched.
- Given a wrong-amount payment, When the notification arrives, Then the order screen refreshes, still shows "Pay now", and tells the customer the amount is being refunded.
- Given the order expired, When the status shows `CANCELLED` with `PAYMENT_TIMEOUT`, Then no reason text is invented and the "payment time ran out" message shows.

---

## 7. Order tracking: statuses, timeline, live events, notifications

### 7.1 Goal
Show the customer where the order is, live, with a correct stepper for delivery or pickup.

### 7.2 Who and settings
Customer (own orders; someone else's id is 404, not 403). Staff use the staff stream (section 12).

### 7.3 Screens and fields

Orders list `GET /orders?page&size` (newest first; only `page` and `size` are read, other params are ignored) rows (`OrderSummary`):
`id, orderNo, status, fulfilment, totalPaise, itemCount, placedAt, subtotalPaise, couponCode, couponDiscountPaise, coinsUsed, coinDiscountPaise, deliveryFeePaise, paymentMethod, paymentStatus, cancelledBy, cancelCode`. **`itemCount` is the total number of units** (sum of quantities), not the number of lines.

Order detail `GET /orders/{id}` (`OrderView`):

| Field | Type | Notes |
|---|---|---|
| `id`, `orderNo` | string | |
| `status` | enum | `PAYMENT_PENDING, PLACED, CONFIRMED, PACKED, OUT_FOR_DELIVERY, READY_FOR_PICKUP, DELIVERED, PICKED_UP, CANCELLED` |
| `fulfilment` | `DELIVERY` / `PICKUP` | decides the last steps |
| `paymentMethod`, `paymentStatus` | `COD`/`ONLINE`; `NONE`/`INITIATED`/`CAPTURED`/`REFUNDED`/`FAILED` | |
| `items[]` | `productId, name, qty, unitPricePaise, lineTotalPaise, nameGu, unitLabel, couponDiscountPaise, imageUrl?` | frozen at order time. `imageUrl` is a snapshot (nullable). A flash product can appear as two rows. |
| money | `subtotalPaise, couponCode?, couponDiscountPaise, coinsUsed, coinDiscountPaise, deliveryFeePaise, totalPaise` | same as the quote at order time |
| `coinsEarned` | int | credited on DELIVERED/PICKED_UP; always `0` once CANCELLED |
| `address` | `{id,label,line1,area,pincode,isDefault}` or null | snapshot; null for pickup |
| `paymentSession` | object or null | only while `PAYMENT_PENDING` and the attempt is open |
| `paymentExpiresAt` | instant or null | only while `PAYMENT_PENDING` |
| `timeline[]` | `{status, at}` oldest first | every status the order entered (no staff notes) |
| `placedAt`, `slot` | | slot is the text the customer chose |
| `cancelReason`, `cancelledBy`, `cancelCode` | strings or null | section 8 |
| `coinBalance` | int | the customer's balance now; refresh the wallet badge |

### 7.4 API sequence
1. Open `GET /api/v1/orders/stream` once after sign-in (SSE). Header `Authorization: Bearer ...`; a browser `EventSource` may use `?access_token=` on this path only.
2. On `ready`: reload the visible orders. On `order` for an `orderId` you show: refetch `GET /orders/{id}` and update the list row.
3. List screen: `GET /orders` page 0 on open and after reconnect; infinite scroll with `hasMore`.
4. Open order: `GET /orders/{id}`.

SSE events (event name, then `data:` JSON):

| Event | To whom | Data | UI |
|---|---|---|---|
| `ready` | the connecting client | `{"connected":true}` | mark live, reload |
| `order` | the order's customer (staff: all orders) | `{ "orderId", "orderNo", "status", "previousStatus", "at" }`; `previousStatus` null = new order; `status == previousStatus` = something changed without a status change (payment recorded, return recorded) | refetch the order; toast on status change |
| `wallet` | that customer | `{ "userId", "reason" (REDEEM, EARN, REVERSE, CLAWBACK, RESTORE), "delta", "at" }` | refetch `GET /wallet` |
| `stock` | all | `{ "productIds": [...], "reason", "at" }` (empty list = many) | refresh visible stock |
| `catalog` | all | `{ "area": "CATALOG|COUPONS|UNITS|SHOP|PAYMENT_METHODS|FLASH", "at" }` | refetch that area |
| `credit` | that customer | `{ "customerId", "reason", "deltaPaise", "at" }` | credit screen |

Stream rules: ends by itself after **10 minutes**; max 5 streams per customer (the oldest is closed); a `: keepalive` comment every 25 s (ignore); events are **not replayed**; `stock`/`catalog` are merged at most twice a second. Reconnect with back-off 1, 2, 5, up to 30 seconds, using a fresh token (refresh first if expired); after the new `ready` reload the screens.

### 7.5 State machines

Customer-facing meaning:

| Status | Message | Customer can cancel |
|---|---|---|
| `PAYMENT_PENDING` | Waiting for your payment | yes |
| `PLACED` | Order received | yes |
| `CONFIRMED` | Shop accepted (stock is set aside now) | yes |
| `PACKED` | Being packed / packed | no |
| `OUT_FOR_DELIVERY` | On the way (DELIVERY only) | no |
| `READY_FOR_PICKUP` | Ready at the shop (PICKUP only) | no |
| `DELIVERED` / `PICKED_UP` | Done; coins credited | no (final) |
| `CANCELLED` | Cancelled / rejected / expired | final |

Stepper rules: DELIVERY: PLACED, CONFIRMED, PACKED, OUT_FOR_DELIVERY, DELIVERED. PICKUP: PLACED, CONFIRMED, PACKED, READY_FOR_PICKUP, PICKED_UP. Staff can skip steps or move **backwards** (for example PACKED back to CONFIRMED, or a PLACED order straight to DELIVERED), so draw reached steps from `timeline` and the current step from `status`; do not assume a strict sequence or that a step shows only once. `PAYMENT_PENDING` shows as step 0 only for online orders.

### 7.6 Edge cases
- Cancelled order: grey out the stepper and show section 8 content.
- Delivered order: show returns recorded by the shop (section 10.3 customer view) and "Order again".
- SSE blocked by a proxy: fall back to polling the list every 20-30 s while the screen is open.
- A status can change while the customer views the cancel button: handle 409 (section 8).
- Per-product rows with the same `productId` (flash split) are not merged.

### 7.7 Dart model sketch
```dart
class OrderItem { final String productId, name, nameGu, unitLabel; final String? imageUrl;
  final int qty, unitPricePaise, lineTotalPaise, couponDiscountPaise; }
class TimelineStep { final String status; final DateTime at; }
class OrderView {
  final String id, orderNo, status, fulfilment, paymentMethod, paymentStatus;
  final List<OrderItem> items; final int subtotalPaise, couponDiscountPaise, coinsUsed, coinDiscountPaise, deliveryFeePaise, totalPaise, coinsEarned;
  final String? couponCode, slot, cancelReason, cancelledBy, cancelCode; final Address? address;
  final PaymentSession? paymentSession; final List<TimelineStep> timeline; final DateTime placedAt;
  final int? coinBalance; final DateTime? paymentExpiresAt;
}
class OrderSummary { final String id, orderNo, status, fulfilment, paymentMethod, paymentStatus; final int totalPaise, itemCount,
  subtotalPaise, couponDiscountPaise, coinsUsed, coinDiscountPaise, deliveryFeePaise; final String? couponCode, cancelledBy, cancelCode; final DateTime placedAt; }
```

### 7.8 Acceptance checklist
- Given the stream is open, When staff confirm the order, Then an `order` event arrives and the detail screen refetches and shows CONFIRMED.
- Given a PICKUP order, When rendering the stepper, Then OUT_FOR_DELIVERY/DELIVERED never appear.
- Given staff moved an order backwards, When the timeline has repeated statuses, Then the screen still renders and shows the latest `status` as current.
- Given the stream dropped, When it reconnects, Then the list and the open order are refetched after `ready`.
- Given 41 orders, When scrolling, Then pages load until `hasMore` is false.
- Given another customer's order id, When opened, Then 404 shows "Order not found".

---

## 8. Customer cancel, rejection and cancel info

### 8.1 Goal
Let a customer cancel while it is still allowed, and explain every cancelled order correctly and kindly.

### 8.2 Who and settings
Customer (own order). Staff reject from the order desk (section 12).

### 8.3 Screens and fields
`POST /orders/{id}/cancel` body is optional (`{}` or no body): `reason` string, **max 200 chars** (400 `fields.reason` over), blank = none. UI: confirm dialog + optional reason chips + free text.

### 8.4 API sequence
1. Show "Cancel order" only when `status` is `PAYMENT_PENDING`, `PLACED` or `CONFIRMED`.
2. `POST /api/v1/orders/{id}/cancel` body `{ "reason": "Ordered by mistake" }` -> 200 `OrderView` with `status: "CANCELLED"`.
3. Refresh wallet and the list.

| Status / code | When | UI |
|---|---|---|
| 404 `NOT_FOUND` | not this user's order | Back to list. |
| 409 `ILLEGAL_TRANSITION` ("This order can no longer be cancelled") | status is PACKED or later, or already final (including cancelling twice) | Reload the order, hide the button, tell the customer to call the shop. |
| 409 `VERSION_CONFLICT` | staff changed the order at the same moment | Reload and retry once. |
| 400 `VALIDATION_FAILED` `fields.reason` | over 200 chars | Fix. |

Effects (same for every cancel path): stock given back if it had been deducted (at CONFIRMED), coupon use released, coins spent restored, flash units returned, captured online payment queued for refund (`paymentStatus: REFUNDED`), unpaid ONLINE becomes `paymentStatus: FAILED`, no coins earned (`coinsEarned: 0`).

### 8.5 Derived values: `cancelledBy`, `cancelCode`, `cancelReason`
All three are on every order view and the two codes are on list rows; all are null while the order is not cancelled.

| `cancelCode` | `cancelledBy` | Set when | `cancelReason` | Suggested customer text (translate yourself) |
|---|---|---|---|---|
| `CUSTOMER_CANCELLED` | `CUSTOMER` | customer cancel | customer's text, or null | "You cancelled this order." + reason if present |
| `REJECTED_BY_SHOP` | `SHOP` | staff `/reject` | staff text (1..200) | "The shop could not accept this order." + reason |
| `CANCELLED_BY_SHOP` | `SHOP` | staff `/status` with `CANCELLED` | staff `note` or null | "The shop cancelled this order." + reason if present |
| `PAYMENT_TIMEOUT` | `SYSTEM` | unpaid ONLINE order expired | null | "Payment time ran out." |
| `PAYMENT_FAILED` | `SYSTEM` | defensive code; **not produced today** | null | "Payment failed." (handle it anyway) |
| null on a `CANCELLED` order | null | old data that could not be classified | maybe | "Cancelled." |

Rules: `cancelReason` is raw text exactly as typed (no "Rejected by shop:" prefix; the server strips the old prefix). Translate the **code**; show `cancelReason` as a reason line when not null; never build the message from `cancelReason` alone. If `paymentStatus == "REFUNDED"` add "Your refund is on its way to your original payment method."

Notification for a rejected order: title `Order rejected`, "Sorry, we could not accept order 1001 (Item out of stock). Anything you paid will be refunded." Any other cancel: `Order update` "Order 1001 is now cancelled."

### 8.6 Edge cases
- Cancelling a PAYMENT_PENDING order whose payment is being confirmed: if the money lands after the cancel, the server refunds it ("paid after expiry"/order cancelled); tell the customer.
- Double tap: second call answers 409 `ILLEGAL_TRANSITION`; treat as success if the order now shows `CANCELLED`.
- Staff reject at the same time: you may see `REJECTED_BY_SHOP` instead of your cancel; render by code.

### 8.7 Dart model sketch
```dart
String cancelTitle(String? code) => switch (code) {
  'CUSTOMER_CANCELLED' => 'You cancelled this order',
  'REJECTED_BY_SHOP' => 'The shop could not accept this order',
  'CANCELLED_BY_SHOP' => 'The shop cancelled this order',
  'PAYMENT_TIMEOUT' => 'Payment time ran out',
  'PAYMENT_FAILED' => 'Payment failed',
  _ => 'Cancelled' };
```

### 8.8 Acceptance checklist
- Given status CONFIRMED, When the customer cancels, Then 200, status CANCELLED, wallet restored, button gone.
- Given status PACKED, When the button is hidden but a stale screen calls cancel, Then 409 reloads the order and shows the message.
- Given `REJECTED_BY_SHOP` with reason "Item out of stock", When viewing, Then the shop-rejection title and the reason line show.
- Given `PAYMENT_TIMEOUT`, When viewing, Then there is no reason line and no refund text unless `paymentStatus` is `REFUNDED`.
- Given `cancelCode` null on a CANCELLED order, When viewing, Then the generic "Cancelled" shows.

---

## 9. Reorder

### 9.1 Goal
"Order again": check each old line against today's catalog and stock so the cart can be filled.

### 9.2 Who and settings
Customer, own order (404 otherwise).

### 9.3 Screens and fields
Result sheet listing each old line with new price, available quantity and a reason.

### 9.4 API sequence
1. `GET /api/v1/orders/{id}/reorder` (button on past orders).
   ```json
   { "orderId": "e3b0...", "orderNo": "1001", "allAvailable": false,
     "items": [ { "productId": "0b0e...", "name": "Basmati Rice 5kg", "nameGu": "...", "unitLabel": "5 kg", "requestedQty": 3, "qty": 2,
                  "unitPricePaise": 46000, "priceChanged": true, "available": true, "reason": "LOW_STOCK", "imageUrl": "/img/product/rice_400.jpg" } ] }
   ```
2. Add every line with `qty > 0` to the cart using `qty` (not `requestedQty`), tell the customer about the rest, open the cart and quote normally.

### 9.5 Derived values
`qty = min(requestedQty, stock now)`; `available = qty > 0`; `reason`: null (full), `LOW_STOCK` (part), `OUT_OF_STOCK`, `UNAVAILABLE` (removed, hidden or counter-only; price shown is the old price, `priceChanged` false). `allAvailable` is true only when every line is available in full. `unitPricePaise` is today's price **without** flash (do not promise a flash price; the quote decides). `imageUrl` is the current picture or the old snapshot.

### 9.6 Edge cases
- Cancelled or old orders can be reordered too.
- The result never reserves stock; stock can change before checkout (OUT_OF_STOCK at place).
- If every line is unavailable, show "Nothing from this order is available now" and do not open the cart.

### 9.7 Dart model sketch
```dart
class ReorderLine { final String productId, name, nameGu, unitLabel; final String? reason, imageUrl;
  final int requestedQty, qty, unitPricePaise; final bool priceChanged, available; }
class Reorder { final String orderId, orderNo; final bool allAvailable; final List<ReorderLine> items; }
```

### 9.8 Acceptance checklist
- Given a line with stock for 2 of 3, When reordering, Then 2 are added and the sheet says "Only 2 of 3 available".
- Given a removed product, When reordering, Then the line is listed as unavailable and not added.
- Given `priceChanged` true, When shown, Then the new price is displayed next to the old one.

---

## 10. Returns: customer view, admin flow, preview, refund maths, policies, defective review

### 10.1 Goal
After delivery, staff record goods that came back, see the **exact refund before confirming**, refund money correctly (coupon and coin shares included), put goods back on the shelf or send them to a review queue, and the customer sees the result.

### 10.2 Who and settings
- **Customers cannot request or create returns in the API.** They can only read returns the shop recorded: `GET /orders/{id}/returns`.
- Staff (ADMIN or CASHIER) record returns. A customer token on the admin routes gets 403. No time window or extra permission applies to online-order returns.
- Setting `returns.couponPolicy`: `KEEP` (default) or `CLAWBACK_BELOW_MINIMUM`.
- Only orders in `DELIVERED` or `PICKED_UP` can have returns (else 409 `ORDER_NOT_DELIVERED`).

### 10.3 Screens and fields

**Customer: returns on an order** (`GET /orders/{id}/returns`, oldest first, empty array when none). Per return show date (`createdAt`), reason, `refundPaise`, mode text, coin changes and the items.

| Field | Type | Meaning |
|---|---|---|
| `id`, `orderId`, `orderNo` | string | |
| `reason` | string | staff text |
| `refundPaise` | int | money back for this return (already net of coupon and coin shares and any clawback; delivery fee not included) |
| `refundMode` | `ONLINE` / `CASH` / `NONE` | `ONLINE`: goes back to the original payment method (asynchronously). `CASH`: the shop refunds directly (cash-on-delivery orders). `NONE`: refund is 0 |
| `coinsClawedBack` | int | earned coins taken back |
| `coinsRestored` | int | spent coins given back (as a new lot valid 30 days) |
| `couponClawbackPaise` | int | coupon discount taken back (policy `CLAWBACK_BELOW_MINIMUM`), already inside `refundPaise` |
| `items[]` | `productId, name, qty, refundPaise, restocked, couponSharePaise` | one row per **order line** (a flash-sale product can appear as two rows) |

Suggested texts: `ONLINE` "Rs X refunded to your original payment method"; `CASH` "Rs X will be refunded by the shop"; `NONE` "Return recorded".

**Staff: record a return** (button only when status is DELIVERED or PICKED_UP):

| Field | Type | Required | Validation mirrored from the server |
|---|---|---|---|
| `reason` | string | yes for the real call (optional for preview) | trimmed, 1..200 chars, else 400 `fields.reason` = `1 to 200 characters` |
| `items[]` | list | yes | 1..100 entries (400 `fields.items` = `1 to 100 products`); each product **at most once** (400 `fields.items` = `a product appears twice`) |
| `items[].productId` | UUID | yes | must be on this order (400 `fields.productId` = `is not on this order`) |
| `items[].qty` | int | yes | > 0 (400 `fields.qty` = `must be positive`); not above what is still returnable (409 `RETURN_EXCEEDS_SOLD`) |
| `items[].restock` | bool | no | `null`/`true` = back on the shelf; `false` = "claimed defective" (refund still paid; goods go to the review queue) |
| Header `Idempotency-Key` | string | yes (real call only) | one random UUID per submission |

Screen: list of order items grouped by product with "sold / already returned / returnable" and a qty stepper (0..returnable), a restock switch per product ("Fine to resell" on / "Defective" off), a reason field, then the **preview panel** and a Confirm button.

Remaining returnable per product (compute for the stepper only): sum of `qty` of that product's order lines minus sum of `qty` of that product in earlier returns (`GET /admin/orders/{id}/returns`). The server enforces the real limit.

### 10.4 API sequence (staff)

1. Open order: `GET /api/v1/admin/orders/{id}` (items, status) and `GET /api/v1/admin/orders/{id}/returns` (earlier returns).
2. After each stepper change (debounce): **preview** `POST /api/v1/admin/orders/{id}/returns/preview`, same body as the real call, **no Idempotency-Key**, `reason` optional, nothing is stored or moved:
   ```json
   { "reason": "Packet damaged", "items": [ { "productId": "0b0e8c0e-...", "qty": 1, "restock": false } ] }
   ```
   Response 200:
   ```json
   { "orderId": "...", "orderNo": "VM-1024", "couponPolicy": "KEEP", "refundPaise": 31500,
     "couponShareRemovedPaise": 3500, "coinShareRemovedPaise": 0, "couponClawbackPaise": 0, "deliveryFeeRefundedPaise": 0,
     "items": [ { "orderItemId": "...", "productId": "...", "name": "Oil", "qty": 1, "grossPaise": 35000,
                  "couponSharePaise": 3500, "coinSharePaise": 0, "clawbackPaise": 0, "refundPaise": 31500 } ] }
   ```
   Per line: `refundPaise = grossPaise - couponSharePaise - coinSharePaise - clawbackPaise` exactly. Show "Price Rs 350 - coupon Rs 35 - coins Rs 0 - taken back Rs 0 = refund Rs 315". `deliveryFeeRefundedPaise` is always 0: say "Delivery fee is not refunded". Preview rows are per order line (`orderItemId`).
3. Confirm: `POST /api/v1/admin/orders/{id}/returns` with header `Idempotency-Key: <uuid>`, same body, `reason` required. Response **201** `ReturnView` (fields in 10.3). Show `refundPaise` and `refundMode`: for `CASH` tell the cashier to hand cash; for `ONLINE` "sent to the customer's payment method".
4. Refresh the order and its returns. An SSE `order` event arrives with `status == previousStatus` (something changed). **The order's `paymentStatus` is not changed by a return** (it stays `CAPTURED`); read refunds from the returns list or the refunds queue.
5. Retry safety: on timeout call again with the **same** key; the server returns the first stored return (201) without refunding twice, even if the body differs. The key is global (not per order): a key reused on another order returns the *first* return, so never reuse keys.

Errors and the UI reaction (preview and real call share the first four):

| Status / code | When | UI |
|---|---|---|
| 400 `VALIDATION_FAILED` | reason, items, productId, qty, missing key | highlight `details.fields` |
| 404 `NOT_FOUND` | unknown order | back to list |
| 409 `ORDER_NOT_DELIVERED` | status is not DELIVERED / PICKED_UP | hide the button; reload |
| 409 `RETURN_EXCEEDS_SOLD` | more than still returnable; `details.productId`, `details.returnable` (total left across split lines) | set the stepper to `returnable`, re-preview |
| 409 `REFUND_EXCEEDS_ORDER` | total refunds would exceed the order total; `details.totalPaise` | contact owner; do not retry |
| 409 `REFUND_EXCEEDS_PAYMENT` | online refund would exceed what was paid; `details.paidPaise, alreadyRefundedPaise, requestedPaise` | show the three numbers; refund the rest in cash if the owner agrees (record outside the app) |
| 403 | customer token | n/a |

### 10.5 Derived values: what a return refunds (server only)

Every order line keeps its own **coupon share** (`items[].couponDiscountPaise`, 0 on lines the coupon did not cover) and gets a **coin share** (the coins spent, spread over the lines in proportion to what each cost after its coupon share).

`refund of the returned pieces = price of the pieces - their coupon share - their coin share` (- clawback, below). **The delivery fee is never refunded.** Shares are cumulative and rounded half-up per piece count, so any sequence of partial returns adds up to exactly `lineTotal - couponShare - coinShare`; the last piece takes the remainder. Example: a Rs 100.00 line, qty 3, no coupon: returning 1, 1, 1 refunds 3333, 3334, 3333 paise (the first return is `total/3`, as in `OrderReturnIT`).

Coupon uses and limits are **not** given back by a partial return (only a cancelled order releases the use).

**Worked example (whole-cart coupon: 10% with minimum Rs 1,000; no coins).** Order: Rice Rs 400 (coupon Rs 40), Oil Rs 350 (Rs 35), Dal Rs 250 (Rs 25); total Rs 1,000, customer paid Rs 900.
- Category coupon (10% on Rice only, discount Rs 40): returning Oil refunds the full Rs 350; returning Rice refunds Rs 360.
- Item coupon (20% on Dal only, minimum Rs 200, discount Rs 50): returning Rice refunds Rs 400; returning Dal refunds Rs 200.
- Whole-cart coupon, policy **`KEEP`**: return Oil -> refund 350 - 35 = **Rs 315**. The customer keeps the coupon on Rice and Dal (Rs 65) although what is left (Rs 650) is below the Rs 1,000 minimum. Later: Rice Rs 360, Dal Rs 225. Total refunded Rs 900 = paid.
- Whole-cart coupon, policy **`CLAWBACK_BELOW_MINIMUM`**: return Oil: what is left of the covered items (Rs 650) is below the minimum (Rs 1,000), so the coupon discount on the **kept** items (40 + 25 = Rs 65) is taken out of this refund: 315 - 65 = **Rs 250**, `couponClawbackPaise: 6500` (never below zero; if the refund is smaller than the amount, the refund is 0 and the rest is forgiven). The kept lines then carry no coupon share, so later returns give the clawed amount back in proportion: Rice Rs 400, Dal Rs 250. Total refunded Rs 900 again; the clawback only changes *when* money comes back. Nothing is clawed when all covered pieces are returned, or while the covered remainder still meets the minimum. The coupon's minimum is **stored on the order when placed**, so editing the coupon later never changes refunds; old orders without a stored minimum behave as `KEEP`.
- With coins (same order, Rs 50 of coins spent, `KEEP`): coin shares over Rice/Oil/Dal (their prices after coupon: 360, 315, 225) are Rs 20, Rs 17.50 and Rs 12.50. Returning Oil refunds 350 - 35 - 17.50 = **Rs 297.50** (`29750` paise).

**Coins on a return**: earned coins on the returned goods are taken back (`coinsClawedBack`; only as many as are still unspent) and spent coins on them are restored (`coinsRestored`, new lot valid 30 days), both in proportion to the returned price relative to the order subtotal, rounded down. The app shows these numbers; it never computes them.

**Refund mode**: `ONLINE` only when the order was paid ONLINE and its payment was captured: a partial refund is queued with reason `return <orderNo>` (sent to Razorpay shortly; see section 11). Otherwise `CASH` (cash-on-delivery orders and anything not paid online). `NONE` when the refund is 0.

**Stock**: `restock:true` puts the units back on the shelf (stock history reason `ORDER_RETURN`, `refId` = return id, `ref` = order number). `restock:false` does not add them to sellable stock: they wait in the defective review queue with status `PENDING_REVIEW`; the customer is still refunded.

**Customer notification** (`Return accepted`): ONLINE "Rs X will go back to your original payment method."; CASH "Rs X will be refunded by the shop."; NONE "The return is recorded."

### 10.6 Defective review flow (staff / owner)
A "defective" claim is not a fact. Statuses: `PENDING_REVIEW` -> (`HELD` | `RESTOCKED`); `HELD` -> (`WRITTEN_OFF` | `RETURNED_TO_SUPPLIER`). Each step once.

1. Queue: `GET /api/v1/pos/stock/defective?status=PENDING_REVIEW&page=0&size=20` (CASHIER or ADMIN). Row: `id, productId, productName, qty, source ("ORDER_RETURN"|"POS_RETURN"), refId, note (the return reason), status, createdAt, createdBy, reviewedAt, reviewedBy, reviewNote, resolvedAt, resolvedBy, resolveNote`. Other `status` values: `HELD`, `WRITTEN_OFF`, `RETURNED_TO_SUPPLIER`, `RESTOCKED`; anything else is 400 `fields.status`.
2. Decide: `POST /api/v1/pos/stock/defective/{id}/review` body `{ "decision": "CONFIRM_DEFECTIVE" | "RESTOCK", "note": "optional, max 200" }` -> 200 row with the new status. `RESTOCK` adds the units to sellable stock; `CONFIRM_DEFECTIVE` -> `HELD`.
   - 422 `DEFECTIVE_SELF_REVIEW`: a cashier cannot confirm a batch they took back themselves (admin can; `RESTOCK` has no such limit).
   - 409 `DEFECTIVE_ALREADY_REVIEWED` (`details.status`): reload the queue.
   - 400 `fields.decision` / `fields.note` / 404 `NOT_FOUND`.
3. Admin final decision on `HELD`: `POST /api/v1/admin/stock/defective/{id}/resolve` body `{ "action": "WRITE_OFF" | "RETURN_TO_SUPPLIER", "note": "..." }`; 409 `DEFECTIVE_NOT_HELD`. Full report: `GET /api/v1/admin/stock/defective` (all statuses) and `/summary`.
UI: two buttons "Defective" and "Fine, put back on sale", show product, qty, return reason and who took the return.

### 10.7 Edge cases
- **Concurrency**: two staff returning the same item: the second gets 409 `RETURN_EXCEEDS_SOLD` with the new `returnable`.
- **Preview then real call**: amounts can differ if another return was recorded in between; always show the real response.
- **Flash-split products**: the request still has one entry per product; units are taken from the flash line first, then the normal line; the response and preview show one row per order line.
- **Mixed restock**: for a product part resellable and part defective you cannot send the same product twice; record two returns (two keys).
- Policy changes (`returns.couponPolicy`) apply from the next preview/return; each preview returns `couponPolicy` so show it.
- Empty states: no earlier returns -> empty array; a fully returned order has `returnable 0` everywhere, disable the button.
- A return on a cancelled order is impossible (`ORDER_NOT_DELIVERED`).

### 10.8 Dart model sketches
```dart
class ReturnItem { final String productId, name; final int qty, refundPaise, couponSharePaise; final bool restocked; }
class ReturnView { final String id, orderId, orderNo, reason, refundMode; // ONLINE|CASH|NONE
  final int refundPaise, coinsClawedBack, coinsRestored, couponClawbackPaise; final DateTime createdAt; final List<ReturnItem> items; }
class ReturnPreviewLine { final String orderItemId, productId, name; final int qty, grossPaise, couponSharePaise, coinSharePaise, clawbackPaise, refundPaise; }
class ReturnPreview { final String orderId, orderNo, couponPolicy; // KEEP|CLAWBACK_BELOW_MINIMUM
  final int refundPaise, couponShareRemovedPaise, coinShareRemovedPaise, couponClawbackPaise, deliveryFeeRefundedPaise; final List<ReturnPreviewLine> items; }
class ReturnInput { final String productId; final int qty; final bool restock; }
class DefectiveBatch { final String id, productId, productName, source, status; final int qty; final String? refId, note; /* + audit fields */ }
```

### 10.9 Acceptance checklist
- Given a DELIVERED order, When staff pick 1 Oil and open the panel, Then a preview shows gross, coupon, coin, clawback and refund for that line without creating anything.
- Given the preview, When Confirm is tapped twice (timeout retry with the same key), Then one return exists and stock/refund moved once.
- Given restock off, When confirmed, Then the customer is refunded, stock is unchanged and a `PENDING_REVIEW` batch appears in the queue.
- Given a COD order, When returned, Then `refundMode` is `CASH` and the screen tells the cashier to hand cash.
- Given an online order, When returned, Then `refundMode` is `ONLINE`, a refund appears in the admin queue with reason `return <orderNo>`.
- Given policy `CLAWBACK_BELOW_MINIMUM` and the covered remainder falls below the minimum, When previewing, Then `couponClawbackPaise > 0` and the explanation line is visible.
- Given an order that is not delivered, When opened, Then no return button is shown; a direct call answers 409 `ORDER_NOT_DELIVERED`.
- Given the customer opens a delivered order, When returns exist, Then they are listed read-only with refund text per mode; no "request return" button exists.
- Given a cashier took back the goods, When the same cashier confirms "Defective", Then 422 `DEFECTIVE_SELF_REVIEW` asks for someone else.

---

## 11. Refunds queue and payment problems (admin)

### 11.1 Goal
Let the owner see money going back to customers, retry the refunds that failed, and see wrong-amount or duplicate payments.

### 11.2 Who and settings
**ADMIN only** (a cashier gets 403; cashiers see the problem only as a notification). Needs Razorpay keys working for refunds to be issued.

### 11.3 Screens and fields
- **Refunds list**: tabs All / PENDING / PROCESSING / ISSUED / FAILED. FAILED is the to-do list; show its count as a badge on the admin home (`GET /admin/refunds?status=FAILED&size=1` -> `total`).
- **Payment problems list**: kind `MISMATCH` (wrong amount) or `DUPLICATE` (second payment).

### 11.4 API sequence
1. `GET /api/v1/admin/refunds?status=FAILED&page=0&size=20`. `status` optional, case-insensitive (`PENDING`, `PROCESSING`, `ISSUED`, `FAILED`); anything else is 400 `fields.status`. Newest first. Row:
   ```json
   { "id": "...", "paymentId": "...", "orderId": "e3b0...", "amountPaise": 88000, "status": "FAILED", "attempts": 10,
     "lastError": "HttpClientErrorException", "reason": "order cancelled", "createdAt": "2026-11-08T10:30:00Z" }
   ```
   `reason` values you will see: `order cancelled`, `paid after expiry`, `amount mismatch`, `duplicate payment`, `return <orderNo>`. `lastError` is up to 500 chars of provider error text; show it in a detail sheet. `orderId` can be null if the payment row is gone.
2. `POST /api/v1/admin/refunds/{id}/retry` (no body) -> **204**. Show the button only for `FAILED`. The refund returns to `PENDING`, attempts reset to 0, and is sent straight away (it can already be `ISSUED` on the next list). Errors: 409 `REFUND_NOT_FAILED` ("Only a failed refund can be retried"; also for an unknown id) -> refetch.
3. `GET /api/v1/admin/payments/anomalies?page&size` -> `{ items: [ { id, paymentId, orderId, kind, expectedPaise, receivedPaise, providerRef, createdAt } ], page, size, total, totalExact, hasMore }` newest first. The refunds these caused are in the refunds list with reason `amount mismatch` / `duplicate payment`.
4. Payment methods screen: `GET /admin/payment-methods` -> `[ { code, enabled, configured } ]`; `PUT` with **both** methods exactly once, for example `[ { "code": "ONLINE", "enabled": true }, { "code": "COD", "enabled": true } ]`. Errors: 400 `fields.methods` (`must list every method`, `duplicate code "COD"`, `keep at least one payment method on`), 400 `fields.code` (`unknown method`), 422 `PAYMENT_NOT_CONFIGURED` (enabling ONLINE without Razorpay keys: disable the toggle while `configured` is false).

### 11.5 State machine (refund)
`PENDING` (queued; picked up within seconds after the order change commits, then a job every 30 s) -> `PROCESSING` (being sent) -> `ISSUED` (Razorpay accepted; final). A send error puts it back to `PENDING` with `attempts + 1`; after **10** failed attempts it becomes `FAILED` and needs a person (retry resets attempts). A refund stuck in `PROCESSING` for over 10 minutes goes back to `PENDING`; before re-sending, the server asks the provider whether the refund already went through, so a retry never pays twice. A cancel or reject never waits for or fails because of Razorpay.

### 11.6 Edge cases
- A customer never sees refund rows; they see `paymentStatus: REFUNDED` on a cancelled order and `refundMode` / `refundPaise` on returns.
- Many `PENDING` rows for a while means a provider outage; do not mass-retry (retry works only for `FAILED`).
- A return refund above what was paid is refused at return time (`REFUND_EXCEEDS_PAYMENT`), so it never appears here.
- Paging: use `hasMore`.

### 11.7 Dart model sketch
```dart
class RefundRow { final String id, paymentId, status, reason; final String? orderId, lastError; final int amountPaise, attempts; final DateTime createdAt; }
class PaymentAnomaly { final String id, paymentId, kind, providerRef; final String? orderId; final int expectedPaise, receivedPaise; final DateTime createdAt; }
class PaymentMethodAdmin { final String code; final bool enabled, configured; }
```

### 11.8 Acceptance checklist
- Given a FAILED refund, When the admin taps Retry, Then 204 and the row leaves the FAILED tab on refetch.
- Given an ISSUED refund, When listed, Then no Retry button is shown; a forced call answers 409 `REFUND_NOT_FAILED`.
- Given a cashier token, When opening the refunds screen, Then 403 is handled by hiding the screen (never shown to cashiers).
- Given a wrong-amount payment, When the admin opens payment problems, Then a `MISMATCH` row shows expected and received paise and links the order.
- Given ONLINE is not configured, When the admin tries to enable it, Then the toggle is disabled and the 422 message is shown if forced.

---

## 12. Admin order desk: list, filters, detail, transitions, reject, notes

### 12.1 Goal
Let counter staff work online orders: find them, accept, pack, hand over, reject, and see customer contact details.

### 12.2 Who and settings
ADMIN and CASHIER (`/admin/orders/**`). Refund and payment screens are ADMIN only (section 11). Stock is deducted when an order first enters CONFIRMED (or any later stock-holding status).

### 12.3 Screens and fields

**List** `GET /api/v1/admin/orders`. All filters combine with each other:

| Param | Type | Rules and errors (400 `VALIDATION_FAILED`, field named) |
|---|---|---|
| `status` | string | One status or comma list: `status=PLACED,CONFIRMED`; case-insensitive; blank = all. Unknown value: `fields.status` = `must be one of PAYMENT_PENDING, PLACED, CONFIRMED, PACKED, OUT_FOR_DELIVERY, READY_FOR_PICKUP, DELIVERED, PICKED_UP, CANCELLED`. |
| `needsAction` | bool | `true` = `PLACED, CONFIRMED, PACKED, OUT_FOR_DELIVERY, READY_FOR_PICKUP`. With `status`: `fields.needsAction` = `cannot be used together with status`. |
| `q` | string | Order number (contains) **or** customer name, mobile or e-mail (contains); case-insensitive; trimmed; `%` and `_` ignored; **max 100 chars** (`fields.q`). Blank = none. Debounce. |
| `date` | `YYYY-MM-DD` | One India-time day. Not with `from`/`to` (`fields.date` = `cannot be used together with from or to`); bad value `must be a valid date`. |
| `from` | date or date-time | Placed at or after. ISO instant (with `Z`/offset), zone-less date-time (India time) or plain date (start of that India day). Bad: `fields.from` = `must be a valid date`. |
| `to` | date or date-time | A **plain date includes the whole day**; an instant or zone-less date-time is **excluded** (`to=2026-11-09T12:00:00` = before noon). Must be after `from` (`fields.to` = `must be after from`). |
| `page`, `size` | int | page from 0; size 1..50 (default 20). |

Newest first by `placedAt`. Row (`AdminSummary`):
`id, orderNo, status, fulfilment, totalPaise, itemCount (units), placedAt, customerName, customerEmail, customerMobile, subtotalPaise, couponCode, couponDiscountPaise, coinsUsed, coinDiscountPaise, deliveryFeePaise, paymentMethod, paymentStatus, cancelledBy, cancelCode` (customer fields are nullable), plus paging fields. Note: `needsAction` is also a number on `GET /admin/dashboard` (cached about 30 s): badge the Orders tab with it and open the list with `?needsAction=true`.

Tabs to build: "Needs action" (`needsAction=true`), "Awaiting payment" (`status=PAYMENT_PENDING`; staff cannot confirm these), "Today" (`date=<today>`), "Done" (`status=DELIVERED,PICKED_UP`), "Cancelled" (`status=CANCELLED`), plus search box and date picker.

**Detail** `GET /api/v1/admin/orders/{id}` returns all `OrderView` fields **flattened at the top level** plus `customerName, customerEmail, customerMobile`. Show address, slot, items with images, money, `cancelReason` etc., timeline and returns (section 10), and `paymentSession` while pending.

**Actions** (buttons built from the table below).

### 12.4 API sequence
1. Open `GET /admin/orders/stream` (SSE; staff receive every `order` event plus `stock` and `catalog`, not `wallet`/`credit`). On `order` with `previousStatus == null` (new order): sound/badge and refetch the list. Reload after each reconnect (events are not replayed; 10-minute stream life; up to 200 staff streams).
2. List -> detail.
3. **Move status**: `POST /api/v1/admin/orders/{id}/status` body `{ "status": "CONFIRMED", "note": "optional, max 200" }` -> 200 `AdminView`. Repeating the current status is a no-op 200.
4. **Reject**: `POST /api/v1/admin/orders/{id}/reject` body `{ "reason": "Item out of stock" }` (`reason` required, trimmed, 1..200: blank is 400 `fields.reason` = `tell the customer why the order is rejected`, over 200 = 400) -> 200 `AdminView` with `status: CANCELLED`, `cancelledBy: "SHOP"`, `cancelCode: "REJECTED_BY_SHOP"`, `cancelReason: "Item out of stock"`. Confirm first (irreversible). Prefer this over status `CANCELLED`.
5. Cancel through status: `{ "status": "CANCELLED", "note": "Cannot deliver to this area" }` gives `cancelCode: "CANCELLED_BY_SHOP"` and the (trimmed) note as `cancelReason`; with no note `cancelReason` is null.

Errors for status/reject:

| Status / code | When | UI |
|---|---|---|
| 400 `VALIDATION_FAILED` | unknown `status` (message lists the real values), `note` > 200, bad id | fix |
| 404 `NOT_FOUND` | unknown order | back |
| 409 `AWAITING_PAYMENT` | order is `PAYMENT_PENDING` and target is not `CANCELLED` | explain "customer has not paid"; offer Reject |
| 409 `ILLEGAL_TRANSITION` | order is final (`DELIVERED`, `PICKED_UP`, `CANCELLED`), or target is `PAYMENT_PENDING` | reload; hide buttons |
| 422 `INVALID_TRANSITION` | target does not fit the fulfilment (`details.fulfilment`), or going back to `PLACED` after stock was set aside (`details.from`); `message` is ready to show | show message; nothing changed |
| 409 `OUT_OF_STOCK` | moving to a stock-holding status (usually `CONFIRMED`) and the shelf is short; `details.items = [ { productId, available } ]`; order unchanged | show items; offer Reject |
| 409 `VERSION_CONFLICT` | changed concurrently | reload and retry |

### 12.5 State machine (allowed moves; the current status itself is a no-op)

| From | DELIVERY order may go to | PICKUP order may go to |
|---|---|---|
| `PAYMENT_PENDING` | `CANCELLED` only (the system moves it to `PLACED` when paid) | same |
| `PLACED` | `CONFIRMED`, `PACKED`, `OUT_FOR_DELIVERY`, `DELIVERED`, `CANCELLED` | `CONFIRMED`, `PACKED`, `READY_FOR_PICKUP`, `PICKED_UP`, `CANCELLED` |
| `CONFIRMED` | `PACKED`, `OUT_FOR_DELIVERY`, `DELIVERED`, `CANCELLED` | `PACKED`, `READY_FOR_PICKUP`, `PICKED_UP`, `CANCELLED` |
| `PACKED` | `CONFIRMED`, `OUT_FOR_DELIVERY`, `DELIVERED`, `CANCELLED` | `CONFIRMED`, `READY_FOR_PICKUP`, `PICKED_UP`, `CANCELLED` |
| `OUT_FOR_DELIVERY` | `CONFIRMED`, `PACKED`, `DELIVERED`, `CANCELLED` | n/a |
| `READY_FOR_PICKUP` | n/a | `CONFIRMED`, `PACKED`, `PICKED_UP`, `CANCELLED` |
| `DELIVERED`, `PICKED_UP`, `CANCELLED` | nothing (409) | nothing (409) |

`PLACED` is never offered once the order is `CONFIRMED` or later. Offer only these buttons; still handle 409/422.

Side effects:
- First entry to a stock-holding status (`CONFIRMED` and later) **deducts stock** (stock history reason for the order). Cancel/reject gives it back only if it was deducted.
- `DELIVERED` / `PICKED_UP`: credits `coinsEarned` once (wallet event follows) and marks a COD order's `paymentStatus` `CAPTURED`. Irreversible: confirm.
- `CANCELLED` (reject, status or customer): stock, coupon use, coins and flash units released; captured online payment refunded (queued).
- Each change notifies the customer (`Order update` / `Order rejected`).
- Notes: a `note` on a normal status move is stored in the order history for staff (not part of the customer view). For `CANCELLED` it becomes `cancelReason`.
- Audit entries are written for status changes, rejects and returns.

### 12.6 Edge cases
- Two staff acting together: `VERSION_CONFLICT` or `ILLEGAL_TRANSITION`; always reload the order after an error.
- A customer cancels while staff confirm: staff gets 409 (order already `CANCELLED`).
- ONLINE unpaid orders: show a "waiting for payment" state with the `paymentExpiresAt` countdown; only Reject/Cancel are possible.
- `needsAction` excludes `PAYMENT_PENDING`.
- Empty list: show a friendly empty state per tab; no error for no match.
- Total above 10,000 rows: show "10,000+" if `totalExact` is false.
- Cashier restrictions: none on these routes; refunds and payment problems are admin-only.

### 12.7 Dart model sketch
```dart
class AdminOrderSummary { /* OrderSummary fields */ final String? customerName, customerEmail, customerMobile; }
class AdminOrderView { final OrderView order; final String? customerName, customerEmail, customerMobile; } // JSON is flat: parse OrderView.fromJson(json) and read the 3 extra keys
const nextMoves = {
  'DELIVERY': { 'PLACED': ['CONFIRMED','PACKED','OUT_FOR_DELIVERY','DELIVERED','CANCELLED'], 'CONFIRMED': ['PACKED','OUT_FOR_DELIVERY','DELIVERED','CANCELLED'],
                'PACKED': ['CONFIRMED','OUT_FOR_DELIVERY','DELIVERED','CANCELLED'], 'OUT_FOR_DELIVERY': ['CONFIRMED','PACKED','DELIVERED','CANCELLED'], 'PAYMENT_PENDING': ['CANCELLED'] },
  'PICKUP':   { 'PLACED': ['CONFIRMED','PACKED','READY_FOR_PICKUP','PICKED_UP','CANCELLED'], 'CONFIRMED': ['PACKED','READY_FOR_PICKUP','PICKED_UP','CANCELLED'],
                'PACKED': ['CONFIRMED','READY_FOR_PICKUP','PICKED_UP','CANCELLED'], 'READY_FOR_PICKUP': ['CONFIRMED','PACKED','PICKED_UP','CANCELLED'], 'PAYMENT_PENDING': ['CANCELLED'] },
};
```

### 12.8 Acceptance checklist
- Given new online orders, When one is placed, Then the staff stream fires, the Needs-action tab refetches and the badge grows.
- Given a PLACED pickup order, When staff open the menu, Then OUT_FOR_DELIVERY and DELIVERED are not offered.
- Given stock ran out, When staff confirm, Then 409 `OUT_OF_STOCK` lists the products and the order stays PLACED, with Reject offered.
- Given a PAYMENT_PENDING order, When staff try to confirm, Then only Reject/Cancel exist; a forced call answers 409 `AWAITING_PAYMENT`.
- Given `q=98765`, When searching, Then orders of customers whose mobile contains it are listed, combined with the active status tab.
- Given `date=2026-11-09`, When listing, Then only orders placed that India-time day appear; combining with `from` shows the `date` field error.
- Given a CONFIRMED order, When staff press Reject with a blank reason, Then the reason field shows the server message.
- Given a delivered order, When staff try to cancel, Then 409 `ILLEGAL_TRANSITION` and buttons are hidden after reload.

---

## 13. Wallet ledger effects of orders and returns

### 13.1 Goal
Show the customer a correct coin history and keep the balance badge fresh after each order event.

### 13.2 Who and settings
Customer: `GET /wallet` and `GET /wallet/ledger?page&size` (newest first; page object). Coin settings are admin-set (section 3).

### 13.3 Screens and fields
Ledger row: `id, kind, coins (signed), orderNo (order, bill or return number; null for admin adjustments), createdAt, expiresAt`. `kind` is `EARN`, `REDEEM`, `REVERSE`, `ADJUST` or `EXPIRE`. Hide rows with `coins == 0` (refund lots). Show `expiresAt` on EARN and positive ADJUST rows.

### 13.4 API sequence and effects

| Event | Ledger | Balance | SSE `wallet.reason` |
|---|---|---|---|
| Order placed with coins | `REDEEM` (negative) with the order number | down at once, oldest-expiring coins first | `REDEEM` |
| Order cancelled / rejected / payment expired | `REVERSE` (positive) | up; coins go back into their original lots if still valid, else into a fresh lot valid 30 days (a `0` ADJUST row may appear) | `REVERSE` |
| Order DELIVERED / PICKED_UP | `EARN` (positive, once) | up by `coinsEarned`; expires after `expiryMonths` | `EARN` |
| Return recorded | `ADJUST` negative (earned coins taken back, only what is unspent) | down | `CLAWBACK` |
| Return recorded | `ADJUST` positive (spent coins restored, new lot valid 30 days) | up | `RESTORE` |
| Admin gift/deduction | `ADJUST` | changes | n/a here |

A cancelled order never earns coins and shows `coinsEarned: 0`. The order view also carries `coinBalance`.

After each of: place with coins, cancel, status events, return: refetch `GET /wallet` (an SSE `wallet` event also tells you).

### 13.5 Edge cases
- Ledger is paged; use `hasMore`.
- A deduction can never exceed the balance; clawback takes at most the unspent coins.
- Balance shown in the cart can be stale; the order call re-checks (`coins` error).

### 13.6 Dart model sketch
```dart
class LedgerRow { final String id, kind; final int coins; final String? orderNo; final DateTime createdAt; final DateTime? expiresAt; }
```

### 13.7 Acceptance checklist
- Given a delivered order that earns 12 coins, When the status changes, Then a `wallet` event arrives, the wallet refetches and an EARN row appears with an expiry date.
- Given a cancelled order that used 100 coins, When the ledger loads, Then a REVERSE row of +100 shows with the order number.
- Given a return with coins involved, When the ledger loads, Then ADJUST rows show the clawback and restore with the return/order number.

---

## 14. Doc vs code differences

Each item: what the older guide said -> what the code does (trust the code; this playbook follows the code). Items 1 to 17 were corrected in place in `10` and `20` afterwards (see `README.md`); item 18 concerns a code comment only (`20` already states both checks).

1. `20` order list rows: "`itemCount` number of lines" -> code sums quantities (`sum(qty)`), so it is the **total number of units**.
2. `20` payment checkout step 2: "the API does not return the payment deadline" -> the order view has `paymentExpiresAt` (set only while `PAYMENT_PENDING`); the same file's addenda say so. Use it.
3. `20`/addendum cancel codes: `PAYMENT_FAILED` "the online payment failed while the order was waiting" -> no code path publishes the payment-failed event (a Razorpay `payment.failed` only records the attempt and leaves the order pending). Today only the timeout cancels an unpaid order. Handle `PAYMENT_FAILED` defensively.
4. `20` quote error table lists `COUPON_INVALID` reasons without `ITEM` and `FLASH_ITEMS` -> the code also returns those two (with item names in the message).
5. `20` quote response sample has no `flashSaleId`, `listPricePaise`, `flashDiscountPaise` on lines -> they exist (file `40`); one product can be two lines.
6. `20` return sample lacks `couponClawbackPaise` and `items[].couponSharePaise` -> both are returned.
7. `20` basket rules: a single `PUT /cart` line over 99 -> field error `items[n].qty` from bean validation (`must be 99 or less`), not the `quantity must be 1 to 99` text used for quote/place and for merged duplicates (the stored basket would silently cap, but the controller refuses first).
8. `20` text "Rejected by shop: <reason>" in `cancelReason` -> raw reason with `cancelCode: REJECTED_BY_SHOP` (the addendum agrees; old rows are cleaned on read).
9. `20` "cancelling through `status` leaves `cancelReason` empty" -> the `note` becomes `cancelReason` with code `CANCELLED_BY_SHOP`.
10. `20` `POST /admin/orders/{id}/returns`: `reason` is "yes (checked in the service)" and the preview takes the same body -> preview accepts a blank `reason` (it substitutes a placeholder); the real call requires 1..200 chars.
11. `20` flash-split addendum says one request entry per product -> true, but the **response and the preview list one row per order line**, so the same `productId` can appear twice.
12. `20` return text implies the order's money state follows the return -> a return does **not** change the order's `paymentStatus`; read refund state from the return (`refundMode`) and the refunds queue.
13. `20` Idempotency key for returns "global" -> confirmed; note the consequence: reusing a key on a different order returns the first order's return.
14. `20` payment-method section says ONLINE appears when keys are configured -> also, ONLINE is **switched off by default** (seeded `enabled=false`), so a fresh server offers COD only.
15. `10` wallet response sample lacks `expiryMonths` -> `GET /wallet` returns `expiryMonths`.
16. `10` `GET /coupons` sample shows only the old fields -> the response also has `productIds`, `productGroupIds`, `appliesTo`, `appliesToNames`, `appliesToText`.
17. `10` address `pincode` validation "only `@NotBlank`" -> later addendum in `20`: must be exactly 6 digits (400 `fields.pincode`); validate 6 digits in the UI.
18. `OrderStatus` code comment says staff may move an open order "to any other status" -> true only after two checks: fulfilment fit and no return to `PLACED` once stock is deducted (422 `INVALID_TRANSITION`); and an unpaid online order can only be cancelled (409 `AWAITING_PAYMENT`).

(Counted: 18 differences.)

---

## 15. Dart building blocks (shared models)

```dart
class ApiError implements Exception {
  final int status; final String code, message; final String? traceId; final Map<String, dynamic> details;
  Map<String, String> get fields => Map<String, String>.from(details['fields'] ?? {});
  ApiError(this.status, this.code, this.message, this.traceId, this.details);
  factory ApiError.fromJson(int status, Map<String, dynamic> j) =>
      ApiError(status, j['code'] ?? 'UNKNOWN', j['message'] ?? '', j['traceId'], Map<String, dynamic>.from(j['details'] ?? {}));
}
int _i(dynamic v) => (v as num).toInt();            // all *Paise and counts are integers
DateTime? _t(dynamic v) => v == null ? null : DateTime.parse(v as String).toUtc();

class Address { final String id, label, line1, area, pincode; final bool isDefault; }
class Page<T> { final List<T> items; final int page, size, total; final bool totalExact, hasMore; }

// Typed handling of the codes used in this playbook
String? errorHint(ApiError e) => switch (e.code) {
  'PRICE_CHANGED' => 'quote in details.quote',          // 409 on POST /orders
  'OUT_OF_STOCK' => 'items in details.items',           // 409 on POST /orders and staff status moves
  'COUPON_INVALID' => 'details.reason + message',       // 422
  'ORDERS_PAUSED' => 'show message',                    // 422
  'ILLEGAL_TRANSITION' || 'INVALID_TRANSITION' || 'AWAITING_PAYMENT' || 'VERSION_CONFLICT' => 'reload order',
  'ORDER_NOT_DELIVERED' || 'RETURN_EXCEEDS_SOLD' || 'REFUND_EXCEEDS_ORDER' || 'REFUND_EXCEEDS_PAYMENT' => 'return form message',
  'REFUND_NOT_FAILED' => 'refetch refunds',
  'DEFECTIVE_SELF_REVIEW' || 'DEFECTIVE_ALREADY_REVIEWED' || 'DEFECTIVE_NOT_HELD' => 'review queue message',
  _ => null };
```

Error code index (all in this file): `VALIDATION_FAILED` 400; `NOT_FOUND` 404; `PRICE_CHANGED`, `OUT_OF_STOCK`, `ILLEGAL_TRANSITION`, `AWAITING_PAYMENT`, `VERSION_CONFLICT`, `ORDER_NOT_DELIVERED`, `RETURN_EXCEEDS_SOLD`, `REFUND_EXCEEDS_ORDER`, `REFUND_EXCEEDS_PAYMENT`, `REFUND_NOT_FAILED`, `DEFECTIVE_ALREADY_REVIEWED`, `DEFECTIVE_NOT_HELD` 409; `ORDERS_PAUSED`, `COUPON_INVALID`, `INVALID_TRANSITION`, `PAYMENT_NOT_CONFIGURED` (admin switch), `DEFECTIVE_SELF_REVIEW` 422; `PAYMENT_PROVIDER_ERROR` 502; `PAYMENT_NOT_CONFIGURED` 503 (placing); `RATE_LIMITED` 429; `SERVICE_BUSY` 503; `BAD_SIGNATURE` 400 (webhook, server only).
