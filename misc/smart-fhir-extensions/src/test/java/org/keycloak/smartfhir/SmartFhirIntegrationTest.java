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

package org.keycloak.smartfhir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.keycloak.authentication.authenticators.browser.UsernamePasswordFormFactory;
import org.keycloak.protocol.ProtocolMapperUtils;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.representations.AccessToken;
import org.keycloak.representations.idm.AuthenticationExecutionInfoRepresentation;
import org.keycloak.representations.idm.AuthenticatorConfigRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.keycloak.smartfhir.authentication.AudienceValidatorFactory;
import org.keycloak.smartfhir.authentication.PatientSelectionFormFactory;
import org.keycloak.smartfhir.mappers.FhirUserClaimMapper;
import org.keycloak.smartfhir.mappers.PatientPrefixUserAttributeMapper;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.InjectUser;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.injection.LifeCycle;
import org.keycloak.testframework.oauth.OAuthClient;
import org.keycloak.testframework.oauth.annotations.InjectOAuthClient;
import org.keycloak.testframework.realm.AuthenticationExecutionExportBuilder;
import org.keycloak.testframework.realm.AuthenticationFlowBuilder;
import org.keycloak.testframework.realm.ClientBuilder;
import org.keycloak.testframework.realm.ClientConfig;
import org.keycloak.testframework.realm.ClientScopeBuilder;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.realm.ManagedUser;
import org.keycloak.testframework.realm.RealmBuilder;
import org.keycloak.testframework.realm.RealmConfig;
import org.keycloak.testframework.realm.UserBuilder;
import org.keycloak.testframework.realm.UserConfig;
import org.keycloak.testframework.ui.annotations.InjectPage;
import org.keycloak.testframework.ui.annotations.InjectWebDriver;
import org.keycloak.testframework.ui.page.LoginPage;
import org.keycloak.testframework.ui.webdriver.ManagedWebDriver;
import org.keycloak.testframework.util.ApiUtil;
import org.keycloak.testsuite.util.oauth.AccessTokenResponse;
import org.keycloak.testsuite.util.oauth.AuthorizationEndpointResponse;
import org.keycloak.util.JsonSerialization;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;

@KeycloakIntegrationTest(config = SmartFhirIntegrationTest.ServerConfig.class)
class SmartFhirIntegrationTest {

    private static final String CLIENT_ID = "smart-app";
    private static final String FLOW_ALIAS = "smart-browser";
    private static final String FHIR_AUDIENCE = "https://fhir.example.test/baseR4";
    private static final String MULTI_PATIENT_1 = "pat-alpha";
    private static final String MULTI_PATIENT_2 = "pat-bravo";
    private static final String SINGLE_PATIENT = "pat-single";
    private static final String PRACTITIONER_ID = "prac-001";
    private static final String PATIENT_SESSION_NOTE = "patient_id";
    private static final String AUDIENCES_PROP_NAME = "audiences";
    private static final String INTERNAL_FHIR_URL_PROP_NAME = "internalFhirUrl";

    @InjectRealm(config = SmartRealmConfig.class)
    ManagedRealm managedRealm;

    @InjectOAuthClient(config = SmartClientConfig.class, lifecycle = LifeCycle.METHOD)
    OAuthClient oauth;

    @InjectWebDriver(lifecycle = LifeCycle.METHOD)
    ManagedWebDriver webDriver;

    @InjectPage
    LoginPage loginPage;

    @InjectUser(ref = "multiPatientUser", config = MultiPatientUserConfig.class)
    ManagedUser multiPatientUser;

    @InjectUser(ref = "singlePatientUser", config = SinglePatientUserConfig.class)
    ManagedUser singlePatientUser;

    @InjectUser(ref = "practitionerUser", config = PractitionerUserConfig.class)
    ManagedUser practitionerUser;

    @InjectUser(ref = "noResourceIdUser", config = NoResourceIdUserConfig.class)
    ManagedUser noResourceIdUser;

    private HttpServer fhirServer;
    private List<FhirRequest> fhirRequests;

    @BeforeEach
    void setUp() throws IOException {
        webDriver.cookies().deleteAll();
        fhirRequests = new ArrayList<>();
        fhirServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        fhirServer.createContext("/fhir/Patient", this::handlePatientRequest);
        fhirServer.start();

        enableUnmanagedUserAttributes();
        ensureDefaultClientScopesAttached("launch/patient", "patient/Patient.read", "fhirUser");
        ensureUserResourceIds(multiPatientUser.getUsername(), MULTI_PATIENT_1, MULTI_PATIENT_2);
        ensureUserResourceIds(singlePatientUser.getUsername(), SINGLE_PATIENT);
        ensureUserFhirAttributes(practitionerUser.getUsername(), PRACTITIONER_ID, "Practitioner");

        configureExecution(
                AudienceValidatorFactory.PROVIDER_ID,
                "smart-audience-config",
                Map.of(AUDIENCES_PROP_NAME, FHIR_AUDIENCE));
        configureExecution(
                PatientSelectionFormFactory.PROVIDER_ID,
                "smart-patient-config",
                Map.of(INTERNAL_FHIR_URL_PROP_NAME, getInternalFhirUrl()));
    }

    @AfterEach
    void tearDown() {
        if (fhirServer != null) {
            fhirServer.stop(0);
            fhirServer = null;
        }
    }

    // -------------------------------------------------------------------------
    // Audience validation
    // -------------------------------------------------------------------------

    @Test
    void rejectsMissingSmartAud() {
        // No aud parameter at all — AudienceValidator must reject before showing the login form
        AuthorizationEndpointResponse response = oauth.loginForm()
                .scope("openid")
                .doLoginWithCookie();

        Assertions.assertTrue(response.isRedirected());
        Assertions.assertEquals("invalid_request", response.getError());
        Assertions.assertTrue(response.getErrorDescription().contains("'aud'"),
                "error description must mention the missing 'aud' parameter");
    }

    @Test
    void rejectsInvalidSmartAudienceBeforeLogin() {
        AuthorizationEndpointResponse response = oauth.loginForm()
                .scope("openid")
                .param("aud", "https://malicious.example.test/fhir")
                .doLoginWithCookie();

        Assertions.assertTrue(response.isRedirected());
        Assertions.assertEquals("invalid_request", response.getError());
        Assertions.assertTrue(response.getErrorDescription().contains("Requested audience"));
    }

    // -------------------------------------------------------------------------
    // Patient context (launch/patient scope)
    // -------------------------------------------------------------------------

    @Test
    void singlePatientAutoSelectsWithoutPickerForm() {
        // One resourceId → PatientSelectionForm short-circuits; no FHIR HTTP call, no picker UI
        AuthorizationEndpointResponse authzResponse = oauth.loginForm()
                .scope("openid", "launch/patient", "patient/Patient.read")
                .param("aud", FHIR_AUDIENCE)
                .doLogin(singlePatientUser.getUsername(), singlePatientUser.getPassword());

        Assertions.assertTrue(authzResponse.isRedirected());
        AccessTokenResponse tokenResponse = oauth.doAccessTokenRequest(authzResponse.getCode());
        Assertions.assertTrue(tokenResponse.isSuccess());
        Assertions.assertEquals(SINGLE_PATIENT, tokenResponse.getOtherClaims().get("patient"),
                "single-patient user must auto-select without showing the picker");
        Assertions.assertTrue(fhirRequests.isEmpty(),
                "single-patient auto-selection must not call the FHIR server");
    }

    @Test
    void skipPatientStepWhenLaunchPatientScopeNotRequested() {
        // launch/patient not in scope → PatientSelectionForm succeeds immediately, no patient claim
        AuthorizationEndpointResponse authzResponse = oauth.loginForm()
                .scope("openid")
                .param("aud", FHIR_AUDIENCE)
                .doLogin(singlePatientUser.getUsername(), singlePatientUser.getPassword());

        Assertions.assertTrue(authzResponse.isRedirected());
        AccessTokenResponse tokenResponse = oauth.doAccessTokenRequest(authzResponse.getCode());
        Assertions.assertTrue(tokenResponse.isSuccess());
        Assertions.assertNull(tokenResponse.getOtherClaims().get("patient"),
                "patient claim must not be present when launch/patient scope was not requested");
        Assertions.assertTrue(fhirRequests.isEmpty());
    }

    @Test
    void launchPatientFlowFetchesPatientsAndAddsLaunchContextToTokenResponse() throws Exception {
        oauth.loginForm()
                .scope("openid", "launch/patient", "patient/Patient.read")
                .param("aud", FHIR_AUDIENCE)
                .open();

        loginPage.fillLogin(multiPatientUser.getUsername(), multiPatientUser.getPassword());
        loginPage.submit();

        Assertions.assertNotNull(webDriver.findElement(By.id("patient-selection")));
        webDriver.findElement(By.id(MULTI_PATIENT_2)).click();
        webDriver.findElement(By.id("submit")).click();

        AuthorizationEndpointResponse authzResponse = oauth.parseLoginResponse();
        Assertions.assertTrue(authzResponse.isRedirected());
        Assertions.assertNotNull(authzResponse.getCode());

        AccessTokenResponse tokenResponse = oauth.doAccessTokenRequest(authzResponse.getCode());
        Assertions.assertTrue(tokenResponse.isSuccess());
        Assertions.assertEquals(MULTI_PATIENT_2, tokenResponse.getOtherClaims().get("patient"));

        Assertions.assertEquals(2, fhirRequests.size());
        Assertions.assertTrue(fhirRequests.stream().anyMatch(request -> request.path().endsWith("/" + MULTI_PATIENT_1)));
        Assertions.assertTrue(fhirRequests.stream().anyMatch(request -> request.path().endsWith("/" + MULTI_PATIENT_2)));

        JsonNode tokenPayload = decodeJwtPayload(fhirRequests.get(0).bearerToken());
        Assertions.assertEquals("patient/Patient.read", tokenPayload.path("scope").asText());
        assertAudience(tokenPayload.path("aud"), FHIR_AUDIENCE);
    }

    // -------------------------------------------------------------------------
    // fhirUser claim (identity)
    // -------------------------------------------------------------------------

    @Test
    void fhirUserClaimAbsentWhenUserHasNoResourceId() {
        AuthorizationEndpointResponse authzResponse = oauth.loginForm()
                .scope("openid", "fhirUser")
                .param("aud", FHIR_AUDIENCE)
                .doLogin(noResourceIdUser.getUsername(), noResourceIdUser.getPassword());

        Assertions.assertTrue(authzResponse.isRedirected());
        AccessTokenResponse tokenResponse = oauth.doAccessTokenRequest(authzResponse.getCode());
        Assertions.assertTrue(tokenResponse.isSuccess());

        AccessToken accessToken = oauth.verifyToken(tokenResponse.getAccessToken(), AccessToken.class);
        Assertions.assertNull(accessToken.getOtherClaims().get("fhirUser"),
                "fhirUser claim must be absent when user has no resourceId");
        Assertions.assertTrue(fhirRequests.isEmpty());
    }

    @Test
    void ehrUserFhirUserClaimHasPractitionerPrefix() {
        // EHR user with fhirResourceType=Practitioner → fhirUser claim is Practitioner/{id}
        AuthorizationEndpointResponse authzResponse = oauth.loginForm()
                .scope("openid", "fhirUser")
                .param("aud", FHIR_AUDIENCE)
                .doLogin(practitionerUser.getUsername(), practitionerUser.getPassword());

        Assertions.assertTrue(authzResponse.isRedirected());
        AccessTokenResponse tokenResponse = oauth.doAccessTokenRequest(authzResponse.getCode());
        Assertions.assertTrue(tokenResponse.isSuccess());

        AccessToken accessToken = oauth.verifyToken(tokenResponse.getAccessToken(), AccessToken.class);
        Assertions.assertEquals("Practitioner/" + PRACTITIONER_ID,
                accessToken.getOtherClaims().get("fhirUser"),
                "EHR practitioner user must have Practitioner/ prefixed fhirUser claim");
        Assertions.assertTrue(fhirRequests.isEmpty());
    }

    @Test
    void mapsSinglePatientAttributeToFhirUserClaim() {
        AuthorizationEndpointResponse authzResponse = oauth.loginForm()
                .scope("openid", "fhirUser")
                .param("aud", FHIR_AUDIENCE)
                .doLogin(singlePatientUser.getUsername(), singlePatientUser.getPassword());

        Assertions.assertTrue(authzResponse.isRedirected());
        AccessTokenResponse tokenResponse = oauth.doAccessTokenRequest(authzResponse.getCode());
        Assertions.assertTrue(tokenResponse.isSuccess());

        AccessToken accessToken = oauth.verifyToken(tokenResponse.getAccessToken(), AccessToken.class);
        Assertions.assertEquals("Patient/" + SINGLE_PATIENT, accessToken.getOtherClaims().get("fhirUser"));
        Assertions.assertTrue(fhirRequests.isEmpty());
    }

    private void configureExecution(String providerId, String alias, Map<String, String> config) {
        AuthenticationExecutionInfoRepresentation execution = managedRealm.admin().flows().getExecutions(FLOW_ALIAS).stream()
                .filter(candidate -> providerId.equals(candidate.getProviderId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing execution for provider " + providerId));

        if (execution.getAuthenticationConfig() == null) {
            AuthenticatorConfigRepresentation authenticatorConfig = new AuthenticatorConfigRepresentation();
            authenticatorConfig.setAlias(alias);
            authenticatorConfig.setConfig(new HashMap<>(config));

            try (var response = managedRealm.admin().flows().newExecutionConfig(execution.getId(), authenticatorConfig)) {
                Assertions.assertEquals(201, response.getStatus());
            }
            return;
        }

        AuthenticatorConfigRepresentation authenticatorConfig = managedRealm.admin().flows()
                .getAuthenticatorConfig(execution.getAuthenticationConfig());
        authenticatorConfig.setAlias(alias);
        authenticatorConfig.setConfig(new HashMap<>(config));
        managedRealm.admin().flows().updateAuthenticatorConfig(authenticatorConfig.getId(), authenticatorConfig);
    }

    private void ensureDefaultClientScopesAttached(String... scopeNames) {
        var client = managedRealm.admin().clients().findByClientId(CLIENT_ID).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing client " + CLIENT_ID));
        var clientResource = managedRealm.admin().clients().get(client.getId());
        List<ClientScopeRepresentation> defaultScopes = new ArrayList<>(clientResource.getDefaultClientScopes());
        List<ClientScopeRepresentation> availableScopes = managedRealm.admin().clientScopes().findAll();

        for (String scopeName : scopeNames) {
            boolean alreadyAttached = defaultScopes.stream().anyMatch(scope -> scopeName.equals(scope.getName()));
            if (alreadyAttached) {
                continue;
            }

            ClientScopeRepresentation scope = availableScopes.stream()
                    .filter(candidate -> scopeName.equals(candidate.getName()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Missing client scope " + scopeName));
            clientResource.addDefaultClientScope(scope.getId());
            defaultScopes.add(scope);
        }
    }

    private void ensureUserFhirAttributes(String username, String resourceId, String resourceType) {
        var user = managedRealm.admin().users().searchByUsername(username, true).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing user " + username));
        var userResource = managedRealm.admin().users().get(user.getId());
        var userRepresentation = userResource.toRepresentation();
        Map<String, List<String>> attributes = userRepresentation.getAttributes() == null
                ? new HashMap<>()
                : new HashMap<>(userRepresentation.getAttributes());
        attributes.put("resourceId", List.of(resourceId));
        attributes.put("fhirResourceType", List.of(resourceType));
        userRepresentation.setAttributes(attributes);
        userResource.update(userRepresentation);
    }

    private void ensureUserResourceIds(String username, String... resourceIds) {
        var user = managedRealm.admin().users().searchByUsername(username, true).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing user " + username));
        var userResource = managedRealm.admin().users().get(user.getId());
        var userRepresentation = userResource.toRepresentation();

        Map<String, List<String>> attributes = userRepresentation.getAttributes() == null
                ? new HashMap<>()
                : new HashMap<>(userRepresentation.getAttributes());
        attributes.put("resourceId", List.of(resourceIds));
        userRepresentation.setAttributes(attributes);
        userResource.update(userRepresentation);
    }

    private void enableUnmanagedUserAttributes() {
        UPConfig upConfig = managedRealm.admin().users().userProfile().getConfiguration();
        if (upConfig.getUnmanagedAttributePolicy() == UPConfig.UnmanagedAttributePolicy.ENABLED) {
            return;
        }

        upConfig.setUnmanagedAttributePolicy(UPConfig.UnmanagedAttributePolicy.ENABLED);
        managedRealm.admin().users().userProfile().update(upConfig);
    }

    private void handlePatientRequest(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String patientId = path.substring(path.lastIndexOf('/') + 1);
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        fhirRequests.add(new FhirRequest(path, extractBearerToken(authorization)));

        String responseBody = switch (patientId) {
            case MULTI_PATIENT_1 -> patientJson(MULTI_PATIENT_1, "Alice Alpha", "1980-01-01");
            case MULTI_PATIENT_2 -> patientJson(MULTI_PATIENT_2, "Bob Bravo", "1985-05-05");
            default -> null;
        };

        if (responseBody == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/fhir+json");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(payload);
        } finally {
            exchange.close();
        }
    }

    private String getInternalFhirUrl() {
        return "http://127.0.0.1:" + fhirServer.getAddress().getPort() + "/fhir";
    }

    private String patientJson(String id, String displayName, String birthDate) {
        return "{" +
                "\"resourceType\":\"Patient\"," +
                "\"id\":\"" + id + "\"," +
                "\"name\":[{\"text\":\"" + displayName + "\"}]," +
                "\"birthDate\":\"" + birthDate + "\"" +
                "}";
    }

    private String extractBearerToken(String authorization) {
        Assertions.assertNotNull(authorization);
        Assertions.assertTrue(authorization.startsWith("Bearer "));
        return authorization.substring("Bearer ".length());
    }

    private JsonNode decodeJwtPayload(String token) throws IOException {
        String[] parts = token.split("\\.");
        Assertions.assertEquals(3, parts.length);
        byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
        return JsonSerialization.mapper.readTree(payload);
    }

    private void assertAudience(JsonNode audienceNode, String expectedAudience) {
        if (audienceNode.isArray()) {
            List<String> audiences = new ArrayList<>();
            audienceNode.forEach(node -> audiences.add(node.asText()));
            Assertions.assertTrue(audiences.contains(expectedAudience));
            return;
        }

        Assertions.assertEquals(expectedAudience, audienceNode.asText());
    }

    public static class ServerConfig implements org.keycloak.testframework.server.KeycloakServerConfig {

        @Override
        public org.keycloak.testframework.server.KeycloakServerConfigBuilder configure(
                org.keycloak.testframework.server.KeycloakServerConfigBuilder config) {
            return config.dependencyCurrentProject();
        }
    }

    public static class SmartRealmConfig implements RealmConfig {

        @Override
        public RealmBuilder configure(RealmBuilder realm) {
            return realm.name("smart-fhir")
                    .browserFlow(FLOW_ALIAS)
                    .clientScopes(launchPatientScope(), patientReadScope(), fhirUserScope())
                    .authenticationFlows(AuthenticationFlowBuilder.create(FLOW_ALIAS,
                                    "SMART App Launch browser flow",
                                    "basic-flow",
                                    true,
                                    false)
                            .authenticationExecutions(
                                    AuthenticationExecutionExportBuilder.authenticator(
                                            AudienceValidatorFactory.PROVIDER_ID,
                                            "REQUIRED",
                                            10,
                                            false),
                                    AuthenticationExecutionExportBuilder.authenticator(
                                            UsernamePasswordFormFactory.PROVIDER_ID,
                                            "REQUIRED",
                                            20,
                                            false),
                                    AuthenticationExecutionExportBuilder.authenticator(
                                            PatientSelectionFormFactory.PROVIDER_ID,
                                            "REQUIRED",
                                            30,
                                            false)));
        }

        private ClientScopeRepresentation launchPatientScope() {
            ClientScopeRepresentation scope = ClientScopeBuilder.create()
                    .name("launch/patient")
                    .protocol(OIDCLoginProtocol.LOGIN_PROTOCOL)
                    .description("SMART launch patient context")
                    .build();
            scope.setProtocolMappers(List.of(createLaunchPatientMapper()));
            return scope;
        }

        private ClientScopeRepresentation patientReadScope() {
            return ClientScopeBuilder.create()
                    .name("patient/Patient.read")
                    .protocol(OIDCLoginProtocol.LOGIN_PROTOCOL)
                    .description("SMART patient read scope")
                    .build();
        }

        private ClientScopeRepresentation fhirUserScope() {
            ClientScopeRepresentation scope = ClientScopeBuilder.create()
                    .name("fhirUser")
                    .protocol(OIDCLoginProtocol.LOGIN_PROTOCOL)
                    .description("SMART fhirUser scope")
                    .build();
            scope.setProtocolMappers(List.of(createFhirUserMapper()));
            return scope;
        }

        private ProtocolMapperRepresentation createLaunchPatientMapper() {
            ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
            mapper.setName("patient-launch-context");
            mapper.setProtocol(OIDCLoginProtocol.LOGIN_PROTOCOL);
            mapper.setProtocolMapper("oidc-usersessionmodel-note-mapper");
            mapper.setConfig(Map.of(
                    ProtocolMapperUtils.USER_SESSION_NOTE, PATIENT_SESSION_NOTE,
                    OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME, "patient",
                    OIDCAttributeMapperHelper.JSON_TYPE, "String",
                    OIDCAttributeMapperHelper.INCLUDE_IN_ACCESS_TOKEN_RESPONSE, "true"));
            return mapper;
        }

        private ProtocolMapperRepresentation createFhirUserMapper() {
            ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
            mapper.setName("fhir-user");
            mapper.setProtocol(OIDCLoginProtocol.LOGIN_PROTOCOL);
            mapper.setProtocolMapper(FhirUserClaimMapper.PROVIDER_ID);
            mapper.setConfig(Map.of(
                    FhirUserClaimMapper.RESOURCE_ID_ATTRIBUTE, "resourceId",
                    FhirUserClaimMapper.RESOURCE_TYPE_ATTRIBUTE, "fhirResourceType",
                    FhirUserClaimMapper.DEFAULT_RESOURCE_TYPE, "Patient",
                    OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME, "fhirUser",
                    OIDCAttributeMapperHelper.JSON_TYPE, "String",
                    OIDCAttributeMapperHelper.INCLUDE_IN_ACCESS_TOKEN, "true"));
            return mapper;
        }
    }

    public static class SmartClientConfig implements ClientConfig {

        @Override
        public ClientBuilder configure(ClientBuilder client) {
            return client.clientId(CLIENT_ID)
                    .secret("smart-secret")
                    .serviceAccountsEnabled(true)
                    .directAccessGrantsEnabled(true)
                    .optionalClientScopes("launch/patient", "patient/Patient.read", "fhirUser");
        }
    }

    public static class MultiPatientUserConfig implements UserConfig {

        @Override
        public UserBuilder configure(UserBuilder user) {
            return user.username("multi-patient-user")
                    .email("multi@example.test")
                    .password("password")
                    .firstName("Multi")
                    .lastName("Patient")
                    .attribute("resourceId", MULTI_PATIENT_1, MULTI_PATIENT_2);
        }
    }

    public static class SinglePatientUserConfig implements UserConfig {

        @Override
        public UserBuilder configure(UserBuilder user) {
            return user.username("single-patient-user")
                    .email("single@example.test")
                    .password("password")
                    .firstName("Single")
                    .lastName("Patient")
                    .attribute("resourceId", SINGLE_PATIENT);
        }
    }

    public static class PractitionerUserConfig implements UserConfig {

        @Override
        public UserBuilder configure(UserBuilder user) {
            return user.username("practitioner-user")
                    .email("practitioner@example.test")
                    .password("password")
                    .firstName("Alice")
                    .lastName("Practitioner")
                    // fhirResourceType and resourceId are ensured in setUp after enabling unmanaged attrs
                    .attribute("resourceId", PRACTITIONER_ID)
                    .attribute("fhirResourceType", "Practitioner");
        }
    }

    public static class NoResourceIdUserConfig implements UserConfig {

        @Override
        public UserBuilder configure(UserBuilder user) {
            return user.username("no-resource-user")
                    .email("noresource@example.test")
                    .password("password")
                    .firstName("No")
                    .lastName("Resource");
        }
    }

    private record FhirRequest(String path, String bearerToken) {
    }
}
