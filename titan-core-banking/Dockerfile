FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app

COPY gradle/ gradle/
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x ./gradlew

COPY src/ src/

ENV GRADLE_OPTS="-Dos.name=Linux -Dos.arch=x86_64 -Dos.detected.classifier=linux-x86_64"
RUN ./gradlew clean build -x test --no-daemon

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=builder /app/build/libs/titan-core-banking-*-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
