FROM gradle:8-jdk21 AS build
WORKDIR /app
COPY build.gradle settings.gradle* ./
COPY src ./src
ENV GRADLE_OPTS="-Dos.name=Linux -Dos.arch=x86_64 -Dos.detected.classifier=linux-x86_64"
RUN gradle clean build -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar
EXPOSE 8083
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8083/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
