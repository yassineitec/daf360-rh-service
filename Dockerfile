# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -q
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
# See daf360-log-service/log/dockerfile: tzdata makes `date` agree with the JVM, and UTC is
# the deliberate default because per-entity zones come from pays / working_time_regimes.
RUN apk add --no-cache tzdata
ENV TZ=UTC
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8891
# `exec` keeps Java as PID 1 so docker stop's SIGTERM still reaches it.
ENTRYPOINT ["sh", "-c", "exec java -Duser.timezone=$TZ -jar app.jar"]
