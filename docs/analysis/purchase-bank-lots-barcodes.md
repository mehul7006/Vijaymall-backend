# Analysis: purchases, bank and settlement reports, stock lots and prices, barcode labels, other POS options

Date: 2026-11-09. Scope: what the backend does today (read from the code at commit `69172c8`), what is missing, the design we recommend, and the decisions we need from the owner. Nothing here is implemented yet. Money is paise.

## 1. What exists today

### Purchases (`purchasing` module, migration V12, V32)
- A purchase is one document: `supplier_name` (free text), `invoice_no`, `note`, `total_cost_paise`, created by and at, and lines of `(product, qty, unit_cost_paise)`. Receiving adds stock (reason `PURCHASE`) once per idempotency key. Since round 4 a repeated supplier invoice number returns a warning.
- **Not there:** supplier master (GSTIN, state, terms), invoice date, per-line discount, GST rate and amount, CGST/SGST/IGST split, cess, freight or other charges, round-off, MRP and selling price on the line, HSN, batch number and expiry, payment status (unpaid, partial, paid), payment records, due date, purchase return (debit note), purchase order.
- **Cost is stored and used nowhere else.** No sale line, online order line or report reads the cost, so there is no margin, no cost of goods sold (COGS), no stock valuation.

### Payments and "bank"
- Online payments: a vendor-neutral `PaymentGateway` port (create, session, parse webhook, refund, find refund, fetch status) with a Razorpay implementation and a COD one. The `payment` table keeps provider, method (a plain word), amount, status, provider order id and reference.
- **Not there:** the payer's detail (UPI id, bank, card network and last four digits), the provider's fee and the GST on that fee, the settlement the money arrived in, the UTR, the settlement date, any "money account" concept (which bank account or wallet holds the money), reconciliation, or a settlement report. Counter UPI and card payments are recorded as a mode only, with no reference and no account.
- Razorpay itself does offer what we need: a settlements list, a per-settlement reconciliation report (each payment, refund and adjustment with the settlement id and UTR, fee, tax, method), a `settlement.processed` webhook and a payment fetch with method, bank/VPA and card network. Their reconciliation report fields should be checked against the current API reference before building ([recon](https://razorpay.com/docs/us/api/settlements/fetch-recon/), [settlement entity](https://razorpay.com/docs/us/api/settlements/entity), [fetch all](https://razorpay.com/docs/us/api/settlements/fetch-all), [webhooks](https://razorpay.com/docs/us/webhooks/settlements/?preferred-country=US)). Availability and plan limits depend on the owner's Razorpay account and must be confirmed with them.

### GST on sales
- Counter bills carry per-line `gst_percent`, HSN, taxable value, CGST and SGST, and an optional round-off to the rupee. There is no IGST (inter-state), no cess, and no GST summary or return-ready export.

### Stock and prices
- One quantity per variant. No lots or batches, no expiry. One selling price and one MRP per variant. A price change applies to all stock at once.

### Barcodes
- Several barcodes per variant plus a short PLU code; scanning and search work. There is no label printing and no generator for items without a barcode.

## 2. Purchases v2: the recommended document

**Supplier master.** `supplier`: name, GSTIN, state code, phone, address, payment terms in days, active flag, opening balance. Purchases refer to a supplier id (the old name text stays for history).

**Purchase header.** Supplier, invoice number and date, received date, place of supply (decides CGST+SGST or IGST from the two state codes), document discount (amount or percent), freight and other charges (with their own GST rate), round-off, notes, attachment (photo or PDF of the invoice), status.

**Purchase line.**
| Field | Meaning |
|---|---|
| quantity, free quantity | free units cost nothing but add stock; used in the landed cost |
| unit cost before tax | as printed on the invoice |
| line discount (percent or amount) | applied before tax |
| taxable value | (qty x unit cost) - discount |
| GST percent, CGST, SGST or IGST | from the product's rate unless overridden |
| cess percent and amount | optional per line |
| line total | taxable + tax + cess |
| landed unit cost | (taxable + non-creditable tax + share of freight) / (qty + free qty); creditable GST is not part of cost for a regular dealer |
| MRP, selling price, HSN, batch number, expiry | optional; "apply to variant" switches decide whether they update the variant |

**Rounding rules (so admins see the same numbers everywhere).** Money totals are exact paise. Unit costs are stored with 4 decimals (a unit cost of 33.3333 is real when a case is divided), and the app shows them in the admin's chosen rounding: exact, nearest paise, or nearest rupee. The document round-off is stored as its own line, like the counter bill. The report shows both the exact and the rounded price per product.

**Payments against a purchase.** `purchase_payment`: date, amount, mode (cash, UPI, card, bank transfer, cheque), reference (UTR or cheque number), the money account it was paid from (section 3), note. Status is derived: `UNPAID` (no payments), `PARTIAL` (paid below total), `PAID`, and `OVERDUE` as a flag when the due date has passed and the status is not paid. The admin can add several payments, edit the due date, and mark paid with one tap (creates the payment for the balance). Overpayment is refused, or kept as supplier advance if we choose to model it.

**Supplier ledger.** Purchases, payments, debit notes and opening balance give "what we owe each supplier", ageing (0-30, 31-60, 61+ days), and a supplier statement.

**Purchase return (debit note).** Return goods to a supplier: reduces stock, reduces what we owe, keeps GST reversal. This is separate from the existing "return to supplier" in defective stock, which can later post here.

**"Purchase online by card".** We recommend recording only: the admin pays on the supplier's site or machine and records the payment with mode CARD, the card or account it went from and the reference. Paying suppliers from inside the app (RazorpayX payouts or similar) is a separate regulated project; we do not recommend it now.

**Reports.** Purchase register (by date, supplier, product, GST rate), input GST summary by rate (creditable tax for the return filing), discount summary, payables and ageing, price history per product and supplier (cost trend), last purchase cost on the product screen.

**Size.** Large: about 2 weeks of backend plus tests and the guide.

## 3. Bank module: where did the money go

### 3.1 Money accounts
`money_account`: name ("HDFC current ...1234", "Razorpay settlement", "Counter UPI (PhonePe business)", "Card machine (Bank X)", "Cash drawer"), kind (`BANK`, `GATEWAY`, `UPI`, `CARD_TERMINAL`, `CASH`), provider, last four digits or VPA, settles-to account (a `GATEWAY` or `CARD_TERMINAL` settles into a `BANK`), active flag. The admin creates these once.

Every incoming and outgoing payment gets a `money_account_id`:
- online payments: the gateway account (settles to a bank account);
- counter UPI: the account of the QR the cashier used; the cashier picks the QR (one or several) when taking a UPI payment;
- counter card: the terminal;
- purchase payments: the account they were paid from.

### 3.2 What we can and cannot see
| Channel | What we know | How we reconcile |
|---|---|---|
| Online UPI/card via Razorpay | Everything: method, UPI id or card network, fee, tax, settlement id, UTR | Automatic through the provider's settlement API and webhook |
| Counter UPI to a plain QR | Only what the cashier enters | The cashier can enter the UPI reference; true confirmation needs a bank statement import or a dynamic QR from a provider. A personal or plain business QR has no API that tells us the money arrived |
| Counter card swipe | Mode, amount, optional approval code | The acquirer's settlement statement; import a CSV |
| Bank | Nothing automatic | Optional CSV or statement import matched by UTR and amount; bank APIs or Account Aggregator are a later step |

We recommend: auto for gateway money, manual capture plus statement import for the rest, and a clear "unreconciled" list so nothing hides.

### 3.3 Vendor-neutral design (same style as `PaymentGateway`)
- New port `SettlementProvider`: `listSettlements(from, to)`, `settlementDetail(settlementId)` returning items (payment or refund id, type, gross amount, fee, tax, net, method, settled at, UTR), `fetchPaymentDetail(providerRef)` (method detail: bank, UPI id, card network and last four). Implementations: Razorpay now; Cashfree, PhonePe or Paytm later without touching the rest of the app.
- Tables: `settlement` (provider, provider settlement id, UTR, gross, fee, tax, net, status, settled at, money account), `settlement_item` (settlement, payment id or refund id, amount, fee, tax, type), and extra columns on `payment` for method detail, fee, tax, net, settlement id, money account.
- A scheduled job pulls yesterday's and pending settlements, and the `settlement.processed` webhook triggers an immediate pull. Idempotent by provider settlement id.
- Our `payment.status = CAPTURED` but no settlement yet is the "expected, not yet received" bucket; a settlement item with no matching payment (or an amount mismatch) is an exception raised to the admin, like the existing payment anomaly queue.

### 3.4 The reports you asked for
1. **Receipts by account**: for a date range, total received by money account and by method (UPI, card, cash), with counts, refunds and net.
2. **Settlement report**: each settlement with UTR, date, gross, fee, tax, net, the bank account it landed in, and the payments inside it.
3. **Unsettled and exceptions**: captured payments not yet settled (with expected date), unmatched settlement items, differences.
4. **Fees**: gateway fee and GST on the fee per month (the GST on fees is input credit).
5. **Bank book / day book**: every inflow and outflow per account with running balance, including purchase payments and expenses (section 7).
6. **Payer detail on an order**: UPI id or card network and last four digits (never a full card number), shown to admin only.

**Size.** Large: about 2 to 3 weeks including the Razorpay implementation and reports. First confirm with Razorpay that the settlement and reconciliation APIs are enabled on the account.

## 4. The "10 at 100 and 10 at 120" question

First, which price do you mean? There are two different problems and they need different answers.

**A. Cost (what you paid): 10 units at 100, then 10 at 120.** You need this to know profit and stock value. The standard answer is **stock lots with first-in-first-out costing**:
- Every purchase line creates a lot: variant, quantity received, quantity left, unit cost, received date, optional batch number, expiry, MRP.
- Every sale (counter or online order) takes from the oldest lot first and records the lot's cost on the sale line. That sale line is "cost 100", the next "cost 120", and a sale that spans both is split in the line's cost record.
- Result: margin per bill, cost of goods sold, stock valuation, and "this bill's cost" exactly. The alternative, a weighted average (here 110 after both purchases), is simpler but cannot tell you which bill carried which cost. We recommend FIFO lots, with the weighted average shown as a convenience figure.

**B. Selling price: do old and new stock sell at different prices?** Three cases:
1. **Same selling price for both lots** (most common): nothing changes for billing. Only the cost differs (case A). The selling price stays on the variant.
2. **New price for new stock, but the old stock sells at the old price until it is gone**: needs lot-level selling price. At billing the cashier scans one barcode; the system sells from the oldest lot and uses that lot's price. When the old lot runs out the price rolls over to the next lot's price, and the till shows a notice ("next unit is 120"). 
3. **MRP printed on each pack differs** (the legal case in India: you cannot charge above the MRP printed on the pack): the lot's MRP caps the price for units from that lot. This is why case 2 matters for many items.

**Online orders.** The shop view shows one price, the price of the oldest lot with stock. An order that takes units from two lots with different prices is split into two lines (for example "2 x 100" and "1 x 120"), priced at placing the order, with the quote showing the split before the customer pays. Alternatively we can block mixing: the app shows the old price only up to the old lot's quantity.

**The options you described, compared.**
| Option | What it is | Verdict |
|---|---|---|
| Set price when adding stock (purchase or inventory screen) | "New selling price" on the purchase line, with a switch: update the variant now, or from the next lot | **Yes, do this first.** It is simple and covers case B1 and the common B2 flow |
| Separate variant per price | A second variant for the 120 pack | Works for fixed, visibly different packs; breaks when the barcode is the same, so not a general answer |
| Lot-level price with automatic rollover | The design above | **Phase 2**, only for items flagged "lot priced" (MRP varies per lot), so ordinary items stay simple |

**Recommendation.** Phase 1: lots with FIFO cost for every item, selling price set on the purchase line with "update variant price" choice, cost on every sale line, margin reports. Phase 2: per-lot MRP and price with rollover for flagged items, including the online split lines. Offline counter sales work with lots because the till already syncs the item list: it needs the lot split sent down with the item (oldest lot's price, quantity left in it).

**Size.** Phase 1 medium to large (it touches inventory, orders and POS sale lines); phase 2 large. This is the riskiest change in the system because every stock movement touches it, so it needs its own migration plan (opening lots built from current stock at the latest cost).

## 5. Barcode labels

Admin and staff print labels from a variant in the inventory screen.

**API (vendor-neutral, same standards as the rest)**
- `POST /api/v1/admin/labels` with variants, copies per variant (or "one per unit in stock", or "units received on this purchase"), and a template. Returns a PDF for A4 sheets and browsers, or printer language (TSPL, ZPL or ESC/POS) for thermal printers, or a JSON description the Flutter app renders for a Bluetooth printer.
- Templates: size (for example 50x25 mm, 38x25 mm, 100x50 mm, A4 sheets of 3x8 or 4x12), fields on or off (shop name, product name in English or Gujarati, pack size, MRP, selling price, packed on, best before, batch, price per kg), barcode type (EAN-13, Code 128, QR), and a font size choice. Templates are saved so staff pick one by name.
- **Barcode source.** Use the variant's own barcode if it has one. For a variant with none, generate a store-internal EAN-13 in the "in-store" prefix range (for example starting 2) and save it as the variant's barcode so scanning works at the counter. Weighed items use the standard variable-measure barcode (item code plus weight or price) so a label printed from the scale scans at the till.
- **Print history:** who printed what and when (audit), so repeated reprints are visible.
- **Options to confirm:** printer make and connection (Bluetooth thermal from the phone, USB or network label printer, or ordinary A4 printer).

**Size.** Medium: about 1 week. Libraries for barcode images (ZXing) and PDF output are standard.

## 6. Other POS options we recommend considering

Ranked by value for a supermarket. "Have" means it exists today.

| Option | Why it matters | Status | Priority |
|---|---|---|---|
| Stock lots, cost on sale lines, margin report | Profit by bill, item, category | Missing (section 4) | High |
| Supplier master, payables, ageing | Know what you owe | Missing (section 2) | High |
| Purchase GST and input credit report | Filing returns | Missing | High |
| Money accounts, settlement reconciliation | Know where money is | Missing (section 3) | High |
| Expiry and batch tracking, near-expiry alerts, FEFO picking for dated goods | Waste, food safety | Missing | High for groceries |
| IGST and cess on sales, HSN-wise GST summary, return-ready exports | Compliance for inter-state and cess goods | Partial (CGST/SGST only) | High |
| Barcode label printing and generated barcodes | Counter speed | Missing (section 5) | High |
| Purchase orders and reorder suggestions | Replenishment from low stock, supplier lead time | Low-stock alerts only | Medium |
| Purchase returns (debit notes) | Returning goods | Missing | Medium |
| Day book, cash book, expenses (rent, power, wages) | Net profit, not only gross | Cash-up per shift only | Medium |
| Scheduled price changes, bulk price update, price history | Safe repricing | Missing | Medium |
| Weighing scale integration and loose-item variable barcodes | Fruit, grains | Weight lines exist; no scale integration | Medium |
| Customer price tiers (wholesale, staff), quotations, delivery challans | B2B customers | Missing | Medium |
| E-invoice for B2B above the turnover threshold, e-way bill | Compliance if you cross the thresholds | Missing | Check with your accountant |
| E-receipt by SMS or WhatsApp | Paper saving | Missing | Low |
| Stock transfer between stores, multiple stores | Growth | Single shop | Later |
| Dead-stock and ABC (fast and slow movers) analysis | Buying decisions | Missing | Low |
| Loyalty, coupons, coins, credit (udhaar), returns, shifts, offline billing, holds | | Have | |

## 7. Suggested order of work
1. **Foundation (do together):** supplier master, purchase v2 with GST, cess, discount and payments, stock lots with FIFO cost and cost on sale lines. Needed by almost everything after it.
2. **Money:** money accounts, tagging counter payments, settlement port with Razorpay, receipts-by-account and settlement reports.
3. **Labels:** barcode label API and templates.
4. **Price lots (phase 2):** lot-level MRP and price rollover for flagged items, with online split lines.
5. **Then:** expiry tracking, purchase returns and orders, IGST/cess/HSN reports, expenses and books.

Each step ships with the frontend guide section, tests and the performance checks we used so far, so the Flutter team can build in parallel from the guide.

## 8. Decisions we need from the owner
1. **The 100/120 question.** Is it your cost, or does the selling price also differ per lot (different MRP printed on the pack)? That decides whether phase 2 is needed or only phase 1.
2. **GST status.** Regular dealer (claims input credit) or composition? Do you buy from other states (IGST)? Any cess goods (aerated drinks, tobacco)?
3. **Supplier payments.** Record only (recommended), or also pay from the app?
4. **Bank and UPI set-up.** How many bank accounts and UPI QR codes, which card machine or acquirer, and is Razorpay's settlement API enabled on your account?
5. **Label printer.** Which make and size, and from where do staff print (phone, counter PC)?
6. **Loose and weighed items.** Do you use a weighing scale that prints barcodes?
7. **Priority and timing.** Confirm the order in section 7, or move something earlier.
