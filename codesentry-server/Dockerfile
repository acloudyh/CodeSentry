FROM maven:3.9.9-eclipse-temurin-11 AS builder

WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:11-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl git gnupg python3 python3-pip tar \
    && rm -rf /var/lib/apt/lists/*
COPY docker/install-scanners.sh /tmp/install-scanners.sh
RUN sh /tmp/install-scanners.sh && rm /tmp/install-scanners.sh
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-unifont \
    && rm -rf /var/lib/apt/lists/*
COPY docker/fonts/simsun.ttf /opt/codesentry/fonts/simsun.ttf
COPY docker/fonts/simhei.ttf /opt/codesentry/fonts/simhei.ttf

ENV TZ=Asia/Shanghai \
    JAVA_TOOL_OPTIONS="-Duser.timezone=Asia/Shanghai"

WORKDIR /app
COPY --from=builder /workspace/target/codesentry-server-0.1.0-SNAPSHOT.jar /app/codesentry-server.jar

EXPOSE 18080
ENTRYPOINT ["java", "-jar", "/app/codesentry-server.jar"]
