#!/usr/bin/env sh
set -eu

PORT_VALUE="${PORT:-10000}"
VISION_URL="${VISION_SERVICE_BASE_URL:-}"

if [ -z "$VISION_URL" ] && [ -n "${VISION_SERVICE_HOSTPORT:-}" ]; then
  VISION_URL="http://${VISION_SERVICE_HOSTPORT}"
fi

if [ -z "$VISION_URL" ]; then
  VISION_URL="http://localhost:8000"
fi

echo "Starting backend on port ${PORT_VALUE}"
echo "Using vision service URL: ${VISION_URL}"
if [ "$VISION_URL" = "http://localhost:8000" ]; then
  echo "WARNING: Falling back to localhost vision URL. Set VISION_SERVICE_HOSTPORT or VISION_SERVICE_BASE_URL in Render."
fi

exec java $JAVA_TOOL_OPTIONS \
  -Dserver.port="${PORT_VALUE}" \
  -Dvision.service.base-url="${VISION_URL}" \
  -jar /app/app.jar
