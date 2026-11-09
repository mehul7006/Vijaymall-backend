# 10 - Customer, Catalog, Wallet, Notifications, Wishlist, Coupons (frontend guide)

Scope: identity (auth, profile, addresses, devices, account deletion), wallet (coins), notifications, wishlist, catalog (categories, products, product groups, reviews, units, hot sale, admin catalog), admin coupons, admin customers.

All paths are relative to the API host. Every path starts with `/api/v1`.

## Global conventions used by every endpoint below

- **Auth header**: `Authorization: Bearer <accessToken>` for every endpoint whose Who is not "public".
- **JSON field names** are exactly the Java record component names (camelCase), e.g. `isDefault`, `pricePaise`. Null fields are always sent (`default-property-inclusion: always`), so a nullable field appears as `null`, never missing.
- **Money** is always an integer number of **paise** (1 rupee = 100 paise). Field names end in `Paise`. Never use floats for money.
- **Dates/times** are ISO-8601 UTC instants, e.g. `"2026-11-08T10:15:30Z"` (Java `Instant`).
- **IDs** are UUID strings.
- **Error body** (all errors): `{ "code": "VALIDATION_FAILED", "message": "...", "traceId": "...", "details": { ... } }`. `details` is `{}` when empty. Show `message` only for debugging; branch UI on `code` (and `details`).
- **Validation errors** (HTTP 400, code `VALIDATION_FAILED`): `details.fields` is a map `fieldName -> message`. Bean-validation failures have message "Please check the highlighted fields" and the per-field messages are the default Jakarta texts (e.g. "must not be blank"); service-level failures carry a single field with a hand-written message. A malformed JSON body, bad UUID/number in a path or query, or missing required query param gives 400 `VALIDATION_FAILED` with message "Bad request" and empty details.
- **Unauthenticated / expired / bad token**: 401 `UNAUTHENTICATED`. **Signed in but wrong role**: 403 `FORBIDDEN`. These can happen on every non-public endpoint and are not repeated in each Errors table unless specific.
- **Rate limit**: every `/api/**` call is limited to 300 per minute per client IP. Over the limit: 429 `RATE_LIMITED` with `details.retryAfterSeconds` (integer). OTP endpoints have extra tight limits (below). Respect `retryAfterSeconds`.
- **Paging**: list endpoints that say "paged" take `page` (int, default `0`, zero based; negative is treated as 0) and `size` (int, default `20`; clamped to the range 1..50, so `size=500` returns at most 50). Response is `{ "items": [...], "page": 0, "size": 20, "total": 123 }` (`total` = total matching rows across all pages). Some paged endpoints add extra fields (documented).
- **Roles**: `CUSTOMER`, `CASHIER`, `ADMIN` (string values of `user.role`). SecurityConfig rules: `/api/v1/auth/**` public; `GET` on categories, products, products/{id}, products/{id}/reviews, hot-sale, shop-settings, coupons, product-groups, product-groups/{id}, product-groups/{id}/reviews, units is public; `/api/v1/admin/orders/**` is ADMIN+CASHIER; every other `/api/v1/admin/**` is ADMIN only; everything else needs any valid sign-in. Cashiers therefore get 403 on all admin endpoints in this document.
- **Image URLs** (`imageUrl`) are server-relative paths like `/img/products/<uuid>_400.jpg` (a `_1000.jpg` sibling exists with the same prefix for a larger image). Prefix with the API host. `/img/**` is public.
- **Live updates**: catalog, unit, coupon and wallet changes are pushed to open apps by the live-events stream (documented elsewhere); this guide only notes where an endpoint triggers one.

---

## Table of contents

### Auth (public)
- POST /api/v1/auth/otp/send - send a login code to an email
- POST /api/v1/auth/otp/verify - exchange email + code for tokens (signs up on first use)
- POST /api/v1/auth/otp/send-mobile - send a login code by SMS
- POST /api/v1/auth/otp/verify-mobile - exchange mobile + code for tokens (signs up on first use)
- POST /api/v1/auth/refresh - rotate refresh token, get new access token
- POST /api/v1/auth/logout - revoke a refresh token

### Profile, addresses, devices, account deletion (customer)
- GET /api/v1/me - current profile
- PUT /api/v1/me - update name / mobile
- DELETE /api/v1/me - delete my account
- GET /api/v1/me/addresses - list saved addresses
- POST /api/v1/me/addresses - add address
- PUT /api/v1/me/addresses/{id} - edit address
- DELETE /api/v1/me/addresses/{id} - delete address
- POST /api/v1/me/devices - register push (FCM) token

### Notifications (customer)
- GET /api/v1/me/notifications - inbox (paged) with unread count
- POST /api/v1/me/notifications/{id}/read - mark one read
- POST /api/v1/me/notifications/read-all - mark all read

### Wishlist (customer)
- GET /api/v1/me/wishlist - saved products
- POST /api/v1/me/wishlist/{productId} - add
- DELETE /api/v1/me/wishlist/{productId} - remove

### Wallet / coins
- GET /api/v1/wallet - balance, settings and active earn rules (customer)
- GET /api/v1/wallet/ledger - coin history (customer, paged)
- GET /api/v1/admin/coin-rules - list earn rules (admin)
- POST /api/v1/admin/coin-rules - create rule (admin)
- PUT /api/v1/admin/coin-rules/{id} - update rule (admin)
- DELETE /api/v1/admin/coin-rules/{id} - delete rule (admin)
- GET /api/v1/admin/coin-settings - coin value/redeem/expiry settings (admin)
- PUT /api/v1/admin/coin-settings - change those settings (admin)

### Catalog browsing (public)
- GET /api/v1/categories - active categories
- GET /api/v1/products - flat product list (one row per variant)
- GET /api/v1/products/{id} - one product (variant) detail
- GET /api/v1/product-groups - products with variants (preferred listing)
- GET /api/v1/product-groups/{id} - one product with all variants
- GET /api/v1/hot-sale - banners + hot products
- GET /api/v1/units - active units of measure
- GET /api/v1/coupons - coupons on offer (public)

### Reviews
- GET /api/v1/products/{productId}/reviews - reviews of one variant (public)
- GET /api/v1/product-groups/{id}/reviews - reviews of all variants (public)
- GET /api/v1/products/{productId}/reviews/mine - may I review / my review (customer)
- POST /api/v1/products/{productId}/reviews - write or replace my review (customer)

### Admin catalog (admin)
- GET /api/v1/admin/products - admin product list (paged)
- POST /api/v1/admin/products - create product
- GET /api/v1/admin/products/{id} - product with stock/admin fields
- PUT /api/v1/admin/products/{id} - update product
- GET /api/v1/admin/products/{id}/variants - all variants of a product
- POST /api/v1/admin/products/{id}/variants - add a variant
- PUT /api/v1/admin/product-groups/{id} - edit fields shared by all variants
- POST /api/v1/admin/products/{id}/stock - restock / adjust stock
- GET /api/v1/admin/stock-movements - stock history (paged)
- POST /api/v1/admin/products/{id}/image - upload product image
- POST /api/v1/admin/categories - create category
- PUT /api/v1/admin/categories/{id} - update category
- GET /api/v1/admin/hot-banners - list banners
- POST /api/v1/admin/hot-banners - create banner
- PUT /api/v1/admin/hot-banners/{id} - update banner
- DELETE /api/v1/admin/hot-banners/{id} - delete banner
- GET /api/v1/admin/units - all units incl. switched-off
- POST /api/v1/admin/units - create unit
- PUT /api/v1/admin/units/{code} - update unit
- DELETE /api/v1/admin/units/{code} - delete unused unit

### Admin coupons and customers (admin)
- GET /api/v1/admin/coupons - list coupons
- POST /api/v1/admin/coupons - create coupon
- PUT /api/v1/admin/coupons/{id} - update coupon
- DELETE /api/v1/admin/coupons/{id} - delete (or deactivate) coupon
- GET /api/v1/admin/customers - search customers (paged)
- GET /api/v1/admin/customers/{id} - customer detail
- PATCH /api/v1/admin/customers/{id} - block / unblock
- POST /api/v1/admin/customers/{id}/coins - give / take back coins

---

## Flows

### Flow A - Sign in with email OTP, then token refresh
1. User types email. App calls `POST /auth/otp/send` -> 204. Show a 5-digit code box. The code is valid for 5 minutes; at most 5 wrong tries per code; a newly sent code replaces the previous one (the latest open code is the one checked).
2. User enters code. App calls `POST /auth/otp/verify` with `{email, otp, deviceId}`. Success returns `{accessToken, refreshToken, expiresInSeconds, user}`. If the email is new, the account is created as a `CUSTOMER` on the spot (there is no separate sign-up call).
3. Store `refreshToken` in secure storage, keep `accessToken` in memory. Send `Authorization: Bearer <accessToken>`. Access tokens live `expiresInSeconds` (900 s = 15 min with default config; read the value, do not hard-code). Refresh tokens live 30 days (default config).
4. When any call returns 401 `UNAUTHENTICATED` (or just before expiry), call `POST /auth/refresh` with the refresh token. The response contains a NEW refresh token; the old one is revoked and must be discarded (rotation). Only one refresh call may be in flight at a time (serialise it): sending the same old refresh token twice is treated as theft and revokes the whole device session, forcing a fresh login. If refresh returns 401, go to the login screen.
5. Logout: `POST /auth/logout` with the refresh token, then clear local tokens.
6. The `user.role` tells which app shell to show: `CUSTOMER` (shopping app), `CASHIER` (counter), `ADMIN` (back office). Admin/cashier accounts sign in with email OTP.

### Flow B - Sign in with mobile OTP
Same as Flow A but with `/auth/otp/send-mobile` and `/auth/otp/verify-mobile`. A verified mobile becomes the account's login identity: the account is found by that mobile; otherwise a customer created at the shop counter with that number is claimed (so counter coins/history appear in the app); otherwise a new customer with no email is created (`user.email` is `null`). If the server has no SMS provider, send returns 503 `SMS_NOT_CONFIGURED` - hide/disable mobile login and fall back to email.

### Flow C - Profile, addresses, push token
After login: `POST /me/devices` (once per app start/token change) to receive push notifications -> `GET /me` -> `GET /me/addresses`. Checkout uses addresses (the `id` is referenced by order endpoints, documented elsewhere). The first address added is automatically the default.

### Flow D - Browse, search, product detail, reviews
1. Home: `GET /categories` and `GET /hot-sale` (banners + hot products); `GET /units` can be cached and used to localise unit codes.
2. Listing/search: `GET /product-groups?categoryId=..&q=..&page=..` (one card per product with a variant picker). Use `GET /products` only if you need one row per variant.
3. Detail: `GET /product-groups/{id}` (id may be group id or any variant id). The id the cart/order must use is a **variant id** (`variants[].id`, equal to a `products` row id).
4. Reviews: `GET /product-groups/{id}/reviews` (all variants together). If signed in, `GET /products/{variantId}/reviews/mine` tells whether to show the "Write a review" button (`canReview`) and prefills `review`. Submit with `POST /products/{variantId}/reviews`.
5. Wishlist heart: `POST`/`DELETE /me/wishlist/{variantId}`; screen uses `GET /me/wishlist`.

### Flow E - Wallet / coins
- `GET /wallet` shows balance (`coins`), its rupee value (`valuePaise`), how coins are valued/limited (`paisePerCoin`, `maxRedeemPercent`) and the live earn rules (to display "Spend Rs 100, get 2 coins").
- `GET /wallet/ledger` is the history. Coins are earned (EARN) when an order is DELIVERED/PICKED_UP, spent (REDEEM) at checkout (the redeem choice is made in the cart quote/order endpoints, documented elsewhere), returned (REVERSE) when an order is cancelled, adjusted (ADJUST) by admin gifts/returns, and EXPIRE after `expiryMonths`.
- The live-events stream tells open screens that the balance changed; refetch `GET /wallet` on that event and after placing/cancelling orders.

### Flow F - Notifications
`GET /me/notifications` for the inbox (badge = `unread`), `POST /me/notifications/{id}/read` when a row is opened, `POST /me/notifications/read-all` for "mark all as read". Push messages (FCM) mirror the same text. `kind` + `ref` let the app deep-link (`ORDER` + order number, `STOCK` + product id for staff).

### Flow G - Account deletion
`DELETE /me`. Refused (409) while orders are open or credit is owed to the shop. On success all of the user's tokens are revoked: clear local state and go to login.

### Flow H - Admin catalog
Units first (`/admin/units`), categories (`/admin/categories`), then products (`POST /admin/products`), image upload, variants (`POST /admin/products/{id}/variants`), stock (`/admin/products/{id}/stock`), banners, coupons, coin rules/settings, customer support (`/admin/customers`).

---

## Shared shapes

**User** (login responses, `GET/PUT /me`):

| field | type | null? | meaning |
|---|---|---|---|
| id | uuid | no | user id |
| email | string | yes | null for customers who signed up by mobile only |
| name | string | yes | display name; null until set |
| mobile | string | yes | 10 digits, no country code |
| role | string | no | `CUSTOMER` / `CASHIER` / `ADMIN` |

**AuthResult**: `{ "accessToken": "<jwt>", "refreshToken": "<opaque string>", "expiresInSeconds": 900, "user": User }`. `expiresInSeconds` is a long (seconds until the access token expires).

**Address**: `{ "id": uuid, "label": string, "line1": string, "area": string, "pincode": string, "isDefault": boolean }`.

**Product** (customer view of one variant/pack; used by `/products`, `/products/{id}`, `/hot-sale`, `/me/wishlist`):

| field | type | null? | meaning |
|---|---|---|---|
| id | uuid | no | the id used by cart, orders, reviews, wishlist |
| categoryId | uuid | no | |
| nameEn / nameGu | string | no | English / Gujarati name |
| description | string | no | empty string when none |
| mrpPaise | integer (paise) | no | printed MRP |
| pricePaise | integer (paise) | no | selling price (<= mrp) |
| unit | string | no | unit code, see `/units` |
| packSize | string | no | e.g. "500 g"; empty string when none |
| imageUrl | string | yes | server-relative |
| inStock | boolean | no | stock > 0 |
| qtyAvailable | integer | no | stock capped at 99 (show "99+" if you like) |
| hotSale | boolean | no | true only when hot flag is on AND now is inside hotStart..hotEnd |
| earnsCoins | boolean | no | buying earns coins |
| coinRedeemable | boolean | no | coins can pay for it |
| productGroupId | uuid | no | shared by all variants of one product |
| rating | number | no | average review stars, 0 when none (2-decimals) |
| reviewCount | integer | no | |

**ProductGroup** (from `/product-groups`):

| field | type | null? | meaning |
|---|---|---|---|
| groupId | uuid | no | same as `productGroupId` |
| defaultVariantId | uuid | no | first in-stock variant in display order (else first variant) - preselect this |
| categoryId | uuid | no | |
| nameEn, nameGu, description | string | no | shared by variants |
| imageUrl | string | yes | default variant's image, else the first variant image that exists |
| hotSale | boolean | no | any variant currently hot |
| earnsCoins, coinRedeemable | boolean | no | |
| rating | number | no | review-count weighted average of variants, 2 decimals |
| reviewCount | integer | no | all variants together |
| fromPricePaise | integer (paise) | no | lowest variant price ("from Rs X") |
| inStock | boolean | no | any listed variant in stock |
| variants | array of Variant | no | in display order (variantOrder, then price) |

**Variant**: `{ id: uuid, variantName: string ("" when none), packSize: string ("" when none), unit: string, label: string (pack size, or the unit when no pack size - show next to price), mrpPaise: int paise, pricePaise: int paise, imageUrl: string|null, inStock: boolean, qtyAvailable: int (capped 99) }`.

**AdminProduct** (admin endpoints): all Product fields plus `qtyOnHand` (int, exact stock), `lowThreshold` (int, alert level), `active` (boolean), `hotStart`/`hotEnd` (instant|null). Here `hotSale` is the raw flag (not time-adjusted) and `inStock`/`qtyAvailable` mirror the Product meaning. Full field list: `id, categoryId, nameEn, nameGu, description, mrpPaise, pricePaise, unit, packSize, imageUrl, inStock, qtyAvailable, hotSale, earnsCoins, coinRedeemable, qtyOnHand, lowThreshold, active, hotStart, hotEnd, productGroupId, rating, reviewCount`.

**Review**: `{ id: uuid, rating: int 1..5, comment: string (may be ""), reviewer: string (first name only, "Customer" when the user has no name), createdAt: instant, verified: boolean (true when written for a delivered order) }`.

**Category**: `{ id: uuid, nameEn: string, nameGu: string, imageUrl: string|null, sortOrder: int }`.

**HotBanner**: `{ id: uuid, title: string, subtitle: string, imageUrl: string|null, startAt: instant|null, endAt: instant|null, active: boolean }`.

**UnitOfMeasure**: `{ code: string, nameEn: string, nameGu: string, soldBy: "UNIT"|"WEIGHT", sortOrder: int, active: boolean }`. `UNIT` = counted pieces; `WEIGHT` = priced per 1000 base units and weighed at the counter.

**Coupon** (admin view): `{ id: uuid, code: string (UPPERCASE), kind: "FLAT"|"PERCENT", value: long (paise for FLAT, percent 1..100 for PERCENT), minOrderPaise: long paise (0 = none), maxDiscountPaise: long paise (0 = no cap), categoryId: uuid|null (first of categoryIds), perUserLimit: int (0 = unlimited, 1 = once per customer), totalLimit: int (0 = unlimited), usedCount: int, startAt: instant|null, endAt: instant|null, active: boolean, worksWithCoins: boolean, firstOrderOnly: boolean, categoryIds: [uuid] (empty = all categories) }`.

---

# AUTH

OTP rate limits (429 `RATE_LIMITED`, `details.retryAfterSeconds`): per email 5 sends/hour, per mobile 5 sends/hour, per client IP 20 sends/hour (shared by email and mobile sends), plus the global 300/min/IP.

### POST /api/v1/auth/otp/send
- **Who**: public (`/api/v1/auth/**` is permitAll).
- **Purpose / when the UI calls it**: Login/sign-up screen, after the user enters an email and taps "Send code". Passwordless login: a 5-digit code is emailed (in dev setups it may be printed to the server log or be a fixed code).
- **Request**: no params/headers. Body:
```json
{ "email": "customer@example.com" }
```
| field | type | required | validation | meaning |
|---|---|---|---|---|
| email | string | yes | `@NotBlank` and `@Email` (well-formed email) else 400 `VALIDATION_FAILED` on `fields.email`. Server trims and lower-cases it. | address to send the code to |

- **Response**: `204 No Content`, empty body.
- **Errors**:

| status | code | when |
|---|---|---|
| 400 | VALIDATION_FAILED | missing/blank/malformed email |
| 429 | RATE_LIMITED | more than 5 codes/hour for this email, or 20/hour for this IP, or 300/min for the IP |
| 500/other | (mail failure) | if the mail server fails the call fails and no code is stored |

- **UI notes**: Show a resend timer; use `details.retryAfterSeconds` from 429. Code lifetime is 5 minutes. A new send invalidates nothing explicitly but the latest open code is the one checked. The same endpoint creates no account; the account appears at verify.

### POST /api/v1/auth/otp/verify
- **Who**: public.
- **Purpose / when the UI calls it**: OTP entry screen, after the user types the code. Completes login (and sign-up for a new email).
- **Request**: body:
```json
{ "email": "customer@example.com", "otp": "12345", "deviceId": "7c1e...-device-uuid" }
```
| field | type | required | validation | meaning |
|---|---|---|---|---|
| email | string | yes | `@NotBlank @Email`; trimmed + lower-cased | same email the code was sent to |
| otp | string | yes | `@NotBlank`; trimmed; compared to the 5-digit code | the code |
| deviceId | string | no | no validation | stable per-install id. Refresh tokens are grouped per device: reusing a revoked refresh token revokes all tokens of that deviceId. Send a stable UUID generated once per install. |

- **Response**: `200` AuthResult (see Shared shapes):
```json
{ "accessToken": "eyJ...", "refreshToken": "q3Zf...", "expiresInSeconds": 900,
  "user": { "id": "…", "email": "customer@example.com", "name": null, "mobile": null, "role": "CUSTOMER" } }
```
- **Errors**:

| status | code | when |
|---|---|---|
| 400 | VALIDATION_FAILED | blank/malformed email or blank otp |
| 422 | OTP_INVALID | `details.reason` is `EXPIRED` (no open code, or older than 5 min, or already used), `LOCKED` (5 wrong tries used up), or `WRONG` (code mismatch; the attempt is counted) |
| 403 | FORBIDDEN | the account is blocked by an admin (or deleted) |

- **UI notes**: Map `reason`: WRONG -> "Wrong code, try again" (max 5 tries); LOCKED/EXPIRED -> "Request a new code". After 5 wrong tries the user must request a new code. A deleted account is blocked and its email is renamed to a placeholder, so signing in again with the old email creates a brand-new empty account.

### POST /api/v1/auth/otp/send-mobile
- **Who**: public.
- **Purpose / when the UI calls it**: Login screen, "Continue with mobile". Sends a 5-digit SMS code.
- **Request**: body `{ "mobile": "98765 43210" }`.

| field | type | required | validation | meaning |
|---|---|---|---|---|
| mobile | string | yes | `@NotBlank`; then must normalise to a 10-digit Indian mobile starting 6-9 (accepts spaces, dashes, parentheses, `+91`, `0091`, `91`, leading `0` prefixes) else 400 `VALIDATION_FAILED` with `fields.mobile` = "must be a valid 10-digit mobile number" | phone to SMS |

- **Response**: `204 No Content`.
- **Errors**:

| status | code | when |
|---|---|---|
| 400 | VALIDATION_FAILED | blank or not a valid Indian mobile |
| 429 | RATE_LIMITED | 5 sends/hour per mobile, 20/hour per IP, 300/min per IP |
| 503 | SMS_NOT_CONFIGURED | the server has no SMS provider ("Mobile sign-in is not available right now. Please use email.") |

- **UI notes**: On 503 hide the mobile option and steer to email. Same 5-minute / 5-try rules as email.

### POST /api/v1/auth/otp/verify-mobile
- **Who**: public.
- **Purpose / when the UI calls it**: OTP entry after `send-mobile`. Logs in or creates the account for that proven mobile.
- **Request**: body `{ "mobile": "9876543210", "otp": "12345", "deviceId": "..." }`.

| field | type | required | validation | meaning |
|---|---|---|---|---|
| mobile | string | yes | `@NotBlank` + same normalisation/validation as above (400 `fields.mobile`) | |
| otp | string | yes | `@NotBlank` | code |
| deviceId | string | no | none | as in email verify |

- **Response**: `200` AuthResult. For a mobile-only customer `user.email` is `null`, `user.mobile` is the 10-digit number.
- **Errors**: 400 `VALIDATION_FAILED`; 422 `OTP_INVALID` (`details.reason` EXPIRED | LOCKED | WRONG); 403 `FORBIDDEN` (blocked account).
- **UI notes**: The number is now "verified" and cannot be changed through `PUT /me` (see that endpoint). Existing counter (POS) customers with this number are adopted automatically so their coins/history show up.

### POST /api/v1/auth/refresh
- **Who**: public (the refresh token is the credential).
- **Purpose / when the UI calls it**: HTTP client interceptor, when the access token expired (401) or is about to. Gives a new access token and rotates the refresh token.
- **Request**: body `{ "refreshToken": "q3Zf..." }`; `refreshToken` string, required, `@NotBlank`.
- **Response**: `200` AuthResult (new `accessToken` AND new `refreshToken`; the user object reflects the current role/profile).
- **Errors**:

| status | code | when |
|---|---|---|
| 400 | VALIDATION_FAILED | blank token |
| 401 | UNAUTHENTICATED | unknown token, expired (30 days), already used/revoked (this also revokes every token of that device), or user missing |
| 403 | FORBIDDEN | the account is blocked |

- **UI notes**: Replace the stored refresh token with the one returned, every time. Serialise concurrent refreshes (a second call with the old token kills the session). On 401 or 403 go to login.

### POST /api/v1/auth/logout
- **Who**: public.
- **Purpose / when the UI calls it**: Logout button. Revokes that refresh token.
- **Request**: body `{ "refreshToken": "..." }` (`@NotBlank`).
- **Response**: `204 No Content`. Unknown tokens are silently ignored (still 204).
- **Errors**: 400 `VALIDATION_FAILED` for a blank token.
- **UI notes**: The access token stays technically valid until it expires (<= 15 min); just discard it locally. No other call is needed (push tokens stay attached to the user until the same device token is registered by another user).

---

# PROFILE, ADDRESSES, DEVICES

### GET /api/v1/me
- **Who**: customer (any signed-in user; `anyRequest().authenticated()`).
- **Purpose / when the UI calls it**: Profile screen / app start after login to show name, email and mobile.
- **Request**: none.
- **Response**: `200` User.
```json
{ "id": "…", "email": "a@b.com", "name": "Asha Patel", "mobile": "9876543210", "role": "CUSTOMER" }
```
- **Errors**: 404 `NOT_FOUND` ("User not found") if the user row no longer exists.
- **UI notes**: `name` is null for new accounts: prompt the user to complete the profile.

### PUT /api/v1/me
- **Who**: customer.
- **Purpose / when the UI calls it**: Edit profile screen "Save".
- **Request**: body:
```json
{ "name": "Asha Patel", "mobile": "9876543210" }
```
| field | type | required | validation | meaning |
|---|---|---|---|---|
| name | string | no | trimmed. No length rule enforced by the API. **Omitting it (or null) keeps the current name**; to change it send the new name. | display name |
| mobile | string | no | blank/omitted = "no mobile". Otherwise must match exactly 10 digits `\d{10}` (no `+91`, no spaces) else 400 `VALIDATION_FAILED` `fields.mobile` = "must be 10 digits". | contact number (unverified unless proven by OTP login) |

Mobile rules: if the account's mobile is **verified** (came from mobile OTP login), sending a different number returns 409 `MOBILE_VERIFIED`; sending the same number is fine; sending blank/omitted keeps the verified mobile (accounts with no email also keep their mobile). Changing the mobile of an unverified account just stores the new number as unverified.
- **Response**: `200` User (updated).
- **Errors**:

| status | code | when |
|---|---|---|
| 400 | VALIDATION_FAILED | mobile not 10 digits |
| 409 | MOBILE_VERIFIED | account has a verified mobile and a different number was sent ("A verified mobile number cannot be changed here") |
| 404 | NOT_FOUND | user missing |

- **UI notes**: Make the mobile field read-only when the user logged in by mobile. Always include `name`.

### DELETE /api/v1/me
- **Who**: customer. Only `CUSTOMER` role; cashier/admin get 403.
- **Purpose / when the UI calls it**: "Delete my account" in settings (required by app stores / data protection law). Erases personal data (name, mobile, email replaced with a placeholder, addresses, devices, sign-in tokens); bills/orders are kept but anonymous. Coins are forfeited.
- **Request**: none (show a confirmation dialog first; warn that coins are lost and this is irreversible).
- **Response**: `204 No Content`.
- **Errors**:

| status | code | when |
|---|---|---|
| 409 | ACCOUNT_HAS_OPEN_ORDERS | `details.openOrders` (number) orders are not yet DELIVERED/PICKED_UP/CANCELLED |
| 409 | ACCOUNT_HAS_DUES | customer owes the shop credit (udhar); `details.owedPaise` (integer paise) |
| 403 | FORBIDDEN | role is not CUSTOMER |
| 404 | NOT_FOUND | user missing |

- **UI notes**: On 409 show the reason (open orders count / amount owed formatted from paise) and do not log out. On success clear all local data (tokens, cache) and return to the login screen.

### GET /api/v1/me/addresses
- **Who**: customer.
- **Purpose / when the UI calls it**: Address book and checkout address picker.
- **Request**: none.
- **Response**: `200` array of Address, default address first then oldest first. Not paged.
```json
[ { "id": "…", "label": "Home", "line1": "12 Rose Apt", "area": "Satellite", "pincode": "380015", "isDefault": true } ]
```
- **Errors**: none specific.
- **UI notes**: At most one address is default.

### POST /api/v1/me/addresses
- **Who**: customer.
- **Purpose / when the UI calls it**: "Add address" form.
- **Request**: body:
```json
{ "label": "Home", "line1": "12 Rose Apt, Ring Road", "area": "Satellite", "pincode": "380015", "isDefault": false }
```
| field | type | required | validation | meaning |
|---|---|---|---|---|
| label | string | yes | `@NotBlank` | "Home"/"Work" tag |
| line1 | string | yes | `@NotBlank` | street/house |
| area | string | yes | `@NotBlank` | locality |
| pincode | string | yes | `@NotBlank` only (no format or length check in the API; validate 6 digits in the UI) | postal code |
| isDefault | boolean | no | null = false | make default |

The very first address is forced to default regardless of `isDefault`. Setting a new default clears the previous one.
- **Response**: `201 Created` Address (with new `id`).
- **Errors**: 400 `VALIDATION_FAILED` (`fields.label|line1|area|pincode`: "must not be blank"). No limit on the number of addresses is enforced.
- **UI notes**: No delivery-area/pincode serviceability check happens here.

### PUT /api/v1/me/addresses/{id}
- **Who**: customer (own addresses only).
- **Purpose / when the UI calls it**: Edit address form "Save".
- **Request**: path `id` uuid. Body is identical to POST (same validation).
  **Important**: omitting `isDefault` (or null) keeps the address as it is (default stays default, non-default stays non-default). Send `true` to make it the default (this clears the default on all others); sending `false` on the current default is ignored (a customer who has addresses always has exactly one default; to change it, mark another address as default).
- **Response**: `200` Address (updated).
- **Errors**: 400 `VALIDATION_FAILED`; 404 `NOT_FOUND` ("Address not found") if it does not exist or belongs to someone else.
- **UI notes**: Re-fetch the list after saving if default changed.

### DELETE /api/v1/me/addresses/{id}
- **Who**: customer (own).
- **Purpose / when the UI calls it**: Swipe/delete on an address.
- **Request**: path `id` uuid.
- **Response**: `204 No Content`. Deleting an id that does not exist (or is not yours) also returns 204 (no error).
- **Errors**: none specific (400 for a malformed uuid).
- **UI notes**: If the deleted address was the default and other addresses remain, the first one in the list becomes the default automatically (refetch the list to show it). Orders already placed keep their own copy of the address (handled elsewhere).

### POST /api/v1/me/devices
- **Who**: customer (also works for any signed-in role, e.g. admin app to get new-order pushes).
- **Purpose / when the UI calls it**: After login and whenever FCM gives a new token. Registers the device for push notifications.
- **Request**: body `{ "fcmToken": "<firebase token>", "platform": "android" }`.

| field | type | required | validation | meaning |
|---|---|---|---|---|
| fcmToken | string | yes | `@NotBlank` | Firebase Cloud Messaging token |
| platform | string | yes | `@NotBlank`; free text, not an enum (use `android` / `ios` / `web`) | |

Idempotent: the token is unique; registering it again (even from another account) re-assigns it to the current user.
- **Response**: `204 No Content`.
- **Errors**: 400 `VALIDATION_FAILED`.
- **UI notes**: Call on every login (so a shared device's token moves to the new user). Tokens Firebase reports as invalid are removed by the server.

---

# NOTIFICATIONS

Notification `kind` values produced by the server: `ORDER` (customer and staff; `ref` = order number such as `VSM-1001`) and `STOCK` (staff only; `ref` = a product id, or null for the daily digest). Do not assume the list is closed.

### GET /api/v1/me/notifications
- **Who**: customer (any signed-in user; staff get their own stock/new-order alerts here).
- **Purpose / when the UI calls it**: Notification inbox screen and badge count.
- **Request**: query `page` (default 0), `size` (default 20, max 50). Newest first.
- **Response**: `200`:
```json
{ "items": [ { "id": "…", "title": "Order placed", "body": "Your order VSM-1001 has been placed.",
               "kind": "ORDER", "ref": "VSM-1001", "read": false, "createdAt": "2026-11-08T10:15:30Z" } ],
  "page": 0, "size": 20, "total": 37, "unread": 5 }
```
| field | type | null? | meaning |
|---|---|---|---|
| items[].id | uuid | no | |
| items[].title / body | string | no | ready-to-display text (English) |
| items[].kind | string | no | see above |
| items[].ref | string | yes | order number or product id for deep link |
| items[].read | boolean | no | |
| items[].createdAt | instant | no | |
| unread | integer | no | unread count across ALL pages |

- **Errors**: none specific.
- **UI notes**: Use `unread` for the badge. Money inside `body` is pre-formatted text ("Rs 120.50").

### POST /api/v1/me/notifications/{id}/read
- **Who**: customer (own notifications).
- **Purpose / when the UI calls it**: When the user opens a notification.
- **Request**: path `id` uuid.
- **Response**: `204 No Content`. Safe to repeat.
- **Errors**: 404 `NOT_FOUND` ("Notification not found") when it does not exist or is someone else's.
- **UI notes**: Optimistically decrement the badge.

### POST /api/v1/me/notifications/read-all
- **Who**: customer.
- **Purpose / when the UI calls it**: "Mark all as read".
- **Request**: no body.
- **Response**: `204 No Content`.
- **Errors**: none.
- **UI notes**: Set badge to 0.

---

# WISHLIST

### GET /api/v1/me/wishlist
- **Who**: customer.
- **Purpose / when the UI calls it**: Wishlist screen.
- **Request**: none. Not paged (maximum 200 items).
- **Response**: `200` array of **Product** (see Shared shapes), newest-saved first. Products that were deactivated or are counter-only since saving are silently left out.
- **Errors**: none specific.
- **UI notes**: Each entry is a single variant (use `productGroupId` to open the group screen). Out-of-stock products are still returned (`inStock:false`).

### POST /api/v1/me/wishlist/{productId}
- **Who**: customer.
- **Purpose / when the UI calls it**: Tapping the heart on a product.
- **Request**: path `productId` uuid (a variant/product id).
- **Response**: `204 No Content`. Adding an already saved product is fine (no error).
- **Errors**:

| status | code | when |
|---|---|---|
| 404 | NOT_FOUND | product does not exist, is inactive or counter-only ("Product not found") |
| 400 | VALIDATION_FAILED | already 200 items and this one is new: `fields.productId` = "wishlist is full (200 items)" |

- **UI notes**: Optimistic toggle; roll back on error.

### DELETE /api/v1/me/wishlist/{productId}
- **Who**: customer.
- **Purpose / when the UI calls it**: Un-heart.
- **Request**: path `productId` uuid.
- **Response**: `204 No Content` (also when it was not saved).
- **Errors**: none.
- **UI notes**: Repeatable.

---

# WALLET (coins)

Concepts: 1 coin = `paisePerCoin` paise. Coins can pay at most `maxRedeemPercent`% of the redeemable part of an order. Earned coins expire after `expiryMonths`; the soonest-expiring coins are spent first. Earn rules: on a base amount, the single best rule applies (rules never add up). `PRO_RATA`: `floor(base * coins / spendPaise)`; `SLAB`: `floor(base / spendPaise) * coins`; orders below `minOrderPaise` earn 0. A rule is "live" if `active` and now is within `startAt..endAt` (null bounds = open).

### GET /api/v1/wallet
- **Who**: customer.
- **Purpose / when the UI calls it**: Wallet/coins screen, cart (to display redeem options), home badge.
- **Request**: none.
- **Response**: `200`:
```json
{ "coins": 120, "valuePaise": 12000, "paisePerCoin": 100, "maxRedeemPercent": 20,
  "rules": [ { "name": "Festival", "spendPaise": 10000, "coins": 2, "mode": "PRO_RATA" } ] }
```
| field | type | meaning |
|---|---|---|
| coins | integer | current balance in coins |
| valuePaise | integer (paise) | `coins * paisePerCoin` |
| paisePerCoin | integer (paise) | value of 1 coin |
| maxRedeemPercent | integer 0..100 | max share of the redeemable amount payable with coins |
| rules[] | array | only currently live earn rules: `name` string, `spendPaise` integer paise ("per this much spend"), `coins` integer (coins given), `mode` `"PRO_RATA"` or `"SLAB"` |

- **Errors**: none specific.
- **UI notes**: Settings and rules are cached server side briefly; changes by admin apply almost immediately. Refetch on the live "wallet changed" event. Coins are only earned on delivery (or pickup) of an order.

### GET /api/v1/wallet/ledger
- **Who**: customer.
- **Purpose / when the UI calls it**: "Coin history" list.
- **Request**: query `page`, `size` (see Paging; default 20, max 50). Always **newest first** (sorted by `createdAt` descending by the server; part of the contract). A row with `coins: 0` can appear (a refund lot) and can be hidden.
- **Response**: `200` paged:
```json
{ "items": [ { "id": "…", "kind": "EARN", "coins": 12, "orderNo": "VSM-1001",
               "createdAt": "2026-11-08T10:15:30Z", "expiresAt": "2027-05-08T10:15:30Z" } ],
  "page": 0, "size": 20, "total": 3 }
```
| field | type | null? | meaning |
|---|---|---|---|
| id | uuid | no | |
| kind | string | no | `EARN` (credited on delivery), `REDEEM` (spent, negative coins), `REVERSE` (spent coins returned after cancellation, positive), `ADJUST` (admin gift/deduction, return restore/clawback; positive or negative), `EXPIRE` (expired, negative) |
| coins | integer | no | signed change (+ earned, - spent) |
| orderNo | string | yes | human number (e.g. `VSM-1001`, may also be a counter bill or return number); null for admin adjustments and expiries |
| createdAt | instant | no | |
| expiresAt | instant | yes | only on rows that created a lot of coins (EARN, positive ADJUST) |

- **Errors**: none specific.
- **UI notes**: A refund of coins into a new lot can appear as an `ADJUST` row with `coins: 0` - hide rows with `coins == 0`. Show EARN rows' `expiresAt` as "expires on".

### GET /api/v1/admin/coin-rules
- **Who**: admin (`/api/v1/admin/**` ADMIN only).
- **Purpose / when the UI calls it**: Admin "Coin rules" list. Always shows stored truth, including inactive and expired rules.
- **Request**: none.
- **Response**: `200` array:
```json
[ { "id": "…", "name": "Festival", "spendPaise": 10000, "coins": 2, "mode": "PRO_RATA",
    "minOrderPaise": 0, "startAt": null, "endAt": null, "active": true } ]
```
Fields: `id` uuid; `name` string; `spendPaise` integer paise (spend step); `coins` integer; `mode` `PRO_RATA|SLAB`; `minOrderPaise` integer paise; `startAt`/`endAt` instant|null; `active` boolean.
- **Errors**: 403 for non-admin.
- **UI notes**: Not paged.

### POST /api/v1/admin/coin-rules
- **Who**: admin.
- **Purpose / when the UI calls it**: "Add rule" form.
- **Request**: body:
```json
{ "name": "Festival", "spendPaise": 10000, "coins": 2, "mode": "PRO_RATA", "minOrderPaise": 0,
  "startAt": "2026-11-01T00:00:00Z", "endAt": "2026-11-30T23:59:59Z", "active": true }
```
| field | type | required | validation | meaning |
|---|---|---|---|---|
| name | string | yes | `@NotBlank` | label |
| spendPaise | long (paise) | yes | `@Min(1)` (omitted = 0 = fails) | spend step |
| coins | long | yes | `@Min(1)` | coins per step / per spend |
| mode | enum | yes | `@NotNull`; exactly `PRO_RATA` or `SLAB` (else 400 "Bad request") | |
| minOrderPaise | long (paise) | no | null -> 0. `@Min(0)`: a negative number answers 400 `VALIDATION_FAILED` `fields.minOrderPaise`. | minimum order base to earn |
| startAt / endAt | instant | no | null = open. Order (end after start) is NOT validated. | active window |
| active | boolean | no | null -> true | |

- **Response**: `201 Created` rule (same shape as list item).
- **Errors**: 400 `VALIDATION_FAILED` (fields as above).
- **UI notes**: Validate `minOrderPaise >= 0` and `endAt >= startAt` in the UI.

### PUT /api/v1/admin/coin-rules/{id}
- **Who**: admin.
- **Purpose / when the UI calls it**: Edit rule.
- **Request**: path `id`; body same as POST (full replacement; omitted `active` becomes true, omitted `minOrderPaise` becomes 0, omitted dates become null).
- **Response**: `200` rule.
- **Errors**: 400 `VALIDATION_FAILED`; 404 `NOT_FOUND` ("Coin rule not found").
- **UI notes**: Send all fields every time.

### DELETE /api/v1/admin/coin-rules/{id}
- **Who**: admin.
- **Purpose / when the UI calls it**: Delete rule.
- **Request**: path `id`.
- **Response**: `204 No Content` (also for an unknown id).
- **Errors**: none specific.
- **UI notes**: To pause a rule prefer PUT with `active:false`.

### GET /api/v1/admin/coin-settings
- **Who**: admin.
- **Purpose / when the UI calls it**: Admin coin settings screen.
- **Request**: none.
- **Response**: `200` `{ "paisePerCoin": 100, "maxRedeemPercent": 20, "expiryMonths": 6 }` (`paisePerCoin` integer paise, `maxRedeemPercent` integer 0..100, `expiryMonths` integer months).
- **Errors**: 403 non-admin.
- **UI notes**: —

### PUT /api/v1/admin/coin-settings
- **Who**: admin.
- **Purpose / when the UI calls it**: Save settings.
- **Request**: body `{ "paisePerCoin": 100, "maxRedeemPercent": 20, "expiryMonths": 6 }`.

| field | type | required | validation | meaning |
|---|---|---|---|---|
| paisePerCoin | long (paise) | yes | > 0 else 400 `fields.paisePerCoin` "must be positive" | value of 1 coin |
| maxRedeemPercent | int | yes | 0..100 else 400 `fields.maxRedeemPercent` "0 to 100" | |
| expiryMonths | int | yes | > 0 else 400 `fields.expiryMonths` "must be positive" | applies to coins earned/gifted after the change |

Omitted numeric fields become 0 and fail validation (except `maxRedeemPercent`, where 0 is valid) - always send all three.
- **Response**: `200` saved settings (same shape).
- **Errors**: 400 `VALIDATION_FAILED`.
- **UI notes**: Changing `paisePerCoin` changes the value of existing balances immediately (value = coins x paisePerCoin).

---

# CATALOG BROWSING (public)

Catalog GETs are public (guests can browse). Counter-only ("POS only") and inactive products are never returned by customer endpoints. There is no ETag/Cache-Control on these endpoints (only `/shop-settings`, documented elsewhere, has an ETag); use the live-events catalog-changed signal to refresh.

### GET /api/v1/categories
- **Who**: public.
- **Purpose / when the UI calls it**: Home category chips/grid and filter lists.
- **Request**: none.
- **Response**: `200` array of Category, only active ones, ordered by `sortOrder` then `nameEn`. Not paged.
```json
[ { "id": "…", "nameEn": "Dairy", "nameGu": "ડેરી", "imageUrl": null, "sortOrder": 1 } ]
```
- **Errors**: none.
- **UI notes**: Server caches this list; refresh on the live "catalog" event. `imageUrl` may be null: show a placeholder (the admin API has no way to set a category image).

### GET /api/v1/products
- **Who**: public.
- **Purpose / when the UI calls it**: Flat product search/list - one row per variant (pack size). Prefer `/product-groups` for customer listing screens.
- **Request** (query):

| param | type | default | rules |
|---|---|---|---|
| categoryId | uuid | none | filter by category |
| q | string | none | case-insensitive substring match on English OR Gujarati name; blank ignored |
| hot | boolean | false | true = only products whose hot-sale flag is on and inside their hot window now |
| includeOutOfStock | boolean | false | false = only products with stock > 0 |
| page, size | int | 0, 20 | max size 50 |

Sorted by `nameEn` ascending.
- **Response**: `200` paged Product.
- **Errors**: 400 `VALIDATION_FAILED` ("Bad request") for an invalid uuid/boolean/number.
- **UI notes**: Debounce search input (every call counts toward the 300/min IP limit).

### GET /api/v1/products/{id}
- **Who**: public.
- **Purpose / when the UI calls it**: Product detail by variant id (e.g. deep link from a wishlist/order line).
- **Request**: path `id` uuid.
- **Response**: `200` Product.
- **Errors**: 404 `NOT_FOUND` ("Product not found") if missing, inactive or counter-only.
- **UI notes**: Out-of-stock products are returned with `inStock:false` (not 404).

### GET /api/v1/product-groups
- **Who**: public.
- **Purpose / when the UI calls it**: The main shop grid/search: one card per product with a variant (size/flavour) picker, one rating, price "from".
- **Request** (query): same as `/products` (`categoryId`, `q`, `hot`, `includeOutOfStock`, `page`, `size`). A product group matches when ANY visible variant matches; when `includeOutOfStock=false` (default) out-of-stock variants are removed from each group and groups with none left are dropped. Sorted by the group's lowest English name. `total` counts groups.
- **Response**: `200` paged ProductGroup.
```json
{ "items": [ { "groupId": "…", "defaultVariantId": "…", "categoryId": "…", "nameEn": "Mango Pickle", "nameGu": "…",
               "description": "", "imageUrl": "/img/products/x_400.jpg", "hotSale": false, "earnsCoins": true,
               "coinRedeemable": true, "rating": 4.5, "reviewCount": 12, "fromPricePaise": 12000, "inStock": true,
               "variants": [ { "id": "…", "variantName": "Mango", "packSize": "500 g", "unit": "g", "label": "500 g",
                               "mrpPaise": 15000, "pricePaise": 12000, "imageUrl": "/img/products/x_400.jpg",
                               "inStock": true, "qtyAvailable": 40 } ] } ],
  "page": 0, "size": 20, "total": 1 }
```
- **Errors**: 400 for bad query values.
- **UI notes**: Add-to-cart uses `variants[].id` (not `groupId`). A page may contain fewer than `size` items after out-of-stock filtering; keep paging until `items` is empty or `(page+1)*size >= total`.

### GET /api/v1/product-groups/{id}
- **Who**: public.
- **Purpose / when the UI calls it**: Product detail screen with variant picker.
- **Request**: path `id` uuid: group id OR any variant's id.
- **Response**: `200` ProductGroup. Includes out-of-stock variants (with `inStock:false`); only active, non-counter-only variants.
- **Errors**: 404 `NOT_FOUND` ("Product not found") when there are no visible variants.
- **UI notes**: Preselect `defaultVariantId`. Disable add-to-cart for out-of-stock variants. `qtyAvailable` is capped at 99.

### GET /api/v1/hot-sale
- **Who**: public.
- **Purpose / when the UI calls it**: Home "Hot deals" carousel + banners.
- **Request**: none.
- **Response**: `200`:
```json
{ "banners": [ HotBanner ], "products": [ Product ], "page": 0, "size": 20, "total": 37, "totalExact": true, "hasMore": true }
```
`banners`: active banners whose `startAt..endAt` window contains now. `products`: one page (default 20) of in-stock hot products; paging is described in the "changed: paged products" addendum below.
- **Errors**: none.
- **UI notes**: Banners have no link target field; they are display only.

### GET /api/v1/units
- **Who**: public.
- **Purpose / when the UI calls it**: Cache once to render unit names (English/Gujarati) for `product.unit` codes and in admin product forms.
- **Request**: none.
- **Response**: `200` array of UnitOfMeasure with `active: true` only, ordered by `sortOrder`, then `code`.
```json
[ { "code": "kg", "nameEn": "Kilogram", "nameGu": "કિલો", "soldBy": "WEIGHT", "sortOrder": 3, "active": true } ]
```
- **Errors**: none.
- **UI notes**: `Product.unit` stores the `code`. Refresh on the live "units" change.

### GET /api/v1/coupons
- **Who**: public (listed in SecurityConfig permitAll for GET).
- **Purpose / when the UI calls it**: "Offers" screen before/after sign-in. Applying a code still happens through the cart quote endpoint (documented elsewhere), which decides per customer.
- **Request**: none.
- **Response**: `200` array of PublicCoupon: only coupons that are active, started, not ended and not used up, ordered by code.
```json
[ { "code": "DAIRY10", "kind": "PERCENT", "value": 10, "minOrderPaise": 20000, "maxDiscountPaise": 5000,
    "categoryId": null, "startAt": null, "endAt": null, "worksWithCoins": true, "firstOrderOnly": false,
    "perUserLimit": 1, "categoryIds": [] } ]
```
`value`: paise for `FLAT`, percent for `PERCENT`; `minOrderPaise`, `maxDiscountPaise`: paise, 0 = none/no cap; `perUserLimit` 0 = unlimited; `categoryIds` empty = all categories; `categoryId` = first of them or null. Internal id, total limit and used count are not exposed.
- **Errors**: none.
- **UI notes**: Show "valid on <category names>", "first order only", "cannot combine with coins" (`worksWithCoins:false`). Presence in this list does not guarantee the signed-in customer can use it.

---

# REVIEWS

### GET /api/v1/products/{productId}/reviews
- **Who**: public (`GET /products/*/reviews`).
- **Purpose / when the UI calls it**: Reviews list for one variant.
- **Request**: path `productId` uuid; query `page`, `size`. Newest first.
- **Response**: `200`:
```json
{ "items": [ { "id": "…", "rating": 5, "comment": "Fresh", "reviewer": "Asha", "createdAt": "2026-11-08T10:15:30Z", "verified": true } ],
  "page": 0, "size": 20, "total": 1, "rating": 5.0, "reviewCount": 1 }
```
`rating` = average stars of this product (number), `reviewCount` = integer; `total` = number of review rows (same as reviewCount).
- **Errors**: 404 `NOT_FOUND` ("Product not found") for an invisible product.
- **UI notes**: `reviewer` is only a first name.

### GET /api/v1/product-groups/{id}/reviews
- **Who**: public.
- **Purpose / when the UI calls it**: Reviews tab on the product detail (all variants together).
- **Request**: path `id` uuid (group id or any variant id); query `page`, `size`. Newest first.
- **Response**: `200` same shape as above; `rating` is rounded to 2 decimals.
- **Errors**: 404 `NOT_FOUND` ("Product not found").
- **UI notes**: Each review belongs to a specific variant but the response does not say which.

### GET /api/v1/products/{productId}/reviews/mine
- **Who**: customer (needs sign-in: it is not in the public GET list).
- **Purpose / when the UI calls it**: Decide whether to show "Write a review" and prefill the user's existing review.
- **Request**: path `productId` uuid (variant id).
- **Response**: `200`:
```json
{ "canReview": true, "review": { "id": "…", "rating": 4, "comment": "Good", "reviewer": "Asha", "createdAt": "…", "verified": true } }
```
`canReview` boolean: true iff the customer has a DELIVERED or PICKED_UP order containing this exact variant. `review`: the customer's existing review or `null`.
- **Errors**: 404 `NOT_FOUND` ("Product not found").
- **UI notes**: Show the write button only when `canReview`. Show the existing review for editing if present.

### POST /api/v1/products/{productId}/reviews
- **Who**: customer. Only customers who have had the product delivered/picked up may review.
- **Purpose / when the UI calls it**: Submit or edit a review (one review per customer per product; posting again replaces it).
- **Request**: path `productId` uuid (variant id). Body:
```json
{ "rating": 5, "comment": "Fresh and tasty" }
```
| field | type | required | validation | meaning |
|---|---|---|---|---|
| rating | integer | yes | `@Min(1) @Max(5)`; missing = 0 = fails with 400 `fields.rating` | stars |
| comment | string | no | `@Size(max=1000)` (400 `fields.comment`); trimmed; null/blank stored as "" | free text |

- **Response**: `200` Review (the saved one). The product's average rating/count are recomputed.
- **Errors**:

| status | code | when |
|---|---|---|
| 400 | VALIDATION_FAILED | rating outside 1..5, comment longer than 1000 characters |
| 404 | NOT_FOUND | product invisible/unknown |
| 422 | REVIEW_NOT_ALLOWED | no delivered/picked-up order with this variant ("You can review a product after it has been delivered to you") |

- **UI notes**: Same endpoint for create and edit. Use the variant id from the order line. Refresh product/group `rating` after success.

---

# UNITS (admin)

Unit `code` is the identifier (max 20 chars, matched case-insensitively; the server stores the admin's spelling). Products store the code in `unit`.

### GET /api/v1/admin/units
- **Who**: admin.
- **Purpose / when the UI calls it**: Admin units management list.
- **Request**: none.
- **Response**: `200` array of UnitOfMeasure including `active:false`, ordered by `sortOrder`, `code`.
- **Errors**: 403 non-admin.
- **UI notes**: —

### POST /api/v1/admin/units
- **Who**: admin.
- **Purpose / when the UI calls it**: Add unit (e.g. "dozen").
- **Request**: body:
```json
{ "code": "dz", "nameEn": "Dozen", "nameGu": "ડઝન", "soldBy": "UNIT", "sortOrder": 5, "active": true }
```
| field | type | required | validation | meaning |
|---|---|---|---|---|
| code | string | yes | `@Size(max=20)`; stripped; empty -> 400 `fields.code` "required"; >20 -> 400 "at most 20 characters"; duplicate (case-insensitive) -> 409 `UNIT_EXISTS` | identifier stored on products |
| nameEn | string | yes | `@NotBlank` (stripped) | English name |
| nameGu | string | no | null -> "" | Gujarati name |
| soldBy | string | no | blank/null -> `UNIT`; else (case-insensitive) must be `UNIT` or `WEIGHT`, else 400 `fields.soldBy` "UNIT or WEIGHT" | `UNIT` counted, `WEIGHT` priced per 1000 base units |
| sortOrder | integer | no | null -> 0 | list order |
| active | boolean | no | null -> true | |

- **Response**: `201 Created` UnitOfMeasure.
- **Errors**: 400 `VALIDATION_FAILED`; 409 `UNIT_EXISTS`.
- **UI notes**: Triggers a live "units changed" event for all open apps.

### PUT /api/v1/admin/units/{code}
- **Who**: admin.
- **Purpose / when the UI calls it**: Edit/rename/switch off a unit.
- **Request**: path `code`; body same as POST. A blank `code` in the body keeps the current code; a different code renames the unit and moves every product using it to the new code.
- **Response**: `200` UnitOfMeasure.
- **Errors**:

| status | code | when |
|---|---|---|
| 404 | NOT_FOUND | unit not found ("Unit not found") |
| 400 | VALIDATION_FAILED | nameEn blank; soldBy invalid; new code >20 chars; trying to switch off the last active unit while products use it (`fields.active` "keep at least one active unit") |
| 409 | UNIT_EXISTS | new code collides with another unit |

- **UI notes**: After a rename the products' `unit` values change (a catalog-changed event is sent).

### DELETE /api/v1/admin/units/{code}
- **Who**: admin.
- **Purpose / when the UI calls it**: Delete an unused unit.
- **Request**: path `code`.
- **Response**: `204 No Content`.
- **Errors**: 404 `NOT_FOUND`; 409 `UNIT_IN_USE` with `details.products` (count) when products use it - offer "switch off" (PUT `active:false`) instead.
- **UI notes**: —

---

# ADMIN CATALOG

All endpoints below: **Who = admin** only. Changes trigger a live catalog-changed event to open apps.

### GET /api/v1/admin/products
- **Who**: admin.
- **Purpose / when the UI calls it**: Admin product table with search and category filter; includes inactive and counter-only products and exact stock.
- **Request**: query `q` (string; substring match on English or Gujarati name), `categoryId` (uuid), `page`, `size` (default 20, max 50). Sorted by `nameEn`.
- **Response**: `200` paged AdminProduct (one row per variant).
- **Errors**: 403 non-admin; 400 bad uuid.
- **UI notes**: Use `/admin/products/{id}/variants` for a product's variants.

### POST /api/v1/admin/products
- **Who**: admin.
- **Purpose / when the UI calls it**: "Add product" form (creates the first variant; it becomes its own group).
- **Request**: body:
```json
{ "categoryId": "…", "nameEn": "Mango Pickle", "nameGu": "કેરીનું અથાણું", "description": "Homemade",
  "mrpPaise": 15000, "pricePaise": 12000, "unit": "g", "packSize": "500 g", "active": true,
  "hotSale": false, "hotStart": null, "hotEnd": null, "earnsCoins": true, "coinRedeemable": true,
  "qtyOnHand": 40, "lowThreshold": 5, "productGroupId": null, "variantName": "Mango", "variantOrder": 0 }
```
| field | type | required | validation | meaning |
|---|---|---|---|---|
| categoryId | uuid | yes | `@NotNull`; must exist else 400 `VALIDATION_FAILED` "Something you referred to does not exist" | category |
| nameEn | string | yes | `@NotBlank` | |
| nameGu | string | yes | `@NotBlank` | |
| description | string | no | null -> "" | |
| mrpPaise | long (paise) | no | `@Min(0)`; **must be >= pricePaise** else 400 `fields.mrpPaise` "MRP must not be below the price" (omitted = 0) | MRP |
| pricePaise | long (paise) | yes in practice | `@Min(0)`; omitted = 0 | selling price |
| unit | string | no | blank -> `pc`; must match an ACTIVE unit code (case-insensitive, canonicalised) else 400 `fields.unit` "unknown unit 'x'; add it under units first" | |
| packSize | string | no | null -> "" | label e.g. "500 g" |
| active | boolean | no | null -> true | visible to customers |
| hotSale | boolean | no | null -> false | hot flag |
| hotStart / hotEnd | instant | no | if both set, `hotEnd` must not be before `hotStart` else 400 `fields.hotEnd` "must be after hotStart" | hot window |
| earnsCoins | boolean | no | null -> true | |
| coinRedeemable | boolean | no | null -> true | |
| qtyOnHand | integer | no | `@Min(0)` (negative = 400); null -> 0 on create | initial stock (on edit: omitted keeps current stock) |
| lowThreshold | integer | no | `@Min(0)`; null -> default 5 on create; `0` is allowed (alert only when out of stock) | low-stock alert level (on edit: omitted keeps current) |
| productGroupId | uuid | no | puts this product into an existing group (as a variant) | |
| variantName | string | no | if either `variantName` or `variantOrder` is sent, they are applied after creation | variant label e.g. "Mango" |
| variantOrder | integer | no | | position in the variant picker |

- **Response**: `201 Created` AdminProduct.
- **Errors**: 400 `VALIDATION_FAILED` (above); 403.
- **UI notes**: Upload the image afterwards with `POST /admin/products/{id}/image`. Prefer `POST .../variants` to add more sizes of an existing product.

### GET /api/v1/admin/products/{id}
- **Who**: admin.
- **Purpose / when the UI calls it**: Edit-product form load.
- **Request**: path `id`.
- **Response**: `200` AdminProduct.
- **Errors**: 404 `NOT_FOUND` ("Product not found").
- **UI notes**: —

### PUT /api/v1/admin/products/{id}
- **Who**: admin.
- **Purpose / when the UI calls it**: Edit-product "Save" (single variant).
- **Request**: body identical to POST (same validation). **PUT means replace: send the whole form every time; use `PATCH` (below) to change only some fields.** What an omitted key does, per field:
  - **Cleared**: `hotStart` and `hotEnd` (both become null, so a hot-sale window disappears), `description` and `packSize` (stored as empty text `""`).
  - **Reset to a default**: `active` true, `hotSale` false, `earnsCoins` true, `coinRedeemable` true.
  - **Kept**: `qtyOnHand` (stock), `lowThreshold` (alert level), `unit` (a blank unit keeps the current one), `productGroupId` (a given id moves the product into that group; omitted leaves it where it is), `variantName` and `variantOrder` (changed only when sent).
  - `categoryId`, `nameEn`, `nameGu`, `mrpPaise`, `pricePaise` are required.
  Also: `hotStart`/`hotEnd` accept the date forms listed under "Dates and times" in file 00. `qtyOnHand` is the **new absolute stock** when sent: the server records the difference as an `ADJUST` stock movement. **Omitted (null) keeps the current stock** (it no longer resets to 0); a negative value is 400 `fields.qtyOnHand`. `lowThreshold` changes only if sent (`0` is a valid level). An inactive current unit is allowed to stay.
- **Response**: `200` AdminProduct.
- **Errors**: 400 `VALIDATION_FAILED`; 404 `NOT_FOUND`.
- **UI notes**: For quick stock changes use the stock endpoint instead, to avoid overwriting concurrent changes (e.g. a sale while the form was open).

### GET /api/v1/admin/products/{id}/variants
- **Who**: admin.
- **Purpose / when the UI calls it**: Variants table for a product.
- **Request**: path `id` (group id or any variant id).
- **Response**: `200` array of AdminProduct for every variant including inactive, in display order. Not paged.
- **Errors**: 404 `NOT_FOUND`.
- **UI notes**: —

### POST /api/v1/admin/products/{id}/variants
- **Who**: admin.
- **Purpose / when the UI calls it**: "Add size/flavour" on a product. Name, description, category and flags are copied from the product `id`.
- **Request**: path `id` (a variant id of the product; the variant's group is used). Body:
```json
{ "variantName": "Spicy", "packSize": "1 kg", "unit": "g", "mrpPaise": 28000, "pricePaise": 25000,
  "active": true, "qtyOnHand": 10, "lowThreshold": 5, "variantOrder": 1 }
```
| field | type | required | validation | meaning |
|---|---|---|---|---|
| variantName | string | one of variantName/packSize | trimmed. If both variantName and packSize are empty -> 400 `fields.packSize` "give the variant a name or a pack size" | |
| packSize | string | see above | trimmed. A group may not already contain a variant with the same (variantName, packSize) pair (case-insensitive) -> 400 `fields.packSize` "this product already has that variant" | |
| unit | string | no | blank -> base product's unit; else must be an active unit (400 `fields.unit`) | |
| mrpPaise | long (paise) | yes | `@Min(0)`; >= pricePaise else 400 `fields.mrpPaise` | |
| pricePaise | long (paise) | yes | `@Min(0)` | |
| active | boolean | no | null -> true | |
| qtyOnHand | integer | no | null -> 0 | |
| lowThreshold | integer | no | `@Min(0)`? not enforced; null (or negative) -> 5; `0` is allowed | |
| variantOrder | integer | no | null -> last position + 1 | |

- **Response**: `201 Created` AdminProduct (the new variant; its `productGroupId` equals the group).
- **Errors**: 400 `VALIDATION_FAILED`; 404 `NOT_FOUND` (product).
- **UI notes**: —

### PUT /api/v1/admin/product-groups/{id}
- **Who**: admin.
- **Purpose / when the UI calls it**: Edit the details every variant shares (name, description, category, hot sale, coin flags, active) in one save.
- **Request**: path `id` (group id or any variant id). Body:
```json
{ "categoryId": "…", "nameEn": "Mango Pickle", "nameGu": "…", "description": "", "hotSale": false,
  "hotStart": null, "hotEnd": null, "earnsCoins": true, "coinRedeemable": true, "active": true }
```
| field | type | required | validation | meaning |
|---|---|---|---|---|
| categoryId | uuid | yes | `@NotNull`; must exist (400 otherwise) | |
| nameEn, nameGu | string | yes | `@NotBlank` | |
| description | string | no | null -> "" | |
| hotSale | boolean | no | null -> false | |
| hotStart / hotEnd | instant | no | if both set, end must not be before start (400 `fields.hotEnd`) | |
| earnsCoins, coinRedeemable | boolean | no | null -> true | |
| active | boolean | no | Omitted (null) **keeps the current value** on every variant (the server only writes `active` when it is sent); `true`/`false` set it for all variants | applies to all variants |

- **Response**: `200` array of AdminProduct (all variants).
- **Errors**: 400 `VALIDATION_FAILED`; 404 `NOT_FOUND`.
- **UI notes**: Always send `active` explicitly.

### POST /api/v1/admin/products/{id}/stock
- **Who**: admin.
- **Purpose / when the UI calls it**: "Restock" or "Correct stock" action on a product.
- **Request**: path `id`. Body `{ "delta": 20, "reason": "RESTOCK" }`.

| field | type | required | validation | meaning |
|---|---|---|---|---|
| delta | integer | yes | positive adds stock, negative removes; the result must not go below 0 else 400 `fields.delta` "stock cannot go below zero". `0` is accepted (no-op movement). | change in units |
| reason | string | no | only the exact value `ADJUST` is recorded as ADJUST; any other value (or none) is recorded as `RESTOCK` | movement type |

- **Response**: `200` AdminProduct with the new `qtyOnHand`.
- **Errors**: 400 `VALIDATION_FAILED`; 404 `NOT_FOUND`.
- **UI notes**: A negative delta can trigger low-stock notifications to staff.

### GET /api/v1/admin/stock-movements
- **Who**: admin.
- **Purpose / when the UI calls it**: Stock history report.
- **Request**: query `productId` (uuid, optional = all products), `page`, `size` (default 20, max 50). Newest first.
- **Response**: `200` paged:
```json
{ "items": [ { "id": "…", "productId": "…", "name": "Mango Pickle", "packLabel": "500 g", "delta": -2,
               "reason": "SALE", "at": "2026-11-08T10:15:30Z", "cashierId": null, "refId": "…", "ref": "VSM-1001" } ],
  "page": 0, "size": 20, "total": 1 }
```
`name` is the English name ("?" if the product vanished); `packLabel` is pack size or unit; `delta` signed integer; `reason` string (values include `RESTOCK`, `ADJUST`; sales/returns/purchases set other values - treat as free text); `cashierId` = user who made the change (despite the name, may be an admin; nullable); `refId`/`ref` = the order/bill/return id and its human number, both null for manual adjustments.
- **Errors**: 403.
- **UI notes**: Display `ref` as a link to the order/bill if present.

### POST /api/v1/admin/products/{id}/image
- **Who**: admin.
- **Purpose / when the UI calls it**: Upload or replace a product's picture.
- **Request**: `multipart/form-data` with one part named `file` (the image). Max file 5 MB, max request 6 MB. Server accepts any format Java ImageIO can read (JPEG, PNG, GIF, BMP; WebP/HEIC are not supported) up to 25 megapixels, and produces 400 px and 1000 px JPEGs.
- **Response**: `200` AdminProduct with `imageUrl` = `/img/products/<uuid>_400.jpg`. The 1000 px version is the same name with `_1000.jpg`.
- **Errors**: 400 `VALIDATION_FAILED` for empty file ("file is empty"), not an image ("not an image"), too many pixels ("image is too large"), or over the size limit ("File too large"); 404 `NOT_FOUND`.
- **UI notes**: Every upload gets a new URL, so images are cache-safe. Variants have separate images.

### POST /api/v1/admin/categories
- **Who**: admin.
- **Purpose / when the UI calls it**: Add category.
- **Request**: body `{ "nameEn": "Dairy", "nameGu": "ડેરી", "sortOrder": 1, "active": true }`.

| field | type | required | validation | meaning |
|---|---|---|---|---|
| nameEn, nameGu | string | yes | `@NotBlank` | |
| sortOrder | integer | no | null -> 0 | ascending display order |
| active | boolean | no | null -> true | inactive categories vanish from `GET /categories` |

- **Response**: `201 Created`, the full category, the same shape as an item of `GET /admin/categories`: `{ "id": "...", "nameEn": "Dairy", "nameGu": "ડેરી", "imageUrl": null, "sortOrder": 1, "active": true }` (`imageUrl` is null until a picture is uploaded with `POST /admin/categories/{id}/image`). `active` is now always present (it was missing before).
- **Errors**: 400 `VALIDATION_FAILED`.
- **UI notes**: The admin list is `GET /admin/categories` (includes hidden ones); `GET /categories` returns only active ones.

### PUT /api/v1/admin/categories/{id}
- **Who**: admin.
- **Purpose / when the UI calls it**: Edit/hide category.
- **Request**: path `id`; same body/validation as POST (full replacement: omitted `sortOrder` -> 0, omitted `active` -> true).
- **Response**: `200`, the full category with `active`, same shape as `POST /admin/categories` and the admin list item.
- **Errors**: 400 `VALIDATION_FAILED`; 404 `NOT_FOUND` ("Category not found").
- **UI notes**: —

### GET /api/v1/admin/hot-banners
- **Who**: admin.
- **Purpose / when the UI calls it**: Banner management list (all banners incl. inactive/expired).
- **Request**: none.
- **Response**: `200` array of HotBanner.
- **Errors**: 403.
- **UI notes**: —

### POST /api/v1/admin/hot-banners
- **Who**: admin.
- **Purpose / when the UI calls it**: Add banner.
- **Request**: body `{ "title": "Diwali Sale", "subtitle": "Up to 20% off", "imageUrl": "/img/...", "startAt": null, "endAt": null, "active": true }`.

| field | type | required | validation | meaning |
|---|---|---|---|---|
| title | string | yes | `@NotBlank` | |
| subtitle | string | no | null -> "" | |
| imageUrl | string | no | stored as given (no upload endpoint for banners exists; it must be an existing URL, e.g. an image previously uploaded) | |
| startAt / endAt | instant | no | null = open; order not validated | display window |
| active | boolean | no | null -> true | |

- **Response**: `201 Created` HotBanner.
- **Errors**: 400 `VALIDATION_FAILED`.
- **UI notes**: Customers see it only while active and inside the window.

### PUT /api/v1/admin/hot-banners/{id}
- **Who**: admin.
- **Purpose / when the UI calls it**: Edit banner.
- **Request**: path `id`; body same as POST (full replacement).
- **Response**: `200` HotBanner.
- **Errors**: 400 `VALIDATION_FAILED`; 404 `NOT_FOUND` ("Banner not found").
- **UI notes**: —

### DELETE /api/v1/admin/hot-banners/{id}
- **Who**: admin.
- **Purpose / when the UI calls it**: Remove banner.
- **Request**: path `id`.
- **Response**: `204 No Content` (also for an unknown id).
- **Errors**: none specific.
- **UI notes**: —

---

# ADMIN COUPONS

Coupons are discounts applied during cart quote / checkout (documented elsewhere). `kind`: `FLAT` (value = paise off) or `PERCENT` (value = percent off, 1..100). The customer-side failure code is 422 `COUPON_INVALID` with `details.reason` in `NOT_FOUND`, `EXPIRED` (inactive or outside dates), `MIN_ORDER`, `CATEGORY` (no cart line in the coupon's categories), `LIMIT_REACHED` (per-customer or total limit), `FIRST_ORDER_ONLY`.

### GET /api/v1/admin/coupons
- **Who**: admin.
- **Purpose / when the UI calls it**: Coupon management list.
- **Request**: none.
- **Response**: `200` array of Coupon (admin shape, incl. `usedCount`, inactive and expired), ordered by `code`. Not paged.
- **Errors**: 403.
- **UI notes**: —

### POST /api/v1/admin/coupons
- **Who**: admin.
- **Purpose / when the UI calls it**: Create coupon.
- **Request**: body:
```json
{ "code": "DAIRY10", "kind": "PERCENT", "value": 10, "minOrderPaise": 20000, "maxDiscountPaise": 5000,
  "categoryId": null, "categoryIds": ["…"], "perUserLimit": 1, "totalLimit": 500,
  "startAt": "2026-11-01T00:00:00Z", "endAt": "2026-11-30T23:59:59Z", "active": true,
  "worksWithCoins": true, "firstOrderOnly": false }
```
| field | type | required | validation | meaning |
|---|---|---|---|---|
| code | string | yes | `@NotBlank`; trimmed and upper-cased; must be unique (case-insensitive) else 400 `fields.code` "code already exists" | what the customer types |
| kind | string | yes | `@NotBlank`; exactly `FLAT` or `PERCENT` (case-sensitive) else 400 `fields.kind` "FLAT or PERCENT" | |
| value | long | yes | `@Min(1)`; for PERCENT also <= 100 else 400 `fields.value` "percent cannot exceed 100" | paise for FLAT, percent for PERCENT |
| minOrderPaise | long (paise) | no | null -> 0 (no minimum). For category-limited coupons the minimum counts only spend in those categories. | |
| maxDiscountPaise | long (paise) | no | null -> 0 (no cap) | cap |
| categoryIds | uuid[] | no | limits coupon to these categories; null/empty = all. | |
| categoryId | uuid | no | legacy single category; used only when `categoryIds` is empty. Responses always return both. | |
| perUserLimit | integer | no | null -> 0; negative -> 400 `fields.perUserLimit` | 0 unlimited, 1 once per customer, N times |
| totalLimit | integer | no | null -> 0; negative -> 400 `fields.totalLimit` | total uses across customers (0 unlimited) |
| startAt / endAt | instant | no | `endAt` before `startAt` -> 400 `fields.endAt` "must be after startAt" | validity window |
| active | boolean | no | null -> true | |
| worksWithCoins | boolean | no | null -> true. false = coins cannot be spent in the same order | |
| firstOrderOnly | boolean | no | null -> false; true = only customers with no earlier non-cancelled order | |

Unknown category ids: rejected by the database with 400 `VALIDATION_FAILED` ("Something you referred to does not exist") - assumed from the global handler.
- **Response**: `201 Created` Coupon (`usedCount` 0).
- **Errors**: 400 `VALIDATION_FAILED` (above).
- **UI notes**: Triggers a live "coupons" change event.

### PUT /api/v1/admin/coupons/{id}
- **Who**: admin.
- **Purpose / when the UI calls it**: Edit coupon.
- **Request**: path `id`; same body/validation as POST (full replacement; omitted optional fields reset to their defaults). The code may be changed but must stay unique.
- **Response**: `200` Coupon (keeps `usedCount`).
- **Errors**: 400 `VALIDATION_FAILED`; 404 `NOT_FOUND` ("Coupon not found").
- **UI notes**: Send all fields.

### DELETE /api/v1/admin/coupons/{id}
- **Who**: admin.
- **Purpose / when the UI calls it**: Delete a coupon.
- **Request**: path `id`.
- **Response**: `204 No Content`. A coupon that has ever been redeemed is **deactivated** instead of deleted (history is kept); an unused one is removed.
- **Errors**: 404 `NOT_FOUND`.
- **UI notes**: After delete, refetch the list: a redeemed coupon will still appear with `active:false`.

---

# ADMIN CUSTOMERS

Only users with role `CUSTOMER` are visible here; staff ids return 404.

### GET /api/v1/admin/customers
- **Who**: admin.
- **Purpose / when the UI calls it**: Customer search/list in the back office.
- **Request**: query `q` (optional; matches part of name, email or mobile, case-insensitive; omit for everyone), `page`, `size` (default 20, max 50). Newest first (as documented in code; not otherwise asserted).
- **Response**: `200` paged CustomerRow:
```json
{ "items": [ { "id": "…", "name": "Asha", "email": "a@b.com", "mobile": "9876543210", "active": true, "createdAt": "2026-11-08T10:15:30Z" } ],
  "page": 0, "size": 20, "total": 1 }
```
`active` false = blocked. `email`, `name`, `mobile` nullable. Coins/credit/open orders are NOT in the list; open the detail.
- **Errors**: 403.
- **UI notes**: Debounce the search box.

### GET /api/v1/admin/customers/{id}
- **Who**: admin.
- **Purpose / when the UI calls it**: Customer detail page.
- **Request**: path `id`.
- **Response**: `200` CustomerView:
```json
{ "id": "…", "name": "Asha", "email": null, "mobile": "9876543210", "mobileVerified": true, "active": true,
  "source": "APP", "createdAt": "2026-11-08T10:15:30Z", "coinBalance": 120, "creditBalancePaise": 0, "openOrders": 1 }
```
`source`: `APP` (signed up in the app) or `POS` (created at the shop counter). `coinBalance` integer coins. `creditBalancePaise` integer paise owed to the shop (udhar). `openOrders` integer count of orders not yet delivered/picked up/cancelled.
- **Errors**: 404 `NOT_FOUND` ("Customer not found").
- **UI notes**: —

### PATCH /api/v1/admin/customers/{id}
- **Who**: admin.
- **Purpose / when the UI calls it**: Block/unblock a customer.
- **Request**: body `{ "active": false }`. `active` boolean, **required**: omitting it answers 400 `VALIDATION_FAILED` `fields.active` (it no longer defaults to false/blocked).
- **Response**: `200` CustomerView (updated).
- **Errors**: 404 `NOT_FOUND`.
- **UI notes**: Blocking revokes every refresh token (signed out everywhere after the current access token expires, up to 15 min; customer access tokens are not re-checked per call) and prevents any new login (403 `FORBIDDEN` on verify/refresh). Confirm before blocking.

### POST /api/v1/admin/customers/{id}/coins
- **Who**: admin.
- **Purpose / when the UI calls it**: Give goodwill coins or take back coins given by mistake. Recorded in the audit trail and the customer's ledger as `ADJUST`.
- **Request**: path `id`. Body `{ "delta": 50, "reason": "Goodwill for late delivery" }`.

| field | type | required | validation | meaning |
|---|---|---|---|---|
| delta | integer | yes | non-zero, absolute value <= 100000 else 400 `fields.coins` "a non-zero number up to 100000"; negative may not exceed what the customer can spend else 400 `fields.coins` "the customer has only N coins" | positive gives coins (with normal expiry), negative takes back (soonest-expiring first) |
| reason | string | yes | stripped; 1..100 characters else 400 `fields.reason` "1 to 100 characters" | kept in the audit log |

- **Response**: `200` CustomerView (with new `coinBalance`).
- **Errors**: 400 `VALIDATION_FAILED` (note: the validation error field is named `coins`, not `delta`); 404 `NOT_FOUND`.
- **UI notes**: The customer's wallet screen is updated via the live wallet event. Map `fields.coins` to the delta input.


---
## Addendum: safe retries of admin actions (optional `Idempotency-Key`)

These admin/staff calls accept an **optional** `Idempotency-Key` header (max 100 characters). Send a fresh UUID per tap and reuse it on every retry of that tap. The first call does the work; a repeat with the same key does nothing more and answers with the current state of what the first call touched. Without the header the call behaves as before (and a double tap applies twice). A call that fails does not use up its key.

| Endpoint | Why |
|---|---|
| `POST /api/v1/admin/products/{id}/stock` | the stock change is relative (+50 twice = +100) |
| `POST /api/v1/admin/customers/{id}/coins` | coins would be given or taken twice |
| `POST /api/v1/admin/products` | would create two products |
| `POST /api/v1/admin/categories` | would create two categories |
| `POST /api/v1/admin/coin-rules` | would create two overlapping rules |
| `POST /api/v1/admin/credit/accounts/{customerId}/adjust` (see file 30) | a write-off would be applied twice |
| `POST /api/v1/admin/stock-takes` (see file 30) | a repeat would overwrite sales made since the count |

For stock takes a repeat returns the first call's result and applies nothing more. A key belongs to one person and one request: the same key with a **different** request (or from a different admin) answers `409 IDEMPOTENCY_KEY_REUSED` (use a new key). If what the first call created has since been deleted, a repeat answers `409 IDEMPOTENT_RESULT_GONE`.


---
## Addendum: smaller behaviour changes

- **Login code e-mail failures**: `POST /api/v1/auth/otp/send` answers `503 OTP_EMAIL_FAILED` ("We could not send the sign-in code right now. Please try again in a moment.") when the shop's mail server cannot be reached; show the message and let the user retry. (Mobile codes: `503 SMS_NOT_CONFIGURED` / `502 SMS_PROVIDER_ERROR`.)
- **`PUT /api/v1/me`**: `mobile` is validated as request validation: blank or exactly 10 digits, otherwise `400 VALIDATION_FAILED` with `details.fields.mobile` = "must be 10 digits".


---
## Addendum (2026-11-09 release)

Everything below is new or changed in this release and **overrides** older text in this file where they differ. Money is integer paise, times are ISO-8601 UTC, error bodies are `{ code, message, traceId, details }`. Where an older section of this file says the opposite of this addendum, this addendum wins (the affected old statements are listed at the end).

### Paged lists: `totalExact` and `hasMore`
Every paged response (`items`, `page`, `size`, `total`) now also carries two fields:
```json
{ "items": [ ... ], "page": 0, "size": 20, "total": 10000, "totalExact": false, "hasMore": true }
```
| field | type | meaning |
|---|---|---|
| total | integer | exact while the list has 10,000 rows or fewer. A longer list reports `10000` here. |
| totalExact | boolean | `true` when `total` is the real count; `false` when `total` is only the 10,000 cap (show "10,000+"). |
| hasMore | boolean | always exact: `true` when another page follows this one. Page until it is `false`; do not compute it from `total`. |

- Totals are counted on every request (never cached or stale). When the page itself shows the end of the list, `total` is simply rows before this page + rows on this page, exact, even for a list that is longer than 10,000 rows seen from its last page.
- Paging endpoints in this file affected: `GET /products`, `GET /product-groups`, `GET /wallet/ledger`, `GET /admin/products`, `GET /admin/customers`, `GET /admin/customers/{id}/wallet-ledger`, `GET /admin/customers/{id}/orders` and the other paged lists of the backend. `GET /hot-sale` pages the products part and now also returns `totalExact` and `hasMore` (see below).
- A product search result is remembered by the server for up to 5 minutes but is cleared at once when a catalog edit commits or a product sells out or comes back in stock, so a product that appears or vanishes with stock shows up in the next list call. Stock numbers and flash prices in the answer are always fresh.

### ETag and `If-None-Match` (conditional GET)
- **Applies to exactly four public GET paths**: `/api/v1/categories`, `/api/v1/product-groups`, `/api/v1/units`, `/api/v1/hot-sale` (any query string). No other endpoint gets an ETag.
- The `200` answer carries `ETag: "<hash>"` and `Cache-Control: no-cache`. Send the stored value back as `If-None-Match: "<hash>"` on the next call; if the body is unchanged the server answers `304 Not Modified` with no body: keep your cached copy.
- The ETag is a hash of the body, so it differs per query string (`/hot-sale?page=1` has its own ETag). Browsers (CORS) may send `If-None-Match` and read `ETag`, `Retry-After` and `X-Trace-Id`.
- Every `429` (`RATE_LIMITED`) now also has a `Retry-After` header in seconds, equal to `details.retryAfterSeconds`.

### Image upload errors (all image uploads)
Applies to `POST /admin/products/{id}/image` and `POST /admin/categories/{id}/image` (and any other image upload). Send `multipart/form-data` with the part named `file`; max 5 MB; JPEG, PNG or WebP.

| status | code | `details.fields.file` | when |
|---|---|---|---|
| 400 | UNSUPPORTED_IMAGE | "must be an image (JPEG, PNG or WebP)" | the file is not a readable image |
| 400 | FILE_TOO_LARGE | "image is too large (max 25000000 pixels)" | more than 25,000,000 pixels (width x height) |
| 400 | FILE_TOO_LARGE | "must be 5 MB or smaller" | the upload is over 5 MB |
| 400 | VALIDATION_FAILED | "file is empty" | empty file |
| 400 | VALIDATION_FAILED | "send the file as multipart/form-data in a part named file" | not a multipart request (this used to be a 500) |

### POST /api/v1/admin/products/{id}/image  (changed: `variants`)
- **Who**: admin.
- **Purpose / when the UI calls it**: Product picture upload. A product (group) can have several packs (variants), each with its own `id`; `{id}` is the pack being edited.
- **Request**: multipart part `file` plus optional query `variants`:

| `variants` | effect |
|---|---|
| `one` (default, also when omitted or blank) | the picture is set on this pack only |
| `missing` | on this pack **and** on every other pack of the same product that has no picture yet; packs that already have one keep it |
| `all` | on every pack of the product, replacing the pictures already there |

The value is case-insensitive. Any other value: `400 VALIDATION_FAILED` `details.fields.variants` = "must be one, missing or all".
- **Response**: `200` AdminProduct of the pack `{id}` (new `imageUrl`, `/img/products/<uuid>_400.jpg`; a `_1000.jpg` sibling exists).
- **Errors**: 400 as in the upload table above and for `variants`; 404 `NOT_FOUND` unknown product.
- **UI notes**: After `all`/`missing` re-fetch `GET /admin/products/{id}/variants` to see the other packs. A live catalog-change event is sent.

### Customer picture fallback for packs
In `GET /product-groups` and `GET /product-groups/{id}`, a `Variant` without its own picture now shows the product's picture: `variants[i].imageUrl` is the pack's own image when it has one, otherwise the group `imageUrl` (the default pack's image, else the first pack that has one). It is `null` only when no pack has a picture. The group-level `imageUrl` is unchanged. `GET /products` rows (one per pack) are **not** changed: their `imageUrl` stays the pack's own and may be `null`.

### DELETE /api/v1/admin/products/{id}
- **Who**: admin.
- **Purpose / when the UI calls it**: "Delete product" on one pack.
- **Request**: path `id` (the pack id). No body.
- **Response**: `200` always, with what actually happened:
```json
{ "deleted": true, "deactivated": false, "reason": "Never sold, received or reviewed: removed completely" }
```
```json
{ "deleted": false, "deactivated": true, "reason": "Has orders: kept for history and switched off instead" }
```
`deleted: true` means the pack is gone for good, together with its stock row and movements, barcodes, wishlist and cart rows. It is only done when nothing refers to it. Otherwise the pack is switched off (`active: false`) and kept for history; `reason` is then "Has <what>: kept for history and switched off instead" where `<what>` is the first match of: `orders`, `counter sales`, `purchases`, `flash sales`, `reviews`, `defective stock records`, `offline till records`. Deleting a pack that is already inactive answers `deactivated: true` again (200, nothing changes).
- **Errors**: 404 `NOT_FOUND` for an unknown id (including a pack that was already hard-deleted).
- **UI notes**: Branch on `deleted` / `deactivated`, show `reason`. Audited (`PRODUCT_DELETE` / `PRODUCT_DEACTIVATE`).

### PATCH /api/v1/admin/products/{id}
- **Who**: admin.
- **Purpose / when the UI calls it**: Change only some fields of a pack (for example a price or the active switch) without resending the whole form.
- **Request**: path `id`; JSON object containing **only the keys to change**. An absent key keeps the current value. Stock is never changed here.

| key | type | rule |
|---|---|---|
| categoryId | uuid string | else 400 `fields.categoryId` "must be an id" |
| nameEn, nameGu | string | not blank, else 400 "must not be blank" |
| description | string or **null** | `null` clears it |
| mrpPaise, pricePaise | whole number (paise) | else 400 "must be a whole number"; same price rules as PUT: price and MRP not negative ("price cannot be negative" on `fields.pricePaise`), MRP not below price (`fields.mrpPaise` "MRP must not be below the price") |
| unit, packSize | string | text (`null` accepted); `unit` is checked against the unit list as in PUT |
| active, hotSale, earnsCoins, coinRedeemable | boolean | else 400 "must be true or false" |
| hotStart, hotEnd | date-time string (same forms as everywhere, see file 00) or **null** | `null` clears it; end must be after start (`fields.hotEnd` "must be after hotStart"); bad text: 400 "must be a date-time such as 2026-01-31T10:00:00Z" |
| lowThreshold | integer >= 0 | else 400 "must be 0 or more" |
| variantName, variantOrder | string, integer | change the pack's name / order inside its product |

`qtyOnHand` is **rejected**: `400 VALIDATION_FAILED` `fields.qtyOnHand` = "use the stock endpoint (POST /api/v1/admin/products/{id}/stock)". `productGroupId` and unknown keys are ignored. Types that are wrong give the 400 above with the key as the field name.
```json
{ "pricePaise": 4500, "hotEnd": null }
```
- **Response**: `200` AdminProduct (full, after the change).
- **Errors**: 400 `VALIDATION_FAILED` (above); 404 `NOT_FOUND`.
- **UI notes**: Use this for quick toggles (active, price). `PUT /admin/products/{id}` still replaces the editable fields; there an omitted `qtyOnHand` / `lowThreshold` keeps the stored value. To change stock use `POST /admin/products/{id}/stock`.

### PUT /api/v1/admin/product-groups/{id}  (clarified)
Leaving `active` out keeps the current value on every pack (the server writes `active` only when it is sent). Already described in the field table; no change in behaviour.

### GET /api/v1/admin/categories
- **Who**: admin.
- **Purpose / when the UI calls it**: Category management screen (the public `GET /categories` lists only active ones).
- **Request**: none.
- **Response**: `200` array (not paged) of every category, hidden ones included, in shop order (`sortOrder`, then English name):
```json
[ { "id": "…", "nameEn": "Dairy", "nameGu": "ડેરી", "imageUrl": "/img/categories/<uuid>_400.jpg", "sortOrder": 1, "active": true } ]
```
`imageUrl` is `null` when there is no picture; `active: false` = hidden from the shop.
- **Errors**: 403.

### DELETE /api/v1/admin/categories/{id}
- **Who**: admin.
- **Request**: path `id`.
- **Response**: `204 No Content`.
- **Errors**: 404 `NOT_FOUND` (unknown id); `409 CATEGORY_IN_USE` ("Products or coupons still use this category; move them or hide it instead") while any product or coupon (single category or coupon category list) refers to it, active or not.
- **UI notes**: On 409 offer to hide it instead (`PUT /admin/categories/{id}` with `active: false`). A live catalog-change event is sent.

### POST /api/v1/admin/categories/{id}/image
- **Who**: admin.
- **Request**: multipart part `file`, same rules and errors as the product picture (see the upload table). No `variants` parameter. Stored under `/img/categories/`.
- **Response**: `200` category in the admin shape above (`id, nameEn, nameGu, imageUrl, sortOrder, active`).
- **Errors**: 400 upload errors; 404 `NOT_FOUND` unknown category.

### GET /api/v1/hot-sale  (changed: paged products)
- **Who**: public. ETag/304 applies (see above).
- **Request**: query `page` (default 0), `size` (default 20, clamped to 1..50; negative page counts as 0).
- **Response**: `200`:
```json
{ "banners": [ HotBanner ], "products": [ Product ], "page": 0, "size": 20, "total": 37, "totalExact": true, "hasMore": true }
```
`banners`: all banners that are active and whose window contains now (not paged; always the same on every page). `products`: this page of in-stock products on hot sale now, ordered by **soonest `hotEnd` first (products with no end date last), then name**. `page`, `size`, `total`, `totalExact` and `hasMore` describe the products part only and mean the same as in every paged list: `total` is exact up to 10,000 (`totalExact` false above that), and `hasMore` is always exact: ask for the next page while it is true.
- **Errors**: none.
- **UI notes**: Replaces the old "first 20 by name" behaviour; `GET /hot-sale` with no params is page 0 of 20.

### POST /api/v1/auth/logout  (changed: optional `fcmToken`)
- **Request**: body `{ "refreshToken": "…", "fcmToken": "<firebase token>" }`. `refreshToken` required (`@NotBlank`); `fcmToken` optional. When it is sent (not blank) and the refresh token is known, that push token is removed for the user the refresh token belongs to. An unknown refresh token, or a token registered to someone else, is ignored.
- **Response**: `204`. **Errors**: 400 `VALIDATION_FAILED` for a blank `refreshToken`.
- **UI notes**: Send your current FCM token on logout so the signed-out phone stops receiving this customer's pushes. (This replaces the old note that push tokens stay attached after logout.)

### DELETE /api/v1/me/devices/{fcmToken}
- **Who**: any signed-in user (customer app).
- **Request**: path `fcmToken`, URL-encoded. No body.
- **Response**: `204 No Content`, always. Idempotent: an unknown token, or one registered to another user, answers 204 and changes nothing (you can only remove your own).
- **Errors**: 401 only.
- **UI notes**: Call on logout if you cannot send `fcmToken` to `/auth/logout`. Push is sent through Firebase HTTP v1 once the shop saves the Firebase service-account JSON on the admin config screen and a device token is registered.

### Addresses: optional `phone`
- `POST /api/v1/me/addresses` and `PUT /api/v1/me/addresses/{id}` accept an optional `phone`: blank (or omitted/null) or exactly 10 digits. Anything else: `400 VALIDATION_FAILED` `details.fields.phone` = "must be 10 digits". Blank is stored as no phone.
- Address responses (`GET /me/addresses`, POST, PUT) now include `"phone": "9876543210"` or `"phone": null`:
```json
{ "id": "…", "label": "Home", "line1": "12 Rose Apt", "area": "Satellite", "pincode": "380015", "isDefault": true, "phone": "9876543210" }
```
- `PUT` replaces the whole address: **omitting `phone` clears it**. Always send the current phone when editing other fields.
- Older orders have no phone in their address snapshot (`phone: null`). Details of the order address snapshot are in file 20.

### PUT /api/v1/me  (changed: blank name)
`name` omitted or `null` keeps the saved name (unchanged). A **blank** (empty or spaces) non-null `name` is now `400 VALIDATION_FAILED` `details.fields.name` = "must not be blank". `mobile` rules are unchanged (`fields.mobile` "must be 10 digits").

### Sign-in code (OTP) error messages
Codes, statuses and `details.reason` are unchanged; the `message` is now specific (e-mail and mobile flows alike). Showing `message` to the customer is now fine.

| status / code | `details.reason` | message |
|---|---|---|
| 422 OTP_INVALID | `WRONG` | "That code is not right. Check it and try again." |
| 422 OTP_INVALID | `EXPIRED` (no open code, or too old) | "That code has expired. Request a new one." |
| 422 OTP_INVALID | `LOCKED` (too many wrong tries) | "Too many wrong attempts. Request a new code." |
| 429 RATE_LIMITED (on `POST /auth/otp/send` and the mobile send) | `details.retryAfterSeconds` N (also the `Retry-After` header) | "Too many codes requested. Try again in N seconds." (or "Too many codes requested. Try again later." when no wait time is known) |

### Blocked customers
Blocking a customer (`PATCH /admin/customers/{id}`) now takes effect on that customer's **access token** too: a blocked customer's calls are refused within 10 seconds on every server instance (immediately on the instance that handled the block, because the remembered answer is cleared), instead of when the access token expires. The refusal is the same kind of rejection as for a blocked staff member (401/403, see file 00): treat it as "signed out / account blocked". Staff accounts are still checked on every call.

### GET /api/v1/admin/customers/{id}/wallet-ledger
- **Who**: admin.
- **Purpose / when the UI calls it**: Customer detail, "Coin history" tab.
- **Request**: path `id`; query `page` (0), `size` (20, clamped 1..50). Always newest first (`createdAt` descending).
- **Response**: `200` paged:
```json
{ "items": [ { "id": "…", "kind": "EARN", "coins": 12, "orderId": "…", "createdAt": "2026-11-08T10:15:30Z", "expiresAt": "2027-05-08T10:15:30Z" } ],
  "page": 0, "size": 20, "total": 1, "totalExact": true, "hasMore": false }
```
`kind`: `EARN`, `REDEEM`, `REVERSE`, `ADJUST`, `EXPIRE`. `coins` is signed (negative for REDEEM/EXPIRE and for coins taken back). `orderId` is the order's uuid or `null` (it is an **id**, not the order number as in the customer's own `/wallet/ledger`, which has `orderNo`). `expiresAt` is `null` unless the row is an earning that expires. There is no "coins remaining" field on a row.
- **Errors**: 404 `NOT_FOUND` for an unknown id or a staff account.

### GET /api/v1/admin/customers/{id}/orders
- **Who**: admin.
- **Purpose / when the UI calls it**: Customer detail, "Orders" tab.
- **Request**: path `id`; query `page`, `size` (same as above). Newest first.
- **Response**: `200` paged, `items` are the same rows as the admin order list (`AdminSummary`, see file 20):
```json
{ "items": [ { "id": "…", "orderNo": "VSM-1001", "status": "DELIVERED", "fulfilment": "DELIVERY", "totalPaise": 25000, "itemCount": 3,
    "placedAt": "2026-11-08T10:15:30Z", "customerName": "Asha", "customerEmail": null, "customerMobile": "9876543210",
    "subtotalPaise": 26000, "couponCode": null, "couponDiscountPaise": 0, "coinsUsed": 10, "coinDiscountPaise": 1000,
    "deliveryFeePaise": 0, "paymentMethod": "COD", "paymentStatus": "PAID", "cancelledBy": null, "cancelCode": null } ],
  "page": 0, "size": 20, "total": 1, "totalExact": true, "hasMore": false }
```
`cancelledBy` and `cancelCode` are filled for a cancelled order (same values as the admin order list) and `null` otherwise.
- **Errors**: 404 `NOT_FOUND` for an unknown id or a staff account.

### POST /api/v1/admin/customers/{id}/coins  (changed: error field)
An error raised by the coin check ("a non-zero number up to 100000", "the customer has only N coins") is now reported under **both** `details.fields.delta` and `details.fields.coins` (same message), so map either to the delta input. The `reason` error stays on `fields.reason` ("1 to 100 characters").
```json
{ "code": "VALIDATION_FAILED", "message": "…", "details": { "fields": { "delta": "the customer has only 5 coins", "coins": "the customer has only 5 coins" } } }
```

### GET /api/v1/admin/customers  (paging fields)
Response also has `totalExact` and `hasMore` (see the paging section). Searching with `q` counts up to 10,000 matches.

### Statements in older sections that this addendum replaces
- Paging (global conventions): the response also has `totalExact` and `hasMore`.
- `GET /hot-sale`: "up to 20 products, no paging" is now paged (see above).
- `POST /auth/logout` UI note about push tokens: send `fcmToken`, or call `DELETE /me/devices/{fcmToken}`.
- `PATCH /admin/customers/{id}` note "customer access tokens are not re-checked per call": they are now re-checked within 10 seconds.
- `POST /admin/customers/{id}/coins`: errors are on `fields.coins` **and** `fields.delta`.
- Address shape: has `phone`.

## Addendum: round 2 (2026-11-09 evening)

### Dates in requests (JSON bodies and query parameters)
Every request field or query parameter that is a date-time accepts three forms, read by one parser:
- ISO-8601 with `Z` or an offset: `2026-11-09T10:00:00Z`, `2026-11-09T15:30:00+05:30` (as before).
- Zone-less date-time: `2026-11-09T15:30:00`, read as **India time** (`Asia/Kolkata`).
- A plain date: `2026-11-09`, read as the **start of that day in India time** (`2026-11-08T18:30:00Z`).
Anything else is a 400. Responses are unchanged: always UTC with `Z`. These were stricter before and are now lenient: `GET /pos/products?updatedSince=` (only a full offset date-time was accepted; a zone-less or plain date gave a bare 400) and the `hotStart`/`hotEnd` keys of `PATCH /admin/products/{id}` (only `Z`/offset). JSON body fields (`hotStart`, `hotEnd`, coupon and banner windows, ...) were already lenient.

### PATCH /admin/products/{id}: typed schema in Swagger
The body is still a free JSON object read key by key (so "key absent = keep" and "key null = clear" stay different), but Swagger/OpenAPI now publishes the keys as the schema `ProductPatchBody`: `categoryId`, `nameEn`, `nameGu`, `description`, `mrpPaise`, `pricePaise`, `unit`, `packSize`, `active`, `hotSale`, `hotStart`, `hotEnd`, `earnsCoins`, `coinRedeemable`, `lowThreshold`, `variantName`, `variantOrder`, all optional. `qtyOnHand` is rejected and is not in the schema.

### Swagger schema names are now unique
Several response/request records used to share a simple name (`View`, `Row`, `Report`, `ReviewBody`, ...), so Swagger merged them into one wrong schema. Each now has its own name. The ones you are most likely to look for: the customer order view `OrderViews.View` is the schema **`OrderView`**; the product review body `{rating, comment}` is **`ProductReviewBody`**; the defective-stock review body `{decision, note}` is **`DefectiveReviewBody`**; the offline-exception review `{note}` is **`OfflineExceptionReviewBody`**; the counter-controls report is **`CounterControlsReport`** (rows `CounterControlsRow`) and the credit report **`CreditReport`**; POS and cart quote bodies are **`PosQuoteBody`** and **`CartQuoteBody`**; the two return bodies are **`PosReturnBody`** and **`OrderReturnBody`**. The JSON on the wire did not change; only generated code and docs names do. A unit test fails the build if two API models ever share a schema name again.

### Plain dates in range-end fields
- A plain date (for example `2026-12-31`) sent for a range-**end** instant field such as `hotEnd` means the **start** of that day in India time, so to say "through the end of that day" send a full timestamp (`2026-12-31T23:59:59+05:30`).
