#!/bin/sh
# Substitute environment variables into the realm import template before starting Keycloak.
# Required because Keycloak's --import-realm does not interpolate shell env vars.
set -eu

TEMPLATE=/opt/keycloak/data/import-templates/smart-fhir-realm-template.json
OUTPUT=/opt/keycloak/data/import/smart-fhir-realm.json

mkdir -p /opt/keycloak/data/import
if command -v envsubst >/dev/null 2>&1; then
  envsubst < "$TEMPLATE" > "$OUTPUT"
else
  # Fallback for minimal images where envsubst is not available.
  cp "$TEMPLATE" "$OUTPUT"
  vars=$(grep -o '\${[A-Za-z_][A-Za-z0-9_]*}' "$TEMPLATE" | tr -d '${}' | sort -u || true)
  for var in $vars; do
    value=$(printenv "$var" | sed 's/[\\/&]/\\&/g')
    sed -i "s|\${$var}|$value|g" "$OUTPUT"
  done
fi

# The image was pre-built with `kc.sh build` in the Dockerfile, so we must
# invoke `start --optimized` at runtime.  If Docker CMD provides args (e.g.
# --import-realm), preserve them; otherwise default to "start --optimized".
if [ $# -eq 0 ]; then
  set -- start --optimized
fi

# Ensure runtime DB credentials are passed explicitly in production.
if [ -n "${KC_DB_URL:-}" ]; then
  set -- "$@" "--db-url=${KC_DB_URL}"
fi
if [ -n "${KC_DB_USERNAME:-}" ]; then
  set -- "$@" "--db-username=${KC_DB_USERNAME}"
fi
if [ -n "${KC_DB_PASSWORD:-}" ]; then
  set -- "$@" "--db-password=${KC_DB_PASSWORD}"
fi
if [ -n "${KC_DB_SCHEMA:-}" ]; then
  set -- "$@" "--db-schema=${KC_DB_SCHEMA}"
fi

# Default to HTTP in containerized environments unless HTTPS material is
# explicitly provided. This avoids local startup failures such as:
# "Key material not provided to setup HTTPS".
if [ -z "${KC_HTTPS_KEY_STORE_FILE:-}" ] && [ -z "${KC_HTTPS_CERTIFICATE_FILE:-}" ] && [ -z "${KC_HTTPS_CERTIFICATE_KEY_FILE:-}" ]; then
  KC_HTTP_ENABLED="${KC_HTTP_ENABLED:-true}"
  if [ "$KC_HTTP_ENABLED" = "true" ]; then
    set -- "$@" "--http-enabled=true"
  fi
  if [ -n "${KC_HTTP_PORT:-}" ]; then
    set -- "$@" "--http-port=${KC_HTTP_PORT}"
  fi
fi

# No migration-strategy override. The Keycloak default (update) handles both
# a fresh empty schema and a fully-initialized schema correctly.
# If the schema is in a broken partial-init state, reset it first:
#   DROP SCHEMA IF EXISTS keycloak CASCADE;
#   CREATE SCHEMA keycloak;
#   GRANT ALL ON SCHEMA keycloak TO CURRENT_USER;

# Ensure Keycloak knows its public-facing URL so the admin UI, CSP frame-src
# and 3rd-party cookie check iframe all resolve to the correct host rather
# than falling back to localhost:8080.
# These CLI args take precedence over the env-var equivalents and work
# across all Keycloak 22-26 builds.
if [ -n "${KC_HOSTNAME:-}" ]; then
  set -- "$@" "--hostname=${KC_HOSTNAME}"
fi
if [ -n "${KC_HOSTNAME_ADMIN:-}" ]; then
  set -- "$@" "--hostname-admin=${KC_HOSTNAME_ADMIN}"
fi
# Allow requests on any hostname when KC_HOSTNAME_STRICT is explicitly "false"
# (useful during local dev and before a custom domain is attached).
if [ "${KC_HOSTNAME_STRICT:-}" = "false" ]; then
  set -- "$@" "--hostname-strict=false"
fi

exec /opt/keycloak/bin/kc.sh "$@"
