# 60. Admin push notifications and customer segments

Audience: Flutter and web teams and the LLM assistants they use. Read `00-overview.md` first (auth, error envelope, pagination, idempotency). This file is derived from the backend source (`notification/**`, `identity/**`) and the integration test `NotificationBroadcastIT`.

Global conventions (not repeated per endpoint):

- Base path `/api/v1/admin/notifications`. JSON UTF-8. Timestamps are ISO-8601 UTC instants ending in `Z` (for example `2027-03-01T10:15:30.123Z`). IDs are UUID strings. Money is an integer number of **paise**. Field names are camelCase.
- **Every endpoint in this file is ADMIN only.** A CASHIER or CUSTOMER token gets 403 `FORBIDDEN`, no token gets 401 `UNAUTHENTICATED`. Hide these screens for other roles.
- Error body for every non-2xx: `{ "code", "message", "traceId", "details" }`. Field validation errors are HTTP 400 `VALIDATION_FAILED` with `details.fields = { "<field>": "<message>" }`.
- Responses always contain every field of the shape documented here. A field that has no value is sent as `null` (never omitted). Lists are never `null`.
- Paged lists use the project's page object: `{ "items": [...], "page": 0, "size": 20, "total": 123, "totalExact": true, "hasMore": false }` (`size` is clamped to 1 to 50). The lists in this module are small, so `totalExact` is always `true`; page until `hasMore` is `false`.

---

## 1. Purpose and what a customer sees

An admin can push an announcement to (a) specific people, (b) a role group (all customers, all staff, admins, cashiers, everybody), or (c) a **segment**: customers picked by conditions the admin builds, such as "spent more than Rs 5,000", "never purchased", "bought product X", "left items in the cart".

Sending is asynchronous. `POST /broadcast` only records the broadcast and answers `202 Accepted` at once; a background worker writes the inbox messages and sends the push notifications within a few seconds (large audiences take longer). The app polls the broadcast until it is `DONE`.

### How the recipient's app shows it

- Every recipient gets a normal inbox item, the same shape as order notifications (`GET /api/v1/me/notifications`, see `10-customer-and-catalog.md`): `{ id, title, body, kind, ref, read, createdAt }` with **`kind` = `"ANNOUNCEMENT"`**. The consumer app must add `ANNOUNCEMENT` to the kinds it renders (a megaphone icon is suggested) and must not crash on an unknown kind.
- `title` and `body` are exactly what the admin typed (trimmed). `ref` is optional free text chosen by the admin (at most 100 characters), or `null`. The backend gives it no meaning; agree a convention in the app, for example `"offers"`, `"product:<uuid>"`, `"category:<uuid>"`, `"order:<orderNo>"`. When the user taps the item or the push, open the screen `ref` names and fall back to the inbox if `ref` is `null` or not understood.
- If the user has a registered device, the phone also receives a push with the same title and body. The push carries a string-only `data` payload: `type` = `ANNOUNCEMENT`, `ref` = the admin's `ref` (the key is left out when the admin gave none) and `broadcastId` = the broadcast id (the same value as the inbox item's `broadcastId`). Open the screen named by `ref` from the push data; if it is missing or unknown, open the inbox. Other pushes (orders, payments, stock, flash sales) carry `type` = `ORDER`, `PAYMENT`, `STOCK` or `FLASH` and `ref` = order number, order number, product id or sale id; all `data` values are strings, ignore keys you do not know.
- Staff members (admin, cashier) can be recipients too (audiences `ALL`, `STAFF`, `ADMINS`, `CASHIERS`, `USERS`); they see the same inbox item.

### Delivery guarantees

- **The inbox is reliable.** Each recipient gets exactly one inbox item per broadcast, even if the server restarts in the middle (a broadcast left half sent is resumed and nobody receives it twice).
- **Push is best effort, but a crash does not lose a batch.** If Firebase is down or not configured, the inbox items are still written and the broadcast still ends `DONE`. The server records how far it has pushed; after a restart it pushes the batch that was interrupted again (so one person may get that push twice, but never zero times). A push call that fails is not retried: the batch is counted in `pushFailedBatches` (its inbox items were written anyway).
- **`pushed` only counts real hand-overs.** It counts devices of push calls that returned normally, and stays `0` while no push provider (Firebase service account) is configured: the broadcast then reaches the inbox only, and `pushConfigured` is `false`. Show a hint such as "Push is not set up: customers will see this in the app inbox only" when `pushConfigured` is `false`. `pushed` does not prove arrival.
- A user with no registered device gets the inbox item only.
- **Blocked accounts never receive anything**, and neither do deleted accounts or counter customers who never claimed their number (they cannot sign in).
- Messages are delivered to the people eligible when sending happens, not a frozen list (except `USERS`, which is resolved when you create the broadcast).

---

## 2. Screens and flow

### 2.1 Broadcasts list

`GET /broadcasts?page=0&size=20`. One row per broadcast: title, audience chip, status chip (`QUEUED`, `SENDING`, `DONE`, `FAILED`), `delivered / recipients`, `createdAt`. Pull to refresh; if any visible row is `QUEUED` or `SENDING`, re-fetch the page every 5 s while the screen is open. Tap a row for the detail (`GET /broadcasts/{id}`, same object). Button "New broadcast".

### 2.2 New broadcast wizard

1. **Audience.** Radio list: Everyone (`ALL`), All customers (`CUSTOMERS`), All staff (`STAFF`), Admins (`ADMINS`), Cashiers (`CASHIERS`), Specific people (`USERS`), Customer segment (`SEGMENT`).
2. **Who exactly.**
   - Role group: call `GET /audience-size?audience=...` and show "This will reach **N** people (**M** have the app installed with push on)". `recipients` is exact, `withDevices` is how many of them have at least one registered push device.
   - `USERS`: a list builder where the admin adds customers (search with `GET /api/v1/admin/customers?q=...`, see `10-customer-and-catalog.md`, and use `id`) or types 10-digit mobile numbers. Max 500 ids and 500 numbers. Blocked or unknown entries are not an error: they come back in `skipped` after sending.
   - `SEGMENT`: either pick a saved segment (`GET /segments`) or open the **segment builder** (section 2.4) and use it inline without saving. Always show the preview count (`POST /segments/preview`) before continuing.
3. **Message.** Title (required, 1 to 80 characters after trimming), body (required, 1 to 500), optional `ref` (at most 100). Show live counters; trim the text before measuring. A live preview card shows how it looks in the inbox.
4. **Confirm.** Summary: audience, count, title, body. Primary button "Send to N people". **Generate a fresh UUID when the confirm screen opens or when the button is tapped, keep it for retries of that tap, and send it as the `Idempotency-Key` header** (section 4). Disable the button while the request runs.
5. **Progress.** After the `202`, show the broadcast. Poll `GET /broadcasts/{id}` every 2 s (back off to 5 s after 30 s). Stop when `status` is `DONE` or `FAILED`, or when the admin leaves the screen. Show a progress bar `delivered / recipients` (for role groups and `USERS`, `recipients` is the count at creation and `delivered` can end slightly different if people joined or were blocked meanwhile; for a **segment**, `recipients` is only an estimate while `recipientsExact` is `false`: show "10,000+" instead of a number, never let the bar exceed 100% (`recipients` is never less than `delivered`), and when the broadcast ends `recipients` is replaced by the number really delivered and `recipientsExact` becomes `true`. On `DONE` show the final `delivered` and do not insist the bar reaches 100%). On `FAILED` show "Sending stopped. N of M people received it. Contact support" and offer "Send again to everyone", warning that people who already received it will get a second message (a new broadcast is a new message). Never retry automatically after `FAILED`.

### 2.3 Segments list and editor

Segments list: `GET /segments`, rows with name, description, number of conditions, `updatedAt`; actions edit, delete, "Use in a broadcast" (opens the wizard at step 3 with `segmentId`). Delete asks for confirmation; deleting never affects broadcasts already sent.

### 2.4 Segment builder (used by the editor and by the wizard)

1. Load the catalog once per session: `GET /segments/condition-types` and render the form from it (section 6). Do not hardcode conditions: new types appear in the catalog.
2. The admin picks "Customers who meet **all** / **any** of these conditions" (`match` `ALL` or `ANY`) and adds up to 10 conditions. Each condition is a card: type picker grouped by `group`, then one input per entry of `params`, plus a "Not" toggle (`negate`) for the opposite ("never bought X").
3. After every change (debounce 600 ms) call `POST /segments/preview` with the current `{match, rules}` and show "**N** customers match" (or "More than 10,000 customers match" when `totalExact` is `false`), "M can receive push", and the `sample` (up to 5 people with masked mobile numbers) so the admin can sanity check the rules.
4. Save with `POST /segments` (name and description) or use inline.

---

## 3. Endpoints

| # | Endpoint | Purpose |
|---|---|---|
| 1 | `POST /broadcast` | Queue a broadcast (202) |
| 2 | `GET /audience-size?audience=` | Size of a role audience |
| 3 | `GET /broadcasts` | List broadcasts, newest first |
| 4 | `GET /broadcasts/{id}` | One broadcast with progress |
| 5 | `GET /segments/condition-types` | Catalog for the segment builder |
| 6 | `POST /segments/preview` | Who would a set of conditions reach |
| 7 | `POST /segments` | Save a segment (201) |
| 8 | `GET /segments` | List saved segments |
| 9 | `GET /segments/{id}` | One saved segment |
| 10 | `PUT /segments/{id}` | Replace a saved segment |
| 11 | `DELETE /segments/{id}` | Delete a saved segment (204) |

### 3.1 `POST /broadcast`

Headers: `Authorization`, `Content-Type: application/json`, optional `Idempotency-Key` (max 100 characters).

Request fields:

| Field | Type | Rules |
|---|---|---|
| `audience` | string | Required. `ALL`, `CUSTOMERS`, `STAFF`, `ADMINS`, `CASHIERS`, `USERS`, `SEGMENT` |
| `title` | string | Required, trimmed, 1 to 80 characters |
| `body` | string | Required, trimmed, 1 to 500 characters |
| `ref` | string or null | Optional, trimmed, at most 100 characters; blank is stored as `null` |
| `userIds` | UUID[] | Only with `USERS`. At most 500 |
| `mobiles` | string[] | Only with `USERS`. 10 digits each, at most 500. A number can belong to a counter account and an app account; all eligible accounts get it |
| `segmentId` | UUID | Only with `SEGMENT`: a saved segment |
| `segment` | object | Only with `SEGMENT`: inline `{ "match": "ALL", "rules": [...] }` instead of `segmentId`. Give exactly one of `segmentId` and `segment` |

`USERS` needs at least one id or number. Sending `userIds`, `mobiles`, `segmentId` or `segment` with another audience is a validation error. An unknown `audience` string is HTTP 400 `VALIDATION_FAILED` with message `Bad request`.

Examples:

```json
{ "audience": "CUSTOMERS", "title": "Diwali offers", "body": "Flat 10% off till Sunday", "ref": "offers" }
```

```json
{ "audience": "USERS", "userIds": ["6f0c2d0e-0000-4000-8000-000000000001"], "mobiles": ["9876543210"],
  "title": "Your order is ready", "body": "Please collect it at the counter.", "ref": null }
```

```json
{ "audience": "SEGMENT", "segmentId": "0a7d1f52-0000-4000-8000-000000000002",
  "title": "We miss you", "body": "Come back and get 50 coins.", "ref": "coins" }
```

```json
{ "audience": "SEGMENT",
  "segment": { "match": "ALL", "rules": [ { "type": "TOTAL_SPENT", "op": "GTE", "paise": 500000 } ] },
  "title": "Thank you", "body": "A gift for our best customers.", "ref": null }
```

Response `202 Accepted`: the broadcast object (section 4.1) with `status` `QUEUED`.

Errors:

| HTTP | `code` | Meaning and what the UI should show |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Field errors in `details.fields`: `audience`, `title`, `body`, `ref`, `userIds`, `mobiles`, `segment`, or `rules[i].<param>` for segment problems. Mark the fields |
| 404 | `NOT_FOUND` | `segmentId` does not exist (deleted). Ask the admin to pick another segment |
| 409 | `IDEMPOTENCY_KEY_REUSED` | The key was used with a different body. Generate a new key and ask the admin to review |
| 422 | `NO_RECIPIENTS` | `USERS` list matched nobody; `details.skipped` lists the ids and numbers that did not match |
| 422 | `SEGMENT_TOO_HEAVY` | The segment could not be counted in time (about 5 seconds). Ask the admin to narrow it (add a condition or a shorter period). Nothing was recorded and no quota was used |
| 429 | `PREVIEW_BUSY` | Other segment counts are running. Retry after `Retry-After` seconds (`details.retryAfterSeconds`). Nothing was recorded |
| 429 | `RATE_LIMITED` | Too many broadcasts. See section 5. Only a broadcast that is actually recorded uses the hourly quota: failed requests (validation, unknown segment, no recipients, too heavy) do not |
| 401 / 403 | `UNAUTHENTICATED` / `FORBIDDEN` | Sign in again / not an admin |

### 3.2 `GET /audience-size?audience=CUSTOMERS`

Response:

```json
{ "audience": "CUSTOMERS", "recipients": 1250, "withDevices": 810 }
```

Allowed for `ALL`, `CUSTOMERS`, `STAFF`, `ADMINS`, `CASHIERS`. `USERS` and `SEGMENT` answer 400 `VALIDATION_FAILED` (`USERS` has nothing to size; for `SEGMENT` use the preview, 3.6). An unknown audience is 400 `VALIDATION_FAILED` ("Bad request").

### 3.3 `GET /broadcasts?page=0&size=20`

Response: page object whose `items` are broadcast objects (4.1), newest first.

### 3.4 `GET /broadcasts/{id}`

Response: one broadcast object (4.1). 404 `NOT_FOUND` for an unknown id.

### 3.5 `GET /segments/condition-types`

Response: a plain JSON array (not a page) of condition type entries, see section 6.

### 3.6 `POST /segments/preview`

Request: either a saved segment or inline conditions:

```json
{ "segmentId": "0a7d1f52-0000-4000-8000-000000000002" }
```

```json
{ "match": "ALL", "rules": [ { "type": "ORDER_COUNT", "op": "EQ", "value": 0 } ] }
```

Give exactly one of `segmentId` or (`match` and `rules`); otherwise 400 `VALIDATION_FAILED` on `segment`, with the message `give a segmentId or a segment (match and rules)` when neither was sent (for example `{}`) and `give either a segmentId or a segment, not both` when both were. A `SEGMENT` broadcast without `segmentId` and `segment` gets the first text. Response:

```json
{
  "recipients": 8721,
  "totalExact": true,
  "withDevices": 5300,
  "sample": [
    { "id": "6f0c2d0e-0000-4000-8000-000000000001", "name": "Asha Shah", "mobile": "98XXXXXX10" },
    { "id": "6f0c2d0e-0000-4000-8000-000000000002", "name": null, "mobile": null }
  ]
}
```

`recipients` is exact up to 10,000. When more people match, `recipients` is `10000` and `totalExact` is `false` (show "10,000+"). `withDevices` counts push-capable people among exactly those `recipients`, so it is never larger than `recipients`. `sample` has at most 5 customers; the mobile number is masked and `name`/`mobile` can be `null`. The preview typically answers in well under a second; debounce the calls. Errors: 400 `VALIDATION_FAILED` (`rules[i].<param>` field keys), 404 for an unknown `segmentId`, 422 `SEGMENT_TOO_HEAVY` (the count did not finish in about 5 seconds: tell the admin to narrow the segment), 429 `PREVIEW_BUSY` (at most 2 previews/counts run at once on the server; retry after the `Retry-After` header).

### 3.7 `POST /segments` (201), `PUT /segments/{id}` (200)

Request:

```json
{
  "name": "Big spenders",
  "description": "Spent more than Rs 5,000 in total",
  "match": "ALL",
  "rules": [ { "type": "TOTAL_SPENT", "op": "GTE", "paise": 500000 } ]
}
```

| Field | Rules |
|---|---|
| `name` | Required, trimmed, 1 to 60 characters, unique among saved segments (ignoring case) |
| `description` | Optional, at most 300 characters (null is saved as empty text) |
| `match` | `ALL` or `ANY`; missing means `ALL` |
| `rules` | 1 to 10 conditions, see section 6 |

Response: the segment object (4.2). Errors: 400 `VALIDATION_FAILED` (`name`, `description`, `match`, `rules`, `rules[i].type`, `rules[i].<param>`), **all failing fields together** in `details.fields` (an empty `name` and empty `rules` report both); 409 `SEGMENT_NAME_TAKEN` (show "Another segment already has this name"); 404 `NOT_FOUND` for an unknown `id` on update.

### 3.8 `GET /segments?page=0&size=20`, `GET /segments/{id}`

Page of segment objects (newest first) / one segment. 404 `NOT_FOUND` for an unknown or deleted id.

### 3.9 `DELETE /segments/{id}`

204 with no body. The segment disappears from lists. Broadcasts already created keep their own copy of the conditions. 404 `NOT_FOUND` when unknown.

---

## 4. Shapes

### 4.1 Broadcast object

```json
{
  "id": "b1d1c3f0-0000-4000-8000-000000000003",
  "audience": "SEGMENT",
  "title": "Diwali offers",
  "body": "Flat 10% off till Sunday",
  "ref": "offers",
  "status": "DONE",
  "recipients": 120,
  "recipientsExact": true,
  "delivered": 120,
  "pushed": 87,
  "pushFailedBatches": 0,
  "pushConfigured": true,
  "createdBy": "78e35581-0000-4000-8000-000000000004",
  "createdAt": "2027-03-01T10:15:30.123Z",
  "startedAt": "2027-03-01T10:15:31.002Z",
  "finishedAt": "2027-03-01T10:15:33.870Z",
  "skipped": [],
  "segmentId": "0a7d1f52-0000-4000-8000-000000000002",
  "segment": { "match": "ALL", "rules": [ { "type": "TOTAL_SPENT", "negate": false, "op": "GTE", "value": null, "paise": 500000, "minPaise": null, "maxPaise": null, "withinDays": null, "notWithinDays": null, "beforeDays": null, "days": null, "olderThanHours": null, "productId": null, "categoryId": null, "code": null, "pincodes": null, "source": null, "has": null } ] }
}
```

| Field | Meaning |
|---|---|
| `status` | `QUEUED` (waiting), `SENDING`, `DONE`, `FAILED` |
| `recipients` | People it was addressed to when it was created. For a `SEGMENT` it is an estimate (counted only up to 10,000) while `recipientsExact` is `false`; it is never less than `delivered`, and at the end it becomes the number really delivered |
| `recipientsExact` | `false` while `recipients` is only an estimate (show "10,000+"); `true` for all other audiences and once the broadcast has ended |
| `delivered` | Inbox messages written so far (the reliable count) |
| `pushed` | Devices a push was handed to so far (best effort; always `0` when `pushConfigured` is `false`) |
| `pushFailedBatches` | Batches (of up to 500 people) in which a push call failed. Their inbox messages were written; the push was not retried |
| `pushConfigured` | `false` when no push provider (Firebase) is configured: the broadcast reaches the inbox only |
| `createdBy` | Admin user id |
| `startedAt`, `finishedAt` | `null` until sending starts / ends |
| `skipped` | For `USERS`: ids and numbers that matched nobody; otherwise `[]` |
| `segmentId` | The saved segment used, or `null` (inline segment or other audience) |
| `segment` | **Snapshot** of the conditions used (`null` unless audience is `SEGMENT`). Editing or deleting the segment later does not change it |

A broadcast that was interrupted by a server restart is picked up again automatically (it can sit in `SENDING` for up to about ten minutes first; while a server is working on it, it shows that it is alive at least every 30 seconds, and a server that was replaced can no longer change it). A `FAILED` broadcast stopped after three failed attempts; the people reached before that keep their message.

### 4.2 Segment object

```json
{
  "id": "0a7d1f52-0000-4000-8000-000000000002",
  "name": "Big spenders",
  "description": "Spent more than Rs 5,000 in total",
  "match": "ALL",
  "rules": [ { "type": "TOTAL_SPENT", "negate": false, "op": "GTE", "paise": 500000, "value": null, "minPaise": null, "maxPaise": null, "withinDays": null, "notWithinDays": null, "beforeDays": null, "days": null, "olderThanHours": null, "productId": null, "categoryId": null, "code": null, "pincodes": null, "source": null, "has": null } ],
  "createdBy": "78e35581-0000-4000-8000-000000000004",
  "createdAt": "2027-03-01T09:00:00.000Z",
  "updatedAt": "2027-03-01T09:00:00.000Z"
}
```

### 4.3 Condition (rule) object

A condition always has the same flat set of fields; only the ones belonging to its `type` are filled, the rest are `null`. When **sending** a condition you may leave unused fields out (or send `null`). `negate` defaults to `false`. Words are case-insensitive on input and returned upper-case.

---

## 5. Idempotency and rate limit

- **Idempotency-Key** (optional but strongly recommended on `POST /broadcast`): generate a UUID per tap of "Send" and reuse it on every retry of that tap (network error, timeout, app restart before the answer). The same key with the same body returns the **same** broadcast (still `202`) and sends nothing twice. The same key with a different body is 409 `IDEMPOTENCY_KEY_REUSED`. Keys are remembered for a month. Without a key every request creates a new broadcast, so a double tap would message everyone twice.
- **Rate limit:** an admin may create at most **20 broadcasts per hour** (server setting `app.notifications.broadcast-per-hour`). The 21st gets HTTP 429 `RATE_LIMITED` with `details.retryAfterSeconds` (seconds until the oldest of the 20 leaves the hour). The response also carries a standard `Retry-After` header (seconds) with the same value; either can be used. Show "You have sent many announcements. Try again in N minutes." and disable Send until then. A repeat of an already accepted idempotent request does not count again. Other endpoints (preview, lists) only fall under the general API limit (300 calls per minute per IP).

---

## 6. Segments

### 6.1 Who can be in a segment

Only **customers** who are active (not blocked) and can sign in. Staff are never in a segment; to message staff use a role audience.

### 6.2 What counts as a purchase

Every order and spending condition uses one definition:

- An online order counts unless its status is `PAYMENT_PENDING` (online payment not completed) or `CANCELLED` (cancelled or rejected). So `PLACED`, `CONFIRMED`, `PACKED`, `OUT_FOR_DELIVERY`, `READY_FOR_PICKUP`, `DELIVERED` and `PICKED_UP` all count, paid or cash on delivery.
- A completed shop-counter (POS) sale counts when it was billed to the customer's account (a customer was attached to the bill by mobile number). Anonymous counter sales cannot be attributed and never count.
- Returns and refunds are **not** subtracted. Order totals are the amount payable (`total`), in paise.
- "`withinDays` N" means the last N times 24 hours back from now (from the moment sending starts for a broadcast, from the moment of the call for a preview).

### 6.3 Condition catalog

`GET /segments/condition-types` returns an array of entries:

```json
{
  "type": "TOTAL_SPENT",
  "label": "Total spent",
  "labelGu": "કુલ ખર્ચ",
  "description": "Customers whose total spending compares as chosen. Use op BETWEEN with minPaise and maxPaise, otherwise paise.",
  "descriptionGu": "જેમનો કુલ ખર્ચ પસંદ કરેલી શરત મુજબ હોય તેવા ગ્રાહકો. ...",
  "group": "Spending",
  "params": [
    { "name": "op", "kind": "ENUM", "label": "Comparison", "labelGu": "સરખામણી", "required": true, "min": null, "max": null, "options": ["GTE", "LTE", "BETWEEN"], "optionLabelsGu": { "GTE": "ઓછામાં ઓછું", "LTE": "વધુમાં વધુ", "BETWEEN": "વચ્ચે" }, "pattern": null, "pickFrom": null },
    { "name": "paise", "kind": "AMOUNT_PAISE", "label": "Amount (Rs)", "labelGu": "રકમ (રૂપિયા)", "required": false, "min": 0, "max": 10000000000, "options": null, "optionLabelsGu": null, "pattern": null, "pickFrom": null, "inputUnit": "RUPEES", "scale": 100 }
  ],
  "supportsNegate": true,
  "example": { "type": "TOTAL_SPENT", "negate": false, "op": "GTE", "paise": 500000 }
}
```

(`example` is a full condition object with the unused fields `null`.) `group` is one of `Spending`, `Products`, `Activity`, `Account`. `supportsNegate` is `true` for every type today. Param `kind`:

| `kind` | Input widget | Value sent |
|---|---|---|
| `INT` | Number field, `min`..`max` | integer |
| `AMOUNT_PAISE` | Money field in **rupees** shown to the admin (`inputUnit` is `RUPEES`, the label says `(Rs)`), multiplied by `scale` (100) before sending; `min`..`max` are in paise on the wire | integer paise |
| `ENUM` | Dropdown of `options` | string |
| `PRODUCT_ID` | Product picker, see `pickFrom` | UUID string |
| `CATEGORY_ID` | Category picker, see `pickFrom` | UUID string |
| `STRING` | Text field, must match `pattern` (upper-cased by the server) | string |
| `STRING_LIST` | Chips input, each value must match `pattern`, `min`..`max` values | string[] |
| `BOOLEAN` | Switch | true/false |

Every param entry also has `inputUnit` and `scale`: `"RUPEES"` and `100` for `AMOUNT_PAISE` params, `null` for all others. A range error on an amount names the unit: `must be between 0 and 10000000000 paise`. In Swagger the broadcast request body is the schema `BroadcastRequest` and `Rule.type` lists the 17 condition type names.

**Gujarati texts.** Every condition entry has `labelGu` and `descriptionGu`, every param has `labelGu`, and every `ENUM` param has `optionLabelsGu`, an object keyed by the option value (`GTE`, `LTE`, `EQ`, `BETWEEN`, `APP`, `POS`) with its Gujarati caption. The English `label`, `description` and `options` values stay as they are (the `options` values are what you send). Show the Gujarati text when the admin's language is Gujarati and fall back to the English one when the Gujarati field is blank or missing (the backend always fills them today, but a new condition type could ship without a translation). Amount captions say `(રૂપિયા)`, matching the rupee input.

> **Proof-read before launch:** the Gujarati texts were written for this release and have not yet been reviewed by a native speaker. Have a Gujarati speaker read all of them (they live next to the English texts in `ConditionType.java` and `Param.java`) before the admin app goes live; wording fixes are a backend text change only, the API does not change.

`required` is `false` for parameters that are required only in some cases (for example `paise` of `TOTAL_SPENT` is needed unless `op` is `BETWEEN`, where `minPaise` and `maxPaise` are needed); the `description` says so and the server enforces it. For types with two alternatives (`LAST_ORDER`, `REGISTERED`) give exactly one of the two.

`pickFrom` for ids: products use `GET /api/v1/admin/products?q={text}&page=0&size=20` (take `items[].id`); categories use `GET /api/v1/categories` (take each item's `id`).

### 6.4 Conditions

| `type` | Params | Meaning | Example |
|---|---|---|---|
| `ORDER_COUNT` | `op` GTE/LTE/EQ, `value` (0 to 100000), `withinDays?` | Number of purchases. `EQ 0` = never purchased | `{"type":"ORDER_COUNT","op":"EQ","value":0}` |
| `TOTAL_SPENT` | `op` GTE/LTE/BETWEEN, `paise` or (`minPaise`, `maxPaise`), `withinDays?` | Total paid | `{"type":"TOTAL_SPENT","op":"GTE","paise":500000}` |
| `AVG_ORDER_VALUE` | `op` GTE/LTE, `paise` | Average purchase value; people with no purchase never match | `{"type":"AVG_ORDER_VALUE","op":"GTE","paise":100000}` |
| `LAST_ORDER` | `withinDays` or `notWithinDays` | Bought in the last N days / **lapsed**: has bought before but not in the last N days | `{"type":"LAST_ORDER","notWithinDays":30}` |
| `FIRST_ORDER_WITHIN` | `days` | First ever purchase was in the last N days (new buyers) | `{"type":"FIRST_ORDER_WITHIN","days":7}` |
| `BOUGHT_PRODUCT` | `productId`, `withinDays?` | Bought this product (online or at the counter); `negate` = never bought it | `{"type":"BOUGHT_PRODUCT","productId":"<uuid>"}` |
| `BOUGHT_CATEGORY` | `categoryId`, `withinDays?` | Bought any product of the category | `{"type":"BOUGHT_CATEGORY","categoryId":"<uuid>","negate":true}` |
| `USED_COUPON` | `code?`, `withinDays?` | Used any coupon (or this code) on a purchase | `{"type":"USED_COUPON","code":"WELCOME10"}` |
| `REGISTERED` | `withinDays` or `beforeDays` | Joined in the last N days / more than N days ago | `{"type":"REGISTERED","withinDays":7}` |
| `COIN_BALANCE` | `op` GTE/LTE/EQ, `value` | Current coin balance | `{"type":"COIN_BALANCE","op":"GTE","value":500}` |
| `CREDIT_OWED` | `op` GTE/LTE/EQ, `paise` | Outstanding counter credit (udhar) | `{"type":"CREDIT_OWED","op":"GTE","paise":1}` |
| `CART_ABANDONED` | `olderThanHours` (1 to 8760) | Has items in the cart untouched for N hours and has not bought since | `{"type":"CART_ABANDONED","olderThanHours":24}` |
| `WISHLIST_HAS` | `productId` | Product is in the wishlist | `{"type":"WISHLIST_HAS","productId":"<uuid>"}` |
| `HAS_REVIEWED` | none | Wrote at least one review; `negate` = never reviewed | `{"type":"HAS_REVIEWED","negate":true}` |
| `PINCODE_IN` | `pincodes` (1 to 50, 6 digits each) | A saved address is in one of these pincodes | `{"type":"PINCODE_IN","pincodes":["380001","380015"]}` |
| `SOURCE` | `source` APP/POS | Signed up in the app / created at the counter and later claimed | `{"type":"SOURCE","source":"APP"}` |
| `HAS_DEVICE` | `has` true/false | Has / has no registered push device (`false` = inbox only) | `{"type":"HAS_DEVICE","has":true}` |

`withinDays`, `notWithinDays`, `beforeDays`, `days` are integers 1 to 3650. Add `"negate": true` to any condition to select the opposite. `negate` on a comparison is a plain "not": `ORDER_COUNT GTE 3` negated includes people with 0, 1 or 2 orders. Note that `AVG_ORDER_VALUE` negated also includes people who never purchased.

Notes: `BETWEEN` is inclusive on both ends. `ORDERED but never reviewed` is built by combining two conditions with `match: ALL`: `ORDER_COUNT GTE 1` and `HAS_REVIEWED` with `negate`.

### 6.5 Ready-made examples

| Name | match | rules |
|---|---|---|
| Big spenders | ALL | `[{"type":"TOTAL_SPENT","op":"GTE","paise":500000}]` |
| Never purchased | ALL | `[{"type":"ORDER_COUNT","op":"EQ","value":0}]` |
| Lapsed 30 days | ALL | `[{"type":"LAST_ORDER","notWithinDays":30}]` |
| Bought product X | ALL | `[{"type":"BOUGHT_PRODUCT","productId":"<uuid>"}]` |
| Never bought product X | ALL | `[{"type":"BOUGHT_PRODUCT","productId":"<uuid>","negate":true}]` |
| Abandoned cart (one day) | ALL | `[{"type":"CART_ABANDONED","olderThanHours":24}]` |
| Owes credit | ALL | `[{"type":"CREDIT_OWED","op":"GTE","paise":1}]` |
| New this week | ANY | `[{"type":"REGISTERED","withinDays":7},{"type":"FIRST_ORDER_WITHIN","days":7}]` |
| Ordered but never reviewed | ALL | `[{"type":"ORDER_COUNT","op":"GTE","value":1},{"type":"HAS_REVIEWED","negate":true}]` |

### 6.6 Segment validation errors

HTTP 400 `VALIDATION_FAILED`, `details.fields` keys:

| Key | Message |
|---|---|
| `match` | must be ALL or ANY |
| `rules` | add at least one condition / at most 10 conditions |
| `rules[i].type` | unknown condition type |
| `rules[i].<param>` | `is required`, `is not used by <TYPE>`, `must be between X and Y`, `must be one of ...`, `has an invalid format`, `give between X and Y values`, `each value must match ...`, `give exactly one of a and b`, `must not be less than minPaise`, `is only used when op is ...` |

`i` is the zero-based position in `rules`. Show the message next to the matching input. A wrong JSON type (for example text where a number is expected, or an unknown `op` spelled as a non-string) is 400 `VALIDATION_FAILED` with message `Bad request` and no field details.

### 6.7 What is not possible

- Counter sales that were not billed to a customer cannot be attributed, so they are invisible to every spending and product condition.
- Returns, refunds and partial cancellations do not reduce spend or counts.
- There is no condition on location beyond saved-address pincodes, nor on app version, device model, notification opt-out, last app open or time of day: the backend does not record them.
- Wishlist and cart conditions look at the current state only, not history.
- Wallet coin conditions use the current balance, not coins earned in a period.

---

## 7. Dart model sketches

```dart
enum BroadcastStatus { queued, sending, done, failed }   // QUEUED SENDING DONE FAILED
enum Audience { all, customers, staff, admins, cashiers, users, segment }

class Broadcast {
  final String id;
  final Audience audience;
  final String title;
  final String body;
  final String? ref;
  final BroadcastStatus status;
  final int recipients;
  final bool recipientsExact;     // false: recipients is an estimate, show "10,000+"
  final int delivered;
  final int pushed;
  final int pushFailedBatches;
  final bool pushConfigured;      // false: inbox only, no push provider set up
  final String createdBy;
  final DateTime createdAt;
  final DateTime? startedAt;
  final DateTime? finishedAt;
  final List<String> skipped;     // never null
  final String? segmentId;
  final SegmentSpec? segment;     // snapshot, null unless audience == segment
}

class SegmentSpec {
  final String match;             // "ALL" | "ANY"
  final List<Condition> rules;
}

class Segment {
  final String id;
  final String name;
  final String description;       // may be ""
  final String match;
  final List<Condition> rules;
  final String createdBy;
  final DateTime createdAt;
  final DateTime updatedAt;
}

class Condition {
  final String type;              // see ConditionType.type
  final bool negate;              // false when absent
  final String? op;
  final int? value;
  final int? paise;
  final int? minPaise;
  final int? maxPaise;
  final int? withinDays;
  final int? notWithinDays;
  final int? beforeDays;
  final int? days;
  final int? olderThanHours;
  final String? productId;
  final String? categoryId;
  final String? code;
  final List<String>? pincodes;
  final String? source;
  final bool? has;
}

class PreviewResult {
  final int recipients;           // capped at 10000
  final bool totalExact;          // false: show "10,000+"
  final int withDevices;
  final List<PreviewSample> sample;   // 0..5
}

class PreviewSample {
  final String id;
  final String? name;
  final String? mobile;           // masked, e.g. 98XXXXXX10
}

class ConditionType {
  final String type;
  final String label;
  final String description;
  final String group;             // Spending | Products | Activity | Account
  final List<ConditionParam> params;
  final bool supportsNegate;
  final Condition example;
}

class ConditionParam {
  final String name;              // JSON field of Condition
  final String kind;              // INT | AMOUNT_PAISE | ENUM | PRODUCT_ID | CATEGORY_ID | STRING | STRING_LIST | BOOLEAN
  final String label;
  final bool required;
  final int? min;
  final int? max;
  final List<String>? options;    // ENUM only
  final String? pattern;          // STRING / STRING_LIST only
  final String? pickFrom;         // PRODUCT_ID / CATEGORY_ID only: how to fetch the choices
}

class AudienceSize {
  final Audience audience;
  final int recipients;
  final int withDevices;
}
```

Parse enums defensively (unknown value falls back to a safe default). Serialise `Condition` for requests with `null` fields left out.

---

## 8. Implementation checklist

- [ ] Admin-only entry point for Broadcasts and Segments; hidden for CASHIER and CUSTOMER.
- [ ] Broadcasts list with status chips, polling every 5 s while any row is open.
- [ ] Wizard: audience, who (size / list builder / segment), message with live length counters (80 / 500 / 100, trimmed), confirm, progress.
- [ ] Role audiences call `GET /audience-size` and show recipients and `withDevices`.
- [ ] `USERS`: max 500 ids and 500 ten-digit numbers; show `skipped` afterwards; handle 422 `NO_RECIPIENTS`.
- [ ] A new `Idempotency-Key` UUID per Send tap, reused on retries; double tap cannot double send.
- [ ] Handle 202, then poll `GET /broadcasts/{id}` every 2 s (5 s after 30 s) until `DONE` or `FAILED`; never auto-retry a `FAILED` broadcast.
- [ ] Handle 429 using the `Retry-After` header or `details.retryAfterSeconds` (same value).
- [ ] Segment builder rendered from `GET /segments/condition-types`; id pickers use `pickFrom`; paise fields shown in rupees.
- [ ] Debounced `POST /segments/preview` showing count, `totalExact`, `withDevices` and masked sample.
- [ ] Segment list, create, edit (PUT), delete; show 409 `SEGMENT_NAME_TAKEN`.
- [ ] Map `details.fields` keys such as `rules[2].paise` to the right input.
- [ ] Customer app: render inbox `kind` `ANNOUNCEMENT`, open the screen named by `ref`, tolerate unknown `ref` and unknown kinds.
- [ ] Push taps read the push `data` (`type`, `ref`, `broadcastId`) and open the matching screen; fall back to the inbox when `ref` is missing or unknown.
