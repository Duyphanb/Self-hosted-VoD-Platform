FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY . .
RUN mvn -pl common -am package -DskipTests

FROM eclipse-temurin:21-jre-jammy

WORKDIR /opt/app

COPY --from=build /workspace/common/target/common-0.1.0-SNAPSHOT.jar /opt/app/backend.jar

ENV SERVER_PORT=8080

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD bash -c ": > /dev/tcp/127.0.0.1/${SERVER_PORT}" || exit 1

CMD ["java", "-jar", "/opt/app/backend.jar"]
