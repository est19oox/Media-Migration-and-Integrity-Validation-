FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/integrity-service.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
