# =============================================================================
# Build Stage
# =============================================================================
FROM gradle:8.9-jdk17 AS builder

# Set working directory
WORKDIR /app

# Copy gradle files first for better layer caching
COPY build.gradle settings.gradle gradlew ./
COPY gradle/ gradle/

# Copy source code
COPY src/ src/

# Build the application
RUN gradle build -x test --no-daemon

# =============================================================================
# Runtime Stage
# =============================================================================
FROM eclipse-temurin:17-jre-jammy
# Create application user for security
RUN groupadd -r appuser && useradd -r -g appuser appuser
# Set working directory
WORKDIR /app
# Copy the built JAR from builder stage
COPY --from=builder /app/build/libs/*.jar app.jar
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
