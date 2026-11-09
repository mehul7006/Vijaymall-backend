#!/usr/bin/env bash
# Creates deploy/prod.env: a complete production configuration for Vijay Super Mall with FRESH random secrets.
#
#   ./deploy/generate-prod-env.sh                      # asks for the few values only you know
#   ADMIN_EMAIL=owner@shop.in SMTP_HOST=smtp.gmail.com SMTP_USER=... SMTP_PASSWORD=... ./deploy/generate-prod-env.sh   # no questions
#
# Run it ON THE NEW SYSTEM (so the secrets never travel). The file is written with permissions 600; keep it out of git and keep a
# copy in your password manager: if CONFIG_ENCRYPTION_KEY is lost, the secrets saved on the admin config screen must be entered again.
set -euo pipefail
cd "$(dirname "$0")"
OUT="${OUT:-prod.env}"
if [ -e "$OUT" ] && [ "${FORCE:-}" != "1" ]; then echo "$OUT already exists. Run with FORCE=1 to replace it (this creates NEW secrets: users are signed out)."; exit 1; fi
command -v openssl >/dev/null || { echo "openssl is required"; exit 1; }

ask() { # ask VAR "question" "default"
  local var="$1" q="$2" def="${3:-}" val="${!1:-}"
  if [ -z "$val" ]; then
    if [ -t 0 ]; then read -r -p "$q${def:+ [$def]}: " val || true; fi
    val="${val:-$def}"
  fi
  printf -v "$var" '%s' "$val"
}

ask ADMIN_EMAIL    "E-mail of the shop owner (becomes the first admin on first login)" ""
[ -n "$ADMIN_EMAIL" ] || { echo "ADMIN_EMAIL is required"; exit 1; }
ask SMTP_HOST      "SMTP server that sends login codes (for example smtp.gmail.com)" ""
ask SMTP_PORT      "SMTP port (587 STARTTLS, 465 SSL)" "587"
ask SMTP_USER      "SMTP user name" ""
ask SMTP_PASSWORD  "SMTP password / app password" ""
ask OTP_FROM       "From address shown on login e-mails" "no-reply@$(echo "$ADMIN_EMAIL" | cut -d@ -f2)"
ask CORS_ORIGINS   "Web app origin(s), comma separated (blank if no web app yet)" ""

secret() { openssl rand -base64 "$1" | tr -d '\n'; }
umask 177
cat > "$OUT" <<ENV
# Vijay Super Mall production configuration. Generated $(date -u +%Y-%m-%dT%H:%M:%SZ). Keep this file private (chmod 600, not in git).
SPRING_PROFILES_ACTIVE=prod
APP_ENV=prod

# ---- secrets (random, generated here) ----
JWT_SECRET=$(secret 48)
OTP_PEPPER=$(secret 32)
# 32 random bytes: encrypts the secrets (payment keys, SMTP password, Firebase key) saved on the admin config screen
CONFIG_ENCRYPTION_KEY=$(secret 32)

# ---- database (the compose file creates it with this password) ----
POSTGRES_DB=mall
POSTGRES_USER=mall
POSTGRES_PASSWORD=$(openssl rand -hex 24)

# ---- first admin and login codes ----
ADMIN_EMAIL=${ADMIN_EMAIL}
OTP_CHANNEL=email
OTP_FIXED_CODE=
OTP_FROM=${OTP_FROM}
SMTP_HOST=${SMTP_HOST}
SMTP_PORT=${SMTP_PORT}
SMTP_USER=${SMTP_USER}
SMTP_PASSWORD=${SMTP_PASSWORD}

# ---- network ----
# Browsers allowed to call the API (the web app), for example https://shop.example.com . Blank = none.
CORS_ORIGINS=${CORS_ORIGINS}
# Behind a reverse proxy / CDN: set FORWARD_HEADERS_STRATEGY=framework and CLIENT_IP_HEADER to the header that carries the real
# client address (for example X-Forwarded-For, or CF-Connecting-IP behind Cloudflare); the admin config screen can change the header later.
FORWARD_HEADERS_STRATEGY=none
CLIENT_IP_HEADER=
# Metrics and health live on this private port; the compose file publishes it on 127.0.0.1 only.
MANAGEMENT_PORT=9090

# ---- logs ----
LOG_DIR=/app/logs
LOG_LEVEL_ROOT=INFO
LOG_LEVEL_APP=INFO
# 7, 15 or 30 days of rotated log files (changeable on the admin config screen)
LOG_RETENTION_DAYS=30

# ---- payments (leave blank now and set them on the admin config screen: Razorpay key id, key secret and webhook secret) ----
RAZORPAY_KEY_ID=
RAZORPAY_KEY_SECRET=
RAZORPAY_WEBHOOK_SECRET=
ENV
case "$OUT" in /*) FULL="$OUT" ;; *) FULL="$(pwd)/$OUT" ;; esac
echo "Wrote $FULL (permissions 600)."
echo "Next:  docker compose -f docker-compose.prod.yml --env-file $OUT up -d --build"
