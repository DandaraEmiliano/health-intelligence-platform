# syntax=docker/dockerfile:1.7
# Build from the repository root: docker build -f bff/Dockerfile .
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /src
COPY bff/pom.xml bff/
RUN --mount=type=cache,target=/root/.m2 mvn -q -f bff/pom.xml dependency:go-offline
COPY bff/src bff/src
# Tests run in CI (mvn verify with Testcontainers); the image only packages.
RUN --mount=type=cache,target=/root/.m2 mvn -q -f bff/pom.xml package -DskipTests \
    && cp bff/target/bff-*.jar /app.jar

FROM eclipse-temurin:25-jre-alpine
RUN apk upgrade --no-cache && addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app
WORKDIR /app
COPY --from=build --chown=10001:10001 /app.jar app.jar
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=10 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
