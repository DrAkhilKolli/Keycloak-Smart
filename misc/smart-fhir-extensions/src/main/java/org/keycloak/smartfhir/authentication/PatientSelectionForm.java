/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.smartfhir.authentication;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientScopeModel;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.TokenManager;
import org.keycloak.protocol.oidc.endpoints.AuthorizationEndpoint;
import org.keycloak.representations.AccessToken;
import org.keycloak.services.Urls;
import org.keycloak.services.util.DefaultClientSessionContext;
import org.keycloak.sessions.AuthenticationSessionModel;

/**
 * Presents a patient picker when launch/patient is requested and the current user maps to multiple FHIR patients.
 */
public class PatientSelectionForm implements Authenticator {

    private static final Logger LOG = Logger.getLogger(PatientSelectionForm.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    static final String PATIENT_SESSION_NOTE = "patient_id";

    private static final String SMART_AUDIENCE_NOTE =
            AuthorizationEndpoint.LOGIN_SESSION_NOTE_ADDITIONAL_REQ_PARAMS_PREFIX + "aud";
    private static final String SMART_SCOPE_PATIENT_READ = "patient/Patient.read";
    private static final String SMART_SCOPE_LAUNCH_PATIENT = "launch/patient";
    private static final String ATTRIBUTE_RESOURCE_ID = "resourceId";
    private static final String PATIENT_TEMPLATE = "patient-select-form.ftl";

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        ClientModel client = authSession.getClient();
        String requestedScopes = authSession.getClientNote(OIDCLoginProtocol.SCOPE_PARAM);

        boolean launchPatientRequested = TokenManager
                .getRequestedClientScopes(context.getSession(), requestedScopes, client, context.getUser())
                .map(ClientScopeModel::getName)
                .anyMatch(SMART_SCOPE_LAUNCH_PATIENT::equals);

        if (!launchPatientRequested) {
            context.success();
            return;
        }

        if (context.getUser() == null) {
            fail(context, "Expected an authenticated user before selecting a patient.");
            return;
        }

        List<String> resourceIds = getResourceIdsForUser(context.getUser());
        if (resourceIds.isEmpty()) {
            fail(context, "Expected the user to have one or more resourceId attributes, but found none.");
            return;
        }

        if (resourceIds.size() == 1) {
            succeed(context, resourceIds.get(0));
            return;
        }

        AuthenticatorConfigModel config = context.getAuthenticatorConfig();
        if (config == null || config.getConfig() == null
                || !config.getConfig().containsKey(PatientSelectionFormFactory.INTERNAL_FHIR_URL_PROP_NAME)) {
            fail(context, "The patient selection authenticator must be configured with a valid FHIR base URL.");
            return;
        }

        String internalFhirUrl = config.getConfig().get(PatientSelectionFormFactory.INTERNAL_FHIR_URL_PROP_NAME);

        List<PatientOption> patients;
        try {
            String accessToken = buildInternalAccessToken(context, resourceIds);
            patients = fetchPatients(internalFhirUrl, accessToken, resourceIds);
        } catch (IOException exception) {
            fail(context, "Unable to retrieve Patient resources from the configured FHIR server.");
            return;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(context, "Interrupted while retrieving Patient resources from the configured FHIR server.");
            return;
        }

        if (patients.isEmpty()) {
            succeed(context, resourceIds.get(0));
            return;
        }

        if (patients.size() == 1) {
            succeed(context, patients.get(0).getId());
            return;
        }

        Response response = context.form()
                .setAttribute("patients", patients)
                .createForm(PATIENT_TEMPLATE);
        context.challenge(response);
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
        String patient = formData.getFirst("patient");

        LOG.debugf("The user selected patient '%s'", patient);

        if (patient == null || patient.isBlank() || !getResourceIdsForUser(context.getUser()).contains(patient.trim())) {
            LOG.warnf("The patient selection '%s' is not valid for the authenticated user.", patient);
            authenticate(context);
            return;
        }

        succeed(context, patient.trim());
    }

    private List<String> getResourceIdsForUser(UserModel user) {
        return user.getAttributeStream(ATTRIBUTE_RESOURCE_ID)
                .flatMap(attribute -> Stream.of(attribute.split(" ")))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    private String buildInternalAccessToken(AuthenticationFlowContext context, List<String> resourceIds) {
        KeycloakSession session = context.getSession();
        RealmModel realm = context.getRealm();
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        UserModel user = context.getUser();
        ClientModel client = authSession.getClient();

        UserSessionModel userSession = session.sessions().createUserSession(
                null,
                realm,
                user,
                user.getUsername(),
                context.getConnection().getRemoteAddr(),
                null,
                false,
                null,
                null,
                UserSessionModel.SessionPersistenceState.TRANSIENT);

        AuthenticatedClientSessionModel clientSession = session.sessions().createClientSession(realm, client, userSession);
        clientSession.setProtocol(OIDCLoginProtocol.LOGIN_PROTOCOL);
        clientSession.setRedirectUri(authSession.getRedirectUri());
        clientSession.setNote(OIDCLoginProtocol.ISSUER,
                Urls.realmIssuer(session.getContext().getUri().getBaseUri(), realm.getName()));

        ClientSessionContext clientSessionContext = DefaultClientSessionContext.fromClientSessionAndScopeParameter(
                clientSession,
                SMART_SCOPE_PATIENT_READ,
                session);

        String requestedAudience = authSession.getClientNote(SMART_AUDIENCE_NOTE);
        if (requestedAudience == null || requestedAudience.isBlank()) {
            requestedAudience = context.getAuthenticatorConfig().getConfig()
                    .get(PatientSelectionFormFactory.INTERNAL_FHIR_URL_PROP_NAME);
            LOG.infof("SMART authorization request is missing 'aud'; using configured internal FHIR URL '%s'.",
                    requestedAudience);
        }

        AccessToken accessToken = new TokenManager().createClientAccessToken(
                session,
                realm,
                client,
                user,
                userSession,
                clientSessionContext,
                false);

        accessToken.setScope(SMART_SCOPE_PATIENT_READ);
        accessToken.audience(requestedAudience);
        accessToken.setOtherClaims(PATIENT_SESSION_NOTE, resourceIds);
        return session.tokens().encode(accessToken);
    }

    private List<PatientOption> fetchPatients(String baseUrl, String accessToken, List<String> resourceIds)
            throws IOException, InterruptedException {
        List<PatientOption> patients = new ArrayList<>();

        for (String resourceId : resourceIds) {
            URI patientUri = UriBuilder.fromUri(baseUrl)
                    .path("Patient")
                    .path("{id}")
                    .build(resourceId);

            HttpRequest request = HttpRequest.newBuilder(patientUri)
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/fhir+json, application/json")
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warnf("Skipping Patient/%s because the FHIR server returned HTTP %d.", resourceId, response.statusCode());
                continue;
            }

            JsonNode patientJson = JSON.readTree(response.body());
            patients.add(new PatientOption(
                    patientJson.path("id").asText(resourceId),
                    extractPatientName(patientJson, resourceId),
                    extractBirthDate(patientJson)));
        }

        return patients;
    }

    private String extractPatientName(JsonNode patientJson, String resourceId) {
        JsonNode names = patientJson.path("name");
        if (!names.isArray() || names.isEmpty()) {
            LOG.warnf("Patient[id=%s] has no name; using placeholder.", resourceId);
            return "Missing Name";
        }

        JsonNode primaryName = names.get(0);
        String text = primaryName.path("text").asText(null);
        if (text != null && !text.isBlank()) {
            return text;
        }

        String given = Stream.of(primaryName.path("given"))
                .filter(JsonNode::isArray)
                .flatMap(node -> {
                    List<String> values = new ArrayList<>();
                    node.forEach(value -> values.add(value.asText()));
                    return values.stream();
                })
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .collect(Collectors.joining(" "));

        String family = primaryName.path("family").asText("");
        String combined = Stream.of(given, family)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.joining(" "));

        return combined.isEmpty() ? "Missing Name" : combined;
    }

    private String extractBirthDate(JsonNode patientJson) {
        String birthDate = patientJson.path("birthDate").asText(null);
        return birthDate == null || birthDate.isBlank() ? "missing" : birthDate;
    }

    private void fail(AuthenticationFlowContext context, String description) {
        Response response = Response.status(Response.Status.FOUND)
                .location(UriBuilder.fromUri(context.getAuthenticationSession().getRedirectUri())
                        .queryParam("error", "server_error")
                        .queryParam("error_description", description)
                        .build())
                .build();
        context.failure(AuthenticationFlowError.INTERNAL_ERROR, response);
    }

    private void succeed(AuthenticationFlowContext context, String patientId) {
        context.getAuthenticationSession().setUserSessionNote(PATIENT_SESSION_NOTE, patientId);
        context.success();
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    }

    @Override
    public void close() {
    }
}