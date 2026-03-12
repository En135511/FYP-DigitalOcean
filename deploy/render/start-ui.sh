#!/usr/bin/env sh
set -eu

PORT_VALUE="${PORT:-10000}"

if [ -z "${BACKEND_HOSTPORT:-}" ]; then
  echo "BACKEND_HOSTPORT is required."
  exit 1
fi

sed \
  -e "s|__PORT__|${PORT_VALUE}|g" \
  -e "s|__BACKEND_HOSTPORT__|${BACKEND_HOSTPORT}|g" \
  /etc/nginx/conf.d/default.conf.template > /etc/nginx/conf.d/default.conf

exec nginx -g "daemon off;"
