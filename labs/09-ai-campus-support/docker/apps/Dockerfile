FROM eclipse-temurin:17-jre

ARG APP_JAR
WORKDIR /app

COPY ${APP_JAR} app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
