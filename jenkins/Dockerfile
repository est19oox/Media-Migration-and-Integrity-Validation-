FROM eclipse-temurin:25.0.4_7-jdk@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2 AS application-jdk

FROM jenkins/jenkins:2.568.3-lts-jdk21@sha256:c1e4c349365f6d16d88595b2c5f7e8ff39b8ae1d061f62420bac193b4b9616d0

USER root

COPY --from=application-jdk /opt/java/openjdk /opt/jdk-25

ARG SEMGREP_VERSION=1.138.0
ARG CHECKOV_VERSION=3.2.489
ARG TRIVY_VERSION=0.74.0
ARG TRIVY_AMD64_SHA256=cf1e32ec8d4d8823e023096a28cadb14f5b5123ce03f201fb633c5b76aa712dd
ARG TRIVY_ARM64_SHA256=14fae2604eab589fd544224ef56b8b8a44266872c43b259bd36cfde81428acc8

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
    && apt-get update \
    && apt-get install --yes --no-install-recommends docker-ce-cli terraform \
    && case "$(dpkg --print-architecture)" in \
        amd64) TRIVY_RELEASE_ARCH=64bit; TRIVY_SHA256="${TRIVY_AMD64_SHA256}" ;; \
        arm64) TRIVY_RELEASE_ARCH=ARM64; TRIVY_SHA256="${TRIVY_ARM64_SHA256}" ;; \
        *) echo "Unsupported architecture for Trivy: $(dpkg --print-architecture)" >&2; exit 1 ;; \
       esac \
    && TRIVY_PACKAGE="trivy_${TRIVY_VERSION}_Linux-${TRIVY_RELEASE_ARCH}.deb" \
    && curl -fsSLo "/tmp/${TRIVY_PACKAGE}" \
        "https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}/${TRIVY_PACKAGE}" \
    && echo "${TRIVY_SHA256}  /tmp/${TRIVY_PACKAGE}" | sha256sum --check --strict \
    && apt-get install --yes --no-install-recommends "/tmp/${TRIVY_PACKAGE}" \
    && rm -f "/tmp/${TRIVY_PACKAGE}" \
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
