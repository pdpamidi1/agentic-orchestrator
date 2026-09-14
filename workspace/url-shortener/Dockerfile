# syntax=docker/dockerfile:1.7
#
# url-shortener release image (T8, AC-15).
#
# Two stages:
#   1. build   - JDK 25 + the committed Maven wrapper produce the Spring Boot fat jar
#                (./mvnw -DskipTests package; tests run in CI, not in the image build).
#   2. runtime - slim JRE 25 image that only contains the fat jar and runs it as a
#                dedicated non-root user.
#
# No secrets or credentials live in this file or in the image. Database and Redis
# coordinates are injected at run time through environment variables (see README.md):
#   SHORTENER_DB_URL, SHORTENER_DB_USERNAME, SHORTENER_DB_PASSWORD,
#   SHORTENER_REDIS_HOST, SHORTENER_REDIS_PORT, SHORTENER_BASE_URL
#
# The deployment surface is selected with SPRING_PROFILES_ACTIVE:
#   default -> both surfaces (POST /api/v1/urls and GET /{short_code})
#   read    -> redirect surface only
#   write   -> creation surface only
#
#   docker build -t url-shortener .
#   docker run --rm -p 8080:8080 -e SPRING_PROFILES_ACTIVE=read url-shortener

# ---------------------------------------------------------------------------------------
# Stage 1: build
# ---------------------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

# Wrapper and build descriptor first so the dependency download layer is cached until
# pom.xml changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw \
    && ./mvnw -q -B -DskipTests dependency:resolve

# Sources last: only this layer is rebuilt on a code change.
COPY src src
RUN ./mvnw -q -B -DskipTests package \
    && mkdir -p /workspace/dist \
    && cp target/*.jar /workspace/dist/app.jar

# ---------------------------------------------------------------------------------------
# Stage 2: runtime
# ---------------------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine AS runtime

# Deployment surface; override at `docker run -e SPRING_PROFILES_ACTIVE=read|write`.
ARG SPRING_PROFILES_ACTIVE=default
ENV SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE} \
    SERVER_PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# Dedicated unprivileged user; nothing in the image is writable by it except /tmp.
RUN addgroup -S shortener \
    && adduser -S -G shortener -H -s /sbin/nologin shortener

WORKDIR /app
COPY --from=build --chown=root:root /workspace/dist/app.jar /app/app.jar

USER shortener:shortener

EXPOSE 8080

# Exec form through sh so JAVA_OPTS can be tuned per deployment; `exec` keeps the JVM as
# PID 1 so container signals reach it and shutdown is graceful.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
