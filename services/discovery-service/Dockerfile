FROM eclipse-temurin:25-jre-alpine@sha256:3c0a9084927a221ccd1d007fcaf614465672c0af37aaa834c5184483afe56d61
WORKDIR /app
COPY target/discovery-service.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
