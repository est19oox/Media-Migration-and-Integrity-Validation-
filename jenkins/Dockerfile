FROM eclipse-temurin:25.0.4_7-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2 AS application-jdk

FROM jenkins/jenkins:2.568.3-lts-jdk21@sha256:c1e4c349365f6d16d88595b2c5f7e8ff39b8ae1d061f62420bac193b4b9616d0

USER root

COPY --from=application-jdk /opt/java/openjdk /opt/jdk-25

ARG SEMGREP_VERSION=1.138.0
ARG CHECKOV_VERSION=3.2.489
ARG TRIVY_VERSION=0.74.0

RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        ca-certificates \
        curl \
        git \
        gnupg \
        jq \
        maven \
        python3 \
        python3-venv \
        unzip \
    && install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc \
    && chmod a+r /etc/apt/keyrings/docker.asc \
    && . /etc/os-release \
    && printf '%s\n' \
        "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $VERSION_CODENAME stable" \
        > /etc/apt/sources.list.d/docker.list \
    && curl -fsSL https://apt.releases.hashicorp.com/gpg -o /etc/apt/keyrings/hashicorp.asc \
    && . /etc/os-release \
    && printf '%s\n' \
        "deb [signed-by=/etc/apt/keyrings/hashicorp.asc] https://apt.releases.hashicorp.com $VERSION_CODENAME main" \
        > /etc/apt/sources.list.d/hashicorp.list \
    && curl -fsSL https://aquasecurity.github.io/trivy-repo/deb/public.key -o /etc/apt/keyrings/trivy.asc \
    && printf '%s\n' \
        "deb [signed-by=/etc/apt/keyrings/trivy.asc] https://aquasecurity.github.io/trivy-repo/deb generic main" \
        > /etc/apt/sources.list.d/trivy.list \
    && apt-get update \
    && apt-get install --yes --no-install-recommends docker-ce-cli terraform "trivy=${TRIVY_VERSION}" \
    && python3 -m venv /opt/ci-tools \
    && /opt/ci-tools/bin/pip install --no-cache-dir \
        "semgrep==${SEMGREP_VERSION}" \
        "checkov==${CHECKOV_VERSION}" \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

ENV PATH="/opt/ci-tools/bin:${PATH}"
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"

COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --latest=false --plugin-file /usr/share/jenkins/ref/plugins.txt

COPY casc.yaml /usr/share/jenkins/ref/casc.yaml

USER jenkins
