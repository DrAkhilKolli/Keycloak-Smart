# syntax=docker/dockerfile:1

FROM registry.access.redhat.com/ubi9 AS ubi-micro-build

RUN dnf install -y tar gzip

COPY quarkus/dist/target/keycloak-*.tar.gz /tmp/keycloak/
RUN cd /tmp/keycloak && \
    tar -xvf keycloak-*.tar.gz && \
    rm keycloak-*.tar.gz && \
    mv keycloak-* /opt/keycloak && \
    mkdir -p /opt/keycloak/data && \
    chmod -R g+rwX /opt/keycloak

COPY quarkus/container/ubi-null.sh /tmp/
RUN bash /tmp/ubi-null.sh java-21-openjdk-headless glibc-langpack-en findutils gettext

FROM registry.access.redhat.com/ubi9-micro
ENV LANG=en_US.UTF-8
ENV KC_RUN_IN_CONTAINER=true

COPY --from=ubi-micro-build /tmp/null/rootfs/ /
COPY --from=ubi-micro-build --chown=1000:0 /opt/keycloak /opt/keycloak

RUN echo "keycloak:x:0:root" >> /etc/group && \
    echo "keycloak:x:1000:0:keycloak user:/opt/keycloak:/sbin/nologin" >> /etc/passwd

COPY --chown=1000:0 misc/smart-fhir-extensions/target/keycloak-smart-fhir-extensions-*.jar /opt/keycloak/providers/
COPY --chown=1000:0 misc/smart-fhir-extensions/target/keycloak-theme-common-resources/theme/keycloak/common/resources/vendor /opt/keycloak/themes/keycloak/common/resources/vendor
COPY --chown=1000:0 misc/smart-fhir-extensions/examples/import/smart-fhir-realm-template.json /opt/keycloak/data/import-templates/smart-fhir-realm-template.json
COPY --chown=1000:0 misc/smart-fhir-extensions/start.sh /opt/keycloak/bin/start-smart-keycloak.sh

USER 1000

RUN chmod +x /opt/keycloak/bin/start-smart-keycloak.sh && \
    /opt/keycloak/bin/kc.sh build --db=postgres

ENTRYPOINT ["/opt/keycloak/bin/start-smart-keycloak.sh"]