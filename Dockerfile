# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE_NAME
WORKDIR /workspace

ENV MAVEN_OPTS="-Xmx768m"

COPY pom.xml ./
COPY eureka-server/pom.xml eureka-server/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY ingest-static/pom.xml ingest-static/pom.xml
COPY ingest-dynamic/pom.xml ingest-dynamic/pom.xml
COPY retrieval-pre/pom.xml retrieval-pre/pom.xml
COPY retrieval-post/pom.xml retrieval-post/pom.xml

COPY . .

RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -pl "${MODULE_NAME}" -am package -DskipTests \
    && cp "${MODULE_NAME}/target/${MODULE_NAME}-"*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN apk add --no-cache curl \
    && addgroup -S edupulse \
    && adduser -S edupulse -G edupulse

COPY --from=build /workspace/app.jar /app/app.jar

USER edupulse
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
