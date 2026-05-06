# Local SMART on FHIR Deployment For LLMs

This document is the canonical local deployment procedure for running the SMART-on-FHIR Keycloak extension with a local FHIR server.

Use it when you need a deterministic local setup for development, demos, or LLM-assisted integration work.

## Files

- `misc/smart-fhir-extensions/Dockerfile`
- `misc/smart-fhir-extensions/docker-compose.local.yml`
- `misc/smart-fhir-extensions/examples/import/smart-fhir-realm-template.json`

## Local endpoints

- Keycloak base URL: `http://localhost:8080`
- Admin console: `http://localhost:8080/admin`
- Imported realm: `smart`
- Public FHIR URL used in SMART `aud`: `http://localhost:8081/fhir`
- Internal FHIR URL used by Keycloak patient lookup: `http://fhir:8080/fhir`
- Default SMART client id: `smart-launch-client`
- Default SMART client redirect URI wildcard: `http://localhost:3000/*`

## What the compose stack does

`docker-compose.local.yml` builds the local SMART-enabled Keycloak image from this repository, starts a local HAPI FHIR server, and starts Keycloak with `start-dev --import-realm`.

At container start, `start.sh` runs `envsubst` to interpolate environment variable placeholders in `smart-fhir-realm-template.json` and writes the rendered file to `/opt/keycloak/data/import/smart-fhir-realm.json` before launching Keycloak. Keycloak then auto-imports that rendered realm on startup.

## Required command

Run this from the `keycloak-main` root:

```bash
docker compose -f misc/smart-fhir-extensions/docker-compose.local.yml up --build
```

To stop the stack:

```bash
docker compose -f misc/smart-fhir-extensions/docker-compose.local.yml down
```

To rebuild after editing the provider or template:

```bash
docker compose -f misc/smart-fhir-extensions/docker-compose.local.yml up --build --force-recreate
```

## Imported realm values

The compose file supplies these concrete template values:

- `SMART_REALM=smart`
- `FHIR_BASE_URL=http://localhost:8081/fhir`
- `INTERNAL_FHIR_URL=http://fhir:8080/fhir`
- `SMART_CLIENT_ID=smart-launch-client`
- `SMART_CLIENT_REDIRECT_URI=http://localhost:3000/*`

These values are consumed directly by `smart-fhir-realm-template.json`.

## Admin login

The compose stack boots Keycloak with:

- username: `admin`
- password: `admin`

These are provided through the current Keycloak bootstrap env vars:

- `KC_BOOTSTRAP_ADMIN_USERNAME`
- `KC_BOOTSTRAP_ADMIN_PASSWORD`

## Local SMART client assumption

The default redirect URI wildcard is `http://localhost:3000/*`.

Use that when your SMART app runs on the host at port `3000`, for example:

- `http://localhost:3000/callback`
- `http://localhost:3000/launch`
- `http://localhost:3000/silent-renew`

If your app runs on a different host or port, change `SMART_CLIENT_REDIRECT_URI` in `docker-compose.local.yml` before starting the stack.

## Deterministic local test data

The patient selection authenticator only works when the Keycloak user has one or more `resourceId` attributes that match Patient ids in the FHIR server.

Create deterministic Patient resources in the local FHIR server with these commands:

```bash
curl -X PUT http://localhost:8081/fhir/Patient/demo-patient-1 \
  -H 'Content-Type: application/fhir+json' \
  -d '{
    "resourceType": "Patient",
    "id": "demo-patient-1",
    "name": [{"family": "Doe", "given": ["Jane"]}],
    "gender": "female",
    "birthDate": "1985-05-05"
  }'
```

```bash
curl -X PUT http://localhost:8081/fhir/Patient/demo-patient-2 \
  -H 'Content-Type: application/fhir+json' \
  -d '{
    "resourceType": "Patient",
    "id": "demo-patient-2",
    "name": [{"family": "Doe", "given": ["John"]}],
    "gender": "male",
    "birthDate": "1982-02-02"
  }'
```

Create a matching Keycloak user and attach `resourceId` values with `kcadm.sh`:

```bash
docker exec smart-keycloak-local /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8080 \
  --realm master \
  --user admin \
  --password admin
```

```bash
docker exec smart-keycloak-local /opt/keycloak/bin/kcadm.sh create users \
  -r smart \
  -s username=smart-user \
  -s enabled=true \
  -s email=smart-user@example.test \
  -s 'attributes.resourceId=["demo-patient-1","demo-patient-2"]'
```

```bash
docker exec smart-keycloak-local /opt/keycloak/bin/kcadm.sh set-password \
  -r smart \
  --username smart-user \
  --new-password password
```

This user will trigger the patient picker because it has two `resourceId` values.

## ABAC quick setup (optional)

The bundled realm import now includes an `abac` optional client scope that emits:

- `tenant_id` from Keycloak user attribute `tenantId`
- `org_id` from Keycloak user attribute `orgId`
- `purpose_of_use` from Keycloak user attribute `purposeOfUse`

Set those attributes on a test user:

```bash
docker exec smart-keycloak-local /opt/keycloak/bin/kcadm.sh update users/$(docker exec smart-keycloak-local /opt/keycloak/bin/kcadm.sh get users -r smart -q username=smart-user --fields id --format csv | tail -n 1 | tr -d '\r') \
  -r smart \
  -s 'attributes.tenantId=["tenant-a"]' \
  -s 'attributes.orgId=["org-123"]' \
  -s 'attributes.purposeOfUse=["TREAT"]'
```

When enabling ABAC in the FHIR server config, keep these default label systems aligned:

- `https://linuxforhealth.org/fhir/abac/tenant`
- `https://linuxforhealth.org/fhir/abac/org`

## SMART request example

When integrating a local SMART app, send the authorization request to the imported `smart` realm and include:

- `scope=openid launch/patient patient/Patient.read`
- `aud=http://localhost:8081/fhir`
- `client_id=smart-launch-client`

Example authorization endpoint:

```text
http://localhost:8080/realms/smart/protocol/openid-connect/auth
```

The `aud` value must match one of the configured allowed audiences in the imported flow config. In the local compose stack that means:

- `http://localhost:8081/fhir`
- `http://fhir:8080/fhir`

External SMART apps should use the public value:

- `http://localhost:8081/fhir`

## Expected local behavior

For a user with one `resourceId`, login completes without a patient selection form.

For a user with multiple `resourceId` values and `launch/patient` requested:

1. Keycloak validates `aud`
2. Keycloak shows the username/password login form
3. Keycloak calls the internal FHIR URL to retrieve `Patient/{id}` resources
4. Keycloak renders the patient chooser
5. The selected patient is emitted in the token response as `patient`

## LLM operating rules

When an LLM is asked to run this stack locally, it should:

1. Use `misc/smart-fhir-extensions/docker-compose.local.yml` as the default entry point.
2. Assume the public SMART FHIR `aud` is `http://localhost:8081/fhir` unless the compose file is edited.
3. Assume the Keycloak realm is `smart`.
4. Assume the default SMART client id is `smart-launch-client`.
5. Change `SMART_CLIENT_REDIRECT_URI` in the compose file if the local SMART app is not on `http://localhost:3000/*`.
6. Create matching FHIR Patient ids and Keycloak `resourceId` attributes before testing patient launch behavior.

## Token verification

After a successful SMART login, verify that ABAC claims are present in the issued access token. Use the Keycloak token endpoint to obtain a token directly (password grant, for local testing only):

```bash
TOKEN=$(curl -s -X POST \
  "http://localhost:8080/realms/smart/protocol/openid-connect/token" \
  -d "grant_type=password" \
  -d "client_id=smart-launch-client" \
  -d "username=smart-user" \
  -d "password=password" \
  -d "scope=openid launch/patient patient/Patient.read abac" \
  | jq -r '.access_token')
echo "$TOKEN" | cut -d'.' -f2 | base64 -d 2>/dev/null | jq '{tenant_id, org_id, purpose_of_use, patient_id}'
```

Expected output (after setting ABAC user attributes):

```json
{
  "tenant_id": "tenant-a",
  "org_id": "org-123",
  "purpose_of_use": "TREAT",
  "patient_id": "demo-patient-1"
}
```

If `tenant_id`, `org_id`, or `purpose_of_use` are missing:

1. Confirm the `abac` scope is listed under `optionalClientScopes` on the client in Keycloak.
2. Confirm `abac` was included in the `scope` parameter of the token request.
3. Confirm the user has `tenantId`, `orgId`, `purposeOfUse` attributes set (see **ABAC quick setup** above).

## When integrating a different local FHIR server

If you replace the bundled HAPI server with another local FHIR server, update both of these values together in `docker-compose.local.yml`:

- `FHIR_BASE_URL`
- `INTERNAL_FHIR_URL`

Keep these rules:

- `FHIR_BASE_URL` must be the URL your SMART client uses as `aud`.
- `INTERNAL_FHIR_URL` must be reachable from inside the Keycloak container.

For example, if your FHIR server runs directly on the host instead of compose, use `host.docker.internal` for the internal URL on macOS:
```yaml
FHIR_BASE_URL: https://localhost:9443/fhir
INTERNAL_FHIR_URL: http://host.docker.internal:9080/fhir
```