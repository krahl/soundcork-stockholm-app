#!/bin/bash
set -euo pipefail

cd /app

STOCKHOLM_DIR="/app/stockholm"
STOCKHOLM_ZIP_DIR="/app/stockholm_zip"
PATCH_VERSION=1
PATCH_MARKER="${STOCKHOLM_DIR}/.soundcork-stockholm-app.json"
PATCH_FILE="/app/stockholm-changes_v1.patch"

LOGBACK_CONFIG=""
if [ -f /app/logback.xml ]; then
  LOGBACK_CONFIG="-Dlogback.configurationFile=/app/logback.xml"
fi

find_stockholm_zip() {
  if [ -f "${STOCKHOLM_ZIP_DIR}/stockholm.zip" ]; then
    echo "${STOCKHOLM_ZIP_DIR}/stockholm.zip"
    return
  fi
  if [ -f /app/stockholm.zip ]; then
    echo "/app/stockholm.zip"
  fi
  return 0
}

is_marker_current() {
  [ -f "${PATCH_MARKER}" ] && jq -e ".patchVersion == ${PATCH_VERSION}" "${PATCH_MARKER}" >/dev/null 2>&1
}

write_patch_marker() {
  cat >"${PATCH_MARKER}" <<EOF
{
  "project": "soundcork-stockholm-app",
  "patchVersion": ${PATCH_VERSION}
}
EOF
}

copy_update_script() {
  if [ -d "${STOCKHOLM_DIR}/json" ]; then
    cp /app/update-urls.sh "${STOCKHOLM_DIR}/json/update-urls.sh"
    chmod +x "${STOCKHOLM_DIR}/json/update-urls.sh"
  fi
}

format_stockholm_js() {
  if ! command -v prettier >/dev/null 2>&1; then
    echo "Prettier is missing in the container image. Rebuild the image and try again."
    exit 1
  fi
  prettier --ignore-path /dev/null --write "stockholm/**/*.js"
}

patch_is_applied() {
  patch -p1 -R --dry-run --batch --silent <"${PATCH_FILE}" >/dev/null 2>&1
}

patch_can_apply() {
  patch -p1 --dry-run --batch --silent <"${PATCH_FILE}" >/dev/null 2>&1
}

apply_v1_patch() {
  if patch_is_applied; then
    echo "Soundcork Stockholm patch v${PATCH_VERSION} is already applied."
    is_marker_current || write_patch_marker
    return
  fi

  if is_marker_current; then
    echo "Soundcork Stockholm patch marker exists, but the patch is incomplete."
    echo "Remove the generated Stockholm directory and restart so it can be extracted cleanly."
    exit 1
  fi

  echo "Preparing Stockholm frontend and applying Soundcork patch v${PATCH_VERSION}."
  format_stockholm_js

  if ! patch_can_apply; then
    echo "Soundcork Stockholm patch v${PATCH_VERSION} cannot be applied cleanly."
    echo "Remove the generated Stockholm directory and restart so it can be extracted cleanly."
    exit 1
  fi

  patch -p1 --batch <"${PATCH_FILE}"
  write_patch_marker
}

prepare_stockholm() {
  if [ ! -f "${STOCKHOLM_DIR}/index.html" ]; then
    local zip_file
    zip_file="$(find_stockholm_zip)"
    if [ -z "${zip_file}" ]; then
      echo "Please download stockholm.zip first, put it at ${STOCKHOLM_ZIP_DIR}/stockholm.zip, and read the README for instructions."
      exit 1
    fi

    mkdir -p "${STOCKHOLM_DIR}"
    echo "Extracting Stockholm frontend from ${zip_file}."
    unzip "${zip_file}" -d "${STOCKHOLM_DIR}"
  else
    echo "Stockholm directory already exists."
  fi

  copy_update_script
  apply_v1_patch
}

prepare_stockholm

if [[ -n "${BACKEND_URL:-}" ]]; then
  pushd "${STOCKHOLM_DIR}/json" >/dev/null
  source update-urls.sh
  popd >/dev/null
fi

exec java ${LOGBACK_CONFIG} -cp "/app/backend/lib/*" com.soundcork.stockholm.backend.BackendApplication "$@"
