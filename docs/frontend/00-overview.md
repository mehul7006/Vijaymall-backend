# Vijay Super Mall: Frontend Integration Guide (start here)

Audience: the Flutter app team, the web app team, and the LLM coding assistants they use. This guide is written so a frontend can be built from it without reading backend code. Read this file first, then the section files:

| File | Covers |
|---|---|
| `01-implementation-guide.md` | **Start here for the plan:** what to build per app, what changed, what to configure, build order |
| `00-overview.md` (this file) | Roles, authentication, request/response conventions, errors, idempotency, money, pagination, live events, images, rate limits |
| `10-customer-and-catalog.md` | Sign-in, profile, addresses, devices, wallet and coins, notifications, wishlist, catalog, reviews, coupons, units, admin catalog and customer tools |
| `20-orders-payments-admin-config.md` | Cart quote, orders, returns, online payment and refunds, public shop details, shop profile, admin configuration screen, caches, logs, live events |
| `30-pos-reports-purchasing.md` | Shop-counter POS, shifts, credit (udhar), offline sync, purchasing, stock takes, reports, audit log |
| `40-flash-sales.md` | Flash sale module |
| `60-admin-push-notifications.md` | Admin push notifications: broadcasts to users, role groups or condition-based customer segments, segment builder, previews, inbox kind `ANNOUNCEMENT` |
| `70-purchases.md` | Suppliers, purchase documents with GST, cess, discount and freight, payments and payables, returns to suppliers, supplier statement, credit and refunds, invoice file |
| `72-barcode-labels.md` | Store barcode generation and label printing (PDF, thermal printer commands, JSON) |
| `71-bank-and-settlements.md` | Bank module: money accounts, payment channels (several vendors at once), receipts, settlements, statement import, one card-machine flow for every vendor |
| `73-paytm-terminal.md` | The Paytm wireless swipe machine as a vendor of that flow: set-up, till flow, void, Paytm settlement import |
| `74-stock-lots-and-pricing.md` | Stock lots, lot-aware prices and scanning, FIFO cost, expiry, margin and stock value reports |
| `playbooks/README.md` | Step-by-step implementation playbooks P1 to P5 (orders, purchases and labels, counter till, bank module, stock lots), checked against the code |
| `50`, `51`, `52-backend-response-*.md` | The backend's answers to the Flutter reviews, item by item (change history) |

The backend is one REST API over HTTPS. Base path: `/api/v1`. All bodies are JSON (`Content-Type: application/json`) except image uploads (`multipart/form-data`) and the live-event stream (`text/event-stream`).

## 1. Who uses what

| Role | Who | Apps / screens |
|---|---|---|
| Guest (no token) | Anyone browsing | Catalog, product details, reviews list, public shop details, units, coupons list, hot sale |
| `CUSTOMER` | A shopper signed in with e-mail or mobile OTP | Cart, orders, wallet, wishlist, addresses, notifications, reviews |
| `CASHIER` | Counter staff | POS screens (`/pos/**`), order handling (`/admin/orders/**`) and flash sales (`/admin/flash-sales/**`, file 40) |
| `ADMIN` | Shop owner | Everything, including all other `/admin/**` screens |

Rules the UI should mirror: `/api/v1/admin/orders*` and `/api/v1/admin/flash-sales/**` (including the new `DELETE`) are open to ADMIN and CASHIER; every other `/api/v1/admin/**` is ADMIN only (this includes `/api/v1/admin/notifications/**`: broadcasts, audience size, segments, segment preview and the condition catalog, see file 60); `/api/v1/pos/**` is CASHIER and ADMIN. A CUSTOMER token on those paths gets 403. After login, the response carries `user.role`, so the app can choose which home screen to show. Hide screens a role cannot use, but never rely on hiding: the server enforces it.

## 2. Authentication (passwordless OTP)

There are no passwords. A user signs in with a one-time code sent to an e-mail address or a mobile number.

1. `POST /api/v1/auth/otp/send` with `{ "email": "a@b.com" }` sends a code. Answers 204 (no body).
2. `POST /api/v1/auth/otp/verify` with `{ "email", "otp", "deviceId" }` answers the token pair below. `deviceId` is optional text identifying the phone or browser.
3. Mobile login uses `POST /api/v1/auth/otp/send-mobile` with `{ "mobile" }` and `POST /api/v1/auth/otp/verify-mobile` with `{ "mobile", "otp", "deviceId" }`. If the shop has not configured SMS, mobile login answers 503, so show "Use e-mail instead".
4. Verify answers `{ "accessToken", "refreshToken", "expiresInSeconds", "user": { "id", "email", "name", "mobile", "role" } }`.
5. Send `Authorization: Bearer <accessToken>` on every protected call.
6. The access token lives about 15 minutes (`expiresInSeconds`). When a call answers 401, call `POST /api/v1/auth/refresh` with `{ "refreshToken" }` once, store the new pair (the refresh token is rotated: the old one stops working), then retry the original call once. If refresh fails, send the user to sign-in.
7. `POST /api/v1/auth/logout` with `{ "refreshToken" }` ends the session (204). Delete both tokens locally.

Store tokens in secure storage (Keychain/Keystore on Flutter; memory plus an httpOnly-safe strategy on web, since the API uses bearer tokens, not cookies). Details, validation and errors for these calls are in `10-customer-and-catalog.md`.

A blocked staff member's token stops working on the next request (403/401). A blocked customer can keep a still-valid access token for up to 15 minutes; show the server's message when it eventually fails.

## 3. Conventions that apply to every endpoint

### Money
All money is an integer number of **paise** (1 rupee = 100 paise), field names end in `Paise`. Never use floating point. Show `Rs 125.00` for `12500`. A negative value only appears where documented (for example `roundOffPaise` on a bill).

### Dates and times
ISO-8601 in UTC (`2026-11-08T17:52:42Z`) in every response. **Requests** (JSON fields and query parameters such as `updatedSince`) accept the same, an offset (`+05:30`), a zone-less date-time (`2026-11-09T15:30:00`, read as India time) and a plain date (`2026-11-09`, start of that day in India time). `updatedSince` and the `PATCH` hot-sale window used to accept only `Z`/offset and are now lenient too. The shop's own clock is India time (`Asia/Kolkata`, UTC+5:30); the public shop details endpoint returns `timezone`, `openNow` and `todayHours` already computed, so do not recompute them on the device.

### IDs
UUID strings. Order numbers and invoice numbers are human-readable strings for display and search; use `id` for API calls.

### Pagination
List endpoints that page return `{ "items": [...], "page": 0, "size": 20, "total": 123, "totalExact": true, "hasMore": true }`. `total` is exact up to 10,000 rows; above that it is `10000` with `totalExact: false` (show "10,000+"). `hasMore` is always exact: page until it is `false`. `page` starts at 0. `size` is clamped to between 1 and 50 (asking for more returns 50). Plain lists (no page object) are documented per endpoint.

### Language
Catalog items carry English and Gujarati names (`nameEn`, `nameGu`). Show the one matching the user's language and fall back to English.

### Headers you receive
`X-Trace-Id` is on every response. Show it in "Something went wrong (ref abc123)" screens and send it to support: it finds the exact request in the server logs.

### Idempotency-Key (important)
Endpoints that create money or stock effects require an `Idempotency-Key` request header: placing an order, a POS sale, a return, a purchase, offline sync, and similar (each endpoint says so). Generate a fresh random UUID **per user action** (when the user taps Pay), keep it with that action, and **reuse the same key on every retry** of that action (timeouts, network drops, app restarts). The server then returns the original result instead of creating a second order or bill. Never reuse a key for a different action. A missing key on those endpoints answers 400. A key reused with a DIFFERENT body is answered 409 `IDEMPOTENCY_KEY_REUSED` on purchases, supplier/credit/catalog/wallet admin writes and broadcasts, while counter sales, returns and repayments look the key up only and return the first result (each endpoint says which). The key is optional on some writes (money accounts and entries, the generic card-terminal start, supplier create); the endpoint's section says so, and not every write takes one (for example supplier `PUT`).

### Errors
Every error has this body (HTTP status varies):

```json
{ "code": "VALIDATION_FAILED", "message": "Please check the highlighted fields", "traceId": "a1b2c3d4", "details": { "fields": { "email": "must be a well-formed email address" } } }
```

- `code` is stable and machine readable: switch on it. `message` is human readable and safe to show.
- `details.fields` (validation errors) maps request field names to messages; highlight those inputs. Field names are JSON paths (`items[0].qty`), header names for a missing header (`Idempotency-Key`), and parameter names for path and query values.
- `traceId` equals the `X-Trace-Id` header.
- Advice: switch on `code` (stable); show `details.fields[<field>]` under the matching input; show `message` as the toast. Never parse `message` or the field texts to make decisions: the texts are for people and may be reworded.

#### Request mistakes: what you get

All of these are `400 VALIDATION_FAILED` with `details.fields[<field>]` set. The server reports the first unreadable value in the body (for example a wrong type) on its own; missing or invalid fields found by validation are reported all at once.

| Mistake | Field | Text |
|---|---|---|
| A required field left out or `null` (code, kind, value, nameEn ...) | the field | `is required` |
| A number below its minimum / above its maximum | the field | `must be 0 or more` / `must be 99 or less` |
| A word that is not one of the allowed values (`fulfilment`, `coinMode`) | the field | `must be DELIVERY or PICKUP`, `must be NONE, ALL or PERCENT`; a list of up to three allowed values reads `must be A, B or C` (two: `must be A or B`), a longer list reads `must be one of A, B, C, D` (for example `must be one of PLACED, CONFIRMED, ...`, `audience: must be one of ALL, CUSTOMERS, STAFF, ADMINS, CASHIERS, USERS, SEGMENT`, the refunds `status` filter reads `must be PENDING, PROCESSING, ISSUED or FAILED`). Do not hard-code the text: show the message from `details.fields` |
| Text where a whole number goes (`items[0].qty: "two"`) | `items[0].qty` | `must be a whole number` |
| A decimal where a number goes | the field | `must be a number` |
| A fraction or a decimal point where a whole number goes (stock `delta: 1.5`, coin `paisePerCoin: 1.5`; `5.0` is refused too, send `5`) | the field | `must be a whole number` (never silently cut down) |
| Text longer than the field allows (name, description, address ...) | the field | `size must be between 0 and 120` |
| A bad id (`items[0].productId: "abc"`, or `/admin/orders/not-a-uuid` in the path) | `items[0].productId` / `id` | `must be a valid id` |
| A bad true/false | the field | `must be true or false` |
| A bad date or date and time | the field | `must be a valid date` / `must be a valid date and time` |
| A list or object where the other is expected | the field | `must be a list` / `must be an object` |
| A bad value in the path or query (id, enum, number) | the parameter | same texts as above |
| A missing query parameter | the parameter | `is required` |
| A missing header (for example `Idempotency-Key`) | the header name | `is required`; `message` is `Missing header Idempotency-Key` |
| Broken JSON, an empty body or the wrong shape as a whole | none (`details` is empty) | `message` is `The request body is not valid JSON` |
| A pincode that is not 6 digits | `pincode` | `must be 6 digits` |
| A payment method that is not offered | `paymentMethod` | `must be COD or ONLINE (got "CASH")` |
| An image over 5 MB (code `FILE_TOO_LARGE`) | `file` | `must be 5 MB or smaller`; `message` also says so |

Common statuses and what the UI should do:

| Status | Typical `code` | Meaning and UI action |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Bad or missing input. Show `details.fields` next to inputs, or `message` |
| 401 | `UNAUTHENTICATED` | Missing, expired or invalid token. Refresh once, then sign in |
| 403 | `FORBIDDEN` | Role not allowed, or account blocked. Show "not allowed" |
| 404 | `NOT_FOUND` | Missing item, or somebody else's item (customers cannot see other people's orders) |
| 409 | `OUT_OF_STOCK`, others | State conflict (stock, duplicate, already done). Refresh the screen's data and let the user retry |
| 422 | `ORDERS_PAUSED`, others | The request is valid but a business rule refuses it. Show `message` |
| 429 | `RATE_LIMITED` | Too many requests. Wait, then retry; do not hammer |
| 503 | provider errors | A provider (SMS, payment) is not set up or is down. Show `message` |
| 503 | `SERVICE_BUSY` | The database pool is full: retry after `Retry-After` seconds (`details.retryAfterSeconds`); distinct from provider 503s like `SMS_NOT_CONFIGURED`. Also sent for a token check that could not reach the database |

Unknown codes: show `message`. Each endpoint lists its own specific codes.

### Rate limits
Per IP and per account, for example one-time-code requests per hour. A 429 includes a message saying when to retry. Do not auto-retry 429 in a tight loop. Sign-in screens should disable the "resend" button for a short cool-down.

### Caching and conditional requests
`GET /api/v1/shop-settings` returns an `ETag`; send `If-None-Match` and treat 304 as "unchanged". Catalog lists are cached on the server for a short time and change immediately when an admin edits data; the apps should refresh when they receive a live event (below) or on pull-to-refresh.

### Images
Product, category and shop images are served from `/img/**` (public, no token). API fields give a path like `/img/product/abc_400.jpg`: prefix with the server origin. A `_400` and `_1000` variant exist for uploaded images (small and large). A picture without transparency is stored as JPEG (`..._400.jpg`); a picture with an alpha channel (a transparent PNG logo) is stored as PNG (`..._400.png`), so always use the exact path the API returns and only swap `_400` for `_1000`. Image files are served with `Cache-Control: public, max-age=31536000, immutable` (each upload has a new UUID name, so a path never changes its content): cache them for good, and also send `If-None-Match`/`If-Modified-Since` if your HTTP client does not cache by itself (`Last-Modified` is set; a repeat gets 304). Uploads use `multipart/form-data` with a part named `file`; files over 5 MB (6 MB per request) or non-images are refused.

### Live updates (Server-Sent Events)
The server can push "something changed" events so screens stay fresh (catalog, coupons, units, shop details, payment methods; orders). The stream endpoints and event names are in `20-orders-payments-admin-config.md`. Treat an event as a hint to re-fetch the affected list; do not trust it as data. Reconnect with back-off on drop.

### Push notifications
Register the device token with the devices endpoint (see `10-customer-and-catalog.md`) after sign-in and remove it on logout. Every push carries a string-only `data` payload: `type` (`ORDER`, `PAYMENT`, `STOCK`, `FLASH` or `ANNOUNCEMENT`, the same as the inbox `kind`) and `ref` (order number such as `VSM-1001`, product id for `STOCK`, sale id for `FLASH`, the admin's free text for `ANNOUNCEMENT`; the key is left out when there is none), plus `broadcastId` for announcements. Open the matching screen from `type` and `ref`, then fetch fresh data; ignore unknown types and keys. Details in file 60.

## 4. Health of the integration (for testers)

- Staging/dev builds use a fixed OTP of `12345` for any e-mail; production never does.
- Dev data: admin `admin@vijaysupermall.local`, cashier `cashier@vijaysupermall.local`.
- Interactive API docs may be available at `/swagger-ui.html` on dev servers.

## 5. Building the app from this guide

Suggested build order: auth and token refresh, catalog browse (guest), cart quote, place order (COD), order tracking, online payment, profile/addresses, wallet, notifications, then staff screens (order desk), POS counter, reports and admin settings. For every endpoint, the section files give: who may call it, why it exists, the exact request fields with validation, the response shape, the error codes, and UI notes.

## 6. Test environments

- The seeded dev admin is whatever `ADMIN_EMAIL` is set to on that server (default `admin@vijaysupermall.local`; the cashier is `cashier@vijaysupermall.local`). A shared test instance that sets `ADMIN_EMAIL=admin@mall.com` has `admin@mall.com` as its admin instead, and the default address will not exist there. Ask whoever runs the instance which address it uses.
- In the dev profile the sign-in code for any e-mail is `12345` unless the server's `OTP_FIXED_CODE` overrides it (production never has a fixed code); when it is empty the code is printed in the server log.
- Swagger UI and the OpenAPI JSON (`/v3/api-docs`) are on in dev and off in production.
