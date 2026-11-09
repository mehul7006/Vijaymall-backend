# Vijay Super Mall: Frontend Integration Guide (start here)

Audience: the Flutter app team, the web app team, and the LLM coding assistants they use. This guide is written so a frontend can be built from it without reading backend code. Read this file first, then the section files:

| File | Covers |
|---|---|
| `00-overview.md` (this file) | Roles, authentication, request/response conventions, errors, idempotency, money, pagination, live events, images, rate limits |
| `10-customer-and-catalog.md` | Sign-in, profile, addresses, devices, wallet and coins, notifications, wishlist, catalog, reviews, coupons, units, admin catalog and customer tools |
| `20-orders-payments-admin-config.md` | Cart quote, orders, returns, online payment and refunds, public shop details, shop profile, admin configuration screen, caches, logs, live events |
| `30-pos-reports-purchasing.md` | Shop-counter POS, shifts, credit (udhar), offline sync, purchasing, stock takes, reports, audit log |
| `40-flash-sales.md` | Flash sale module (added after it is merged) |
| `60-admin-push-notifications.md` | Admin push notifications: broadcasts to users, role groups or condition-based customer segments, segment builder, previews, inbox kind `ANNOUNCEMENT` |

The backend is one REST API over HTTPS. Base path: `/api/v1`. All bodies are JSON (`Content-Type: application/json`) except image uploads (`multipart/form-data`) and the live-event stream (`text/event-stream`).

## 1. Who uses what

| Role | Who | Apps / screens |
|---|---|---|
| Guest (no token) | Anyone browsing | Catalog, product details, reviews list, public shop details, units, coupons list, hot sale |
| `CUSTOMER` | A shopper signed in with e-mail or mobile OTP | Cart, orders, wallet, wishlist, addresses, notifications, reviews |
| `CASHIER` | Counter staff | POS screens (`/pos/**`) and order handling (`/admin/orders/**`) |
| `ADMIN` | Shop owner | Everything, including all other `/admin/**` screens |

Rules the UI should mirror: `/api/v1/admin/orders*` is open to ADMIN and CASHIER; every other `/api/v1/admin/**` is ADMIN only (this includes `/api/v1/admin/notifications/**`: broadcasts, audience size, segments, segment preview and the condition catalog, see file 60); `/api/v1/pos/**` is CASHIER and ADMIN. A CUSTOMER token on those paths gets 403. After login, the response carries `user.role`, so the app can choose which home screen to show. Hide screens a role cannot use, but never rely on hiding: the server enforces it.

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
ISO-8601 in UTC (`2026-11-08T17:52:42Z`). The shop's own clock is India time (`Asia/Kolkata`, UTC+5:30); the public shop details endpoint returns `timezone`, `openNow` and `todayHours` already computed, so do not recompute them on the device.

### IDs
UUID strings. Order numbers and invoice numbers are human-readable strings for display and search; use `id` for API calls.

### Pagination
List endpoints that page return `{ "items": [...], "page": 0, "size": 20, "total": 123, "totalExact": true, "hasMore": true }`. `total` is exact up to 10,000 rows; above that it is `10000` with `totalExact: false` (show "10,000+"). `hasMore` is always exact: page until it is `false`. `page` starts at 0. `size` is clamped to between 1 and 50 (asking for more returns 50). Plain lists (no page object) are documented per endpoint.

### Language
Catalog items carry English and Gujarati names (`nameEn`, `nameGu`). Show the one matching the user's language and fall back to English.

### Headers you receive
`X-Trace-Id` is on every response. Show it in "Something went wrong (ref abc123)" screens and send it to support: it finds the exact request in the server logs.

### Idempotency-Key (important)
Endpoints that create money or stock effects require an `Idempotency-Key` request header: placing an order, a POS sale, a return, a purchase, offline sync, and similar (each endpoint says so). Generate a fresh random UUID **per user action** (when the user taps Pay), keep it with that action, and **reuse the same key on every retry** of that action (timeouts, network drops, app restarts). The server then returns the original result instead of creating a second order or bill. Never reuse a key for a different action. A missing key on those endpoints answers 400.

### Errors
Every error has this body (HTTP status varies):

```json
{ "code": "VALIDATION_FAILED", "message": "Please check the highlighted fields", "traceId": "a1b2c3d4", "details": { "fields": { "email": "must be a well-formed email address" } } }
```

- `code` is stable and machine readable: switch on it. `message` is human readable and safe to show.
- `details.fields` (validation errors) maps request field names to messages; highlight those inputs.
- `traceId` equals the `X-Trace-Id` header.

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

Unknown codes: show `message`. Each endpoint lists its own specific codes.

### Rate limits
Per IP and per account, for example one-time-code requests per hour. A 429 includes a message saying when to retry. Do not auto-retry 429 in a tight loop. Sign-in screens should disable the "resend" button for a short cool-down.

### Caching and conditional requests
`GET /api/v1/shop-settings` returns an `ETag`; send `If-None-Match` and treat 304 as "unchanged". Catalog lists are cached on the server for a short time and change immediately when an admin edits data; the apps should refresh when they receive a live event (below) or on pull-to-refresh.

### Images
Product, category and shop images are served from `/img/**` (public, no token). API fields give a path like `/img/product/abc_400.jpg`: prefix with the server origin. A `_400` and `_1000` variant exist for uploaded images (small and large). Uploads use `multipart/form-data` with a part named `file`; files over 5 MB (6 MB per request) or non-images are refused.

### Live updates (Server-Sent Events)
The server can push "something changed" events so screens stay fresh (catalog, coupons, units, shop details, payment methods; orders). The stream endpoints and event names are in `20-orders-payments-admin-config.md`. Treat an event as a hint to re-fetch the affected list; do not trust it as data. Reconnect with back-off on drop.

### Push notifications
Register the device token with the devices endpoint (see `10-customer-and-catalog.md`) after sign-in and remove it on logout. Pushes carry an order or alert reference; open the matching screen then fetch fresh data.

## 4. Health of the integration (for testers)

- Staging/dev builds use a fixed OTP of `12345` for any e-mail; production never does.
- Dev data: admin `admin@vijaysupermall.local`, cashier `cashier@vijaysupermall.local`.
- Interactive API docs may be available at `/swagger-ui.html` on dev servers.

## 5. Building the app from this guide

Suggested build order: auth and token refresh, catalog browse (guest), cart quote, place order (COD), order tracking, online payment, profile/addresses, wallet, notifications, then staff screens (order desk), POS counter, reports and admin settings. For every endpoint, the section files give: who may call it, why it exists, the exact request fields with validation, the response shape, the error codes, and UI notes.
