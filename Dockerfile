# RenewGuard — production image for Cloudflare Containers / Docker
# Build context: repository root
#   docker build -f Dockerfile -t renewguard .

FROM node:22-bookworm AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk-jammy AS backend
WORKDIR /src
COPY gradle gradle
COPY gradle.properties gradle.properties
COPY deploy/cloudflare/settings.docker.gradle.kts settings.gradle.kts
COPY deploy/cloudflare/build.docker.gradle.kts build.gradle.kts
COPY backend backend
# Repo ships only gradlew.bat, so drive the wrapper JAR directly on Linux.
RUN java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain \
    :backend:installDist --no-daemon

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN mkdir -p /data
COPY --from=backend /src/backend/build/install/backend/ /app/
COPY --from=web /web/dist /app/web-dist

ENV HOST=0.0.0.0
ENV PORT=8080
ENV DATA_DIR=/data
ENV WEB_DIST=/app/web-dist
ENV SECURE_COOKIES=true

EXPOSE 8080
VOLUME ["/data"]

# installDist launcher name matches the Gradle project name
CMD ["/app/bin/backend"]
