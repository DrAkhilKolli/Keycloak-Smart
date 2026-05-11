# Keycloak SMART on FHIR Extensions

This module ports the maintained parts of the old `keycloak-extensions-for-fhir` project into the current Keycloak source tree as a standard provider JAR.

## What moved here

The following old components now live in this module:

| Old component | Current status |
| --- | --- |
| `AudienceValidator` | Ported as authenticator `audience-validator` |
| `PatientSelectionForm` | Ported as authenticator `auth-select-patient` |
| `PatientPrefixUserAttributeMapper` | Retained as compatibility mapper `oidc-patient-prefix-usermodel-attribute-mapper`; the bundled realm template now uses `oidc-fhir-user-claim-mapper` |
| `UserAttributeMapper` | Ported as protocol mapper `oidc-usermodel-attribute-mapper-with-token-response-support` |

## What changed from the old project

- The old `jboss-fhir-provider` module is not needed anymore. Current Keycloak is Quarkus-based, so this module talks to the FHIR server directly over HTTP and parses FHIR JSON without WildFly/JBoss module packaging.
- The old fixed `Patient/` prefix mapper is still available for compatibility, but the included realm-import template uses `oidc-fhir-user-claim-mapper` so `fhirUser` can resolve `Patient`, `Practitioner`, `RelatedPerson`, or any other FHIR resource type from user attributes.
- The old custom `UserAttributeMapper` is kept for compatibility, but the patient launch-context response mapping can now use the built-in `oidc-usersessionmodel-note-mapper` because current Keycloak already supports `access.tokenResponse.claim` there.
- The old `keycloak-config` Java bootstrap client was not copied into this repo. Instead, this module now ships a startup realm-import template under `examples/import/` and a replacement container build path via `misc/smart-fhir-extensions/Dockerfile`.

## Build

From the `keycloak-main` root:

```bash
./mvnw -pl misc/smart-fhir-extensions -am -DskipTests compile
```

The compiled provider JAR is produced under `misc/smart-fhir-extensions/target/`.

To build the local Keycloak server distribution from your forked `keycloak-main` code:

```bash
./mvnw -pl '!js,quarkus/deployment,quarkus/dist' -am -DskipTests package
```

That produces the server tarball under `quarkus/dist/target/`.

## Managed local runtime

Use the checked-in controller script instead of calling `docker compose` directly:

```bash
cp misc/smart-fhir-extensions/.env.smart-keycloak.example misc/smart-fhir-extensions/.env.smart-keycloak
./misc/smart-fhir-extensions/smart-keycloak.sh set-session-conn 'postgres://postgres.<project-ref>:<password>@aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require'
./misc/smart-fhir-extensions/smart-keycloak.sh up
```

That workflow:

1. creates the `keycloak` schema in Supabase when needed
2. builds the local Keycloak distribution from your `keycloak-main` source tree
3. builds the SMART provider JAR from this module
4. builds the container image from those local artifacts only
5. starts Keycloak with the configured admin credentials and realm import

If the target Supabase schema was previously migrated by a released Keycloak version, the managed compose stack also passes `--spi-datastore-legacy-allow-migrate-existing-database-to-snapshot=true` so the local `999.0.0-SNAPSHOT` build can start against that existing schema during development.

The managed runtime also uses `--spi-connections-jpa-quarkus-migration-strategy=validate` and `--spi-connections-jpa-quarkus-initialize-empty=false` so an existing Supabase-backed schema is treated as authoritative instead of being re-migrated on every local startup.

The script also supports:

```bash
./misc/smart-fhir-extensions/smart-keycloak.sh set-session-conn 'postgres://postgres.<project-ref>:<password>@aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require'
./misc/smart-fhir-extensions/smart-keycloak.sh init-db
./misc/smart-fhir-extensions/smart-keycloak.sh up
./misc/smart-fhir-extensions/smart-keycloak.sh down
./misc/smart-fhir-extensions/smart-keycloak.sh logs
./misc/smart-fhir-extensions/smart-keycloak.sh push-image
```

`push-image` publishes whatever tag is configured in `.env.smart-keycloak`, so you can point `SMART_KEYCLOAK_IMAGE` at a private registry such as GHCR or an internal container registry.

Use the direct Supabase Postgres endpoint on port `5432` when your environment supports IPv6. If Docker Desktop cannot resolve `db.<project-ref>.supabase.co`, switch to the Supavisor session-mode host from the Supabase dashboard, still on port `5432`, and change the database username to the pooled form `postgres.<project-ref>`. `smart-keycloak.sh set-session-conn 'postgres://...'` will rewrite `.env.smart-keycloak` for you. Do not use the `6543` transaction-pooler endpoint for Keycloak.

## Build a replacement container image manually

From the repository root:

```bash
./keycloak-main/mvnw -f keycloak-main/pom.xml -pl quarkus/deployment,quarkus/dist -am -DskipTests package
./keycloak-main/mvnw -f keycloak-main/pom.xml -pl '!js,misc/smart-fhir-extensions' -am -DskipTests package
docker build . \
  -f keycloak-main/misc/smart-fhir-extensions/Dockerfile \
  -t smart-keycloak-local:dev
```

This image is built from the local `keycloak-main` distribution and the local SMART provider JAR. It does not use the published upstream `quay.io/keycloak/keycloak` runtime image.

The image includes:

1. the SMART-on-FHIR provider JAR under `/opt/keycloak/providers/`
2. your locally built Keycloak server distribution from `quarkus/dist/target/`
3. a startup import template at `/opt/keycloak/data/import/smart-fhir-realm-template.json`
4. the Clinivault login theme under `/opt/keycloak/themes/clinivault`

To use the included template at startup:

```bash
docker run --rm -p 8080:8080 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=test_admin@healthcare \
  -e KC_BOOTSTRAP_ADMIN_PASSWORD=change-me \
  -e SMART_REALM=test \
  -e FHIR_BASE_URL=https://fhir.example.com/fhir \
  -e INTERNAL_FHIR_URL=http://fhir.internal/fhir \
  -e SMART_CLIENT_ID=smart-launch-client \
  -e SMART_CLIENT_REDIRECT_URI=http://localhost:3000/* \
  -e KC_DB=postgres \
  -e KC_DB_URL='jdbc:postgresql://db.example.supabase.co:5432/postgres?sslmode=require' \
  -e KC_DB_USERNAME=postgres \
  -e KC_DB_PASSWORD=change-me \
  -e KC_DB_SCHEMA=keycloak \
  smart-keycloak start-dev --import-realm
```

## Local Docker Compose deployment

Use `smart-keycloak.sh up` for the concrete local stack. If you need the raw Compose command, it is:

```bash
docker compose \
  --env-file misc/smart-fhir-extensions/.env.smart-keycloak \
  -f misc/smart-fhir-extensions/docker-compose.local.yml \
  up --build
```

That stack:

1. builds the SMART-enabled Keycloak image from this repository's local distribution artifacts
2. starts a local HAPI FHIR server on `http://localhost:8081/fhir`
3. starts Keycloak on `http://localhost:8080`
4. imports `examples/import/smart-fhir-realm-template.json` on startup using concrete local values

For a step-by-step local deployment and LLM-oriented integration guide, see `LOCAL_DEPLOYMENT_FOR_LLM.md`.

## Deploy to Keycloak

1. Copy the built JAR to the Keycloak `providers/` directory.
2. Run `kc.sh build` or `kc.bat build`.
3. Start Keycloak normally.

## Realm configuration

For automated bootstrap, use the import template at `examples/import/smart-fhir-realm-template.json`. The template uses standard Keycloak placeholder replacement, so values like `${SMART_REALM}` and `${FHIR_BASE_URL}` are resolved from environment variables during `start --import-realm`, `start-dev --import-realm`, or `kc.sh import --file ...`.

### Required client scopes and mappers

#### `fhirUser`

Create an OIDC client scope named `fhirUser` and attach mapper `oidc-fhir-user-claim-mapper` with configuration similar to the bundled realm template:

```json
{
  "protocolMapper": "oidc-fhir-user-claim-mapper",
  "config": {
    "resourceIdAttribute": "resourceId",
    "resourceTypeAttribute": "fhirResourceType",
    "defaultResourceType": "Patient",
    "claim.name": "fhirUser",
    "jsonType.label": "String",
    "id.token.claim": "true",
    "access.token.claim": "true",
    "userinfo.token.claim": "true"
  }
}
```

This is the mapper used by `examples/import/smart-fhir-realm-template.json` and it supports both patient-facing and EHR-user identities without per-user mapper changes.

#### `launch/patient`

Create an OIDC client scope named `launch/patient` and attach:

1. A normal user-attribute mapper for the access-token claim:

```json
{
  "protocolMapper": "oidc-usermodel-attribute-mapper",
  "config": {
    "user.attribute": "resourceId",
    "claim.name": "patient_id",
    "jsonType.label": "String",
    "id.token.claim": "false",
    "access.token.claim": "true",
    "userinfo.token.claim": "false"
  }
}
```

2. A built-in user-session-note mapper for the token response payload:

```json
{
  "protocolMapper": "oidc-usersessionmodel-note-mapper",
  "config": {
    "user.session.note": "patient_id",
    "claim.name": "patient",
    "jsonType.label": "String",
    "id.token.claim": "false",
    "access.token.claim": "false",
    "access.tokenResponse.claim": "true"
  }
}
```

#### `abac`

The bundled realm template also defines an optional scope named `abac` for attribute-based policy context. It maps these user attributes into access-token claims:

1. `tenantId` -> `tenant_id`
2. `orgId` -> `org_id`
3. `purposeOfUse` -> `purpose_of_use`

Assign this scope to SMART clients that must pass ABAC context to the FHIR server. The template adds `abac` to the default `optionalClientScopes` list for `SMART_CLIENT_ID`.

To populate the claims, set user attributes on the authenticating Keycloak user:

1. `tenantId`
2. `orgId`
3. `purposeOfUse`

These claims align with ABAC checks in `fhir-server/fhir-smart` and the default FHIR resource label systems:

1. `https://linuxforhealth.org/fhir/abac/tenant`
2. `https://linuxforhealth.org/fhir/abac/org`

### Required authentication flow

Create a browser flow named `SMART App Launch` with a subflow named `SMART Login` and add these executions in order:

1. `Audience Validation` using authenticator `audience-validator`
2. `Username Password Form` using authenticator `auth-username-password-form`
3. `Patient Selection Authenticator` using authenticator `auth-select-patient`

Recommended requirements inside the `SMART Login` subflow:

1. `Audience Validation`: `REQUIRED`
2. `Username Password Form`: `REQUIRED`
3. `Patient Selection Authenticator`: `REQUIRED`

`Patient Selection Authenticator` must be configured with:

```json
{
  "internalFhirUrl": "https://your-internal-fhir-base"
}
```

`Audience Validation` must be configured with one or more allowed audience URLs. The provider accepts values separated by `##` or newlines.

## Admin API scripting targets

If you prefer CI/CD automation instead of realm import, the relevant Admin REST areas are:

1. `/admin/realms/{realm}/client-scopes`
2. `/admin/realms/{realm}/client-scopes/{id}/protocol-mappers/models`
3. `/admin/realms/{realm}/authentication/flows`
4. `/admin/realms/{realm}/authentication/flows/{flowAlias}/executions/flow`
5. `/admin/realms/{realm}/authentication/flows/{flowAlias}/executions/execution`
6. `/admin/realms/{realm}/authentication/executions/{executionId}/config`

Those endpoints are enough to recreate the old SMART bootstrap behavior with current Keycloak-native tooling.

## Replacement assets for retirement

These files replace the operational parts of the retired repo:

1. `misc/smart-fhir-extensions/Dockerfile` replaces the old legacy `smart-keycloak` image packaging and now consumes the local `keycloak-main` distribution build.
2. `misc/smart-fhir-extensions/examples/import/smart-fhir-realm-template.json` replaces the old `keycloak-config` JSON-driven bootstrap path for baseline SMART-on-FHIR realm setup.
3. `misc/smart-fhir-extensions/docker-compose.local.yml` provides a concrete local Keycloak + FHIR runtime.
4. `misc/smart-fhir-extensions/smart-keycloak.sh` is the supported entry point for schema init, local image build, compose up/down, and optional image publishing.
5. `misc/smart-fhir-extensions/LOCAL_DEPLOYMENT_FOR_LLM.md` documents the exact local integration workflow for humans or LLM-driven automation.

## Notes for FHIR deployments

- The patient selector expects the authenticating Keycloak user to have one or more `resourceId` attributes.
- When multiple `resourceId` values exist and `launch/patient` is requested, the authenticator retrieves `Patient/{id}` resources from the configured internal FHIR base URL and renders a selection form.
- If the incoming SMART request includes `aud`, that value is reused as the access-token audience for the internal FHIR lookup token.
- If `aud` is missing, the internal FHIR base URL is used as a fallback audience for that lookup token.