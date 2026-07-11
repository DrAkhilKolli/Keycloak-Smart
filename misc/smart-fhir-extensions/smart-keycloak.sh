#!/usr/bin/env bash

set -euo pipefail

# Color codes for terminal output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
ENV_FILE=${SMART_KEYCLOAK_ENV_FILE:-$SCRIPT_DIR/.env.smart-keycloak}
ENV_TEMPLATE=$SCRIPT_DIR/.env.smart-keycloak.example
COMPOSE_FILE=$SCRIPT_DIR/docker-compose.local.yml
PROJECT_POM=$REPO_ROOT/pom.xml
PSQL_IMAGE=${PSQL_IMAGE:-postgres:16-alpine}

usage() {
  cat <<'EOF'
Usage: ./smart-keycloak.sh <command>

Commands:
  set-session-conn <url>
                   Parse a Supabase session-mode Postgres URL and write it into the env file
  init-db          Create the Keycloak schema in Supabase if it does not exist
  check-db         Verify database connectivity and schema visibility  build-fhir-image   Build the custom FHIR server container image from fhir-server/.
  build-js         Build JS frontends (account-ui, admin-ui, themes-vendor) with pnpm and install JARs to ~/.m2
  build-artifacts  Build JS frontends, Keycloak distribution, and SMART provider jar
  build-image      Build the local SMART Keycloak container image
  up               Build artifacts, build image, and start the compose stack
  down             Stop the compose stack
  ps               Show compose service status
  logs             Tail Keycloak logs from the compose stack
  config           Render the resolved compose configuration
  print-admin      Print the admin console URL and bootstrap credentials
  push-image       Push the configured image tag to its registry
EOF
}

ensure_env_file() {
  if [[ -f "$ENV_FILE" ]]; then
    return
  fi

  if [[ -f "$ENV_TEMPLATE" ]]; then
    cp "$ENV_TEMPLATE" "$ENV_FILE"
    echo "Created $ENV_FILE from $ENV_TEMPLATE. Review the values and rerun." >&2
    exit 1
  fi

  echo "Missing env file: $ENV_FILE" >&2
  exit 1
}

load_env() {
  ensure_env_file
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    exit 1
  fi
}

require_env() {
  local name=$1
  if [[ -z ${!name:-} ]]; then
    echo "Missing required env var: $name" >&2
    exit 1
  fi
}

set_session_conn() {
  ensure_env_file
  require_command python3

  local conn=${1:-}
  if [[ -z "$conn" ]]; then
  echo "Usage: ./smart-keycloak.sh set-session-conn 'postgres://user:password@host:5432/postgres?sslmode=require'" >&2
  echo "   or: ./smart-keycloak.sh set-session-conn 'jdbc:postgresql://host:5432/postgres?user=user&password=pass&sslmode=require'" >&2
    exit 1
  fi

  python3 - "$ENV_FILE" "$conn" <<'PY'
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse
import sys

env_path = Path(sys.argv[1])
conn = sys.argv[2]
if conn.startswith("jdbc:"):
  conn = conn[5:]
parsed = urlparse(conn)

if parsed.scheme not in {"postgres", "postgresql"}:
    raise SystemExit("Connection string must start with postgres:// or postgresql://")
query = parse_qs(parsed.query)
username = parsed.username or query.get("user", [""])[0]
password = parsed.password or query.get("password", [""])[0]

if not parsed.hostname or not username or parsed.path in {"", "/"}:
    raise SystemExit("Connection string must include host, username, and database name")

values = {
    "KC_DB_HOST": parsed.hostname,
    "KC_DB_PORT": str(parsed.port or 5432),
    "KC_DB_DATABASE": parsed.path.lstrip("/"),
  "KC_DB_USERNAME": unquote(username),
  "KC_DB_PASSWORD": unquote(password),
    "KC_DB_SSL_MODE": query.get("sslmode", ["require"])[0],
}

lines = env_path.read_text().splitlines()
updated = []
seen = set()
for line in lines:
    if "=" in line and not line.lstrip().startswith("#"):
        key = line.split("=", 1)[0]
        if key in values:
            updated.append(f"{key}={values[key]}")
            seen.add(key)
            continue
    updated.append(line)

for key, value in values.items():
    if key not in seen:
        updated.append(f"{key}={value}")

env_path.write_text("\n".join(updated) + "\n")
print(f"Updated {env_path}")
print(f"  KC_DB_HOST={values['KC_DB_HOST']}")
print(f"  KC_DB_PORT={values['KC_DB_PORT']}")
print(f"  KC_DB_DATABASE={values['KC_DB_DATABASE']}")
print(f"  KC_DB_USERNAME={values['KC_DB_USERNAME']}")
print(f"  KC_DB_SSL_MODE={values['KC_DB_SSL_MODE']}")
PY
}

check_db_host_resolution() {
  require_command python3
  if python3 - "$KC_DB_HOST" <<'PY'
import socket
import sys

try:
    socket.getaddrinfo(sys.argv[1], 5432, proto=socket.IPPROTO_TCP)
except OSError:
    raise SystemExit(1)
PY
  then
    return
  fi

  cat >&2 <<EOF
Database host '$KC_DB_HOST' is not resolvable from this machine.

If this is a Supabase direct host, switch KC_DB_HOST to the Supavisor session-mode host from the Supabase dashboard and update KC_DB_USERNAME to the pooled format 'postgres.<project-ref>'.
Keep using port 5432. Do not use the transaction-pooler port 6543 for Keycloak.
EOF
  exit 1
}

compose() {
  load_env

  local compose_args=(--env-file "$ENV_FILE" -f "$COMPOSE_FILE")
  if [[ -n ${SMART_KEYCLOAK_COMPOSE_OVERRIDE_FILE:-} ]]; then
    compose_args+=( -f "$SMART_KEYCLOAK_COMPOSE_OVERRIDE_FILE" )
  fi

  docker compose "${compose_args[@]}" "$@"
}

run_maven() {
  local modules=$1
  shift || true
  local goals=("$@")
  if [[ ${#goals[@]} -eq 0 ]]; then
    goals=(package)
  fi
  require_command docker
  "$REPO_ROOT/mvnw" -B -f "$PROJECT_POM" -pl "$modules" -am -DskipTests "${goals[@]}"
}

run_maven_without_tests() {
  local modules=$1
  shift || true
  local goals=("$@")
  if [[ ${#goals[@]} -eq 0 ]]; then
    goals=(package)
  fi
  require_command docker
  "$REPO_ROOT/mvnw" -B -f "$PROJECT_POM" -pl "$modules" -am -Dmaven.test.skip=true "${goals[@]}"
}

prepare_theme_vendor_overlay() {
  require_command jar

  local overlay_root=$SCRIPT_DIR/target/keycloak-theme-common-resources
  local theme_vendor_jar

  theme_vendor_jar=$(find "$HOME/.m2/repository/org/keycloak/keycloak-themes-vendor" -name 'keycloak-themes-vendor-*.jar' -type f | sort | tail -n 1)

  if [[ -z "$theme_vendor_jar" ]]; then
    echo "Missing cached keycloak-themes-vendor jar in ~/.m2. Build the JS assets once before building the image." >&2
    exit 1
  fi

  rm -rf "$overlay_root"
  mkdir -p "$overlay_root"

  (
    cd "$overlay_root"
    jar xf "$theme_vendor_jar" theme/keycloak/common/resources/vendor
  )

  if [[ ! -f "$overlay_root/theme/keycloak/common/resources/vendor/web-crypto-shim/web-crypto-shim.js" ]]; then
    echo "Cached keycloak-themes-vendor jar does not contain web-crypto-shim.js" >&2
    exit 1
  fi
}

build_fhir_image() {
  load_env
  echo "=========================================="
  echo "Building custom FHIR Server image"
  echo "=========================================="
  echo ""
  local fhir_dir
  fhir_dir=$(CDPATH= cd -- "$SCRIPT_DIR/../../../FHIR-Server" && pwd)

  if [[ ! -d "$fhir_dir" ]]; then
    echo -e "${RED}fhir-server directory not found at $fhir_dir${NC}" >&2
    exit 1
  fi

  local image_tag=${SMART_FHIR_IMAGE:-fhir-server-local:dev}
  echo "Building $image_tag from $fhir_dir ..."
  docker build \
    --tag "$image_tag" \
    --file "$fhir_dir/Dockerfile" \
    "$fhir_dir"
  echo -e "${GREEN}✓ FHIR server image built: $image_tag${NC}"
  echo ""
}

build_js() {
  require_command pnpm
  local js_dir="$REPO_ROOT/js"

  echo "Building JS frontend assets (account-ui, admin-ui, themes-vendor)..."
  (cd "$js_dir" && pnpm install --frozen-lockfile)
  (cd "$js_dir" && pnpm \
    --filter @keycloak/keycloak-account-ui \
    --filter @keycloak/keycloak-admin-ui \
    --filter themes-vendor \
    build)

  echo "Packaging JS JARs into local Maven repository..."
  for module in apps/account-ui apps/admin-ui themes-vendor; do
    local module_dir="$js_dir/$module"
    local artifact_id
    artifact_id=$(
      sed -n '/<parent>/,/<\/parent>/d; s/.*<artifactId>\(.*\)<\/artifactId>.*/\1/p' "$module_dir/pom.xml" \
        | head -n 1
    )
    local version="999.0.0-SNAPSHOT"
    local jar_path="$module_dir/target/${artifact_id}-${version}.jar"

    (cd "$module_dir" && "$REPO_ROOT/mvnw" -B jar:jar -q)
    "$REPO_ROOT/mvnw" -B install:install-file -q \
      -Dfile="$jar_path" \
      -DgroupId=org.keycloak \
      -DartifactId="$artifact_id" \
      -Dversion="$version" \
      -Dpackaging=jar
    echo "  ✓ Installed $artifact_id-$version.jar ($(du -sh "$jar_path" | cut -f1))"
  done
}

build_artifacts() {
  echo "Building JS frontend modules..."
  build_js

  echo "Building local Keycloak distribution from keycloak-main source..."
  run_maven_without_tests "!js,model/infinispan,quarkus/runtime,quarkus/deployment,quarkus/dist" package

  echo "Building SMART on FHIR provider jar..."
  run_maven "!js,misc/smart-fhir-extensions"
}

psql() {
  docker run --rm \
    -e PGPASSWORD="$KC_DB_PASSWORD" \
    "$PSQL_IMAGE" \
    psql \
    "host=$KC_DB_HOST port=$KC_DB_PORT dbname=$KC_DB_DATABASE user=$KC_DB_USERNAME sslmode=$KC_DB_SSL_MODE" \
    -v ON_ERROR_STOP=1 \
    "$@"
}

init_db() {
  load_env
  require_command docker
  require_env KC_DB_HOST
  require_env KC_DB_PORT
  require_env KC_DB_DATABASE
  require_env KC_DB_SCHEMA
  require_env KC_DB_USERNAME
  require_env KC_DB_PASSWORD
  require_env KC_DB_SSL_MODE
  check_db_host_resolution

  echo "Ensuring schema '$KC_DB_SCHEMA' exists on $KC_DB_HOST:$KC_DB_PORT..."
  if psql -Atc "SELECT 1 FROM information_schema.schemata WHERE schema_name = '$KC_DB_SCHEMA'" | grep -q 1; then
    echo "Schema '$KC_DB_SCHEMA' already exists."
  else
    echo "Schema '$KC_DB_SCHEMA' not found, creating it..."
    psql -c "CREATE SCHEMA IF NOT EXISTS \"$KC_DB_SCHEMA\";"
    echo "Schema created. Keycloak will migrate it on first startup."
  fi
}

check_db() {
  load_env
  require_command docker
  check_db_host_resolution
  echo "Checking Supabase connectivity for schema '$KC_DB_SCHEMA'..."
  psql -At -c "SELECT current_database() || ':' || current_user;"
  psql -At -c "SELECT schema_name FROM information_schema.schemata WHERE schema_name = '$KC_DB_SCHEMA';"
}

build_image() {
  build_artifacts
  echo "Preparing shared theme vendor overlay..."
  prepare_theme_vendor_overlay
  echo "Building local SMART Keycloak image..."
  compose build keycloak
}

up() {
  load_env

  echo "=========================================="
  echo "Step 1: Initialize Keycloak Database Schema"
  echo "=========================================="
  echo ""
  init_db

  echo "=========================================="
  echo "Step 2: Build FHIR Server Image"
  echo "=========================================="
  echo ""
  if [[ ${START_LOCAL_FHIR:-true} == "true" ]]; then
    build_fhir_image
  fi

  echo "=========================================="
  echo "Step 3: Build Keycloak Image"
  echo "=========================================="
  echo ""
  build_image

  echo "=========================================="
  echo "Step 4: Start Services"
  echo "=========================================="
  echo ""

  local compose_up_args=(-d)
  if [[ ${START_LOCAL_FHIR:-true} != "true" ]]; then
    echo "START_LOCAL_FHIR is false — starting Keycloak only."
    compose_up_args+=(--no-deps keycloak)
  else
    echo "START_LOCAL_FHIR is true — starting FHIR, Kafka, Keycloak."
    compose_up_args+=(fhir kafka zookeeper keycloak)
  fi

  echo "Starting compose services..."
  compose up "${compose_up_args[@]}"
  echo ""

  echo "Waiting for Keycloak to become healthy (this may take up to 3 minutes)..."
  local TIMEOUT=180
  local ELAPSED=0
  until compose ps keycloak | grep -q "healthy"; do
    if [[ $ELAPSED -ge $TIMEOUT ]]; then
      echo -e "${RED}✗ Keycloak did not become healthy within ${TIMEOUT}s.${NC}"
      echo "  Run './smart-keycloak.sh logs' to investigate."
      exit 1
    fi
    echo "  Waiting... (${ELAPSED}/${TIMEOUT}s)"
    sleep 15
    ELAPSED=$((ELAPSED + 15))
  done

  echo -e "${GREEN}✓ All services are up and healthy!${NC}"
  echo ""
  print_admin
}

down() {
  compose down
}

ps_cmd() {
  compose ps
}

logs() {
  compose logs -f keycloak
}

config() {
  compose config
}

print_admin() {
  load_env
  echo "Admin console: http://localhost:${SMART_KEYCLOAK_HTTP_PORT:-8080}/admin/master/console/"
  echo "Bootstrap admin username: ${KC_BOOTSTRAP_ADMIN_USERNAME}"
  echo "Bootstrap admin password: ${KC_BOOTSTRAP_ADMIN_PASSWORD}"
  echo "Note: bootstrap admin credentials only create a user when the target database does not already contain the master realm."
}

push_image() {
  load_env
  require_env SMART_KEYCLOAK_IMAGE
  build_image
  echo "Pushing $SMART_KEYCLOAK_IMAGE ..."
  docker push "$SMART_KEYCLOAK_IMAGE"
}

main() {
  local command=${1:-help}

  case "$command" in
    set-session-conn)
      shift
      set_session_conn "${1:-}"
      ;;
    init-db)
      init_db
      ;;
    check-db)
      check_db
      ;;
    build-fhir-image)
      build_fhir_image
      ;;
    build-js)
      build_js
      ;;
    build-artifacts)
      build_artifacts
      ;;
    build-image)
      build_image
      ;;
    up)
      up
      ;;
    down)
      down
      ;;
    ps)
      ps_cmd
      ;;
    logs)
      logs
      ;;
    config)
      config
      ;;
    print-admin)
      print_admin
      ;;
    push-image)
      push_image
      ;;
    help|-h|--help)
      usage
      ;;
    *)
      echo "Unknown command: $command" >&2
      usage >&2
      exit 1
      ;;
  esac
}

main "$@"