#!/bin/bash
set -e

LOGBACK_CONFIG=""
if [ -f /app/logback.xml ]; then
  LOGBACK_CONFIG="-Dlogback.configurationFile=/app/logback.xml"
fi

if [[ -n $BACKEND_URL ]]; then
  pushd /app/stockholm/json
  source update-urls.sh
  popd
fi

exec java $LOGBACK_CONFIG -cp "/app/backend/lib/*" com.soundcork.stockholm.backend.BackendApplication "$@"
