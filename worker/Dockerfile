FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY . .
RUN mvn -pl queue -am package -DskipTests

# ADR-010 D25: Debian trixie ffmpeg and OpenJDK 21 from trixie and trixie-security. The base is
# pinned by its multi-arch index digest; apt versions are deliberately not pinned (see D25).
FROM debian:trixie-slim@sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4 AS runtime-base

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg openjdk-21-jre-headless ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENV FFMPEG_PATH=ffmpeg
ENV FFPROBE_PATH=ffprobe

RUN ffmpeg -version >/dev/null \
    && ffprobe -version >/dev/null \
    && java -version

# #123: FFmpeg-tagged tests run in this stage, on the runtime ffmpeg/ffprobe layer above.
# Callers mount the worker sources; this stage is never deployed.
FROM runtime-base AS ffmpeg-test

RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-21-jdk-headless maven \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

FROM runtime-base

WORKDIR /opt/worker

ENV WORKER_CONCURRENCY=1
ENV WORKER_TEMP_DIR=/tmp/vod-worker
ENV SERVER_PORT=8081

RUN mkdir -p "${WORKER_TEMP_DIR}"

COPY --from=build /workspace/queue/target/queue-0.1.0-SNAPSHOT.jar /opt/worker/worker.jar

EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD bash -c ": > /dev/tcp/127.0.0.1/${SERVER_PORT}" || exit 1

CMD ["java", "-jar", "/opt/worker/worker.jar"]
