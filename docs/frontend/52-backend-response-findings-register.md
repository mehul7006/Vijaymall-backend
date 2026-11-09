# 52. Backend response to the Flutter team's findings register (2026-11-09, round 3)

This answers every open row of "Frontend Findings Register": section 2 (R2-16 to R2-21, P-1 to P-5) and the functional-validation rows (S-A1 to S-E7). **Fixed** = changed in the code and covered by tests. **Clarification** = behaviour is intended; we explain it and, where useful, documented it. **Deferred** = we agree and will do it later; the reason is given. All money is paise.

Please retest the rows marked Fixed and update the Backend column of your register from this file. Related guides: 00, 10, 20, 30, 40, 60 (their new "Addendum" sections), and 51 for the earlier round.

## 1. Summary

| Result | Rows |
|---|---|
| Fixed | R2-16, R2-17, R2-18, R2-19, R2-20, R2-21 (shortName only), P-1, P-2, P-3, P-4 (labels and unit), P-5, S-A1, S-E3, S-B6, S-D7 (partly, see 4), S-D1/S-C3, S-D3, S-D2/S-A5/S-C1/S-E2, S-E1, S-C2, S-B1, S-B3, S-D6, S-C4, S-E5, S-E6, S-C5, S-C6, and the documentation rows S-A2/S-B5, S-A3, S-A4, S-B2, S-B7, S-D9, S-E4 |
| Clarification | S-B4 (two customers can order the last unit), the segments path (section 5) |
| Deferred | R2-21 `iconUrl`, P-4 Gujarati labels, S-E7 duplicate supplier invoice, plus the part of S-D7 explained in section 4 |

## 2. Section 2 rows

| ID | Verdict | What changed / what to do |
|---|---|---|
| R2-18 images | **Fixed** | `/img/**` is served `Cache-Control: public, max-age=31536000, immutable`; no `no-store`, no `Expires: 0`. A repeat request with `If-Modified-Since` gets 304. |
| R2-19 logo format | **Fixed** | An upload with an alpha channel stays PNG (`..._400.png`, `..._1000.png`); opaque images stay JPEG. The same rule now applies to product and category uploads, so do not assume the extension is `.jpg`; always use the URL from the API. |
| R2-20 Gujarati shop text | **Fixed** | `GET /shop-settings` and the admin shop-settings / shop-profile GET and PUT have optional `shopNameGu`, `addressGu`, `promiseGu`, `detailsGu`, `holidayNoteGu`. Empty means "fall back to English". On PUT, a key left out keeps the stored text; `""` clears it. See file 20, Addendum 3. |
| R2-21 shortName / icon | **Fixed (shortName), deferred (iconUrl)** | `shortName` (max 20 characters) is on the public and admin shop views. A square `iconUrl` needs image processing and a design decision on sizes; it is on the list, not in this release. |
| P-1 push data | **Fixed** | Every push now has an FCM `data` map of strings: `type` (`ORDER`, `PAYMENT`, `STOCK`, `FLASH` or `ANNOUNCEMENT`) and `ref` (left out when there is none); announcements also carry `broadcastId`. Open the order, product or offer from `type` + `ref`. The text in file 00 is corrected. |
| R2-17 low-stock list | **Fixed** | Inactive products are filtered out before the cap of 200. The array shape is unchanged. |
| R2-16 flash sales admin | **Fixed** | (a) Create and update report all failing fields together. (b) `DELETE /admin/flash-sales/{id}`: 204; 404 unknown; 409 `FLASH_NOT_ALLOWED` if the sale started or has any recorded use (end or cancel those instead). Audited, and it fires the live FLASH event. A repeat DELETE answers 404. Cashiers can reach `/admin/flash-sales/**` (now stated in files 00 and 40). |
| P-2 segment validation | **Fixed** | Create and update report `name`, `description` and the rule problems together. |
| P-3 / S-C5 wording | **Fixed** | Neither given: "give a segmentId or a segment (match and rules)". Both given has its own text. |
| P-4 condition catalog | **Fixed (labels), deferred (Gujarati)** | Amount parameters are labelled "(Rs)" and carry `inputUnit: "RUPEES"` and `scale: 100`: show rupees, send paise (`rupees * scale`). `labelGu` / `descriptionGu` are not shipped because we will not release unreviewed Gujarati for 17 condition types; send us reviewed text or confirm we should write it. |
| P-5 defective resolve | **Fixed** | `must be WRITE_OFF or RETURN_TO_SUPPLIER (got "x")` on `fields.action`. |

## 3. Functional-validation rows

| ID | Verdict | What changed / what to do |
|---|---|---|
| S-A1 deleted account token | **Fixed** | The cached "is blocked" answer was not dropped when the account was anonymised. It now is, so the token used for `DELETE /me` gets 401 on the very next call (tested for `GET /me`, `PUT /me`, `POST /me/addresses`). Refresh tokens were already revoked. |
| S-E3 staff create promotes | **Fixed** | `POST /admin/pos/staff` answers 409 `EMAIL_ALREADY_REGISTERED` for any existing account. `confirmPromote: true` promotes only an **active customer**; blocked, deleted and cashier accounts stay 409, admins 400. Mobile must be 10 digits, name 1..80. Suggest a "promote this customer?" dialog on the 409. |
| S-B6 coinsEarned on cancelled | **Fixed** | Coins are only credited on delivery or pickup, so a cancelled order never earned any; every order view now reports `coinsEarned: 0` once cancelled. |
| S-D7 order transitions | **Fixed in part, see section 4** | New 422 `INVALID_TRANSITION` for a status that does not fit the fulfilment (`READY_FOR_PICKUP` / `PICKED_UP` only for PICKUP; `OUT_FOR_DELIVERY` / `DELIVERED` only for DELIVERY) and for a move back to `PLACED` once stock is reserved. The allowed moves are in a table in the file 20 addendum. |
| S-D1 / S-C3 fractions | **Fixed** | A fractional number for an integer field is a 400 `must be a whole number` (stock delta, `paisePerCoin`, coin delta, quantities). `5.0` is also rejected: send `5`. |
| S-D3 sibling rules | **Fixed** | Negative variant stock, a negative coupon minimum order, and a coin rule or hot banner with `endAt` before `startAt` are 400 (`must be 0 or more`, `must be after startAt`). |
| S-D2 / S-A5 / S-C1 / S-E2 size limits | **Fixed** | Limits on names, descriptions, prices, coupon codes, coin-rule bounds, shop texts and phone, stock deltas, profile name (1..80), address label/line1/area, holds, purchase and credit amounts, search `q` (100). They appear as `maxLength` / `maximum` in Swagger and in the tables in file 10 (Addendum 3) and file 30. |
| S-E1 holds | **Fixed** | At most 50 lines and a 60-character label per hold; per-line quantity 1..9999 (or up to 100000 grams for weighed items). Creating the **101st** open basket answers 409 `HOLD_LIMIT` (`details.max` 100), so nothing ever drops out of the till list. Show "resume or discard a parked basket first". |
| S-C2 credit overflow | **Fixed** | Amounts are bounded (400 `fields.amountPaise`, at most 100,000,000 paise; negative stays a write-off on adjust). The same cap applies to credit limits, POS payments, cash tendered and manual discount. |
| S-B1 coupon picker, unknown product | **Fixed** | Now identical to the quote: 400 `fields.productId`. |
| S-B3 coin warning | **Fixed** | Quote with `coinMode` `ALL` or `PERCENT` and no coin-redeemable line warns "None of the items in the cart can be paid with coins". |
| S-D6 return movements | **Fixed** | `ref` now carries the order number for online returns; POS returns resolve to the credit note number. |
| S-C4 coin expiry | **Fixed** | `GET /wallet` has `expiryMonths` (always a positive number, read from the coin settings). |
| S-E5 payment methods | **Fixed** | `PUT /admin/payment-methods` must list COD and ONLINE exactly once: an empty or partial list answers `fields.methods` "must list every method"; a repeat answers `duplicate code "X"`. Send both methods on save. |
| S-E6 pause message | **Fixed** | While orders are paused and the admin left the message blank, `onlineOrdersMessage` returns the default pause text; it is `""` only while orders are open. |
| S-C6 Swagger | **Fixed** | Broadcast request schema is `BroadcastRequest`; `Rule.type` publishes the 17 type names. |
| S-E7 duplicate supplier invoice | **Deferred** | An observation, agreed. It needs a new lookup and a response field; file 30 says the server does not warn. |
| S-B4 last unit | **Clarification** | Two customers can both place an order for the last unit; staff reject the loser (the documented place-time behaviour). Stock is taken at confirmation. |

## 4. S-D7 explained

The order-status rules were deliberately relaxed earlier so staff can correct mistakes (any status until the order is delivered). We did not take that away. We closed only the two real contradictions: a status that makes no sense for the order's fulfilment, and moving back to `PLACED` after stock is reserved (that would leave stock deducted on an order that looks unconfirmed). Everything else, including other backward moves, stays free.

## 5. Clarifications

- **Segments path.** Your register calls it `/admin/push/segments`. The path is `/admin/notifications/segments` (and `/admin/notifications/broadcast`, `/broadcasts`, `/audience-size`), exactly as in file 60. We added no alias. If a call to `/admin/push/...` worked for you, tell us the exact URL, because we do not serve it.
- **Documentation rows.** Corrected in place: quote never checks stock (only `POST /orders` answers `OUT_OF_STOCK`); a merged duplicate quantity above 99 is a 400 and `PUT /cart` allows at most 99 per line; `GET /cart` hides stored lines whose pack is inactive, out of stock or unknown and shows them again after restock; `expectedTotalPaise` is optional; `GET /orders` ignores unknown query parameters (`q` exists only on the admin list); `/pos/products?q=` matches barcode and PLU; a lower-case payment mode is accepted; replaying a used OTP while an earlier code locked by 5 wrong tries is still inside its 5 minutes answers `LOCKED` (file 10).

## 6. What the app must handle (new codes and shapes)

1. 422 `INVALID_TRANSITION`, 409 `HOLD_LIMIT`, 409 `EMAIL_ALREADY_REGISTERED`, 409 `FLASH_NOT_ALLOWED`.
2. `5.0` for integer fields is a 400; send whole numbers.
3. `PUT /admin/payment-methods` needs both methods.
4. `GET /wallet.expiryMonths`; shop settings Gujarati fields and `shortName`; push `data` map.
5. Image URLs may end in `.png`; they are now cacheable for a year (the file name changes when the image changes).
6. Several new 400s for size and amount limits (see the tables in files 10 and 30).

## 7. Your waiting conditions (register section 4)

We cannot create these for you either on a shared server, but here is how to unblock each on a **dev** server: enable ONLINE in admin payment methods with a test gateway configured on the dev server to see `paymentExpiresAt` (tell us if you need help configuring the dev gateway); set `roundOffMode` to `NEAREST_RUPEE` in shop settings and quote a counter bill with paise; for role-group and segment sends use a dev database with only test customers (a `USERS` audience with your own test accounts is the safest) and set an FCM service account only on a test project; for flash-sale purchases create a sale on dev and buy as a test customer.

## 8. Test data

We will remove your financial test records (POS sale `VSM/2026-27/000001`, credit note `VSM-CN/2026-27/000001`, orders `VSM-1022`, `VSM-1023`, test customers, flash sales `ZZ-QA ...`) from the **dev** database when you give the go-ahead. We will not do that on any shared or production instance.
