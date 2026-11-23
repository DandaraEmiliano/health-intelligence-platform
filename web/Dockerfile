# syntax=docker/dockerfile:1.7
# Build from the repository root: docker build -f web/Dockerfile .
FROM node:22.20-alpine AS build
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY web/ ./
ARG VITE_API_BASE_URL=http://localhost:8080
ARG VITE_OIDC_AUTHORITY=http://localhost:8180/realms/hip
ARG VITE_OIDC_CLIENT_ID=hip-web
RUN npm run build

# Unprivileged nginx: listens on 8080 and runs as a non-root user.
FROM nginxinc/nginx-unprivileged:1.29-alpine
# Pick up security fixes released after the base image was built, then drop back to nginx (101).
USER root
RUN apk upgrade --no-cache
USER 101
COPY web/nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /web/dist /usr/share/nginx/html
ENV API_ORIGIN=http://localhost:8080 OIDC_ORIGIN=http://localhost:8180 NGINX_ENVSUBST_FILTER="^(API_ORIGIN|OIDC_ORIGIN)$"
EXPOSE 8080
