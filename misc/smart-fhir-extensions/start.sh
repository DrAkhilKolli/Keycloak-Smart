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

exec /opt/keycloak/bin/kc.sh "$@"
