#!/bin/bash
set -e

LOGBACK_CONFIG=""
if [ -f /app/logback.xml ]; then
  LOGBACK_CONFIG="-Dlogback.configurationFile=/app/logback.xml"
fi

if [ -e /app/stockholm/index.html ]; then
  echo "Stockholm directory already exists. Skipping unzip."
elif [ -e stockholm.zip ]; then
  unzip stockholm.zip -d /app/stockholm
  cp /app/update-urls.sh /app/stockholm/json/update-urls.sh
  npx prettier --write "**/*.js"
  patch -p1 < stockholm-changes_v1.patch
else
  echo "Please download stockholm.zip first. Read the README for instructions."
  exit 1
fi

if [[ -n $BACKEND_URL ]]; then
  pushd /app/stockholm/json
  source update-urls.sh
  popd
fi

exec java $LOGBACK_CONFIG -cp "/app/backend/lib/*" com.soundcork.stockholm.backend.BackendApplication "$@"
