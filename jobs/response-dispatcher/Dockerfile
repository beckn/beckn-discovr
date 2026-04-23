# =============================================================================
# Build Stage
# build.gradle + SDK are copied BEFORE src/ so that Docker can cache the
# Gradle artifact-download layer independently from source changes.
# On a source-only rebuild the "gradlew dependencies" layer is served from
# cache — no Maven Central round-trips.
# =============================================================================
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /app/jobs/response-dispatcher

# Step 1: SDK + build descriptor files only.
# Cache is invalidated only when build.gradle, settings.gradle, or the SDK change.
COPY sdks/beckn-auth-java-sdk/ /app/sdks/beckn-auth-java-sdk/
COPY jobs/response-dispatcher/build.gradle jobs/response-dispatcher/settings.gradle jobs/response-dispatcher/gradlew ./
COPY jobs/response-dispatcher/gradle/ gradle/
RUN chmod +x gradlew

# Step 2: Resolve and cache all runtime dependencies.
RUN ./gradlew dependencies --no-daemon

# Step 3: Copy source — only layers below here are invalidated on code changes.
COPY jobs/response-dispatcher/src/ src/

# Step 4: Build the layered JAR (layered.enabled = true in build.gradle).
RUN ./gradlew bootJar -x test --no-daemon

# =============================================================================
# OTel Agent Download Stage
# Pinned version downloaded once and cached as its own Docker layer.
# =============================================================================
FROM alpine:3.19 AS otel-downloader
RUN apk add --no-cache wget && \
    wget -q -O /otel-javaagent.jar \
        https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.11.0/opentelemetry-javaagent.jar

# =============================================================================
# Layered JAR Extraction Stage
# =============================================================================
FROM eclipse-temurin:17-jre-alpine AS extractor
WORKDIR /app
COPY --from=builder /app/jobs/response-dispatcher/build/libs/seeker-notifier-1.0.0-SNAPSHOT.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# =============================================================================
# Runtime Stage
# Alpine JRE is ~160 MB smaller than Jammy, meaning faster registry pulls.
# =============================================================================
FROM eclipse-temurin:17-jre-alpine

RUN addgroup -S appuser && adduser -S -G appuser appuser
WORKDIR /app

COPY --from=extractor /app/dependencies/ ./
COPY --from=extractor /app/spring-boot-loader/ ./
COPY --from=extractor /app/snapshot-dependencies/ ./
COPY --from=extractor /app/application/ ./

COPY --from=otel-downloader /otel-javaagent.jar /app/opentelemetry-javaagent.jar

RUN mkdir -p /app/logs && \
    chmod 644 /app/opentelemetry-javaagent.jar && \
    chown -R appuser:appuser /app

USER appuser

ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseG1GC -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/opentelemetry-javaagent.jar"

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
