FROM gradle:9.5.1-jdk21 AS builder

WORKDIR /app

COPY gradlew gradlew.bat build.gradle.kts ./
COPY gradle/ ./gradle/

COPY config/ ./config
COPY src/ ./src

RUN gradle :installDist --no-daemon

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN apt-get update && \
    apt-get install -y --no-install-recommends jq unzip npm patch && \
    rm -rf /var/lib/apt/lists/*

RUN npm install -g prettier@3.8.3 && \
    npm cache clean --force

COPY --from=builder /app/build/install/app/ ./backend/
COPY config/ ./backend/config/
COPY update-urls.sh ./
COPY stockholm-changes_v*.patch ./

RUN mkdir -p ./backend/state

EXPOSE 8088

COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh && \
    chmod +x /app/update-urls.sh

ENTRYPOINT ["/app/docker-entrypoint.sh"]
