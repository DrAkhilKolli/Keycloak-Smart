# syntax=docker/dockerfile:1

ARG KEYCLOAK_IMAGE=quay.io/keycloak/keycloak:latest

FROM maven:3.9.11-eclipse-temurin-21 AS builder
WORKDIR /workspace

COPY . .

RUN ./mvnw -f /workspace/pom.xml -pl misc/smart-fhir-extensions -am -DskipTests package

FROM ${KEYCLOAK_IMAGE}

COPY --from=builder /workspace/misc/smart-fhir-extensions/target/keycloak-smart-fhir-extensions-*.jar /opt/keycloak/providers/
# Store the template separately; start.sh will run envsubst and write the resolved file to /data/import/
COPY misc/smart-fhir-extensions/examples/import/smart-fhir-realm-template.json /opt/keycloak/data/import-templates/smart-fhir-realm-template.json
COPY misc/smart-fhir-extensions/start.sh /usr/local/bin/start.sh

USER root
RUN microdnf install -y gettext && \
    microdnf clean all && \
    chmod +x /usr/local/bin/start.sh && \
    chown -R keycloak:keycloak /opt/keycloak/data
USER keycloak

RUN /opt/keycloak/bin/kc.sh build

ENTRYPOINT ["/usr/local/bin/start.sh"]