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
# Base-image ARGs are declared here (global scope, before the first FROM) so
# they resolve in every FROM below. An ARG placed just before a later FROM is
# NOT reliably applied by BuildKit.
ARG DEPS_BASE=eclipse-temurin:17-jdk-jammy
ARG RUNTIME_BASE=dhi.io/eclipse-temurin:17-alpine3.23
# DHI Temurin Alpine (musl) — pin amd64; pin the runtime platform so this builds on
# arm64 hosts too (JARs are arch-independent; prod/GKE is amd64). Override with
# --build-arg RUNTIME_PLATFORM=linux/arm64 if an arm64 runtime is ever needed.
ARG RUNTIME_PLATFORM=linux/amd64
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
        https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/2.29.0/opentelemetry-javaagent-2.29.0.jar

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
# Runtime Stage — Docker Hardened Image (DHI)
# Eclipse Temurin 17 JRE, Alpine 3.23 (musl), distroless, nonroot (UID 65532).
# No shell, no package manager, no root → near-zero CVEs.
#
# ⚠️ RUNTIME_BASE: confirm the exact pullable reference from dhi.io via the
#    "Use this image" button. Requires `docker login dhi.io` where built.
#    (RUNTIME_BASE is declared as a global ARG at the top of this file.)
# =============================================================================
# glibc-compat shim so Kafka's glibc-linked snappy-java native lib
# (libsnappyjava.so needs ld-linux-x86-64.so.2) loads on Alpine/musl.
# Standard Alpine glibc-compat layer, COPY'd into the distroless runtime.
FROM alpine:3.23 AS glibc-compat
RUN apk add --no-cache gcompat

FROM --platform=${RUNTIME_PLATFORM} ${RUNTIME_BASE}
# glibc-compat (gcompat) so glibc-linked native libs (snappy-java) resolve on musl.
COPY --from=glibc-compat /lib/ld-linux-x86-64.so.2 /lib/libgcompat.so.0 /lib/libc.so.6 /lib/libcrypt.so.1 /lib/libm.so.6 /lib/libpthread.so.0 /lib/libresolv.so.2 /lib/librt.so.1 /lib/libutil.so.1 /lib/
COPY --from=glibc-compat /lib64/ld-linux-x86-64.so.2 /lib64/


WORKDIR /app

# DHI already runs as nonroot (65532) — no groupadd/useradd needed.
# Ownership + permissions are set via COPY flags because the distroless image
# has no shell to run chown/chmod/mkdir.
COPY --chown=65532:65532 --from=extractor /app/dependencies/ ./
COPY --chown=65532:65532 --from=extractor /app/spring-boot-loader/ ./
COPY --chown=65532:65532 --from=extractor /app/snapshot-dependencies/ ./
COPY --chown=65532:65532 --from=extractor /app/application/ ./
COPY --chown=65532:65532 --chmod=644 --from=builder /tmp/otel-javaagent.jar /app/opentelemetry-javaagent.jar

# JVM flags fold into JAVA_TOOL_OPTIONS (the JVM auto-applies it), so the
# entrypoint needs no shell to expand a $JAVA_OPTS variable.
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/opentelemetry-javaagent.jar -Xmx512m -Xms256m -XX:+UseG1GC -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

USER 65532
EXPOSE 8080

# Pure exec-form — no `sh -c`, since the distroless image has no /bin/sh.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
