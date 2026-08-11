# RenewGuard Backend — image for Cloudflare Containers / Docker
# Build context: repository root
#   docker build -f Dockerfile -t renewguard-backend .
# Note: the agent web portal (formerly built here) now lives in the
# separate renewguard-web repo and is deployed independently.

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

ENV HOST=0.0.0.0
ENV PORT=8080
ENV DATA_DIR=/data
ENV SECURE_COOKIES=true

EXPOSE 8080
VOLUME ["/data"]

# installDist launcher name matches the Gradle project name
CMD ["/app/bin/backend"]
