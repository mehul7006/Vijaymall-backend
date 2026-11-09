# Vijay Super Mall backend - notes

## Run it
1. PostgreSQL 16 database `mall` (user/password `mall`), or set `DB_URL`, `DB_USER`, `DB_PASSWORD`.
2. `mvn spring-boot:run -Dspring-boot.run.profiles=dev` (JDK 25; on JDK 21 add `-Djava.release=21`).
3. Dev profile: login code is always `12345`; `admin@vijaysupermall.local` becomes ADMIN; sample shop data is created once.
4. API docs: http://localhost:8080/swagger-ui.html (disabled in prod). Contract: `../API_CONTRACT.md`.

## Tests
- `mvn test` runs the unit tests (no Docker).
- `mvn verify` also runs `*IT` tests with Testcontainers (needs Docker): `OrderFlowIT` covers login, quote, order, idempotency, delivery and coin credit.

## Settings (environment variables)
`DB_URL DB_USER DB_PASSWORD JWT_SECRET OTP_CHANNEL(console|email) OTP_PEPPER SMTP_HOST SMTP_PORT SMTP_USER SMTP_PASSWORD
OTP_FROM ADMIN_EMAIL IMAGES_DIR RAZORPAY_KEY_ID RAZORPAY_KEY_SECRET RAZORPAY_WEBHOOK_SECRET`.
In production set a long random `JWT_SECRET` and `OTP_PEPPER`, `OTP_CHANNEL=email`, and `ADMIN_EMAIL` for the first admin.

## Delivery fee
Delivery is free for now: `app.delivery.fee-paise` defaults to 0 (env `DELIVERY_FEE_PAISE`; 4000 = Rs 40). `app.delivery.free-above-paise` (49900) only matters once a fee is set. An admin-editable rule is planned.

## Where things are
Modular monolith, one package per module (`api`, `app`, `domain`, `infra`). Modules call each other only through `*Service` classes
or Spring events (payment -> order uses `PaymentEvents`; notifications use the outbox table).
- Add a payment provider: one class implementing `PaymentGateway` + set `app.payment.online-provider`.
- Add a discount: one class implementing `QuoteStep` with an `order()` number.
- Add an OTP channel (SMS): implement `OtpSender`, select by `app.otp.channel`.
- Push messages: replace `LoggingPushSender` with an FCM implementation of `PushSender`.

## Stubs and limits
- Push is only logged. SMS login is not built (DLT cost). Razorpay code is written against its public HTTP API but has not been run against a live account.
- Webhook URL to register at Razorpay: `https://<your-domain>/api/v1/payments/webhook/razorpay` (events payment.captured, payment.failed).

## Deviations from the design doc
- Spring JDBC (`JdbcClient`) instead of JPA; explicit SQL for stock and coins.
- Spring Boot 3.5.6 instead of 4.x. Images are saved as `<uuid>_400.jpg` and `<uuid>_1000.jpg` (JDK has no WebP writer); the API returns the `_400` path and the app swaps to `_1000` for the detail view.
- Login is email OTP, not mobile SMS.

## Not compiled here
This code was written in a sandbox with JDK 21 but no Maven Central, so only the pure classes (`wallet/domain`, `order/domain`,
`payment/domain`, `RazorpaySignature`) were compiled and checked. Run `mvn test` first and send me the first compiler errors;
the likeliest trouble spots are `OrderViews.AdminView` (`@JsonUnwrapped` on a record), `RazorpayGateway` (RestClient calls),
`ReportService` SQL and the `JdbcClient` parameter binding of nullable values.

---
## Update: stack moved to JPA, Spring Boot 4.1.2, Java 27

- Persistence is now JPA/Hibernate 7 (no JdbcClient left). Domain stays as pure records; `infra` holds package-private `@Entity` classes, Spring Data `*Jpa` interfaces and public `@Repository` wrappers. Schema is still owned by Flyway; Hibernate runs with `ddl-auto: validate`.
- Atomic stock/coupon updates use `@Modifying` JPQL; wallet uses `PESSIMISTIC_WRITE`; orders and wallet balance use `@Version`; outbox relay uses `FOR UPDATE SKIP LOCKED` via lock timeout -2.
- Jackson 3 (`tools.jackson`), `JsonMapper`; `JsonNode.asString()`.
- Java 27 (`java.release`; use `-Djava.release=25` for the LTS JDK). Language features used: records, sealed types, pattern-matching switch, unnamed `_`, immutable folds with `reduce`, lazy rule evaluation with streams, Optional pipelines.
- NOT COMPILED HERE: this sandbox has JDK 21 and no Maven Central. Only pure-Java classes were compiled and run (LotAllocator, CouponRules etc.). Run `mvn test` and send me any errors. Likely trouble spots: BaseEntity/Persistable, JPQL `nulls last`, Jackson 3 API names, `@JsonUnwrapped` on a record.
- Integration tests (Testcontainers 2, `mvn verify`, needs Docker): OrderFlowIT, SecurityIT, AuthFlowIT, InventoryConcurrencyIT, OrderCancelIT on a shared `IntegrationTestBase`. Not yet run. Still to add: WalletLedgerIT, PaymentWebhookIT, OrderExpiryIT.

---
## Tests and coverage

Run everything and check coverage: `mvn verify` (Docker must be running for the integration tests).

- Unit tests (surefire, `*Test.java`): pure logic and services with Mockito. Fast, no Docker.
- Integration tests (failsafe, `*IT.java`): real PostgreSQL through Testcontainers and real HTTP, one shared container. Base class: `support/IntegrationTestBase`.
- JaCoCo merges both into `target/jacoco.exec`; the report is `target/site/jacoco/index.html`.
- The build FAILS if line coverage is below 98% or branch coverage below 95% (see `jacoco-maven-plugin` in pom.xml). Only `MallApplication` is excluded.
- These numbers have NOT been measured yet: the sandbox I work in cannot run Maven. The first `mvn verify` on your machine will show the real figure; send me the list of uncovered lines and I will add tests for them.

Integration test map: OrderFlowIT, OrderApiIT, OrderCancelIT (orders); OnlinePaymentIT (second payment provider, refunds, expiry); WalletLedgerIT; CouponApiIT; CatalogApiIT; IdentityApiIT, AuthFlowIT; SecurityIT, ErrorHandlingIT; InventoryConcurrencyIT.

---
## Update: public coupon list, saved basket, dev OTP code

- `GET /api/v1/coupons` is public (no sign-in). It returns the offers a customer could use right now: active, inside `startAt`/`endAt`, and not used up. Each item has `code, kind, value, minOrderPaise, maxDiscountPaise, categoryId, startAt, endAt, worksWithCoins, firstOrderOnly` (value is paise for FLAT, percent for PERCENT; 0 means no minimum / no cap). Internal fields (`id`, `totalLimit`, `usedCount`, `perUserLimit`, `active`) are not exposed. Only GET is public; applying a code still goes through the authenticated `POST /api/v1/cart/quote`, which does the per-customer checks (including `FIRST_ORDER_ONLY`).
- Saved basket: `GET / PUT / DELETE /api/v1/cart`, signed-in customers only (guests keep a local basket). `GET` returns `{items:[{productId,qty}], couponCode, coinMode, percent, updatedAt}`; with no saved basket it returns `200` with `items: []`, `coinMode: "NONE"`. `PUT` replaces the whole basket (last write wins), merges repeated products, upper-cases the coupon, keeps `percent` only for `PERCENT`, skips products that no longer exist, and returns what is stored. Lines for inactive or sold-out products are left out on read. Limits: 100 lines, quantity 1 to 999. `POST /cart/quote` is unchanged. Migration `V6__cart.sql`; `cart_item.position` keeps the saved order.
- Fixed login code for dev / frontend testing: run with the `dev` profile (code is `12345`), or on any non-prod profile set `OTP_FIXED_CODE=12345`. In the `dev` profile `OTP_FIXED_CODE` overrides the default. The `prod` profile (the Docker image default) refuses to start with a fixed code, by design.
- On startup the log says whether the fixed code is active: `OTP fixed code is ACTIVE` or `OTP fixed code is not set`. If a client gets `OTP_INVALID / WRONG` for `12345`, the running instance is not using a fixed code (started without the `dev` profile and without `OTP_FIXED_CODE`).

## Order defects round (status rules, live updates, variants, reviews)

- **Status changes.** Staff and admins may move an open order to any status except PAYMENT_PENDING (system-set) and
  its current one (a repeat is a no-op, not an error). DELIVERED, PICKED_UP and CANCELLED are closed: any change
  returns `409 ILLEGAL_TRANSITION`. Customers can still cancel only before PACKED. Cancelled stays closed because it has
  already returned stock, coupon use, coins and money. Business risk: an unpaid ONLINE order can now be advanced by staff.
- **Staff access.** `/api/v1/admin/orders/**` is open to ADMIN and CASHIER; everything else under `/admin` stays admin-only.
  New-order notifications go to all active staff.
- **Live updates (SSE).** `GET /api/v1/orders/stream` (customer, own orders) and `GET /api/v1/admin/orders/stream`
  (staff, all orders). Events: `ready`, `order` `{orderId, orderNo, status, previousStatus, at}`, plus a keep-alive
  comment every 25 s. Sent after commit. Streams last 10 minutes, so the app reconnects with a fresh token; EventSource
  cannot set headers, so these two paths (only) accept `?access_token=`. In-memory: one server instance only; use a
  shared channel (Redis / LISTEN-NOTIFY) when scaling out. FCM push is unchanged and remains the background path.
- **Reorder.** `GET /api/v1/orders/{id}/reorder` checks each line against live catalog and stock (UNAVAILABLE,
  OUT_OF_STOCK, LOW_STOCK, price change) so the app does not guess from the products it has loaded.
- **Order value.** Order lists (customer and admin) now carry the same breakdown as the detail view: subtotal, coupon
  and code, coins used and their discount, delivery fee, payment method/status, total.
- **Coupons.** `perUserLimit` (0 = unlimited, 1 = once, N = N times) is enforced when the order is placed, under a
  per-customer lock so parallel orders cannot beat it; cancelling gives the use back. Shown on `GET /coupons`.
- **Variants.** A variant is still a product row (cart, orders, stock, POS use its id) but rows sharing a
  `product_group_id` are one product. `GET /product-groups`, `/product-groups/{id}` (group id or any variant id),
  `/product-groups/{id}/reviews`; admin `GET|POST /admin/products/{id}/variants`, `PUT /admin/product-groups/{id}`;
  `variantName`/`variantOrder` on the product body. `/products` is unchanged. Migration V7.
- **Reviews.** Only a customer with a DELIVERED/PICKED_UP order containing the product may review
  (`422 REVIEW_NOT_ALLOWED` otherwise); the order id is stored and returned as `verified`. `GET /products/{id}/reviews/mine`
  tells the app whether to show the button. Product rating and count are recomputed on each review.

## Sold-out products are hidden from the shop

`GET /products`, `GET /hot-sale` and `GET /product-groups` leave out products whose stock is 0. In the grouped list a
product stays if any variant has stock, and only its in-stock variants are listed. Opening a product by id
(`/products/{id}`, `/product-groups/{id}`) still works and shows `inStock: false`, so wishlist, order history and shared
links keep working. Pass `includeOutOfStock=true` on the two list endpoints to get everything. Admin and POS lists are
unchanged. A product disappears from the list when the last unit is sold, so the app should refresh lists after an order.

## Feedback round: coupons, order numbers, live updates, units, payment switch, shop details

Migration `V9__units_coupon_categories_shop_details.sql`. The credit migration was renamed `V7` → `V8` because two
`V7` files existed (a database that already applied the credit file as V7 needs `flyway repair`).

- **Coupons per category.** A coupon can cover several categories (`categoryIds` on the admin body, `PublicCoupon`; `categoryId`
  still works and is the first of the list; empty = every category). Only lines of those categories are discounted, the
  minimum order is checked against those lines, and the discount is split over them to the paise
  (`order_item.coupon_discount_paise`, `Quote.couponByCategory`, per-line `couponDiscountPaise`). The counter (POS) uses the same split.
- **Order numbers.** Customer, admin, wallet ledger and stock history all show the same `orderNo` (or invoice / credit-note number for
  counter sales, via `ReferenceNumbers`). Stock movements gained `ref` (the number) next to `refId`. The prefix comes from the shop
  setting `invoicePrefix`. Admin order list accepts `q` to search by number. If a screen still shows a different number it is
  reading `id` instead of `orderNo`.
- **Live updates (no re-login).** The order SSE stream also sends `wallet` (that customer only: `{userId, reason, delta, at}`) and
  `stock` (everyone: `{productIds, reason, at}`) after commit; refetch coins / stock on them. Order responses carry `coinBalance`.
- **Units.** `GET /units` (public, active units), admin `GET|POST /admin/units`, `PUT|DELETE /admin/units/{code}`
  (`code, nameEn, nameGu, soldBy UNIT|WEIGHT, sortOrder, active`). Product `unit` must be an active unit (case-insensitive, stored in the
  unit's spelling). Renaming a code updates every product; a unit used by products can only be switched off.
- **Online payment switch.** `GET /payment-methods` lists only methods the admin switched on and, for ONLINE, whose provider keys are set.
  Admin `GET|PUT /admin/payment-methods` returns `{code, enabled, configured}`; ONLINE cannot be switched on until configured
  (`422 PAYMENT_NOT_CONFIGURED`) and at least one method must stay on.
- **Shop details.** `shop_setting.details` (admin `PUT /admin/shop-settings`, public `GET /shop-settings`). Order number prefix and the
  sign-in e-mail name now come from the shop settings.

## Launch-readiness round: stock at confirm, reject + refund, payment fixes (migration V10)

- **Stock is taken when staff CONFIRM an online order, not when it is placed.** Placing only checks the shelf
  (`InventoryService.checkAvailable`, no stock taken, `409 OUT_OF_STOCK` if clearly short); the item may be sold at the
  counter before staff look at the order. Moving the order to CONFIRMED (or any later status) takes the stock atomically;
  if the shelf is short the status change fails with `409 OUT_OF_STOCK` and the order stays where it was, so staff reject it.
  `orders.stock_reserved` records whether an order holds stock, so cancel/reject gives it back exactly once. Orders that
  existed before V10 are marked as holding stock. Customers can still see "in stock" for items promised to unconfirmed
  orders; the reject path covers that. A soft hold for unconfirmed orders was not added.
- **Reject.** `POST /api/v1/admin/orders/{id}/reject {reason}` (ADMIN, CASHIER). Cancels the order, stores the reason as
  `cancelReason` ("Rejected by shop: ..."), tells the customer ("Order rejected ... Anything you paid will be refunded"),
  gives back stock (if held), coins and coupon use, and refunds an online payment in full.
- **Unpaid online orders (`PAYMENT_PENDING`)** can only be moved by the payment result (to PLACED) or cancelled. Staff
  changing them returns `409 AWAITING_PAYMENT`.
- **Refunds are queued, not sent inside the cancel.** `refund` rows start `PENDING` in the same transaction as the cancel; they
  are sent to the provider right after commit and by a 30-second job (`PENDING` -> `PROCESSING` -> `ISSUED`, or back with
  `attempts+1`, `FAILED` after 10 tries: `FAILED` rows need a human, query `select * from refund where status='FAILED'`).
  A provider outage no longer blocks or fails a cancel. A refund stuck in `PROCESSING` for 10 minutes goes back to the queue.
- **Failed payment attempts no longer close the payment.** Razorpay lets the customer retry on the same order, so
  `payment.failed` only records the reason; the order stays `PAYMENT_PENDING` until paid or expired (15 min). A capture of the
  wrong amount is marked `MISMATCH` and refunded in full.
- **Rate limits / client IP.** `X-Forwarded-For` and `CF-Connecting-IP` are no longer believed (anyone can send them). Behind
  Cloudflare set `CLIENT_IP_HEADER=CF-Connecting-IP` and make sure the server only accepts traffic from the proxy.
  `FORWARD_HEADERS_STRATEGY` now defaults to `none`.
- **Startup guard** now runs in every profile except `dev` and `test` (not only `prod`), so starting with no profile can no
  longer run with the public default JWT secret. It also requires `RAZORPAY_WEBHOOK_SECRET` when Razorpay keys are set.
- **Counter returns.** A cashier can take returns only on bills up to `app.pos.cashier-return-days` old (default 7, 0 = no
  limit); older bills return `422 RETURN_NEEDS_ADMIN`. Admins are not limited.
- **Account deletion.** `DELETE /api/v1/me` erases the customer's name, mobile, e-mail, addresses, devices and sign-ins and
  keeps the (now nameless) account row so orders and bills stay for tax records. Refused with `409 ACCOUNT_HAS_OPEN_ORDERS`
  or `ACCOUNT_HAS_DUES`. Coins are forfeited. Wishlist rows and reviews are not erased yet.

### Low-stock and out-of-stock alerts for staff and admin
- **When:** every time a product's stock falls onto or below its own alert level (`lowThreshold`, default 5, editable per
  product by the admin), or to zero. One alert per crossing, not per sale, so staff are not spammed while an item stays low;
  restocking above the level re-arms it. Covers confirmed online orders, counter sales and admin write-downs. Returns,
  cancels and restocks never alert. The alert is written with the stock change (outbox), so a rolled-back sale raises none.
- **Who:** all active ADMIN and CASHIER accounts get an in-app notification (`kind: STOCK`, `ref` = first product id), e.g.
  "Low stock - Rice: 3 left, Dal: 4 left (+2 more)", titled "Out of stock" when any line hit zero. Retired products are skipped.
  Push goes through `PushSender`, which is still the logging stub until FCM is set up.
- **Daily digest:** 08:00 shop time, one notification to staff listing everything still at or below its level
  ("7 products need restocking"). Nothing is sent when stock is fine.
- The existing admin dashboard `lowStock` list and the live `stock` event on the order stream are unchanged.

### Cashier shifts and cash drawer (migration V11)
- `POST /api/v1/pos/shifts/open {openingFloatPaise}` (cashier, admin): one open shift per cashier (`409 SHIFT_ALREADY_OPEN`).
  `GET /pos/shifts/current` (404 when none) shows the live expected cash. `POST /pos/shifts/close {countedCashPaise, note}`.
- Expected cash = float + cash taken for bills - cash refunded + cash credit repayments, all by that cashier since opening.
  If the count differs, a written reason is required (`422 VARIANCE_NEEDS_NOTE`); the variance is stored on the shift.
  `GET /api/v1/admin/pos/shifts?cashierId=&page=&size=` (admin) lists shifts with expected, counted and variance.
- Optional rule: set `app.pos.require-open-shift=true` (env `POS_REQUIRE_OPEN_SHIFT`) and billing is refused with
  `422 SHIFT_NOT_OPEN` until the cashier opens a shift. Off by default so existing tills keep working.
- A bill void is a full return of the bill (credit note, stock back, refund); there is no separate void.

### Still open (needs a decision or outside accounts)
SMS login and FCM push (need provider accounts / DLT template), customer OTP before coins or credit are used at the counter,
round-off to the rupee, IGST and customer GSTIN, GST invoices for online orders,
returns after delivery, purchase / supplier / batch-expiry, delivery zones and slots, moving the Razorpay order creation
out of the order transaction. (Blocked staff already lose access on their next request.)

## Flash sales (migration V19)

A timed offer on chosen products. Staff (ADMIN and CASHIER) configure it; customers see it in the shop; quote, order and counter bill charge it.

**Endpoints** (staff: `/api/v1/admin/flash-sales`, ADMIN or CASHIER; customers: public GET)
- `POST /admin/flash-sales` create with items (`startsAt` omitted = start now; `endsAt` required; `channel` ONLINE|POS|BOTH; default
  `discountKind` PERCENT 1-90 | FIXED_PRICE (paise, at least 100 and below the list price) | NONE; `perCustomerLimit` per product, 0 = none;
  `bannerText`; items `{productId, discountKind?, discountValue?, maxQty?, perCustomerLimit?}`).
- `GET /admin/flash-sales?status=SCHEDULED|ACTIVE|ENDED|CANCELLED`, `GET /{id}`, `PATCH /{id}` (scheduled: anything; running: name, texts and end time only),
  `POST /{id}/items`, `PUT /{id}/items/{itemId}`, `DELETE /{id}/items/{itemId}` (once a unit sold: only the cap may be raised, no removal),
  `POST /{id}/start-now`, `/end-now`, `/cancel`, `/duplicate {name?, startsAt?, endsAt?}`.
- `GET /admin/flash-sales/{id}/report` and `GET /admin/flash-sales/report?from=&to=` (units, orders, bills, revenue, discount, average discount,
  customers, sell-through of the caps, by item / channel / hour in shop time; best items for a range). Given-back units are left out.
- `GET /api/v1/flash-sales/active` and `/upcoming` (public): `{now, sales:[{id,name,description,bannerText,startsAt,endsAt,secondsToEnd,items:[{productId,
  nameEn,listPricePaise,salePricePaise,discountPercent,unitsLeft,perCustomerLimit,inStock}]}]}`. `unitsLeft` null = unlimited, 0 = sold out.
- Products and product groups carry an optional `flash` object `{flashSaleId,name,listPricePaise,salePricePaise,discountPercent,unitsLeft,perCustomerLimit,
  startsAt,endsAt}` (on groups: per variant, and on the group for the first variant on sale). `pricePaise` stays the normal price.
- Quote lines add `flashSaleId`, `listPricePaise`, `flashDiscountPaise`.

**Rules**
- Status is computed (SCHEDULED, ACTIVE, ENDED, CANCELLED); never stored. A product can be in only one non-cancelled sale whose window overlaps: `409 FLASH_OVERLAP` names the other sale.
- Sale price = the product's CURRENT list price minus the percent (rounded half-up to the paisa) or the fixed price; never below Rs 1, never above the list price.
  An item's own discount beats the sale default; an item with no discount of its own in a sale with default NONE is refused.
- Caps: `maxQty` for the whole sale (then the item sells at the normal price again) and a per-customer limit per product. Counted by one conditional
  UPDATE (no oversell); a quantity above what is left is priced as two quote lines (sale price for the units left, list price for the rest). If someone takes the
  last units between quote and placement the order answers `409 PRICE_CHANGED` with a fresh quote. Cancel, reject, payment failure and payment timeout of an
  order give its units back exactly once (`flash_consumption.released_at`). Counter returns and online order returns do NOT give units back.
- Coupons and coins apply on top of the sale price, with no special rule.
- Counter: items sold by weight are never discounted; an offline bill is always recorded (it takes what is left of the sale, the rest is just recorded); a bill without a
  customer mobile is not subject to the per-customer limit.
- Limits: name 1-80, description <= 300, banner <= 80, at most 200 items, a sale runs at most 30 days, a start more than a minute in the past is refused.
- Once a minute a job announces started / ended sales to staff (outbox types `FLASH_STARTED`, `FLASH_ENDED`; `FLASH_ITEM_SOLD_OUT` once per item), publishes the live area
  `FLASH` and empties the `flash` cache. Apps should refetch the sale list and product prices when `FLASH` arrives.
- Code: `common/FlashPricing` is the port (optional setter in `SubtotalStep`, `OrderService`, `PosService`, `CatalogService`); `flash/` holds the module.


## Follow-up round: defective returns, repeat-safe admin actions, review fixes

### Defective returns (review flow)
- A return line with `restock:false` (online `POST /admin/orders/{id}/returns`, counter `POST /pos/sales/{id}/returns`) is a *claim*: the customer is refunded, the units are **not** added to the shelf, and a batch is written to `defective_stock` (V21) with status `PENDING_REVIEW`.
- Staff or admin review it (`POST /pos/stock/defective/{id}/review`): `CONFIRM_DEFECTIVE` -> `HELD` (the defective list), `RESTOCK` -> `RESTOCKED` (units added back to inventory, stock movement `DEFECTIVE_REVIEW_RESTOCK`). Once per batch.
- The admin then writes off or returns to the supplier a `HELD` batch (`POST /admin/stock/defective/{id}/resolve`): `WRITE_OFF` | `RETURN_TO_SUPPLIER`. Confirmed-defective goods never go back to sellable stock.
- Reports: `GET /admin/stock/defective` (+ `/summary`), staff queue `GET /pos/stock/defective`. Metrics: `mall_stock_defective_pending_units`, `mall_stock_defective_units`. Audit actions `DEFECTIVE_*`.

### Repeat-safe admin actions
- `common/idem/AdminOps` + table `admin_op` (V20): optional `Idempotency-Key` header (max 100 chars) on stock adjust, coin adjust, credit adjust, stock take, and create product/category/coin rule. The key is stored in the same transaction as the action, so a failed action does not use it up; a repeat returns the current state. Keys are purged after 30 days. Without the header behaviour is unchanged.

### Fixes from the code review and the frontend-guide review
- Returns on orders split by a flash sale no longer crash (units come from the product's lines in order); client-IP header setting is applied live; Razorpay calls have timeouts and refunds sent after a commit are limited to 5 (the 30 s job takes the rest); a retried refund first asks Razorpay whether it already has it (receipt/notes carry our refund id); offline bills need no open shift at upload and take their invoice year from `soldAt`; SMTP port 465 uses implicit TLS and other ports use STARTTLS when offered.
- Omitted fields keep what is there: profile name, address default flag (and deleting the default promotes the next address), product stock and alert level on edit. `PATCH /admin/customers/{id}` needs `active`. Negative `minOrderPaise` on a coin rule is a 400. The quantity limit of 99 applies to a product's merged total and to the saved basket. Orders waiting for online payment carry `paymentExpiresAt`. A counter sale without a cart or a payment without a mode is a 400.
- Not changed on purpose: staff may still move an order to any non-pending status in any order (documented design). Known limits still open: offline bills are re-priced at upload (a changed price or ended flash sale can reject a bill); an amount-mismatch payment leaves the order pending.
