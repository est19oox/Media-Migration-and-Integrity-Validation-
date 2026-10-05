FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/discovery-service.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
