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

# Issue 5.8: the recorded ffmpeg version floor. Raise it when a Debian advisory requires it. Every build,
# the arm64 one included, fails below it; deploy/tests/worker-image-check.sh asserts it again in CI.
ARG FFMPEG_MIN_VERSION=7:7.1.5-0+deb13u1

RUN ffmpeg -version >/dev/null \
    && ffprobe -version >/dev/null \
    && java -version \
    && dpkg --compare-versions "$(dpkg-query -W -f='${Version}' ffmpeg)" ge "${FFMPEG_MIN_VERSION}" \
    && dpkg-query -W ffmpeg openjdk-21-jre-headless

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

# Issue 5.8: a fixed non-root runtime identity without a login shell or home. Compose runs the container
# with a read-only root and mounts the disk-backed worker_temp volume on /tmp, which then holds both
# WORKER_TEMP_DIR and the JVM's java.io.tmpdir. A new volume copies this /tmp (mode 1777 and the owned
# temp root) on first use.
RUN groupadd --system --gid 10001 vodworker \
    && useradd --system --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent \
        --shell /usr/sbin/nologin vodworker \
    && mkdir -p "${WORKER_TEMP_DIR}" \
    && chown 10001:10001 "${WORKER_TEMP_DIR}" \
    && chmod 0700 "${WORKER_TEMP_DIR}"

COPY --from=build /workspace/queue/target/queue-0.1.0-SNAPSHOT.jar /opt/worker/worker.jar

USER 10001:10001

EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD bash -c ": > /dev/tcp/127.0.0.1/${SERVER_PORT}" || exit 1

CMD ["java", "-jar", "/opt/worker/worker.jar"]
