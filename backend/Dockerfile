# Build from the repository root: docker build -f backend/Dockerfile .
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY android/gradlew android/gradlew
COPY android/gradle/wrapper android/gradle/wrapper
COPY backend/build.gradle.kts backend/settings.gradle.kts backend/
COPY backend/src backend/src
COPY core/build.gradle.kts core/build.gradle.kts
COPY core/src core/src
COPY catalog catalog
WORKDIR /workspace/backend
RUN sh ../android/gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --gid 10001 taptap && useradd --uid 10001 --gid taptap --create-home --shell /usr/sbin/nologin taptap
WORKDIR /app
COPY --from=build /workspace/backend/build/libs/taptap-backend.jar /app/taptap.jar
ENV SERVER_ADDRESS=0.0.0.0 PORT=8080
USER taptap:taptap
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/taptap.jar"]
