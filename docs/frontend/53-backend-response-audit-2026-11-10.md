# 53. Backend response to the frontend audit of 2026-11-10

This answers every new row of "Backend gaps found by the frontend audit": the GST and HSN block (G-1 to G-10, which also covers N-2, N-3, N-4 and N-9) and N-1 to N-23. **Fixed** = changed in the code with tests. **Doc fixed** = the guide was wrong. **Clarification** = intended behaviour, explained. All money is paise. Guides to retest against: 75 (GST master), 20 (online GST, Addendum 10), 30 (Addenda 8 to 10), 70, 71, 72, 74, playbooks P1 to P5.

Retest on a build that contains the migrations V122 to V165 (they run at start-up). The GST master seed and the decimal rate change are in V122 to V124.

## 1. GST and HSN

| ID | Verdict | What changed |
|---|---|---|
| G-1 read endpoints | **Fixed** | `GET /gst/suggest`, `/gst/hsn`, `/gst/hsn/{hsn}`, `/gst/rates` exist, open to ADMIN and CASHIER. ETag and 304 on the last three, none on `/gst/suggest`. Shapes follow the handover contract (section 8 and 9 corrections win). Guide: file 75. |
| G-2 master and matcher | **Fixed** | V122 and V123 load 564 HSN rows, 5,950 item names and 12 rate-card rows; the index is held in memory, rebuilt after an edit and checked every 60 seconds on other servers. The matcher passes all 39 table cases and the 13 others; the 22 named cases of the test plan pass; a suggestion answers in well under a millisecond (p95 0.29 ms over 1,000 calls) and makes no database query. |
| G-3 fill blanks on the server | **Fixed** | `PUT /admin/products/{id}/pos` (only for a product with no saved counter settings), purchase lines and `GET /admin/purchases/lookup` fill a blank rate or HSN from the master when the match scores 0.6 or more. A value you send always wins and a typed `0` is not blank. A saved choice is never overwritten. Responses say where the value came from (`gstSource`: USER, MASTER, STORED, DEFAULT) and `gstVerification`; a fill from a "verify" row adds a purchase warning. A purchase line never writes back to the product. |
| G-4 blank versus zero | **Fixed** | `gstPercent` and `hsn` are optional on the product counter PUT. Left out, or null, keeps the stored value. HSN must be 4, 6 or 8 digits (a blank or whitespace value is treated as not sent). |
| G-5 current-law rates | **Fixed** | Accepted rates are 0, 0.25, 1.5, 3, 5, 12, 18, 28 and 40 (anything else: 400 `fields.gstPercent` "must be one of 0, 0.25, 1.5, 3, 5, 12, 18, 28 or 40"). Stored as `numeric(6,3)` in V124 (about 0.15 s on the 38k-line test table). The GST split works in thousandths of a percent with integer arithmetic. Whole-number bills are identical to the paisa: a test compares the old and new calculation over every amount from 0 to 100,000 paise at all six old rates, 300,000 random amounts, and 50,000 random baskets with discounts, coupon shares and round-off. Read `gstPercent` as a number; it prints as `18`, `1.5`, `0.25`. `freightGstPercent` stays a whole number. |
| G-6 GST by slab online | **Fixed** | `POST /cart/quote` lines carry `gstPercent`, `hsn`, `taxablePaise`, `cgstPaise`, `sgstPaise`, `gstConfigured`; the quote and order views carry `taxableTotalPaise`, `cgstTotalPaise`, `sgstTotalPaise`, `taxTotalPaise` and `gstSlabs[]` (one entry per rate). The split is saved on the order lines when the order is placed (V150), so a later rate change never alters an old order. Old orders show `gstRecorded: false`, null figures and `gstSlabs: []` (nothing is guessed). The delivery fee is outside the slabs and shown separately; whether GST applies to delivery is for your accountant. Online returns do not produce a GST document yet (refund amounts are unchanged). Guide: file 20, Addendum 10. |
| G-7 counter quote | **Fixed** | `POST /pos/quote` lines carry `taxablePaise`, `cgstPaise`, `sgstPaise` and the bill carries `gstSlabs[]`, taken from the same calculation as the finished sale (a test asserts the quote equals the saved sale). Guide: file 30, Addendum 10. |
| G-8 admin product list | **Fixed** | `GET /admin/products` rows and `GET /admin/products/{id}` carry `gstPercent`, `hsn` and `gstConfigured` (read for the whole page in one query; not cached, so a change shows at once). Create, update and patch still return the plain product. |
| G-9 security | **Fixed** | `/api/v1/gst/**` reads are ADMIN and CASHIER only (customer 403, no token 401); the admin edits are ADMIN only. |
| G-10 admin edit of master rows | **Fixed** | `POST /admin/gst-master/rows`, `PUT .../{rowId}`, `DELETE .../{rowId}` (switch off, 204; a PUT of a switched-off row switches it back on). All field errors together; every edit writes the audit log and the old and new values; it shows in `/gst/suggest` at once on that server and within 60 seconds on others. Keywords are add-only. |

**Data quality (your CA).** We did not change the data. 162 of the 564 rows are "verify"; HSN 8306 keeps both rows (18% and 5%); five product names map to two HSN codes; the files contain no Gujarati script, so Gujarati matching works only after Gujarati names are added to the keyword file; add plain single-word rows such as "atta" if short words match badly. These are listed in the "Before go-live" section of file 75. Because a filled rate is written silently, have the CA review the "verify" rows before go-live.

## 2. N-1 to N-23

| ID | Verdict | What changed |
|---|---|---|
| N-1 weighed purchase cost | **Fixed** | `items[].unitCostPaise` is a decimal (0 to 100,000,000, at most 4 decimals): 12 kg at Rs 55/kg is `qty: 12000, unitCostPaise: 5.5`, a line of 66,000 paise. The line gross is `qty x cost` rounded half-up once per line; whole-number costs give the same results as before (comparison test). Responses print `55` and `5.5`, not `55.0000`. No discount trick is needed any more. Rule documented in file 70 (V130). |
| N-2, N-3, N-4, N-9 | **Fixed** | See G-5, G-6, G-7, G-8. |
| N-5 one coin rule | **Fixed** | Activating a rule switches every other off, in one transaction under a lock; a database index guarantees one active rule. There is no 409: after any create or update with `active: true`, reload `GET /admin/coin-rules` (the previously active rule shows inactive). New rules are active unless sent `active: false`. Existing data was repaired in V140 (the most recently updated active rule stays). |
| N-6 shop settings | **Fixed** | `PUT /admin/shop-settings`: an omitted or null key keeps the stored value; `""` clears a text field (for `stateCode`, `invoicePrefix`, `weighedPrefix` it resets to 24, VSM, 2); `shopName` stays required. Documented in file 20. |
| N-7 supplier e-mail and pincode | **Fixed** | Optional `email` (max 254) and `pincode` (6 digits) on supplier body and view; search also matches the e-mail (V131). |
| N-8 IFSC | **Fixed** | Optional `ifsc` (`AAAA0XXXXXX`, upper-cased) on money accounts, BANK accounts only (V132). |
| N-10 lot to purchase | **Fixed** | Lot rows carry `purchaseId`, `invoiceNo` and `supplierName` (null for opening and adjustment lots). |
| N-11 credit notes | **Fixed** | `GET /admin/suppliers/{id}/credit-notes` (paged, newest first) with `amountPaise`, `usedPaise`, `availablePaise`; the same rule the credit-use check applies. |
| N-12 config keys | **Fixed** | `barcode.storePrefix`, `barcode.variableMeasure.enabled`, `barcode.variableMeasure.prefix` and `label.dpi` are on the configuration screen in a new group "Barcodes and labels" and take effect without a restart. |
| N-13 inbox paging | **Fixed** | `GET /me/notifications` returns `totalExact` and `hasMore`. |
| N-14 return preview | **Fixed** | Both previews add `coinsClawedBack`, `coinsRestored` (counts and paise values); the counter preview also adds `creditAdjustmentPaise` and `payOutPaise`. They come from the same planning code as the real return; tests prove preview equals result for online and counter under KEEP and CLAWBACK. |
| N-15 credit customer name | **Fixed** | `name` falls back to the masked mobile, then "Customer"; never null. |
| N-16 freeQty text | **Fixed** | "paid plus free must be at most 1000000". |
| N-17 refund mode | **Fixed** | `CREDIT_NOTE` as a refund mode answers 400 `mode` "CREDIT_NOTE is not allowed for refunds"; other bad modes list the allowed ones. |
| N-18 file 72 section 3.2 | **Doc fixed** | `soldBy` is always required on the product counter PUT; `gstPercent` and `hsn` are optional. Barcodes-only example added. |
| N-19 playbook P5 | **Doc fixed** | Two lots of one product need two purchases (a purchase refuses the same product twice). |
| N-20 CSV import options | **Clarification** | The options are `@RequestParam`: the server accepts them in the query string and as multipart form fields (our tests send them as multipart and it works). P4 and file 71 now say both work. If you still get "no column named date" with multipart fields, send them in the query string and give us the trace id. |
| N-21 refunds status text | **Doc fixed** | Short lists read "must be A, B or C" (here `must be PENDING, PROCESSING, ISSUED or FAILED`), long lists "must be one of ...". File 00 says so. |
| N-22 push messages | **Doc fixed / verified** | Unknown audience: `details.fields.audience` "must be one of ALL, CUSTOMERS, STAFF, ADMINS, CASHIERS, USERS, SEGMENT". An empty segment reports `name` and `rules` together; a preview with `{}` says "give a segmentId or a segment (match and rules)". Tests added. If your server still shows the older texts it is running an older build. |
| N-23 money account entries | **Fixed** | A bad entry reports `direction`, `amountPaise` and `note` errors together. |

## 3. Section D

- **Dev payment channels:** the dev profile now seeds two manual accounts, "Counter UPI" and "Card machine" (identifier DEV0001), so card and UPI buttons show on a dev server (never in production).
- **Paytm, Razorpay, FCM, bank statements:** still need real accounts and files; the guides carry "Before go-live" lists.

## 4. Please retest

G-1 to G-10 (especially the blank-versus-zero cases of file 75 and the 40, 1.5, 0.25 rates end to end), N-1 with a weighed purchase, N-5 (two admins activating different rules), N-6 (partial body), N-14 (preview versus real return), and the seeded dev channels.
