FROM eclipse-temurin:21-jdk-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/worker

ENV FFMPEG_PATH=ffmpeg
ENV FFPROBE_PATH=ffprobe
ENV WORKER_CONCURRENCY=1
ENV WORKER_TEMP_DIR=/tmp/vod-worker

RUN ffmpeg -version >/dev/null && ffprobe -version >/dev/null

CMD ["sh", "-c", "echo 'VoD worker skeleton. Queue consumer and transcoding implementation are deferred.'; while true; do sleep 3600; done"]
