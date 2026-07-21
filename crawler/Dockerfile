# =============================================================================
# Decentralized Catalog Crawler — build + run
# Two stages: Gradle build (with a cached dependency layer) → slim JRE runtime.
# Build context is the crawler/ directory (see docker-compose.crawler-poc.yml).
# =============================================================================
ARG DEPS_BASE=eclipse-temurin:17-jdk-jammy
FROM ${DEPS_BASE} AS builder

WORKDIR /app

# Step 1: build descriptors only — the dependency layer is cached until these change.
COPY build.gradle settings.gradle gradlew ./
COPY gradle/ gradle/
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

# Step 2: source — only layers below here rebuild on a code change.
COPY src/ src/
RUN ./gradlew bootJar -x test -x integrationTest --no-daemon

# =============================================================================
# Runtime
# =============================================================================
FROM eclipse-temurin:17-jre-jammy

RUN groupadd -r appuser && useradd -r -g appuser appuser
WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar
RUN mkdir -p /app/logs && chown -R appuser:appuser /app

USER appuser

# The crawler is a scheduled worker (no web server); memory kept modest for the POC.
ENV JAVA_OPTS="-Xmx384m -Xms128m -XX:+UseG1GC -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
