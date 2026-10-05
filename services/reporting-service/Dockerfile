FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/reporting-service.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
