FROM eclipse-temurin:25-jre

WORKDIR /app

COPY target/user-access-service.jar app.jar

EXPOSE 8083

ENTRYPOINT ["java", "-jar", "app.jar"]