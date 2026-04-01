FROM gradle:8-jdk21 AS build
WORKDIR /app
COPY titan-event-consumer-lib/ ./titan-event-consumer-lib/
RUN cd titan-event-consumer-lib && gradle publishToMavenLocal --no-daemon -q
COPY titan-notifications-service/build.gradle titan-notifications-service/settings.gradle ./titan-notifications-service/
COPY titan-notifications-service/src ./titan-notifications-service/src
RUN cd titan-notifications-service && gradle clean build -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/titan-notifications-service/build/libs/*-SNAPSHOT.jar app.jar
EXPOSE 8084
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8084/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
