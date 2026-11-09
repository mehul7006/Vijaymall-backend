# Paytm wireless swipe machine: what the documentation allows and how we use it

Date: 2026-11-09. Source: Paytm's public developer documentation (paytmpayments.com), read page by page. **Reliability:** most page bodies reached us only as summaries, so every API detail below is "as documented, to be verified with the sandbox before go-live". Items marked **UNCONFIRMED** must be confirmed with Paytm. Nothing here has been run against a real Paytm account yet.

## 1. How the wireless (cloud) machine works

The billing system sends a payment request to Paytm over the internet; Paytm routes it to the specific machine ("wireless ECR", also called cloud ECR). The machine shows the amount, the customer pays by card or UPI QR on it, and the result comes back through Paytm. There is no cable and no local app link. Paytm also has other modes (USB/wired, Bluetooth, Android app-invoke, and a "payment request" mode where the cashier enters a 6-digit request id on any machine of the same merchant id). We recommend the wireless sale mode because the request goes straight to one named terminal, and the cashier does not type anything.

## 2. APIs we can use

| Purpose | Documented call | Key fields | Notes |
|---|---|---|---|
| Send the bill amount to the machine | `POST /ecr/payment/request` (Sale API) | `paytmMid`, `paytmTid` (the machine), `merchantTransactionId` (8-32 alphanumeric, unique), `transactionAmount` (paise), `transactionDateTime`, optional `merchantReferenceNo`, `displayInfo` (up to 3 lines on the machine), `printInfo`, `gstInformation`, `merchantExtendedInfo` (`autoAccept`, `paymentMode` ALL/CARD/QR), `timeoutConfig` (1-60) | Signed with `head.checksum`; `head.channelId` issued by Paytm. Immediate answer only says accepted (code 0009) or rejected (for example terminal not active, another request already open on that terminal, duplicate transaction id) |
| Read the result | `POST /ecr/V2/payment/status` | request: `paytmMid`, `paytmTid`, `transactionDateTime`, `merchantTransactionId`; response: `payMethod` (CREDIT_CARD, DEBIT_CARD, UPI, EMI ...), `cardScheme`, `issuerMaskCardNo`, `retrievalReferenceNo` (RRN), `authCode`, `issuingBankName`, `bankResponseCode`, `aid`, `acquiringBank`, `transactionAmount`, result codes 0000 success, 0010/0030 pending, 0011 failed, 0404 unknown id | Paytm advises the first check about 10 seconds after the sale request and then every 10 seconds. There is no webhook for this flow |
| Cancel a sale the same day | `POST /ecr/void` | `paytmTid`, `paytmMid`, `merchantTransactionId`, `transactionDateTime` | Same day only; the machine must be online and the cashier accepts the void on it; then check the status again |
| Refund on a later day | `POST /refund/apply`, `POST /v2/refund/status` (the general gateway refund API) | `mid`, `orderId`, `txnId`, `refId` (unique), `refundAmount` | **UNCONFIRMED:** how a machine sale maps to `orderId`/`txnId`. Until Paytm confirms, later-day refunds are recorded in our system and paid out through the Paytm dashboard, with the reference stored |
| Settlement and bank reconciliation | Settlement Detail / Transaction Detail / Order Detail APIs (`/merchant-settlement/...`), Settlement Summary, Settlement list API | per transaction: `payoutId`, `utrNo`, `amount`, `settledAmount`, `commission`, `gst`, `paymentMode`, `rrnCode`, `posId`, `extSerialNo` (machine serial), `maskedCardNo`, `cardNetwork`, `requestType` (for example UPI_QR_CODE) | Production only (not on staging); two different auth styles are documented (a checksum in the body for one API, a JWT/Bearer for the others): **UNCONFIRMED** details. Whether machine (card-present) and QR transactions appear in these APIs is **UNCONFIRMED** |
| Settlement files | Dashboard "Settlement Report", email or SFTP | columns: Transaction ID, Order ID, Transaction Date, Updated date, Transaction Type, Status, Amount, Commission, GST, Settled amount, Settled Date, UTR no., Split Flag, Payment Mode | Safe fallback that needs no API access: we import this file |
| Split settlement (post facto) | `POST /ecr/postFacto` | vendor splits | Not needed for this shop |

## 3. Signing the requests

Paytm signs every request with a checksum computed from the body and the merchant key (SHA-256 plus AES-128 according to the pages; the exact string, salt and encoding were not on the pages we could read). Paytm publishes an official Java checksum library (`com.paytm.pg:paytm-checksum:1.2.1`, licence not stated on the page, distributed from their own repository). **Decision for the adapter:** implement the documented algorithm ourselves in a small, fully tested class after reading the library source on GitHub (to avoid an unvetted dependency from a plain-HTTP repository), and test it against the published test vectors; if no vectors exist, verify in the sandbox before go-live. **UNCONFIRMED:** the separate `reqHash` used by the "payment request / CPay" variant.

## 4. What we build (on top of the generic ports)

Everything sits behind the ports the bank agent is building (`CardTerminal`, `SettlementProvider`); Paytm is one adapter.

1. **Terminals master** (`payment_terminal`): provider, terminal id (`paytmTid`), name ("Counter 1"), active, linked money account (the bank the card settlements land in). The cashier picks the terminal for a payment; if only one is active it is chosen automatically.
2. **Admin configuration** (encrypted like the Razorpay keys): environment (STAGING or PRODUCTION), base URL (defaults per environment, editable because Paytm's documentation shows several hosts), merchant id (MID), merchant key, channel id, optional client id for the settlement APIs, enabled flag, poll interval and timeout. A "test connection" button that checks that the signing works and the terminal id is accepted (using a status call for a non-existent transaction, which returns a known "not found" code when the signature is valid).
3. **Take a card or UPI payment at the till:** the till sends the amount to the chosen terminal (`autoAccept` true so the cashier does nothing on the machine, `paymentMode` ALL, the bill number in `displayInfo`/`merchantReferenceNo`). The server then polls the status every 10 seconds (and keeps polling in the background if the app closes), with a timeout, and finishes as paid, failed, cancelled or expired. The payment line stores the machine details: card network, masked card, RRN, auth code, issuing bank, UPI or card, terminal id. A pending payment survives an app crash; the till can resume it.
4. **Cancel before payment / void after:** cancel a pending request from the till; void a paid sale on the same day with the documented void call; later returns are refunded to the customer through the dashboard with the reference recorded (until the API mapping is confirmed), and the stock and bill are returned in our system as today.
5. **Reconciliation:** (a) API pull of settlement details when Paytm grants access, matched by RRN, transaction id or amount and date, storing UTR, commission, GST and net per transaction; (b) CSV import of the dashboard Settlement Report as the always-available path; both feed the same settlement tables and exceptions queue.
6. **Reports** (from the bank module): card/UPI machine receipts by terminal and day, settlements with UTR, fees (commission and GST), unmatched items.
7. **Safety:** one open request per terminal (Paytm rejects a second), idempotent transaction ids derived from our payment id, no secrets in logs, never store full card numbers, all timestamps handled in India time as the API requires, TLS 1.2 or higher.
8. **Testing without Paytm:** a local fake of the Paytm endpoints for unit and integration tests (accept, pending, success, failure, duplicate id, terminal inactive, timeout, void, invalid signature). Live verification waits for sandbox credentials.

## 5. What to ask Paytm (one email)

1. Enable **wireless / cloud ECR** (payment request over the air) on our merchant id and confirm our machine model supports it.
2. Test (staging) MID, merchant key, **channel id** and a staging terminal id; and the **production** base URL and our production terminal id(s).
3. How to sign: confirm the checksum rule for the Sale, Status and Void calls and whether the CPay `reqHash` is needed in our mode.
4. How to map a machine sale to the refund API (`orderId`, `txnId`) for later-day refunds, and whether a refund API can pay back card sales.
5. Access to the **Settlement APIs** (auth method and credentials), and whether machine card sales and QR sales appear in the Settlement Detail API (fields `posId`, `extSerialNo`, `rrnCode`).
6. The settlement cycle for the machine (T+n) and the format of the scheduled settlement report (email or SFTP).

## 6. Things Paytm's pages do not say (so the adapter makes them settings)

Expiry of a pending sale (only `timeoutConfig` 1-60 with no unit), exact production hosts for the machine APIs, rate limits, and whether the status call can be repeated freely. The adapter therefore uses conservative defaults (10-second polling, 3-minute total wait) that can be changed in configuration.
