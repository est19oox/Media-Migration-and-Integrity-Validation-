FROM eclipse-temurin:25-jre
WORKDIR /app
COPY target/transfer-service.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
