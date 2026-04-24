# =============================================================================
# Build Stage
# build.gradle + SDK are copied BEFORE src/ so that Docker can cache the
# Gradle artifact-download layer independently from source changes.
# On a source-only rebuild the dep-resolution and OTel-download layers are
# served from cache — no Maven Central round-trips.
#
# DEPS_BASE: pre-built image with ~/.gradle/ populated for all jobs.
# When supplied, ./gradlew dependencies becomes a fast cache-check (~10 s).
# Falls back to plain eclipse-temurin for local builds without the base image.
# =============================================================================
ARG DEPS_BASE=eclipse-temurin:17-jdk-jammy
FROM ${DEPS_BASE} AS builder

WORKDIR /app/jobs/catalog-publish-job

# Step 1: SDK + build descriptor files only.
# Cache is invalidated only when build.gradle, settings.gradle, or the SDK change.
COPY sdks/beckn-auth-java-sdk/ /app/sdks/beckn-auth-java-sdk/
COPY jobs/catalog-publish-job/build.gradle jobs/catalog-publish-job/settings.gradle jobs/catalog-publish-job/gradlew ./
COPY jobs/catalog-publish-job/gradle/ gradle/
RUN chmod +x gradlew

# Step 2: Resolve and cache all runtime dependencies.
RUN ./gradlew dependencies --no-daemon

# Step 3: Download OTel agent from Maven Central.
# Placed before COPY src/ so code changes do not invalidate this layer.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && curl -sL -o /tmp/otel-javaagent.jar \
        https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/2.11.0/opentelemetry-javaagent-2.11.0.jar

# Step 4: Copy source — only layers below here are invalidated on code changes.
COPY jobs/catalog-publish-job/src/ src/

# Step 5: Build the layered JAR (layered.enabled = true in build.gradle).
RUN ./gradlew bootJar -x test -x integrationTest --no-daemon

# =============================================================================
# Layered JAR Extraction Stage
# =============================================================================
FROM eclipse-temurin:17-jre-jammy AS extractor
WORKDIR /app
COPY --from=builder /app/jobs/catalog-publish-job/build/libs/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# =============================================================================
# Runtime Stage
# =============================================================================
FROM eclipse-temurin:17-jre-jammy

RUN groupadd -r appuser && useradd -r -g appuser appuser
WORKDIR /app

COPY --from=extractor /app/dependencies/ ./
COPY --from=extractor /app/spring-boot-loader/ ./
COPY --from=extractor /app/snapshot-dependencies/ ./
COPY --from=extractor /app/application/ ./

COPY --from=builder /tmp/otel-javaagent.jar /app/opentelemetry-javaagent.jar

RUN mkdir -p /app/logs && \
    chmod 644 /app/opentelemetry-javaagent.jar && \
    chown -R appuser:appuser /app

USER appuser

ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseG1GC -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/opentelemetry-javaagent.jar"

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
