#!/bin/sh
set -e

BACKEND_URL="${BACKEND_URL:-http://soundcork:8000}"
AUTH_SUFFIX="${AUTH_SUFFIX:-}"

cp config.json backup.json

jq '.default |= map_values(try (@base64d) catch .)' backup.json \
| sed \
  -e "s|https://streaming.bose.com|${BACKEND_URL}/marge|" \
  -e "s|https://events.api.bosecm.com|${BACKEND_URL}|" \
  -e "s|https://content.api.bose.io|${BACKEND_URL}|" \
  -e "s|https://worldwide.bose.com|${BACKEND_URL}|" \
| jq --arg authSuffix "${AUTH_SUFFIX}" '.default.d6 = $authSuffix' \
| jq '.default |= map_values(@base64)' \
> config.json
