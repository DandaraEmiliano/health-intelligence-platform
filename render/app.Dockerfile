# syntax=docker/dockerfile:1.7
# The Render profile's single service: core-api serving the SPA (docs/deploy/render.md).
# Build from the repository root: docker build -f render/app.Dockerfile .
FROM node:22.20-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY web/ ./
# Same origin, and the built-in sign-in form instead of Keycloak.
ENV VITE_API_BASE_URL= VITE_AUTH_MODE=local
RUN npm run build && rm -f dist/assets/*.map

FROM maven:3.9.11-eclipse-temurin-25 AS api
WORKDIR /src
COPY contracts/ contracts/
COPY core-api/pom.xml core-api/
RUN --mount=type=cache,target=/root/.m2 mvn -q -f core-api/pom.xml dependency:go-offline
COPY core-api/src core-api/src
# Tests run in CI (mvn verify with Testcontainers); the image only packages.
RUN --mount=type=cache,target=/root/.m2 mvn -q -f core-api/pom.xml package -DskipTests \
    && cp core-api/target/core-api-*.jar /app.jar

FROM eclipse-temurin:25-jre-alpine
RUN apk upgrade --no-cache && addgroup -S -g 10001 app && adduser -S -D -H -u 10001 -G app app
WORKDIR /app
COPY --from=api --chown=10001:10001 /app.jar app.jar
COPY --from=web --chown=10001:10001 /web/dist static/
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=render PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --retries=10 \
    CMD wget -q -O /dev/null http://127.0.0.1:${PORT}/actuator/health/readiness || exit 1
# 512 MB on the free plan: a small heap, the serial collector and a quick start (C1 only).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xss512k", \
    "-XX:ReservedCodeCacheSize=64m", "-XX:MaxMetaspaceSize=160m", "-Djava.security.egd=file:/dev/urandom", \
    "-jar", "/app/app.jar"]
