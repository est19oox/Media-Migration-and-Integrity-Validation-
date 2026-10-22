FROM eclipse-temurin:25-jre-alpine@sha256:3c0a9084927a221ccd1d007fcaf614465672c0af37aaa834c5184483afe56d61
RUN apk add --no-cache 'libexpat>=2.9.0-r0' openssl ca-certificates \
 && wget -q -O /tmp/rds-global-bundle.pem https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem \
 && awk '/-----BEGIN CERTIFICATE-----/{n++} n{print > ("/tmp/rds-ca-" n ".pem")}' /tmp/rds-global-bundle.pem \
 && for cert in /tmp/rds-ca-*.pem; do \
      keytool -importcert -noprompt -trustcacerts -alias "rds-$(basename "$cert" .pem)" \
        -file "$cert" -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit; \
    done \
 && rm -f /tmp/rds-global-bundle.pem /tmp/rds-ca-*.pem
WORKDIR /app
COPY target/reporting-service.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
