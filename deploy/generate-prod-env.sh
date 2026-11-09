#!/usr/bin/env bash
# Creates a complete configuration file for Vijay Super Mall with FRESH random secrets:
#
#   MODE=dev   deploy/dev.env   a TEST server: the dev profile. Login codes are printed in the server log (no e-mail account needed),
#                               sample data is loaded on an empty database. Never expose it to real customers.
#   MODE=prod  deploy/prod.env  a REAL server: the prod profile. Login codes are e-mailed, the server refuses to start with unsafe settings.
#
#   ./deploy/generate-prod-env.sh                       # asks which mode and the few values only you know
#   MODE=dev ADMIN_EMAIL=owner@shop.in ./deploy/generate-prod-env.sh        # no questions
#   MODE=prod ADMIN_EMAIL=owner@shop.in SMTP_HOST=smtp.gmail.com SMTP_USER=... SMTP_PASSWORD=... ./deploy/generate-prod-env.sh
#
# Run it ON THE NEW SYSTEM (so the secrets never travel). The file is written with permissions 600; keep it out of git and keep a
# copy in your password manager: if CONFIG_ENCRYPTION_KEY is lost, the secrets saved on the admin config screen must be entered again.
set -euo pipefail
cd "$(dirname "$0")"
command -v openssl >/dev/null || { echo "openssl is required"; exit 1; }

ask() { # ask VAR "question" "default"
  local var="$1" q="$2" def="${3:-}" val="${!1:-}"
  if [ -z "$val" ]; then
    if [ -t 0 ]; then read -r -p "$q${def:+ [$def]}: " val || true; fi
    val="${val:-$def}"
  fi
  printf -v "$var" '%s' "$val"
}

ask MODE "Which server? dev = a TEST server (login codes printed in the log) or prod = a REAL server (codes e-mailed)" "prod"
MODE="$(echo "$MODE" | tr '[:upper:]' '[:lower:]')"
case "$MODE" in dev|prod) ;; *) echo "MODE must be dev or prod"; exit 1 ;; esac
OUT="${OUT:-$MODE.env}"
if [ -e "$OUT" ] && [ "${FORCE:-}" != "1" ]; then echo "$OUT already exists. Run with FORCE=1 to replace it (this creates NEW secrets: users are signed out)."; exit 1; fi

ask ADMIN_EMAIL    "E-mail of the shop owner (becomes the first admin on first login)" ""
[ -n "$ADMIN_EMAIL" ] || { echo "ADMIN_EMAIL is required"; exit 1; }
if [ "$MODE" = dev ]; then CORS_DEFAULT="http://localhost:*,http://127.0.0.1:*"; else CORS_DEFAULT=""; fi
ask CORS_ORIGINS   "Web app origin(s), comma separated (for a dev server the default allows any local port)" "$CORS_DEFAULT"

if [ "$MODE" = dev ]; then
  # login codes go to the server log; the dev profile prints them. FIXED_CODE=12345 makes every code 12345 (only for a private test server).
  ask FIXED_CODE   "Use the fixed login code 12345 instead of random codes in the log? (y/N)" "N"
  case "$FIXED_CODE" in 1|y|Y|yes|YES|true|TRUE) OTP_FIXED_CODE=12345 ;; *) OTP_FIXED_CODE="" ;; esac
  PROFILE=dev; OTP_CHANNEL=console; SMS_CHANNEL=console
  SMTP_HOST="${SMTP_HOST:-localhost}"; SMTP_PORT="${SMTP_PORT:-587}"; SMTP_USER="${SMTP_USER:-}"; SMTP_PASSWORD="${SMTP_PASSWORD:-}"
  OTP_FROM="${OTP_FROM:-no-reply@test.local}"
else
  PROFILE=prod; OTP_CHANNEL=email; SMS_CHANNEL=none; OTP_FIXED_CODE=""
  ask SMTP_HOST      "SMTP server that sends login codes (for example smtp.gmail.com)" ""
  ask SMTP_PORT      "SMTP port (587 STARTTLS, 465 SSL)" "587"
  ask SMTP_USER      "SMTP user name" ""
  ask SMTP_PASSWORD  "SMTP password / app password" ""
  ask OTP_FROM       "From address shown on login e-mails" "no-reply@$(echo "$ADMIN_EMAIL" | cut -d@ -f2)"
fi

secret() { openssl rand -base64 "$1" | tr -d '\n'; }
umask 177
cat > "$OUT" <<ENV
# Vijay Super Mall ${MODE} configuration. Generated $(date -u +%Y-%m-%dT%H:%M:%SZ). Keep this file private (chmod 600, not in git).
SPRING_PROFILES_ACTIVE=${PROFILE}
APP_ENV=${MODE}

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
# email = codes are e-mailed (prod). console = codes are printed in the server log (dev profile ONLY: the prod profile refuses to start with it).
# To read a code on a dev server: docker compose logs api | grep "OTP for"
OTP_CHANNEL=${OTP_CHANNEL}
# mobile login codes: none (mobile login off), http (your SMS provider, set on the admin config screen) or console (dev profile only)
SMS_CHANNEL=${SMS_CHANNEL}
# empty = random codes. A fixed code is for a private dev server only; the prod profile refuses to start with one.
OTP_FIXED_CODE=${OTP_FIXED_CODE}
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
# Pools (defaults are fine for one backend + one PostgreSQL with max_connections=100; instances x DB_POOL_MAX must stay under it)
DB_POOL_MAX=20
DB_POOL_MIN_IDLE=5
DB_POOL_WAIT_MS=5000
SCHEDULER_THREADS=4
TOMCAT_MAX_CONNECTIONS=8192
TOMCAT_ACCEPT_COUNT=200

# ---- payments (leave blank now and set them on the admin config screen: Razorpay key id, key secret and webhook secret) ----
RAZORPAY_KEY_ID=
RAZORPAY_KEY_SECRET=
RAZORPAY_WEBHOOK_SECRET=
ENV
case "$OUT" in /*) FULL="$OUT" ;; *) FULL="$(pwd)/$OUT" ;; esac
echo "Wrote $FULL (permissions 600), profile ${PROFILE}."
echo "Next:  docker compose -f docker-compose.prod.yml --env-file $OUT up -d --build"
[ "$MODE" = dev ] && echo "Dev server: request a login code in the app, then read it with: docker compose -f docker-compose.prod.yml logs api | grep \"OTP for\""
exit 0
