# =============================================================================
# Build Stage
# =============================================================================
FROM gradle:8.9-jdk17 AS builder

WORKDIR /app/jobs/catalog-publish-job

COPY sdks/beckn-auth-java-sdk/ /app/sdks/beckn-auth-java-sdk/
COPY jobs/catalog-publish-job/build.gradle jobs/catalog-publish-job/settings.gradle jobs/catalog-publish-job/gradlew ./
COPY jobs/catalog-publish-job/gradle/ gradle/
COPY jobs/catalog-publish-job/src/ src/

RUN gradle build -x test -x integrationTest --no-daemon

# =============================================================================
# Runtime Stage
# =============================================================================
FROM eclipse-temurin:17-jre-jammy

RUN groupadd -r appuser && useradd -r -g appuser appuser
WORKDIR /app

COPY --from=builder /app/jobs/catalog-publish-job/build/libs/*.jar app.jar
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.11.0/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar

RUN chmod 644 /app/opentelemetry-javaagent.jar && chown -R appuser:appuser /app
USER appuser

ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseG1GC -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/opentelemetry-javaagent.jar"

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
