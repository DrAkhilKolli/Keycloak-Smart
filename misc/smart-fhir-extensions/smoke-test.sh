#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# smoke-test.sh – Integration smoke test for ClinicalVault (Keycloak + FHIR)
#
# Run against a locally-started or remote stack to verify:
#   1. FHIR /metadata → CapabilityStatement
#   2. Keycloak /health/ready → UP
#   3. FHIR .well-known/smart-configuration → token_endpoint present
#   4. Keycloak OIDC discovery → issuer present
#   5. (optional) Token issuance via client_credentials flow
#
# Usage:
#   ./smoke-test.sh                            # defaults: localhost:8081 / localhost:8080
#   FHIR_PORT=8081 KC_PORT=8080 ./smoke-test.sh
#   FHIR_BASE_URL=https://fhir.example.com KC_BASE_URL=https://auth.example.com ./smoke-test.sh
#
# To also test the token endpoint (needs a real client secret):
#   SMOKE_CLIENT_ID=smart-client \
#   SMOKE_CLIENT_SECRET=<secret> \
#   SMART_REALM=smart-fhir \
#   ./smoke-test.sh
# ─────────────────────────────────────────────────────────────────────────────

set -euo pipefail

FHIR_BASE_URL="${FHIR_BASE_URL:-http://localhost:${FHIR_PORT:-8081}/fhir-server/api/v4}"
KC_BASE_URL="${KC_BASE_URL:-http://localhost:${KC_PORT:-8080}}"
SMART_REALM="${SMART_REALM:-smart-fhir}"

PASS=0
FAIL=0

_pass() { echo "  PASS: $*"; ((PASS++)); }
_fail() { echo "  FAIL: $*"; ((FAIL++)); }

# ── Helper: require jq or python3 for JSON parsing ────────────────────────────
_jq() {
  local query="$1"; shift
  if command -v jq &>/dev/null; then
    echo "$@" | jq -r "$query" 2>/dev/null || true
  else
    echo "$@" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('${query#.}',''))" 2>/dev/null || true
  fi
}

_json_field() {
  local field="$1"
  local json="$2"
  if command -v jq &>/dev/null; then
    echo "$json" | jq -r ".${field}" 2>/dev/null || true
  else
    echo "$json" | python3 -c "import sys,json; d=json.loads(sys.argv[1]); print(d.get('${field}',''))" "$json" 2>/dev/null || true
  fi
}

echo "=== ClinicalVault Smoke Test ==="
echo "    FHIR:      $FHIR_BASE_URL"
echo "    Keycloak:  $KC_BASE_URL"
echo "    Realm:     $SMART_REALM"
echo ""

# ── 1. FHIR CapabilityStatement ───────────────────────────────────────────────
echo "1. FHIR /metadata"
METADATA_JSON=$(curl -sf -H "Accept: application/fhir+json" "${FHIR_BASE_URL}/metadata" 2>/dev/null || echo "{}")
RT=$(_json_field "resourceType" "$METADATA_JSON")
if [[ "$RT" == "CapabilityStatement" ]]; then
  _pass "resourceType=CapabilityStatement"
else
  _fail "expected CapabilityStatement, got '$RT'"
fi

# ── 2. Keycloak health ────────────────────────────────────────────────────────
echo "2. Keycloak /health/ready"
KC_HEALTH_JSON=$(curl -sf "${KC_BASE_URL}/health/ready" 2>/dev/null || echo "{}")
KC_STATUS=$(_json_field "status" "$KC_HEALTH_JSON")
if [[ "$KC_STATUS" == "UP" ]]; then
  _pass "Keycloak status=UP"
else
  _fail "Keycloak status='$KC_STATUS' (expected UP)"
fi

# ── 3. SMART well-known configuration ────────────────────────────────────────
echo "3. FHIR .well-known/smart-configuration"
SMART_CFG_JSON=$(curl -sf "${FHIR_BASE_URL}/.well-known/smart-configuration" 2>/dev/null || echo "{}")
TOKEN_EP=$(_json_field "token_endpoint" "$SMART_CFG_JSON")
if [[ -n "$TOKEN_EP" ]]; then
  _pass "token_endpoint=$TOKEN_EP"
else
  _fail ".well-known/smart-configuration: token_endpoint missing or empty"
fi

# ── 4. OIDC discovery ────────────────────────────────────────────────────────
echo "4. Keycloak OIDC discovery (realm: $SMART_REALM)"
OIDC_JSON=$(curl -sf "${KC_BASE_URL}/realms/${SMART_REALM}/.well-known/openid-configuration" 2>/dev/null || echo "{}")
ISSUER=$(_json_field "issuer" "$OIDC_JSON")
if [[ -n "$ISSUER" ]]; then
  _pass "issuer=$ISSUER"
else
  _fail "OIDC discovery: issuer missing or empty"
fi

# ── 5. (optional) Client-credentials token issuance ─────────────────────────
if [[ -n "${SMOKE_CLIENT_SECRET:-}" ]]; then
  echo "5. Token issuance (client_credentials)"
  CLIENT_ID="${SMOKE_CLIENT_ID:-smart-client}"
  TOKEN_JSON=$(curl -sf -X POST \
    "${KC_BASE_URL}/realms/${SMART_REALM}/protocol/openid-connect/token" \
    -d "grant_type=client_credentials" \
    -d "client_id=${CLIENT_ID}" \
    -d "client_secret=${SMOKE_CLIENT_SECRET}" \
    2>/dev/null || echo "{}")
  ACCESS_TOKEN=$(_json_field "access_token" "$TOKEN_JSON")
  if [[ -n "$ACCESS_TOKEN" ]]; then
    _pass "access_token issued (client_credentials for $CLIENT_ID)"
  else
    ERR=$(_json_field "error" "$TOKEN_JSON")
    _fail "token endpoint returned error='$ERR'"
  fi
else
  echo "5. Token issuance (skipped – set SMOKE_CLIENT_SECRET to enable)"
fi

# ── Summary ──────────────────────────────────────────────────────────────────
echo ""
echo "=== Results: ${PASS} passed, ${FAIL} failed ==="
if [[ $FAIL -gt 0 ]]; then
  exit 1
fi
