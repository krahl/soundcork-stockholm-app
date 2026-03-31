FROM gradle:8.7.0-jdk21 AS builder

WORKDIR /app

COPY gradlew gradlew.bat build.gradle.kts ./
COPY gradle/ ./gradle/

COPY config/ ./config
COPY src/ ./src

RUN gradle :installDist --no-daemon

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=builder /app/build/install/app/ ./backend/
COPY config/ ./backend/config/
RUN mkdir -p ./backend/state

EXPOSE 8088

COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

ENTRYPOINT ["/app/docker-entrypoint.sh"]
