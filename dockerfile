FROM eclipse-temurin:25-jre

WORKDIR /app

COPY target/media-migration-integrity-validation-1.0.0-SNAPSHOT.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]