# Operations guide: logs, metrics, alerts, performance

## Logs
- **Production** logs are one JSON object per line (console and `./logs/mall.log`, rolled at 20 MB, 30 days). Every line has
  `traceId`. After sign-in it also has `userId` and `role`. Ship them to Loki / ELK / CloudWatch and filter on those fields.
- **Access log** (logger `access`), one line per API call, with separate fields: `http.method`, `http.path`, `http.status`,
  `durationMs`, `clientIp`, `userId`, `role`. Calls slower than 1 s or answered with a 5xx are logged at WARN.
  Query strings, headers and bodies are never logged.
- The response header `X-Trace-Id` is the same `traceId`: when someone reports a problem, ask for it and search the logs.
- **Startup line** (`event=startup-summary`) shows profile, login channel, payment provider and which keys are set (never their values).
- **Levels:** `LOG_LEVEL_APP` (shop code, default INFO) and `LOG_LEVEL_ROOT` (libraries, default INFO). Set `LOG_LEVEL_APP=DEBUG`
  and restart to chase a problem; nothing needs rebuilding.
- Business events are in the **audit log** (admin app, `GET /api/v1/admin/audit-log`): every sale, return, discount, refund retry,
  stock count, coin adjustment, block/unblock, with who did it.

## Metrics
`http://<host>:9090/actuator/prometheus` (port `MANAGEMENT_PORT`, default 9090). **Keep this port private** (firewall, Docker network):
Prometheus has no login. In production the server refuses to start if it would share the public port.

| Metric | Meaning |
|---|---|
| `http_server_requests_seconds_*` | every API call: rate, errors, latency histogram by endpoint (`uri`) and `status` |
| `mall_order_place_seconds`, `mall_pos_sale_seconds` | time to place an online order / ring up a counter bill, by `outcome` (`ok` or the error code) |
| `mall_orders_placed_total`, `mall_orders_value_paise_total` | online orders and their value, by payment `method` |
| `mall_pos_sales_total`, `mall_pos_sales_value_paise_total` | counter bills and value, by payment `type` |
| `mall_audit_actions_total` | audited actions by kind (SALE, RETURN, MANUAL_DISCOUNT, STOCK_TAKE, ...) |
| `mall_refunds_pending`, `mall_refunds_failed` | refunds waiting / refused by the provider (**failed needs a person**) |
| `mall_outbox_pending` | notifications / alerts not yet delivered |
| `mall_stock_low_products` | products at or below their alert level |
| `mall_orders_by_status{status}` | online orders in each status right now |
| `mall_rate_limited_total{scope}` | calls refused by a rate limit |
| `mall_flash_units_sold_total{channel}`, `mall_flash_revenue_paise_total{channel}`, `mall_flash_discount_paise_total{channel}` | units, revenue and discount of flash sale lines, `ONLINE` or `POS` |
| `mall_flash_refused_total{reason}` | checkouts refused by a flash sale (`LIMIT_REACHED`, `CUSTOMER_LIMIT`, `ENDED`) |
| `mall_flash_sales{state}` | flash sales `active` / `scheduled` now |
| `mall_payments_anomalies_total{kind}` | online payments received in the wrong amount (`MISMATCH`) or twice (`DUPLICATE`); refunded automatically, listed at `GET /api/v1/admin/payments/anomalies` (alert `PaymentAnomaly`) |
| `mall_pos_offline_exceptions_open`, `mall_pos_offline_exceptions_stale`, `mall_pos_offline_exceptions_recorded_total{kind}` | differences in offline counter bills not yet reviewed by a manager / unreviewed for more than a day / new ones by kind (`PRICE_CHANGED`, `GST_CHANGED`, `OVERSOLD`, `DISCOUNT`, `PRODUCT_INACTIVE`); log line `event=offline-bill-recorded` (alert `OfflineBillExceptionsUnreviewed`) |
| `mall_flash_price_lookup_seconds_*` | time to look up the flash sale price of a product (on every quote and shop list) |
| `hikaricp_*`, `jvm_*`, `process_*`, `tomcat_*` | database pool, memory, GC, CPU, threads |

Gauges that read the database are cached for 15 s, so scraping never hammers it.

## Dashboards and alerts
```
docker compose -f ops/docker-compose.observability.yml up -d     # Prometheus :9091, Grafana :3000 (admin/admin, change it)
```
Grafana loads the dashboard **Vijay Super Mall - Operations** (26 panels) from `ops/grafana/dashboards/`. Alert rules
(`ops/prometheus/alerts.yml`) each say what to do. Point `ops/prometheus/prometheus.yml` at your server's management port first.
These files are syntax-checked but were not run against a live Prometheus/Grafana in this environment: open the dashboard once and
confirm the panels fill; adjust the scrape target if needed.

## Performance test
`java ops/perf/LoadTest.java <baseUrl> <browse|shopper|counter|admin|mixed> <users> <seconds>` (no install needed).
Run the server with the `dev` profile (login code 12345) and the rate limits raised
(`APP_RATE_LIMIT_API_PER_MINUTE=100000000 APP_RATE_LIMIT_OTP_PER_IP_HOUR=100000000 APP_RATE_LIMIT_OTP_PER_EMAIL_HOUR=100000000`).
**Run the server from a copy of the jar**, never from `target/` while rebuilding (Java loads classes lazily and a rebuilt jar breaks a running server).

### Results (8-core 16 GB laptop; server, PostgreSQL 17 in Docker and the load generator all on the same machine, so absolute numbers are
indicative only, relative changes are meaningful). Data: 20,000 products, 30,000 customers, 183,000 orders, 483,000 order lines.

| Scenario | Users | Throughput | Notes |
|---|---|---|---|
| browse (guest) | 50 | 4,400 req/s | p95 14-29 ms per endpoint, 0 errors |
| shopper (browse, quote, order) | 40 | 2,900 req/s | place order p50 36 ms, p95 55 ms |
| counter (search, quote, bill) | 20 | 1,080 req/s (about 360 bills/s) | ring up a bill p50 41 ms, p95 121 ms, p99 213 ms |
| mixed | 100 | 3,460 req/s | bill p95 218 ms, order p95 104 ms, 0 errors |
| admin back office | 10 | 135 req/s | all screens p95 under 0.45 s |

A real till rings a bill every few seconds, so one server has a very large margin over one shop's counters.

### Tuning done
| Finding | Change | Result |
|---|---|---|
| Customer / product search read every row (`contains` match) | trigram indexes (migration V15, skipped safely if the extension is not allowed) | customer search 132 -> 13 ms, product search 71 -> 14 ms |
| Order search used `upper()` so the new index was not used | explicit `lower()` query | order search 164 -> 28 ms p50 |
| Customer list did several queries per row | list is a light view; coins/credit/orders only on the detail view | one query per page |
| Admin throughput | (the three above) | 88 -> 135 req/s |

### Still the heaviest (acceptable, watch these)
Monthly sales report ~310 ms p50 / 430 ms p95 and the dashboard ~120 ms at 183,000 orders: they aggregate over the whole month.
If they grow past 1 s, cache them for 60 s or move them to a summary table. Reading the shop pages makes ~3 database round trips per
request (`/hot-sale` 3.8, `/products` 2.9): wrapping those reads in one read-only transaction would halve database connection use.
The database pool (20) never queued during any test (`hikaricp_connections_pending` stayed 0).

## Flash sales
Staff (admin and cashier) set up timed offers under `/api/v1/admin/flash-sales`; the shop shows them from `/api/v1/flash-sales/active`.
- **Logs** (`event=` field): `flash-created`, `flash-updated`, `flash-cancelled` (staff action, with `actor`), `flash-started` and `flash-ended`
  (the minute job, once per sale, with totals on ended), `flash-consumed` (units used by an order or bill: sale, product, qty, channel, prices,
  `userId`, never personal data), `flash-released` (units given back by a cancelled order), `flash-refused` (`reason`), `flash-item-sold-out`.
  Staff actions are also in the audit log (`FLASH_CREATE`, `FLASH_UPDATE`, `FLASH_ITEM_*`, `FLASH_START`, `FLASH_END`, `FLASH_CANCEL`, `FLASH_DUPLICATE`).
- **Metrics:** see the table above. The dashboard has three flash panels (units per minute, revenue and discount, sales now and refusals).
- **Alerts:** `FlashSaleRunningWithoutSales` (a sale is on and nothing sold for 30 min) and `FlashSaleCheckoutsRefused` (more than 20 checkouts
  turned away in 10 min: customers see a stale price).
- **Notifications:** staff get an in-app note when a sale starts, ends (with units and revenue) and when a capped item sells out (once each).
  The minute job marks what it announced in the database (`flash_sale.started_notified_at` / `ended_notified_at`), so a restart does not repeat it.
- **Cache:** `flash` (15 s) holds the sale definitions only; units left are always read from the database. It is emptied on every change.
