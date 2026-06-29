# Stage 1: Build on linux/amd64 (Render's build environment)
FROM --platform=linux/amd64 eclipse-temurin:21-jdk AS builder
WORKDIR /app

COPY gradle/ gradle/
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x ./gradlew

# Cache dependencies first
RUN ./gradlew dependencies --no-daemon 2>/dev/null || true

COPY src/ src/

ENV GRADLE_OPTS="-Dos.name=Linux -Dos.arch=x86_64 -Dos.detected.classifier=linux-x86_64"
RUN ./gradlew clean build -x test --no-daemon

# Stage 2: Minimal runtime image
FROM --platform=linux/amd64 eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=builder /app/build/libs/titan-core-banking-*-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-Xmx400m", \
  "-Xms200m", \
  "-Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:render}", \
  "-jar", "app.jar"]
