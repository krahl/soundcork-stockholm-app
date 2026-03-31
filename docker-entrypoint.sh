#!/bin/sh
set -e

LOGBACK_CONFIG=""
if [ -f /app/logback.xml ]; then
  LOGBACK_CONFIG="-Dlogback.configurationFile=/app/logback.xml"
fi

exec java $LOGBACK_CONFIG -cp "/app/backend/lib/*" com.soundcork.stockholm.backend.BackendApplication "$@"
