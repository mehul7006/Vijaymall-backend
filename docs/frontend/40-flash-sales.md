# 40. Flash sales

Audience: Flutter and web teams and the LLM assistants they use. Read `00-overview.md` first (auth, error envelope, pagination, money, idempotency, live events). This file is derived from the backend source (`flash/**`, catalog, quote, order and POS code, and the integration tests `FlashSaleIT` and `FlashSaleOnlineIT`); where the code is silent, this file says so.

Global conventions (not repeated per endpoint):

- Base path `/api/v1`. JSON UTF-8. Money is an integer number of **paise** in fields ending `Paise`. Timestamps are ISO-8601 UTC instants. IDs are UUID strings.
- Error body for every non-2xx: `{ "code", "message", "traceId", "details" }`. Field validation errors are HTTP 400 `VALIDATION_FAILED` with `details.fields = { "<field>": "<message>" }`. A malformed body, a wrong-typed value (for example a non-UUID id, an unparsable date), or an unknown enum is HTTP 400 `VALIDATION_FAILED` with message `Bad request` and no field details.
- Common errors on every authenticated endpoint (not repeated in the tables): 401 `UNAUTHENTICATED`, 403 `FORBIDDEN`, 429 `RATE_LIMITED` (300 calls per minute per client IP), 500 `INTERNAL_ERROR`.
- Who: the two public endpoints need no token. Every `/api/v1/admin/flash-sales...` endpoint is open to **ADMIN and CASHIER** (not to customers: 403). This is wider than the rest of `/admin/**`, which is ADMIN only.
- None of the flash endpoints needs an `Idempotency-Key`. Placing orders and counter bills (which consume flash units) do; see file 20 and file 30.
- JSON null handling: a field documented as "null" may be present with value `null` or absent; treat both the same (the code does not configure null suppression, so this is not guaranteed either way).

---

## Table of contents

1. Concepts
2. Endpoints

| # | Endpoint | Who | One-line purpose |
|---|---|---|---|
| 1 | `GET /api/v1/flash-sales/active` | public | Sales running now, with items, prices, units left, countdown |
| 2 | `GET /api/v1/flash-sales/upcoming` | public | Sales that have not started yet |
| 3 | `POST /api/v1/admin/flash-sales` | ADMIN, CASHIER | Create a sale with its items |
| 4 | `GET /api/v1/admin/flash-sales` | ADMIN, CASHIER | List sales (paged, filter by status) |
| 5 | `GET /api/v1/admin/flash-sales/{id}` | ADMIN, CASHIER | One sale with all items |
| 6 | `PATCH /api/v1/admin/flash-sales/{id}` | ADMIN, CASHIER | Edit sale settings |
| 7 | `POST /api/v1/admin/flash-sales/{id}/items` | ADMIN, CASHIER | Add a product to a sale |
| 8 | `PUT /api/v1/admin/flash-sales/{id}/items/{itemId}` | ADMIN, CASHIER | Replace one item's settings |
| 9 | `DELETE /api/v1/admin/flash-sales/{id}/items/{itemId}` | ADMIN, CASHIER | Remove an item that never sold |
| 10 | `POST /api/v1/admin/flash-sales/{id}/start-now` | ADMIN, CASHIER | Start a scheduled sale immediately |
| 11 | `POST /api/v1/admin/flash-sales/{id}/end-now` | ADMIN, CASHIER | End a running sale immediately |
| 12 | `POST /api/v1/admin/flash-sales/{id}/cancel` | ADMIN, CASHIER | Cancel a scheduled or running sale |
| 13 | `POST /api/v1/admin/flash-sales/{id}/duplicate` | ADMIN, CASHIER | Copy a sale onto a new schedule |
| 14 | `GET /api/v1/admin/flash-sales/{id}/report` | ADMIN, CASHIER | Report for one sale |
| 15 | `GET /api/v1/admin/flash-sales/report` | ADMIN, CASHIER | Report across sales that started in a date range |
| 16 | `DELETE /api/v1/admin/flash-sales/{id}` | ADMIN, CASHIER | Delete a sale that never started and sold nothing |

15 endpoints, then: Where flash prices appear in other endpoints, Customer app flow, Staff app flow, Notifications and live events, Things the code does not settle.

---

# Concepts

## Sale lifecycle (status is computed, never stored)

`status` is worked out from the clock and `cancelledAt` on every read. Values: `SCHEDULED`, `ACTIVE`, `ENDED`, `CANCELLED`.

| Status | Rule | Can be edited? |
|---|---|---|
| `CANCELLED` | `cancelledAt` is set (checked first) | No (409 `FLASH_NOT_ALLOWED`) |
| `SCHEDULED` | now is before `startsAt` | Yes, everything |
| `ACTIVE` | `startsAt <= now < endsAt` | Only name, description, bannerText and `endsAt`; items can be added or raised, see below |
| `ENDED` | `now >= endsAt` | No (409 `FLASH_NOT_ALLOWED`) |

Transitions:

- Create with `startsAt` in the future gives `SCHEDULED`; with `startsAt` omitted (or within one minute in the past) gives `ACTIVE` at once.
- `SCHEDULED` becomes `ACTIVE` by itself when the clock reaches `startsAt`, or when staff call **start-now**.
- `ACTIVE` becomes `ENDED` by itself at `endsAt`, or when staff call **end-now** (which sets `endsAt` to now).
- `SCHEDULED` or `ACTIVE` becomes `CANCELLED` by **cancel**. Cancel stops the sale price at once. There is no un-cancel and no un-end; duplicate the sale instead.
- Because status is computed, a sale becomes `ACTIVE` or `ENDED` at the exact second, not at the next one-minute job run. The job (every 60 s) only sends staff notifications and live events; see "Notifications and live events".

## General discount versus per-item override

A sale has a default discount (`discountKind` + `discountValue`). Each item may have its own (`discountKind` + `discountValue` on the item). The rule that applies to an item (`effectiveKind`, `effectiveValue` in the staff view) is the item's own when it names one, else the sale's.

| Kind | `discountValue` means | Rule |
|---|---|---|
| `PERCENT` | whole percent off the list price | 1 to 90 |
| `FIXED_PRICE` | the sale price in paise | at least 100 (Rs 1), and below the product's current list price when the item is saved |
| `NONE` | ignored (stored as 0) | On the sale: no default, so every item needs its own discount. On an item: "follow the sale's default" (the same as omitting the kind) |

Price arithmetic (server only; the app never computes it):

- The sale price is always worked out from the product's **current** list price (`pricePaise`). If staff edit the product's price while the sale runs, the sale price moves with it (20 percent off Rs 100 is Rs 80; after the list price becomes Rs 120 it is Rs 96).
- PERCENT: `list * (100 - percent) / 100`, rounded half up to the paisa. Never below Rs 1 (100 paise), never above the list price.
- The sale price must be strictly below the list price, otherwise the item has **no offer** (a product with a list price of Rs 1 or less can never be on sale; a FIXED_PRICE at or above a list price that later fell is ignored). Such an item silently disappears from the public lists and from `flash` on the product.
- `discountPercent` is the whole percent saved, rounded to nearest.

## Units cap and per-customer limit

- `maxQty` (per item, optional): the total number of units of that product that may be sold **at the sale price** during the whole sale, across all customers and both channels. `null` means no cap.
- `soldQty`: units counted so far (staff view). Units from cancelled/rejected/failed online orders are given back and not counted.
- `unitsLeft`: `maxQty - soldQty` (never below 0), or `null` when there is no cap.
- **`unitsLeft` null means "no limit", not "unknown" and not "sold out".** `unitsLeft == 0` means sold out: the product then sells at the normal price again (it is hidden from `flash` on product cards, but it stays in the `/active` list with `unitsLeft: 0` so the app can show "Sold out").
- The cap is enforced atomically by the server at order time, so it can never oversell, even if the screen is stale.
- `perCustomerLimit`: the most units of **one product** a single customer may buy at the sale price in this sale. `0` means no limit. On the sale it is the default; on an item, a non-null value overrides it (an item value of 0 means "no limit for this item", even if the sale default is higher). In staff views `effectivePerCustomerLimit` is what applies. Counted per customer across orders (earlier orders of the same customer count; cancelled orders do not).
- The public `unitsLeft` is the global remaining, not the customer's own remaining. The per-customer remaining only shows up in the cart quote (the quote line simply splits).

## Countdown: `now`, `secondsToEnd`, `secondsToStart`

`/active` and `/upcoming` return `now` (server time) and per sale `secondsToEnd` and `secondsToStart` (whole seconds, never negative). Use these for countdowns and **do not use the phone clock** to compare with `startsAt`/`endsAt`, because the phone may be wrong. Take `secondsToEnd` when the response arrives, remember the local monotonic time, and count down from that. Alternative: `offset = serverNow - deviceNow` at response time, then `endsAt - (deviceNow + offset)`. When a countdown reaches 0, refetch (the sale may also have been ended earlier by staff; you will normally get a `catalog` event with `area: "FLASH"` first).

## Channels

A sale has `channel`: `ONLINE` (customer app), `POS` (shop counter) or `BOTH` (default). The public lists and the `flash` object on products show only sales whose channel is `ONLINE` or `BOTH`. A `POS` sale is invisible to customers and to the quote; it only affects counter bills.

## How the price appears on product cards

Product and product-group responses (see file 10) carry an optional `flash` object while a running ONLINE/BOTH sale covers the product and units are left:

```json
{ "id": "…", "nameEn": "Basmati Rice 5kg", "mrpPaise": 52000, "pricePaise": 10000,
  "flash": { "flashSaleId": "…", "name": "Weekend Dhamaka", "listPricePaise": 10000, "salePricePaise": 8000,
             "discountPercent": 20, "unitsLeft": 5, "perCustomerLimit": 2,
             "startsAt": "2026-11-08T10:00:00Z", "endsAt": "2026-11-08T12:00:00Z" } }
```

- `pricePaise` **stays the normal price**; `flash.salePricePaise` is what the customer pays. Show the sale price large, the normal price struck through, and a "20% off" badge from `discountPercent`. `mrpPaise` is the printed MRP and is unrelated.
- `flash` is `null` when no sale applies, the sale is only scheduled, it ended, the channel is POS, or the item sold out.
- For product groups (`/product-groups`), `flash` is on each **variant** (each pack can have its own sale), and on the group itself it is a copy of the first variant that is on sale. Show the variant's own `flash` when the customer picks a variant; the group-level one is only for the card.
- `unitsLeft` `null` = no cap. When it is small (for example 5 or less) show "Only 5 left". The per-customer limit is `perCustomerLimit` (0 = none): show "Max 2 per customer" when greater than 0.
- Units left is read live on every request; sale definitions (times, discounts, caps) may be up to 15 seconds old, but staff changes refresh them at once on a single server.

## How a cart line splits into sale-price and list-price units

`POST /cart/quote` (file 20) prices each cart line. When a product is on a running sale, only as many units as fit go at the sale price: the request quantity is cut to what is left of the item's cap and of the customer's personal allowance. The result is up to **two quote lines for one product**, in this order:

1. the sale units: `flashSaleId` set, `unitPricePaise` = sale price, `listPricePaise` = normal price, `flashDiscountPaise` = `(listPricePaise - unitPricePaise) * qty`.
2. the rest (only when there is a rest): `flashSaleId` null, `unitPricePaise` = normal price, `listPricePaise` = same, `flashDiscountPaise` 0.

Example: 3 units of a Rs 100 item, 1 unit left in the sale at Rs 80:

```json
"lines": [
  { "productId": "p1", "qty": 1, "unitPricePaise": 8000,  "lineTotalPaise": 8000,  "flashSaleId": "s1", "listPricePaise": 10000, "flashDiscountPaise": 2000, "…": "…" },
  { "productId": "p1", "qty": 2, "unitPricePaise": 10000, "lineTotalPaise": 20000, "flashSaleId": null, "listPricePaise": 10000, "flashDiscountPaise": 0, "…": "…" } ]
```

Nothing is left at the sale price (cap used up or the customer's allowance used) gives a single normal line (the same as no sale). The UI should not merge the two lines by `productId` when it renders a quote; show the sale line with a "Flash sale" tag and, if it wants, a note "1 of 3 at sale price". Quote lines are produced for **online** carts only, so the quote shows the online sale and its caps.

The stored order keeps these split lines: the price paid is frozen on the order items (later list price changes do not affect it).

## Coupons, coins and delivery apply on top

The sale price is simply the unit price for those units. Subtotal is the sum of lines (sale price included). Coupons (percent or flat, per-category rules, minimum order) and coin redemption and coins earned are computed from that subtotal exactly as without a sale; there is no special flash rule, no extra restriction, and no "cannot combine with coupon". So a 10 percent coupon on a Rs 80 sale unit takes Rs 8 off. Delivery fee and free-delivery threshold also use the discounted subtotal.

## Weighed items are never discounted

At the shop counter, items sold by weight (`soldBy` WEIGHT) never get a flash price; only items sold by the piece do. Online carts have whole-number quantities, so this rule only matters on the counter app. Counter returns and online order returns do **not** give units back to the sale (only cancel, reject, payment failure and payment timeout of an online order do).

## Other rules worth knowing

- A product can be in only one non-cancelled sale whose time window overlaps (touching ends are fine). Violations answer 409 `FLASH_OVERLAP` naming the other sale.
- Limits: name 1 to 80 characters, description up to 300, bannerText up to 80, at most 200 items per sale, a sale runs at most 30 days, `maxQty` 1 to 1,000,000, `perCustomerLimit` 0 to 999.
- Only active products can be added to a sale.
- Counter (POS) specifics (file 30): a bill without a customer mobile is not subject to the per-customer limit; an offline-synced bill is always recorded (it takes what is left of the sale and the rest is just recorded).

---

# Endpoints

## Customer (public)

### GET /api/v1/flash-sales/active
- **Who**: public (no token needed; a token is ignored).
- **Purpose / when the UI calls it**: Home screen flash-sale banner and the "Flash sales" screen; refetch when an SSE `catalog` event with `area: "FLASH"` arrives and when a countdown hits 0.
- **Request**: none.
- **Response** 200:

```json
{
  "now": "2026-11-08T10:30:00.123Z",
  "sales": [
    {
      "id": "6c1f…", "name": "Weekend Dhamaka", "description": "Cooking essentials", "bannerText": "Up to 30% off",
      "startsAt": "2026-11-08T10:00:00Z", "endsAt": "2026-11-08T12:00:00Z",
      "secondsToEnd": 5399, "secondsToStart": 0,
      "items": [
        { "productId": "0b0e…", "nameEn": "Basmati Rice 5kg", "nameGu": "બાસમતી ચોખા 5kg", "imageUrl": "/img/product/abc_400.jpg",
          "unitLabel": "5 kg", "listPricePaise": 10000, "salePricePaise": 8000, "discountPercent": 20,
          "unitsLeft": 5, "perCustomerLimit": 2, "inStock": true }
      ]
    }
  ]
}
```

| field | type | meaning |
|---|---|---|
| `now` | ISO instant | server time at response; use it for countdowns |
| `sales[]` | array | running sales for ONLINE or BOTH channel; empty array when none. Sales with no showable item are left out |
| `sales[].id`, `name`, `description`, `bannerText` | UUID, string | description and bannerText are empty strings when staff left them blank |
| `sales[].startsAt`, `endsAt` | ISO instant | window |
| `sales[].secondsToEnd` | long | seconds until `endsAt`, never negative |
| `sales[].secondsToStart` | long | 0 for running sales |
| `items[].productId` | UUID | the product (pack) id: the id the cart, quote and order accept, and `GET /products/{id}` |
| `items[].nameEn`, `nameGu` | string | names; show the one for the user's language |
| `items[].imageUrl` | string or null | `/img/...` path; prefix with the server origin |
| `items[].unitLabel` | string | pack size, or the unit when there is no pack size ("500 g", "kg") |
| `items[].listPricePaise` | long | normal price now |
| `items[].salePricePaise` | long | price during the sale (worked out from today's list price) |
| `items[].discountPercent` | int | whole percent saved |
| `items[].unitsLeft` | int or null | null = no cap; 0 = sold out (it sells at the normal price; show "Sold out" and do not offer the sale price) |
| `items[].perCustomerLimit` | int | max per customer per product; 0 = none |
| `items[].inStock` | bool | normal shop stock (a sale does not create stock); disable "Add" when false |

Only items that are visible in the shop and still give a real discount are listed. Items are not sorted by any documented rule beyond the order staff added them.
- **Errors**: none specific (always 200, possibly with `sales: []`).
- **UI notes**: Never compute prices locally; show `salePricePaise` and `listPricePaise` as given. Hide the whole banner when `sales` is empty. The list is not per customer: it does not know the signed-in customer's personal allowance. Tapping an item opens the normal product screen (its `flash` object has the same numbers).

### GET /api/v1/flash-sales/upcoming
- **Who**: public.
- **Purpose / when the UI calls it**: "Coming soon" strip or screen with a "starts in" countdown; refetch on SSE `catalog` with `area: "FLASH"`.
- **Request**: none.
- **Response** 200: exactly the shape of `/active`, but `sales` holds sales that have **not started** (`startsAt` is in the future, not cancelled, channel ONLINE or BOTH). `secondsToStart` is the countdown to the start; `secondsToEnd` is the time to `endsAt` (it counts from now, so it is longer than the sale's length). `salePricePaise` is the price **as of today's** list price; it can change before the sale begins if the list price changes. `unitsLeft` is the full cap (nothing sold yet) or null.
- **Errors**: none specific.
- **UI notes**: Do not allow adding the sale price to the cart before it starts: the quote will charge the normal price until `startsAt`. When the countdown reaches 0 refetch `/active` (staff may also start a sale early with start-now, in which case the sale moves from upcoming to active and a `FLASH` event arrives).

## Staff

All staff endpoints: **Who**: ADMIN or CASHIER. Customers get 403.

Sale objects returned by staff endpoints (called `SaleView`):

```json
{
  "id": "6c1f…", "name": "Weekend Dhamaka", "description": "Cooking essentials", "bannerText": "Up to 30% off",
  "status": "SCHEDULED",
  "startsAt": "2026-11-08T10:00:00Z", "endsAt": "2026-11-08T12:00:00Z", "channel": "BOTH",
  "discountKind": "PERCENT", "discountValue": 20, "perCustomerLimit": 2,
  "cancelledAt": null, "createdBy": "9d3a…", "createdAt": "2026-11-07T08:00:00Z", "updatedAt": "2026-11-07T08:00:00Z",
  "items": [
    { "id": "e41b…", "productId": "0b0e…", "nameEn": "Basmati Rice 5kg",
      "listPricePaise": 10000, "salePricePaise": 8000, "discountPercent": 20,
      "discountKind": null, "discountValue": 0, "effectiveKind": "PERCENT", "effectiveValue": 20,
      "maxQty": 50, "soldQty": 0, "unitsLeft": 50, "perCustomerLimit": null, "effectivePerCustomerLimit": 2 }
  ]
}
```

| field | type | meaning |
|---|---|---|
| `id`, `name`, `description`, `bannerText` | | description and bannerText are empty strings when blank |
| `status` | enum | `SCHEDULED`/`ACTIVE`/`ENDED`/`CANCELLED`, computed at response time |
| `startsAt`, `endsAt`, `channel` | | `channel` is `ONLINE`/`POS`/`BOTH` |
| `discountKind`, `discountValue`, `perCustomerLimit` | | the sale's default. `discountKind` is `PERCENT`, `FIXED_PRICE` or `NONE` (`discountValue` 0 for NONE) |
| `cancelledAt`, `createdBy`, `createdAt`, `updatedAt` | | `cancelledAt` null unless cancelled; `createdBy` is the staff user id |
| `items[].id` | UUID | the **item id** used in `/items/{itemId}` endpoints (not the product id) |
| `items[].productId`, `nameEn` | | product; `nameEn` is `"?"` if the product vanished |
| `items[].listPricePaise` | long | current list price (0 if the product vanished) |
| `items[].salePricePaise`, `discountPercent` | long/int or null | what the customer would pay now; **null when the discount no longer gives a lower price** (warn staff: this item currently has no offer) |
| `items[].discountKind`, `discountValue` | string or null, long | the item's own override; `discountKind` null = follows the sale |
| `items[].effectiveKind`, `effectiveValue` | | what really applies (own override else the sale's default) |
| `items[].maxQty`, `soldQty`, `unitsLeft` | int or null, int, int or null | cap, units counted, remaining (null when no cap) |
| `items[].perCustomerLimit` | int or null | the item's own limit; null = follows the sale |
| `items[].effectivePerCustomerLimit` | int | the limit that applies (0 = none) |

### POST /api/v1/admin/flash-sales
- **Who**: ADMIN, CASHIER.
- **Purpose / when the UI calls it**: "Create flash sale" form: save once the staff have chosen the products, the discount and the times.
- **Request**: body (JSON):

```json
{
  "name": "Weekend Dhamaka", "description": "Cooking essentials", "bannerText": "Up to 30% off",
  "startsAt": "2026-11-08T10:00:00Z", "endsAt": "2026-11-08T12:00:00Z", "channel": "BOTH",
  "discountKind": "PERCENT", "discountValue": 20, "perCustomerLimit": 2,
  "items": [
    { "productId": "0b0e…", "maxQty": 50 },
    { "productId": "7a2c…", "discountKind": "FIXED_PRICE", "discountValue": 4500, "maxQty": 10, "perCustomerLimit": 1 }
  ]
}
```

| field | type | required | validation (field name in `details.fields`) | meaning |
|---|---|---|---|---|
| `name` | string | yes | trimmed; 1 to 80 characters, else `name` "name must be 1 to 80 characters" | title |
| `description` | string | no | up to 300 characters after trimming, else `description`; null = empty | long text |
| `bannerText` | string | no | up to 80 characters, else `bannerText` | short banner line |
| `startsAt` | ISO instant | no | null = start now. Must not be more than 1 minute in the past (else `startsAt` "startsAt must not be in the past"). A start within the last minute counts as now | window start |
| `endsAt` | ISO instant | yes | missing: `endsAt` "endsAt is required"; must be after `startsAt` ("endsAt must be after startsAt"); must be in the future ("endsAt must be in the future"); at most 30 days after start ("a sale can run for at most 30 days") | window end |
| `channel` | string | no | `ONLINE`, `POS` or `BOTH` (case-insensitive); null/blank = `BOTH`; other: `channel` "must be ONLINE, POS or BOTH" | where it applies |
| `discountKind` | string | no | `PERCENT`, `FIXED_PRICE`, `NONE` (case-insensitive); null/blank = `NONE`; other: `discountKind` "discount kind must be PERCENT, FIXED_PRICE or NONE" | default discount |
| `discountValue` | long | when kind is PERCENT or FIXED_PRICE | PERCENT 1 to 90 ("percent must be 1 to 90"); FIXED_PRICE at least 100 ("sale price must be at least Rs 1"); error field `discountValue`. A missing value counts as 0 and fails | default value |
| `perCustomerLimit` | int | no | 0 to 999, else `perCustomerLimit`; null = 0 (no limit) | default per-customer limit |
| `items` | array | yes | at least 1 ("choose at least one product"), at most 200; error field `items` | products |
| `items[].productId` | UUID | yes | required, no duplicates in the request, product must exist and be active, else `items` messages ("every item needs a productId", "a product can be added only once: <id>", "unknown product <id>", "<name> is not active") | product (pack) id |
| `items[].discountKind` | string | no | as above; null/blank/`NONE` = follow the sale. Bad value: `items` "discount kind must be PERCENT, FIXED_PRICE or NONE" | override |
| `items[].discountValue` | long | when an override is given | same ranges as above, reported under field `items` | override value |
| `items[].maxQty` | int | no | 1 to 1,000,000 else `items` "maxQty must be 1 to 1000000"; null = no cap | cap |
| `items[].perCustomerLimit` | int | no | 0 to 999 else `items`; null = follow the sale | override |

Also checked per item (all reported under field `items` with the product name in the message): when the sale has `NONE` as default and the item gives no override: "<name> needs its own discount (the sale has no default discount)"; when the discount would not give a price below the current list price (including a FIXED_PRICE at or above it, or a list price of Rs 1 or less): "<name>: this discount gives no lower price than its current price of Rs <x>".

The server validates item errors per field name `items` only; the message names the product, so show the message text next to the item list, not under a specific row.
- **Response** 201: the `SaleView` object above (status `ACTIVE` if it starts now, else `SCHEDULED`).
- **Errors**:

| status | code | when |
|---|---|---|
| 400 | `VALIDATION_FAILED` | any rule above. **Every failing field is reported together** in `details.fields` (for example an empty `name`, `discountValue` 95 and no `items` give all three keys); a single problem keeps its own `message`. The same holds for `PATCH` |
| 409 | `FLASH_OVERLAP` | a product is already in another non-cancelled sale whose window overlaps. `message` names product and sale; `details` = `{ saleId, saleName, productId }` of the other sale. Only the first clash is reported |

- **UI notes**: Show a date-time picker for both times (send UTC; display in the shop's time zone, Asia/Kolkata). Offer "Start now" by omitting `startsAt`. Product picker should search active products and send the pack id (`productId`). Show the computed `salePricePaise` from the response for each item to confirm. On `FLASH_OVERLAP` tell staff which sale holds the product and offer to open it (`details.saleId`). Sending the same create twice makes two sales (no idempotency key), so disable the button after the tap.

### GET /api/v1/admin/flash-sales
- **Who**: ADMIN, CASHIER.
- **Purpose / when the UI calls it**: Flash-sale list screen with status tabs.
- **Request**: query parameters:

| param | type | default | validation |
|---|---|---|---|
| `status` | string | all | `SCHEDULED`, `ACTIVE`, `ENDED` or `CANCELLED`, case-insensitive; blank = all; other: 400 field `status` "must be SCHEDULED, ACTIVE, ENDED or CANCELLED" |
| `page` | int | 0 | negative treated as 0 |
| `size` | int | 20 | clamped to 1..50 |

- **Response** 200: page object, newest start first:

```json
{ "items": [ { "id": "6c1f…", "name": "Weekend Dhamaka", "status": "ACTIVE", "startsAt": "…", "endsAt": "…", "channel": "BOTH", "items": 12 } ],
  "page": 0, "size": 20, "total": 37 }
```

`items[].items` is the number of products in the sale (an integer here, not a list).
- **Errors**: 400 `VALIDATION_FAILED` (bad `status`).
- **UI notes**: Tabs: Running (`ACTIVE`), Scheduled, Ended, Cancelled, plus All. Open a row with `GET /admin/flash-sales/{id}`. Refresh on SSE `catalog` `area: "FLASH"`.

### GET /api/v1/admin/flash-sales/{id}
- **Who**: ADMIN, CASHIER.
- **Purpose / when the UI calls it**: Sale detail / edit screen.
- **Request**: path `id` = sale UUID.
- **Response** 200: `SaleView` (shape above) including all items with live `soldQty` and `unitsLeft`.
- **Errors**: 404 `NOT_FOUND` (unknown id; a non-UUID id is 400 `Bad request`).
- **UI notes**: Poll or refetch on `FLASH` / `stock` events while a sale is running to show sold counts. Items with `salePricePaise: null` need attention.

### PATCH /api/v1/admin/flash-sales/{id}
- **Who**: ADMIN, CASHIER.
- **Purpose / when the UI calls it**: Edit the sale (rename, change times, discount, channel, limit).
- **Request**: body with any subset of the create fields; **a field that is absent or null is left unchanged** (so you cannot clear `description` with null: send an empty string `""`). Items cannot be changed here.

```json
{ "name": "Weekend Dhamaka (extended)", "endsAt": "2026-11-08T14:00:00Z" }
```

| field | type | validation |
|---|---|---|
| `name`, `description`, `bannerText` | string | same length rules as create |
| `startsAt` | ISO instant | `SCHEDULED` only: must not be more than 1 minute in the past; must stay before `endsAt` |
| `endsAt` | ISO instant | must be after `startsAt`, in the future, and the window at most 30 days |
| `channel` | string | `ONLINE`/`POS`/`BOTH` |
| `discountKind`, `discountValue` | string, long | same ranges as create. If you send `discountKind` without `discountValue`, the existing value is reused (probably wrong for a new kind), so always send both. After a change every item is re-checked: each must still end with a valid lower price, else `items` errors as in create (for example switching the default to `NONE` fails while any item relies on it) |
| `perCustomerLimit` | int | 0 to 999 |

Rules by status:

- `SCHEDULED`: anything above.
- `ACTIVE`: only `name`, `description`, `bannerText` and `endsAt`. Sending a different `startsAt`, `channel`, `discountKind`, `discountValue` or `perCustomerLimit` answers 409 `FLASH_RUNNING`. Sending the unchanged value is accepted.
- `ENDED` or `CANCELLED`: 409 `FLASH_NOT_ALLOWED`.
- When `startsAt` or `endsAt` changes, the overlap check is run again for every item.
- **Response** 200: the updated `SaleView`.
- **Errors**:

| status | code | when |
|---|---|---|
| 400 | `VALIDATION_FAILED` | field rules (same field names as create) |
| 404 | `NOT_FOUND` | unknown sale |
| 409 | `FLASH_NOT_ALLOWED` | sale is ENDED or CANCELLED |
| 409 | `FLASH_RUNNING` | a running sale's start, channel, discount or limit was changed |
| 409 | `FLASH_OVERLAP` | the new window clashes with another sale for one of the products (`details` = `{saleId, saleName, productId}`) |

- **UI notes**: For a running sale, disable the locked fields in the form and keep name/texts/end time editable. To "extend" a sale, change `endsAt`. To stop early use end-now.

### POST /api/v1/admin/flash-sales/{id}/items
- **Who**: ADMIN, CASHIER.
- **Purpose / when the UI calls it**: "Add product" to a scheduled or running sale.
- **Request**: body (one item, same fields as an entry of `items[]` in create):

```json
{ "productId": "7a2c…", "discountKind": "PERCENT", "discountValue": 30, "maxQty": 20, "perCustomerLimit": 2 }
```

| field | type | required | validation |
|---|---|---|---|
| `productId` | UUID | yes | must exist and be active; not already in this sale ("this product is already in the sale"); a null/unknown id gives `items` "unknown product …" |
| `discountKind`, `discountValue` | | no | as in create. The product needs a valid lower price (own discount or the sale's default) |
| `maxQty` | int | no | 1 to 1,000,000; null = no cap |
| `perCustomerLimit` | int | no | 0 to 999; null = follow the sale |

The sale may hold at most 200 items (`items` "at most 200 products in one sale").
- **Response** 201: the full updated `SaleView`.
- **Errors**: 400 `VALIDATION_FAILED` (field `items`); 404 `NOT_FOUND` (sale); 409 `FLASH_NOT_ALLOWED` (ENDED/CANCELLED); 409 `FLASH_OVERLAP` (product is in another overlapping sale; `details` as above).
- **UI notes**: Allowed while the sale is running (a new item starts selling at once). Find the new row in `items[]` by `productId` to get its item id.

### PUT /api/v1/admin/flash-sales/{id}/items/{itemId}
- **Who**: ADMIN, CASHIER.
- **Purpose / when the UI calls it**: Edit one item's discount, cap or limit.
- **Request**: body `{ "discountKind", "discountValue", "maxQty", "perCustomerLimit" }`. This **replaces** the item's settings: a field you leave out or send as null goes back to its default (follow the sale, no cap, follow the sale). So always send the full current state of the item. `productId` in the body is ignored (an item's product cannot change). Same validation as in add item.

```json
{ "discountKind": "PERCENT", "discountValue": 25, "maxQty": 80, "perCustomerLimit": 2 }
```

Once any unit of the item has been sold (or the item has sales history), only the cap may change, and only in the loosening direction. Precisely, the server compares the new settings to the old and answers 409 `FLASH_ITEM_SOLD` when: the discount kind, discount value or per-customer limit differ; or the old item had a cap and the new `maxQty` is lower; or the old item had **no** cap and the new one sets a cap. Raising the cap, or removing it (null for an item that had one), is accepted.
- **Response** 200: the full updated `SaleView`.
- **Errors**: 400 `VALIDATION_FAILED` (field `items`); 404 `NOT_FOUND` (sale, or item not in this sale); 409 `FLASH_NOT_ALLOWED`; 409 `FLASH_ITEM_SOLD` (see above).
- **UI notes**: For a sold item, show only the "quantity" input, with the minimum set to the current `maxQty`, and lock the discount fields. If staff want a different discount, end the sale and make a new one (or duplicate).

### DELETE /api/v1/admin/flash-sales/{id}/items/{itemId}
- **Who**: ADMIN, CASHIER.
- **Purpose / when the UI calls it**: "Remove product" from a sale that has not sold it.
- **Request**: none.
- **Response** 200: the updated `SaleView` (not 204).
- **Errors**: 404 `NOT_FOUND` (sale or item); 409 `FLASH_NOT_ALLOWED` (ENDED/CANCELLED); 409 `FLASH_ITEM_SOLD` with `details = { "soldQty": n }` when units have been sold or the item has sales history ("Cancel or end the sale instead").
- **UI notes**: Hide the remove button when `soldQty > 0`. A sale can end up with zero items after removing; the server does not forbid it.

### POST /api/v1/admin/flash-sales/{id}/start-now
- **Who**: ADMIN, CASHIER.
- **Purpose / when the UI calls it**: "Start now" button on a scheduled sale.
- **Request**: no body.
- **Response** 200: `SaleView` with `status: "ACTIVE"` and `startsAt` set to the current time.
- **Errors**: 404 `NOT_FOUND`; 409 `FLASH_NOT_ALLOWED` (only a `SCHEDULED` sale can be started; the message says the current status); 400 `VALIDATION_FAILED` field `endsAt` "the end time has already passed"; 409 `FLASH_OVERLAP` (starting earlier now clashes with another sale of a product).
- **UI notes**: Show the button only when `status == "SCHEDULED"`. The sale keeps its `endsAt`, so it becomes shorter or longer than planned relative to the start; show the new window from the response.

### POST /api/v1/admin/flash-sales/{id}/end-now
- **Who**: ADMIN, CASHIER.
- **Purpose / when the UI calls it**: "End sale now" on a running sale.
- **Request**: no body.
- **Response** 200: `SaleView` with `status: "ENDED"` and `endsAt` = now.
- **Errors**: 404 `NOT_FOUND`; 409 `FLASH_NOT_ALLOWED` (only an `ACTIVE` sale can be ended now).
- **UI notes**: Ask for confirmation. After this, new quotes and orders use normal prices at once. Orders already placed keep the sale price they were charged. Staff then get a "Flash sale ended" notification within about a minute.

### POST /api/v1/admin/flash-sales/{id}/cancel
- **Who**: ADMIN, CASHIER.
- **Purpose / when the UI calls it**: Cancel a scheduled sale, or stop a running one and mark it cancelled.
- **Request**: no body.
- **Response** 200: `SaleView` with `status: "CANCELLED"` and `cancelledAt` set.
- **Errors**: 404 `NOT_FOUND`; 409 `FLASH_NOT_ALLOWED` (already ENDED or CANCELLED).
- **UI notes**: Irreversible: confirm. Difference from end-now: a cancelled sale never produces the "ended" staff notification, and it is shown under "Cancelled". Past orders keep the sale price they were charged. Use `duplicate` to reuse a cancelled sale's setup.

### POST /api/v1/admin/flash-sales/{id}/duplicate
- **Who**: ADMIN, CASHIER.
- **Purpose / when the UI calls it**: "Run again" / "Copy" on any sale (any status).
- **Request**: optional body (the body may be omitted entirely):

```json
{ "name": "Next weekend", "startsAt": "2026-11-15T10:00:00Z", "endsAt": "2026-11-15T12:00:00Z" }
```

| field | type | required | meaning / validation |
|---|---|---|---|
| `name` | string | no | default is the original name plus " (copy)" (cut to fit 80 characters). If given: 1 to 80 characters else `name` |
| `startsAt` | ISO instant | no | null = now. Same past-start rule as create |
| `endsAt` | ISO instant | no | null = same length as the original sale, counted from the new start. Must be in the future, after start, at most 30 days |

Copies: description, bannerText, channel, default discount, perCustomerLimit and every item's settings (discount override, `maxQty`, `perCustomerLimit`) with `soldQty` reset to 0. The copy is never cancelled.
- **Response** 201: `SaleView` of the new sale.
- **Errors**: 400 `VALIDATION_FAILED` (name/time fields; also `items` when a copied item no longer gives a lower price or its product is no longer active); 404 `NOT_FOUND`; 409 `FLASH_OVERLAP` (for example duplicating a running sale with no body starts now and overlaps the original).
- **UI notes**: Always ask for a new start time; sending no body to copy a running sale fails with `FLASH_OVERLAP`.

### DELETE /api/v1/admin/flash-sales/{id}
- **Who**: ADMIN, CASHIER (like every flash-sale call).
- **Purpose / when the UI calls it**: "Delete" on a sale that was set up by mistake and has not started yet. Anything that started is history: use cancel or end-now.
- **Response** 204, no body. The sale and its items are gone, an audit entry `FLASH_DELETE` is written and open apps get the live `FLASH` catalog event.
- **Errors**: 404 `NOT_FOUND` (unknown or already deleted id; a repeated delete therefore answers 404, treat it as done); 409 `FLASH_NOT_ALLOWED` when the sale has already started (running, ended, or cancelled after starting) or any unit was ever counted for it (`message` says which).
- **UI notes**: Show the button only for status `SCHEDULED`; confirm first.

### GET /api/v1/admin/flash-sales/{id}/report
- **Who**: ADMIN, CASHIER.
- **Purpose / when the UI calls it**: "Report" tab of a sale, during or after it.
- **Request**: path `id`.
- **Response** 200:

```json
{
  "saleId": "6c1f…", "name": "Weekend Dhamaka", "status": "ENDED", "startsAt": "…", "endsAt": "…",
  "unitsSold": 42, "orders": 18, "bills": 3,
  "revenuePaise": 336000, "discountPaise": 84000, "avgDiscountPercent": 20.0,
  "customers": 17, "sellThroughPercent": 84.0,
  "byItem":    [ { "productId": "0b0e…", "nameEn": "Basmati Rice 5kg", "units": 30, "revenuePaise": 240000, "discountPaise": 60000, "maxQty": 50, "sellThroughPercent": 60.0 } ],
  "byChannel": [ { "channel": "ONLINE", "units": 39, "revenuePaise": 312000, "discountPaise": 78000 },
                 { "channel": "POS",    "units": 3,  "revenuePaise": 24000,  "discountPaise": 6000 } ],
  "byHour":    [ { "hour": "2026-11-08 10:00", "units": 12, "revenuePaise": 96000 } ]
}
```

| field | meaning |
|---|---|
| `unitsSold` | units sold at the sale price, online and counter, **excluding units given back** by cancelled/rejected/failed online orders |
| `orders` | online orders that used the sale; `bills` = counter bills that did |
| `revenuePaise` | money from sale-price units (`unit price x qty`); the part of lines sold at the normal price is not included |
| `discountPaise` | `(list - sale price) x qty` given away |
| `avgDiscountPercent` | `discount / (revenue + discount)` as a percent, 2 decimals; 0 when nothing sold |
| `customers` | distinct customers (counter bills without a customer mobile are not counted as customers) |
| `sellThroughPercent` | units sold of the **capped** items as a percent of their caps; null when no item has a cap |
| `byItem[]` | one row per sale item, most units first (includes items with 0 sold); `maxQty` and `sellThroughPercent` are null for uncapped items |
| `byChannel[]` | always two rows, `ONLINE` then `POS` (zeros when none) |
| `byHour[]` | units and revenue per hour in shop time; `hour` is `"yyyy-MM-dd HH:00"` in Asia/Kolkata; only hours with sales |

Returns after sale (counter returns, order returns) are **not** subtracted.
- **Errors**: 404 `NOT_FOUND`.
- **UI notes**: Money as rupees from paise; percentages are plain numbers (20.0 means 20 percent). The counter's normal sales reports are not affected by flash sales (a sale line is an ordinary bill line).

### GET /api/v1/admin/flash-sales/report
- **Who**: ADMIN, CASHIER.
- **Purpose / when the UI calls it**: Overview report: all sales that **started** in a date range, with totals and the best items.
- **Request**: query parameters:

| param | type | default | validation |
|---|---|---|---|
| `from` | date `yyyy-MM-dd` | `to` minus 29 days | shop time (Asia/Kolkata), inclusive |
| `to` | date `yyyy-MM-dd` | today (shop time) | inclusive; before `from`: 400 field `to` "must not be before from"; more than 366 days after `from`: 400 field `to` "a report covers at most 366 days" |

A wrongly formatted date is 400 `VALIDATION_FAILED` `Bad request`. Note this route is literally `/report`, so a sale id can never be `report`.
- **Response** 200:

```json
{
  "from": "2026-10-10", "to": "2026-11-08",
  "sales": 4, "unitsSold": 120, "orders": 60, "bills": 8,
  "revenuePaise": 960000, "discountPaise": 240000, "avgDiscountPercent": 20.0, "customers": 55,
  "bySale": [ { "saleId": "6c1f…", "name": "Weekend Dhamaka", "status": "ENDED", "startsAt": "…", "endsAt": "…",
                "unitsSold": 42, "orders": 18, "bills": 3, "revenuePaise": 336000, "discountPaise": 84000, "avgDiscountPercent": 20.0, "customers": 17 } ],
  "bestItems": [ { "productId": "0b0e…", "nameEn": "Basmati Rice 5kg", "units": 60, "revenuePaise": 480000, "discountPaise": 120000, "maxQty": null, "sellThroughPercent": null } ]
}
```

`from` and `to` echo the resolved dates. `sales` is the count of sales listed. `customers` counts a customer once even if they bought in two sales. `bestItems` is up to 10 products by units sold (then revenue) across those sales; `maxQty` and `sellThroughPercent` are always null here. Cancelled sales are included in `bySale` (with their `status`) if they started in the range.
- **Errors**: 400 `VALIDATION_FAILED` (`to`, or malformed dates).
- **UI notes**: Default view = last 30 days. A date picker pair; send plain dates. Tapping a `bySale` row opens the single-sale report.

---

# Where flash prices appear in other endpoints

| Endpoint (file) | What changes |
|---|---|
| `GET /products`, `GET /products/{id}`, hot sale and search results (file 10) | optional `flash` object on each product (see Concepts); `pricePaise` unchanged |
| `GET /product-groups`, `GET /product-groups/{id}` (file 10) | `flash` on each variant and on the group |
| `POST /cart/quote` (file 20) | each `lines[]` entry has `flashSaleId` (null if none), `listPricePaise`, `flashDiscountPaise`; a product may appear as two lines (sale units + normal units) |
| `POST /orders` (file 20) | re-quotes at placement and counts the sale units atomically. If the sale/quota changed since the quote, answers 409 `PRICE_CHANGED` with `details.quote` |
| `GET /orders/{id}` (file 20) | items hold the price actually paid (the sale price for sale units), frozen |
| Counter `POST /pos/sales` and its quote (file 30) | pieces sold by the piece get the sale price on channel POS/BOTH sales; weighed items never. A refused claim answers 409 `PRICE_CHANGED` with `details.reason` of `LIMIT_REACHED`, `CUSTOMER_LIMIT` or `ENDED` (the online order path hides the reason and returns the fresh quote instead) |

---

# Customer app flow

1. **Home banner**: call `GET /flash-sales/active` on home load (guests too). If `sales` is non-empty show a banner per sale with `bannerText` (or `name`), an end countdown from `secondsToEnd`, and a horizontal list of `items` (image, name, struck-through `listPricePaise`, `salePricePaise`, `discountPercent` badge, "Only N left" when `unitsLeft` is small, "Sold out" at 0). Optional "Coming soon" strip from `GET /flash-sales/upcoming` with `secondsToStart` countdown.
2. **Product price display**: product list and detail already carry `flash`. If `flash` is non-null show the sale price (`flash.salePricePaise`) as the price and `flash.listPricePaise` struck through; else show `pricePaise`. Countdown from `flash.endsAt` (use the server offset from a recent `now`, or refetch after the countdown ends). Show `perCustomerLimit` when above 0 ("Max 2 per customer at this price"). For grouped products use the selected variant's `flash`.
3. **Cart**: call `POST /cart/quote` (debounced) and render only the quote lines. If a product appears twice, render the sale line with its tag and the normal-price line below it, and explain "Only N at sale price". The subtotal and total already include the sale. Do not recompute from `flash` on the card; the quote is the source of truth (it also knows this customer's personal allowance).
4. **Place order**: send `expectedTotalPaise` = last quoted `totalPaise` with an `Idempotency-Key` (file 20). The server re-prices and re-counts the sale units.
5. **409 `PRICE_CHANGED`** (the sale ended, the last units were taken, or the customer's allowance is used up, or any other price/total change): read `details.quote`, replace the cart screen's quote with it (the lines now show the new prices and the new split, or no sale lines at all), show "The offer changed, please review", and let the customer confirm. A second tap sends `expectedTotalPaise = details.quote.totalPaise`. Nothing was charged or reserved when this error came; no order exists. Do not auto-retry.
6. **Live refresh**: keep the customer SSE stream open (`GET /orders/stream`, file 20). On a `catalog` event with `area: "FLASH"` refetch `/flash-sales/active` (and `/upcoming`), the visible product lists, and re-run the quote if the cart is open. Also refetch on `stock` events if the screen shows `unitsLeft` (units sold change the number but do not publish a `FLASH` event per purchase, see below). Events are hints, not data.
7. **Cancelling**: when a customer cancels an unpaid or unconfirmed order (or the shop rejects it, or payment fails or times out), the sale units are given back automatically; nothing to do in the app.

# Staff app flow

1. **List**: `GET /admin/flash-sales?status=ACTIVE` (and SCHEDULED, ENDED tabs). Show name, status chip, window, item count.
2. **Create**: form with name, banner text, description, channel (Online / Counter / Both), start (default "Now", or a date-time), end (required), default discount (percent 1 to 90 or fixed price in rupees converted to paise, or "set per product"), optional per-customer limit, then a product picker where each chosen product can have its own discount, quantity cap and per-customer limit. `POST /admin/flash-sales`. Show 400 messages from `details.fields` (`items` messages include the product name); on 409 `FLASH_OVERLAP` show which sale holds the product.
3. **Schedule**: leave `startsAt` in the future; the sale stays `SCHEDULED`, visible to customers only under `/upcoming`. Edit with `PATCH` and the item endpoints until it starts.
4. **Add items**: `POST /admin/flash-sales/{id}/items` (also while running). Remove with `DELETE .../items/{itemId}` only when `soldQty == 0`. Change an item with `PUT` (send its full state).
5. **Start now**: `POST /{id}/start-now` on a scheduled sale.
6. **Watch**: open the sale detail (`GET /{id}`) and refetch every few seconds or on SSE `catalog` / `stock` events; show `soldQty / maxQty` and `unitsLeft`.
7. **Extend or end**: extend with `PATCH {"endsAt": …}`; end early with `POST /{id}/end-now`; abandon with `POST /{id}/cancel`.
8. **Report**: `GET /{id}/report` for a sale; `GET /report?from=&to=` for the period.
9. **Reuse**: `POST /{id}/duplicate` with a new start/end.

CASHIER and ADMIN have identical access to these endpoints.

---

# Notifications and live events

## Staff notifications (in-app inbox and push)

A job runs every 60 seconds. It sends one notification per event to **every staff member (ADMIN and CASHIER)**: stored in the staff user's inbox (`GET /me/notifications`, file 10) and pushed to their registered devices. In the inbox row, `kind` is `FLASH` and `ref` is the sale id (use it to open the sale detail). Each is sent once (the "already announced" mark is stored in the database, so a restart does not repeat it).

| Event | When | Title | Body |
|---|---|---|---|
| `FLASH_STARTED` | within about a minute after a sale's start time (also after start-now, or when a sale was created to start now). Not sent for a cancelled sale | "Flash sale started" | `"<name>" is live now with N item(s).` |
| `FLASH_ENDED` | within about a minute after a sale's end time or end-now. Not sent for a cancelled sale, and not sent when the sale ended more than 24 hours ago (for example the server was down) | "Flash sale ended" | `"<name>" has ended. Sold N units for Rs <revenue>.` |
| `FLASH_ITEM_SOLD_OUT` | once per item, right when its last capped unit is sold (not delayed by the job) | "Flash sale item sold out" | `<product> is sold out in "<sale>". It now sells at the normal price.` |

Notification delivery is asynchronous (an outbox), so allow a few seconds of delay beyond the schedule above.

## Live event (SSE) area `FLASH`

The shared SSE streams (file 20, "Live events reference") send `event: catalog` with data `{ "area": "FLASH", "at": "…" }` to every connected client when a sale is created, edited, items changed, started, ended or cancelled by staff, and by the job when a sale started or ended on its own. Customers react by refetching `/flash-sales/active`, `/upcoming`, the shown products and the quote. Staff screens refetch their lists. The event is **not** sent per purchase: units sold change `unitsLeft` silently, though a stock event may fire as orders change stock. Show the fresh `unitsLeft` when you next refetch.

Because status changes at the exact second (computed), but the `FLASH` event for an automatic start or end is published by the minute job, a client may see the sale start up to about a minute before it receives the event. Countdown timers should therefore trigger their own refetch at 0 rather than wait for the event.

---

# Things the code does not settle

- The ordering of `items` within a public sale is not documented beyond insertion order. The code reads them in creation order, but treat it as unspecified.
- Whether null fields are serialised as `null` or omitted is not configured in code; handle both.
- The `FLASH` live event after an automatic start/end is sent by the minute job; the exact delay is up to 60 seconds.
- `GET /flash-sales/*` has no per-customer view (no personal "remaining" for the signed-in customer); that only shows in the quote.
- Server side cache: sale definitions are cached up to 15 seconds and the cache is emptied on every `FLASH` event on the same server instance; with several backend instances a customer could see a stale definition for up to 15 seconds.
- A sale can be left with zero items after removals; the code does not block it (it would not show up in the public lists).

## Hot sale vs flash sale

Hot sale (`hotSale`, `hotStart`, `hotEnd`) is only advertising: the product shows in the featured list and banners and its price does not change. A flash sale is the occasion sale: a timed discount with its own sale price, quantity caps and reports. Do not merge them in the UI. Coupons may be used on flash sale lines or not, depending on the admin setting `coupon.allowOnFlashItems` (default yes); see Addendum 7 in `20-orders-payments-admin-config.md`.
