# Credit and hybrid payments (shop counter only)

Customers can buy on **credit** (udhar) or pay a bill **partly on credit** ("hybrid"). This exists only at the
shop counter (`/pos/**`). Online checkout still accepts only `COD` and `ONLINE`; `CREDIT` or `HYBRID` there is refused with
"credit and hybrid payment are only available at the shop counter". Migration: `V8__credit.sql` (was V7, which clashed with `V7__variants_and_verified_reviews.sql`).

## How it works

- **A fourth payment line, `CREDIT`.** `POST /pos/sales` already takes several payment lines that must add up to the bill.
  - Full credit: one `CREDIT` line for the whole bill.
  - Hybrid: a `CREDIT` line plus any `CASH` / `UPI` / `CARD` lines. The remaining amount can be completed with any available mode.
  - At most one `CREDIT` line per bill. Cash tendered and change still work on the cash share.
- **Needs a customer.** `cart.customerMobile` is required when any part is on credit (400 otherwise).
- **Needs approval.** Every customer has a credit limit, default **0 = credit not allowed**. The admin approves a customer by mobile
  and sets how much they may owe. A bill that would pass the limit is refused with `CREDIT_LIMIT_EXCEEDED`
  (details: `limitPaise, balancePaise, availablePaise, requestedPaise`); an unapproved or blocked customer gets `CREDIT_NOT_ALLOWED`.
  A refused bill takes no stock, coins or coupon.
- **Bill type.** Each bill is stored and returned with `paymentType`: `NONE`, `CASH`, `UPI`, `CARD`, `SPLIT` (several non-credit
  modes), `CREDIT` or `HYBRID`.
- **On the customer's account.** The balance is a running total (khata), not tied to single bills. Every change is a ledger line:
  `SALE`, `PAYMENT` (repayment), `RETURN`, `ADJUST`. Customers see their balance, limit and ledger in the app.
- **Repayment.** The customer pays some or all of what they owe at the counter, in `CASH` / `UPI` / `CARD` (split allowed).
  A receipt number `<prefix>-CR/<fy>/<n>` is issued. Paying more than they owe is refused (`CREDIT_OVERPAYMENT`).
- **Returns.** Goods returned from a bill that had credit first cancel what the customer still owes for that bill; only the rest
  is paid out. `creditAdjustedPaise` on the credit note says how much. If you send explicit `refunds`, they must add up to the
  part that is paid out (the response message says how much was taken off credit). Refunds can never be `CREDIT`.
- **Cash-up.** Cash received as repayment is added to `cashExpectedPaise`. `paidByMode` stays money actually received
  (credit is not in it); credit is shown on its own lines.

## Endpoints

| Who | Endpoint | Purpose |
|-----|----------|---------|
| Cashier, admin | `GET /pos/credit/account?mobile=` | balance, limit, available (404 if the shop never saw this number) |
| Cashier, admin | `POST /pos/credit/payments` (+ `Idempotency-Key`) `{customerMobile, payments:[{mode,amountPaise,reference}], note}` | take a repayment |
| Cashier, admin | `GET /pos/credit/ledger?mobile=&page=&size=` | statement |
| Cashier, admin | `POST /pos/sales` with a `CREDIT` payment line | sell on credit / hybrid |
| Admin | `PUT /admin/credit/limit {mobile, limitPaise}` | approve a customer (creates them if new); 0 switches credit off |
| Admin | `GET /admin/credit/accounts/{id}`, `.../ledger`, `PUT .../limit {limitPaise}`, `POST .../adjust {amountPaise, note}` | manage one account. `amountPaise < 0` writes off, `> 0` adds; a note is required |
| Admin | `GET /admin/credit/accounts?outstandingOnly=true&page=&size=` | who owes what, biggest first |
| Admin | `GET /admin/reports/credit?period=week\|month\|quarter` or `?from=&to=` | credit report (below) |
| Customer | `GET /credit`, `GET /credit/ledger?page=&size=` | their own balance, limit and history |

## Reports

- **Credit report** (`/admin/reports/credit`): `outstandingPaise`, `customersWithDues`; for the period `givenPaise` (put on accounts),
  `returnedPaise` (cancelled by returns), `recoveredPaise` (customers paid), `writtenOffPaise`, `addedPaise`; `recoveredByMode`
  (CASH/UPI/CARD); `salesByPaymentType` (bills and value per type); `byDay` (given and recovered); `topDebtors` with limit,
  last sale and last payment.
- **Sales summary** (`/admin/reports/summary`) now also has `creditGivenPaise`, `creditRecoveredPaise`, `outstandingCreditPaise`.
- **Cash-up** (`/pos/reports/day`) now also has `creditGivenPaise`, `creditReturnedPaise`, `creditRecoveredPaise`,
  `creditRecoveredByMode`.

## Not built (say if you want any of them)

Due dates, interest or late fees, ageing buckets, SMS or push reminders for dues, a printable repayment receipt (the data is
returned; the till text receipt prints a `CREDIT` line like any other mode), and a default limit for all customers.
