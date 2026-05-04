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

package org.keycloak.smartfhir.mappers;

import java.util.ArrayList;
import java.util.List;

import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.protocol.oidc.mappers.OIDCIDTokenMapper;
import org.keycloak.protocol.oidc.mappers.TokenIntrospectionTokenMapper;
import org.keycloak.protocol.oidc.mappers.UserInfoTokenMapper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;

/**
 * Maps a user's {@code resourceId} attribute to the SMART {@code fhirUser} claim, prefixed
 * with the FHIR resource type read from the user's {@code fhirResourceType} attribute.
 *
 * <p>This single mapper handles both patient-facing and EHR-user contexts (Practitioner,
 * RelatedPerson, etc.) without per-user mapper reconfiguration:
 * <ul>
 *   <li>A user with {@code fhirResourceType=Practitioner} gets {@code fhirUser=Practitioner/{id}}</li>
 *   <li>A user with no {@code fhirResourceType} attribute falls back to the configured default
 *       (typically {@code Patient}), preserving backward compatibility.</li>
 * </ul>
 */
public class FhirUserClaimMapper extends AbstractOIDCProtocolMapper implements OIDCAccessTokenMapper,
        OIDCIDTokenMapper, UserInfoTokenMapper, TokenIntrospectionTokenMapper {

    public static final String PROVIDER_ID = "oidc-fhir-user-claim-mapper";

    /** User attribute that holds the FHIR resource id (e.g. {@code pat-001}). */
    public static final String RESOURCE_ID_ATTRIBUTE = "resourceIdAttribute";

    /** User attribute that holds the FHIR resource type (e.g. {@code Practitioner}). */
    public static final String RESOURCE_TYPE_ATTRIBUTE = "resourceTypeAttribute";

    /** Fallback FHIR resource type when the user has no resource-type attribute. */
    public static final String DEFAULT_RESOURCE_TYPE = "defaultResourceType";

    private static final String FALLBACK_RESOURCE_TYPE = "Patient";

    private static final List<ProviderConfigProperty> CONFIG_PROPERTIES = new ArrayList<>();

    static {
        ProviderConfigProperty property = new ProviderConfigProperty();
        property.setName(RESOURCE_ID_ATTRIBUTE);
        property.setLabel("Resource ID attribute");
        property.setHelpText("User attribute that holds the FHIR resource id (e.g. resourceId).");
        property.setType(ProviderConfigProperty.USER_PROFILE_ATTRIBUTE_LIST_TYPE);
        property.setDefaultValue("resourceId");
        CONFIG_PROPERTIES.add(property);

        property = new ProviderConfigProperty();
        property.setName(RESOURCE_TYPE_ATTRIBUTE);
        property.setLabel("Resource type attribute");
        property.setHelpText(
                "User attribute that carries the FHIR resource type (e.g. fhirResourceType). "
                        + "When present it overrides the default resource type below.");
        property.setType(ProviderConfigProperty.STRING_TYPE);
        property.setDefaultValue("fhirResourceType");
        CONFIG_PROPERTIES.add(property);

        property = new ProviderConfigProperty();
        property.setName(DEFAULT_RESOURCE_TYPE);
        property.setLabel("Default resource type");
        property.setHelpText(
                "FHIR resource type used when the user does not have a resource-type attribute. "
                        + "Typical values: Patient, Practitioner, RelatedPerson.");
        property.setType(ProviderConfigProperty.STRING_TYPE);
        property.setDefaultValue(FALLBACK_RESOURCE_TYPE);
        CONFIG_PROPERTIES.add(property);

        OIDCAttributeMapperHelper.addAttributeConfig(CONFIG_PROPERTIES, FhirUserClaimMapper.class);
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG_PROPERTIES;
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "FHIR User Claim";
    }

    @Override
    public String getDisplayCategory() {
        return TOKEN_MAPPER_CATEGORY;
    }

    @Override
    public String getHelpText() {
        return "Maps a user's resourceId attribute to the SMART fhirUser claim, prefixed with the "
                + "FHIR resource type read from the user's fhirResourceType attribute (default: Patient). "
                + "Supports Patient, Practitioner, RelatedPerson, and any other FHIR resource type.";
    }

    @Override
    protected void setClaim(IDToken token, ProtocolMapperModel mappingModel, UserSessionModel userSession,
            KeycloakSession keycloakSession, ClientSessionContext clientSessionCtx) {
        UserModel user = userSession.getUser();

        String resourceIdAttr = mappingModel.getConfig().getOrDefault(RESOURCE_ID_ATTRIBUTE, "resourceId");
        String resourceTypeAttr = mappingModel.getConfig().getOrDefault(RESOURCE_TYPE_ATTRIBUTE, "fhirResourceType");
        String defaultType = mappingModel.getConfig().getOrDefault(DEFAULT_RESOURCE_TYPE, FALLBACK_RESOURCE_TYPE);

        String resourceId = user.getFirstAttribute(resourceIdAttr);
        if (resourceId == null || resourceId.isBlank()) {
            return;
        }

        String resourceType = user.getFirstAttribute(resourceTypeAttr);
        if (resourceType == null || resourceType.isBlank()) {
            resourceType = defaultType;
        }

        OIDCAttributeMapperHelper.mapClaim(token, mappingModel, resourceType + "/" + resourceId);
    }
}
