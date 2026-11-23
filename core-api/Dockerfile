# syntax=docker/dockerfile:1.7
# Build from the repository root: docker build -f core-api/Dockerfile .
# (the build copies ../contracts into the jar, as in CI).
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /src
COPY contracts/ contracts/
COPY core-api/pom.xml core-api/
RUN --mount=type=cache,target=/root/.m2 mvn -q -f core-api/pom.xml dependency:go-offline
COPY core-api/src core-api/src
# Tests run in CI (mvn verify with Testcontainers); the image only packages.
RUN --mount=type=cache,target=/root/.m2 mvn -q -f core-api/pom.xml package -DskipTests \
    && cp core-api/target/core-api-*.jar /app.jar

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app
WORKDIR /app
COPY --from=build --chown=10001:10001 /app.jar app.jar
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=10 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.security.egd=file:/dev/urandom", "-jar", "/app/app.jar"]
