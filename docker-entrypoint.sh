#!/bin/bash
set -euo pipefail

cd /app

STOCKHOLM_DIR="/app/stockholm"
STOCKHOLM_ZIP_DIR="/app/stockholm_zip"
PATCH_MARKER="${STOCKHOLM_DIR}/.soundcork-stockholm-app.json"

LOGBACK_CONFIG=""
if [ -f /app/logback.xml ]; then
  LOGBACK_CONFIG="-Dlogback.configurationFile=/app/logback.xml"
fi

if [ -f /app/custom-ca.crt ]; then
  echo "Importing custom CA certificate into JVM truststore."
  keytool -importcert \
    -noprompt \
    -trustcacerts \
    -alias custom-ca \
    -file /app/custom-ca.crt \
    -keystore "${JAVA_HOME}/lib/security/cacerts" \
    -storepass changeit 2>/dev/null || true
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

get_current_patch_version() {
  if [ ! -f "${PATCH_MARKER}" ]; then
    echo 0
    return
  fi

  jq -r '.patchVersion // 0' "${PATCH_MARKER}" 2>/dev/null || echo 0
}

get_patch_version_from_file() {
  local patch_file version

  patch_file="$1"
  version="${patch_file##*/}"
  version="${version#stockholm-changes_v}"
  version="${version%.patch}"

  case "${version}" in
    ''|*[!0-9]*)
      return 1
      ;;
  esac

  echo "${version}"
}

find_patch_file() {
  local version

  version="$1"
  if [ -f "/app/stockholm-changes_v${version}.patch" ]; then
    echo "/app/stockholm-changes_v${version}.patch"
    return 0
  fi

  return 1
}

discover_patch_versions() {
  local patch_file version

  for patch_file in /app/stockholm-changes_v*.patch; do
    [ -e "${patch_file}" ] || continue
    version="$(get_patch_version_from_file "${patch_file}")" || continue
    echo "${version}"
  done | sort -n
}

is_marker_current() {
  local version

  version="$1"
  [ -f "${PATCH_MARKER}" ] && jq -e ".patchVersion == ${version}" "${PATCH_MARKER}" >/dev/null 2>&1
}

write_patch_marker() {
  local version

  version="$1"
  cat >"${PATCH_MARKER}" <<EOF
{
  "project": "soundcork-stockholm-app",
  "patchVersion": ${version}
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

filter_stockholm_patch() {
  local patch_file filtered_patch

  patch_file="$1"
  filtered_patch="$(mktemp)"

  # The generated patch files may also include repo-only hunks such as README updates.
  awk '
    /^diff --git / {
      keep = ($0 ~ /^diff --git a\/stockholm\//)
    }

    keep {
      print
    }
  ' "${patch_file}" >"${filtered_patch}"

  if [ ! -s "${filtered_patch}" ]; then
    rm -f "${filtered_patch}"
    echo "Patch ${patch_file} does not contain any stockholm/ changes."
    exit 1
  fi

  echo "${filtered_patch}"
}

patch_is_applied() {
  local patch_file

  patch_file="$1"
  patch -p1 -R --dry-run --batch --silent <"${patch_file}" >/dev/null 2>&1
}

patch_can_apply() {
  local patch_file

  patch_file="$1"
  patch -p1 --dry-run --batch --silent <"${patch_file}" >/dev/null 2>&1
}

apply_patch_file() {
  local version patch_file filtered_patch should_format

  version="$1"
  patch_file="$2"
  should_format="${3:-false}"
  filtered_patch="$(filter_stockholm_patch "${patch_file}")"

  if is_marker_current "${version}"; then
    echo "Soundcork Stockholm patch marker exists for v${version}, but the patch is incomplete."
    echo "Remove the generated Stockholm directory and restart so it can be extracted cleanly."
    rm -f "${filtered_patch}"
    exit 1
  fi

  echo "Preparing Stockholm frontend and applying Soundcork patch v${version}."
  if [ "${should_format}" = "true" ]; then
    format_stockholm_js
  fi

  if ! patch_can_apply "${filtered_patch}"; then
    if patch_is_applied "${filtered_patch}"; then
      echo "Soundcork Stockholm patch v${version} is already applied."
      write_patch_marker "${version}"
      rm -f "${filtered_patch}"
      return
    fi

    echo "Soundcork Stockholm patch v${version} cannot be applied cleanly."
    echo "Remove the generated Stockholm directory and restart so it can be extracted cleanly."
    rm -f "${filtered_patch}"
    exit 1
  fi

  if patch -p1 --batch <"${filtered_patch}"; then
    write_patch_marker "${version}"
    rm -f "${filtered_patch}"
    return
  fi

  if patch_is_applied "${filtered_patch}"; then
    echo "Soundcork Stockholm patch v${version} is already applied."
    write_patch_marker "${version}"
    rm -f "${filtered_patch}"
    return
  fi

  echo "Soundcork Stockholm patch v${version} failed while applying."
  echo "Remove the generated Stockholm directory and restart so it can be extracted cleanly."
  rm -f "${filtered_patch}"
  exit 1
}

apply_pending_patches() {
  local current_version version patch_file current_patch_file current_filtered_patch

  current_version="$(get_current_patch_version)"

  current_patch_file="$(find_patch_file "${current_version}" || true)"
  if [ -n "${current_patch_file}" ]; then
    current_filtered_patch="$(filter_stockholm_patch "${current_patch_file}")"
    if ! patch_is_applied "${current_filtered_patch}"; then
      echo "Soundcork Stockholm patch marker says v${current_version} is applied, but the patch is incomplete."
      echo "Remove the generated Stockholm directory and restart so it can be extracted cleanly."
      rm -f "${current_filtered_patch}"
      exit 1
    fi
    rm -f "${current_filtered_patch}"
  fi

  while IFS= read -r version; do
    [ -n "${version}" ] || continue
    if [ "${version}" -le "${current_version}" ]; then
      continue
    fi

    patch_file="$(find_patch_file "${version}")"
    if [ -z "${patch_file}" ]; then
      echo "Expected patch file /app/stockholm-changes_v${version}.patch is missing."
      exit 1
    fi

    if [ "${current_version}" -eq 0 ] && [ "${version}" -eq 1 ]; then
      apply_patch_file "${version}" "${patch_file}" true
    else
      apply_patch_file "${version}" "${patch_file}" false
    fi
    current_version="${version}"
  done < <(discover_patch_versions)
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
  apply_pending_patches
}

prepare_stockholm

if [[ -n "${BACKEND_URL:-}" ]]; then
  pushd "${STOCKHOLM_DIR}/json" >/dev/null
  source update-urls.sh
  popd >/dev/null
fi

exec java ${LOGBACK_CONFIG} -cp "/app/backend/lib/*" com.soundcork.stockholm.backend.BackendApplication "$@"
