FROM eclipse-temurin:25-jre-alpine@sha256:3c0a9084927a221ccd1d007fcaf614465672c0af37aaa834c5184483afe56d61
RUN apk add --no-cache \
    'libexpat>=2.9.0-r0' \
    'brotli-libs>=1.2.1-r0'
WORKDIR /app
COPY target/transfer-service.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
