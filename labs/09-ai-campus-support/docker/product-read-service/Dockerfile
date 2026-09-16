FROM eclipse-temurin:17-jre

ARG APP_JAR
WORKDIR /app

RUN apt-get update \
    && apt-get install --no-install-recommends --yes curl \
    && rm -rf /var/lib/apt/lists/*

COPY ${APP_JAR} app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
