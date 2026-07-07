FROM eclipse-temurin:21-jdk-jammy

WORKDIR /opt/app

RUN mkdir -p /opt/app/public/actuator /opt/app/public/api/v1 /opt/app/public/hls \
    && printf '{"status":"UP","service":"backend-skeleton"}\n' > /opt/app/public/actuator/health \
    && printf 'VoD backend skeleton. Business API implementation is deferred.\n' > /opt/app/public/api/v1/index.html \
    && printf '#EXTM3U\n# VoD HLS skeleton. Streaming implementation is deferred.\n' > /opt/app/public/hls/index.m3u8

ENV SERVER_PORT=8080

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD bash -c ": > /dev/tcp/127.0.0.1/${SERVER_PORT}" || exit 1

CMD ["sh", "-c", "jwebserver -b 0.0.0.0 -p ${SERVER_PORT} -d /opt/app/public"]
