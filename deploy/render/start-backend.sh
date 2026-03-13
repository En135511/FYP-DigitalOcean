#!/usr/bin/env sh
set -eu

PORT_VALUE="${PORT:-10000}"
VISION_PORT_VALUE="${VISION_PORT:-8000}"
VISION_URL="http://127.0.0.1:${VISION_PORT_VALUE}"

echo "Starting co-located vision service on port ${VISION_PORT_VALUE}..."
cd /app/vision-python-service
python -m uvicorn app.main:app --host 0.0.0.0 --port "${VISION_PORT_VALUE}" &
VISION_PID=$!

cleanup() {
  kill "${VISION_PID}" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

echo "Waiting for vision service readiness..."
attempt=0
until curl -fsS "${VISION_URL}/" >/dev/null 2>&1; do
  attempt=$((attempt + 1))
  if [ "${attempt}" -ge 60 ]; then
    echo "Vision service failed to become ready at ${VISION_URL}."
    exit 1
  fi
  sleep 1
done

echo "Vision service is ready at ${VISION_URL}"
echo "Starting backend on port ${PORT_VALUE}"
echo "Using vision service URL: ${VISION_URL}"

exec java $JAVA_TOOL_OPTIONS \
  -Dserver.port="${PORT_VALUE}" \
  -Dvision.service.base-url="${VISION_URL}" \
  -jar /app/app.jar
