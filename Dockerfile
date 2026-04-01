FROM gradle:8.7.0-jdk21 AS builder

WORKDIR /app

COPY gradlew gradlew.bat build.gradle.kts ./
COPY gradle/ ./gradle/

COPY config/ ./config
COPY src/ ./src

RUN gradle :installDist --no-daemon

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN apt-get update && \
    apt-get install -y --no-install-recommends jq && \
    rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/build/install/app/ ./backend/
COPY config/ ./backend/config/
COPY update-urls.sh ./
RUN mkdir -p ./backend/state

EXPOSE 8088

COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh && \
    chmod +x /app/update-urls.sh

ENTRYPOINT ["/app/docker-entrypoint.sh"]
