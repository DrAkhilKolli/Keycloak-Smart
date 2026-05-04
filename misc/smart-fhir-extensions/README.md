# Keycloak SMART on FHIR Extensions

This module ports the maintained parts of the old `keycloak-extensions-for-fhir` project into the current Keycloak source tree as a standard provider JAR.

## What moved here

The following old components now live in this module:

| Old component | Current status |
| --- | --- |
| `AudienceValidator` | Ported as authenticator `audience-validator` |
| `PatientSelectionForm` | Ported as authenticator `auth-select-patient` |
| `PatientPrefixUserAttributeMapper` | Ported as protocol mapper `oidc-patient-prefix-usermodel-attribute-mapper` |
| `UserAttributeMapper` | Ported as protocol mapper `oidc-usermodel-attribute-mapper-with-token-response-support` |

## What changed from the old project

- The old `jboss-fhir-provider` module is not needed anymore. Current Keycloak is Quarkus-based, so this module talks to the FHIR server directly over HTTP and parses FHIR JSON without WildFly/JBoss module packaging.
- The old custom `UserAttributeMapper` is kept for compatibility, but the patient launch-context response mapping can now use the built-in `oidc-usersessionmodel-note-mapper` because current Keycloak already supports `access.tokenResponse.claim` there.
- The old `keycloak-config` Java bootstrap client was not copied into this repo. Instead, this module now ships a startup realm-import template under `examples/import/` and a replacement container build path via `misc/smart-fhir-extensions/Dockerfile`.

## Build

From the `keycloak-main` root:

```bash
./mvnw -pl misc/smart-fhir-extensions -am -DskipTests compile
```

The compiled provider JAR is produced under `misc/smart-fhir-extensions/target/`.

## Build a replacement container image

From the `keycloak-main` root:

```bash
docker build . \
  -f misc/smart-fhir-extensions/Dockerfile \
  --build-arg KEYCLOAK_IMAGE=quay.io/keycloak/keycloak:latest \
  -t smart-keycloak
```

This replaces the old `alvearie/smart-keycloak` image path. The image includes:

1. the SMART-on-FHIR provider JAR under `/opt/keycloak/providers/`
2. a startup import template at `/opt/keycloak/data/import/smart-fhir-realm-template.json`

To use the included template at startup:

```bash
docker run --rm -p 8080:8080 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin \
  -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  -e SMART_REALM=test \
  -e FHIR_BASE_URL=https://fhir.example.com/fhir \
  -e INTERNAL_FHIR_URL=http://fhir.internal/fhir \
  -e SMART_CLIENT_ID=smart-launch-client \
  -e SMART_CLIENT_REDIRECT_URI=http://localhost:3000/* \
  smart-keycloak start-dev --import-realm
```

## Local Docker Compose deployment

Use the concrete local stack at `docker-compose.local.yml` when you want Keycloak and a local FHIR server together:

```bash
docker compose -f misc/smart-fhir-extensions/docker-compose.local.yml up --build
```

That stack:

1. builds the SMART-enabled Keycloak image from this repository
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

Create an OIDC client scope named `fhirUser` and attach mapper `oidc-patient-prefix-usermodel-attribute-mapper` with configuration similar to:

```json
{
  "user.attribute": "resourceId",
  "claim.name": "fhirUser",
  "jsonType.label": "String",
  "id.token.claim": "true",
  "access.token.claim": "false",
  "userinfo.token.claim": "true"
}
```

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

1. `misc/smart-fhir-extensions/Dockerfile` replaces the old legacy `smart-keycloak` image packaging.
2. `misc/smart-fhir-extensions/examples/import/smart-fhir-realm-template.json` replaces the old `keycloak-config` JSON-driven bootstrap path for baseline SMART-on-FHIR realm setup.
3. `misc/smart-fhir-extensions/docker-compose.local.yml` provides a concrete local Keycloak + FHIR runtime.
4. `misc/smart-fhir-extensions/LOCAL_DEPLOYMENT_FOR_LLM.md` documents the exact local integration workflow for humans or LLM-driven automation.

## Notes for FHIR deployments

- The patient selector expects the authenticating Keycloak user to have one or more `resourceId` attributes.
- When multiple `resourceId` values exist and `launch/patient` is requested, the authenticator retrieves `Patient/{id}` resources from the configured internal FHIR base URL and renders a selection form.
- If the incoming SMART request includes `aud`, that value is reused as the access-token audience for the internal FHIR lookup token.
- If `aud` is missing, the internal FHIR base URL is used as a fallback audience for that lookup token.