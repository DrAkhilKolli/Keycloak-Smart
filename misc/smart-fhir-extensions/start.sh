#!/bin/sh
# Substitute environment variables into the realm import template before starting Keycloak.
# Required because Keycloak's --import-realm does not interpolate shell env vars.
set -eu

TEMPLATE=/opt/keycloak/data/import-templates/smart-fhir-realm-template.json
OUTPUT=/opt/keycloak/data/import/smart-fhir-realm.json

mkdir -p /opt/keycloak/data/import
envsubst < "$TEMPLATE" > "$OUTPUT"

exec /opt/keycloak/bin/kc.sh "$@"
