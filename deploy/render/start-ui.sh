#!/usr/bin/env sh
set -eu

if [ -z "${BACKEND_HOSTPORT:-}" ]; then
  echo "BACKEND_HOSTPORT is required."
  exit 1
fi

sed "s|__BACKEND_HOSTPORT__|${BACKEND_HOSTPORT}|g" /etc/caddy/Caddyfile.template > /etc/caddy/Caddyfile

exec caddy run --config /etc/caddy/Caddyfile --adapter caddyfile
