# Deploying Vijay Super Mall

1. On the new server install Docker (with the compose plugin). To build the jar there you also need JDK 26 and Maven; or build the jar elsewhere
   and copy `target/mall-api-0.1.0-SNAPSHOT.jar` next to the `Dockerfile`.
2. `./deploy/generate-prod-env.sh` creates `deploy/prod.env` with **fresh random** `JWT_SECRET`, `OTP_PEPPER`, `CONFIG_ENCRYPTION_KEY` and database
   password, and asks for the owner's e-mail and the SMTP account that sends login codes. Keep `prod.env` private and back it up.
3. `cd deploy && docker compose -f docker-compose.prod.yml --env-file prod.env up -d --build`
4. Sign in with the owner e-mail (a code arrives by e-mail); that account becomes the first admin. Then use the admin config screen for payment keys
   (Razorpay), push (Firebase), SMS, GST rounding, log retention and the rest. Check `GET /api/v1/admin/config/bootstrap` shows every item OK.

Safety rules the server enforces at start-up (it refuses to start otherwise): a long random `JWT_SECRET` and `OTP_PEPPER`, an empty `OTP_FIXED_CODE`,
`OTP_CHANNEL=email`, an `ADMIN_EMAIL`, and the metrics port different from the public port. Put TLS in front (Caddy, nginx or a cloud load balancer).
Back up the `dbdata` volume (pg_dump) and the `images` volume.
