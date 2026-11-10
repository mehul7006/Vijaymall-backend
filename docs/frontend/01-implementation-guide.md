# 01. Complete implementation guide for the frontend team

Read this first, then use the numbered guides as the detailed reference. It tells you **what to build in which order**, **which guide section answers each screen**, **what changed since the first version of the guides**, and **what must be configured on the server** before a feature works. All money is paise; all times in responses are UTC with `Z`.

## 1. The guides and what each is for

| File | Use it for |
|---|---|
| `00-overview.md` | Roles, sign-in, conventions (money, dates, paging, errors, idempotency, rate limits, ETag, images, live events), test environments. Read once, keep open. |
| `10-customer-and-catalog.md` | Sign-in screens, profile, addresses, devices, wallet and coins, notifications inbox, wishlist, catalog, reviews, coupons list, admin catalog and customer screens. |
| `20-orders-payments-admin-config.md` | Cart and quote, placing and tracking orders, returns, online payment and refunds, shop details, admin order desk, payment methods, the admin configuration screen. |
| `30-pos-reports-purchasing.md` | Counter till (billing, holds, returns, credit), shifts, offline sync, reports, audit log, stock takes, defective stock. |
| `40-flash-sales.md` | Flash sales (timed discounts) for customers and admins; note on hot sale versus flash sale. |
| `60-admin-push-notifications.md` | Admin broadcasts to everyone, a role group, chosen people or a segment built from conditions. |
| `70-purchases.md` | Suppliers, purchase documents (GST, cess, discount, freight), payments and payables, returns to suppliers, supplier statement, credit and refunds, invoice file. |
| `72-barcode-labels.md` | Generating store barcodes and printing labels (PDF, thermal printer commands, JSON). |
| `71-bank-and-settlements.md` | Bank module: money accounts, payment channels (several vendors at once), receipts, settlements and statement import, exceptions, account book, the one card-machine flow for every vendor. |
| `73-paytm-terminal.md` | The Paytm wireless swipe machine as one vendor of that flow: admin set-up, machines master, till flow, same-day void, Paytm settlements. |
| `74-stock-lots-and-pricing.md` | Stock lots, lot-aware prices and scanning, FIFO cost, expiry, margin and stock-value reports. |
| `playbooks/README.md` | Step-by-step implementation playbooks (reading order and index): [P1 orders, coupons, returns, payments](playbooks/P1-orders-coupons-returns-payments.md), [P2 purchases, suppliers, labels](playbooks/P2-purchases-suppliers-labels.md), [P3 counter till and card machine](playbooks/P3-counter-till-and-card-machine.md), [P4 bank, settlements, receipts](playbooks/P4-bank-settlements-receipts.md), [P5 stock lots and pricing](playbooks/P5-stock-lots-pricing.md). Per feature: goal, fields, API sequence, edge cases, Dart, acceptance tests. |
| `75-gst-master.md` | GST and HSN master: suggestions, rate card, admin edits, how forms use them, server-side fill of blank GST and HSN |
| `50` to `53-backend-response-*.md` | The backend's answers to your reviews, item by item, with what to retest. Use them as the change history. |

Each numbered guide ends with its own "Addendum" sections for later changes; where an addendum contradicts older text in the same file, **the addendum wins**.

## 2. Rules that apply to every screen (checklist)

1. **Auth.** OTP sign-in, bearer token (about 15 minutes), refresh once on 401, then sign in again. A blocked, deleted or demoted account gets 401 at once.
2. **Errors.** Every error is `{ code, message, traceId, details }`. Show `details.fields[<name>]` under the input with that name (keys are JSON paths such as `items[0].qty`), else show `message` as a toast, with `traceId` on 5xx. Switch on `code`, never on `message`. The full "mistake to message" table is in file 00.
3. **Retry rules.** 429 and 503 `SERVICE_BUSY` carry `Retry-After` (seconds) and `details.retryAfterSeconds`: wait, then retry once; never loop. `SERVICE_BUSY` is "the shop is busy", not a provider outage.
4. **Idempotency.** Send a fresh `Idempotency-Key` (UUID) per user action and reuse the same key when retrying that action. Applies to orders, POS sales, returns, purchases, payments and most admin writes.
5. **Money.** Integers in paise only. Unit costs on purchases can be fractional (`landedUnitCost*`): parse as double there only.
6. **Paging.** `{items, page, size, total, totalExact, hasMore}`; page from 0, size at most 50, loop until `hasMore` is false. `total` is exact up to 10,000, then `10000` with `totalExact:false` (show "10,000+").
7. **Whole numbers.** Integer fields reject fractions and `5.0`: send `5`.
8. **Dates in requests.** Send ISO with `Z`/offset. A plain date means the start of that day in India time; for "through the end of the day" send a full timestamp. Order list `to=YYYY-MM-DD` is the exception and covers the whole day (file 20 Addendum 6).
9. **Caching.** Honour `ETag`/`If-None-Match` on categories, units, product groups, hot sale and shop settings; images are cacheable for a year (the file name changes when the image changes).
10. **Live updates.** Treat SSE events as "refetch this list", not as data.
11. **Languages.** Show `nameGu`/`...Gu` fields when the app is in Gujarati and fall back to English when blank (catalog, shop details, segment labels).

## 3. What to build, by app

### 3.1 Customer app
| Feature | Guide | Notes |
|---|---|---|
| Sign in (e-mail and mobile OTP), profile, delete account | 10 | Deleting the account signs out at once (401). |
| Addresses (6-digit pincode, optional phone) | 10 | `PUT` replaces the whole address, so send `phone` again. |
| Browse, search, product groups and variants, hot sale, flash sale strip | 10, 40 | Hot sale is advertising only; flash sale carries the discount. Online shows one price per item. |
| Cart, quote, coupons picker | 20 | Quote does not check stock; only placing the order does. Coupons may be limited to categories or specific items (see 6). |
| Place order (COD or ONLINE), order tracking, cancel, returns | 20 | `expectedTotalPaise` is optional but send it. Show `cancelledBy`/`cancelCode`. |
| Online payment | 20 | Needs a configured gateway on the server (section 5). |
| Wallet, coins (with `expiryMonths`), notifications inbox, push | 10 | Push carries `type` and `ref` data (open the order, offer, etc.); inbox kind `ANNOUNCEMENT` is an admin broadcast. |

### 3.2 Admin app
| Feature | Guide | Notes |
|---|---|---|
| Order desk: filters by status list, `needsAction`, date range; status moves; reject/cancel; returns with preview | 20 | `INVALID_TRANSITION` for moves that contradict the fulfilment. |
| Catalog: products, variants, categories, units, banners, hot sale, bulk hot-sale update, stock adjust | 10 | Size and amount limits are in the addendum tables; Swagger shows them. |
| Coupons and coins | 10, 20 | Coupon targets: categories, products, product groups. Return policy setting. |
| Customers, credit (search, limit, adjust), wallet adjust | 10, 30 | `CREDIT_NOT_ALLOWED` (no limit) is different from `CUSTOMER_BLOCKED`. |
| Staff (create cashier) | 30 | Existing e-mail: 409 `EMAIL_ALREADY_REGISTERED`; promote an active customer only with `confirmPromote:true`. |
| Shop profile (Gujarati texts, short name, logo and icons) | 10, 20 | Logo upload also produces icons; replacing a logo removes the old files. |
| Configuration screen (payments, push, SMS, logging, caching, GST, coupons and returns, Paytm) | 20 | See section 5. |
| Purchases, suppliers, payables, supplier statement | 70 | New purchase form with GST mode, discount, freight; payments; returns; attachment. |
| Barcode labels | 72 | Generate store barcode, choose template, print. |
| Push broadcasts and segments | 60 | Audience size, segment builder from the condition catalog, progress screen. |
| Reports, audit log, stock take, defective stock | 30 | Lists are paged with bounded totals. |
| Money accounts (bank, gateway, UPI, card machines, cash), account book with manual entries, payment defaults | 71 | `CARD_TERMINAL` accounts of provider `paytm` are made by adding the machine; defaults and gateway live on the configuration screen (section 5). |
| Payment channels overview, receipts list and summary (all vendors), fees report, payer detail on the order and on the bill (`/admin/payments/by-sale/{saleId}`) | 71 | Counter card/UPI lines show under their machine's vendor (for example `paytm`). |
| Settlements: list, items, sync now, statement CSV import (Paytm Settlement Report is detected by its header), exceptions queue, unsettled report | 71, 73 | Work the exceptions queue; badge = OPEN count. |
| Paytm machines master and test button | 73 | ADMIN; each machine creates its channel. |
| Stock lots per variant, lot-priced and expiry switches, expiring list and write-off, stock valuation, margin report, lot check, "Stock and lots" settings | 74 | `GET/PUT /admin/variants/{id}/lot-settings` read and set the two switches. |
| Flash sales | 40 | Cashiers may use the flash-sale endpoints too. |

### 3.3 Counter till (cashier)
| Feature | Guide | Notes |
|---|---|---|
| Open/close shift, cash up | 30 | |
| Scan or search (barcode, PLU, name), weighed items, quote with manual discount (manager PIN) | 30 | `/pos/products?q=` matches barcode and PLU; incremental sync with `updatedSince`. |
| Coupons and coins at the till | 30 | Needs the customer's mobile; coupon discount is calculated on covered lines at bill time; re-quote after adding or removing items. |
| Payments: cash, UPI, card, credit | 30 | Mode errors: bills accept CREDIT, refunds and repayments do not. |
| Choose the payment channel per mode (cash drawer, UPI QR, Paytm machine, bank machine, ...) | 71 | `GET /pos/payment-channels`; send the channel's `moneyAccountId` on the payment line. Several vendors can be active at once. |
| Card and UPI on a card machine (Paytm wireless or a manual bank machine) | 71, 73 | One flow: start with the channel's `moneyAccountId`, poll, confirm (manual only), cancel/void, then `terminalPaymentId` on the bill line; online only. |
| Lot-aware prices at the scan and quote, split price runs, offline `lot` field | 74 | Show the "after N more the price is ..." notice; group same-product bill lines. |
| Holds (parked baskets) | 30 | At most 100 exist (409 `HOLD_LIMIT`); they expire after 7 days (`expiresAt`). |
| Returns, credit notes, return preview | 30 | Preview shows the exact refund per line before confirming. |
| Credit customers picker (read-only for cashiers) | 30 | `GET /pos/credit/customers`. |
| Offline billing and sync | 30 | Coupons, coins, credit and machine payments are refused offline. |
| Receipts and label printing | 30, 72 | |

## 4. What changed since the first guides (read as a migration checklist)

| Area | Change | Action for the app |
|---|---|---|
| Errors | Field-level messages for bad enum, type, id, missing header or parameter; malformed JSON message | Show `details.fields` per input; stop showing "Bad request" |
| Paging | `totalExact`, `hasMore` on all lists (incl. hot sale) | Page until `hasMore` false |
| Orders | `expectedTotalPaise` optional; `cancelledBy`, `cancelCode`, item `imageUrl`; date filters; status lists; `needsAction`; unknown `status` is now 400 | Update filters and the order desk |
| Orders | 422 `INVALID_TRANSITION`; cancelled orders report `coinsEarned: 0` | Handle the code; hide coins on cancelled |
| Payments | `PUT /admin/payment-methods` needs both methods; payment-mode texts differ for bills and refunds | Send the full list |
| Catalog | Typed PATCH; PUT replaces; delete product; categories return `active`; image `variants=all`; `.png` logo URLs | Use PATCH for partial edits; take URLs from the API |
| Shop | Gujarati fields, `shortName`, `iconUrl`/`icon192Url`, default pause message | Bilingual shop screens; use icons for the web manifest |
| Images | Cacheable for a year | Cache by URL |
| Identity | Pincode 6 digits; address `phone`; token of a deleted account rejected at once; staff create 409 | Update forms |
| Credit | Search, `availablePaise`, `blocked`; `CUSTOMER_BLOCKED`; cashier picker | Use the picker at the till |
| POS | Holds limit 100 and 7-day expiry; qty/label limits; payment mode normalisation; return preview; Paytm terminal payments | Handle `HOLD_LIMIT`; show `expiresAt` |
| Coupons | Item targeting (`appliesTo`), reasons `ITEM`, `FLASH_ITEMS`, plain messages, picker shows savings and covered lines; return policy KEEP or CLAWBACK; `couponSharePaise`, `couponClawbackPaise` on returns | Show messages as they are; use return preview |
| Purchases | `totalCostPaise` now includes GST for products with a GST rate (old amount is `taxablePaise`; `gstMode: NONE` for the old behaviour); suppliers, payments, returns, statements | Rebuild the purchase form from file 70 |
| Barcodes | Store prefix `29`; check digits validated on standard lengths | Use the generate endpoint |
| Push | `type`/`ref` data; broadcasts and segments | Deep-link on tap |
| Inputs | Fractions and `5.0` rejected; size and amount limits everywhere | Validate before sending |
| Reliability | 503 `SERVICE_BUSY` when the database is saturated | Retry after `Retry-After` |

## 5. What must be configured on the server (per feature)

| Feature | Configure | Where |
|---|---|---|
| Online payment | Razorpay key id, key secret, webhook secret; enable ONLINE in payment methods | Admin configuration screen, payment methods |
| Push to phones | Firebase service account | Admin configuration screen (push) |
| SMS login | SMS provider keys | Admin configuration screen (SMS) |
| Counter GST and round-off | GSTIN, state code, invoice prefix, round-off mode | Shop settings |
| Purchases | `purchase.gstMode` default (regular, composition, none; each purchase can override) | Admin configuration screen |
| Coupons and returns | `returns.couponPolicy` (KEEP or CLAWBACK_BELOW_MINIMUM), `coupon.allowOnFlashItems` | Admin configuration screen |
| Paytm machine | `paytm.enabled` (needs the three below), `paytm.mid`, `paytm.merchantKey` (16 characters, secret), `paytm.channelId`, `paytm.environment` (STAGING or PRODUCTION), optional `paytm.baseUrl`; tuning `paytm.pollSeconds`, `paytm.waitSeconds`, `paytm.autoAccept`, `paytm.paymentMode`, `paytm.timeoutConfig`; settlement API `paytm.settlement.enabled`, `paytm.settlement.authStyle`, `paytm.settlement.token`, `paytm.settlement.baseUrl`, `paytm.settlement.syncDays`. Then add each machine's terminal id on the machines screen | Admin configuration screen (group PAYTM) and payment terminals (`73`) |
| Money accounts and defaults | Create the accounts first (bank, Razorpay gateway account, UPI QRs, machines; keep the cash drawer). `payment.gatewayAccountId` (account for online payments; blank = first active GATEWAY account of the payment's provider), `payment.onlineProvider` (gateway the customer app uses; blank = server default), `payment.settlementDays` (normal settlement cycle, default 2; overdue after one more day), `pos.defaultAccount.CASH`, `pos.defaultAccount.UPI`, `pos.defaultAccount.CARD` (account used when the till names none; blank CASH = the drawer, blank UPI/CARD = Unassigned), `pos.terminalProvider` (default `manual`; only the FALLBACK adapter for a channel whose provider has none, the channel's own provider normally decides) | Admin configuration screen (groups Payments and Counter rules), see `71` |
| Stock lots | `inventory.lotCrossPolicy` (only `CAP`), `inventory.costingMethod` (only `FIFO`), `inventory.expiryAlertDays` (default 30, 1 to 365), `inventory.autoWriteOffExpired` (default off; the daily job at 03:15 writes off expired lots when on). Per variant: the `lotPriced` and `trackExpiry` switches | Admin configuration screen (group "Stock and lots") and the variant screen, see `74` |
| Labels | `barcode.storePrefix`, label DPI, label templates | Admin configuration screen and label templates |
| Hold expiry | `HOLD_EXPIRY_DAYS` (server setting, 0 = never) | Server environment |
| Invoice files | `FILES_DIR` folder (back it up) | Server environment |
| Dev testing | Dev server accepts login code `12345` for any e-mail; the seeded admin is `ADMIN_EMAIL` | `00-overview.md` section 6 |

## 6. Rules that need explanation

**Hot sale vs flash sale.** Hot sale is how an admin advertises items (featured list and banners); it changes no price. A flash sale is a timed occasion discount with its own prices and reports. They are separate on purpose.

**Coupons and returns.** A coupon can cover everything, chosen categories, chosen products or whole product groups (or a mix). The discount is split across covered lines at quote/bill time, and the minimum order counts covered lines only. Each order or bill line stores its own coupon and coin share. Returning an item refunds its price minus its shares (delivery fee excluded). With `KEEP` the customer keeps the coupon benefit on the kept items; with `CLAWBACK_BELOW_MINIMUM`, if what remains falls below the coupon minimum, the coupon is removed from the kept items and taken out of this refund (it comes back as the kept items are returned). Always call the return preview before confirming.

**GST on purchases.** Chosen per purchase: `REGULAR` (creditable GST is not part of cost), `COMPOSITION` (GST is part of cost), `NONE`. Inter-state purchases use IGST when the supplier's state differs from the shop's. Landed cost per unit includes freight share and free units.

**Card and UPI at the till.** Choose the channel (the vendor) from `GET /pos/payment-channels`, start the payment with that channel's `moneyAccountId`, poll the session, and put the finished session id on the bill line as `terminalPaymentId`. The channel decides the machine: Paytm reports the result itself, a manual bank machine needs the cashier to confirm with the approval code. Machine payments are online only. UPI paid straight to a QR sticker (not through the machine) appears later in settlement reports, not instantly.

**Several payment vendors at once.** Razorpay online, a Paytm machine, a bank machine, a PhonePe or Paytm QR and the cash drawer can all be active together. Every receipt is tagged with a money account (`Unassigned` is the honest bucket for history); the cashier picks the channel per payment; the customer app uses the gateway named by `payment.onlineProvider`; settlements, receipts and fees are reported per vendor and account. Adding a vendor is server work (adapter) plus a money account, not a new screen: see `71` section 7.

## 7. Suggested build order

1. Foundation: sign-in, error handling, paging, money, retry and idempotency helpers, ETag cache.
2. Customer app: catalog, cart, quote, orders, wallet, notifications.
3. Admin core: order desk, catalog, coupons, customers, configuration.
4. Till: shifts, billing, payments, holds, returns, credit; then offline.
5. Purchases and suppliers (70), barcode labels (72).
6. Push broadcasts (60), flash sales (40).
7. Bank module and channels (71): accounts, defaults, channel chooser at the till, receipts, settlements, exceptions; then the Paytm machine (73) once the server has Paytm credentials.
8. Stock lots (74): lots tab and switches, till price notice, valuation and margin reports.

For step-by-step detail per feature use the playbooks (`playbooks/README.md`).

## 8. Open items and dependencies

- Paytm and Razorpay accounts: card/UPI machine and online payments can be verified live only after the owner supplies credentials; the guides list what is unconfirmed.
- Gujarati text for segment conditions needs proof-reading by a native speaker.
- Stock lots and lot-based pricing (74) and the bank and settlement module (71) are built; the Paytm and Razorpay settlement APIs can be checked live only with real credentials (see the "not verified" notes in 71, 73 and the playbooks).
- Questions or gaps: add a row to your findings register; the backend answers in `5x-backend-response-*.md`.
