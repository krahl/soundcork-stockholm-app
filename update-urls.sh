#!/bin/sh
set -e

BACKEND_URL="${BACKEND_URL:-http://soundcork:8000}"
STREAMING_URL="${STREAMING_URL:-${BACKEND_URL%/}/marge}"
AUTH_SERVICE_URL="${AUTH_SERVICE_URL:-}"

echo Using streaming url $STREAMING_URL

# Keep backup.json as the canonical source so restart-time env changes are applied
if [ ! -f backup.json ]; then
  cp config.json backup.json
fi

jq '.default |= map_values(try (@base64d) catch .)' backup.json \
| sed \
  -e "s|https://streaming.bose.com|${STREAMING_URL}|" \
  -e "s|https://events.api.bosecm.com|${BACKEND_URL}|" \
  -e "s|https://content.api.bose.io|${BACKEND_URL}|" \
  -e "s|https://worldwide.bose.com|${BACKEND_URL}|" \
| jq --arg authSuffix "${AUTH_SERVICE_URL}" '.default.d6 = $authSuffix' \
| jq '.default |= map_values(@base64)' \
> config.json
