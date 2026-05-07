#!/bin/sh
# Substitute environment variables into the realm import template before starting Keycloak.
# Required because Keycloak's --import-realm does not interpolate shell env vars.
set -eu

TEMPLATE=/opt/keycloak/data/import-templates/smart-fhir-realm-template.json
OUTPUT=/opt/keycloak/data/import/smart-fhir-realm.json

mkdir -p /opt/keycloak/data/import
envsubst < "$TEMPLATE" > "$OUTPUT"

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
