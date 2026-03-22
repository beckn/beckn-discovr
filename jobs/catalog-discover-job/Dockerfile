# =============================================================================
# Build Stage
# =============================================================================
FROM gradle:8.9-jdk17 AS builder

# Set working directory
WORKDIR /app/jobs/catalog-discover-job

COPY sdks/beckn-auth-java-sdk/ /app/sdks/beckn-auth-java-sdk/
# Copy gradle files first for better layer caching
COPY jobs/catalog-discover-job/build.gradle jobs/catalog-discover-job/settings.gradle jobs/catalog-discover-job/gradlew ./
COPY jobs/catalog-discover-job/gradle/ gradle/

# Copy source code
COPY jobs/catalog-discover-job/src/ src/

# Build the application
RUN gradle build -x test --no-daemon

# =============================================================================
# Runtime Stage
# =============================================================================
FROM eclipse-temurin:17-jre-jammy
# Install curl for healthcheck
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
# Create application user for security
RUN groupadd -r appuser && useradd -r -g appuser appuser
# Set working directory
WORKDIR /app
# Copy the built JAR from builder stage
COPY --from=builder /app/jobs/catalog-discover-job/build/libs/*.jar app.jar
# Download OpenTelemetry Java Agent using ADD (no curl needed)
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.11.0/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
# Create logs directory
RUN mkdir -p /app/logs && chmod 644 /app/opentelemetry-javaagent.jar && chown -R appuser:appuser /app
# Switch to non-root user
USER appuser
# JVM tuning for containers
ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseG1GC -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
# OpenTelemetry Java Agent
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/opentelemetry-javaagent.jar"

# Expose application port
EXPOSE 8080
# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
