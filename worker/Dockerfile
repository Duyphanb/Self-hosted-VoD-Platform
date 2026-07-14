FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY . .
RUN mvn -pl queue -am package -DskipTests

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/worker

ENV FFMPEG_PATH=ffmpeg
ENV FFPROBE_PATH=ffprobe
ENV WORKER_CONCURRENCY=1
ENV WORKER_TEMP_DIR=/tmp/vod-worker
ENV SERVER_PORT=8081

RUN mkdir -p "${WORKER_TEMP_DIR}" \
    && ffmpeg -version >/dev/null \
    && ffprobe -version >/dev/null

COPY --from=build /workspace/queue/target/queue-0.1.0-SNAPSHOT.jar /opt/worker/worker.jar

EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD bash -c ": > /dev/tcp/127.0.0.1/${SERVER_PORT}" || exit 1

CMD ["java", "-jar", "/opt/worker/worker.jar"]
