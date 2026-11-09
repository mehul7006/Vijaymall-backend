# Deploying Vijay Super Mall

1. On the new server install Docker (with the compose plugin). To build the jar there you also need JDK 26 and Maven; or build the jar elsewhere
   and copy `target/mall-api-0.1.0-SNAPSHOT.jar` next to the `Dockerfile`.
2. `MODE=prod ./deploy/generate-prod-env.sh` creates `deploy/prod.env` with **fresh random** `JWT_SECRET`, `OTP_PEPPER`, `CONFIG_ENCRYPTION_KEY` and database
   password, and asks for the owner's e-mail and the SMTP account that sends login codes. Keep `prod.env` private and back it up.
3. `cd deploy && docker compose -f docker-compose.prod.yml --env-file prod.env up -d --build`
4. Sign in with the owner e-mail (a code arrives by e-mail); that account becomes the first admin. Then use the admin config screen for payment keys
   (Razorpay), push (Firebase), SMS, GST rounding, log retention and the rest. Check `GET /api/v1/admin/config/bootstrap` shows every item OK.

**Test server (login codes in the log):** run `MODE=dev ./deploy/generate-prod-env.sh` (or choose `dev` when asked). It writes `deploy/dev.env` with
`SPRING_PROFILES_ACTIVE=dev`: login codes are **printed in the server log** (no e-mail or SMS account needed), sample products and users are loaded on an empty
database, and the startup safety checks are relaxed. Start it with `docker compose -f docker-compose.prod.yml --env-file dev.env up -d --build`, request a code
in the app and read it with `docker compose -f docker-compose.prod.yml logs api | grep "OTP for"`. Console login codes exist **only in the dev profile**: a prod
server refuses to start with them, and the admin config screen refuses to save them there. Never put customers on a dev server (anyone who can read its log can
sign in as anyone).

**Seeded dev admin:** the admin account is whatever `ADMIN_EMAIL` is set to in the env file (default `admin@vijaysupermall.local`; a test instance with
`ADMIN_EMAIL=admin@mall.com` has `admin@mall.com`). The sample cashier is `cashier@vijaysupermall.local`. In the dev profile the login code is `12345` unless `OTP_FIXED_CODE` overrides it
(an env file written with the fixed code left off has it empty and the codes then appear in the log).

Safety rules the server enforces at start-up (it refuses to start otherwise): a long random `JWT_SECRET` and `OTP_PEPPER`, an empty `OTP_FIXED_CODE`,
`OTP_CHANNEL=email`, an `ADMIN_EMAIL`, and the metrics port different from the public port. Put TLS in front (Caddy, nginx or a cloud load balancer).
Back up the `dbdata` volume (pg_dump) and the `images` volume.

## Thread and connection pools

Defaults live in the property files: shared values in `application.yml`, then `application-dev.yml` (small pools, leak detection on) or `application-prod.yml` (production sizing) on top. Every value can be overridden from `prod.env` with the variable named below, without a rebuild. The table shows the prod defaults (dev: pool 10, 2 idle).

| Setting | Default | Meaning |
|---|---|---|
| `DB_POOL_MAX` | 20 | Most database connections one backend holds. `instances x DB_POOL_MAX` must stay under PostgreSQL `max_connections` (100 in the compose file). |
| `DB_POOL_MIN_IDLE` | 10 (dev 2; base default 5) | Connections kept warm. |
| `DB_POOL_WAIT_MS` | 5000 | A request waits this long for a connection, then fails fast. |
| `DB_POOL_MAX_LIFETIME_MS` | 1500000 | Connections are replaced after 25 min (before firewalls drop them). |
| `DB_POOL_LEAK_MS` | 0 (off) | Set e.g. 20000 to log code that holds a connection too long. |
| `TOMCAT_MAX_CONNECTIONS` / `TOMCAT_ACCEPT_COUNT` | 8192 / 200 | Open sockets / waiting queue. |

Requests and background jobs (`@Scheduled`: outbox relay, flash sales...) run on virtual threads: each request and each job run gets its own thread, so there is no request-thread or scheduler pool to size; the database pool is the real limit.
Watch `hikaricp_connections_pending` (should stay ~0) and `hikaricp_connections_active` in Prometheus; raise `DB_POOL_MAX`
only if pending stays above 0 while the database CPU is not busy. JVM memory: container uses `-XX:MaxRAMPercentage=70`.

## Running migrations safely

Flyway runs the migrations when the backend starts. Some of them (the V24 backfill, plain `CREATE INDEX` statements such as V27's) lock the table while they run.
That is instant on today's data, but on a big database stop the app first (`docker compose ... stop api`), let the new version start alone, and for
very large tables create the index by hand with `CREATE INDEX CONCURRENTLY IF NOT EXISTS ...` before deploying so the migration finds it already there.
