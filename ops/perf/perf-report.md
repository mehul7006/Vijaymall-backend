# Vijay Super Mall backend: per-endpoint performance benchmark

## 1. Test environment and method

**Server under test.** Spring Boot 4.1.2, `dev` profile, running as a single JVM (OpenJDK 26.0.2.1, `-XX:MaxRAMPercentage=50`, so up to 8 GiB heap), Hikari pool of 20 connections for the first 95 cases and 10 connections (property file, dev profile) for the rest, listening on `localhost:8080` (management/Prometheus on 9090). Rate limits were lifted (100,000,000 per minute/hour).

**Database.** PostgreSQL 17.11 in a docker container (`mallpg`), seeded with about 183,000 orders (150,000 DELIVERED, 33,000 PLACED at the start), 20,000 products, 30,100 users, 21,000 counter bills. The DB is not reset between cases: every write case leaves its rows behind (orders, bills, products, coupons, flash sales, ...), so later cases see a slightly larger database than earlier ones.

**Hardware.** One Apple M1 laptop, 8 cores, 16 GiB RAM, macOS 26.6. **The application, PostgreSQL (in docker) and the load generator all ran on the same laptop**, so they compete for CPU and memory, and the load generator itself consumes CPU. Numbers are therefore pessimistic for the server in absolute terms and are not a capacity statement for production hardware; they are comparable to each other (same machine, same conditions, one endpoint at a time). Loopback networking, so there is no network latency. A short Maven/Testcontainers build ran on the machine before the run started; the run itself began after it had finished and nothing else heavy was started while it ran.

**Logging.** The `dev` profile sets `com.vijay.mall` to DEBUG (`application-dev.yml`), and `GET /admin/logs/status` reports `levelApp=DEBUG, levelRoot=INFO, levelSql=OFF`. So the run is NOT free of debug logging: the application writes DEBUG lines for its own code to the console log (about 1 million lines accumulated in `app.log` before the run). Production profile logging would be lighter.

**Load generator.** `ops/perf/EndpointBench.java` (single file, JDK `java.net.http.HttpClient`, HTTP/1.1 keep-alive, platform threads, no other dependency). Endpoints are benchmarked strictly one at a time. For each case:

1. warm-up: 4 users for 3 s, discarded;
2. run A: 1 virtual user for 5 s (uncontended latency);
3. run B: 16 closed-loop virtual users for 10 s, no think time (throughput under load).

Latency is measured per request from send to full response body (SSE: to first byte). Percentiles are exact (all samples kept, nearest-rank). TPS = timed requests / wall time of the window. Expected status codes are explicit per case; any other status, timeout (30 s) or exception counts as an error. Each case has a hard stop of window + 60 s.

**Data used.** 60 customer-visible products with stock topped up by 2,000,000 each (orders and bills never fail for stock); 24 fresh customers (each with an address and orders) for customer writes and 24 seeded customers with 5 to 12 past orders for customer reads (so reads run against realistic, not empty, histories); admin and cashier dev accounts; random seeded ids for admin reads (orders, customers, bills, products, groups). Writes that would collapse onto one row use unique values (fresh `Idempotency-Key` per call, random products, unique codes/e-mails). Updates cycle over 16 to 32 pre-created "bench" entities (one per virtual user at least).

**Prep for consuming endpoints.** Endpoints that consume something (cancel, reject, status change, returns, review, account/address/banner/unit/coupon/coin-rule/hold/flash-item deletion, shifts, defective-goods review/resolve, offline-exception review, logout, OTP verify, flash-sale start/end/cancel, ...) draw a fresh target from a pool prepared outside the timing before each run (warm-up pool 80, run A pool up to 700, run B pool up to 3,500; smaller for expensive preps). Only the call under test is timed. If a pool runs dry before the window ends, the run stops early, the case is flagged "POOL/RUN LIMITED" and TPS is computed over the time actually used.

**Request mix.** Where one endpoint has several realistic parameter shapes (page/search/filter, cached vs cache-defeating report), each shape is a separate row with the shape in the note. Reads of catalog lists use random pages/searches so they are not a single hot cache key.

**What the numbers do not say.** No tuning conclusions are drawn. Single run per case (no repetition), so differences of a few tens of percent between rows are within noise. Cached endpoints (categories, units, shop settings, dashboard, report summary, ...) measure the cache hit path.

**Which jar measured what (important).** The run was done in three stages on three builds of the application, all on the same database, port and `dev` profile:
- *Before* rows (`before-results.tsv`, 20 cases) were measured on jar 1ef9503 (the original commit), tables at seed size.
- The first 95 cases of the full list (up to `DELETE /admin/units/{code}`; file `bench-partial-1.tsv`) were measured on jar 96d2213 (product-list and order-count caches, stream/multipart/settings-race fixes). Hikari pool size was 20.
- The remaining 91 cases, plus a re-run of the 12 list cases whose code changed (admin customers, stock-movements, audit log, products, product-groups, admin/products, admin/orders variants, POS product search), were measured on the rebuilt jar from commits 334f873 + d9d08ed (exact bounded list totals, no TTL-cached totals; Hikari pool 10 in dev). For those 12 cases the re-run replaces the partial-1 numbers. All other cases from the first 95 are NOT re-measured on the final jar, so a few of their numbers (for example anything that waits for a DB connection) are not strictly comparable with rows measured on the final jar. The `GET /pos/holds` row reflects about 23,000 holds the benchmark itself created earlier in the same run (the endpoint returns every hold; capped at 100 afterwards in commit 52ab049, which is not in the measured jar).
- Application console logging for the final stages went to /dev/null, so no app-log error counts exist for them.

## 2. Results per endpoint

Times are milliseconds. `1u` = run A (1 virtual user, 5 s). All other columns = run B (16 closed-loop virtual users, 10 s, no think time). Requests/TPS count only the timed call under test (prep calls are excluded). Rows with a bracketed variant in the note are the same endpoint with a different parameter mix.

### Auth

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| POST | `/api/v1/auth/otp/send` | Public | 2.39 | 4.03 | 39300 | 3929.4 | 4.07 | 3.55 | 5.76 | 6.88 | 11.05 | 41.1 | 0 | 0.00 |  |
| POST | `/api/v1/auth/otp/verify` | Public | 2.80 | 5.68 | 3500 | 1973.6 | 8.08 | 7.53 | 10.75 | 12.40 | 16.56 | 32.8 | 0 | 0.00 | [existing user login; the code is sent in untimed prep] POOL/RUN LIMITED (A pool 700 B pool 3500, window 1.77s) |
| POST | `/api/v1/auth/otp/send-mobile` | Public | 0.96 | 2.24 | 47885 | 4787.8 | 3.34 | 3.10 | 4.24 | 4.70 | 7.51 | 30.3 | 0 | 0.00 |  |
| POST | `/api/v1/auth/otp/verify-mobile` | Public | 2.47 | 3.53 | 3500 | 1708.9 | 9.34 | 8.98 | 12.38 | 13.78 | 17.28 | 25.9 | 0 | 0.00 | [first login of a new mobile user; code sent in untimed prep] POOL/RUN LIMITED (A pool 700 B pool 3500, window 2.05s) |
| POST | `/api/v1/auth/refresh` | Public | 1.58 | 2.80 | 23991 | 2398.4 | 6.64 | 6.25 | 8.74 | 10.03 | 13.63 | 42.8 | 0 | 0.00 | [each user rotates its own refresh-token chain] |
| POST | `/api/v1/auth/logout` | Public | 1.08 | 2.05 | 3500 | 4104.2 | 3.89 | 3.57 | 5.27 | 6.21 | 8.50 | 11.9 | 0 | 0.00 | POOL/RUN LIMITED (A pool 700 B pool 3500, window 0.85s) |

### Profile

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| GET | `/api/v1/me` | Customer | 0.40 | 0.53 | 73282 | 7327.3 | 2.18 | 1.80 | 3.70 | 4.52 | 6.85 | 34.0 | 0 | 0.00 |  |
| PUT | `/api/v1/me` | Customer | 1.15 | 2.56 | 37458 | 3744.9 | 4.27 | 4.03 | 5.62 | 6.27 | 8.09 | 71.8 | 0 | 0.00 |  |
| GET | `/api/v1/me/addresses` | Customer | 0.30 | 0.37 | 118565 | 11854.8 | 1.35 | 1.21 | 1.94 | 2.43 | 4.02 | 30.8 | 0 | 0.00 |  |
| POST | `/api/v1/me/addresses` | Customer | 1.36 | 2.55 | 30607 | 3059.8 | 5.22 | 4.95 | 7.13 | 8.00 | 10.36 | 18.0 | 0 | 0.00 |  |
| PUT | `/api/v1/me/addresses/{id}` | Customer | 1.38 | 3.11 | 32260 | 3224.9 | 4.96 | 4.67 | 6.57 | 7.44 | 9.64 | 16.5 | 0 | 0.00 |  |
| DELETE | `/api/v1/me/addresses/{id}` | Customer | 1.16 | 1.93 | 3500 | 4056.5 | 3.94 | 3.78 | 5.14 | 5.71 | 7.29 | 11.6 | 0 | 0.00 | POOL/RUN LIMITED (A pool 700 B pool 3500, window 0.86s) |
| POST | `/api/v1/me/devices` | Customer | 2.72 | 3.69 | 39113 | 3910.3 | 4.09 | 3.83 | 5.52 | 6.23 | 7.98 | 20.9 | 0 | 0.00 |  |
| DELETE | `/api/v1/me` | Customer | 2.64 | 3.70 | 1500 | 1455.0 | 10.96 | 10.38 | 14.83 | 16.18 | 22.20 | 118.2 | 0 | 0.00 | [deletes a fresh account created in untimed prep] POOL/RUN LIMITED (A pool 300 B pool 1500, window 1.03s) |

### Notifications

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| GET | `/api/v1/me/notifications` | Customer | 0.46 | 0.63 | 73646 | 7363.6 | 2.17 | 1.92 | 3.32 | 4.15 | 6.35 | 98.8 | 0 | 0.00 |  |
| POST | `/api/v1/me/notifications/{id}/read` | Customer | 0.66 | 0.82 | 52631 | 5261.8 | 2.99 | 2.60 | 4.65 | 5.69 | 8.13 | 19.5 | 0 | 0.00 | [idempotent; one notification id per user] |
| POST | `/api/v1/me/notifications/read-all` | Customer | 0.42 | 0.56 | 79135 | 7911.8 | 2.02 | 1.75 | 3.19 | 4.09 | 6.16 | 15.7 | 0 | 0.00 |  |

### Wishlist

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| GET | `/api/v1/me/wishlist` | Customer | 0.31 | 0.39 | 114395 | 11437.7 | 1.40 | 1.28 | 1.96 | 2.37 | 3.78 | 31.1 | 0 | 0.00 |  |
| POST | `/api/v1/me/wishlist/{productId}` | Customer | 1.22 | 1.68 | 25702 | 2569.4 | 6.23 | 5.11 | 10.39 | 12.75 | 18.22 | 56.8 | 0 | 0.00 | [cycles 150 products per user: first passes insert, later passes are no-ops] |
| DELETE | `/api/v1/me/wishlist/{productId}` | Customer | 0.92 | 1.84 | 3500 | 5389.8 | 2.96 | 2.99 | 4.52 | 5.02 | 6.36 | 11.0 | 0 | 0.00 | [product saved in untimed prep] POOL/RUN LIMITED (A pool 700 B pool 3500, window 0.65s) |

### Wallet

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| GET | `/api/v1/wallet` | Customer | 0.41 | 0.57 | 80672 | 8066.4 | 1.98 | 1.77 | 2.97 | 3.71 | 5.58 | 56.1 | 0 | 0.00 |  |
| GET | `/api/v1/wallet/ledger` | Customer | 0.33 | 0.46 | 101212 | 10120.6 | 1.58 | 1.43 | 2.29 | 2.80 | 4.41 | 17.4 | 0 | 0.00 |  |

### Credit (customer)

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| GET | `/api/v1/credit` | Customer | 0.62 | 0.90 | 52380 | 5237.2 | 3.05 | 2.61 | 4.87 | 6.05 | 8.78 | 30.0 | 0 | 0.00 |  |
| GET | `/api/v1/credit/ledger` | Customer | 0.74 | 1.02 | 55675 | 5566.0 | 2.87 | 2.50 | 4.64 | 5.79 | 8.87 | 33.0 | 0 | 0.00 |  |

### Catalog

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| GET | `/api/v1/categories` | Public | 0.14 | 0.18 | 245562 | 24554.2 | 0.65 | 0.61 | 0.87 | 1.01 | 1.58 | 16.0 | 0 | 0.00 |  |
| GET | `/api/v1/products` | Public | 0.73 | 1.54 | 58919 | 5891.1 | 2.71 | 2.38 | 4.15 | 5.00 | 8.49 | 55.5 | 0 | 0.00 | [page 0-99, size 20] |
| GET | `/api/v1/products` | Public | 1.15 | 1.69 | 32694 | 3268.5 | 4.89 | 4.54 | 7.03 | 8.14 | 11.68 | 69.4 | 0 | 0.00 | [search q=Item <n>] |
| GET | `/api/v1/products` | Public | 0.45 | 0.54 | 65659 | 6564.7 | 2.43 | 2.24 | 3.78 | 4.36 | 5.89 | 23.1 | 0 | 0.00 | [by category, hot=false] |
| GET | `/api/v1/products/{id}` | Public | 0.79 | 1.02 | 42040 | 4202.7 | 3.81 | 3.27 | 5.93 | 7.34 | 10.65 | 42.6 | 0 | 0.00 |  |
| GET | `/api/v1/product-groups` | Public | 1.17 | 1.40 | 28978 | 2896.7 | 5.51 | 5.24 | 7.69 | 8.71 | 10.90 | 36.9 | 0 | 0.00 | [page 0-99, size 20] |
| GET | `/api/v1/product-groups/{id}` | Public | 0.76 | 1.02 | 44558 | 4454.4 | 3.59 | 3.09 | 5.66 | 6.99 | 10.24 | 29.8 | 0 | 0.00 |  |
| GET | `/api/v1/hot-sale` | Public | 3.32 | 4.09 | 13750 | 1374.2 | 11.64 | 10.72 | 17.42 | 20.16 | 26.74 | 51.8 | 0 | 0.00 |  |
| GET | `/api/v1/units` | Public | 0.14 | 0.19 | 243755 | 24374.5 | 0.66 | 0.62 | 0.88 | 1.00 | 1.47 | 10.3 | 0 | 0.00 |  |
| GET | `/api/v1/coupons` | Public | 0.14 | 0.18 | 243999 | 24398.6 | 0.66 | 0.62 | 0.89 | 1.02 | 1.52 | 9.5 | 0 | 0.00 |  |

### Reviews

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| GET | `/api/v1/products/{productId}/reviews` | Public | 1.21 | 1.56 | 26389 | 2638.0 | 6.06 | 5.29 | 9.50 | 11.40 | 15.84 | 36.1 | 0 | 0.00 |  |
| GET | `/api/v1/product-groups/{id}/reviews` | Public | 1.24 | 1.95 | 26863 | 2685.4 | 5.95 | 5.17 | 9.33 | 11.30 | 15.80 | 35.7 | 0 | 0.00 |  |
| GET | `/api/v1/products/{productId}/reviews/mine` | Customer | 1.23 | 1.92 | 28224 | 2821.7 | 5.67 | 4.90 | 8.85 | 10.80 | 15.31 | 32.2 | 0 | 0.00 |  |
| POST | `/api/v1/products/{productId}/reviews` | Customer | 2.53 | 3.43 | 1200 | 1334.1 | 11.95 | 11.36 | 16.37 | 18.39 | 24.29 | 37.2 | 1 | 0.08 | codes: 200x1499 409x1 ; e.g. 409 {"code":"ALREADY_EXISTS","message":"This already exists","traceId":"2f03847f","details":{}} [verified purchase: fresh delivered order made in untimed prep] POOL/RUN LIMITED (A pool 300 B pool 1200, window 0.90s) |

### Cart

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| POST | `/api/v1/cart/quote` | Customer | 0.66 | 0.91 | 49240 | 4923.2 | 3.25 | 2.83 | 5.08 | 6.23 | 8.81 | 21.8 | 0 | 0.00 |  |
| GET | `/api/v1/cart` | Customer | 0.27 | 0.35 | 131976 | 13196.5 | 1.21 | 1.09 | 1.76 | 2.17 | 3.47 | 18.7 | 0 | 0.00 |  |
| PUT | `/api/v1/cart` | Customer | 1.69 | 3.23 | 21533 | 2151.6 | 7.43 | 6.96 | 10.36 | 11.73 | 15.06 | 30.5 | 0 | 0.00 |  |
| DELETE | `/api/v1/cart` | Customer | 0.58 | 0.76 | 53684 | 5367.3 | 2.98 | 2.47 | 5.00 | 6.37 | 9.65 | 34.0 | 0 | 0.00 |  |

### Orders

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| POST | `/api/v1/orders` | Customer | 3.47 | 6.10 | 10805 | 1079.3 | 14.81 | 14.09 | 19.94 | 22.28 | 28.33 | 43.9 | 0 | 0.00 | [COD, delivery, 1-2 units of one of 60 products] |
| GET | `/api/v1/orders` | Customer | 0.50 | 0.69 | 68572 | 6855.8 | 2.33 | 2.06 | 3.60 | 4.43 | 6.73 | 31.7 | 0 | 0.00 |  |
| GET | `/api/v1/orders/{id}` | Customer | 0.78 | 1.02 | 43006 | 4299.6 | 3.72 | 3.19 | 5.92 | 7.26 | 10.30 | 23.5 | 0 | 0.00 |  |
| POST | `/api/v1/orders/{id}/cancel` | Customer | 2.52 | 3.62 | 3500 | 1377.8 | 11.60 | 11.22 | 15.72 | 17.31 | 20.58 | 27.9 | 0 | 0.00 | [PLACED order created in untimed prep] POOL/RUN LIMITED (A pool 700 B pool 3500, window 2.54s) |
| GET | `/api/v1/orders/{id}/reorder` | Customer | 1.05 | 1.32 | 32169 | 3215.8 | 4.97 | 4.42 | 7.53 | 8.88 | 12.08 | 22.5 | 0 | 0.00 |  |
| GET | `/api/v1/orders/{id}/returns` | Customer | 0.54 | 0.72 | 58152 | 5814.2 | 2.75 | 2.36 | 4.39 | 5.45 | 8.02 | 45.5 | 0 | 0.00 |  |
| GET | `/api/v1/orders/stream` | Customer | 0.22 | 0.26 | 124795 | 12478.1 | 1.25 | 1.16 | 1.63 | 1.87 | 2.85 | 75.3 | 0 | 0.00 | [SSE: time to first byte, then close] |

### Payments

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| GET | `/api/v1/payment-methods` | Customer | 0.16 | 0.20 | 242270 | 24225.2 | 0.66 | 0.61 | 0.91 | 1.05 | 1.78 | 42.0 | 0 | 0.00 |  |
| POST | `/api/v1/payments/webhook/{provider}` | Public (Razorpay) | 0.29 | 0.34 | 117929 | 11791.9 | 1.36 | 1.25 | 1.77 | 2.00 | 2.99 | 41.5 | 0 | 0.00 | [REJECTION PATH: unsigned call answers 400 BAD_SIGNATURE; a valid signed webhook cannot be produced] |
| GET | `/api/v1/admin/payment-methods` | Admin | 0.42 | 0.49 | 87086 | 8707.6 | 1.84 | 1.64 | 2.72 | 3.40 | 5.12 | 14.6 | 0 | 0.00 |  |
| PUT | `/api/v1/admin/payment-methods` | Admin | 1.32 | 2.57 | 28371 | 2836.2 | 5.64 | 5.33 | 7.49 | 8.37 | 10.84 | 20.1 | 0 | 0.00 | [re-saves COD enabled] |
| GET | `/api/v1/admin/refunds` | Admin | 0.41 | 0.49 | 87335 | 8732.1 | 1.83 | 1.62 | 2.77 | 3.46 | 5.21 | 14.4 | 0 | 0.00 |  |
| POST | `/api/v1/admin/refunds/{id}/retry` | Admin | 0.61 | 0.78 | 55045 | 5503.2 | 2.91 | 2.57 | 4.44 | 5.44 | 7.88 | 25.0 | 0 | 0.00 | [REJECTION PATH: no FAILED refund exists (needs Razorpay); unknown id answers 409 REFUND_NOT_FAILED] |
| GET | `/api/v1/admin/payments/anomalies` | Admin | 0.43 | 0.56 | 82813 | 8279.7 | 1.93 | 1.70 | 2.96 | 3.74 | 5.60 | 17.1 | 0 | 0.00 |  |

### Admin orders

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| GET | `/api/v1/admin/orders` | Admin/Cashier | 2.63 | 3.18 | 15127 | 1511.4 | 10.58 | 10.17 | 14.15 | 15.56 | 19.18 | 45.5 | 0 | 0.00 | [page 0-49, size 20] |
| GET | `/api/v1/admin/orders` | Admin/Cashier | 11.94 | 24.33 | 3652 | 364.4 | 43.84 | 43.32 | 58.34 | 63.25 | 75.61 | 99.7 | 0 | 0.00 | [status=PLACED, page 0-49] |
| GET | `/api/v1/admin/orders` | Admin/Cashier | 4.12 | 5.87 | 9958 | 994.9 | 16.07 | 15.13 | 21.11 | 23.33 | 29.30 | 306.4 | 0 | 0.00 | [search q=order number] |
| GET | `/api/v1/admin/orders/{id}` | Admin/Cashier | 1.07 | 1.46 | 32126 | 3211.7 | 4.98 | 4.33 | 7.65 | 9.16 | 12.55 | 48.6 | 0 | 0.00 |  |
| POST | `/api/v1/admin/orders/{id}/status` | Admin/Cashier | 2.91 | 4.12 | 3500 | 1112.0 | 14.36 | 13.38 | 20.20 | 23.10 | 29.52 | 50.2 | 0 | 0.00 | [PLACED -> CONFIRMED (deducts stock); order created in untimed prep] POOL/RUN LIMITED (A pool 700 B pool 3500, window 3.15s) |
| POST | `/api/v1/admin/orders/{id}/reject` | Admin/Cashier | 2.63 | 4.54 | 3500 | 1266.9 | 12.61 | 12.12 | 16.79 | 18.42 | 22.18 | 29.0 | 0 | 0.00 | [order created in untimed prep] POOL/RUN LIMITED (A pool 700 B pool 3500, window 2.76s) |
| POST | `/api/v1/admin/orders/{id}/returns` | Admin/Cashier | 5.69 | 8.45 | 1500 | 590.0 | 27.02 | 25.40 | 38.50 | 43.46 | 53.99 | 82.9 | 0 | 0.00 | [return of 1 of 2 units, restocked; delivered order made in untimed prep] POOL/RUN LIMITED (A pool 300 B pool 1500, window 2.54s) |
| GET | `/api/v1/admin/orders/{id}/returns` | Admin/Cashier | 0.70 | 0.91 | 48853 | 4884.2 | 3.27 | 2.91 | 5.02 | 6.05 | 8.42 | 26.4 | 0 | 0.00 |  |
| GET | `/api/v1/admin/orders/stream` | Admin/Cashier | 0.38 | 0.51 | 77395 | 7737.2 | 2.04 | 1.87 | 2.79 | 3.30 | 5.51 | 51.8 | 0 | 0.00 | [SSE: time to first byte, then close] |

### Admin catalog

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| GET | `/api/v1/admin/products` | Admin | 0.56 | 0.65 | 56284 | 5627.4 | 2.84 | 2.62 | 4.10 | 4.75 | 6.41 | 36.7 | 0 | 0.00 | [page 0-99, size 20] |
| GET | `/api/v1/admin/products` | Admin | 1.28 | 2.46 | 30968 | 3095.7 | 5.16 | 4.93 | 7.08 | 7.95 | 10.00 | 63.0 | 0 | 0.00 | [search q=Item <n>] |
| GET | `/api/v1/admin/products/{id}` | Admin | 0.71 | 0.87 | 47660 | 4764.9 | 3.36 | 2.94 | 5.20 | 6.34 | 8.94 | 23.8 | 0 | 0.00 |  |
| GET | `/api/v1/admin/products/{id}/variants` | Admin | 0.75 | 0.88 | 46357 | 4633.9 | 3.45 | 2.99 | 5.33 | 6.62 | 9.74 | 37.7 | 0 | 0.00 |  |
| GET | `/api/v1/admin/stock-movements` | Admin | 2.59 | 3.21 | 17077 | 1706.5 | 9.37 | 9.21 | 12.24 | 13.21 | 15.44 | 39.3 | 0 | 0.00 | [all products, page 0-19] |
| GET | `/api/v1/admin/hot-banners` | Admin | 0.42 | 0.53 | 84293 | 8427.7 | 1.90 | 1.67 | 2.91 | 3.63 | 5.38 | 25.7 | 0 | 0.00 |  |
| GET | `/api/v1/admin/units` | Admin | 1.30 | 3.84 | 61509 | 6149.8 | 2.60 | 2.28 | 4.25 | 5.12 | 7.29 | 20.4 | 0 | 0.00 |  |
| GET | `/api/v1/admin/coupons` | Admin | 0.55 | 0.72 | 62459 | 6244.2 | 2.56 | 2.17 | 4.09 | 5.12 | 7.75 | 20.9 | 0 | 0.00 |  |
| GET | `/api/v1/admin/coin-rules` | Admin | 0.42 | 0.53 | 55854 | 5584.7 | 2.86 | 2.47 | 4.79 | 5.83 | 8.45 | 25.9 | 0 | 0.00 |  |
| GET | `/api/v1/admin/coin-settings` | Admin | 0.60 | 2.20 | 80287 | 8027.7 | 1.99 | 1.72 | 3.22 | 3.98 | 6.35 | 55.8 | 0 | 0.00 |  |
| PUT | `/api/v1/admin/coin-settings` | Admin | 1.36 | 3.14 | 22562 | 2254.8 | 7.09 | 6.44 | 10.21 | 12.02 | 18.94 | 50.9 | 0 | 0.00 | [re-saves 100 paise/coin, 20%, 6 months] |

### Admin customers

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| GET | `/api/v1/admin/customers` | Admin | 13.51 | 14.08 | 3810 | 379.2 | 42.08 | 41.21 | 53.87 | 58.38 | 68.20 | 98.2 | 0 | 0.00 | [page 0-99, size 20] |
| GET | `/api/v1/admin/customers` | Admin | 1.28 | 1.58 | 27649 | 2759.3 | 5.79 | 5.21 | 7.60 | 8.62 | 12.58 | 129.4 | 0 | 0.00 | [search q=load-<hex>] |
| GET | `/api/v1/admin/customers/{id}` | Admin | 0.97 | 1.21 | 35076 | 3506.6 | 4.56 | 3.96 | 7.12 | 8.72 | 12.46 | 26.9 | 0 | 0.00 |  |
| PATCH | `/api/v1/admin/customers/{id}` | Admin | 4.54 | 6.60 | 14448 | 1443.8 | 11.08 | 10.63 | 15.39 | 16.99 | 20.78 | 29.2 | 0 | 0.00 | [active=true on bench customers] |
| POST | `/api/v1/admin/customers/{id}/coins` | Admin | 5.90 | 11.39 | 10521 | 1050.8 | 15.22 | 14.74 | 20.59 | 22.53 | 26.87 | 38.8 | 0 | 0.00 | [+1 coin on bench customers] |

### Admin catalog writes

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| POST | `/api/v1/admin/products` | Admin | 2.26 | 4.83 | 16892 | 1687.2 | 9.47 | 8.83 | 13.10 | 14.88 | 19.61 | 113.8 | 0 | 0.00 |  |
| PUT | `/api/v1/admin/products/{id}` | Admin | 7.69 | 17.65 | 9972 | 996.4 | 16.04 | 14.08 | 23.20 | 29.81 | 52.95 | 109.4 | 0 | 0.00 | [bench product per user; stock omitted] |
| POST | `/api/v1/admin/products/{id}/variants` | Admin | 10.29 | 15.41 | 5621 | 560.4 | 28.48 | 27.07 | 41.57 | 46.66 | 59.49 | 100.1 | 0 | 0.00 |  |
| PUT | `/api/v1/admin/product-groups/{id}` | Admin | 2.18 | 3.66 | 18406 | 1839.8 | 8.69 | 8.15 | 11.96 | 13.57 | 17.35 | 33.7 | 0 | 0.00 |  |
| POST | `/api/v1/admin/products/{id}/stock` | Admin | 2.24 | 5.48 | 12198 | 1219.3 | 13.11 | 12.03 | 18.59 | 22.30 | 37.42 | 63.4 | 0 | 0.00 |  |
| POST | `/api/v1/admin/products/{id}/image` | Admin | 9.43 | 15.79 | 5314 | 530.6 | 27.13 | 25.43 | 39.26 | 43.34 | 54.51 | 69.5 | 0 | 0.00 | [multipart, 96x96 PNG] |
| POST | `/api/v1/admin/categories` | Admin | 1.24 | 3.33 | 34596 | 3458.9 | 4.62 | 4.32 | 6.14 | 7.07 | 10.07 | 24.4 | 0 | 0.00 |  |
| PUT | `/api/v1/admin/categories/{id}` | Admin | 1.52 | 3.56 | 28367 | 2834.7 | 5.64 | 5.21 | 7.80 | 9.08 | 12.24 | 43.2 | 0 | 0.00 |  |
| POST | `/api/v1/admin/hot-banners` | Admin | 4.62 | 8.66 | 26249 | 2624.4 | 6.09 | 5.54 | 9.09 | 10.71 | 14.89 | 25.9 | 0 | 0.00 |  |
| PUT | `/api/v1/admin/hot-banners/{id}` | Admin | 2.15 | 6.43 | 30722 | 3070.8 | 5.21 | 4.92 | 6.90 | 7.84 | 10.11 | 16.9 | 0 | 0.00 |  |
| DELETE | `/api/v1/admin/hot-banners/{id}` | Admin | 4.13 | 8.43 | 3500 | 2280.6 | 7.00 | 6.55 | 9.81 | 11.03 | 15.38 | 22.2 | 0 | 0.00 | POOL/RUN LIMITED (A pool 700 B pool 3500, window 1.53s) |
| POST | `/api/v1/admin/units` | Admin | 4.55 | 11.15 | 7471 | 745.7 | 21.43 | 19.93 | 31.44 | 36.11 | 47.57 | 72.6 | 0 | 0.00 |  |
| PUT | `/api/v1/admin/units/{code}` | Admin | 3.91 | 6.72 | 21415 | 2140.6 | 7.47 | 6.45 | 11.08 | 13.59 | 22.49 | 53.2 | 0 | 0.00 |  |
| DELETE | `/api/v1/admin/units/{code}` | Admin | 5.62 | 8.13 | 3500 | 1086.7 | 14.70 | 13.89 | 20.76 | 23.27 | 28.19 | 58.5 | 0 | 0.00 | POOL/RUN LIMITED (A pool 700 B pool 3500, window 3.22s) |
| POST | `/api/v1/admin/coupons` | Admin | 2.61 | 6.49 | 11479 | 1146.8 | 13.93 | 11.89 | 16.46 | 18.83 | 31.70 | 1007.1 | 1 | 0.01 | codes: 201x13031 500x1 ; e.g. 500 {"code":"INTERNAL_ERROR","message":"Something went wrong","traceId":"1b8a8443","details":{}} [not-yet-started coupon] |
| PUT | `/api/v1/admin/coupons/{id}` | Admin | 2.20 | 3.97 | 13303 | 1329.3 | 12.02 | 11.80 | 15.43 | 16.86 | 19.81 | 33.2 | 0 | 0.00 |  |
| DELETE | `/api/v1/admin/coupons/{id}` | Admin | 1.76 | 4.39 | 3500 | 1849.8 | 8.63 | 8.35 | 11.43 | 12.63 | 15.72 | 29.8 | 0 | 0.00 | POOL/RUN LIMITED (A pool 700 B pool 3500, window 1.89s) |
| POST | `/api/v1/admin/coin-rules` | Admin | 1.37 | 2.96 | 26549 | 2654.0 | 6.02 | 5.80 | 7.83 | 8.66 | 11.28 | 28.7 | 0 | 0.00 |  |
| PUT | `/api/v1/admin/coin-rules/{id}` | Admin | 1.44 | 3.32 | 23557 | 2354.5 | 6.79 | 6.62 | 8.79 | 9.68 | 11.90 | 20.2 | 0 | 0.00 |  |
| DELETE | `/api/v1/admin/coin-rules/{id}` | Admin | 1.23 | 1.80 | 3500 | 2586.0 | 6.17 | 6.06 | 8.00 | 8.58 | 10.04 | 13.0 | 0 | 0.00 | POOL/RUN LIMITED (A pool 700 B pool 3500, window 1.35s) |

### Shop

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| GET | `/api/v1/shop-settings` | Public | 0.14 | 0.19 | 228326 | 22831.3 | 0.70 | 0.66 | 0.96 | 1.12 | 1.60 | 12.1 | 0 | 0.00 |  |
| GET | `/api/v1/admin/shop-profile` | Admin | 0.31 | 0.42 | 113125 | 11311.2 | 1.41 | 1.31 | 2.02 | 2.38 | 3.37 | 20.2 | 0 | 0.00 |  |
| PUT | `/api/v1/admin/shop-profile` | Admin | 1.35 | 2.95 | 10008 | 999.5 | 16.00 | 14.13 | 25.74 | 30.10 | 40.93 | 75.5 | 0 | 0.00 | [opening hours + holiday note; restored afterwards] |
| POST | `/api/v1/admin/shop-profile/logo` | Admin | 6.35 | 8.29 | 4864 | 485.5 | 30.59 | 26.41 | 48.68 | 58.86 | 81.20 | 129.4 | 0 | 0.00 | [multipart, 96x96 PNG] |
| DELETE | `/api/v1/admin/shop-profile/logo` | Admin | 1.33 | 2.03 | 150 | 1097.0 | 13.91 | 12.18 | 24.98 | 28.52 | 33.40 | 37.5 | 0 | 0.00 | [logo uploaded in untimed prep] POOL/RUN LIMITED (A pool 60 B pool 150, window 0.14s) |
| GET | `/api/v1/admin/shop-settings` | Admin | 0.30 | 0.40 | 114168 | 11415.6 | 1.40 | 1.29 | 2.00 | 2.34 | 3.39 | 26.9 | 0 | 0.00 |  |
| PUT | `/api/v1/admin/shop-settings` | Admin | 1.54 | 3.11 | 10504 | 1049.2 | 15.23 | 13.33 | 24.76 | 30.08 | 41.50 | 85.3 | 0 | 0.00 | [bill header details; promise text varies; restored afterwards] |

### Staff

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| GET | `/api/v1/admin/staff` | Admin | 7.36 | 7.99 | 4812 | 480.3 | 33.29 | 32.91 | 39.62 | 41.95 | 47.82 | 63.0 | 0 | 0.00 |  |
| POST | `/api/v1/admin/staff` | Admin | 1.58 | 2.74 | 23391 | 2338.3 | 6.84 | 6.71 | 8.48 | 9.22 | 11.57 | 83.6 | 0 | 0.00 |  |
| PATCH | `/api/v1/admin/staff/{id}` | Admin | 1.26 | 2.90 | 30884 | 3087.2 | 5.18 | 5.06 | 6.44 | 6.99 | 8.97 | 29.5 | 0 | 0.00 |  |
| GET | `/api/v1/admin/products/{id}/pos` | Admin | 1.10 | 1.20 | 29478 | 2946.7 | 5.43 | 5.21 | 7.25 | 8.01 | 9.77 | 21.3 | 0 | 0.00 |  |
| PUT | `/api/v1/admin/products/{id}/pos` | Admin | 2.55 | 3.18 | 11288 | 1127.6 | 14.18 | 14.04 | 17.71 | 19.10 | 22.80 | 36.7 | 0 | 0.00 | [counter attributes + barcode of a bench product] |
| PUT | `/api/v1/admin/me/approval-pin` | Admin | 80.24 | 86.73 | 637 | 62.8 | 254.08 | 211.77 | 417.29 | 515.39 | 712.19 | 994.8 | 0 | 0.00 |  |

### POS

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| GET | `/api/v1/pos/shop` | Cashier/Admin | 0.31 | 0.45 | 111890 | 11188.0 | 1.43 | 1.26 | 2.16 | 2.68 | 4.08 | 19.4 | 0 | 0.00 |  |
| GET | `/api/v1/pos/barcode/{code}` | Cashier/Admin | 1.59 | 2.00 | 18946 | 1892.9 | 8.45 | 8.10 | 11.41 | 12.64 | 15.56 | 25.2 | 0 | 0.00 |  |
| GET | `/api/v1/pos/products` | Cashier/Admin | 2.67 | 5.28 | 13025 | 1301.4 | 12.29 | 10.83 | 18.79 | 22.97 | 32.70 | 79.2 | 0 | 0.00 | [q=Item <n>] |
| POST | `/api/v1/pos/quote` | Cashier/Admin | 0.82 | 1.23 | 39221 | 3920.7 | 4.08 | 3.83 | 5.80 | 6.60 | 8.24 | 16.1 | 0 | 0.00 |  |
| POST | `/api/v1/pos/sales` | Cashier/Admin | 3.99 | 5.34 | 4497 | 448.9 | 35.58 | 33.00 | 50.53 | 58.46 | 75.44 | 124.2 | 0 | 0.00 | [cash bill, 1-3 units of one of 60 products] |
| GET | `/api/v1/pos/sales` | Cashier/Admin | 3.39 | 3.83 | 9474 | 946.2 | 16.90 | 16.20 | 22.47 | 24.99 | 30.98 | 102.4 | 0 | 0.00 | [today's bills, latest 200] |
| GET | `/api/v1/pos/sales/{id}` | Cashier/Admin | 0.89 | 1.12 | 35051 | 3504.4 | 4.56 | 4.31 | 6.35 | 7.15 | 8.93 | 15.9 | 0 | 0.00 |  |
| GET | `/api/v1/pos/sales/{id}/receipt` | Cashier/Admin | 1.16 | 1.66 | 26741 | 2673.1 | 5.98 | 5.69 | 8.09 | 9.01 | 11.31 | 19.7 | 0 | 0.00 |  |
| POST | `/api/v1/pos/sales/{id}/returns` | Cashier/Admin | 4.26 | 8.08 | 1500 | 452.3 | 35.25 | 33.28 | 49.95 | 55.26 | 69.31 | 95.3 | 0 | 0.00 | [return 1 of 2 units; bill made in untimed prep] POOL/RUN LIMITED (A pool 300 B pool 1500, window 3.32s) |
| GET | `/api/v1/pos/sales/{id}/returns` | Cashier/Admin | 0.47 | 0.63 | 55992 | 5597.9 | 2.86 | 2.60 | 4.37 | 5.08 | 6.91 | 55.3 | 0 | 0.00 |  |
| GET | `/api/v1/pos/upi-link` | Cashier/Admin | 0.34 | 0.48 | 86885 | 8687.2 | 1.84 | 1.68 | 2.71 | 3.18 | 4.52 | 26.3 | 0 | 0.00 | [UPI id set to vijaymall@okbank for this case, restored afterwards] |
| POST | `/api/v1/pos/holds` | Cashier/Admin | 1.34 | 2.68 | 23003 | 2299.1 | 6.95 | 6.70 | 9.28 | 10.31 | 12.99 | 22.3 | 0 | 0.00 |  |
| GET | `/api/v1/pos/holds` | Cashier/Admin | 121.84 | 160.04 | 196 | 18.4 | 851.31 | 824.38 | 1240.83 | 1462.82 | 1798.47 | 2179.1 | 0 | 0.00 |  |
| GET | `/api/v1/pos/holds/{id}` | Cashier/Admin | 0.66 | 1.55 | 39217 | 3920.7 | 4.07 | 3.76 | 5.79 | 6.65 | 9.12 | 44.8 | 0 | 0.00 |  |
| DELETE | `/api/v1/pos/holds/{id}` | Cashier/Admin | 1.42 | 2.76 | 3500 | 1889.8 | 8.45 | 8.01 | 11.68 | 13.13 | 16.72 | 22.2 | 0 | 0.00 | POOL/RUN LIMITED (A pool 700 B pool 3500, window 1.85s) |
| GET | `/api/v1/pos/reports/day` | Cashier/Admin | 18.29 | 26.21 | 1843 | 182.7 | 87.24 | 85.18 | 112.11 | 121.48 | 142.40 | 194.9 | 0 | 0.00 |  |

### POS shifts

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| POST | `/api/v1/pos/shifts/open` | Cashier/Admin | 2.40 | 4.09 | 700 | 1357.5 | 11.66 | 11.37 | 14.69 | 15.79 | 17.89 | 22.4 | 0 | 0.00 | [a fresh cashier account is created and signed in during untimed prep] POOL/RUN LIMITED (A pool 150 B pool 700, window 0.52s) |
| GET | `/api/v1/pos/shifts/current` | Cashier/Admin | 12.93 | 18.09 | 2470 | 245.7 | 64.97 | 62.73 | 85.04 | 94.28 | 118.24 | 165.6 | 0 | 0.00 | [cashier has an open shift (opened for this case)] |
| POST | `/api/v1/pos/shifts/close` | Cashier/Admin | 1.89 | 2.61 | 700 | 929.1 | 17.11 | 16.31 | 23.49 | 26.40 | 31.66 | 51.0 | 0 | 0.00 | [fresh cashier with open shift made in untimed prep; counted = opening float] POOL/RUN LIMITED (A pool 150 B pool 700, window 0.75s) |
| GET | `/api/v1/admin/pos/shifts` | Admin | 12.84 | 18.31 | 2470 | 245.8 | 64.96 | 63.06 | 83.64 | 92.14 | 116.29 | 159.3 | 0 | 0.00 |  |

### POS sync

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| GET | `/api/v1/pos/sync/status` | Cashier/Admin | 4.26 | 6.62 | 11320 | 1130.4 | 14.14 | 13.61 | 18.93 | 20.97 | 26.75 | 55.0 | 0 | 0.00 |  |
| POST | `/api/v1/pos/sync/sales` | Cashier/Admin | 8.16 | 16.01 | 2046 | 203.5 | 78.38 | 74.93 | 104.56 | 115.16 | 148.88 | 198.1 | 0 | 0.00 | [2 offline bills per upload] |

### Credit

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| GET | `/api/v1/pos/credit/account` | Cashier/Admin | 3.79 | 6.36 | 11356 | 1134.7 | 14.09 | 13.59 | 19.35 | 21.37 | 25.56 | 44.1 | 0 | 0.00 |  |
| GET | `/api/v1/pos/credit/ledger` | Cashier/Admin | 4.44 | 7.15 | 10109 | 1010.0 | 15.83 | 15.34 | 21.28 | 23.08 | 27.50 | 45.6 | 0 | 0.00 |  |
| POST | `/api/v1/pos/credit/payments` | Cashier/Admin | 8.29 | 10.19 | 2055 | 204.8 | 77.94 | 74.39 | 110.37 | 124.20 | 155.03 | 291.8 | 0 | 0.00 | [Rs 1 cash repayment against a bench debtor] |
| PUT | `/api/v1/admin/credit/limit` | Admin | 6.57 | 8.02 | 7815 | 780.5 | 20.48 | 20.19 | 26.21 | 28.39 | 35.05 | 51.2 | 0 | 0.00 |  |
| GET | `/api/v1/admin/credit/accounts/{customerId}` | Admin | 0.84 | 1.08 | 37205 | 3719.1 | 4.30 | 3.95 | 6.25 | 7.23 | 9.41 | 40.2 | 0 | 0.00 |  |
| GET | `/api/v1/admin/credit/accounts/{customerId}/ledger` | Admin | 0.83 | 1.04 | 44208 | 4419.3 | 3.62 | 3.34 | 5.09 | 5.89 | 8.25 | 84.4 | 0 | 0.00 |  |
| PUT | `/api/v1/admin/credit/accounts/{customerId}/limit` | Admin | 1.89 | 2.90 | 17721 | 1771.2 | 9.03 | 8.78 | 11.65 | 12.83 | 16.15 | 55.9 | 0 | 0.00 |  |
| POST | `/api/v1/admin/credit/accounts/{customerId}/adjust` | Admin | 2.22 | 3.89 | 14661 | 1464.9 | 10.92 | 10.73 | 13.96 | 15.26 | 18.64 | 54.2 | 0 | 0.00 | [+Rs 1 correction] |

### Defective stock

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| GET | `/api/v1/pos/stock/defective` | Cashier/Admin | 0.57 | 0.71 | 57847 | 5783.6 | 2.77 | 2.54 | 4.01 | 4.67 | 6.34 | 30.2 | 0 | 0.00 |  |
| GET | `/api/v1/admin/stock/defective` | Admin | 0.66 | 0.84 | 33991 | 3398.0 | 4.71 | 4.04 | 7.56 | 9.31 | 14.83 | 70.6 | 0 | 0.00 |  |
| GET | `/api/v1/admin/stock/defective/summary` | Admin | 0.44 | 0.58 | 71997 | 7198.1 | 2.22 | 1.98 | 3.37 | 4.06 | 5.88 | 31.7 | 0 | 0.00 |  |
| POST | `/api/v1/pos/stock/defective/{id}/review` | Cashier/Admin | 2.07 | 3.64 | 600 | 1177.2 | 13.48 | 13.23 | 17.53 | 19.22 | 21.96 | 26.1 | 0 | 0.00 | [RESTOCK decision; defective batch from a no-restock return made in untimed prep] POOL/RUN LIMITED (A pool 150 B pool 600, window 0.51s) |
| POST | `/api/v1/admin/stock/defective/{id}/resolve` | Admin | 1.58 | 4.42 | 600 | 1584.0 | 10.02 | 9.70 | 13.85 | 15.26 | 18.10 | 22.8 | 0 | 0.00 | [WRITE_OFF; batch already confirmed defective in untimed prep] POOL/RUN LIMITED (A pool 150 B pool 600, window 0.38s) |

### Offline exceptions

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| GET | `/api/v1/admin/pos/offline-exceptions` | Admin | 0.55 | 0.68 | 55932 | 5592.0 | 2.86 | 2.61 | 4.24 | 4.96 | 6.56 | 20.8 | 0 | 0.00 |  |
| POST | `/api/v1/admin/pos/offline-exceptions/{id}/review` | Admin | 1.46 | 1.90 | 1525 | 2142.2 | 7.44 | 7.16 | 9.78 | 10.78 | 13.35 | 15.4 | 0 | 0.00 | [OVERSOLD exceptions created by uploading offline bills for a zero-stock product in untimed prep] POOL/RUN LIMITED (A pool 325 B pool 1525, window 0.71s) |

### Purchasing

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| POST | `/api/v1/admin/purchases` | Admin | 3.42 | 6.57 | 3755 | 374.6 | 42.63 | 36.77 | 70.94 | 85.24 | 125.17 | 302.5 | 0 | 0.00 | [3 lines] |
| GET | `/api/v1/admin/purchases` | Admin | 8.97 | 13.72 | 3352 | 334.0 | 47.83 | 46.93 | 57.10 | 60.65 | 67.65 | 78.8 | 0 | 0.00 |  |
| GET | `/api/v1/admin/purchases/{id}` | Admin | 1.02 | 1.26 | 30922 | 3090.7 | 5.17 | 4.89 | 7.15 | 8.03 | 10.22 | 43.2 | 0 | 0.00 |  |
| POST | `/api/v1/admin/stock-takes` | Admin | 1.97 | 4.03 | 7406 | 738.5 | 21.62 | 12.95 | 32.18 | 53.27 | 185.82 | 399.0 | 0 | 0.00 | [5 lines set to 2,000,000 so later cases keep stock] |

### Reports

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| GET | `/api/v1/admin/dashboard` | Admin | 0.32 | 0.39 | 109991 | 10997.9 | 1.45 | 1.32 | 2.12 | 2.52 | 3.77 | 37.4 | 0 | 0.00 |  |
| GET | `/api/v1/admin/reports/summary` | Admin | 0.33 | 0.42 | 104989 | 10497.7 | 1.52 | 1.40 | 2.20 | 2.60 | 3.79 | 19.6 | 0 | 0.00 | [period=month, served from the ~1 min cache] |
| GET | `/api/v1/admin/reports/summary` | Admin | 410.15 | 609.40 | 122 | 10.7 | 1402.51 | 1271.34 | 2216.17 | 2505.80 | 2812.23 | 2823.7 | 0 | 0.00 | [cache-defeating: from=random day in last 300 days, to=today] |
| GET | `/api/v1/admin/reports/credit` | Admin | 88.47 | 106.95 | 494 | 48.1 | 328.93 | 312.18 | 437.21 | 498.88 | 637.81 | 706.0 | 0 | 0.00 | [period=month] |
| GET | `/api/v1/admin/credit/accounts` | Admin | 58.72 | 60.13 | 691 | 67.7 | 234.49 | 226.00 | 315.26 | 349.56 | 426.25 | 489.2 | 0 | 0.00 |  |
| GET | `/api/v1/admin/reports/counter-controls` | Admin | 13.79 | 14.43 | 3603 | 359.1 | 44.46 | 43.55 | 56.88 | 61.98 | 72.64 | 171.5 | 0 | 0.00 | [period=month] |
| GET | `/api/v1/admin/stock/low` | Admin | 1.54 | 1.86 | 28377 | 2836.4 | 5.64 | 5.43 | 7.76 | 8.62 | 10.62 | 16.5 | 0 | 0.00 |  |
| GET | `/api/v1/admin/audit-log` | Admin | 48.46 | 53.99 | 395 | 38.3 | 411.16 | 393.57 | 589.13 | 658.54 | 738.18 | 760.7 | 0 | 0.00 | [latest page] |
| GET | `/api/v1/admin/audit-log` | Admin | 28.30 | 59.22 | 700 | 68.8 | 230.65 | 227.13 | 309.28 | 334.68 | 400.05 | 525.4 | 0 | 0.00 | [action=SALE, page 0-49] |

### Config

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| GET | `/api/v1/admin/config` | Admin | 0.79 | 0.96 | 36369 | 3636.1 | 4.40 | 4.15 | 7.16 | 8.14 | 10.20 | 16.4 | 0 | 0.00 |  |
| PUT | `/api/v1/admin/config` | Admin | 3.31 | 5.11 | 14815 | 1480.1 | 10.80 | 7.67 | 22.62 | 28.95 | 43.53 | 86.0 | 0 | 0.00 | [toggles pos.cashierReturnDays 7/8; cleared afterwards] |
| GET | `/api/v1/admin/config/bootstrap` | Admin | 0.34 | 0.42 | 108377 | 10836.7 | 1.48 | 1.34 | 2.12 | 2.51 | 3.91 | 24.8 | 0 | 0.00 |  |
| GET | `/api/v1/admin/logs/status` | Admin | 0.65 | 0.73 | 79817 | 7980.3 | 2.00 | 1.86 | 2.65 | 3.13 | 4.89 | 99.0 | 0 | 0.00 |  |
| GET | `/api/v1/admin/cache` | Admin | 0.37 | 0.48 | 91216 | 9120.3 | 1.75 | 1.59 | 2.73 | 3.23 | 4.61 | 30.3 | 0 | 0.00 |  |
| POST | `/api/v1/admin/cache/refresh` | Admin | 1.58 | 3.26 | 22153 | 2214.2 | 7.22 | 6.89 | 9.57 | 10.76 | 13.77 | 22.0 | 0 | 0.00 |  |
| POST | `/api/v1/admin/cache/refresh/{name}` | Admin | 1.29 | 2.66 | 26948 | 2694.0 | 5.94 | 5.62 | 7.97 | 8.99 | 12.43 | 25.8 | 0 | 0.00 | [name=categories] |

### Flash sales

| Method | Path | Who | 1u P50 | 1u P95 | Requests | TPS | Avg | P50 | P90 | P95 | P99 | Max | Errors | Err % | Status codes / notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| GET | `/api/v1/flash-sales/active` | Public | 0.15 | 0.19 | 241671 | 24165.7 | 0.66 | 0.62 | 0.89 | 1.02 | 1.54 | 14.5 | 0 | 0.00 |  |
| GET | `/api/v1/flash-sales/upcoming` | Public | 0.16 | 0.19 | 241343 | 24133.3 | 0.66 | 0.62 | 0.91 | 1.05 | 1.54 | 33.5 | 0 | 0.00 |  |
| GET | `/api/v1/admin/flash-sales` | Admin/Cashier | 0.65 | 0.88 | 49121 | 4911.3 | 3.26 | 3.04 | 4.67 | 5.38 | 6.89 | 11.8 | 0 | 0.00 |  |
| GET | `/api/v1/admin/flash-sales/report` | Admin/Cashier | 0.54 | 0.70 | 59304 | 5929.0 | 2.70 | 2.46 | 3.89 | 4.54 | 6.25 | 31.6 | 0 | 0.00 |  |
| GET | `/api/v1/admin/flash-sales/{id}` | Admin/Cashier | 0.84 | 0.96 | 38244 | 3823.3 | 4.15 | 3.87 | 5.90 | 6.76 | 8.67 | 48.5 | 0 | 0.00 |  |
| GET | `/api/v1/admin/flash-sales/{id}/report` | Admin/Cashier | 1.20 | 1.36 | 24249 | 2423.8 | 6.60 | 5.99 | 9.38 | 11.14 | 15.81 | 59.8 | 0 | 0.00 |  |
| POST | `/api/v1/admin/flash-sales` | Admin/Cashier | 5.29 | 6.90 | 3568 | 355.5 | 44.90 | 40.40 | 68.28 | 71.69 | 81.42 | 119.3 | 0 | 0.00 | [scheduled sale with 3 products, own time slot] |
| PATCH | `/api/v1/admin/flash-sales/{id}` | Admin/Cashier | 2.16 | 3.52 | 5273 | 526.0 | 30.37 | 29.54 | 33.89 | 36.22 | 46.96 | 77.9 | 0 | 0.00 |  |
| POST | `/api/v1/admin/flash-sales/{id}/items` | Admin/Cashier | 2.73 | 4.05 | 1500 | 445.0 | 35.79 | 35.40 | 39.34 | 40.63 | 47.82 | 50.4 | 0 | 0.00 | [sale + 1 item made in untimed prep; the call adds a second product] POOL/RUN LIMITED (A pool 300 B pool 1500, window 3.37s) |
| PUT | `/api/v1/admin/flash-sales/{id}/items/{itemId}` | Admin/Cashier | 2.58 | 3.77 | 1500 | 500.8 | 31.81 | 31.57 | 34.36 | 36.01 | 40.55 | 41.7 | 0 | 0.00 | [sale with an item made in untimed prep] POOL/RUN LIMITED (A pool 300 B pool 1500, window 3.00s) |
| DELETE | `/api/v1/admin/flash-sales/{id}/items/{itemId}` | Admin/Cashier | 2.09 | 3.41 | 1500 | 1542.4 | 10.33 | 10.31 | 13.11 | 14.13 | 17.08 | 21.1 | 0 | 0.00 | [sale with 2 items made in untimed prep] POOL/RUN LIMITED (A pool 300 B pool 1500, window 0.97s) |
| POST | `/api/v1/admin/flash-sales/{id}/start-now` | Admin/Cashier | 2.18 | 3.07 | 1200 | 531.5 | 29.92 | 29.34 | 33.71 | 35.85 | 44.46 | 50.0 | 0 | 0.00 | [scheduled sale made in untimed prep; started sales are ended again afterwards] POOL/RUN LIMITED (A pool 300 B pool 1200, window 2.26s) |
| POST | `/api/v1/admin/flash-sales/{id}/end-now` | Admin/Cashier | 1.85 | 2.44 | 1200 | 1883.7 | 8.46 | 8.47 | 10.55 | 11.36 | 13.52 | 15.5 | 0 | 0.00 | [running sale made in untimed prep] POOL/RUN LIMITED (A pool 300 B pool 1200, window 0.64s) |
| POST | `/api/v1/admin/flash-sales/{id}/cancel` | Admin/Cashier | 1.92 | 2.79 | 1500 | 1864.4 | 8.53 | 8.40 | 10.61 | 11.34 | 14.20 | 18.2 | 0 | 0.00 | [scheduled sale made in untimed prep] POOL/RUN LIMITED (A pool 300 B pool 1500, window 0.80s) |
| POST | `/api/v1/admin/flash-sales/{id}/duplicate` | Admin/Cashier | 5.18 | 6.52 | 2869 | 284.9 | 55.95 | 53.06 | 70.02 | 72.00 | 86.54 | 113.7 | 0 | 0.00 | [copies a 3-product sale into a new time slot] |

## 3. Summary

### 15 slowest by P95 (16 users)

| # | Endpoint | P95 ms | P99 ms | TPS |
|---|---|---:|---:|---:|
| 1 | `GET /api/v1/admin/reports/summary cache-defeating: from=random day in last 300 days, to=today` | 2505.80 | 2812.23 | 10.7 |
| 2 | `GET /api/v1/pos/holds ` | 1462.82 | 1798.47 | 18.4 |
| 3 | `GET /api/v1/admin/audit-log latest page` | 658.54 | 738.18 | 38.3 |
| 4 | `PUT /api/v1/admin/me/approval-pin ` | 515.39 | 712.19 | 62.8 |
| 5 | `GET /api/v1/admin/reports/credit period=month` | 498.88 | 637.81 | 48.1 |
| 6 | `GET /api/v1/admin/credit/accounts ` | 349.56 | 426.25 | 67.7 |
| 7 | `GET /api/v1/admin/audit-log action=SALE, page 0-49` | 334.68 | 400.05 | 68.8 |
| 8 | `POST /api/v1/pos/credit/payments Rs 1 cash repayment against a bench debtor` | 124.20 | 155.03 | 204.8 |
| 9 | `GET /api/v1/pos/reports/day ` | 121.48 | 142.40 | 182.7 |
| 10 | `POST /api/v1/pos/sync/sales 2 offline bills per upload` | 115.16 | 148.88 | 203.5 |
| 11 | `GET /api/v1/pos/shifts/current cashier has an open shift (opened for this case)` | 94.28 | 118.24 | 245.7 |
| 12 | `GET /api/v1/admin/pos/shifts ` | 92.14 | 116.29 | 245.8 |
| 13 | `POST /api/v1/admin/purchases 3 lines` | 85.24 | 125.17 | 374.6 |
| 14 | `POST /api/v1/admin/flash-sales/{id}/duplicate copies a 3-product sale into a new time slot` | 72.00 | 86.54 | 284.9 |
| 15 | `POST /api/v1/admin/flash-sales scheduled sale with 3 products, own time slot` | 71.69 | 81.42 | 355.5 |

### 15 slowest by P99 (16 users)

| # | Endpoint | P99 ms | P95 ms | Max ms |
|---|---|---:|---:|---:|
| 1 | `GET /api/v1/admin/reports/summary cache-defeating: from=random day in last 300 days, to=today` | 2812.23 | 2505.80 | 2823.7 |
| 2 | `GET /api/v1/pos/holds ` | 1798.47 | 1462.82 | 2179.1 |
| 3 | `GET /api/v1/admin/audit-log latest page` | 738.18 | 658.54 | 760.7 |
| 4 | `PUT /api/v1/admin/me/approval-pin ` | 712.19 | 515.39 | 994.8 |
| 5 | `GET /api/v1/admin/reports/credit period=month` | 637.81 | 498.88 | 706.0 |
| 6 | `GET /api/v1/admin/credit/accounts ` | 426.25 | 349.56 | 489.2 |
| 7 | `GET /api/v1/admin/audit-log action=SALE, page 0-49` | 400.05 | 334.68 | 525.4 |
| 8 | `POST /api/v1/admin/stock-takes 5 lines set to 2,000,000 so later cases keep stock` | 185.82 | 53.27 | 399.0 |
| 9 | `POST /api/v1/pos/credit/payments Rs 1 cash repayment against a bench debtor` | 155.03 | 124.20 | 291.8 |
| 10 | `POST /api/v1/pos/sync/sales 2 offline bills per upload` | 148.88 | 115.16 | 198.1 |
| 11 | `GET /api/v1/pos/reports/day ` | 142.40 | 121.48 | 194.9 |
| 12 | `POST /api/v1/admin/purchases 3 lines` | 125.17 | 85.24 | 302.5 |
| 13 | `GET /api/v1/pos/shifts/current cashier has an open shift (opened for this case)` | 118.24 | 94.28 | 165.6 |
| 14 | `GET /api/v1/admin/pos/shifts ` | 116.29 | 92.14 | 159.3 |
| 15 | `POST /api/v1/admin/flash-sales/{id}/duplicate copies a 3-product sale into a new time slot` | 86.54 | 72.00 | 113.7 |

### 10 lowest throughput (16 users)

| # | Endpoint | TPS | Avg ms | P95 ms | Note |
|---|---|---:|---:|---:|---|
| 1 | `GET /api/v1/admin/reports/summary cache-defeating: from=random day in last 300 days, to=today` | 10.7 | 1402.51 | 2505.80 |  |
| 2 | `GET /api/v1/pos/holds ` | 18.4 | 851.31 | 1462.82 |  |
| 3 | `GET /api/v1/admin/audit-log latest page` | 38.3 | 411.16 | 658.54 |  |
| 4 | `GET /api/v1/admin/reports/credit period=month` | 48.1 | 328.93 | 498.88 |  |
| 5 | `PUT /api/v1/admin/me/approval-pin ` | 62.8 | 254.08 | 515.39 |  |
| 6 | `GET /api/v1/admin/credit/accounts ` | 67.7 | 234.49 | 349.56 |  |
| 7 | `GET /api/v1/admin/audit-log action=SALE, page 0-49` | 68.8 | 230.65 | 334.68 |  |
| 8 | `GET /api/v1/pos/reports/day ` | 182.7 | 87.24 | 121.48 |  |
| 9 | `POST /api/v1/pos/sync/sales 2 offline bills per upload` | 203.5 | 78.38 | 115.16 |  |
| 10 | `POST /api/v1/pos/credit/payments Rs 1 cash repayment against a bench debtor` | 204.8 | 77.94 | 124.20 |  |

### Endpoints with errors

| Endpoint | Errors (A+B) | Status codes | First unexpected response |
|---|---:|---|---|
| `POST /api/v1/products/{productId}/reviews verified purchase: fresh delivered order made in untimed prep` | 1 | 200x1499 409x1 | 409 {"code":"ALREADY_EXISTS","message":"This already exists","traceId":"2f03847f","details":{}} |
| `POST /api/v1/admin/coupons not-yet-started coupon` | 1 | 201x13031 500x1 | 500 {"code":"INTERNAL_ERROR","message":"Something went wrong","traceId":"1b8a8443","details":{}} |

### Endpoints with P99 above 1 s (16 users)

| Endpoint | P99 ms | P95 ms | Max ms | TPS |
|---|---:|---:|---:|---:|
| `GET /api/v1/pos/holds ` | 1798.5 | 1462.8 | 2179.1 | 18.4 |
| `GET /api/v1/admin/reports/summary cache-defeating: from=random day in last 300 days, to=today` | 2812.2 | 2505.8 | 2823.7 | 10.7 |

### Cases where a prepared pool ran dry or a run hung

- `POST /api/v1/auth/otp/verify existing user login; the code is sent in untimed prep`: A pool 700 B pool 3500 (A limited=true, B limited=true, B window 1.77 s)
- `POST /api/v1/auth/otp/verify-mobile first login of a new mobile user; code sent in untimed prep`: A pool 700 B pool 3500 (A limited=true, B limited=true, B window 2.05 s)
- `POST /api/v1/auth/logout `: A pool 700 B pool 3500 (A limited=true, B limited=true, B window 0.85 s)
- `DELETE /api/v1/me/addresses/{id} `: A pool 700 B pool 3500 (A limited=true, B limited=true, B window 0.86 s)
- `DELETE /api/v1/me deletes a fresh account created in untimed prep`: A pool 300 B pool 1500 (A limited=true, B limited=true, B window 1.03 s)
- `DELETE /api/v1/me/wishlist/{productId} product saved in untimed prep`: A pool 700 B pool 3500 (A limited=true, B limited=true, B window 0.65 s)
- `POST /api/v1/products/{productId}/reviews verified purchase: fresh delivered order made in untimed prep`: A pool 300 B pool 1200 (A limited=true, B limited=true, B window 0.90 s)
- `POST /api/v1/orders/{id}/cancel PLACED order created in untimed prep`: A pool 700 B pool 3500 (A limited=true, B limited=true, B window 2.54 s)
- `POST /api/v1/admin/orders/{id}/status PLACED -> CONFIRMED (deducts stock); order created in untimed prep`: A pool 700 B pool 3500 (A limited=true, B limited=true, B window 3.15 s)
- `POST /api/v1/admin/orders/{id}/reject order created in untimed prep`: A pool 700 B pool 3500 (A limited=true, B limited=true, B window 2.76 s)
- `POST /api/v1/admin/orders/{id}/returns return of 1 of 2 units, restocked; delivered order made in untimed prep`: A pool 300 B pool 1500 (A limited=true, B limited=true, B window 2.54 s)
- `DELETE /api/v1/admin/hot-banners/{id} `: A pool 700 B pool 3500 (A limited=true, B limited=true, B window 1.53 s)
- `DELETE /api/v1/admin/units/{code} `: A pool 700 B pool 3500 (A limited=true, B limited=true, B window 3.22 s)
- `DELETE /api/v1/admin/coupons/{id} `: A pool 700 B pool 3500 (A limited=true, B limited=true, B window 1.89 s)
- `DELETE /api/v1/admin/coin-rules/{id} `: A pool 700 B pool 3500 (A limited=true, B limited=true, B window 1.35 s)
- `DELETE /api/v1/admin/shop-profile/logo logo uploaded in untimed prep`: A pool 60 B pool 150 (A limited=true, B limited=true, B window 0.14 s)
- `POST /api/v1/pos/sales/{id}/returns return 1 of 2 units; bill made in untimed prep`: A pool 300 B pool 1500 (A limited=true, B limited=true, B window 3.32 s)
- `DELETE /api/v1/pos/holds/{id} `: A pool 700 B pool 3500 (A limited=true, B limited=true, B window 1.85 s)
- `POST /api/v1/pos/shifts/open a fresh cashier account is created and signed in during untimed prep`: A pool 150 B pool 700 (A limited=true, B limited=true, B window 0.52 s)
- `POST /api/v1/pos/shifts/close fresh cashier with open shift made in untimed prep; counted = opening float`: A pool 150 B pool 700 (A limited=true, B limited=true, B window 0.75 s)
- `POST /api/v1/pos/stock/defective/{id}/review RESTOCK decision; defective batch from a no-restock return made in untimed prep`: A pool 150 B pool 600 (A limited=true, B limited=true, B window 0.51 s)
- `POST /api/v1/admin/stock/defective/{id}/resolve WRITE_OFF; batch already confirmed defective in untimed prep`: A pool 150 B pool 600 (A limited=true, B limited=true, B window 0.38 s)
- `POST /api/v1/admin/pos/offline-exceptions/{id}/review OVERSOLD exceptions created by uploading offline bills for a zero-stock product in untimed prep`: A pool 325 B pool 1525 (A limited=true, B limited=true, B window 0.71 s)
- `POST /api/v1/admin/flash-sales/{id}/items sale + 1 item made in untimed prep; the call adds a second product`: A pool 300 B pool 1500 (A limited=true, B limited=true, B window 3.37 s)
- `PUT /api/v1/admin/flash-sales/{id}/items/{itemId} sale with an item made in untimed prep`: A pool 300 B pool 1500 (A limited=true, B limited=true, B window 3.00 s)
- `DELETE /api/v1/admin/flash-sales/{id}/items/{itemId} sale with 2 items made in untimed prep`: A pool 300 B pool 1500 (A limited=true, B limited=true, B window 0.97 s)
- `POST /api/v1/admin/flash-sales/{id}/start-now scheduled sale made in untimed prep; started sales are ended again afterwards`: A pool 300 B pool 1200 (A limited=true, B limited=true, B window 2.26 s)
- `POST /api/v1/admin/flash-sales/{id}/end-now running sale made in untimed prep`: A pool 300 B pool 1200 (A limited=true, B limited=true, B window 0.64 s)
- `POST /api/v1/admin/flash-sales/{id}/cancel scheduled sale made in untimed prep`: A pool 300 B pool 1500 (A limited=true, B limited=true, B window 0.80 s)

## 4. Not benchmarked / benchmarked only as a rejection path

| Endpoint | Status | Reason |
|---|---|---|
| `POST /api/v1/payments/webhook/{provider}` | Measured as a rejection path only | A valid call needs a Razorpay signature made with the saved webhook secret (not configured). The unsigned call is rejected with 400 `BAD_SIGNATURE`; the row measures that rejection (signature check), not payment processing. |
| `POST /api/v1/admin/refunds/{id}/retry` | Measured as a rejection path only | Retrying needs a refund in FAILED state, which only arises from a failed Razorpay refund; the DB has no refund rows. A random id answers 409 `REFUND_NOT_FAILED`; the row measures that rejection. |
| `POST /api/v1/admin/config/test/razorpay` | Not benchmarked | Calls the external Razorpay API with the saved keys (none saved; it would just answer "keys not saved" and would hit the provider once configured). |
| `POST /api/v1/admin/config/test/fcm` | Not benchmarked | Signs in to Firebase (external provider). |
| `POST /api/v1/admin/config/test/sms` | Not benchmarked | Sends a real SMS through the configured HTTP provider (external). |
| `POST /api/v1/admin/config/test/email` | Not benchmarked | Sends a real e-mail through SMTP (external). |
| `POST /api/v1/orders` with `paymentMethod=ONLINE` and the Razorpay payment session | Not benchmarked | ONLINE is disabled and unconfigured (no Razorpay keys); would call Razorpay to create the payment order. COD ordering is benchmarked instead. |
| Live-event streams (`GET /orders/stream`, `GET /admin/orders/stream`) | Benchmarked as time-to-first-byte only | Long-lived SSE connections (up to 10 min); the row is the time until the first byte arrives, then the connection is closed. These are the only two SSE endpoints in the controllers (there is no separate "live events" endpoint). |

## 5. JVM and database facts (Prometheus, before and after the run)

| Metric | Before | After |
|---|---:|---:|
| Process CPU usage (instant, 0-1) | 0.000 | 0.002 |
| System CPU usage (instant, 0-1) | 0.000 | 0.589 |
| Heap used (MiB) | 861.742 | 553.808 |
| Heap max (MiB) | 8192.000 | 8192.000 |
| Live threads | 28.000 | 34.000 |
| Hikari active / pending (instant) | 0 / 0 | 0 / 0 |
| Hikari max connections | 10.000 | 10.000 |
| Hikari acquire timeouts (total) | 0.000 | 1.000 |
| Hikari connections acquired (total) | 128.000 | 8190401.000 |
| GC pauses (count) | 0 | 901.000 |
| GC pause time total (s) | 0 | 3.513 |
| HTTP server requests (total) | 1.000 | 4364065.000 |
| HTTP 5xx responses (total) | 0 | 1.000 |
| Uptime (s) | 196.988 | 2001.081 |

Sampled every 5 s during the whole run (350 samples): Hikari active max 10, Hikari pending max 22, process CPU avg 0.19 max 0.87, system CPU avg 0.62 max 1.00.

## 6. Anomalies and suspected bugs

1. **POST /admin/coupons (not-yet-started coupon): one HTTP 500 in 13,032 requests** (`INTERNAL_ERROR`, traceId 1b8a8443). Cause not confirmed (the application log of that run was discarded and the request was not reproduced). The server counters for the final-jar stage show exactly one HTTP 5xx and exactly one Hikari connection-acquire timeout, and the Hikari pool (10 connections in dev) had up to 22 requests waiting during the run, so connection-pool exhaustion under 16 users plus the harness's parallel prep threads is the most likely cause.
2. **GET /pos/holds returns every held basket, unpaged**, so after the benchmark had created about 23,000 holds it took P95 1.46 s and P99 1.80 s at 16 users (about 18 TPS). Documented as "all held baskets, not paged"; capped to 100 later in commit 52ab049 (not in the measured jar).
3. **POST /admin/refunds/{id}/retry with an unknown id answers 409 `REFUND_NOT_FAILED` instead of 404 `NOT_FOUND`.** (Measured as a rejection path; no FAILED refund can be produced without Razorpay.)
4. **SSE streams answered a few 500s on jar 1ef9503** (1 and 2 requests in the before run) because the error body could not be written with content type `text/event-stream` (`HttpMessageNotWritableException: No converter for ErrorBody`); fixed in 96d2213 (see before/after).
5. **A multipart endpoint called without a multipart body answered 500 `Unhandled error` (MultipartException) instead of 400 on jar 1ef9503** (seen when the harness posted the shop logo without a file); fixed in 96d2213 per the commit notes, not re-tested here.
6. **Concurrent first-time `PUT /admin/config` produced duplicate-key warnings (SQLState 23505) on `app_setting`** on jar 1ef9503; fixed in 96d2213 per the commit notes.
7. **Unbounded lists slow down as data accumulates** (Appendix A): with about 2,600 extra coin rules, coupons, banners and categories, unpaged admin lists and paths that read them slow down measurably. Several lists are unpaged by design (`/admin/coupons`, `/admin/coin-rules`, `/admin/hot-banners`, `/admin/units`, `/admin/staff`, `/me/addresses`, `/pos/holds`).
8. **Slowest queries**: `/admin/reports/summary` with a cache-defeating date range (P95 2.5 s, about 10 TPS), the audit log (P95 659 ms on the newest page), credit reports and the credit accounts list. See the tables.
9. **`PUT /admin/me/approval-pin` P95 515 ms at 16 users** (about 63 TPS): consistent with deliberate slow hashing of the PIN; not verified in code.
10. **The dev profile logs application code at DEBUG** (`com.vijay.mall: DEBUG` in `application-dev.yml`; `GET /admin/logs/status` reports `levelApp=DEBUG`), so all numbers include debug logging cost.
11. **One review POST returned 409 `ALREADY_EXISTS`** in 1,500 calls: the harness drew the same customer and product twice from its pool (harness collision, not a server fault).
12. **Pool-limited consuming cases.** Every case that consumes a prepared target (cancel, reject, status, return, delete, review, shifts, flash-sale item/start/end/cancel, defective, offline-exception review, logout, OTP verify, delete account) used up its pool before the 10 s window ended, so its 16-user numbers cover the active part of the window only (TPS is computed over that time); latencies remain valid. They are flagged in the table.
13. **Hikari pool pressure with 10 connections:** during the final-jar stage the sampler saw up to 22 pending connection requests (pool size 10) and 1 acquire timeout; this is under 16 concurrent users plus up to 16 prep threads on the same pool, which is more than one-user-per-request production load, but shows the pool is the first limit hit.

### App log ERROR lines

Not available for this run: the application under test was started with its console log going to /dev/null, so there is no log to count ERROR lines from. What is known from the earlier jar (1ef9503, DEBUG logging captured in app.log): its `GlobalExceptionHandler : Unhandled error` ERROR lines came from (a) SSE error bodies that could not be written as text/event-stream (HttpMessageNotWritableException) and (b) the multipart endpoint called without a multipart body (MultipartException), plus duplicate-key WARNs on `app_setting`. Server-side 5xx counts for this run are in the Prometheus facts above.

## 7. Before / after (old jar 1ef9503 vs the later jars)

Before = `before-results.tsv` (jar 1ef9503, tables at seed size, measured just before the swap). After = the same case in the final merged results: the product, product-group, admin/products and admin/orders variants were re-measured on the final jar (334f873 + d9d08ed); the others come from the first stage on jar 96d2213 (see section 1). Benchmark rows were cleaned up in both. Same method, same machine, single run each: differences within roughly 20 percent are noise.

| Endpoint | 1u P50 before | 1u P50 after | TPS before | TPS after | P95 before | P95 after | P99 before | P99 after | Errors before / after |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| `GET /api/v1/categories` | 0.14 | 0.14 | 24020.57 | 24554.19 | 1.08 | 1.01 | 1.65 | 1.58 | 0 / 0 |
| `GET /api/v1/products [page 0-99, size 20]` | 19.75 | 0.73 | 239.69 | 5891.07 | 99.82 | 5.00 | 120.98 | 8.49 | 0 / 0 |
| `GET /api/v1/products [search q=Item <n>]` | 1.75 | 1.15 | 2398.92 | 3268.47 | 11.94 | 8.14 | 17.11 | 11.68 | 0 / 0 |
| `GET /api/v1/products [by category, hot=false]` | 8.72 | 0.45 | 538.47 | 6564.68 | 48.84 | 4.36 | 64.24 | 5.89 | 0 / 0 |
| `GET /api/v1/product-groups [page 0-99, size 20]` | 23.81 | 1.17 | 170.68 | 2896.68 | 148.36 | 8.71 | 183.83 | 10.90 | 0 / 0 |
| `GET /api/v1/hot-sale` | 3.12 | 3.32 | 1404.37 | 1374.22 | 19.67 | 20.16 | 25.27 | 26.74 | 0 / 0 |
| `GET /api/v1/coupons` | 0.14 | 0.14 | 23717.27 | 24398.58 | 1.08 | 1.02 | 1.58 | 1.52 | 0 / 0 |
| `POST /api/v1/cart/quote` | 0.66 | 0.66 | 4358.87 | 4923.21 | 7.48 | 6.23 | 11.16 | 8.81 | 0 / 0 |
| `POST /api/v1/orders [COD, delivery, 1-2 units of one of 60 products]` | 3.18 | 3.47 | 1097.97 | 1079.33 | 21.85 | 22.28 | 27.58 | 28.33 | 0 / 0 |
| `GET /api/v1/orders/stream [SSE: time to first byte, then close]` | 0.22 | 0.22 | 11259.06 | 12478.12 | 2.16 | 1.87 | 3.45 | 2.85 | 1 / 0 |
| `GET /api/v1/admin/orders [page 0-49, size 20]` | 15.16 | 2.63 | 170.38 | 1511.43 | 157.06 | 15.56 | 194.87 | 19.18 | 0 / 0 |
| `GET /api/v1/admin/orders [status=PLACED, page 0-49]` | 14.89 | 11.94 | 164.90 | 364.39 | 151.80 | 63.25 | 183.93 | 75.61 | 0 / 0 |
| `GET /api/v1/admin/orders [search q=order number]` | 3.36 | 4.12 | 1270.43 | 994.94 | 22.19 | 23.33 | 31.89 | 29.30 | 0 / 0 |
| `GET /api/v1/admin/orders/stream [SSE: time to first byte, then close]` | 0.37 | 0.38 | 7564.50 | 7737.24 | 3.28 | 3.30 | 5.38 | 5.51 | 2 / 0 |
| `GET /api/v1/admin/products [page 0-99, size 20]` | 12.31 | 0.56 | 373.94 | 5627.37 | 68.17 | 4.75 | 89.04 | 6.41 | 0 / 0 |
| `GET /api/v1/admin/products [search q=Item <n>]` | 1.84 | 1.28 | 2559.99 | 3095.69 | 11.89 | 7.95 | 16.21 | 10.00 | 0 / 0 |
| `POST /api/v1/admin/products/{id}/image [multipart, 96x96 PNG]` | 6.40 | 9.43 | 655.71 | 530.60 | 31.97 | 43.34 | 39.44 | 54.51 | 0 / 0 |
| `POST /api/v1/admin/shop-profile/logo [multipart, 96x96 PNG]` | 5.73 | 6.35 | 473.64 | 485.50 | 80.33 | 58.86 | 141.31 | 81.20 | 0 / 0 |
| `DELETE /api/v1/admin/shop-profile/logo [logo uploaded in untimed prep]` | 3.14 | 1.33 | 725.65 | 1097.00 | 57.33 | 28.52 | 97.10 | 33.40 | 0 / 0 |
| `PUT /api/v1/admin/config [toggles pos.cashierReturnDays 7/8; cleared afterwards]` | 3.56 | 3.31 | 1492.16 | 1480.11 | 39.17 | 28.95 | 67.01 | 43.53 | 0 / 0 |

## Appendix A. First run with 2,600 extra rows (polluted tables), kept as a stress result

This partial first run (the first 85 cases, old jar 1ef9503) was executed while the benchmark's own create cases had left debris in the database and nothing cleaned it up: by the time the run was stopped the tables held coin_rule 2,596 (seed 1), coupon 1,473 (seed 1), hot_banner 2,806 (seed 0), category 2,596 (seed 5), unit_of_measure 1,696 (seed 10), flash_sale 1,467 (seed 0), product 43,884 (seed 20,013). Unpaged lists return every row, so rows measured after the debris built up (admin lists of coin rules, coupons, banners, units, categories, the cart/quote and order paths that read coin rules and coupons) are slower than on a normal shop. They are NOT the headline numbers; the clean numbers are in section 2. The run was stopped when this was noticed.

| Method | Path | 1u P50 | 1u P95 | TPS | P50 | P95 | P99 | Errors |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| POST | `/api/v1/auth/otp/send`  | 2.10 | 3.16 | 3470.97 | 4.25 | 7.06 | 9.55 | 0 |
| POST | `/api/v1/auth/otp/verify` [existing user login; the code is sent in untimed prep] | 3.40 | 4.38 | 1798.78 | 8.53 | 12.68 | 15.49 | 3 |
| POST | `/api/v1/auth/otp/send-mobile`  | 2.09 | 3.12 | 3768.93 | 4.04 | 5.86 | 7.66 | 0 |
| POST | `/api/v1/auth/otp/verify-mobile` [first login of a new mobile user; code sent in untimed prep] | 4.27 | 5.50 | 1490.04 | 10.38 | 15.37 | 19.23 | 0 |
| POST | `/api/v1/auth/refresh` [each user rotates its own refresh-token chain] | 3.35 | 4.64 | 2073.23 | 7.39 | 11.05 | 13.47 | 0 |
| POST | `/api/v1/auth/logout`  | 1.86 | 2.97 | 3125.41 | 4.81 | 7.34 | 9.17 | 0 |
| GET | `/api/v1/me`  | 0.54 | 0.77 | 6305.00 | 2.28 | 4.60 | 6.49 | 0 |
| PUT | `/api/v1/me`  | 3.48 | 4.63 | 2004.13 | 7.65 | 12.19 | 14.87 | 0 |
| GET | `/api/v1/me/addresses`  | 0.43 | 0.71 | 10309.74 | 1.40 | 2.83 | 4.48 | 0 |
| POST | `/api/v1/me/addresses`  | 3.55 | 4.18 | 2415.09 | 6.35 | 9.91 | 12.31 | 0 |
| PUT | `/api/v1/me/addresses/{id}`  | 3.42 | 4.14 | 2387.56 | 6.29 | 10.36 | 13.53 | 0 |
| DELETE | `/api/v1/me/addresses/{id}`  | 1.81 | 3.13 | 3682.68 | 4.07 | 6.46 | 8.50 | 0 |
| POST | `/api/v1/me/devices`  | 3.03 | 3.91 | 3028.78 | 5.06 | 7.74 | 9.76 | 0 |
| DELETE | `/api/v1/me` [deletes a fresh account created in untimed prep] | 7.00 | 8.81 | 925.83 | 16.75 | 25.08 | 32.30 | 0 |
| GET | `/api/v1/me/notifications`  | 0.53 | 1.59 | 7173.27 | 1.99 | 4.24 | 6.33 | 0 |
| POST | `/api/v1/me/notifications/{id}/read` [idempotent; one notification id per user] | 0.44 | 0.55 | 4182.24 | 0.83 | 1.71 | 3.55 | 41835 |
| POST | `/api/v1/me/notifications/read-all`  | 0.52 | 1.28 | 7483.50 | 1.80 | 4.62 | 7.16 | 0 |
| GET | `/api/v1/me/wishlist`  | 0.37 | 0.80 | 11107.99 | 1.24 | 2.87 | 4.78 | 0 |
| POST | `/api/v1/me/wishlist/{productId}` [cycles 150 products per user: first passes insert, later passes are no-ops] | 1.52 | 3.00 | 2598.84 | 5.20 | 12.16 | 16.79 | 0 |
| DELETE | `/api/v1/me/wishlist/{productId}` [product saved in untimed prep] | 1.39 | 2.50 | 4603.74 | 3.43 | 6.05 | 7.95 | 0 |
| GET | `/api/v1/wallet`  | 0.47 | 0.86 | 7622.77 | 1.83 | 4.11 | 6.14 | 0 |
| GET | `/api/v1/wallet/ledger`  | 0.36 | 0.70 | 11400.74 | 1.24 | 2.59 | 4.21 | 0 |
| GET | `/api/v1/credit`  | 0.70 | 2.14 | 5181.86 | 2.64 | 6.13 | 8.95 | 0 |
| GET | `/api/v1/credit/ledger`  | 0.92 | 1.43 | 5192.93 | 2.53 | 6.80 | 11.18 | 0 |
| GET | `/api/v1/categories`  | 0.15 | 0.21 | 23919.64 | 0.63 | 1.05 | 1.60 | 0 |
| GET | `/api/v1/products` [page 0-99, size 20] | 27.31 | 31.46 | 184.64 | 83.38 | 128.94 | 154.34 | 0 |
| GET | `/api/v1/products` [search q=Item <n>] | 2.61 | 4.02 | 2014.87 | 7.09 | 15.20 | 22.22 | 0 |
| GET | `/api/v1/products` [by category, hot=false] | 0.91 | 1.46 | 5447.44 | 2.48 | 6.17 | 9.98 | 0 |
| GET | `/api/v1/products/{id}`  | 0.95 | 2.52 | 3994.96 | 3.40 | 7.99 | 11.48 | 0 |
| GET | `/api/v1/product-groups` [page 0-99, size 20] | 30.72 | 36.34 | 150.47 | 100.50 | 164.67 | 201.70 | 0 |
| GET | `/api/v1/product-groups/{id}`  | 1.07 | 3.18 | 2987.52 | 4.29 | 12.15 | 21.42 | 0 |
| GET | `/api/v1/hot-sale`  | 4.89 | 6.15 | 1118.26 | 13.24 | 24.36 | 31.62 | 0 |
| GET | `/api/v1/units`  | 0.17 | 0.24 | 23827.43 | 0.61 | 1.12 | 1.69 | 0 |
| GET | `/api/v1/coupons`  | 0.18 | 0.26 | 24351.81 | 0.59 | 1.12 | 1.86 | 0 |
| GET | `/api/v1/products/{productId}/reviews`  | 1.64 | 3.23 | 2617.68 | 5.37 | 11.48 | 15.50 | 0 |
| GET | `/api/v1/product-groups/{id}/reviews`  | 1.76 | 3.95 | 2188.90 | 6.19 | 14.55 | 24.20 | 0 |
| GET | `/api/v1/products/{productId}/reviews/mine`  | 2.43 | 3.93 | 2458.67 | 5.82 | 11.94 | 16.37 | 0 |
| POST | `/api/v1/products/{productId}/reviews` [verified purchase: fresh delivered order made in untimed prep] | 5.08 | 7.07 | 1077.47 | 13.94 | 23.00 | 29.06 | 3 |
| POST | `/api/v1/cart/quote`  | 1.19 | 2.47 | 4133.14 | 3.48 | 7.12 | 9.87 | 0 |
| GET | `/api/v1/cart`  | 0.30 | 0.46 | 12705.09 | 1.11 | 2.39 | 3.77 | 0 |
| PUT | `/api/v1/cart`  | 5.00 | 6.52 | 1300.30 | 11.77 | 18.91 | 23.29 | 0 |
| DELETE | `/api/v1/cart`  | 1.78 | 2.79 | 4140.37 | 3.35 | 7.78 | 11.15 | 0 |
| POST | `/api/v1/orders` [COD, delivery, 1-2 units of one of 60 products] | 7.78 | 9.62 | 859.94 | 17.79 | 27.25 | 32.75 | 0 |
| GET | `/api/v1/orders`  | 0.59 | 0.89 | 6460.61 | 2.22 | 4.62 | 6.87 | 0 |
| GET | `/api/v1/orders/{id}`  | 0.98 | 2.35 | 3883.49 | 3.54 | 8.10 | 11.31 | 0 |
| POST | `/api/v1/orders/{id}/cancel` [PLACED order created in untimed prep] | 5.52 | 7.21 | 1228.86 | 12.44 | 19.08 | 24.16 | 0 |
| GET | `/api/v1/orders/{id}/reorder`  | 1.52 | 2.98 | 2805.42 | 5.20 | 9.73 | 13.45 | 0 |
| GET | `/api/v1/orders/{id}/returns`  | 0.66 | 1.13 | 5823.54 | 2.42 | 5.18 | 7.33 | 0 |
| GET | `/api/v1/orders/stream` [SSE: time to first byte, then close] | 0.24 | 0.33 | 11255.50 | 1.27 | 2.13 | 3.51 | 2 |
| GET | `/api/v1/payment-methods`  | 0.18 | 0.23 | 24430.48 | 0.59 | 1.08 | 1.98 | 0 |
| POST | `/api/v1/payments/webhook/{provider}` [REJECTION PATH: unsigned call answers 400 BAD_SIGNATURE; a valid signed webhook cannot be produced] | 0.32 | 0.42 | 11123.60 | 1.30 | 2.28 | 3.57 | 0 |
| GET | `/api/v1/admin/payment-methods`  | 0.53 | 1.24 | 6945.05 | 1.99 | 4.61 | 6.88 | 0 |
| PUT | `/api/v1/admin/payment-methods` [re-saves COD enabled] | 2.45 | 4.41 | 2501.53 | 6.07 | 9.54 | 12.23 | 0 |
| GET | `/api/v1/admin/refunds`  | 0.48 | 0.67 | 8015.03 | 1.76 | 3.84 | 5.59 | 0 |
| POST | `/api/v1/admin/refunds/{id}/retry` [REJECTION PATH: no FAILED refund exists (needs Razorpay); unknown id answers 409 REFUND_NOT_FAILED] | 0.74 | 1.30 | 5142.73 | 2.77 | 5.77 | 7.90 | 0 |
| GET | `/api/v1/admin/payments/anomalies`  | 0.48 | 0.67 | 7936.32 | 1.79 | 3.91 | 5.62 | 0 |
| GET | `/api/v1/admin/orders` [page 0-49, size 20] | 18.13 | 20.23 | 166.37 | 91.76 | 145.94 | 185.62 | 0 |
| GET | `/api/v1/admin/orders` [status=PLACED, page 0-49] | 18.74 | 20.51 | 161.57 | 93.91 | 153.92 | 193.37 | 0 |
| GET | `/api/v1/admin/orders` [search q=order number] | 1.90 | 3.38 | 2629.51 | 4.80 | 10.67 | 15.28 | 0 |
| GET | `/api/v1/admin/orders/{id}`  | 1.33 | 2.82 | 2997.35 | 4.80 | 9.35 | 12.54 | 0 |
| POST | `/api/v1/admin/orders/{id}/status` [PLACED -> CONFIRMED (deducts stock); order created in untimed prep] | 6.17 | 8.05 | 942.18 | 15.83 | 27.30 | 34.51 | 0 |
| POST | `/api/v1/admin/orders/{id}/reject` [order created in untimed prep] | 6.08 | 8.05 | 1077.74 | 14.35 | 21.51 | 26.27 | 0 |
| POST | `/api/v1/admin/orders/{id}/returns` [return of 1 of 2 units, restocked; delivered order made in untimed prep] | 9.32 | 12.54 | 485.88 | 30.77 | 52.57 | 65.53 | 0 |
| GET | `/api/v1/admin/orders/{id}/returns`  | 0.88 | 1.56 | 4181.28 | 3.35 | 7.39 | 10.33 | 0 |
| GET | `/api/v1/admin/orders/stream` [SSE: time to first byte, then close] | 0.45 | 0.80 | 7322.58 | 1.90 | 3.88 | 6.51 | 3 |
| GET | `/api/v1/admin/products` [page 0-99, size 20] | 18.97 | 22.08 | 288.31 | 52.99 | 86.30 | 106.92 | 0 |
| GET | `/api/v1/admin/products` [search q=Item <n>] | 3.63 | 4.88 | 1419.74 | 10.29 | 19.82 | 27.48 | 0 |
| GET | `/api/v1/admin/products/{id}`  | 0.90 | 1.64 | 4065.70 | 3.31 | 7.89 | 11.62 | 0 |
| GET | `/api/v1/admin/products/{id}/variants`  | 1.17 | 6.08 | 2448.85 | 5.01 | 14.29 | 33.02 | 0 |
| GET | `/api/v1/admin/stock-movements` [all products, page 0-19] | 5.92 | 7.36 | 912.90 | 16.07 | 30.04 | 41.10 | 0 |
| GET | `/api/v1/admin/hot-banners`  | 5.67 | 7.06 | 555.19 | 27.19 | 47.06 | 65.17 | 0 |
| GET | `/api/v1/admin/units`  | 4.25 | 5.14 | 962.84 | 12.50 | 28.11 | 211.23 | 0 |
| GET | `/api/v1/admin/coupons`  | 8.27 | 9.37 | 393.71 | 35.00 | 64.06 | 249.18 | 0 |
| GET | `/api/v1/admin/coin-rules`  | 7.75 | 9.42 | 430.51 | 30.40 | 65.61 | 236.35 | 0 |
| GET | `/api/v1/admin/coin-settings`  | 0.35 | 0.55 | 10616.64 | 1.27 | 3.17 | 5.21 | 0 |
| PUT | `/api/v1/admin/coin-settings` [re-saves 100 paise/coin, 20%, 6 months] | 3.13 | 4.57 | 2957.63 | 5.11 | 8.02 | 10.40 | 0 |
| GET | `/api/v1/admin/customers` [page 0-99, size 20] | 20.24 | 22.20 | 262.39 | 58.18 | 93.74 | 117.47 | 0 |
| GET | `/api/v1/admin/customers` [search q=load-<hex>] | 1.05 | 1.86 | 3917.43 | 3.54 | 8.13 | 12.73 | 0 |
| GET | `/api/v1/admin/customers/{id}`  | 1.31 | 2.16 | 2944.58 | 4.71 | 10.56 | 15.20 | 0 |
| PATCH | `/api/v1/admin/customers/{id}` [active=true on bench customers] | 6.02 | 8.15 | 1048.08 | 14.34 | 24.74 | 34.06 | 0 |
| POST | `/api/v1/admin/customers/{id}/coins` [+1 coin on bench customers] | 8.10 | 9.74 | 703.01 | 21.36 | 35.26 | 53.78 | 0 |
| POST | `/api/v1/admin/products`  | 5.28 | 7.72 | 1213.00 | 12.07 | 22.13 | 32.27 | 0 |
| PUT | `/api/v1/admin/products/{id}` [bench product per user; stock omitted] | 5.93 | 7.70 | 1123.52 | 13.51 | 22.05 | 27.96 | 0 |
| POST | `/api/v1/admin/products/{id}/variants`  | 12.17 | 14.93 | 520.40 | 29.13 | 48.43 | 59.11 | 0 |
| PUT | `/api/v1/admin/product-groups/{id}`  | 5.39 | 6.78 | 1262.87 | 11.88 | 20.22 | 27.40 | 0 |

## Appendix B. Row counts of the benchmark-sensitive tables at the start of each case (clean run)

| Case | Table row counts |
|---|---|
| POST `/api/v1/auth/otp/send`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=36683 |
| POST `/api/v1/auth/otp/verify` [existing user login; the code is sent in untimed prep] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=36683 |
| POST `/api/v1/auth/otp/send-mobile`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=36683 |
| POST `/api/v1/auth/otp/verify-mobile` [first login of a new mobile user; code sent in untimed prep] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=36683 |
| POST `/api/v1/auth/refresh` [each user rotates its own refresh-token chain] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=40963 |
| POST `/api/v1/auth/logout`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=40963 |
| GET `/api/v1/me`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=40963 |
| PUT `/api/v1/me`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=40963 |
| GET `/api/v1/me/addresses`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=40963 |
| POST `/api/v1/me/addresses`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=40963 |
| PUT `/api/v1/me/addresses/{id}`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=40963 |
| DELETE `/api/v1/me/addresses/{id}`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=40963 |
| POST `/api/v1/me/devices`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=40963 |
| DELETE `/api/v1/me` [deletes a fresh account created in untimed prep] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=40963 |
| GET `/api/v1/me/notifications`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42843 |
| POST `/api/v1/me/notifications/{id}/read` [idempotent; one notification id per user] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42843 |
| POST `/api/v1/me/notifications/read-all`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42843 |
| GET `/api/v1/me/wishlist`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42843 |
| POST `/api/v1/me/wishlist/{productId}` [cycles 150 products per user: first passes insert, later passes are no-ops] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42843 |
| DELETE `/api/v1/me/wishlist/{productId}` [product saved in untimed prep] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42843 |
| GET `/api/v1/wallet`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42843 |
| GET `/api/v1/wallet/ledger`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42843 |
| GET `/api/v1/credit`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42843 |
| GET `/api/v1/credit/ledger`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42843 |
| GET `/api/v1/categories`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42843 |
| GET `/api/v1/products` [page 0-99, size 20] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42868 |
| GET `/api/v1/products` [search q=Item <n>] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42868 |
| GET `/api/v1/products` [by category, hot=false] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42868 |
| GET `/api/v1/products/{id}`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42843 |
| GET `/api/v1/product-groups` [page 0-99, size 20] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42868 |
| GET `/api/v1/product-groups/{id}`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/hot-sale`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/units`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/coupons`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/products/{productId}/reviews`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/product-groups/{id}/reviews`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/products/{productId}/reviews/mine`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| POST `/api/v1/products/{productId}/reviews` [verified purchase: fresh delivered order made in untimed prep] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| POST `/api/v1/cart/quote`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/cart`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| PUT `/api/v1/cart`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| DELETE `/api/v1/cart`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| POST `/api/v1/orders` [COD, delivery, 1-2 units of one of 60 products] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/orders`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/orders/{id}`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| POST `/api/v1/orders/{id}/cancel` [PLACED order created in untimed prep] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/orders/{id}/reorder`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/orders/{id}/returns`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/orders/stream` [SSE: time to first byte, then close] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/payment-methods`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| POST `/api/v1/payments/webhook/{provider}` [REJECTION PATH: unsigned call answers 400 BAD_SIGNATURE; a valid signed webhook cannot be produced] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/admin/payment-methods`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| PUT `/api/v1/admin/payment-methods` [re-saves COD enabled] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/admin/refunds`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| POST `/api/v1/admin/refunds/{id}/retry` [REJECTION PATH: no FAILED refund exists (needs Razorpay); unknown id answers 409 REFUND_NOT_FAILED] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/admin/payments/anomalies`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/admin/orders` [page 0-49, size 20] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42868 |
| GET `/api/v1/admin/orders` [status=PLACED, page 0-49] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42868 |
| GET `/api/v1/admin/orders` [search q=order number] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42868 |
| GET `/api/v1/admin/orders/{id}`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| POST `/api/v1/admin/orders/{id}/status` [PLACED -> CONFIRMED (deducts stock); order created in untimed prep] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| POST `/api/v1/admin/orders/{id}/reject` [order created in untimed prep] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| POST `/api/v1/admin/orders/{id}/returns` [return of 1 of 2 units, restocked; delivered order made in untimed prep] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/admin/orders/{id}/returns`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/admin/orders/stream` [SSE: time to first byte, then close] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/admin/products` [page 0-99, size 20] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42868 |
| GET `/api/v1/admin/products` [search q=Item <n>] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42868 |
| GET `/api/v1/admin/products/{id}`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/admin/products/{id}/variants`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/admin/stock-movements` [all products, page 0-19] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42868 |
| GET `/api/v1/admin/hot-banners`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/admin/units`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/admin/coupons`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/admin/coin-rules`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/admin/coin-settings`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| PUT `/api/v1/admin/coin-settings` [re-saves 100 paise/coin, 20%, 6 months] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| GET `/api/v1/admin/customers` [page 0-99, size 20] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42868 |
| GET `/api/v1/admin/customers` [search q=load-<hex>] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42868 |
| GET `/api/v1/admin/customers/{id}`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| PATCH `/api/v1/admin/customers/{id}` [active=true on bench customers] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| POST `/api/v1/admin/customers/{id}/coins` [+1 coin on bench customers] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| POST `/api/v1/admin/products`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| PUT `/api/v1/admin/products/{id}` [bench product per user; stock omitted] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42844 |
| POST `/api/v1/admin/products/{id}/variants`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20077 address=24 users=42844 |
| PUT `/api/v1/admin/product-groups/{id}`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20077 address=24 users=42844 |
| POST `/api/v1/admin/products/{id}/stock`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20077 address=24 users=42844 |
| POST `/api/v1/admin/products/{id}/image` [multipart, 96x96 PNG] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20077 address=24 users=42844 |
| POST `/api/v1/admin/categories`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20077 address=24 users=42844 |
| PUT `/api/v1/admin/categories/{id}`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20077 address=24 users=42844 |
| POST `/api/v1/admin/hot-banners`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42876 |
| PUT `/api/v1/admin/hot-banners/{id}`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42876 |
| DELETE `/api/v1/admin/hot-banners/{id}`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42876 |
| POST `/api/v1/admin/units`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42876 |
| PUT `/api/v1/admin/units/{code}`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42876 |
| DELETE `/api/v1/admin/units/{code}`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42876 |
| POST `/api/v1/admin/coupons` [not-yet-started coupon] | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42868 |
| PUT `/api/v1/admin/coupons/{id}`  | coin_rule=1 coupon=1 hot_banner=0 category=5 unit=10 flash_sale=0 product=20013 address=24 users=42868 |
| DELETE `/api/v1/admin/coupons/{id}`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20013 address=24 users=42900 |
| POST `/api/v1/admin/coin-rules`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20013 address=24 users=42900 |
| PUT `/api/v1/admin/coin-rules/{id}`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20013 address=24 users=42900 |
| DELETE `/api/v1/admin/coin-rules/{id}`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20013 address=24 users=42900 |
| GET `/api/v1/shop-settings`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20013 address=24 users=42900 |
| GET `/api/v1/admin/shop-profile`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20013 address=24 users=42900 |
| PUT `/api/v1/admin/shop-profile` [opening hours + holiday note; restored afterwards] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20013 address=24 users=42900 |
| POST `/api/v1/admin/shop-profile/logo` [multipart, 96x96 PNG] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20013 address=24 users=42900 |
| DELETE `/api/v1/admin/shop-profile/logo` [logo uploaded in untimed prep] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20013 address=24 users=42900 |
| GET `/api/v1/admin/shop-settings`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20013 address=24 users=42900 |
| PUT `/api/v1/admin/shop-settings` [bill header details; promise text varies; restored afterwards] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20013 address=24 users=42900 |
| GET `/api/v1/admin/staff`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20013 address=24 users=42900 |
| POST `/api/v1/admin/staff`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20013 address=24 users=42900 |
| PATCH `/api/v1/admin/staff/{id}`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20013 address=24 users=42900 |
| GET `/api/v1/admin/products/{id}/pos`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20013 address=24 users=42900 |
| PUT `/api/v1/admin/products/{id}/pos` [counter attributes + barcode of a bench product] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20013 address=24 users=42900 |
| PUT `/api/v1/admin/me/approval-pin`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42900 |
| GET `/api/v1/pos/shop`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42900 |
| GET `/api/v1/pos/barcode/{code}`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42900 |
| GET `/api/v1/pos/products` [q=Item <n>] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42900 |
| POST `/api/v1/pos/quote`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42900 |
| POST `/api/v1/pos/sales` [cash bill, 1-3 units of one of 60 products] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42900 |
| GET `/api/v1/pos/sales` [today's bills, latest 200] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42900 |
| GET `/api/v1/pos/sales/{id}`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42900 |
| GET `/api/v1/pos/sales/{id}/receipt`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42900 |
| POST `/api/v1/pos/sales/{id}/returns` [return 1 of 2 units; bill made in untimed prep] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42900 |
| GET `/api/v1/pos/sales/{id}/returns`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42900 |
| GET `/api/v1/pos/upi-link` [UPI id set to vijaymall@okbank for this case, restored afterwards] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42900 |
| POST `/api/v1/pos/holds`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42900 |
| GET `/api/v1/pos/holds`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42900 |
| GET `/api/v1/pos/holds/{id}`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42900 |
| DELETE `/api/v1/pos/holds/{id}`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42900 |
| GET `/api/v1/pos/reports/day`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42900 |
| POST `/api/v1/pos/shifts/open` [a fresh cashier account is created and signed in during untimed prep] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42900 |
| GET `/api/v1/pos/shifts/current` [cashier has an open shift (opened for this case)] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42900 |
| POST `/api/v1/pos/shifts/close` [fresh cashier with open shift made in untimed prep; counted = opening float] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42900 |
| GET `/api/v1/admin/pos/shifts`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42900 |
| GET `/api/v1/pos/sync/status`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42900 |
| POST `/api/v1/pos/sync/sales` [2 offline bills per upload] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42900 |
| GET `/api/v1/pos/credit/account`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42900 |
| GET `/api/v1/pos/credit/ledger`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| POST `/api/v1/pos/credit/payments` [Rs 1 cash repayment against a bench debtor] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| PUT `/api/v1/admin/credit/limit`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/admin/credit/accounts/{customerId}`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/admin/credit/accounts/{customerId}/ledger`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| PUT `/api/v1/admin/credit/accounts/{customerId}/limit`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| POST `/api/v1/admin/credit/accounts/{customerId}/adjust` [+Rs 1 correction] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/pos/stock/defective`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/admin/stock/defective`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/admin/stock/defective/summary`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| POST `/api/v1/pos/stock/defective/{id}/review` [RESTOCK decision; defective batch from a no-restock return made in untimed prep] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| POST `/api/v1/admin/stock/defective/{id}/resolve` [WRITE_OFF; batch already confirmed defective in untimed prep] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/admin/pos/offline-exceptions`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| POST `/api/v1/admin/pos/offline-exceptions/{id}/review` [OVERSOLD exceptions created by uploading offline bills for a zero-stock product in untimed prep] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| POST `/api/v1/admin/purchases` [3 lines] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/admin/purchases`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/admin/purchases/{id}`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| POST `/api/v1/admin/stock-takes` [5 lines set to 2,000,000 so later cases keep stock] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/admin/dashboard`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/admin/reports/summary` [period=month, served from the ~1 min cache] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/admin/reports/summary` [cache-defeating: from=random day in last 300 days, to=today] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/admin/reports/credit` [period=month] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/admin/credit/accounts`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/admin/reports/counter-controls` [period=month] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/admin/stock/low`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/admin/audit-log` [latest page] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/admin/audit-log` [action=SALE, page 0-49] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/admin/config`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| PUT `/api/v1/admin/config` [toggles pos.cashierReturnDays 7/8; cleared afterwards] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/admin/config/bootstrap`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/admin/logs/status`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/admin/cache`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| POST `/api/v1/admin/cache/refresh`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| POST `/api/v1/admin/cache/refresh/{name}` [name=categories] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/flash-sales/active`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/flash-sales/upcoming`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/admin/flash-sales`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/admin/flash-sales/report`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/admin/flash-sales/{id}`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=0 product=20077 address=24 users=42924 |
| GET `/api/v1/admin/flash-sales/{id}/report`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=16 product=20077 address=24 users=42924 |
| POST `/api/v1/admin/flash-sales` [scheduled sale with 3 products, own time slot] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=16 product=20077 address=24 users=42924 |
| PATCH `/api/v1/admin/flash-sales/{id}`  | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=16 product=20077 address=24 users=42924 |
| POST `/api/v1/admin/flash-sales/{id}/items` [sale + 1 item made in untimed prep; the call adds a second product] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=16 product=20077 address=24 users=42924 |
| PUT `/api/v1/admin/flash-sales/{id}/items/{itemId}` [sale with an item made in untimed prep] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=16 product=20077 address=24 users=42924 |
| DELETE `/api/v1/admin/flash-sales/{id}/items/{itemId}` [sale with 2 items made in untimed prep] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=16 product=20077 address=24 users=42924 |
| POST `/api/v1/admin/flash-sales/{id}/start-now` [scheduled sale made in untimed prep; started sales are ended again afterwards] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=16 product=20077 address=24 users=42924 |
| POST `/api/v1/admin/flash-sales/{id}/end-now` [running sale made in untimed prep] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=16 product=20077 address=24 users=42924 |
| POST `/api/v1/admin/flash-sales/{id}/cancel` [scheduled sale made in untimed prep] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=16 product=20077 address=24 users=42924 |
| POST `/api/v1/admin/flash-sales/{id}/duplicate` [copies a 3-product sale into a new time slot] | coin_rule=33 coupon=33 hot_banner=32 category=37 unit=42 flash_sale=16 product=20077 address=24 users=42924 |
