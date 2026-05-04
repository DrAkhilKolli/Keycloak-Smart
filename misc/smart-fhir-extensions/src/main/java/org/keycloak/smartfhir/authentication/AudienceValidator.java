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

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.protocol.oidc.endpoints.AuthorizationEndpoint;

/**
 * Validates the SMART App Launch {@code aud} request parameter against a configured allow-list.
 */
public class AudienceValidator implements Authenticator {

    private static final Logger LOG = Logger.getLogger(AudienceValidator.class);
    private static final String SMART_AUDIENCE_PARAM = "aud";
    private static final String SMART_AUDIENCE_NOTE =
            AuthorizationEndpoint.LOGIN_SESSION_NOTE_ADDITIONAL_REQ_PARAMS_PREFIX + SMART_AUDIENCE_PARAM;

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        if (context.getAuthenticatorConfig() == null
                || context.getAuthenticatorConfig().getConfig() == null
                || !context.getAuthenticatorConfig().getConfig().containsKey(AudienceValidatorFactory.AUDIENCES_PROP_NAME)) {
            fail(context, AuthenticationFlowError.INTERNAL_ERROR, "server_error",
                    "The SMART audience validator must be configured with one or more allowed audiences.");
            return;
        }

        String requestedAudience = context.getAuthenticationSession().getClientNote(SMART_AUDIENCE_NOTE);
        if (requestedAudience == null || requestedAudience.isBlank()) {
            fail(context, AuthenticationFlowError.INVALID_CLIENT_SESSION, "invalid_request",
                    "Missing required SMART authorization parameter 'aud'.");
            return;
        }

        List<String> allowedAudiences = parseAudiences(
                context.getAuthenticatorConfig().getConfig().get(AudienceValidatorFactory.AUDIENCES_PROP_NAME));

        LOG.debugf("Requested SMART audience: %s", requestedAudience);
        LOG.debugf("Allowed SMART audiences: %s", allowedAudiences);

        if (allowedAudiences.contains(requestedAudience)) {
            context.success();
            return;
        }

        fail(context, AuthenticationFlowError.INVALID_CLIENT_SESSION, "invalid_request",
                "Requested audience '" + requestedAudience + "' must match one of the configured resource server URLs: "
                        + allowedAudiences);
    }

    private List<String> parseAudiences(String audiences) {
        return Arrays.stream(audiences.split("##|\\r?\\n"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toList());
    }

    private void fail(AuthenticationFlowContext context, AuthenticationFlowError flowError, String error,
            String description) {
        Response response = Response.status(Response.Status.FOUND)
                .location(UriBuilder.fromUri(context.getAuthenticationSession().getRedirectUri())
                        .queryParam("error", error)
                        .queryParam("error_description", description)
                        .build())
                .build();
        context.failure(flowError, response);
    }

    @Override
    public void action(AuthenticationFlowContext context) {
    }

    @Override
    public boolean requiresUser() {
        return false;
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